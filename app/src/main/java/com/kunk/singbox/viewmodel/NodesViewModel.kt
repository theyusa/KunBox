package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NodesViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configRepository = ConfigRepository.getInstance(application)

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()
    
    // 正在测试延迟的节点 ID 集合
    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val testingNodeIds: StateFlow<Set<String>> = _testingNodeIds.asStateFlow()

    val nodes: StateFlow<List<NodeUi>> = configRepository.nodes
        .stateIn(
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

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun setActiveNode(nodeId: String) {
        configRepository.setActiveNode(nodeId)
    }
    
    fun testLatency(nodeId: String) {
        viewModelScope.launch {
            _testingNodeIds.value = _testingNodeIds.value + nodeId
            try {
                configRepository.testNodeLatency(nodeId)
            } finally {
                _testingNodeIds.value = _testingNodeIds.value - nodeId
            }
        }
    }

    fun testAllLatency() {
        if (_isTesting.value) return
        
        viewModelScope.launch {
            _isTesting.value = true
            // 将所有节点加入测试中
            val allIds = nodes.value.map { it.id }.toSet()
            _testingNodeIds.value = allIds
            try {
                configRepository.testAllNodesLatency()
            } finally {
                _isTesting.value = false
                _testingNodeIds.value = emptySet()
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
}
