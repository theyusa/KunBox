package com.kunk.singbox.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.*
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppRule?>(null) }

    val nodes by nodesViewModel.allNodes.collectAsState()
    val groups by nodesViewModel.allNodeGroups.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app -> app.packageName != context.packageName }
                .map { app ->
                    InstalledApp(
                        packageName = app.packageName,
                        appName = app.loadLabel(pm).toString(),
                        isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedBy { it.appName.lowercase() }
            installedApps = apps
            isLoading = false
        }
    }

    if (showAddDialog) {
        AppRuleEditorDialog(
            installedApps = installedApps,
            existingPackages = settings.appRules.map { it.packageName }.toSet(),
            nodes = nodes,
            profiles = profiles,
            groups = groups,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addAppRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        AppRuleEditorDialog(
            initialRule = editingRule,
            installedApps = installedApps,
            existingPackages = settings.appRules.filter { it.id != editingRule?.id }.map { it.packageName }.toSet(),
            nodes = nodes,
            profiles = profiles,
            groups = groups,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateAppRule(rule)
                editingRule = null
            }
        )
    }

    if (showDeleteConfirm != null) {
        ConfirmDialog(
            title = "删除规则",
            message = "确定要删除 \"${showDeleteConfirm?.appName}\" 的分流规则吗？",
            confirmText = "删除",
            onConfirm = {
                settingsViewModel.deleteAppRule(showDeleteConfirm!!.id)
                showDeleteConfirm = null
            },
            onDismiss = { showDeleteConfirm = null }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("应用分流", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PureWhite)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StandardCard {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("为不同应用设置不同的代理规则", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutboundChip(RuleSetOutboundMode.PROXY, "代理")
                                OutboundChip(RuleSetOutboundMode.DIRECT, "直连")
                                OutboundChip(RuleSetOutboundMode.BLOCK, "拦截")
                            }
                        }
                    }
                }

                if (settings.appRules.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("暂无应用分流规则", color = TextSecondary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("点击右上角 + 添加规则", color = Neutral500, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    items(settings.appRules) { rule ->
                        val mode = rule.outboundMode ?: RuleSetOutboundMode.DIRECT
                        val outboundText = when (mode) {
                            RuleSetOutboundMode.DIRECT -> "直连"
                            RuleSetOutboundMode.BLOCK -> "拦截"
                            RuleSetOutboundMode.PROXY -> "代理"
                            RuleSetOutboundMode.NODE -> {
                                val value = rule.outboundValue
                                val parts = value?.split("::", limit = 2)
                                val node = if (!value.isNullOrBlank() && parts != null && parts.size == 2) {
                                    val profileId = parts[0]
                                    val name = parts[1]
                                    nodes.find { it.sourceProfileId == profileId && it.name == name }
                                } else {
                                    nodes.find { it.id == value } ?: nodes.find { it.name == value }
                                }
                                val profileName = profiles.find { p -> p.id == node?.sourceProfileId }?.name
                                if (node != null && profileName != null) "${node.name} ($profileName)" else "未选择"
                            }
                            RuleSetOutboundMode.PROFILE -> profiles.find { it.id == rule.outboundValue }?.name ?: "未知配置"
                            RuleSetOutboundMode.GROUP -> rule.outboundValue ?: "未知组"
                        }
                        AppRuleItem(
                            rule = rule,
                            outboundText = outboundText,
                            onClick = { editingRule = rule },
                            onToggle = { settingsViewModel.toggleAppRuleEnabled(rule.id) },
                            onDelete = { showDeleteConfirm = rule }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OutboundChip(mode: RuleSetOutboundMode, label: String) {
    val (icon, color) = when (mode) {
        RuleSetOutboundMode.PROXY, RuleSetOutboundMode.NODE, RuleSetOutboundMode.PROFILE, RuleSetOutboundMode.GROUP -> Icons.Rounded.Shield to Color(0xFF4CAF50)
        RuleSetOutboundMode.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
        RuleSetOutboundMode.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text = label, fontSize = 12.sp, color = color)
    }
}
