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
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.core.BoxWrapperManager
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
                val msg = getApplication<Application>().getString(R.string.node_switch_success, name)
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
        }

        // 2025-fix: 如果VPN正在运行，切换配置后需要触发热切换/重启以加载新配置
        // 否则VPN仍然使用旧配置，导致用户看到"选中"了新配置的节点但实际没网
        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
            viewModelScope.launch {
                // 等待配置切换完成（setActiveProfile 内部可能有异步加载）
                delay(100)
                // 获取新配置的当前选中节点
                val currentNodeId = configRepository.activeNodeId.value
                if (currentNodeId != null) {
                    Log.i(TAG, "Profile switched while VPN running, triggering node switch for: $currentNodeId")
                    configRepository.setActiveNodeWithResult(currentNodeId)
                }
            }
        }
    }

    fun setActiveNode(nodeId: String) {
        // 2025-fix: 先同步更新 activeNodeId，避免竞态条件
        configRepository.setActiveNodeIdOnly(nodeId)

        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val result = configRepository.setActiveNodeWithResult(nodeId)

            if (SingBoxRemote.isRunning.value && node != null) {
                val msg = when (result) {
                    is ConfigRepository.NodeSwitchResult.Success,
                    is ConfigRepository.NodeSwitchResult.NotRunning -> getApplication<Application>().getString(R.string.node_switch_success, node.name)

                    is ConfigRepository.NodeSwitchResult.Failed ->
                        getApplication<Application>().getString(R.string.node_switch_failed, node.name)
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
    ) { nodes: List<NodeUi>, filter: NodeFilter, sortType: NodeSortType, customOrder: List<String>, _ ->
        val filtered = when (filter.filterMode) {
            FilterMode.NONE -> nodes
            FilterMode.INCLUDE -> {
                val keywords = filter.effectiveIncludeKeywords
                if (keywords.isEmpty()) nodes
                else nodes.filter { node -> keywords.any { keyword -> node.displayName.contains(keyword, ignoreCase = true) } }
            }
            FilterMode.EXCLUDE -> {
                val keywords = filter.effectiveExcludeKeywords
                if (keywords.isEmpty()) nodes
                else nodes.filter { node -> keywords.none { keyword -> node.displayName.contains(keyword, ignoreCase = true) } }
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

        // 2025-fix: 移除自动选择第一个节点的逻辑
        // 原因: 配置切换时 ProfilesViewModel/DashboardViewModel.setActiveProfile() 已经处理了节点切换
        // 这里再次调用 setActiveNode() 会导致重复触发 VPN 重启，造成 TG 等应用二次加载
        // 如果用户过滤后当前节点不在列表中，UI 只需显示第一个节点即可，不需要强制切换 VPN

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

    // 2025-fix: 用于确保状态监听只在 IPC 绑定后启动一次
    @Volatile private var stateCollectorStarted = false

    // 2025-fix: 标记是否在启动时检测到了系统 VPN
    // 用于过滤 IPC 连接初期的虚假 STOPPED 状态
    private var systemVpnDetectedOnBoot = false

    init {
        viewModelScope.launch {
            settingsRepository.getNodeFilterFlow().collect {
                _nodeFilter.value = it
            }
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
        // Ensure IPC is bound before subscribing to state flows
        // This prevents stale state when app returns from background
        viewModelScope.launch {
            runCatching { SingBoxRemote.ensureBound(getApplication()) }

            // Wait for IPC binding to complete (with timeout)
            var retries = 0
            while (!SingBoxRemote.isBound() && retries < 20) {
                delay(50)
                retries++
            }

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

                // 记录系统 VPN 状态
                if (hasSystemVpn) {
                    systemVpnDetectedOnBoot = true
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

            // 2025-fix: IPC 绑定完成后再启动状态监听
            // 避免在绑定前收到过时的初始值 STOPPED 导致 UI 显示断开
            startStateCollector()
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
    }

    /**
     * 2025-fix: 启动状态监听器
     * 确保只在 IPC 绑定完成后调用一次
     */
    // 2025-fix: 用于处理连接状态变更的防抖 Job
    private var pendingIdleJob: Job? = null
    private var startGraceUntilElapsedMs: Long? = null

    /**
     * 统一管理连接状态更新，内置防抖逻辑防止 UI 闪烁
     */
    private fun setConnectionState(newState: ConnectionState) {
        if (newState == ConnectionState.Disconnecting && _connectionState.value == ConnectionState.Connecting) {
            val graceUntil = startGraceUntilElapsedMs
            if (graceUntil != null && SystemClock.elapsedRealtime() < graceUntil) {
                return
            }
        }
        when (newState) {
            ConnectionState.Connected -> {
                // 如果有挂起的"变更为Idle"的任务，立即取消，说明是虚惊一场
                pendingIdleJob?.cancel()
                pendingIdleJob = null
                startGraceUntilElapsedMs = null

                if (_connectionState.value != ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                    startTrafficMonitor()
                }
            }
            ConnectionState.Idle -> {
                // 如果当前是已连接，不要立即断开，而是延迟执行
                if (_connectionState.value == ConnectionState.Connected) {
                    // 如果已经在等待断开，不要重复创建
                    if (pendingIdleJob?.isActive == true) return

                    pendingIdleJob = viewModelScope.launch {
                        // 如果是启动时的检测，给更长的宽限期 (1000ms)，否则给普通的防抖期 (300ms)
                        val delayTime = if (systemVpnDetectedOnBoot) 1000L else 300L
                        delay(delayTime)

                        // 宽限期过，再次检查 SingBoxRemote 状态
                        // 只有当服务端依然坚持是 STOPPED 时，才真正断开 UI
                        if (SingBoxRemote.state.value == ServiceState.STOPPED) {
                            performDisconnect()
                        }
                        // 宽限期结束，标记失效
                        systemVpnDetectedOnBoot = false
                        pendingIdleJob = null
                    }
                } else if (_connectionState.value == ConnectionState.Connecting) {
                    val graceUntil = startGraceUntilElapsedMs
                    if (graceUntil != null) {
                        val now = SystemClock.elapsedRealtime()
                        val remaining = graceUntil - now
                        if (remaining > 0) {
                            if (pendingIdleJob?.isActive == true) return
                            pendingIdleJob = viewModelScope.launch {
                                delay(remaining)
                                if (SingBoxRemote.state.value == ServiceState.STOPPED) {
                                    performDisconnect()
                                }
                                pendingIdleJob = null
                            }
                            return
                        }
                    }
                    performDisconnect()
                } else {
                    // 当前不是连接状态，直接更新
                    performDisconnect()
                }
            }
            else -> {
                // 其他状态（Connecting/Disconnecting/Error）直接更新
                pendingIdleJob?.cancel()
                if (newState == ConnectionState.Connecting) {
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 800L
                } else {
                    startGraceUntilElapsedMs = null
                }
                if (_connectionState.value != newState) {
                    _connectionState.value = newState
                }
            }
        }
    }

    private fun performDisconnect() {
        if (_connectionState.value != ConnectionState.Idle) {
            _connectionState.value = ConnectionState.Idle
            _connectedAtElapsedMs.value = null
            stopTrafficMonitor()
            stopPingTest()
            _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
            _currentNodePing.value = null
        }
    }

    private fun startStateCollector() {
        if (stateCollectorStarted) return
        stateCollectorStarted = true

        // Observe SingBoxService state to keep UI in sync
        viewModelScope.launch {
            SingBoxRemote.state.collect { state ->
                when (state) {
                    ServiceState.RUNNING -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connected)
                    }
                    ServiceState.STARTING -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connecting)
                    }
                    ServiceState.STOPPING -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Disconnecting)
                    }
                    ServiceState.STOPPED -> {
                        setConnectionState(ConnectionState.Idle)
                    }
                }
            }
        }

        // 2025-fix: 监听服务端节点切换，同步更新主进程的 activeNodeId
        // 解决通知栏切换节点后首页显示旧节点的问题
        viewModelScope.launch {
            SingBoxRemote.activeLabel
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collect { nodeName ->
                    Log.d(TAG, "activeLabel changed from service: $nodeName")
                    configRepository.syncActiveNodeFromProxySelection(nodeName)
                }
        }
    }

    /**
     * 2025-fix-v6: 刷新 VPN 状态 (增强版)
     *
     * 核心改进:
     * 1. 回调超时检测 - 如果回调通道失效，不再依赖它
     * 2. 强制从 VpnStateStore 同步 - 直接读取跨进程共享的真实状态
     * 3. 强制重连 - rebind() 直接断开再重连，确保回调通道畅通
     *
     * 这是解决 "后台恢复后 UI 一直加载中" 问题的关键修复
     */
    fun refreshState() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            // 2025-fix-v6: 第一步 - 立即从 VpnStateStore 恢复状态
            // 这确保 UI 不会显示过时状态，即使 IPC 还没完成
            SingBoxRemote.forceStoreSync()

            // 同步更新 UI 状态
            val isActive = VpnStateStore.getActive()
            if (isActive) {
                setConnectionState(ConnectionState.Connected)
            } else if (!SingBoxRemote.isStarting.value) {
                setConnectionState(ConnectionState.Idle)
            }

            // 2025-fix-v6: 第二步 - 检测回调通道是否超时
            val isCallbackStale = SingBoxRemote.isCallbackStale()
            val lastSyncAge = SingBoxRemote.getLastSyncAge()

            if (isCallbackStale || lastSyncAge > 30_000L) {
                // 回调通道可能失效，强制重连
                Log.w(TAG, "refreshState: callback stale (age=${lastSyncAge}ms), forcing rebind")
                SingBoxRemote.rebind(context)
            } else {
                // 回调通道正常，尝试同步
                val synced = runCatching { SingBoxRemote.queryAndSyncState(context) }.getOrDefault(false)

                if (!synced) {
                    Log.w(TAG, "refreshState: queryAndSyncState failed, forcing rebind")
                    SingBoxRemote.rebind(context)
                }
            }

            // 等待 IPC 绑定完成
            var retries = 0
            while (!SingBoxRemote.isBound() && retries < 15) {
                delay(100)
                retries++
            }

            // 最终状态同步
            val state = SingBoxRemote.state.value
            Log.i(TAG, "refreshState: state=$state, bound=${SingBoxRemote.isBound()}")

            when (state) {
                ServiceState.RUNNING -> setConnectionState(ConnectionState.Connected)
                ServiceState.STARTING -> setConnectionState(ConnectionState.Connecting)
                ServiceState.STOPPING -> setConnectionState(ConnectionState.Disconnecting)
                ServiceState.STOPPED -> setConnectionState(ConnectionState.Idle)
            }

            startStateCollector()
        }
    }

    /**
     * 检查系统是否有活跃的 VPN 连接
     */
    private fun checkSystemVpn(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system VPN", e)
            false
        }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            when (_connectionState.value) {
                ConnectionState.Idle, ConnectionState.Error -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 800L
                    _connectionState.value = ConnectionState.Connecting
                    startCore()
                }
                ConnectionState.Connecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
                ConnectionState.Connected, ConnectionState.Disconnecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
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

            val configResult = withContext(Dispatchers.IO) {
                val settingsRepository = SettingsRepository.getInstance(context)
                settingsRepository.checkAndMigrateRuleSets()
                configRepository.generateConfigFile()
            }

            if (configResult == null) {
                _testStatus.value = getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                delay(2000)
                _testStatus.value = null
                return@launch
            }

            val useTun = settings.tunEnabled
            val perAppSettingsChanged = VpnStateStore.hasPerAppVpnSettingsChanged(
                appMode = settings.vpnAppMode.name,
                allowlist = settings.vpnAllowlist,
                blocklist = settings.vpnBlocklist
            )

            logRestartDebugInfo(settings)

            val tunSettingsChanged = VpnStateStore.hasTunSettingsChanged(
                tunStack = settings.tunStack.name,
                tunMtu = settings.tunMtu,
                autoRoute = settings.autoRoute,
                strictRoute = settings.strictRoute,
                proxyPort = settings.proxyPort
            )

            val requiresFullRestart = perAppSettingsChanged || tunSettingsChanged

            if (useTun && SingBoxRemote.isRunning.value && !requiresFullRestart) {
                Log.i(TAG, "Settings are hot-reloadable, attempting kernel hot reload")
                if (tryHotReload(configResult.path)) {
                    Log.i(TAG, "Hot reload succeeded, settings applied without VPN reconnection")
                    return@launch
                }
                Log.w(TAG, "Hot reload failed, falling back to full restart")
            } else {
                if (requiresFullRestart) {
                    Log.i(
                        TAG,
                        "Full restart required: perAppChanged=$perAppSettingsChanged, tunChanged=$tunSettingsChanged"
                    )
                }
            }

            performRestart(context, configResult.path, useTun, perAppSettingsChanged)
        }
    }

    private fun logRestartDebugInfo(settings: AppSettings) {
        Log.d(
            TAG,
            "restartVpn: useTun=${settings.tunEnabled}, isRunning=${SingBoxRemote.isRunning.value}"
        )
        Log.d(
            TAG,
            "restartVpn: currentMode=${settings.vpnAppMode.name}, " +
                "allowlist=${settings.vpnAllowlist.take(100)}, blocklist=${settings.vpnBlocklist.take(100)}"
        )
    }

    private suspend fun tryHotReload(configPath: String): Boolean {
        val configContent = withContext(Dispatchers.IO) {
            runCatching { java.io.File(configPath).readText() }.getOrNull()
        }

        if (!configContent.isNullOrEmpty()) {
            Log.i(TAG, "Attempting kernel hot reload via IPC...")

            val result = withContext(Dispatchers.IO) {
                SingBoxRemote.hotReloadConfig(configContent)
            }

            when (result) {
                SingBoxRemote.HotReloadResult.SUCCESS -> {
                    Log.i(TAG, "Hot reload succeeded via IPC")
                    return true
                }
                SingBoxRemote.HotReloadResult.IPC_ERROR -> {
                    Log.w(TAG, "Hot reload IPC failed, falling back to traditional restart")
                }
                else -> {
                    Log.w(TAG, "Hot reload failed (code=$result), falling back to traditional restart")
                }
            }
        }
        return false
    }

    private suspend fun performRestart(
        context: Context,
        configPath: String,
        useTun: Boolean,
        perAppSettingsChanged: Boolean
    ) {
        if (perAppSettingsChanged && useTun && SingBoxRemote.isRunning.value) {
            Log.i(TAG, "Per-app settings changed, using full restart to rebuild TUN")
            val intent = Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_FULL_RESTART
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            }
            startServiceCompat(context, intent)
            return
        }

        runCatching {
            if (!com.kunk.singbox.ipc.VpnStateStore.shouldTriggerPrepareRestart(1500L)) {
                Log.d(TAG, "PREPARE_RESTART suppressed (sender throttle)")
            } else {
                context.startService(Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_PREPARE_RESTART
                    putExtra(
                        SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                        "DashboardViewModel:restartVpn"
                    )
                })
            }
        }

        delay(150)

        val intent = if (useTun) {
            Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_START
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        } else {
            Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_START
                putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        }

        startServiceCompat(context, intent)
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
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
                                .first { it == ServiceState.STOPPED }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Timeout waiting for opposite service to stop")
                    }
                }
                // 优化: 减少缓冲时间从 800ms 到 200ms
                // 原因: 已经通过状态机等待 STOPPED,只需短暂缓冲即可
                delay(200)
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
                _testStatus.value = getApplication<Application>().getString(R.string.node_start_failed, e.message ?: "")
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

        val mode = VpnStateStore.getMode()
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

        // 记录 BoxWrapper 初始流量值 (用于计算本次会话流量)
        wrapperBaseUpload = BoxWrapperManager.getUploadTotal().let { if (it >= 0) it else 0L }
        wrapperBaseDownload = BoxWrapperManager.getDownloadTotal().let { if (it >= 0) it else 0L }

        trafficSmoothingJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)

                val nowElapsed = SystemClock.elapsedRealtime()

                // 双源流量统计: 优先使用 BoxWrapper (内核级), 回退到 TrafficStats (系统级)
                val (tx, rx, totalTx, totalRx) = if (BoxWrapperManager.isAvailable()) {
                    // 使用 BoxWrapper 内核级流量统计 (更准确)
                    val wrapperUp = BoxWrapperManager.getUploadTotal()
                    val wrapperDown = BoxWrapperManager.getDownloadTotal()
                    if (wrapperUp >= 0 && wrapperDown >= 0) {
                        // 计算本次会话流量
                        val sessionUp = (wrapperUp - wrapperBaseUpload).coerceAtLeast(0L)
                        val sessionDown = (wrapperDown - wrapperBaseDownload).coerceAtLeast(0L)
                        Quadruple(wrapperUp, wrapperDown, sessionUp, sessionDown)
                    } else {
                        // BoxWrapper 返回无效值，回退到 TrafficStats
                        val sysTx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
                        val sysRx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
                        Quadruple(sysTx, sysRx, (sysTx - trafficBaseTxBytes).coerceAtLeast(0L), (sysRx - trafficBaseRxBytes).coerceAtLeast(0L))
                    }
                } else {
                    // BoxWrapper 不可用，使用 TrafficStats
                    val sysTx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
                    val sysRx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
                    Quadruple(sysTx, sysRx, (sysTx - trafficBaseTxBytes).coerceAtLeast(0L), (sysRx - trafficBaseRxBytes).coerceAtLeast(0L))
                }

                val dtMs = (nowElapsed - lastTrafficSampleAtElapsedMs).coerceAtLeast(1L)
                val dTx = (tx - lastTrafficTxBytes).coerceAtLeast(0L)
                val dRx = (rx - lastTrafficRxBytes).coerceAtLeast(0L)

                val up = (dTx * 1000L) / dtMs
                val down = (dRx * 1000L) / dtMs

                // 优化: 使用自适应平滑因子，根据速度变化幅度动态调整
                // 优势: 大幅变化时快速响应,小幅变化时平滑显示，兼顾响应性和稳定性
                val uploadSmoothFactor = calculateAdaptiveSmoothFactor(up, lastUploadSpeed)
                val downloadSmoothFactor = calculateAdaptiveSmoothFactor(down, lastDownloadSpeed)

                val smoothedUp = if (lastUploadSpeed == 0L) up
                else (lastUploadSpeed * (1 - uploadSmoothFactor) + up * uploadSmoothFactor).toLong()
                val smoothedDown = if (lastDownloadSpeed == 0L) down
                else (lastDownloadSpeed * (1 - downloadSmoothFactor) + down * downloadSmoothFactor).toLong()

                lastUploadSpeed = smoothedUp
                lastDownloadSpeed = smoothedDown

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

    // 用于双源流量统计的辅助数据类
    private data class Quadruple(val tx: Long, val rx: Long, val totalTx: Long, val totalRx: Long)

    // BoxWrapper 流量基准值 (用于计算本次会话流量)
    private var wrapperBaseUpload: Long = 0
    private var wrapperBaseDownload: Long = 0

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
        wrapperBaseUpload = 0
        wrapperBaseDownload = 0
    }

    /**
     * 计算自适应平滑因子
     * @param current 当前速度
     * @param previous 上一次速度
     * @return 平滑因子 (0.0-1.0),值越大响应越快
     */
    private fun calculateAdaptiveSmoothFactor(current: Long, previous: Long): Double {
        // 处理零值情况
        if (previous <= 0) return 1.0

        // 计算变化幅度比例
        val change = kotlin.math.abs(current - previous).toDouble()
        val ratio = change / previous

        // 根据变化幅度返回不同的平滑因子
        return when {
            ratio > 2.0 -> 0.7 // 大幅变化(200%+),快速响应
            ratio > 0.5 -> 0.4 // 中等变化(50%-200%),平衡响应
            ratio > 0.1 -> 0.25 // 小幅变化(10%-50%),适度平滑
            else -> 0.15 // 微小变化(<10%),高度平滑
        }
    }

    private fun getRegionWeight(flag: String?): Int {
        if (flag.isNullOrBlank()) return 9999
        // Priority order: CN, HK, MO, TW, JP, KR, SG, US, Others
        return when (flag) {
            "🇨🇳" -> 0 // China
            "🇭🇰" -> 1 // Hong Kong
            "🇲🇴" -> 2 // Macau
            "🇹🇼" -> 3 // Taiwan
            "🇯🇵" -> 4 // Japan
            "🇰🇷" -> 5 // South Korea
            "🇸🇬" -> 6 // Singapore
            "🇺🇸" -> 7 // USA
            "🇻🇳" -> 8 // Vietnam
            "🇹🇭" -> 9 // Thailand
            "🇵🇭" -> 10 // Philippines
            "🇲🇾" -> 11 // Malaysia
            "🇮🇩" -> 12 // Indonesia
            "🇮🇳" -> 13 // India
            "🇷🇺" -> 14 // Russia
            "🇹🇷" -> 15 // Turkey
            "🇮🇹" -> 16 // Italy
            "🇩🇪" -> 17 // Germany
            "🇫🇷" -> 18 // France
            "🇳🇱" -> 19 // Netherlands
            "🇬🇧" -> 20 // UK
            "🇦🇺" -> 21 // Australia
            "🇨🇦" -> 22 // Canada
            "🇧🇷" -> 23 // Brazil
            "🇦🇷" -> 24 // Argentina
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
     * 使用改进的 getNodeById 方法确保即使配置切换或节点列表未完全加载时也能正确显示
     */
    fun getActiveNodeName(): String? {
        val activeId = activeNodeId.value ?: return null
        return configRepository.getNodeById(activeId)?.displayName
    }

    override fun onCleared() {
        super.onCleared()
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
    }
}
