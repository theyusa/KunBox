package com.kunk.singbox.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "DashboardViewModel"
    }
    
    private val configRepository = ConfigRepository.getInstance(application)
    private val singBoxCore = SingBoxCore.getInstance(application)
    
    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Stats
    private val _statsBase = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    private val _connectedAtElapsedMs = MutableStateFlow<Long?>(null)

    private val durationMsFlow: Flow<Long> = connectionState.flatMapLatest { state ->
        if (state == ConnectionState.Connected) {
            flow {
                while (true) {
                    val start = _connectedAtElapsedMs.value
                    emit(if (start != null) SystemClock.elapsedRealtime() - start else 0L)
                    delay(1000)
                }
            }
        } else {
            flowOf(0L)
        }
    }

    val stats: StateFlow<ConnectionStats> = combine(_statsBase, durationMsFlow) { base, duration ->
        base.copy(duration = duration)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionStats(0, 0, 0, 0, 0)
    )
    
    // 当前节点的实时延迟（VPN启动后测得的）
    private val _currentNodePing = MutableStateFlow<Long?>(null)
    val currentNodePing: StateFlow<Long?> = _currentNodePing.asStateFlow()
    
    private var trafficWebSocket: WebSocket? = null
    private var pingTestJob: Job? = null
    
    // 用于平滑流量显示的缓存
    private var lastUploadSpeed: Long = 0
    private var lastDownloadSpeed: Long = 0
    
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
    
    private var trafficSmoothingJob: Job? = null
    
    // Status
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()
    
    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    // VPN 权限请求结果
    private val _vpnPermissionNeeded = MutableStateFlow(false)
    val vpnPermissionNeeded: StateFlow<Boolean> = _vpnPermissionNeeded.asStateFlow()
    
    init {
        // Observe SingBoxService running and starting state to keep UI in sync
        viewModelScope.launch {
            SingBoxService.isRunningFlow.collect { running ->
                if (running) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                    startTrafficMonitor()
                    // VPN 启动后自动对当前节点进行测速
                    startPingTest()
                } else if (!SingBoxService.isStarting) {
                    _connectionState.value = ConnectionState.Idle
                    _connectedAtElapsedMs.value = null
                    stopTrafficMonitor()
                    stopPingTest()
                    _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
                    _currentNodePing.value = null
                }
            }
        }

        viewModelScope.launch {
            SingBoxService.isStartingFlow.collect { starting ->
                if (starting) {
                    _connectionState.value = ConnectionState.Connecting
                } else if (!SingBoxService.isRunning) {
                    _connectionState.value = ConnectionState.Idle
                }
            }
        }
    }

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
    
    fun restartVpn() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                _vpnPermissionNeeded.value = true
                return@launch
            }

            val wasRunning = SingBoxService.isRunning || SingBoxService.isStarting
            if (wasRunning) {
                stopVpn()

                withTimeoutOrNull(5000) {
                    SingBoxService.isRunningFlow.first { running -> !running }
                }
            }

            startVpn()
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
                // 在生成配置前先执行强制迁移，修复可能导致 404 的旧配置
                val configPath = withContext(Dispatchers.IO) {
                    val settingsRepository = com.kunk.singbox.repository.SettingsRepository.getInstance(context)
                    settingsRepository.checkAndMigrateRuleSets()
                    configRepository.generateConfigFile()
                }
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
        stopPingTest()
        // Immediately set to Idle for responsive UI
        _connectionState.value = ConnectionState.Idle
        _connectedAtElapsedMs.value = null
        _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
        _currentNodePing.value = null
        
        val intent = Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_STOP
        }
        context.startService(intent)
    }
    
    /**
     * 启动当前节点的延迟测试
     */
    private fun startPingTest() {
        stopPingTest()
        
        pingTestJob = viewModelScope.launch {
            try {
                // 等待一小段时间确保 VPN 完全启动
                delay(1000)
                
                // 检查 VPN 是否还在运行
                if (_connectionState.value != ConnectionState.Connected) {
                    return@launch
                }
                
                val activeNodeId = activeNodeId.value
                if (activeNodeId == null) {
                    Log.w(TAG, "No active node to test ping")
                    return@launch
                }
                
                val nodeName = configRepository.getNodeById(activeNodeId)?.name
                if (nodeName == null) {
                    Log.w(TAG, "Node name not found for id: $activeNodeId")
                    return@launch
                }
                
                Log.d(TAG, "Starting ping test for node: $nodeName")
                
                // 通过 Clash API 测试当前节点延迟
                val clashApi = singBoxCore.getClashApiClient()
                val delay = clashApi.testProxyDelay(nodeName)
                
                // 再次检查 VPN 是否还在运行（测试可能需要一些时间）
                if (_connectionState.value == ConnectionState.Connected && pingTestJob?.isActive == true) {
                    if (delay > 0) {
                        _currentNodePing.value = delay
                        Log.d(TAG, "Ping test completed: ${delay}ms")
                    } else {
                        Log.w(TAG, "Ping test failed or timed out")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during ping test", e)
            }
        }
    }
    
    /**
     * 停止延迟测试
     */
    private fun stopPingTest() {
        pingTestJob?.cancel()
        pingTestJob = null
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
        
        // 重置平滑缓存
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
        
        // 连接 WebSocket 获取实时流量，使用平滑处理
        trafficWebSocket = singBoxCore.getClashApiClient().connectTrafficWebSocket { up, down ->
            // 使用指数移动平均进行平滑处理，减少闪烁
            val smoothFactor = 0.3 // 平滑因子，越小越平滑
            val smoothedUp = if (lastUploadSpeed == 0L) up else (lastUploadSpeed * (1 - smoothFactor) + up * smoothFactor).toLong()
            val smoothedDown = if (lastDownloadSpeed == 0L) down else (lastDownloadSpeed * (1 - smoothFactor) + down * smoothFactor).toLong()
            
            lastUploadSpeed = smoothedUp
            lastDownloadSpeed = smoothedDown
            
            _statsBase.update { current ->
                current.copy(
                    uploadSpeed = smoothedUp,
                    downloadSpeed = smoothedDown,
                    uploadTotal = current.uploadTotal + up,
                    downloadTotal = current.downloadTotal + down
                )
            }
        }
    }
    
    private fun stopTrafficMonitor() {
        trafficSmoothingJob?.cancel()
        trafficSmoothingJob = null
        trafficWebSocket?.close(1000, "Stop monitoring")
        trafficWebSocket = null
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
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
        stopPingTest()
    }
}
