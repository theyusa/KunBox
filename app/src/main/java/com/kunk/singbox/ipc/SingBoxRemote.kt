package com.kunk.singbox.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * SingBoxRemote - IPC 客户端
 * 
 * 2025-fix-v5: 学习 NekoBox SagerConnection 的完整实现
 * 
 * 核心改进:
 * 1. connectionActive 标志位 - 防止重复绑定/解绑
 * 2. binderDied 立即重连 - 不使用延迟重试
 * 3. 前后台切换优化 - 支持连接优先级更新
 * 4. 连接有效性检测强化 - 主动断开 stale 连接后重连
 * 
 * 参考: NekoBox SagerConnection.kt
 * - https://github.com/MatsuriDayo/NekoBoxForAndroid/blob/main/app/src/main/java/io/nekohasekai/sagernet/bg/SagerConnection.kt
 */
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"
    private const val RECONNECT_DELAY_MS = 300L
    private const val MAX_RECONNECT_ATTEMPTS = 5

    private val _state = MutableStateFlow(SingBoxService.ServiceState.STOPPED)
    val state: StateFlow<SingBoxService.ServiceState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _activeLabel = MutableStateFlow("")
    val activeLabel: StateFlow<String> = _activeLabel.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _manuallyStopped = MutableStateFlow(false)
    val manuallyStopped: StateFlow<Boolean> = _manuallyStopped.asStateFlow()

    @Volatile
    private var service: ISingBoxService? = null

    // 2025-fix-v5: 使用 connectionActive 替代简单的 bound 标志
    // 参考 NekoBox SagerConnection: connectionActive 在 connect() 时设置，disconnect() 时清除
    @Volatile
    private var connectionActive = false

    @Volatile
    private var bound = false

    @Volatile
    private var callbackRegistered = false

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var lastSyncTimeMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            val st = SingBoxService.ServiceState.values().getOrNull(state)
                ?: SingBoxService.ServiceState.STOPPED
            updateState(st, activeLabel, lastError, manuallyStopped)
        }
    }

    private fun updateState(
        st: SingBoxService.ServiceState,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        _state.value = st
        _isRunning.value = st == SingBoxService.ServiceState.RUNNING
        _isStarting.value = st == SingBoxService.ServiceState.STARTING
        activeLabel?.let { _activeLabel.value = it }
        lastError?.let { _lastError.value = it }
        manuallyStopped?.let { _manuallyStopped.value = it }
        lastSyncTimeMs = System.currentTimeMillis()
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder died, performing NekoBox-style immediate reconnect")
            service = null
            callbackRegistered = false
            
            mainHandler.post {
                val ctx = contextRef?.get()
                if (ctx != null && !SagerConnection_restartingApp) {
                    disconnect(ctx)
                    connect(ctx)
                }
            }
        }
    }

    @Volatile
    private var SagerConnection_restartingApp = false

    private fun cleanupConnection() {
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
        callbackRegistered = false
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Service connected")
            this@SingBoxRemote.binder = binder
            reconnectAttempts = 0

            runCatching { binder?.linkToDeath(deathRecipient, 0) }

            val s = ISingBoxService.Stub.asInterface(binder)
            service = s
            bound = true

            if (s != null && !callbackRegistered) {
                runCatching {
                    s.registerCallback(callback)
                    callbackRegistered = true
                }
            }

            syncStateFromService(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            unregisterCallback()
            service = null
            bound = false

            val ctx = contextRef?.get()
            if (ctx != null && hasSystemVpn(ctx)) {
                Log.i(TAG, "System VPN still active, keeping current state and reconnecting")
                scheduleReconnect()
            } else {
                updateState(SingBoxService.ServiceState.STOPPED, "", "", false)
            }
        }
    }

    private fun unregisterCallback() {
        val s = service
        if (s != null && callbackRegistered) {
            runCatching { s.unregisterCallback(callback) }
        }
        callbackRegistered = false
    }

    private fun syncStateFromService(s: ISingBoxService?) {
        if (s == null) return
        runCatching {
            val st = SingBoxService.ServiceState.values().getOrNull(s.state)
                ?: SingBoxService.ServiceState.STOPPED
            updateState(st, s.activeLabel.orEmpty(), s.lastError.orEmpty(), s.isManuallyStopped)
            Log.i(TAG, "State synced: $st, running=${_isRunning.value}")
        }.onFailure {
            Log.e(TAG, "Failed to sync state from service", it)
        }
    }

    private fun hasSystemVpn(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system VPN", e)
            false
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }

        val ctx = contextRef?.get() ?: return
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts

        mainHandler.postDelayed({
            if (!bound && contextRef?.get() != null) {
                Log.i(TAG, "Reconnect attempt #$reconnectAttempts")
                doBindService(ctx)
            }
        }, delay)
    }

    private fun doBindService(context: Context) {
        val intent = Intent(context, SingBoxIpcService::class.java)
        runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.e(TAG, "Failed to bind service", it)
        }
    }

    fun connect(context: Context) {
        if (connectionActive) {
            Log.d(TAG, "connect: already active, skip")
            return
        }
        connectionActive = true
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0
        doBindService(context)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) {
            runCatching { context.applicationContext.unbindService(conn) }
        }
        connectionActive = false
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
    }

    fun ensureBound(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        if (connectionActive && bound && service != null) {
            val isAlive = runCatching { service?.state }.isSuccess
            if (isAlive) return

            Log.w(TAG, "Service connection stale, rebinding...")
        }

        if (!connectionActive) {
            connect(context)
        } else if (!bound || service == null) {
            disconnect(context)
            connect(context)
        }
    }

    /**
     * v2rayNG 风格: 主动查询并同步状态
     * 用于 Activity onResume 时确保 UI 与服务状态一致
     * 
     * 2025-fix-v5: 增强版 - 如果连接 stale 则强制重连
     */
    fun queryAndSyncState(context: Context): Boolean {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (connectionActive && bound && s != null) {
            val synced = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (synced) {
                Log.i(TAG, "queryAndSyncState: synced from service")
                return true
            } else {
                Log.w(TAG, "queryAndSyncState: sync failed, forcing reconnect")
                disconnect(context)
                connect(context)
                return false
            }
        }

        val ctx = contextRef?.get() ?: return false
        val hasVpn = hasSystemVpn(ctx)

        if (hasVpn && !connectionActive) {
            Log.i(TAG, "queryAndSyncState: system VPN active but not connected, connecting")
            connect(ctx)

            if (_state.value != SingBoxService.ServiceState.RUNNING) {
                updateState(SingBoxService.ServiceState.RUNNING)
            }
            return true
        }

        if (!hasVpn && _state.value == SingBoxService.ServiceState.RUNNING) {
            Log.i(TAG, "queryAndSyncState: no system VPN but state is RUNNING, correcting")
            updateState(SingBoxService.ServiceState.STOPPED)
        }

        if (!connectionActive) {
            connect(ctx)
        }

        return connectionActive
    }

    /**
     * NekoBox 风格: 强制重新绑定
     * 
     * Fix B: Doze 唤醒后强制重新注册 callback，确保 IPC 回调通道畅通
     */
    fun rebind(context: Context) {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (connectionActive && bound && s != null) {
            val isAlive = runCatching { s.state; true }.getOrDefault(false)

            if (isAlive) {
                runCatching {
                    if (callbackRegistered) {
                        s.unregisterCallback(callback)
                    }
                    s.registerCallback(callback)
                    callbackRegistered = true
                    syncStateFromService(s)
                    Log.i(TAG, "Rebind: callback re-registered, state synced")
                }.onFailure {
                    Log.w(TAG, "Rebind: re-register failed, forcing reconnect", it)
                    disconnect(context)
                    connect(context)
                }
                return
            }
        }

        Log.i(TAG, "Rebind: connection invalid, forcing reconnect")
        disconnect(context)
        connect(context)
    }

    fun isBound(): Boolean = connectionActive && bound && service != null

    fun isConnectionActive(): Boolean = connectionActive

    fun unbind(context: Context) {
        disconnect(context)
    }

    fun getLastSyncAge(): Long = System.currentTimeMillis() - lastSyncTimeMs
}
