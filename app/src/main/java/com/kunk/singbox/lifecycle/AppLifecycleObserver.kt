package com.kunk.singbox.lifecycle

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kunk.singbox.service.manager.BackgroundPowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用生命周期观察者
 * 使用 ProcessLifecycleOwner 精确检测应用前后台状态
 */
object AppLifecycleObserver : DefaultLifecycleObserver {
    private const val TAG = "AppLifecycleObserver"

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private var powerManager: BackgroundPowerManager? = null

    @Volatile
    private var isRegistered = false

    fun register() {
        if (isRegistered) return
        isRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "AppLifecycleObserver registered with ProcessLifecycleOwner")
    }

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.i(TAG, "App entered FOREGROUND")
        _isAppInForeground.value = true
        powerManager?.onAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.i(TAG, "App entered BACKGROUND")
        _isAppInForeground.value = false
        powerManager?.onAppBackground()
    }
}
