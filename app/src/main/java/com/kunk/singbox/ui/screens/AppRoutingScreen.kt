package com.kunk.singbox.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.theme.*
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutingScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("应用分组", "独立规则")

    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var showDeleteGroupConfirm by remember { mutableStateOf<AppGroup?>(null) }

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showDeleteRuleConfirm by remember { mutableStateOf<AppRule?>(null) }

    val nodes by nodesViewModel.allNodes.collectAsState()
    val availableGroups by nodesViewModel.allNodeGroups.collectAsState()
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

    if (showAddGroupDialog) {
        AppGroupEditorDialog(
            installedApps = installedApps,
            nodes = nodes,
            profiles = profiles,
            groups = availableGroups,
            onDismiss = { showAddGroupDialog = false },
            onConfirm = { group ->
                settingsViewModel.addAppGroup(group)
                showAddGroupDialog = false
            }
        )
    }

    if (editingGroup != null) {
        AppGroupEditorDialog(
            initialGroup = editingGroup,
            installedApps = installedApps,
            nodes = nodes,
            profiles = profiles,
            groups = availableGroups,
            onDismiss = { editingGroup = null },
            onConfirm = { group ->
                settingsViewModel.updateAppGroup(group)
                editingGroup = null
            }
        )
    }

    if (showDeleteGroupConfirm != null) {
        ConfirmDialog(
            title = "删除分组",
            message = "确定要删除 \"${showDeleteGroupConfirm?.name}\" 分组吗？",
            confirmText = "删除",
            onConfirm = {
                settingsViewModel.deleteAppGroup(showDeleteGroupConfirm!!.id)
                showDeleteGroupConfirm = null
            },
            onDismiss = { showDeleteGroupConfirm = null }
        )
    }

    if (showAddRuleDialog) {
        AppRuleEditorDialog(
            installedApps = installedApps,
            existingPackages = settings.appRules.map { it.packageName }.toSet(),
            nodes = nodes,
            profiles = profiles,
            groups = availableGroups,
            onDismiss = { showAddRuleDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addAppRule(rule)
                showAddRuleDialog = false
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
            groups = availableGroups,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateAppRule(rule)
                editingRule = null
            }
        )
    }

    if (showDeleteRuleConfirm != null) {
        ConfirmDialog(
            title = "删除规则",
            message = "确定要删除 \"${showDeleteRuleConfirm?.appName}\" 的规则吗？",
            confirmText = "删除",
            onConfirm = {
                settingsViewModel.deleteAppRule(showDeleteRuleConfirm!!.id)
                showDeleteRuleConfirm = null
            },
            onDismiss = { showDeleteRuleConfirm = null }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("应用分流", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                        }
                    },
                    actions = {
                        IconButton(onClick = { 
                            if (selectedTab == 0) showAddGroupDialog = true 
                            else showAddRuleDialog = true 
                        }) {
                            Icon(Icons.Rounded.Add, contentDescription = "添加", tint = PureWhite)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = AppBackground,
                    contentColor = PureWhite,
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = PureWhite
                        )
                    },
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { 
                                Text(
                                    title, 
                                    color = if (selectedTab == index) PureWhite else Neutral500,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                ) 
                            }
                        )
                    }
                }
            }
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (selectedTab == 0) {
                    if (settings.appGroups.isEmpty()) {
                        item {
                            EmptyState(Icons.Rounded.Folder, "暂无应用分组", "点击右上角 + 创建分组")
                        }
                    } else {
                        items(settings.appGroups) { group ->
                            val mode = group.outboundMode ?: RuleSetOutboundMode.DIRECT
                            val outboundText = resolveOutboundText(mode, group.outboundValue, nodes, profiles)
                            AppGroupCard(
                                group = group,
                                outboundText = outboundText,
                                onClick = { editingGroup = group },
                                onToggle = { settingsViewModel.toggleAppGroupEnabled(group.id) },
                                onDelete = { showDeleteGroupConfirm = group }
                            )
                        }
                    }
                } else {
                    if (settings.appRules.isEmpty()) {
                        item {
                            EmptyState(Icons.Rounded.Apps, "暂无独立应用规则", "点击右上角 + 添加规则")
                        }
                    } else {
                        items(settings.appRules) { rule ->
                            val mode = rule.outboundMode ?: RuleSetOutboundMode.DIRECT
                            val outboundText = resolveOutboundText(mode, rule.outboundValue, nodes, profiles)
                            AppRuleItem(
                                rule = rule,
                                outboundText = outboundText,
                                onClick = { editingRule = rule },
                                onToggle = { settingsViewModel.toggleAppRuleEnabled(rule.id) },
                                onDelete = { showDeleteRuleConfirm = rule }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Neutral500, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(subtitle, color = Neutral500, fontSize = 12.sp)
        }
    }
}

private fun resolveOutboundText(
    mode: RuleSetOutboundMode,
    value: String?,
    nodes: List<NodeUi>,
    profiles: List<ProfileUi>
): String {
    return when (mode) {
        RuleSetOutboundMode.DIRECT -> "直连"
        RuleSetOutboundMode.BLOCK -> "拦截"
        RuleSetOutboundMode.PROXY -> "代理"
        RuleSetOutboundMode.NODE -> {
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
        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == value }?.name ?: "未知配置"
        RuleSetOutboundMode.GROUP -> value ?: "未知组"
    }
}
