package com.kunk.singbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.ui.components.AboutDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import com.kunk.singbox.model.AppThemeMode

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val settings by viewModel.settings.collectAsState()
    
    var showAboutDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var isUpdatingRuleSets by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    if (showThemeDialog) {
        SingleSelectDialog(
            title = "应用主题",
            options = AppThemeMode.entries.map { it.displayName },
            selectedIndex = AppThemeMode.entries.indexOf(settings.appTheme),
            onSelect = { index ->
                viewModel.setAppTheme(AppThemeMode.entries[index])
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = statusBarPadding.calculateTopPadding())
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 1. Connection & Startup
        SettingsGroupTitle("通用")
        StandardCard {
            SettingItem(
                title = "应用主题",
                value = settings.appTheme.displayName,
                icon = Icons.Rounded.Brightness6,
                onClick = { showThemeDialog = true }
            )
            SettingItem(
                title = "连接与启动",
                subtitle = "自动连接、断线重连",
                icon = Icons.Rounded.PowerSettingsNew,
                onClick = { navController.navigate(Screen.ConnectionSettings.route) }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // 2. Network
        SettingsGroupTitle("网络")
        StandardCard {
            SettingItem(
                title = "路由设置",
                subtitle = "模式、规则集、默认规则",
                icon = Icons.Rounded.Route,
                onClick = { navController.navigate(Screen.RoutingSettings.route) }
            )
            SettingItem(
                title = "DNS 设置",
                value = "自动",
                icon = Icons.Rounded.Dns,
                onClick = { navController.navigate(Screen.DnsSettings.route) }
            )
            SettingItem(
                title = "TUN / VPN",
                subtitle = "堆栈、MTU、分应用代理",
                icon = Icons.Rounded.VpnKey,
                onClick = { navController.navigate(Screen.TunSettings.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Tools
        SettingsGroupTitle("工具")
        StandardCard {
            SettingItem(
                title = if (isUpdatingRuleSets) updateMessage else "更新规则集",
                subtitle = if (isUpdatingRuleSets) "正在下载..." else "手动更新广告与路由规则",
                icon = Icons.Rounded.Sync,
                trailing = {
                    if (isUpdatingRuleSets) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                },
                onClick = {
                    if (!isUpdatingRuleSets) {
                        isUpdatingRuleSets = true
                        updateMessage = "准备更新..."
                        scope.launch {
                            try {
                                val success = RuleSetRepository.getInstance(context).ensureRuleSetsReady(
                                    forceUpdate = true,
                                    allowNetwork = true
                                ) {
                                    updateMessage = it
                                }
                                updateMessage = if (success) "更新成功" else "更新失败"
                                Toast.makeText(
                                    context,
                                    if (success) "规则集更新成功" else "规则集更新失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                updateMessage = "发生错误: ${e.message}"
                                Toast.makeText(
                                    context,
                                    "规则集更新失败: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                kotlinx.coroutines.delay(1000)
                                isUpdatingRuleSets = false
                            }
                        }
                    }
                }
            )
            com.kunk.singbox.ui.components.SettingSwitchItem(
                title = "调试模式",
                subtitle = "开启后记录详细日志（需重启服务）",
                icon = Icons.Rounded.BugReport,
                checked = settings.debugLoggingEnabled,
                onCheckedChange = { viewModel.setDebugLoggingEnabled(it) }
            )
            SettingItem(
                title = "运行日志",
                icon = Icons.Rounded.History,
                onClick = { navController.navigate(Screen.Logs.route) }
            )
            SettingItem(
                title = "网络诊断",
                icon = Icons.Rounded.BugReport,
                onClick = { navController.navigate(Screen.Diagnostics.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. About
        SettingsGroupTitle("关于")
        StandardCard {
            SettingItem(
                title = "关于应用",
                icon = Icons.Rounded.Info,
                onClick = { showAboutDialog = true }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}