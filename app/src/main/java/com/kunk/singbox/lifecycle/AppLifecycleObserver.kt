package com.kunk.singbox.lifecycle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用生命周期观察者
 * 使用 ProcessLifecycleOwner 精确检测应用前后台状态
 * 通过 IPC 通知 :bg 进程触发省电模式
 *
 * 省电模式下主动杀死主进程，只保留 :bg 进程
 */
object AppLifecycleObserver : DefaultLifecycleObserver {
    private const val TAG = "AppLifecycleObserver"

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    @Volatile
    private var isRegistered = false

    @Volatile
    private var backgroundTimeoutMs: Long = BackgroundPowerSavingDelay.MINUTES_30.delayMs

    @Volatile
    private var backgroundAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var killProcessRunnable: Runnable? = null

    fun register() {
        if (isRegistered) return
        isRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "AppLifecycleObserver registered with ProcessLifecycleOwner")
    }

    /**
     * 设置后台超时时间
     */
    fun setBackgroundTimeout(timeoutMs: Long) {
        backgroundTimeoutMs = timeoutMs
        val displayMin = if (timeoutMs == Long.MAX_VALUE) "NEVER" else "${timeoutMs / 1000 / 60}min"
        Log.i(TAG, "Background timeout set to $displayMin")
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.i(TAG, "App entered FOREGROUND")
        _isAppInForeground.value = true
        backgroundAtMs = 0L

        // 取消待执行的杀进程任务
        cancelKillProcess()

        // 通过 IPC 通知 :bg 进程
        SingBoxRemote.notifyAppLifecycle(isForeground = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.i(TAG, "App entered BACKGROUND")
        _isAppInForeground.value = false
        backgroundAtMs = SystemClock.elapsedRealtime()

        // 通过 IPC 通知 :bg 进程
        SingBoxRemote.notifyAppLifecycle(isForeground = false)

        // 调度主进程自杀
        scheduleKillProcess()
    }

    /**
     * 调度主进程自杀
     */
    private fun scheduleKillProcess() {
        if (backgroundTimeoutMs == Long.MAX_VALUE) {
            Log.d(TAG, "Power saving disabled, skip scheduling kill process")
            return
        }

        // 只有 VPN 在运行时才需要杀主进程省电
        if (!SingBoxRemote.isRunning.value) {
            Log.d(TAG, "VPN not running, skip scheduling kill process")
            return
        }

        cancelKillProcess()

        killProcessRunnable = Runnable {
            // 再次检查是否仍在后台且 VPN 在运行
            if (!_isAppInForeground.value && SingBoxRemote.isRunning.value) {
                Log.i(TAG, ">>> Background timeout reached, killing main process to save power")
                Log.i(TAG, ">>> VPN will continue running in :bg process")

                // 断开 IPC 连接（不影响 :bg 进程）
                // 不调用 disconnect，让 :bg 进程自己处理 binder 死亡

                // 杀死主进程
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        mainHandler.postDelayed(killProcessRunnable!!, backgroundTimeoutMs)
        Log.i(TAG, "Scheduled kill process in ${backgroundTimeoutMs / 1000 / 60}min")
    }

    /**
     * 取消主进程自杀
     */
    private fun cancelKillProcess() {
        killProcessRunnable?.let {
            mainHandler.removeCallbacks(it)
            killProcessRunnable = null
            Log.d(TAG, "Cancelled pending kill process")
        }
    }
}
