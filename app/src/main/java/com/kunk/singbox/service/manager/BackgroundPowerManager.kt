package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台省电管理器
 * 
 * 功能:
 * 1. 检测应用进入后台超过指定时间后，关闭非核心进程以节省资源
 * 2. 用户返回前台时立即恢复所有进程
 * 
 * 设计理念:
 * - VPN 核心连接必须始终保持 (BoxService, TUN 接口)
 * - 非核心进程 (日志收集、连接追踪、流量监控) 可以暂停
 * - 健康检查可以降低频率而非完全停止
 */
class BackgroundPowerManager(
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "BackgroundPowerManager"

        /** 默认后台省电阈值: 30 分钟 */
        const val DEFAULT_BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L

        /** 最小阈值: 5 分钟 (防止过于激进) */
        const val MIN_THRESHOLD_MS = 5 * 60 * 1000L

        /** 最大阈值: 2 小时 */
        const val MAX_THRESHOLD_MS = 2 * 60 * 60 * 1000L
    }

    /**
     * 省电模式状态
     */
    enum class PowerMode {
        /** 正常模式 - 所有进程运行 */
        NORMAL,
        /** 省电模式 - 非核心进程已暂停 */
        POWER_SAVING
    }

    /**
     * 回调接口 - 由 SingBoxService 实现
     */
    interface Callbacks {
        /** VPN 是否正在运行 */
        val isVpnRunning: Boolean

        /** 暂停非核心进程 (进入省电模式) */
        fun suspendNonEssentialProcesses()

        /** 恢复非核心进程 (退出省电模式) */
        fun resumeNonEssentialProcesses()
    }

    private var callbacks: Callbacks? = null
    private var backgroundThresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS
    private var powerSavingJob: Job? = null

    @Volatile
    private var currentMode: PowerMode = PowerMode.NORMAL

    @Volatile
    private var lastBackgroundAtMs: Long = 0L

    @Volatile
    private var isInBackground: Boolean = false

    /**
     * 当前省电模式
     */
    val powerMode: PowerMode get() = currentMode

    /**
     * 是否处于省电模式
     */
    val isPowerSaving: Boolean get() = currentMode == PowerMode.POWER_SAVING

    /**
     * 初始化管理器
     */
    fun init(callbacks: Callbacks, thresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS) {
        this.callbacks = callbacks
        this.backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) "NEVER" else "${backgroundThresholdMs / 1000 / 60}min"
        Log.i(TAG, "BackgroundPowerManager initialized (threshold=$thresholdDisplay)")
    }

    /**
     * 更新后台省电阈值
     */
    fun setThreshold(thresholdMs: Long) {
        backgroundThresholdMs = thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        Log.i(TAG, "Threshold updated to ${backgroundThresholdMs / 1000 / 60}min")

        // 如果已经在后台，重新计算定时器
        if (isInBackground && currentMode == PowerMode.NORMAL) {
            schedulePowerSavingCheck()
        }
    }

    /**
     * 应用进入后台时调用
     */
    fun onAppBackground() {
        if (isInBackground) return
        if (backgroundThresholdMs == Long.MAX_VALUE) {
            Log.i(TAG, "App entered background, but power saving is disabled (threshold=NEVER)")
            return
        }

        isInBackground = true
        lastBackgroundAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "App entered background, scheduling power saving check in ${backgroundThresholdMs / 1000 / 60}min")

        schedulePowerSavingCheck()
    }

    /**
     * 应用返回前台时调用
     */
    fun onAppForeground() {
        if (!isInBackground) return

        isInBackground = false
        val wasInBackground = SystemClock.elapsedRealtime() - lastBackgroundAtMs
        Log.i(TAG, "App returned to foreground after ${wasInBackground / 1000}s")

        // 取消待执行的省电检查
        powerSavingJob?.cancel()
        powerSavingJob = null

        // 如果处于省电模式，立即恢复
        if (currentMode == PowerMode.POWER_SAVING) {
            exitPowerSavingMode()
        }
    }

    /**
     * 调度省电检查
     */
    private fun schedulePowerSavingCheck() {
        powerSavingJob?.cancel()

        powerSavingJob = serviceScope.launch(Dispatchers.Main) {
            val alreadyInBackground = SystemClock.elapsedRealtime() - lastBackgroundAtMs
            val remainingDelay = (backgroundThresholdMs - alreadyInBackground).coerceAtLeast(0L)

            if (remainingDelay > 0) {
                Log.d(TAG, "Waiting ${remainingDelay / 1000}s before entering power saving mode")
                delay(remainingDelay)
            }

            // 再次检查是否仍在后台
            if (isInBackground && callbacks?.isVpnRunning == true) {
                enterPowerSavingMode()
            }
        }
    }

    /**
     * 进入省电模式
     */
    private fun enterPowerSavingMode() {
        if (currentMode == PowerMode.POWER_SAVING) return

        Log.i(TAG, ">>> Entering POWER_SAVING mode - suspending non-essential processes")
        currentMode = PowerMode.POWER_SAVING

        try {
            callbacks?.suspendNonEssentialProcesses()
            Log.i(TAG, "Non-essential processes suspended successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend non-essential processes", e)
        }
    }

    /**
     * 退出省电模式
     */
    private fun exitPowerSavingMode() {
        if (currentMode == PowerMode.NORMAL) return

        Log.i(TAG, "<<< Exiting POWER_SAVING mode - resuming all processes")
        currentMode = PowerMode.NORMAL

        try {
            callbacks?.resumeNonEssentialProcesses()
            Log.i(TAG, "All processes resumed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume processes", e)
        }
    }

    /**
     * 强制进入省电模式 (用于测试或手动触发)
     */
    fun forceEnterPowerSaving() {
        if (callbacks?.isVpnRunning == true) {
            enterPowerSavingMode()
        }
    }

    /**
     * 强制退出省电模式
     */
    fun forceExitPowerSaving() {
        exitPowerSavingMode()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        powerSavingJob?.cancel()
        powerSavingJob = null
        currentMode = PowerMode.NORMAL
        isInBackground = false
        callbacks = null
        Log.i(TAG, "BackgroundPowerManager cleaned up")
    }

    /**
     * 获取统计信息 (用于调试)
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "currentMode" to currentMode.name,
            "isInBackground" to isInBackground,
            "thresholdMin" to (backgroundThresholdMs / 1000 / 60),
            "backgroundDurationSec" to if (isInBackground) {
                (SystemClock.elapsedRealtime() - lastBackgroundAtMs) / 1000
            } else 0L
        )
    }
}
