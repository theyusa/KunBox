package com.kunk.singbox.ipc

import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import java.util.concurrent.atomic.AtomicLong

object SingBoxIpcHub {
    private const val TAG = "SingBoxIpcHub"

    private val logRepo by lazy { LogRepository.getInstance() }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logRepo.addLog("INFO [IPC] $msg")
    }

    @Volatile
    private var stateOrdinal: Int = ServiceState.STOPPED.ordinal

    @Volatile
    private var activeLabel: String = ""

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var manuallyStopped: Boolean = false

    private val callbacks = RemoteCallbackList<ISingBoxServiceCallback>()

    private fun getStateName(ordinal: Int): String =
        ServiceState.values().getOrNull(ordinal)?.name ?: "UNKNOWN"

    private val broadcastLock = Any()
    @Volatile private var broadcasting: Boolean = false
    @Volatile private var broadcastPending: Boolean = false

    // 省电管理器引用，由 SingBoxService 设置
    @Volatile
    private var powerManager: BackgroundPowerManager? = null

    // Foreground recovery handler (set by SingBoxService) to avoid calling libbox concurrently.
    @Volatile
    private var foregroundRecoveryHandler: (() -> Unit)? = null

    // 2025-fix-v6: 状态更新时间戳，用于检测回调通道是否正常
    private val lastStateUpdateAtMs = AtomicLong(0L)

    // 2025-fix-v7: 上次应用返回前台的时间戳，用于防抖
    private val lastForegroundAtMs = AtomicLong(0L)

    // 2025-fix-v11: 上次应用进入后台的时间戳，用于计算后台时长
    private val lastBackgroundAtMs = AtomicLong(0L)

    // 2025-fix-v11: 前台恢复防抖最小间隔 (2秒)
    private const val FOREGROUND_RESET_DEBOUNCE_MS = 2_000L

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    fun setForegroundRecoveryHandler(handler: (() -> Unit)?) {
        foregroundRecoveryHandler = handler
        Log.d(TAG, "ForegroundRecoveryHandler ${if (handler != null) "set" else "cleared"}")
    }

    /**
     * 接收主进程的 App 生命周期通知
     *
     * 2025-fix-v14: 使用 NetworkBump 统一恢复方案
     * 通过短暂改变底层网络设置触发应用重建连接，解决 TG 等应用卡住问题
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    fun onAppLifecycle(isForeground: Boolean) {
        val vpnState = ServiceState.values().getOrNull(stateOrdinal)?.name ?: "UNKNOWN"
        log("onAppLifecycle: isForeground=$isForeground, vpnState=$vpnState")

        if (isForeground) {
            powerManager?.onAppForeground()
            performForegroundRecovery()
        } else {
            lastBackgroundAtMs.set(SystemClock.elapsedRealtime())
            powerManager?.onAppBackground()
        }
    }

    /**
     * 2025-fix-v14: 前台恢复 - 使用 NetworkBump 触发应用重建连接
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun performForegroundRecovery() {
        val isVpnRunning = stateOrdinal == ServiceState.RUNNING.ordinal
        if (!isVpnRunning) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val lastForeground = lastForegroundAtMs.get()
        val timeSinceLastForeground = now - lastForeground

        if (timeSinceLastForeground < FOREGROUND_RESET_DEBOUNCE_MS) {
            Log.d(TAG, "[Foreground] skipped (debounce, elapsed=${timeSinceLastForeground}ms)")
            return
        }

        val backgroundDuration = now - lastBackgroundAtMs.get()

        // 2025-fix-v14: 如果从未进入过后台或后台时间太短，跳过恢复
        if (lastBackgroundAtMs.get() == 0L || backgroundDuration < FOREGROUND_RESET_DEBOUNCE_MS) {
            Log.d(TAG, "[Foreground] skipped (background too short: ${backgroundDuration}ms)")
            return
        }

        log("[Foreground] VPN running, background=${backgroundDuration}ms, triggering NetworkBump")

        // 2025-fix-v14: 使用 NetworkBump 替代分级恢复模式
        // NetworkBump 通过短暂改变底层网络触发应用重建连接，是根治方案
        val handler = foregroundRecoveryHandler
        if (handler != null) {
            handler.invoke()
            lastForegroundAtMs.set(now)
            log("[Foreground] NetworkBump requested via handler")
        } else {
            // Fallback: 直接调用 BoxWrapperManager（不触发应用重建连接，但至少重置 sing-box 内部状态）
            Log.w(TAG, "[Foreground] foregroundRecoveryHandler not set, using fallback")
            BoxWrapperManager.resetAllConnections(true)
            lastForegroundAtMs.set(now)
        }
    }

    fun getStateOrdinal(): Int = stateOrdinal

    fun getActiveLabel(): String = activeLabel

    fun getLastError(): String = lastError

    fun isManuallyStopped(): Boolean = manuallyStopped

    /**
     * 2025-fix-v6: 获取上次状态更新时间戳
     */
    fun getLastStateUpdateTime(): Long = lastStateUpdateAtMs.get()

    fun update(
        state: ServiceState? = null,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        var shouldStartBroadcast = false
        val updateStart = SystemClock.elapsedRealtime()
        synchronized(broadcastLock) {
            state?.let {
                val oldState = ServiceState.values().getOrNull(stateOrdinal)?.name ?: "UNKNOWN"
                stateOrdinal = it.ordinal
                log("state update: $oldState -> ${it.name}")
                // 2025-fix-v6: 同步状态到 VpnStateStore (跨进程持久化)
                // 这确保主进程恢复时可以直接读取真实状态，不依赖回调
                VpnStateStore.setActive(it == ServiceState.RUNNING)
            }
            activeLabel?.let {
                this.activeLabel = it
                // 2025-fix-v6: 同步 activeLabel 到 VpnStateStore
                VpnStateStore.setActiveLabel(it)
            }
            lastError?.let {
                this.lastError = it
                VpnStateStore.setLastError(it)
            }
            manuallyStopped?.let {
                this.manuallyStopped = it
                VpnStateStore.setManuallyStopped(it)
            }

            // 更新时间戳
            lastStateUpdateAtMs.set(SystemClock.elapsedRealtime())

            if (broadcasting) {
                broadcastPending = true
            } else {
                broadcasting = true
                shouldStartBroadcast = true
            }
        }

        if (shouldStartBroadcast) {
            drainBroadcastLoop()
        }
        Log.d(TAG, "[IPC] update completed in ${SystemClock.elapsedRealtime() - updateStart}ms")
    }

    fun registerCallback(callback: ISingBoxServiceCallback) {
        callbacks.register(callback)
        runCatching {
            callback.onStateChanged(stateOrdinal, activeLabel, lastError, manuallyStopped)
        }
    }

    fun unregisterCallback(callback: ISingBoxServiceCallback) {
        callbacks.unregister(callback)
    }

    private fun drainBroadcastLoop() {
        while (true) {
            val snapshot = synchronized(broadcastLock) {
                broadcastPending = false
                StateSnapshot(stateOrdinal, activeLabel, lastError, manuallyStopped)
            }

            val n = callbacks.beginBroadcast()
            Log.d(
                TAG,
                "[IPC] broadcasting to $n callbacks, state=${getStateName(snapshot.stateOrdinal)}"
            )
            try {
                for (i in 0 until n) {
                    runCatching {
                        callbacks.getBroadcastItem(i)
                            .onStateChanged(snapshot.stateOrdinal, snapshot.activeLabel, snapshot.lastError, snapshot.manuallyStopped)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }

            val shouldContinue = synchronized(broadcastLock) {
                if (broadcastPending) {
                    true
                } else {
                    broadcasting = false
                    false
                }
            }

            if (!shouldContinue) return
        }
    }

    private data class StateSnapshot(
        val stateOrdinal: Int,
        val activeLabel: String,
        val lastError: String,
        val manuallyStopped: Boolean
    )

    /**
     * 热重载结果码
     */
    object HotReloadResult {
        const val SUCCESS = 0
        const val VPN_NOT_RUNNING = 1
        const val KERNEL_ERROR = 2
        const val UNKNOWN_ERROR = 3
    }

    /**
     * 内核级热重载配置
     * 通过 ServiceStateHolder.instance 访问 SingBoxService
     * 直接调用 Go 层 StartOrReloadService，不销毁 VPN 服务
     *
     * @param configContent 新的配置内容 (JSON)
     * @return 热重载结果码 (HotReloadResult)
     */
    fun hotReloadConfig(configContent: String): Int {
        log("[HotReload] IPC request received")

        // 检查 VPN 是否运行
        if (stateOrdinal != ServiceState.RUNNING.ordinal) {
            Log.w(TAG, "[HotReload] VPN not running, state=$stateOrdinal")
            return HotReloadResult.VPN_NOT_RUNNING
        }

        // 获取 SingBoxService 实例
        val service = ServiceStateHolder.instance
        if (service == null) {
            Log.e(TAG, "[HotReload] SingBoxService instance is null")
            return HotReloadResult.VPN_NOT_RUNNING
        }

        // 调用 Service 的热重载方法
        return try {
            val result = service.performHotReloadSync(configContent)
            if (result) {
                log("[HotReload] Success")
                HotReloadResult.SUCCESS
            } else {
                Log.e(TAG, "[HotReload] Kernel returned false")
                HotReloadResult.KERNEL_ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HotReload] Exception: ${e.message}", e)
            HotReloadResult.UNKNOWN_ERROR
        }
    }
}
