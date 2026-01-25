package com.kunk.singbox.ipc

import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import java.util.concurrent.atomic.AtomicLong

object SingBoxIpcHub {
    private const val TAG = "SingBoxIpcHub"

    @Volatile
    private var stateOrdinal: Int = SingBoxService.ServiceState.STOPPED.ordinal

    @Volatile
    private var activeLabel: String = ""

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var manuallyStopped: Boolean = false

    private val callbacks = RemoteCallbackList<ISingBoxServiceCallback>()

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

    // 2025-fix-v7: 前台恢复后重置连接的最小间隔 (5秒)
    private const val FOREGROUND_RESET_DEBOUNCE_MS = 5_000L

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
     * 2025-fix-v7: 当应用返回前台时，立即重置所有连接
     * 这是解决 "后台恢复后 TG 等应用一直加载中" 问题的关键修复
     *
     * 2025-fix-v9: 同时清理闲置连接，解决 "TG 图片加载慢" 问题
     *
     * 参考 NekoBox: 在 onServiceConnected() 时无条件调用 resetAllConnections()
     */
    fun onAppLifecycle(isForeground: Boolean) {
        Log.i(TAG, "onAppLifecycle: isForeground=$isForeground")

        if (isForeground) {
            powerManager?.onAppForeground()

            // 2025-fix-v7 + v9: 应用返回前台时，网络恢复 + 清理闲置连接
            // 这确保 sing-box 内核不会使用后台期间可能已失效的连接
            val isVpnRunning = stateOrdinal == SingBoxService.ServiceState.RUNNING.ordinal
            if (isVpnRunning) {
                val now = SystemClock.elapsedRealtime()
                val lastForeground = lastForegroundAtMs.get()
                val elapsed = now - lastForeground

                // 防抖: 避免频繁切换前后台时重复重置
                if (elapsed >= FOREGROUND_RESET_DEBOUNCE_MS) {
                    lastForegroundAtMs.set(now)

                    Log.i(TAG, "[Foreground] VPN running, triggering network recovery")
                    val handler = foregroundRecoveryHandler
                    if (handler != null) {
                        runCatching { handler.invoke() }
                            .onFailure { e -> Log.w(TAG, "[Foreground] recovery handler failed", e) }
                    } else {
                        // Fallback for early startup or tests.
                        // 2025-fix-v8: Use recoverNetworkAuto which calls CloseAllTrackedConnections
                        // This properly sends RST/FIN to apps, fixing "TG stuck loading" issue
                        val success = BoxWrapperManager.recoverNetworkAuto()
                        if (success) {
                            Log.i(TAG, "[Foreground] recoverNetworkAuto success")
                        } else {
                            Log.w(TAG, "[Foreground] recoverNetworkAuto failed, VPN may have stale connections")
                        }

                        // 2025-fix-v9: Also close idle connections to fix "TG image loading slow"
                        // Close connections idle for more than 60 seconds
                        val closedIdle = BoxWrapperManager.closeIdleConnections(60)
                        if (closedIdle > 0) {
                            Log.i(TAG, "[Foreground] closeIdleConnections: closed $closedIdle idle connections")
                        }
                    }
                } else {
                    Log.d(TAG, "[Foreground] skipped reset (debounce, elapsed=${elapsed}ms)")
                }
            }
        } else {
            powerManager?.onAppBackground()
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
        state: SingBoxService.ServiceState? = null,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        var shouldStartBroadcast = false
        synchronized(broadcastLock) {
            state?.let {
                stateOrdinal = it.ordinal
                // 2025-fix-v6: 同步状态到 VpnStateStore (跨进程持久化)
                // 这确保主进程恢复时可以直接读取真实状态，不依赖回调
                VpnStateStore.setActive(it == SingBoxService.ServiceState.RUNNING)
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
        Log.i(TAG, "[HotReload] IPC request received")

        // 检查 VPN 是否运行
        if (stateOrdinal != SingBoxService.ServiceState.RUNNING.ordinal) {
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
                Log.i(TAG, "[HotReload] Success")
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
