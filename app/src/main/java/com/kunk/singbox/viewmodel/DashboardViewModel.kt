package com.kunk.singbox.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.WebSocket

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configRepository = ConfigRepository.getInstance(application)
    private val singBoxCore = SingBoxCore.getInstance(application)
    
    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Stats
    private val _stats = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()
    
    private var trafficWebSocket: WebSocket? = null
    
    // Active profile and node from ConfigRepository
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    
    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val activeNodeLatency = kotlinx.coroutines.flow.combine(configRepository.nodes, activeNodeId) { nodes, id ->
        nodes.find { it.id == id }?.latencyMs
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
    
    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    private var statsJob: Job? = null
    
    // Status
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()
    
    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    // VPN 权限请求结果
    private val _vpnPermissionNeeded = MutableStateFlow(false)
    val vpnPermissionNeeded: StateFlow<Boolean> = _vpnPermissionNeeded.asStateFlow()
    
    fun toggleConnection() {
        viewModelScope.launch {
            when (_connectionState.value) {
                ConnectionState.Idle, ConnectionState.Error -> {
                    startVpn()
                }
                ConnectionState.Connecting -> {
                    stopVpn()
                }
                ConnectionState.Connected, ConnectionState.Disconnecting -> {
                    stopVpn()
                }
            }
        }
    }
    
    private fun startVpn() {
        val context = getApplication<Application>()
        
        // 检查 VPN 权限
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            _vpnPermissionNeeded.value = true
            return
        }
        
        _connectionState.value = ConnectionState.Connecting
        
        // 生成配置文件并启动 VPN 服务
        viewModelScope.launch {
            try {
                val configPath = configRepository.generateConfigFile()
                if (configPath == null) {
                    _connectionState.value = ConnectionState.Error
                    _testStatus.value = "配置生成失败"
                    delay(2000)
                    _testStatus.value = null
                    return@launch
                }
                
                val intent = Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_START
                    putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                }
                context.startForegroundService(intent)
                
                // 等待服务启动
                delay(2000)
                if (SingBoxService.isRunning) {
                    _connectionState.value = ConnectionState.Connected
                    startTrafficMonitor()
                } else {
                    _connectionState.value = ConnectionState.Error
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error
                _testStatus.value = "启动失败: ${e.message}"
                delay(2000)
                _testStatus.value = null
            }
        }
    }
    
    private fun stopVpn() {
        val context = getApplication<Application>()
        stopTrafficMonitor()
        _connectionState.value = ConnectionState.Disconnecting
        
        val intent = Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_STOP
        }
        context.startService(intent)
        
        viewModelScope.launch {
            delay(500)
            _connectionState.value = ConnectionState.Idle
            _stats.value = ConnectionStats(0, 0, 0, 0, 0)
        }
    }
    
    fun onVpnPermissionResult(granted: Boolean) {
        _vpnPermissionNeeded.value = false
        if (granted) {
            startVpn()
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _updateStatus.value = "正在更新订阅..."
            delay(1500) // Mock network delay
            configRepository.updateAllProfiles()
            _updateStatus.value = "更新成功"
            delay(2000)
            _updateStatus.value = null
        }
    }

    fun testAllNodesLatency() {
        viewModelScope.launch {
            _testStatus.value = "正在测试延迟..."
            configRepository.testAllNodesLatency()
            _testStatus.value = "测试完成"
            delay(2000)
            _testStatus.value = null
        }
    }
    
    private fun startTrafficMonitor() {
        stopTrafficMonitor()
        
        // 启动计时器
        statsJob = viewModelScope.launch {
            while (_connectionState.value == ConnectionState.Connected) {
                delay(1000)
                _stats.update { current ->
                    current.copy(duration = current.duration + 1000)
                }
            }
        }
        
        // 连接 WebSocket 获取实时流量
        trafficWebSocket = singBoxCore.getClashApiClient().connectTrafficWebSocket { up, down ->
            _stats.update { current ->
                current.copy(
                    uploadSpeed = up,
                    downloadSpeed = down,
                    uploadTotal = current.uploadTotal + up,
                    downloadTotal = current.downloadTotal + down
                )
            }
        }
    }
    
    private fun stopTrafficMonitor() {
        statsJob?.cancel()
        statsJob = null
        trafficWebSocket?.close(1000, "Stop monitoring")
        trafficWebSocket = null
    }
    
    /**
     * 获取活跃配置的名称
     */
    fun getActiveProfileName(): String? {
        val activeId = activeProfileId.value ?: return null
        return profiles.value.find { it.id == activeId }?.name
    }
    
    /**
     * 获取活跃节点的名称
     */
    fun getActiveNodeName(): String? {
        val activeId = activeNodeId.value ?: return null
        return configRepository.nodes.value.find { it.id == activeId }?.name
    }
    
    override fun onCleared() {
        super.onCleared()
        stopTrafficMonitor()
    }
}
