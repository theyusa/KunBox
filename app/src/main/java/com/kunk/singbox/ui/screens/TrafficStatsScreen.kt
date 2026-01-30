package com.kunk.singbox.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import androidx.navigation.NavController
import com.kunk.singbox.repository.NodeTrafficStats
import com.kunk.singbox.repository.TrafficPeriod
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.viewmodel.TrafficStatsViewModel

private val chartColors = listOf(
    Color(0xFF6366F1),
    Color(0xFF22C55E),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF8B5CF6),
    Color(0xFF06B6D4),
    Color(0xFFEC4899),
    Color(0xFF14B8A6),
    Color(0xFFF97316),
    Color(0xFF64748B)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun TrafficStatsScreen(
    navController: NavController,
    viewModel: TrafficStatsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        ConfirmDialog(
            title = "清除流量统计",
            message = "确定要清除所有流量统计数据吗？此操作不可撤销。",
            confirmText = "清除",
            onConfirm = {
                viewModel.clearAllStats()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("流量统计", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "清除统计",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            PeriodSelector(
                selectedPeriod = uiState.selectedPeriod,
                onPeriodSelected = { viewModel.selectPeriod(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            uiState.summary?.let { summary ->
                TotalTrafficCard(
                    totalUpload = summary.totalUpload,
                    totalDownload = summary.totalDownload
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.nodePercentages.isNotEmpty()) {
                    TrafficDistributionCard(
                        nodePercentages = uiState.nodePercentages.take(8),
                        nodeNames = uiState.nodeNames
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (uiState.topNodes.isNotEmpty()) {
                    NodeRankingCard(
                        nodes = uiState.topNodes,
                        totalTraffic = summary.totalUpload + summary.totalDownload,
                        nodeNames = uiState.nodeNames
                    )
                }
            }

            if (uiState.summary == null || uiState.topNodes.isEmpty()) {
                EmptyStateCard()
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: TrafficPeriod,
    onPeriodSelected: (TrafficPeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TrafficPeriod.entries.forEach { period ->
            val label = when (period) {
                TrafficPeriod.TODAY -> "今日"
                TrafficPeriod.THIS_WEEK -> "本周"
                TrafficPeriod.THIS_MONTH -> "本月"
                TrafficPeriod.ALL_TIME -> "全部"
            }
            FilterChip(
                selected = selectedPeriod == period,
                onClick = { onPeriodSelected(period) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun TotalTrafficCard(
    totalUpload: Long,
    totalDownload: Long
) {
    StandardCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "总流量",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TrafficStatItem(
                    icon = Icons.Rounded.ArrowUpward,
                    label = "上传",
                    value = formatBytes(totalUpload),
                    color = Color(0xFF22C55E)
                )
                TrafficStatItem(
                    icon = Icons.Rounded.ArrowDownward,
                    label = "下载",
                    value = formatBytes(totalDownload),
                    color = Color(0xFF3B82F6)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "总计: ${formatBytes(totalUpload + totalDownload)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TrafficStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TrafficDistributionCard(
    nodePercentages: List<Pair<NodeTrafficStats, Float>>,
    nodeNames: Map<String, String>
) {
    StandardCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "流量分布",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                DonutChart(
                    data = nodePercentages.map { it.second },
                    colors = chartColors.take(nodePercentages.size),
                    modifier = Modifier.size(160.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            nodePercentages.forEachIndexed { index, (stats, percentage) ->
                ChartLegendItem(
                    color = chartColors[index % chartColors.size],
                    label = nodeNames[stats.nodeId] ?: stats.nodeId,
                    percentage = percentage,
                    traffic = formatBytes(stats.upload + stats.download)
                )
                if (index < nodePercentages.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DonutChart(
    data: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    val total = data.sum()
    if (total <= 0f) return

    val animatedProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 800),
        label = "donut_animation"
    )

    Canvas(modifier = modifier) {
        val strokeWidth = 32.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2
        val center = Offset(size.width / 2, size.height / 2)

        var startAngle = -90f

        data.forEachIndexed { index, value ->
            val sweepAngle = (value / total) * 360f * animatedProgress
            drawArc(
                color = colors[index % colors.size],
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweepAngle
        }
    }
}

@Composable
private fun ChartLegendItem(
    color: Color,
    label: String,
    percentage: Float,
    traffic: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format(Locale.US, "%.1f%%", percentage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = traffic,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun NodeRankingCard(
    nodes: List<NodeTrafficStats>,
    totalTraffic: Long,
    nodeNames: Map<String, String>
) {
    StandardCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "节点排行",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            nodes.forEachIndexed { index, stats ->
                NodeRankingItem(
                    rank = index + 1,
                    stats = stats,
                    totalTraffic = totalTraffic,
                    color = chartColors[index % chartColors.size],
                    displayName = nodeNames[stats.nodeId] ?: stats.nodeId
                )
                if (index < nodes.size - 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun NodeRankingItem(
    rank: Int,
    stats: NodeTrafficStats,
    totalTraffic: Long,
    color: Color,
    displayName: String
) {
    val nodeTotal = stats.upload + stats.download
    val progress = if (totalTraffic > 0) nodeTotal.toFloat() / totalTraffic else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "progress_animation"
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (rank) {
                            1 -> Color(0xFFFFD700)
                            2 -> Color(0xFFC0C0C0)
                            3 -> Color(0xFFCD7F32)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (rank <= 3) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        text = "↑${formatBytes(stats.upload)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF22C55E)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "↓${formatBytes(stats.download)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3B82F6)
                    )
                }
            }

            Text(
                text = formatBytes(nodeTotal),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun EmptyStateCard() {
    StandardCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "暂无流量数据",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "连接 VPN 后开始记录流量统计",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
