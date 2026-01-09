package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
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
import androidx.compose.ui.res.stringResource
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
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.NodeFilterDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.NodeCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.Neutral500
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
    val sortType by viewModel.sortType.collectAsState()

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
            stringResource(R.string.nodes_sort_default) to NodeSortType.DEFAULT,
            stringResource(R.string.nodes_sort_latency) to NodeSortType.LATENCY,
            stringResource(R.string.nodes_sort_name) to NodeSortType.NAME,
            stringResource(R.string.nodes_sort_region) to NodeSortType.REGION
        )

        SingleSelectDialog(
            title = stringResource(R.string.nodes_sort),
            options = sortOptions.map { it.first },
            selectedIndex = sortOptions.indexOfFirst { it.second == sortType },
            onSelect = { index ->
                viewModel.setSortType(sortOptions[index].second)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    if (showAddNodeDialog) {
        InputDialog(
            title = stringResource(R.string.nodes_add),
            placeholder = stringResource(R.string.nodes_add_hint),
            confirmText = stringResource(R.string.common_add),
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
        val copiedMsg = stringResource(R.string.nodes_copied_to_clipboard)
        InputDialog(
            title = stringResource(R.string.nodes_export_link),
            initialValue = exportLink!!,
            confirmText = stringResource(R.string.common_copy),
            onConfirm = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                exportLink = null
            },
            onDismiss = { exportLink = null }
        )
    }

    Scaffold(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
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
                                text = stringResource(R.string.nodes_clear_latency),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            SmallFloatingActionButton(
                                onClick = {
                                    viewModel.clearLatency()
                                    isFabExpanded = false
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.nodes_clear_latency))
                            }
                        }
                        
                        // Add Node
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.nodes_add),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            SmallFloatingActionButton(
                                onClick = {
                                    showAddNodeDialog = true
                                    isFabExpanded = false
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.nodes_add))
                            }
                        }
                        
                        // Test Latency
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isTesting) stringResource(R.string.nodes_stop_test) else stringResource(R.string.nodes_test_latency),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            SmallFloatingActionButton(
                                onClick = {
                                    viewModel.testAllLatency()
                                    isFabExpanded = false
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Rounded.Bolt, contentDescription = stringResource(R.string.nodes_test_latency))
                                }
                            }
                        }
                    }
                }
                
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.common_menu)
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
                    text = stringResource(R.string.nodes_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 筛选按钮（替换原来的启动按钮）
                    IconButton(onClick = { showFilterDialog = true }) {
                        val hasFilter = nodeFilter.filterMode != FilterMode.NONE
                        Icon(
                            imageVector = Icons.Rounded.FilterAlt,
                            contentDescription = stringResource(R.string.nodes_filter),
                            tint = if (hasFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = stringResource(R.string.nodes_sort), tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            // 2. Group Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedGroupIndex,
                contentColor = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
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
                                    Toast.makeText(context, context.getString(R.string.nodes_group_auto_generated), Toast.LENGTH_LONG).show()
                                },
                                onTap = {
                                    selectedGroupIndex = index
                                }
                            )
                        },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedGroupIndex == index) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
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

                    // Scroll-triggered animation for all items
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        visible = true
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(durationMillis = 300),
                        label = "alpha"
                    )
                    val translateY by animateFloatAsState(
                        targetValue = if (visible) 0f else 50f,
                        animationSpec = tween(durationMillis = 300),
                        label = "translateY"
                    )

                    NodeCard(
                        name = node.displayName,
                        type = node.protocolDisplay,
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
                }
            }
        }
    }
}
