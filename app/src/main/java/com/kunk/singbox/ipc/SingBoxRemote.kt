package com.kunk.singbox.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.ipc.SingBoxIpcService
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SingBoxRemote {
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

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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
            }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val s = service
            service = null
            bound = false
            runCatching { s?.unregisterCallback(callback) }
            _state.value = SingBoxService.ServiceState.STOPPED
            _isRunning.value = false
            _isStarting.value = false
        }
    }

    fun ensureBound(context: Context) {
        if (bound) return
        val intent = Intent(context, SingBoxIpcService::class.java)
        runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
    }

    fun isBound(): Boolean = bound

    fun unbind(context: Context) {
        if (!bound) return
        val s = service
        service = null
        bound = false
        runCatching { s?.unregisterCallback(callback) }
        runCatching { context.applicationContext.unbindService(conn) }
    }
}
