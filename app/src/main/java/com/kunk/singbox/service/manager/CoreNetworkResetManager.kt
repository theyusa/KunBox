package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 核心网络重置管理器
 * 负责管理网络栈重置逻辑，包括：
 * - 防抖控制
 * - 失败计数和自动重启
 * - 连接关闭和网络重置
 */
class CoreNetworkResetManager(
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CoreNetworkResetManager"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val FAILURE_TIMEOUT_MS = 30000L

        // Avoid restart storms when multiple triggers keep failing.
        private const val RESTART_COOLDOWN_MS = 120000L
    }

    interface Callbacks {
        fun isServiceRunning(): Boolean
        suspend fun restartVpnService(reason: String)
    }

    private var callbacks: Callbacks? = null

    private val lastResetAtMs = AtomicLong(0L)
    private val lastSuccessfulResetAtMs = AtomicLong(0L)
    private val failureCounter = AtomicInteger(0)
    private val lastRestartAtMs = AtomicLong(0L)
    private var resetJob: Job? = null

    var debounceMs: Long = 500L

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * Execute a reset immediately (no delayed scheduling).
     *
     * This is intended for callers that already coalesce/serialize recovery operations.
     */
    suspend fun resetNow(reason: String, force: Boolean = false, skipIntervalCheck: Boolean = false) {
        val now = SystemClock.elapsedRealtime()

        // Check whether we should restart due to repeated failures.
        val lastSuccess = lastSuccessfulResetAtMs.get()
        val hasEverSucceeded = lastSuccess > 0L
        val timeSinceLastSuccess = if (hasEverSucceeded) now - lastSuccess else 0L
        val failures = failureCounter.get()

        if (failures >= MAX_CONSECUTIVE_FAILURES && hasEverSucceeded && timeSinceLastSuccess > FAILURE_TIMEOUT_MS) {
            val lastRestart = lastRestartAtMs.get()
            if (now - lastRestart >= RESTART_COOLDOWN_MS && callbacks?.isServiceRunning() == true) {
                lastRestartAtMs.set(now)
                Log.w(TAG, "Too many reset failures ($failures), requesting service restart")
                callbacks?.restartVpnService("Excessive network reset failures")
            } else {
                Log.w(TAG, "Too many reset failures ($failures) but restart is in cooldown or service not running")
            }
            return
        }

        if (callbacks?.isServiceRunning() != true) {
            Log.w(TAG, "Service not running, skip resetNow")
            return
        }

        if (!skipIntervalCheck) {
            val last = lastResetAtMs.get()
            val minInterval = if (force) 100L else debounceMs
            if (now - last < minInterval) {
                return
            }
        }

        // NOTE: Do not cancel resetJob here.
        // requestReset() may call resetNow() from inside resetJob itself (delayed path).
        // Callers that need cancellation should invoke cancelPendingReset() before resetNow().
        lastResetAtMs.set(now)

        if (force) {
            performForceReset(reason)
        } else {
            performReset(reason)
        }
    }

    /**
     * 请求核心网络重置
     */
    fun requestReset(reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = lastResetAtMs.get()

        // 检查是否需要完全重启
        val lastSuccess = lastSuccessfulResetAtMs.get()
        val hasEverSucceeded = lastSuccess > 0L
        val timeSinceLastSuccess = if (hasEverSucceeded) now - lastSuccess else 0L
        val failures = failureCounter.get()

        if (failures >= MAX_CONSECUTIVE_FAILURES && hasEverSucceeded && timeSinceLastSuccess > FAILURE_TIMEOUT_MS) {
            val lastRestart = lastRestartAtMs.get()
            if (now - lastRestart < RESTART_COOLDOWN_MS) {
                Log.w(TAG, "Too many reset failures ($failures) but restart is in cooldown, skipping")
                return
            }
            if (callbacks?.isServiceRunning() != true) {
                Log.w(TAG, "Too many reset failures ($failures) but service not running, skip restart")
                return
            }

            lastRestartAtMs.set(now)
            Log.w(TAG, "Too many reset failures ($failures), restarting service")
            serviceScope.launch {
                callbacks?.restartVpnService("Excessive network reset failures")
            }
            return
        }

        val minInterval = if (force) 100L else debounceMs

        if (force) {
            if (now - last < minInterval) return
            lastResetAtMs.set(now)
            resetJob?.cancel()
            resetJob = null
            serviceScope.launch {
                resetNow(reason, force = true, skipIntervalCheck = true)
            }
            return
        }

        val delayMs = (debounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastResetAtMs.set(now)
            resetJob?.cancel()
            resetJob = null
            serviceScope.launch {
                resetNow(reason, force = false, skipIntervalCheck = true)
            }
            return
        }

        if (resetJob?.isActive == true) return
        resetJob = serviceScope.launch {
            delay(delayMs)
            val t = SystemClock.elapsedRealtime()
            val last2 = lastResetAtMs.get()
            if (t - last2 < debounceMs) return@launch
            lastResetAtMs.set(t)
            resetNow(reason, force = false, skipIntervalCheck = true)
        }
    }

    private suspend fun performForceReset(reason: String) {
        try {
            if (callbacks?.isServiceRunning() != true) {
                Log.w(TAG, "Service not running, skip force reset")
                return
            }

            // Step 1: 尝试关闭连接
            try {
                BoxWrapperManager.resetAllConnections(true)
            } catch (_: Exception) {}

            // Step 2: 延迟等待
            delay(150)

            // Step 3: 重置网络栈
            BoxWrapperManager.resetNetwork()

            failureCounter.set(0)
            lastSuccessfulResetAtMs.set(SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            val newFailures = failureCounter.incrementAndGet()
            Log.w(TAG, "Force reset failed (reason=$reason, failures=$newFailures)", e)
        }
    }

    private suspend fun performReset(reason: String) {
        try {
            if (callbacks?.isServiceRunning() != true) {
                Log.w(TAG, "Service not running, skip reset")
                return
            }
            BoxWrapperManager.resetNetwork()
            failureCounter.set(0)
            lastSuccessfulResetAtMs.set(SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            failureCounter.incrementAndGet()
            Log.w(TAG, "Reset failed (reason=$reason)", e)
        }
    }

    fun cancelPendingReset() {
        resetJob?.cancel()
        resetJob = null
    }

    fun cleanup() {
        cancelPendingReset()
        callbacks = null
    }
}
