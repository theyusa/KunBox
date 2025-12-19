package com.kunk.singbox.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun AppGroupsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppGroup?>(null) }

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

    if (showAddDialog) {
        AppGroupEditorDialog(
            installedApps = installedApps,
            nodes = nodes,
            profiles = profiles,
            groups = availableGroups,
            onDismiss = { showAddDialog = false },
            onConfirm = { group ->
                settingsViewModel.addAppGroup(group)
                showAddDialog = false
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

    if (showDeleteConfirm != null) {
        ConfirmDialog(
            title = "删除分组",
            message = "确定要删除 \"${showDeleteConfirm?.name}\" 分组吗？包含 ${showDeleteConfirm?.apps?.size ?: 0} 个应用。",
            confirmText = "删除",
            onConfirm = {
                settingsViewModel.deleteAppGroup(showDeleteConfirm!!.id)
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
                        Icon(Icons.Rounded.Add, contentDescription = "添加分组", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
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
                item {
                    StandardCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "创建分组，将多个应用归类，统一设置代理规则",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (settings.appGroups.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = Neutral500,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("暂无分组", color = TextSecondary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("点击右上角 + 创建分组", color = Neutral500, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    this.items(items = settings.appGroups) { group ->
                        val mode = group.outboundMode ?: RuleSetOutboundMode.DIRECT
                        val outboundText = when (mode) {
                            RuleSetOutboundMode.DIRECT -> "直连"
                            RuleSetOutboundMode.BLOCK -> "拦截"
                            RuleSetOutboundMode.PROXY -> "代理"
                            RuleSetOutboundMode.NODE -> {
                                val value = group.outboundValue
                                val parts = value?.split("::", limit = 2)
                                val node = if (!value.isNullOrBlank() && parts != null && parts.size == 2) {
                                    val profileId = parts[0]
                                    val name = parts[1]
                                    nodes.find { it.sourceProfileId == profileId && it.name == name }
                                } else {
                                    nodes.find { it.id == value } ?: nodes.find { it.name == value }
                                }
                                val profileName = profiles.find { p -> p.id == node?.sourceProfileId }?.name
                                if (node != null && profileName != null) {
                                    "${node.name} ($profileName)"
                                } else {
                                    "未选择"
                                }
                            }
                            RuleSetOutboundMode.PROFILE -> profiles.find { it.id == group.outboundValue }?.name ?: "未知配置"
                            RuleSetOutboundMode.GROUP -> group.outboundValue ?: "未知组"
                        }
                        AppGroupCard(
                            group = group,
                            outboundText = outboundText,
                            onClick = { editingGroup = group },
                            onToggle = { settingsViewModel.toggleAppGroupEnabled(group.id) },
                            onDelete = { showDeleteConfirm = group }
                        )
                    }
                }
            }
        }
    }
}
