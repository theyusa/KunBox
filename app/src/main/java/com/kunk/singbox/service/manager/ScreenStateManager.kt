package com.kunk.singbox.service.manager

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 屏幕和设备状态管理器
 * 负责屏幕状态监听、设备空闲处理和 Activity 生命周期回调
 * 屏幕状态变化会通知 BackgroundPowerManager 触发省电模式
 */
class ScreenStateManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ScreenStateManager"
        private const val SCREEN_ON_CHECK_DEBOUNCE_MS = 3000L
        private const val BACKGROUND_THRESHOLD_MS = 30_000L
        private const val SHORT_BACKGROUND_THRESHOLD_MS = 10_000L

        // Recovery modes (must match Go side constants)
        const val RECOVERY_MODE_AUTO = 0
        const val RECOVERY_MODE_QUICK = 1
        const val RECOVERY_MODE_FULL = 2
        const val RECOVERY_MODE_DEEP = 3
        const val RECOVERY_MODE_PROACTIVE = 4 // New: includes network probe
    }

    interface Callbacks {
        val isRunning: Boolean
        suspend fun performScreenOnCheck()
        suspend fun performAppForegroundCheck()
        suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean)
        /**
         * 通知远程 UI 强制刷新状态
         * 用于 Doze 唤醒后确保 IPC 状态同步
         */
        fun notifyRemoteStateUpdate(force: Boolean)
        /**
         * 执行网络恢复
         * mode: 0=自动, 1=快速, 2=完整, 3=深度
         */
        suspend fun performNetworkRecovery(mode: Int, reason: String)

        /**
         * 设备进入 Doze 时的内核降频/暂停处理。
         * 必须走串行协调器，避免与恢复/重置并发。
         */
        suspend fun enterDeviceIdle(reason: String)
    }

    private var callbacks: Callbacks? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var powerManager: BackgroundPowerManager? = null

    @Volatile private var lastScreenOnCheckMs: Long = 0L
    @Volatile var isScreenOn: Boolean = true
        private set
    @Volatile var isAppInForeground: Boolean = true
        private set
    @Volatile private var lastAppBackgroundAtMs: Long = 0L

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 设置省电管理器引用
     */
    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    /**
     * 注册屏幕状态监听器
     */
    fun registerScreenStateReceiver() {
        try {
            if (screenStateReceiver != null) return

            screenStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            Log.i(TAG, "Screen ON detected")
                            isScreenOn = true
                            // 通知省电管理器屏幕点亮
                            powerManager?.onScreenOn()

                            // ===== 早期恢复：屏幕亮起时立即开始网络恢复 =====
                            // 不等待用户解锁，提前开始恢复网络连接
                            if (callbacks?.isRunning == true) {
                                serviceScope.launch {
                                    Log.i(TAG, "[ScreenOn] Early recovery triggered")
                                    try {
                                        // 使用快速恢复模式，不阻塞用户解锁
                                        callbacks?.performNetworkRecovery(RECOVERY_MODE_QUICK, "screen_on")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "[ScreenOn] Early recovery failed", e)
                                    }
                                }
                            }
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            Log.i(TAG, "Screen OFF detected")
                            isScreenOn = false
                            // 通知省电管理器屏幕关闭
                            powerManager?.onScreenOff()
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastScreenOnCheckMs < SCREEN_ON_CHECK_DEBOUNCE_MS) return

                            lastScreenOnCheckMs = now
                            Log.i(TAG, "[Unlock] User unlocked device")

                            serviceScope.launch {
                                delay(800) // Reduced from 1200ms for faster response
                                callbacks?.performScreenOnCheck()

                                // 参考 NekoBox: 唤醒后按需重置出站连接，避免应用(如 Telegram)卡在旧连接上等待超时
                                val settings = runCatching {
                                    SettingsRepository.getInstance(context).settings.value
                                }.getOrNull()
                                if (callbacks?.isRunning == true && settings?.wakeResetConnections == true) {
                                    Log.i(TAG, "[Unlock] wakeResetConnections enabled, resetting connections")
                                    callbacks?.resetConnectionsOptimal("user_present", false)
                                }
                            }
                        }
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                val isIdleMode = pm?.isDeviceIdleMode == true

                                if (isIdleMode) {
                                    Log.i(TAG, "[Doze Enter] Device entering idle mode")
                                    serviceScope.launch { handleDeviceIdle() }
                                } else {
                                    Log.i(TAG, "[Doze Exit] Device exiting idle mode")
                                    serviceScope.launch { handleDeviceWake() }
                                }
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }

            context.registerReceiver(screenStateReceiver, filter)
            Log.i(TAG, "Screen state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen state receiver", e)
        }
    }

    /**
     * 注销屏幕状态监听器
     */
    fun unregisterScreenStateReceiver() {
        try {
            screenStateReceiver?.let {
                context.unregisterReceiver(it)
                screenStateReceiver = null
                Log.i(TAG, "Screen state receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen state receiver", e)
        }
    }

    /**
     * 注册 Activity 生命周期回调
     */
    fun registerActivityLifecycleCallbacks(application: Application?) {
        try {
            if (activityLifecycleCallbacks != null) return

            val app = application ?: return

            activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    if (!isAppInForeground) {
                        Log.i(TAG, "App returned to FOREGROUND (${activity.localClassName})")
                        isAppInForeground = true

                        val backgroundDuration = SystemClock.elapsedRealtime() - lastAppBackgroundAtMs
                        val wasInBackgroundLong = lastAppBackgroundAtMs > 0 && backgroundDuration >= BACKGROUND_THRESHOLD_MS
                        val wasInBackgroundShort = lastAppBackgroundAtMs > 0 && backgroundDuration >= SHORT_BACKGROUND_THRESHOLD_MS

                        serviceScope.launch {
                            delay(300) // Reduced from 500ms for faster response
                            callbacks?.performAppForegroundCheck()
                            callbacks?.notifyRemoteStateUpdate(true)

                            // Only update UI state, do NOT reset network connections
                            // NekoBox/SagerNet philosophy: UI should not interfere with background service state
                            if (callbacks?.isRunning == true) {
                                Log.i(TAG, "[Foreground] App returned to foreground, updating UI state only")
                            }
                        }
                    }
                }

                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivityStarted(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {}
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            }

            app.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
            Log.i(TAG, "Activity lifecycle callbacks registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register activity lifecycle callbacks", e)
        }
    }

    /**
     * 注销 Activity 生命周期回调
     */
    fun unregisterActivityLifecycleCallbacks(application: Application?) {
        try {
            activityLifecycleCallbacks?.let { cb ->
                application?.unregisterActivityLifecycleCallbacks(cb)
                activityLifecycleCallbacks = null
                Log.i(TAG, "Activity lifecycle callbacks unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister activity lifecycle callbacks", e)
        }
    }

    /**
     * 处理应用进入后台
     */
    fun onAppBackground() {
        Log.i(TAG, "App moved to BACKGROUND")
        isAppInForeground = false
        lastAppBackgroundAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * 设备进入空闲模式
     */
    private suspend fun handleDeviceIdle() {
        if (callbacks?.isRunning != true) return

        try {
            callbacks?.enterDeviceIdle("doze_enter")
        } catch (e: Exception) {
            Log.e(TAG, "[Doze] handleDeviceIdle failed", e)
        }
    }

    /**
     * 设备退出空闲模式
     */
    private suspend fun handleDeviceWake() {
        if (callbacks?.isRunning != true) return

        try {
            Log.i(TAG, "[Doze] Device wake - performing deep network recovery")

            // ===== 核心修复：Doze 唤醒时使用深度恢复 =====
            // 深度恢复包含：唤醒 pause.Manager -> 关闭连接 -> 清除 DNS -> 重置网络栈
            try {
                callbacks?.performNetworkRecovery(RECOVERY_MODE_DEEP, "doze_exit")
            } catch (e: Exception) {
                Log.w(TAG, "[Doze] Deep recovery failed, falling back to full recovery", e)
                runCatching {
                    callbacks?.performNetworkRecovery(RECOVERY_MODE_FULL, "doze_exit_fallback")
                }.onFailure { e2 ->
                    Log.w(TAG, "[Doze] Full recovery also failed", e2)
                    // As the last fallback, only do connection reset if explicitly enabled.
                    val settings = SettingsRepository.getInstance(context).settings.value
                    if (settings.wakeResetConnections) {
                        Log.i(TAG, "[Doze] wakeResetConnections enabled, resetting connections")
                        callbacks?.resetConnectionsOptimal("doze_exit", false)
                    }
                }
            }

            // Fix A: Doze 唤醒后强制推送状态到 UI，修复 IPC 状态同步问题
            callbacks?.notifyRemoteStateUpdate(true)
        } catch (e: Exception) {
            Log.e(TAG, "[Doze] handleDeviceWake failed", e)
        }
    }

    fun cleanup() {
        unregisterScreenStateReceiver()
        callbacks = null
    }
}
