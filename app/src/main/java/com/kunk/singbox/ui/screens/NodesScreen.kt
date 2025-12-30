package com.kunk.singbox.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.viewmodel.FilterMode
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.NodeFilterDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.NodeCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Primary
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodesScreen(
    navController: NavController,
    viewModel: NodesViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val nodes by viewModel.nodes.collectAsState()
    val activeNodeId by viewModel.activeNodeId.collectAsState()
    val groups by viewModel.nodeGroups.collectAsState()
    val testingNodeIds by viewModel.testingNodeIds.collectAsState()
    val nodeFilter by viewModel.nodeFilter.collectAsState()

    var selectedGroupIndex by remember { mutableStateOf(0) }
    val isTesting by viewModel.isTesting.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 当groups变化时重置索引，避免越界
    LaunchedEffect(groups) {
        if (selectedGroupIndex >= groups.size) {
            selectedGroupIndex = 0
        }
    }

    // Filter nodes based on selected group
    val filteredNodes by remember {
        androidx.compose.runtime.derivedStateOf {
            if (selectedGroupIndex == 0 || groups.isEmpty()) {
                nodes
            } else {
                val selectedGroup = groups.getOrNull(selectedGroupIndex)
                if (selectedGroup == null) nodes else nodes.filter { it.group == selectedGroup }
            }
        }
    }

    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var exportLink by remember { mutableStateOf<String?>(null) }
    var isFabExpanded by remember { mutableStateOf(false) }
    var showAddNodeDialog by remember { mutableStateOf(false) }

    if (showSortDialog) {
        val sortOptions = listOf(
            "默认" to NodesViewModel.SortType.DEFAULT,
            "延迟 (低 -> 高)" to NodesViewModel.SortType.LATENCY,
            "名称 (A -> Z)" to NodesViewModel.SortType.NAME,
            "地区" to NodesViewModel.SortType.REGION
        )

        SingleSelectDialog(
            title = "排序方式",
            options = sortOptions.map { it.first },
            selectedIndex = -1,
            onSelect = { index ->
                viewModel.setSortType(sortOptions[index].second)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    if (showAddNodeDialog) {
        InputDialog(
            title = "添加节点",
            placeholder = "输入节点链接 (vmess://, vless://, ss://, etc)...",
            confirmText = "添加",
            onConfirm = {
                viewModel.addNode(it)
                showAddNodeDialog = false
            },
            onDismiss = { showAddNodeDialog = false }
        )
    }

    // 节点筛选对话框
    if (showFilterDialog) {
        NodeFilterDialog(
            currentFilter = nodeFilter,
            onConfirm = { filter ->
                viewModel.setNodeFilter(filter)
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }

    if (exportLink != null) {
        InputDialog(
            title = "导出链接",
            initialValue = exportLink!!,
            confirmText = "复制",
            onConfirm = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                exportLink = null
            },
            onDismiss = { exportLink = null }
        )
    }

    Scaffold(
        modifier = Modifier
            .background(AppBackground)
            .statusBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        // Clear Latency
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "清空延迟",
                                color = PureWhite,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            SmallFloatingActionButton(
                                onClick = {
                                    viewModel.clearLatency()
                                    isFabExpanded = false
                                },
                                containerColor = PureWhite,
                                contentColor = Color.Black
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "清空延迟")
                            }
                        }
                        
                        // Add Node
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "添加节点",
                                color = PureWhite,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            SmallFloatingActionButton(
                                onClick = {
                                    showAddNodeDialog = true
                                    isFabExpanded = false
                                },
                                containerColor = PureWhite,
                                contentColor = Color.Black
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "添加节点")
                            }
                        }
                        
                        // Test Latency
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isTesting) "停止测试" else "测试延迟",
                                color = PureWhite,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            SmallFloatingActionButton(
                                onClick = {
                                    viewModel.testAllLatency()
                                    isFabExpanded = false
                                },
                                containerColor = PureWhite,
                                contentColor = Color.Black
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.Black,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Rounded.Bolt, contentDescription = "测试延迟")
                                }
                            }
                        }
                    }
                }
                
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = PureWhite,
                    contentColor = Color.Black
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = "菜单"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // 1. Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "节点列表",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 筛选按钮（替换原来的启动按钮）
                    IconButton(onClick = { showFilterDialog = true }) {
                        val hasFilter = nodeFilter.filterMode != FilterMode.NONE
                        Icon(
                            imageVector = Icons.Rounded.FilterAlt,
                            contentDescription = "筛选",
                            tint = if (hasFilter) Primary else PureWhite
                        )
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = "排序", tint = PureWhite)
                    }
                }
            }

            // 2. Group Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedGroupIndex,
                contentColor = PureWhite,
                modifier = Modifier.background(AppBackground),
                edgePadding = 16.dp,
                divider = {},
                indicator = {}
            ) {
                groups.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedGroupIndex == index,
                        onClick = { selectedGroupIndex = index },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    Toast.makeText(context, "分组由配置自动生成，如需删除请前往'配置管理'删除对应配置", Toast.LENGTH_LONG).show()
                                },
                                onTap = {
                                    selectedGroupIndex = index
                                }
                            )
                        },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedGroupIndex == index) PureWhite else Neutral500,
                                fontWeight = if (selectedGroupIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // 3. Node List
            LazyColumn(
                contentPadding = PaddingValues(bottom = 88.dp, top = 16.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = filteredNodes,
                    key = { index, node -> "${node.id}_$index" },
                    contentType = { _, _ -> "node" }
                ) { index, node ->
                    val isSelected = activeNodeId == node.id
                    val isTestingNode = testingNodeIds.contains(node.id)
                    
                    val onNodeClick = remember(node.id) { { viewModel.setActiveNode(node.id) } }
                    val onEdit = remember(node.id) { 
                        { navController.navigate(Screen.NodeDetail.createRoute(node.id)) } 
                    }
                    val onExport = remember(node.id) {
                        {
                            val link = viewModel.exportNode(node.id)
                            if (link != null) {
                                exportLink = link
                            }
                        }
                    }
                    val onLatency = remember(node.id) { { viewModel.testLatency(node.id) } }
                    val onDelete = remember(node.id) { { viewModel.deleteNode(node.id) } }

                    // Optimization: Only animate first 15 items to reduce load
                    if (index < 15) {
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            delay(index * 30L)
                            visible = true
                        }

                        val alpha by animateFloatAsState(
                            targetValue = if (visible) 1f else 0f,
                            animationSpec = tween(durationMillis = 300),
                            label = "alpha"
                        )
                        val translateY by animateFloatAsState(
                            targetValue = if (visible) 0f else 40f,
                            animationSpec = tween(durationMillis = 300),
                            label = "translateY"
                        )

                        NodeCard(
                            name = node.displayName,
                            type = node.protocol,
                            latency = node.latencyMs,
                            isSelected = isSelected,
                            isTesting = isTestingNode,
                            regionFlag = node.regionFlag,
                            trafficUsed = node.trafficUsed,
                            onClick = onNodeClick,
                            onEdit = onEdit,
                            onExport = onExport,
                            onLatency = onLatency,
                            onDelete = onDelete,
                            modifier = Modifier
                                .animateItemPlacement()
                                .graphicsLayer(
                                    alpha = alpha,
                                    translationY = translateY
                                )
                        )
                    } else {
                        // For items beyond the first 15, render directly without animation overhead
                        NodeCard(
                            name = node.displayName,
                            type = node.protocol,
                            latency = node.latencyMs,
                            isSelected = isSelected,
                            isTesting = isTestingNode,
                            regionFlag = node.regionFlag,
                            trafficUsed = node.trafficUsed,
                            onClick = onNodeClick,
                            onEdit = onEdit,
                            onExport = onExport,
                            onLatency = onLatency,
                            onDelete = onDelete,
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                }
            }
        }
    }
}
