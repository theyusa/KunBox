package com.kunk.singbox.service.manager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台应用网络监控器
 * 实时检测前台应用网络是否卡顿，如果卡顿则刷新连接
 *
 * 功能：
 * - 检测当前前台应用
 * - 每秒检测该应用网络是否卡顿
 * - 卡顿时自动关闭该应用的连接，强制重建
 *
 * 卡顿判定：
 * - 应用有活跃连接
 * - 连接存在超过 3 秒
 * - 有上传（请求发出）但没有下载（无响应）
 */
class ForegroundAppMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ForegroundAppMonitor"
        private const val CHECK_INTERVAL_MS = 2000L
        private const val STALL_WINDOW_MS = 5000
        private const val STARTUP_DELAY_MS = 10000L
        private const val COOLDOWN_MS = 10000L
    }

    interface Callbacks {
        fun isVpnRunning(): Boolean
        fun isAppInVpnWhitelist(packageName: String): Boolean
        fun isCoreReady(): Boolean
    }

    private var callbacks: Callbacks? = null
    private var monitorJob: Job? = null
    private var usageStatsManager: UsageStatsManager? = null

    @Volatile
    private var isEnabled: Boolean = false

    @Volatile
    private var hasUsageStatsPermission: Boolean = false

    private var lastForegroundPackage: String = ""
    private val lastResetTimeMap = mutableMapOf<String, Long>()

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
        usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        hasUsageStatsPermission = PermissionUtils.hasUsageStatsPermission(context)
        if (!hasUsageStatsPermission) {
            Log.w(TAG, "UsageStats permission not granted, monitor will be disabled")
        }
    }

    fun start() {
        if (monitorJob != null) return
        if (usageStatsManager == null) {
            Log.w(TAG, "UsageStatsManager not available")
            return
        }
        if (!hasUsageStatsPermission) {
            Log.w(TAG, "UsageStats permission not granted, skipping start")
            return
        }

        isEnabled = true
        monitorJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Foreground app monitor started, waiting for startup delay")
            delay(STARTUP_DELAY_MS)
            Log.i(TAG, "Foreground app monitor active")
            while (isActive && isEnabled) {
                try {
                    checkForegroundApp()
                } catch (e: Exception) {
                    Log.w(TAG, "Check failed: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isEnabled = false
        monitorJob?.cancel()
        monitorJob = null
        lastForegroundPackage = ""
        lastResetTimeMap.clear()
        Log.i(TAG, "Foreground app monitor stopped")
    }

    private fun checkForegroundApp() {
        val cb = callbacks ?: return
        if (!cb.isVpnRunning()) return
        if (!cb.isCoreReady()) return

        val foregroundPackage = getForegroundPackage() ?: return

        if (foregroundPackage != lastForegroundPackage) {
            Log.d(TAG, "Foreground app changed: $lastForegroundPackage -> $foregroundPackage")
            lastForegroundPackage = foregroundPackage
        }

        if (!cb.isAppInVpnWhitelist(foregroundPackage)) {
            return
        }

        val now = System.currentTimeMillis()
        val lastResetTime = lastResetTimeMap[foregroundPackage] ?: 0L
        if (now - lastResetTime < COOLDOWN_MS) {
            return
        }

        val isStalled = try {
            BoxWrapperManager.isAppNetworkStalled(foregroundPackage, STALL_WINDOW_MS)
        } catch (e: Exception) {
            Log.w(TAG, "isAppNetworkStalled failed for $foregroundPackage: ${e.message}")
            false
        }

        if (isStalled) {
            Log.w(TAG, "Network stalled for $foregroundPackage, closing connections")
            val closedCount = try {
                BoxWrapperManager.closeConnectionsForApp(foregroundPackage)
            } catch (e: Exception) {
                Log.w(TAG, "closeConnectionsForApp failed for $foregroundPackage: ${e.message}")
                0
            }
            if (closedCount > 0) {
                lastResetTimeMap[foregroundPackage] = now
                Log.i(TAG, "Closed $closedCount connections for $foregroundPackage")
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun getForegroundPackage(): String? {
        val usm = usageStatsManager ?: return null

        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 10_000 // 10 秒内的事件

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val events = usm.queryEvents(beginTime, endTime)
                var lastPackage: String? = null
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        lastPackage = event.packageName
                    }
                }
                lastPackage
            } else {
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    beginTime,
                    endTime
                )
                stats?.maxByOrNull { it.lastTimeUsed }?.packageName
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No usage stats permission: ${e.message}")
            null
        }
    }

    fun cleanup() {
        stop()
        callbacks = null
        usageStatsManager = null
        hasUsageStatsPermission = false
    }
}
