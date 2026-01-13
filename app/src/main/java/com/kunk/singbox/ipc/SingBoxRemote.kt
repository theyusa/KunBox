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
import com.kunk.singbox.ipc.SingBoxIpcService
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * SingBoxRemote - IPC 客户端，用于与 SingBoxIpcService 通信
 *
 * 2025-fix: 参考 NekoBox 的 SagerConnection 实现，增加以下功能：
 * 1. Binder 死亡监听 (DeathRecipient) - 当服务进程崩溃时自动重连
 * 2. 自动重连机制 - 连接断开后自动尝试重连
 * 3. 状态保持 - 连接断开时不立即清空状态，而是检查系统 VPN 状态
 */
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"
    private const val RECONNECT_DELAY_MS = 500L
    private const val MAX_RECONNECT_ATTEMPTS = 3

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

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            val st = SingBoxService.ServiceState.values().getOrNull(state)
                ?: SingBoxService.ServiceState.STOPPED
            _state.value = st
            _isRunning.value = st == SingBoxService.ServiceState.RUNNING
            _isStarting.value = st == SingBoxService.ServiceState.STARTING
            _activeLabel.value = activeLabel.orEmpty()
            _lastError.value = lastError.orEmpty()
            _manuallyStopped.value = manuallyStopped
        }
    }

    /**
     * Binder 死亡监听器
     * 当服务进程崩溃或被杀死时触发，自动尝试重连
     */
    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder died, attempting to reconnect...")
            binder?.unlinkToDeath(this, 0)
            binder = null
            service = null
            bound = false

            // 尝试自动重连
            scheduleReconnect()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Service connected")
            this@SingBoxRemote.binder = binder
            reconnectAttempts = 0 // 重置重连计数

            // 注册 Binder 死亡监听
            runCatching {
                binder?.linkToDeath(deathRecipient, 0)
            }

            val s = ISingBoxService.Stub.asInterface(binder)
            service = s
            runCatching {
                val st = SingBoxService.ServiceState.values().getOrNull(s.state)
                    ?: SingBoxService.ServiceState.STOPPED
                _state.value = st
                _isRunning.value = st == SingBoxService.ServiceState.RUNNING
                _isStarting.value = st == SingBoxService.ServiceState.STARTING
                _activeLabel.value = s.activeLabel.orEmpty()
                _lastError.value = s.lastError.orEmpty()
                _manuallyStopped.value = s.isManuallyStopped
                s.registerCallback(callback)
                Log.i(TAG, "State synced: $st, running=${_isRunning.value}")
            }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            val s = service
            service = null
            bound = false
            runCatching { s?.unregisterCallback(callback) }

            // 2025-fix: 不立即将状态设为 STOPPED
            // 因为服务可能仍在运行，只是 IPC 连接断开了
            // 检查系统 VPN 状态来决定是否保持当前状态
            val ctx = contextRef?.get()
            if (ctx != null && hasSystemVpn(ctx)) {
                Log.i(TAG, "System VPN still active, keeping current state")
                // 保持当前状态，尝试重连
                scheduleReconnect()
            } else {
                // 没有系统 VPN，说明服务确实停止了
                _state.value = SingBoxService.ServiceState.STOPPED
                _isRunning.value = false
                _isStarting.value = false
            }
        }
    }

    /**
     * 检查系统是否有活跃的 VPN 连接
     */
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

    /**
     * 调度重连
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached, giving up")
            return
        }

        val ctx = contextRef?.get() ?: return
        reconnectAttempts++

        mainHandler.postDelayed({
            if (!bound && contextRef?.get() != null) {
                Log.i(TAG, "Attempting reconnect #$reconnectAttempts")
                doBindService(ctx)
            }
        }, RECONNECT_DELAY_MS * reconnectAttempts)
    }

    /**
     * 执行服务绑定
     */
    private fun doBindService(context: Context) {
        val intent = Intent(context, SingBoxIpcService::class.java)
        runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.e(TAG, "Failed to bind service", it)
        }
    }

    /**
     * 确保 IPC 已绑定
     * 如果已绑定则直接返回，否则尝试绑定
     */
    fun ensureBound(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        // 如果已绑定且服务可用，直接返回
        if (bound && service != null) {
            // 额外检查：尝试调用服务方法确认连接有效
            val isAlive = runCatching { service?.state }.isSuccess
            if (isAlive) return

            // 连接已失效，重置状态
            Log.w(TAG, "Service connection stale, rebinding...")
            bound = false
            service = null
        }

        doBindService(context)
    }

    /**
     * 强制重新绑定
     * 用于 Activity resume 时确保状态同步
     */
    fun rebind(context: Context) {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        // 如果已绑定，先检查连接是否有效
        if (bound && service != null) {
            val isAlive = runCatching {
                val st = service?.state
                // 同步状态
                if (st != null) {
                    val state = SingBoxService.ServiceState.values().getOrNull(st)
                        ?: SingBoxService.ServiceState.STOPPED
                    _state.value = state
                    _isRunning.value = state == SingBoxService.ServiceState.RUNNING
                    _isStarting.value = state == SingBoxService.ServiceState.STARTING
                    _activeLabel.value = service?.activeLabel.orEmpty()
                    _lastError.value = service?.lastError.orEmpty()
                    _manuallyStopped.value = service?.isManuallyStopped ?: false
                }
                true
            }.getOrDefault(false)

            if (isAlive) {
                Log.i(TAG, "Rebind: connection alive, state synced")
                return
            }
        }

        // 连接无效，重新绑定
        Log.i(TAG, "Rebind: connection invalid, rebinding...")
        bound = false
        service = null
        doBindService(context)
    }

    fun isBound(): Boolean = bound && service != null

    fun unbind(context: Context) {
        if (!bound) return
        val s = service
        service = null
        bound = false
        binder?.unlinkToDeath(deathRecipient, 0)
        binder = null
        runCatching { s?.unregisterCallback(callback) }
        runCatching { context.applicationContext.unbindService(conn) }
    }
}
