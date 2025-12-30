package com.kunk.singbox.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.components.BigToggle
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InfoCard
import com.kunk.singbox.ui.components.ModeChip
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StatusChip
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.R
import androidx.compose.ui.res.painterResource
import android.widget.Toast
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    
    val connectionState by viewModel.connectionState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val activeNodeId by viewModel.activeNodeId.collectAsState()
    val activeNodeLatency by viewModel.activeNodeLatency.collectAsState()
    val currentNodePing by viewModel.currentNodePing.collectAsState()
    val isPingTesting by viewModel.isPingTesting.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    
    // 获取活跃配置和节点的名称
    val activeProfileName = profiles.find { it.id == activeProfileId }?.name
    val activeNodeName = viewModel.getActiveNodeName()
    
    var showModeDialog by remember { mutableStateOf(false) }
    val currentMode = settings.routingMode.displayName
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var lastConnectionState by remember { mutableStateOf<ConnectionState?>(null) }
    
    val updateStatus by viewModel.updateStatus.collectAsState()
    val testStatus by viewModel.testStatus.collectAsState()
    val actionStatus by viewModel.actionStatus.collectAsState()
    val vpnPermissionNeeded by viewModel.vpnPermissionNeeded.collectAsState()
    
    // VPN 权限请求处理
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    
    // 当需要 VPN 权限时启动请求
    LaunchedEffect(vpnPermissionNeeded) {
        if (vpnPermissionNeeded) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                // 已有权限
                viewModel.onVpnPermissionResult(true)
            }
        }
    }

    // Monitor connection errors
    LaunchedEffect(connectionState, testStatus) {
        if (connectionState == ConnectionState.Error && testStatus.isNullOrBlank()) {
            Toast.makeText(context, "连接失败，请检查配置", Toast.LENGTH_SHORT).show()
        }
    }
    
    LaunchedEffect(connectionState) {
        val prev = lastConnectionState
        if (prev != null && prev != connectionState) {
            if (connectionState == ConnectionState.Connected) {
                Toast.makeText(context, "已连接", Toast.LENGTH_SHORT).show()
            } else if (connectionState == ConnectionState.Idle && (prev == ConnectionState.Connected || prev == ConnectionState.Disconnecting)) {
                Toast.makeText(context, "已断开", Toast.LENGTH_SHORT).show()
            }
        }
        lastConnectionState = connectionState
    }

    // Monitor update status
    LaunchedEffect(updateStatus) {
        updateStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }
    
    // Monitor test status
    LaunchedEffect(testStatus) {
        testStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(actionStatus) {
        actionStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    if (showModeDialog) {
        val options = RoutingMode.entries.map { it.displayName }
        SingleSelectDialog(
            title = "路由模式",
            options = options,
            selectedIndex = RoutingMode.entries.indexOf(settings.routingMode).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setRoutingMode(
                    RoutingMode.entries[index],
                    notifyRestartRequired = false
                )
                if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting) {
                    viewModel.restartVpn()
                }
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }
    
    if (showUpdateDialog) {
        ConfirmDialog(
            title = "更新订阅",
            message = "确定要更新所有订阅吗？",
            confirmText = "更新",
            onConfirm = {
                viewModel.updateAllSubscriptions()
                showUpdateDialog = false
            },
            onDismiss = { showUpdateDialog = false }
        )
    }
    
    if (showTestDialog) {
        ConfirmDialog(
            title = "延迟测试",
            message = "确定要测试所有节点延迟吗？",
            confirmText = "开始测试",
            onConfirm = {
                viewModel.testAllNodesLatency()
                showTestDialog = false
            },
            onDismiss = { showTestDialog = false }
        )
    }

    if (showProfileDialog) {
        val options = profiles.map { it.name }
        SingleSelectDialog(
            title = "选择配置",
            options = options,
            selectedIndex = profiles.indexOfFirst { it.id == activeProfileId }.let { idx ->
                when {
                    options.isEmpty() -> -1
                    idx >= 0 -> idx
                    else -> 0
                }
            },
            onSelect = { index ->
                profiles.getOrNull(index)?.id?.let { viewModel.setActiveProfile(it) }
                showProfileDialog = false
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    if (showNodeDialog) {
        val options = nodes.map { it.displayName }
        SingleSelectDialog(
            title = "选择节点",
            options = options,
            selectedIndex = nodes.indexOfFirst { it.id == activeNodeId }.let { idx ->
                when {
                    options.isEmpty() -> -1
                    idx >= 0 -> idx
                    else -> 0
                }
            },
            optionsHeight = 360.dp,
            onSelect = { index ->
                nodes.getOrNull(index)?.id?.let { viewModel.setActiveNode(it) }
                showNodeDialog = false
            },
            onDismiss = { showNodeDialog = false }
        )
    }
    
    // Helper to format bytes
    fun formatBytes(bytes: Long): String = Formatter.formatFileSize(context, bytes)

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    
    // Background Animation
    val isRunning = connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting
    val infiniteTransition = rememberInfiniteTransition(label = "BackgroundAnimation")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isRunning) 0.6f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Background Decoration
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            val baseRadius = size.minDimension / 1.5f
            
            // Inner Circle
            drawCircle(
                color = if (isRunning) Color(0xFF4CAF50).copy(alpha = pulseAlpha * 0.3f) else Color(0xFF2C2C2C),
                radius = baseRadius * pulseScale,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = if (isRunning) 4.dp.toPx() else 2.dp.toPx()
                )
            )
            
            // Outer Circle
            drawCircle(
                color = if (isRunning) Color(0xFF4CAF50).copy(alpha = pulseAlpha * 0.15f) else Color(0xFF1E1E1E),
                radius = (baseRadius * 1.2f) * (pulseScale * 1.02f), // Slightly different scale for depth
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = if (isRunning) 2.dp.toPx() else 1.dp.toPx()
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()) // 为状态栏添加顶部内边距
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Status Bar (Chips)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // App Logo & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    // Use AndroidView to render adaptive icon correctly
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                setImageResource(R.mipmap.ic_launcher_round)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .padding(end = 12.dp)
                    )
                    Text(
                        text = "SingBox",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(
                        label = when (connectionState) {
                            ConnectionState.Idle -> "未连接"
                            ConnectionState.Connecting -> "连接中..."
                            ConnectionState.Connected -> "已连接"
                            ConnectionState.Disconnecting -> "断开中..."
                            ConnectionState.Error -> "错误"
                        },
                        isActive = connectionState == ConnectionState.Connected
                    )
                    
                    val indicatorColor = when (connectionState) {
                        ConnectionState.Connected -> Color(0xFF4CAF50) // Green
                        ConnectionState.Error -> Color(0xFFF44336) // Red
                        else -> Neutral500 // Grey
                    }
                    
                    ModeChip(
                        mode = currentMode,
                        indicatorColor = indicatorColor
                    ) { showModeDialog = true }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    StatusChip(
                        label = activeProfileName ?: "未选择配置",
                        onClick = {
                            if (profiles.isNotEmpty()) {
                                showProfileDialog = true
                            } else {
                                Toast.makeText(context, "暂无可用配置", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusChip(
                        label = activeNodeName ?: "未选择节点",
                        onClick = {
                            if (nodes.isNotEmpty()) {
                                showNodeDialog = true
                            } else {
                                Toast.makeText(context, "暂无可用节点", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // 2. Main Toggle - 居中显示
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
            ) {
                BigToggle(
                    isRunning = connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Disconnecting,
                    onClick = {
                        viewModel.toggleConnection()
                    }
                )
            }

            // 3. Stats & Quick Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Always show InfoCard but with placeholder data when not connected
                val isConnected = connectionState == ConnectionState.Connected
                // 优先使用 VPN 启动后测得的实时延迟，如果没有则使用缓存的延迟
                // currentNodePing: null = 未测试, -1 = 超时/失败, >0 = 实际延迟
                val displayPing = when {
                    currentNodePing != null && currentNodePing!! > 0 -> currentNodePing
                    currentNodePing == null && activeNodeLatency != null -> activeNodeLatency
                    else -> currentNodePing // 可能是 -1（超时）或 null
                }
                // 使用明确的 isPingTesting 状态来控制加载动画
                val isPingLoading = isConnected && isPingTesting
                // 格式化延迟显示：超时显示"超时"，未测试显示"-"
                val pingText = when {
                    !isConnected -> "-"
                    displayPing != null && displayPing > 0 -> "${displayPing} ms"
                    displayPing == -1L -> "超时"
                    else -> "-"
                }
                InfoCard(
                    uploadSpeed = if (isConnected) "${formatBytes(stats.uploadSpeed)}/s" else "-/s",
                    downloadSpeed = if (isConnected) "${formatBytes(stats.downloadSpeed)}/s" else "-/s",
                    ping = pingText,
                    isPingLoading = isPingLoading,
                    onPingClick = if (isConnected) {
                        { viewModel.retestCurrentNodePing() }
                    } else {
                        null
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickActionButton(Icons.Rounded.Refresh, "更新订阅") { showUpdateDialog = true }
                    QuickActionButton(Icons.Rounded.Bolt, "延迟测试") { showTestDialog = true }
                    QuickActionButton(Icons.Rounded.Terminal, "运行日志") { navController.navigate(Screen.Logs.route) }
                    QuickActionButton(Icons.Rounded.BugReport, "网络诊断") { navController.navigate(Screen.Diagnostics.route) }
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = TextPrimary
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}
