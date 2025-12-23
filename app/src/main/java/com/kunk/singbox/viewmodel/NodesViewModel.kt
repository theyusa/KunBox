package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 过滤模式枚举
enum class FilterMode {
    NONE,      // 不过滤
    INCLUDE,   // 只显示包含关键字的节点
    EXCLUDE    // 排除包含关键字的节点
}

// 节点过滤配置数据类
data class NodeFilter(
    val keywords: List<String> = emptyList(),
    val filterMode: FilterMode = FilterMode.NONE
)

class NodesViewModel(application: Application) : AndroidViewModel(application) {
    
    enum class SortType {
        DEFAULT, LATENCY, NAME, REGION
    }
    
    private val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    private var testingJob: Job? = null

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()
    
    // 正在测试延迟的节点 ID 集合
    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val testingNodeIds: StateFlow<Set<String>> = _testingNodeIds.asStateFlow()
    
    private val _sortType = MutableStateFlow(SortType.DEFAULT)
    val sortType: StateFlow<SortType> = _sortType.asStateFlow()

    // 节点过滤状态
    private val _nodeFilter = MutableStateFlow(NodeFilter())
    val nodeFilter: StateFlow<NodeFilter> = _nodeFilter.asStateFlow()

    init {
        // 加载保存的过滤配置
        viewModelScope.launch {
            _nodeFilter.value = settingsRepository.getNodeFilter()
        }
    }

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        _sortType,
        _nodeFilter
    ) { nodes, sortType, filter ->
        // 先过滤
        val filtered = when (filter.filterMode) {
            FilterMode.NONE -> nodes
            FilterMode.INCLUDE -> {
                if (filter.keywords.isEmpty()) {
                    nodes
                } else {
                    nodes.filter { node ->
                        filter.keywords.any { keyword ->
                            node.displayName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
            }
            FilterMode.EXCLUDE -> {
                if (filter.keywords.isEmpty()) {
                    nodes
                } else {
                    nodes.filter { node ->
                        filter.keywords.none { keyword ->
                            node.displayName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
            }
        }
        // 再排序
        when (sortType) {
            SortType.DEFAULT -> filtered
            SortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
                val l = it.latencyMs
                // 将未测试(null)和超时/失败(<=0)的节点排到最后
                if (l == null || l <= 0) Long.MAX_VALUE else l
            })
            SortType.NAME -> filtered.sortedBy { it.name }
            SortType.REGION -> filtered.sortedBy { it.regionFlag ?: "\uFFFF" } // Put no flag at end
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val nodeGroups: StateFlow<List<String>> = configRepository.nodeGroups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf("全部")
        )

    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allNodeGroups: StateFlow<List<String>> = configRepository.allNodeGroups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _switchResult = MutableStateFlow<String?>(null)
    val switchResult: StateFlow<String?> = _switchResult.asStateFlow()

    // 单节点测速反馈信息（仅在失败/超时时提示）
    private val _latencyMessage = MutableStateFlow<String?>(null)
    val latencyMessage: StateFlow<String?> = _latencyMessage.asStateFlow()

    // 添加节点结果反馈
    private val _addNodeResult = MutableStateFlow<String?>(null)
    val addNodeResult: StateFlow<String?> = _addNodeResult.asStateFlow()

    fun setActiveNode(nodeId: String) {
        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val success = configRepository.setActiveNode(nodeId)
            if (SingBoxRemote.isRunning.value && node != null) {
                _switchResult.value = if (success) {
                    "已切换到 ${node.name}"
                } else {
                    "切换到 ${node.name} 失败"
                }
            }
        }
    }

    fun clearSwitchResult() {
        _switchResult.value = null
    }
    
    fun testLatency(nodeId: String) {
        if (_testingNodeIds.value.contains(nodeId)) return
        viewModelScope.launch {
            _testingNodeIds.value = _testingNodeIds.value + nodeId
            try {
                val node = nodes.value.find { it.id == nodeId }
                val latency = configRepository.testNodeLatency(nodeId)
                if (latency <= 0) {
                    _latencyMessage.value = "${node?.displayName ?: "该节点"} 测速失败/超时"
                }
            } finally {
                _testingNodeIds.value = _testingNodeIds.value - nodeId
            }
        }
    }

    fun clearLatencyMessage() {
        _latencyMessage.value = null
    }

    fun clearAddNodeResult() {
        _addNodeResult.value = null
    }

    fun testAllLatency() {
        if (_isTesting.value) {
            // 如果正在测试，则取消测试
            testingJob?.cancel()
            testingJob = null
            _isTesting.value = false
            _testingNodeIds.value = emptySet()
            return
        }
        
        testingJob = viewModelScope.launch {
            _isTesting.value = true
            // 测速期间暂停排序，防止列表跳动 (暂时切回默认排序，或者保持当前排序但锁定更新?)
            // 这里为了简单且符合用户"最后一次性排好序"的要求，我们暂时将排序设为 DEFAULT
            _sortType.value = SortType.DEFAULT
            
            // 只测试当前列表显示的节点（已过滤）
            val currentNodes = nodes.value
            val targetIds = currentNodes.map { it.id }
            _testingNodeIds.value = targetIds.toSet()

            try {
                // 使用 ConfigRepository 的批量测试功能，它已经在内部实现了并行化
                configRepository.testAllNodesLatency(targetIds) { finishedNodeId ->
                    _testingNodeIds.value = _testingNodeIds.value - finishedNodeId
                }
                // 测速完成后自动切换到延迟排序
                setSortType(SortType.LATENCY)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isTesting.value = false
                _testingNodeIds.value = emptySet()
                testingJob = null
            }
        }
    }

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            configRepository.deleteNode(nodeId)
        }
    }

    fun exportNode(nodeId: String): String? {
        return configRepository.exportNode(nodeId)
    }
    
    fun setSortType(type: SortType) {
        _sortType.value = type
    }
    
    // 设置节点过滤条件
    fun setNodeFilter(filter: NodeFilter) {
        _nodeFilter.value = filter
        viewModelScope.launch {
            settingsRepository.setNodeFilter(filter)
        }
    }
    
    // 清除节点过滤条件
    fun clearNodeFilter() {
        val emptyFilter = NodeFilter()
        _nodeFilter.value = emptyFilter
        viewModelScope.launch {
            settingsRepository.setNodeFilter(emptyFilter)
        }
    }
    
    fun clearLatency() {
        viewModelScope.launch {
            configRepository.clearAllNodesLatency()
        }
    }
    
    fun addNode(content: String) {
        viewModelScope.launch {
            val trimmedContent = content.trim()
            
            // 检查是否是支持的节点链接格式
            val supportedPrefixes = listOf(
                "vmess://", "vless://", "ss://", "trojan://",
                "hysteria2://", "hy2://", "hysteria://",
                "tuic://", "anytls://", "wireguard://", "ssh://"
            )
            
            if (supportedPrefixes.none { trimmedContent.startsWith(it) }) {
                _addNodeResult.value = "不支持的链接格式"
                return@launch
            }
            
            val result = configRepository.addSingleNode(trimmedContent)
            result.onSuccess { node ->
                _addNodeResult.value = "已添加节点: ${node.displayName}"
            }.onFailure { e ->
                _addNodeResult.value = e.message ?: "添加失败"
            }
        }
    }
}
