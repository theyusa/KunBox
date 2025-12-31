package com.kunk.singbox.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.LogViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(navController: NavController, viewModel: LogViewModel = viewModel()) {
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("运行日志", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    if (settings.debugLoggingEnabled) {
                        IconButton(onClick = {
                            val logsText = viewModel.getLogsForExport()
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "KunBox 运行日志")
                                putExtra(Intent.EXTRA_TEXT, logsText)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "导出日志"))
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = "导出", tint = MaterialTheme.colorScheme.onBackground)
                        }
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (!settings.debugLoggingEnabled) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.Delete, // Using Delete as placeholder if BugReport is missing or use generic info
                        contentDescription = null,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .width(64.dp)
                            .height(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "调试模式未开启",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "未开启调试模式不记录日志以节省性能",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = "请在“设置 > 工具”中开启",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                reverseLayout = true
            ) {
                items(logs) { log ->
                    // Simplify log display: remove timestamp for cleaner view, but keep it in raw log
                    val displayLog = if (log.length > 11 && log[0] == '[' && log[9] == ']') {
                        log.substring(11) // Skip "[HH:mm:ss] "
                    } else {
                        log
                    }
                    
                    Text(
                        text = displayLog,
                        color = when {
                            log.contains("WARN", ignoreCase = true) -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            log.contains("ERROR", ignoreCase = true) -> MaterialTheme.colorScheme.error
                            log.contains("DEBUG", ignoreCase = true) -> Neutral500
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}