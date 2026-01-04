package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.repository.ConfigRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "DashboardViewModel"
    }
    
    private val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
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

    fun setActiveProfile(profileId: String) {
        configRepository.setActiveProfile(profileId)
        val name = profiles.value.find { it.id == profileId }?.name
        if (!name.isNullOrBlank()) {
            viewModelScope.launch {
                val msg = getApplication<Application>().getString(R.string.profiles_updated) + ": $name" // TODO: better string
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
        }
    }

    fun setActiveNode(nodeId: String) {
        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val result = configRepository.setActiveNodeWithResult(nodeId)

            if (SingBoxRemote.isRunning.value && node != null) {
                val msg = when (result) {
                    is ConfigRepository.NodeSwitchResult.Success,
                    is ConfigRepository.NodeSwitchResult.NotRunning -> "Switched to ${node.name}" // TODO: add to strings.xml

                    is ConfigRepository.NodeSwitchResult.Failed -> "Failed to switch to ${node.name}" // TODO: add to strings.xml
                }
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
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
    // null = 未测试, -1 = 测试失败/超时, >0 = 实际延迟
    private val _currentNodePing = MutableStateFlow<Long?>(null)
    val currentNodePing: StateFlow<Long?> = _currentNodePing.asStateFlow()
    
    // Ping 测试状态：true = 正在测试中
    private val _isPingTesting = MutableStateFlow(false)
    val isPingTesting: StateFlow<Boolean> = _isPingTesting.asStateFlow()
    
    private var pingTestJob: Job? = null
    private var lastErrorToastJob: Job? = null
    private var startMonitorJob: Job? = null
    
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

    private val _nodeFilter = MutableStateFlow(NodeFilter())
    private val _sortType = MutableStateFlow(NodeSortType.DEFAULT)
    private val _customNodeOrder = MutableStateFlow<List<String>>(emptyList())

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        _nodeFilter,
        _sortType,
        _customNodeOrder,
        configRepository.activeNodeId
    ) { nodes, filter, sortType, customOrder, currentActiveNodeId ->
        val filtered = when (filter.filterMode) {
            FilterMode.NONE -> nodes
            FilterMode.INCLUDE -> {
                if (filter.keywords.isEmpty()) nodes
                else nodes.filter { node -> filter.keywords.any { node.displayName.contains(it, ignoreCase = true) } }
            }
            FilterMode.EXCLUDE -> {
                if (filter.keywords.isEmpty()) nodes
                else nodes.filter { node -> filter.keywords.none { node.displayName.contains(it, ignoreCase = true) } }
            }
        }
        
        // 应用排序
        val sorted = when (sortType) {
            NodeSortType.DEFAULT -> filtered
            NodeSortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
                val l = it.latencyMs
                // 将未测试(null)和超时/失败(<=0)的节点排到最后
                if (l == null || l <= 0) Long.MAX_VALUE else l
            })
            NodeSortType.NAME -> filtered.sortedBy { it.name }
            NodeSortType.REGION -> filtered.sortedWith(compareBy<NodeUi> {
                getRegionWeight(it.regionFlag)
            }.thenBy { it.name })
            NodeSortType.CUSTOM -> {
                val orderMap = customOrder.withIndex().associate { it.value to it.index }
                filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
            }
        }
        
        // 如果当前活跃节点不在过滤后的列表中，自动选择第一个过滤后的节点
        if (sorted.isNotEmpty() && (currentActiveNodeId == null || sorted.none { it.id == currentActiveNodeId })) {
            // 直接通过 configRepository 设置活跃节点，避免显示 Toast
            viewModelScope.launch {
                configRepository.setActiveNode(sorted.first().id)
            }
        }
        
        sorted
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    private var trafficSmoothingJob: Job? = null
    private var trafficBaseTxBytes: Long = 0
    private var trafficBaseRxBytes: Long = 0
    private var lastTrafficTxBytes: Long = 0
    private var lastTrafficRxBytes: Long = 0
    private var lastTrafficSampleAtElapsedMs: Long = 0
    
    // Status
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()
    
    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    private val _actionStatus = MutableStateFlow<String?>(null)
    val actionStatus: StateFlow<String?> = _actionStatus.asStateFlow()

    // VPN 权限请求结果
    private val _vpnPermissionNeeded = MutableStateFlow(false)
    val vpnPermissionNeeded: StateFlow<Boolean> = _vpnPermissionNeeded.asStateFlow()
    
    init {
        viewModelScope.launch {
            _nodeFilter.value = settingsRepository.getNodeFilter()
        }
        viewModelScope.launch {
            settingsRepository.getNodeSortType().collect {
                _sortType.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.getCustomNodeOrder().collect {
                _customNodeOrder.value = it
            }
        }
        runCatching { SingBoxRemote.ensureBound(getApplication()) }

        // Best-effort initial sync for UI state after process restart/force-stop.
        // We rely on system VPN presence + persisted state, and clear stale persisted state.
        runCatching {
            val context = getApplication<Application>()
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val hasSystemVpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } else {
                false
            }
            val persisted = context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
                .getBoolean("vpn_active", false)

            if (!hasSystemVpn && persisted) {
                VpnTileService.persistVpnState(context, false)
            }

            if (hasSystemVpn && persisted) {
                // If process restarted while VPN is still up, show as connected until flows catch up.
                _connectionState.value = ConnectionState.Connected
                _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
            } else if (!SingBoxRemote.isStarting.value) {
                _connectionState.value = ConnectionState.Idle
            }
        }

        // Observe SingBoxService running and starting state to keep UI in sync
        viewModelScope.launch {
            SingBoxRemote.isRunning.collect { running ->
                if (running) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                    startTrafficMonitor()
                } else if (!SingBoxRemote.isStarting.value) {
                    _connectionState.value = ConnectionState.Idle
                    _connectedAtElapsedMs.value = null
                    stopTrafficMonitor()
                    stopPingTest()
                    _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
                    // Disconnect resets ping state to "not tested"
                    _currentNodePing.value = null
                }
            }
        }

        // 专门处理自动测速逻辑：仅在 VPN 状态从停止变为运行时（真正的新连接）触发测速
        // 使用 drop(1) 跳过初始状态，防止每次进入 Dashboard 只要 VPN 开着就重测
        viewModelScope.launch {
            SingBoxRemote.isRunning
                .drop(1) // 忽略初始值，避免进入页面时重复触发
                .distinctUntilChanged() // 确保状态发生变化
                .filter { it } // 只关注变为 running 的情况
                .collect {
                    // VPN 启动后自动对当前节点进行测速
                    startPingTest()
                }
        }

        // Surface service-level startup errors on UI
        viewModelScope.launch {
            SingBoxRemote.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _testStatus.value = err
                    lastErrorToastJob?.cancel()
                    lastErrorToastJob = viewModelScope.launch {
                        delay(3000)
                        if (_testStatus.value == err) {
                            _testStatus.value = null
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            SingBoxRemote.isStarting.collect { starting ->
                if (starting) {
                    _connectionState.value = ConnectionState.Connecting
                } else if (!SingBoxRemote.isRunning.value) {
                    if (_connectionState.value != ConnectionState.Connecting) {
                        _connectionState.value = ConnectionState.Idle
                    }
                }
            }
        }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            when (_connectionState.value) {
                ConnectionState.Idle, ConnectionState.Error -> {
                    startCore()
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

            val settings = SettingsRepository.getInstance(context).settings.first()
            if (settings.tunEnabled) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            // 记录当前状态，用于判断是否需要等待
            val wasRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
            
            // Force restart to apply latest generated config.
            // ACTION_START on running service is ignored, so we must STOP first.
            runCatching {
                context.startService(Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                })
            }
            runCatching {
                context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                    action = ProxyOnlyService.ACTION_STOP
                })
            }

            // 【优雅等待】只有在服务之前正在运行时才需要等待
            // 使用 drop(1) 跳过当前值，确保等待的是状态变化而不是当前状态
            if (wasRunning) {
                try {
                    withTimeout(5000L) {
                        // 使用 drop(1) 跳过当前值，等待真正的状态变化
                        SingBoxRemote.state
                            .drop(1)
                            .first { it == SingBoxService.ServiceState.STOPPED }
                    }
                } catch (e: TimeoutCancellationException) {
                    // 超时后仍然继续，但记录警告
                    Log.w(TAG, "Timeout waiting for service to stop, proceeding with restart")
                }
            }
            
            // 额外等待确保 QUIC 连接和网络接口完全释放
            // STOPPED 状态是在 cleanupScope 中异步设置的，boxService.close() 可能仍在执行
            // 对于 Hysteria2/QUIC，需要更长时间来关闭 UDP 连接
            // 增加到 2500ms 以确保绝对安全，避免 "use of closed network connection"
            delay(2500)

            startCore()
        }
    }
    
    private fun startCore() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val settings = runCatching {
                SettingsRepository.getInstance(context).settings.first()
            }.getOrNull()

            val desiredMode = if (settings?.tunEnabled == true) {
                VpnStateStore.CoreMode.VPN
            } else {
                VpnStateStore.CoreMode.PROXY
            }

            if (settings?.tunEnabled == true) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            _connectionState.value = ConnectionState.Connecting

            // Ensure only one core instance is running at a time to avoid local port conflicts.
            // Do not rely on VpnStateStore here (multi-process timing); just stop the opposite service.
            val needToStopOpposite = when (desiredMode) {
                VpnStateStore.CoreMode.VPN -> {
                    runCatching {
                        context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_STOP
                        })
                    }
                    true
                }
                VpnStateStore.CoreMode.PROXY -> {
                    runCatching {
                        context.startService(Intent(context, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_STOP
                        })
                    }
                    true
                }
                else -> false
            }

            // 如果需要停止对立服务，等待其完全停止
            if (needToStopOpposite) {
                // 先检查对立服务是否正在运行
                val oppositeWasRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
                if (oppositeWasRunning) {
                    try {
                        withTimeout(3000L) {
                            // 使用 drop(1) 跳过当前值，等待真正的状态变化
                            SingBoxRemote.state
                                .drop(1)
                                .first { it == SingBoxService.ServiceState.STOPPED }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Timeout waiting for opposite service to stop")
                    }
                }
                // 额外缓冲时间确保网络接口释放（对于 QUIC 协议需要更长时间）
                delay(800)
            }
            
            // 生成配置文件并启动 VPN 服务
            try {
                // 在生成配置前先执行强制迁移，修复可能导致 404 的旧配置
                val configResult = withContext(Dispatchers.IO) {
                    val settingsRepository = com.kunk.singbox.repository.SettingsRepository.getInstance(context)
                    settingsRepository.checkAndMigrateRuleSets()
                    configRepository.generateConfigFile()
                }
                if (configResult == null) {
                    _connectionState.value = ConnectionState.Error
                    _testStatus.value = getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                    delay(2000)
                    _testStatus.value = null
                    return@launch
                }
                
                val useTun = desiredMode == VpnStateStore.CoreMode.VPN
                val intent = if (useTun) {
                    Intent(context, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, configResult.path)
                        // 从停止状态启动时，强制清理缓存，确保使用配置文件中选中的节点
                        // 修复 bug: App 更新后 cache.db 保留了旧的选中节点，导致 UI 上选中的新节点无效
                        putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                    }
                } else {
                    Intent(context, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_START
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configResult.path)
                        // 同理，Proxy 模式也需要清理缓存
                        putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // 1) 1000ms 内给出反馈：仍未 running 则提示“启动中”，但不判失败
                // 2) 后续只在服务端明确失败（lastErrorFlow）或服务异常退出时才置 Error
                startMonitorJob?.cancel()
                startMonitorJob = viewModelScope.launch {
                    val startTime = System.currentTimeMillis()
                    val quickFeedbackMs = 1000L
                    var showedStartingHint = false

                    while (true) {
                        if (SingBoxRemote.isRunning.value) {
                            _connectionState.value = ConnectionState.Connected
                            startTrafficMonitor()
                            return@launch
                        }

                        val err = SingBoxRemote.lastError.value
                        if (!err.isNullOrBlank()) {
                            _connectionState.value = ConnectionState.Error
                            _testStatus.value = err
                            delay(3000)
                            _testStatus.value = null
                            return@launch
                        }

                        val elapsed = System.currentTimeMillis() - startTime
                        if (!showedStartingHint && elapsed >= quickFeedbackMs) {
                            showedStartingHint = true
                            _testStatus.value = getApplication<Application>().getString(R.string.connection_connecting)
                            lastErrorToastJob?.cancel()
                            lastErrorToastJob = viewModelScope.launch {
                                delay(1200)
                                if (_testStatus.value == getApplication<Application>().getString(R.string.connection_connecting)) {
                                    _testStatus.value = null
                                }
                            }
                        }

                        val intervalMs = when {
                            elapsed < 10_000L -> 200L
                            elapsed < 60_000L -> 1000L
                            else -> 5000L
                        }
                        delay(intervalMs)
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error
                _testStatus.value = "Start failed: ${e.message}" // TODO: add to strings.xml
                delay(2000)
                _testStatus.value = null
            }
        }
    }
    
    private fun stopVpn() {
        val context = getApplication<Application>()
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
        // Immediately set to Idle for responsive UI
        _connectionState.value = ConnectionState.Idle
        _connectedAtElapsedMs.value = null
        _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
        _currentNodePing.value = null
        
        val mode = VpnStateStore.getMode(context)
        val intent = when (mode) {
            VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_STOP
            }
            else -> Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
        }
        context.startService(intent)
    }
    
    /**
     * 启动当前节点的延迟测试
     * 使用5秒超时限制，测不出来就终止并显示超时状态
     */
    private fun startPingTest() {
        // Prevent redundant testing if we already have a valid ping result
        // This stops the test from re-running every time the dashboard is opened/recomposed
        // UNLESS the ping is currently null (not tested) or being manually refreshed
        if (_connectionState.value == ConnectionState.Connected &&
            _currentNodePing.value != null &&
            _currentNodePing.value != -1L &&
            !_isPingTesting.value) {
            return
        }

        stopPingTest()

        _isPingTesting.value = true
        // Only clear current ping if we are manually retesting or it was failed/null.
        // If it was valid, keep showing old value until new one arrives?
        // No, UI usually shows spinner. Let's clear to indicate "refreshing".
        _currentNodePing.value = null
        
        pingTestJob = viewModelScope.launch {
            try {
                // 设置测试中状态
                _isPingTesting.value = true
                _currentNodePing.value = null
                
                // 等待一小段时间确保 VPN 完全启动
                delay(1000)
                
                // 检查 VPN 是否还在运行
                if (_connectionState.value != ConnectionState.Connected) {
                    _isPingTesting.value = false
                    return@launch
                }
                
                val activeNodeId = activeNodeId.value ?: withTimeoutOrNull(1500L) {
                    this@DashboardViewModel.activeNodeId.filterNotNull().first()
                }
                if (activeNodeId.isNullOrBlank()) {
                    Log.w(TAG, "No active node to test ping")
                    _isPingTesting.value = false
                    _currentNodePing.value = -1L // 标记为失败
                    return@launch
                }
                
                val nodeName = configRepository.getNodeById(activeNodeId)?.name
                if (nodeName == null) {
                    Log.w(TAG, "Node name not found for id: $activeNodeId")
                    _isPingTesting.value = false
                    _currentNodePing.value = -1L // 标记为失败
                    return@launch
                }
                
                Log.d(TAG, "Starting ping test for node: $nodeName (5s timeout)")
                
                // 使用5秒超时包装整个测试过程
                val delay = withTimeoutOrNull(5000L) {
                    configRepository.testNodeLatency(activeNodeId)
                }
                
                // 测试完成，更新状态
                _isPingTesting.value = false
                
                // 再次检查 VPN 是否还在运行（测试可能需要一些时间）
                if (_connectionState.value == ConnectionState.Connected && pingTestJob?.isActive == true) {
                    if (delay != null && delay > 0) {
                        _currentNodePing.value = delay
                        Log.d(TAG, "Ping test completed: ${delay}ms")
                    } else {
                        // 超时或失败，设置为 -1 表示超时
                        _currentNodePing.value = -1L
                        Log.w(TAG, "Ping test failed or timed out (5s)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during ping test", e)
                _isPingTesting.value = false
                _currentNodePing.value = -1L // 标记为失败
            }
        }
    }
    
    /**
     * 停止延迟测试
     */
    private fun stopPingTest() {
        pingTestJob?.cancel()
        pingTestJob = null
        _isPingTesting.value = false
    }

    fun retestCurrentNodePing() {
        if (_connectionState.value != ConnectionState.Connected) return
        if (_isPingTesting.value) return
        // Force test by clearing previous value to bypass the check in startPingTest
        _currentNodePing.value = null
        startPingTest()
    }
    
    fun onVpnPermissionResult(granted: Boolean) {
        _vpnPermissionNeeded.value = false
        if (granted) {
            startCore()
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _updateStatus.value = getApplication<Application>().getString(R.string.common_loading)
            
            val result = configRepository.updateAllProfiles()
            
            // 根据结果显示不同的提示
            _updateStatus.value = result.toDisplayMessage(getApplication())
            delay(2500)
            _updateStatus.value = null
        }
    }

    fun testAllNodesLatency() {
        viewModelScope.launch {
            _testStatus.value = getApplication<Application>().getString(R.string.common_loading)
            val targetIds = nodes.value.map { it.id }
            configRepository.testAllNodesLatency(targetIds)
            _testStatus.value = getApplication<Application>().getString(R.string.dashboard_test_complete)
            delay(2000)
            _testStatus.value = null
        }
    }
    
    private fun startTrafficMonitor() {
        stopTrafficMonitor()
        
        // 重置平滑缓存
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
        
        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
        val rx0 = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
        trafficBaseTxBytes = tx0
        trafficBaseRxBytes = rx0
        lastTrafficTxBytes = tx0
        lastTrafficRxBytes = rx0
        lastTrafficSampleAtElapsedMs = SystemClock.elapsedRealtime()

        trafficSmoothingJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)

                val nowElapsed = SystemClock.elapsedRealtime()
                val tx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
                val rx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }

                val dtMs = (nowElapsed - lastTrafficSampleAtElapsedMs).coerceAtLeast(1L)
                val dTx = (tx - lastTrafficTxBytes).coerceAtLeast(0L)
                val dRx = (rx - lastTrafficRxBytes).coerceAtLeast(0L)

                val up = (dTx * 1000L) / dtMs
                val down = (dRx * 1000L) / dtMs

                // 使用指数移动平均进行平滑处理，减少闪烁
                val smoothFactor = 0.3 // 平滑因子，越小越平滑
                val smoothedUp = if (lastUploadSpeed == 0L) up else (lastUploadSpeed * (1 - smoothFactor) + up * smoothFactor).toLong()
                val smoothedDown = if (lastDownloadSpeed == 0L) down else (lastDownloadSpeed * (1 - smoothFactor) + down * smoothFactor).toLong()

                lastUploadSpeed = smoothedUp
                lastDownloadSpeed = smoothedDown

                val totalTx = (tx - trafficBaseTxBytes).coerceAtLeast(0L)
                val totalRx = (rx - trafficBaseRxBytes).coerceAtLeast(0L)

                _statsBase.update { current ->
                    current.copy(
                        uploadSpeed = smoothedUp,
                        downloadSpeed = smoothedDown,
                        uploadTotal = totalTx,
                        downloadTotal = totalRx
                    )
                }

                lastTrafficTxBytes = tx
                lastTrafficRxBytes = rx
                lastTrafficSampleAtElapsedMs = nowElapsed
            }
        }
    }
    
    private fun stopTrafficMonitor() {
        trafficSmoothingJob?.cancel()
        trafficSmoothingJob = null
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
        trafficBaseTxBytes = 0
        trafficBaseRxBytes = 0
        lastTrafficTxBytes = 0
        lastTrafficRxBytes = 0
        lastTrafficSampleAtElapsedMs = 0
    }
    
    private fun getRegionWeight(flag: String?): Int {
        if (flag.isNullOrBlank()) return 9999
        // Priority order: CN, HK, MO, TW, JP, KR, SG, US, Others
        return when (flag) {
            "🇨🇳" -> 0   // China
            "🇭🇰" -> 1   // Hong Kong
            "🇲🇴" -> 2   // Macau
            "🇹🇼" -> 3   // Taiwan
            "🇯🇵" -> 4   // Japan
            "🇰🇷" -> 5   // South Korea
            "🇸🇬" -> 6   // Singapore
            "🇺🇸" -> 7   // USA
            "🇻🇳" -> 8   // Vietnam
            "🇹🇭" -> 9   // Thailand
            "🇵🇭" -> 10  // Philippines
            "🇲🇾" -> 11  // Malaysia
            "🇮🇩" -> 12  // Indonesia
            "🇮🇳" -> 13  // India
            "🇷🇺" -> 14  // Russia
            "🇹🇷" -> 15  // Turkey
            "🇮🇹" -> 16  // Italy
            "🇩🇪" -> 17  // Germany
            "🇫🇷" -> 18  // France
            "🇳🇱" -> 19  // Netherlands
            "🇬🇧" -> 20  // UK
            "🇦🇺" -> 21  // Australia
            "🇨🇦" -> 22  // Canada
            "🇧🇷" -> 23  // Brazil
            "🇦🇷" -> 24  // Argentina
            else -> 1000 // Others
        }
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
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
    }
}
