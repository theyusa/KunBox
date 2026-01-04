package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection_settings_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        StandardCard {
            SettingSwitchItem(
                title = stringResource(R.string.connection_settings_auto_connect),
                subtitle = stringResource(R.string.connection_settings_auto_connect_subtitle),
                checked = settings.autoConnect,
                onCheckedChange = { settingsViewModel.setAutoConnect(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.connection_settings_auto_reconnect),
                subtitle = stringResource(R.string.connection_settings_auto_reconnect_subtitle),
                checked = settings.autoReconnect,
                onCheckedChange = { settingsViewModel.setAutoReconnect(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.connection_settings_hide_recent),
                subtitle = stringResource(R.string.connection_settings_hide_recent_subtitle),
                checked = settings.excludeFromRecent,
                onCheckedChange = { settingsViewModel.setExcludeFromRecent(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.connection_settings_show_notification_speed),
                subtitle = stringResource(R.string.connection_settings_show_notification_speed_subtitle),
                checked = settings.showNotificationSpeed,
                onCheckedChange = { settingsViewModel.setShowNotificationSpeed(it) }
            )
        }
            
            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                EditableTextItem(
                    title = "代理端口",
                    subtitle = "本地混合代理端口 (Mixed Port)",
                    value = settings.proxyPort.toString(),
                    onValueChange = { 
                        it.toIntOrNull()?.let { port -> settingsViewModel.updateProxyPort(port) }
                    }
                )
                SettingSwitchItem(
                    title = "允许来自局域网的连接",
                    subtitle = "开启后，局域网内的其他设备可以通过该端口使用代理",
                    checked = settings.allowLan,
                    onCheckedChange = { settingsViewModel.updateAllowLan(it) }
                )
                SettingSwitchItem(
                    title = "追加 HTTP 代理至 VPN",
                    subtitle = "将本地 HTTP 代理设置为系统代理 (Android 10+)",
                    checked = settings.appendHttpProxy,
                    onCheckedChange = { settingsViewModel.updateAppendHttpProxy(it) }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
