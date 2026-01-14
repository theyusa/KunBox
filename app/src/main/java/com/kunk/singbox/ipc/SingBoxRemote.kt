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
 * 2025-fix-v4: 综合 NekoBox + v2rayNG 的最佳实践
 * - NekoBox: DeathRecipient + 自动重连
 * - v2rayNG: 主动查询机制 (MSG_REGISTER_CLIENT)
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

    @Volatile
    private var bound = false

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
            Log.w(TAG, "Binder died, attempting to reconnect...")
            mainHandler.post {
                cleanupConnection()
                scheduleReconnect()
            }
        }
    }

    private fun cleanupConnection() {
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
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

            syncStateFromService(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            val s = service
            service = null
            bound = false
            runCatching { s?.unregisterCallback(callback) }

            val ctx = contextRef?.get()
            if (ctx != null && hasSystemVpn(ctx)) {
                Log.i(TAG, "System VPN still active, keeping current state and reconnecting")
                scheduleReconnect()
            } else {
                updateState(SingBoxService.ServiceState.STOPPED, "", "", false)
            }
        }
    }

    private fun syncStateFromService(s: ISingBoxService) {
        runCatching {
            val st = SingBoxService.ServiceState.values().getOrNull(s.state)
                ?: SingBoxService.ServiceState.STOPPED
            updateState(st, s.activeLabel.orEmpty(), s.lastError.orEmpty(), s.isManuallyStopped)
            s.registerCallback(callback)
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

    fun ensureBound(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        if (bound && service != null) {
            val isAlive = runCatching { service?.state }.isSuccess
            if (isAlive) return

            Log.w(TAG, "Service connection stale, rebinding...")
            cleanupConnection()
        }

        doBindService(context)
    }

    /**
     * v2rayNG 风格: 主动查询并同步状态
     * 用于 Activity onResume 时确保 UI 与服务状态一致
     */
    fun queryAndSyncState(context: Context): Boolean {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (bound && s != null) {
            val synced = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (synced) {
                Log.i(TAG, "queryAndSyncState: synced from service")
                return true
            }
        }

        val ctx = contextRef?.get() ?: return false
        val hasVpn = hasSystemVpn(ctx)

        if (hasVpn && !bound) {
            Log.i(TAG, "queryAndSyncState: system VPN active but not bound, reconnecting")
            doBindService(ctx)

            if (_state.value != SingBoxService.ServiceState.RUNNING) {
                updateState(SingBoxService.ServiceState.RUNNING)
            }
            return true
        }

        if (!hasVpn && _state.value == SingBoxService.ServiceState.RUNNING) {
            Log.i(TAG, "queryAndSyncState: no system VPN but state is RUNNING, correcting")
            updateState(SingBoxService.ServiceState.STOPPED)
        }

        if (!bound) {
            doBindService(ctx)
        }

        return bound
    }

    /**
     * NekoBox 风格: 强制重新绑定
     */
    fun rebind(context: Context) {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        if (bound && service != null) {
            val isAlive = runCatching {
                syncStateFromService(service!!)
                true
            }.getOrDefault(false)

            if (isAlive) {
                Log.i(TAG, "Rebind: connection alive, state synced")
                return
            }
        }

        Log.i(TAG, "Rebind: connection invalid, rebinding...")
        cleanupConnection()
        doBindService(context)
    }

    fun isBound(): Boolean = bound && service != null

    fun unbind(context: Context) {
        if (!bound) return
        val s = service
        cleanupConnection()
        runCatching { s?.unregisterCallback(callback) }
        runCatching { context.applicationContext.unbindService(conn) }
    }

    fun getLastSyncAge(): Long = System.currentTimeMillis() - lastSyncTimeMs
}
