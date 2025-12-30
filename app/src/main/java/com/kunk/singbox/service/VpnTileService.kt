package com.kunk.singbox.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.R
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxIpcService
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bindTimeoutJob: Job? = null
    @Volatile private var lastServiceState: SingBoxService.ServiceState = SingBoxService.ServiceState.STOPPED
    private var serviceBound = false
    private var bindRequested = false
    private var tapPending = false

    @Volatile private var remoteService: ISingBoxService? = null

    private val tileRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REFRESH_TILE) {
                updateTile()
            }
        }
    }

    private val remoteCallback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            lastServiceState = SingBoxService.ServiceState.values().getOrNull(state)
                ?: SingBoxService.ServiceState.STOPPED
            updateTile(activeLabelOverride = activeLabel)
        }
    }

    companion object {
        private const val PREFS_NAME = "vpn_state"
        private const val KEY_VPN_ACTIVE = "vpn_active"
        private const val KEY_VPN_PENDING = "vpn_pending"
        const val ACTION_REFRESH_TILE = "com.kunk.singbox.REFRESH_TILE"
        /**
         * 持久化 VPN 状态到 SharedPreferences
         * 在 SingBoxService 启动/停止时调用
         */
        fun persistVpnState(context: Context, isActive: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VPN_ACTIVE, isActive)
                .commit()
        }

        fun persistVpnPending(context: Context, pending: String?) {
            val value = pending.orEmpty()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VPN_PENDING, value)
                .commit()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        val filter = IntentFilter(ACTION_REFRESH_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tileRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(tileRefreshReceiver, filter)
        }
        bindService()
    }

    override fun onStopListening() {
        super.onStopListening()
        runCatching { unregisterReceiver(tileRefreshReceiver) }
        unbindService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_TILE) {
            updateTile()
        }
        return START_NOT_STICKY
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggle() }
            return
        }
        toggle()
    }

    private fun updateTile(activeLabelOverride: String? = null) {
        var persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val coreMode = VpnStateStore.getMode(this)

        if (persistedActive && coreMode == VpnStateStore.CoreMode.VPN && !hasSystemVpnTransport()) {
            persistVpnState(this, false)
            persistVpnPending(this, "")
            persistedActive = false
        }

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        if (!serviceBound || remoteService == null || pending.isNotEmpty()) {
            lastServiceState = when (pending) {
                "starting" -> SingBoxService.ServiceState.STARTING
                "stopping" -> SingBoxService.ServiceState.STOPPING
                else -> if (persistedActive) SingBoxService.ServiceState.RUNNING else SingBoxService.ServiceState.STOPPED
            }
        }

        val tile = qsTile ?: return
        when (lastServiceState) {
            SingBoxService.ServiceState.STARTING,
            SingBoxService.ServiceState.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
            }
            SingBoxService.ServiceState.STOPPING -> {
                tile.state = Tile.STATE_UNAVAILABLE
            }
            SingBoxService.ServiceState.STOPPED -> {
                tile.state = Tile.STATE_INACTIVE
            }
        }
        val activeLabel = if (lastServiceState == SingBoxService.ServiceState.RUNNING ||
            lastServiceState == SingBoxService.ServiceState.STARTING
        ) {
            activeLabelOverride?.takeIf { it.isNotBlank() }
                ?: runCatching { remoteService?.activeLabel }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val repo = ConfigRepository.getInstance(applicationContext)
                    val nodeId = repo.activeNodeId.value
                    if (!nodeId.isNullOrBlank()) repo.getNodeById(nodeId)?.name else null
                }.getOrNull()
        } else {
            null
        }

        tile.label = activeLabel ?: getString(R.string.app_name)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (_: Exception) {
        }
        tile.updateTile()
    }

    private fun hasSystemVpnTransport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun toggle() {
        val persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        val effectiveState = if (serviceBound && remoteService != null) {
            val state = runCatching { remoteService?.state }.getOrNull()
            SingBoxService.ServiceState.values().getOrNull(state ?: -1)
                ?: SingBoxService.ServiceState.STOPPED
        } else {
            when (pending) {
                "starting" -> SingBoxService.ServiceState.STARTING
                "stopping" -> SingBoxService.ServiceState.STOPPING
                else -> if (persistedActive) SingBoxService.ServiceState.RUNNING else SingBoxService.ServiceState.STOPPED
            }
        }

        lastServiceState = effectiveState

        when (effectiveState) {
            SingBoxService.ServiceState.RUNNING,
            SingBoxService.ServiceState.STARTING -> {
                if (!serviceBound || remoteService == null) {
                    tapPending = true
                    bindService(force = true)
                    updateTile()
                    return
                }
                persistVpnPending(this, "stopping")
                persistVpnState(this, false)
                updateTile()
                val coreMode = VpnStateStore.getMode(this)
                val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                    Intent(this, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_STOP
                    }
                } else {
                    Intent(this, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_STOP
                    }
                }
                startService(intent)
            }
            SingBoxService.ServiceState.STOPPED -> {
                persistVpnPending(this, "starting")
                updateTile()
                serviceScope.launch {
                    runCatching {
                        Toast.makeText(this@VpnTileService, "正在切换 VPN...", Toast.LENGTH_SHORT).show()
                    }
                    val configRepository = ConfigRepository.getInstance(applicationContext)
                    val settings = SettingsRepository.getInstance(applicationContext).settings.first()

                    if (settings.tunEnabled) {
                        val prepareIntent = VpnService.prepare(this@VpnTileService)
                        if (prepareIntent != null) {
                            persistVpnPending(this@VpnTileService, "")
                            persistVpnState(this@VpnTileService, false)
                            lastServiceState = SingBoxService.ServiceState.STOPPED
                            updateTile()
                            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { startActivityAndCollapse(prepareIntent) }
                            return@launch
                        }
                    }
                    val configResult = configRepository.generateConfigFile()
                    if (configResult != null) {
                        persistVpnPending(this@VpnTileService, "starting")

                        // Avoid running VPN and proxy-only cores at the same time (local mixed port conflict).
                        if (settings.tunEnabled) {
                            runCatching {
                                startService(Intent(this@VpnTileService, ProxyOnlyService::class.java).apply {
                                    action = ProxyOnlyService.ACTION_STOP
                                })
                            }
                        } else {
                            runCatching {
                                startService(Intent(this@VpnTileService, SingBoxService::class.java).apply {
                                    action = SingBoxService.ACTION_STOP
                                })
                            }
                        }
                        delay(600)

                        val intent = if (settings.tunEnabled) {
                            Intent(this@VpnTileService, SingBoxService::class.java).apply {
                                action = SingBoxService.ACTION_START
                                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configResult.path)
                            }
                        } else {
                            Intent(this@VpnTileService, ProxyOnlyService::class.java).apply {
                                action = ProxyOnlyService.ACTION_START
                                putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configResult.path)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else {
                        persistVpnPending(this@VpnTileService, "")
                        persistVpnState(this@VpnTileService, false)
                        updateTile()
                    }
                }
            }
            SingBoxService.ServiceState.STOPPING -> {
                updateTile()
            }
        }
    }

    private fun bindService(force: Boolean = false) {
        if (serviceBound || bindRequested) return

        val persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        val shouldTryBind = force || persistedActive || pending == "starting" || pending == "stopping"
        if (!shouldTryBind) return

        val intent = Intent(this, SingBoxIpcService::class.java)

        val ok = runCatching {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bindRequested = ok

        bindTimeoutJob?.cancel()
        bindTimeoutJob = serviceScope.launch {
            delay(1200)
            if (serviceBound || remoteService != null) return@launch
            if (!bindRequested) return@launch

            val active = runCatching {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_VPN_ACTIVE, false)
            }.getOrDefault(false)
            val p = runCatching {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_VPN_PENDING, "")
            }.getOrNull().orEmpty()

            if (p != "starting" && (active || p == "stopping")) {
                unbindService()
                tapPending = false
                persistVpnState(this@VpnTileService, false)
                persistVpnPending(this@VpnTileService, "")
                lastServiceState = SingBoxService.ServiceState.STOPPED
                updateTile()
            }
        }

        if (!ok && pending != "starting" && (persistedActive || pending == "stopping")) {
            tapPending = false
            persistVpnState(this, false)
            persistVpnPending(this, "")
            lastServiceState = SingBoxService.ServiceState.STOPPED
            updateTile()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = ISingBoxService.Stub.asInterface(service)
            remoteService = binder
            runCatching { binder.registerCallback(remoteCallback) }
            serviceBound = true
            bindRequested = true
            lastServiceState = SingBoxService.ServiceState.values().getOrNull(runCatching { binder.state }.getOrNull() ?: -1)
                ?: SingBoxService.ServiceState.STOPPED
            updateTile(activeLabelOverride = runCatching { binder.activeLabel }.getOrNull())
            if (tapPending) {
                tapPending = false
                toggle()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runCatching { remoteService?.unregisterCallback(remoteCallback) }
            remoteService = null
            serviceBound = false
            bindRequested = false
            updateTile()
        }
    }

    private fun unbindService() {
        if (!bindRequested) return
        bindTimeoutJob?.cancel()
        bindTimeoutJob = null
        runCatching { remoteService?.unregisterCallback(remoteCallback) }
        runCatching { unbindService(serviceConnection) }
        remoteService = null
        serviceBound = false
        bindRequested = false
    }

    override fun onDestroy() {
        unbindService()
        serviceScope.cancel()
        super.onDestroy()
    }
}
