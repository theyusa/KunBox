package com.kunk.singbox.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.viewmodel.LogViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(navController: NavController, viewModel: LogViewModel = viewModel()) {
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("运行日志", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val logsText = viewModel.getLogsForExport()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "SingBox 运行日志")
                            putExtra(Intent.EXTRA_TEXT, logsText)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "导出日志"))
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "导出", tint = PureWhite)
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "清空", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            reverseLayout = true
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = when {
                        log.contains("WARN", ignoreCase = true) -> PureWhite
                        log.contains("ERROR", ignoreCase = true) -> PureWhite
                        else -> Neutral500
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}