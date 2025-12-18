package com.kunk.singbox.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kunk.singbox.R
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        
        // 订阅VPN状态变化，确保磁贴状态与实际VPN状态同步
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            SingBoxService.isRunningFlow.collect { isRunning ->
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        // 停止监听时取消订阅
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    override fun onClick() {
        super.onClick()
        val isRunning = SingBoxService.isRunning
        
        // Update tile state immediately for responsive feel
        val tile = qsTile
        if (tile != null) {
            tile.state = if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }

        if (isRunning) {
            val intent = Intent(this, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
            startService(intent)
        } else {
            serviceScope.launch {
                val configRepository = ConfigRepository.getInstance(applicationContext)
                val configPath = configRepository.generateConfigFile()
                if (configPath != null) {
                    val intent = Intent(this@VpnTileService, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                    }
                    startForegroundService(intent)
                } else {
                    // Revert tile state if start fails
                    updateTile()
                }
            }
        }
        
        // Final update after a short delay to ensure synchronization with actual service state
        // Removed to prevent flickering back to old state if service takes time to stop
        // serviceScope.launch {
        //     kotlinx.coroutines.delay(500)
        //     updateTile()
        // }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = SingBoxService.isRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (e: Exception) {
            // Fallback to manifest icon if something goes wrong
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
