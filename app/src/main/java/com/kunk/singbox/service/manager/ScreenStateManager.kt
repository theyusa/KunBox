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
                                delay(1200)
                                callbacks?.performScreenOnCheck()
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

                        serviceScope.launch {
                            delay(500)
                            callbacks?.performAppForegroundCheck()
                            callbacks?.notifyRemoteStateUpdate(true)

                            if (wasInBackgroundLong && callbacks?.isRunning == true) {
                                Log.i(TAG, "[Foreground] Background duration ${backgroundDuration}ms >= threshold, resetting connections")
                                callbacks?.resetConnectionsOptimal("foreground_after_long_background", false)
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
            val success = BoxWrapperManager.sleep()
            if (success) {
                Log.i(TAG, "[Doze] Device idle - called sleep() successfully")
            } else {
                Log.w(TAG, "[Doze] Device idle - sleep() returned false, trying pause()")
                BoxWrapperManager.pause()
            }
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
            val success = BoxWrapperManager.wake()
            if (success) {
                Log.i(TAG, "[Doze] Device wake - called wake() successfully")
            } else {
                Log.w(TAG, "[Doze] Device wake - wake() returned false, trying resume()")
                BoxWrapperManager.resume()
            }

            val settings = SettingsRepository.getInstance(context).settings.value
            if (settings.wakeResetConnections) {
                Log.i(TAG, "[Doze] wakeResetConnections enabled, resetting connections")
                callbacks?.resetConnectionsOptimal("doze_exit", false)
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
