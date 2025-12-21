package com.kunk.singbox.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel

private fun resolveOutboundText(
    mode: RuleSetOutboundMode,
    value: String?,
    nodes: List<com.kunk.singbox.model.NodeUi>,
    profiles: List<com.kunk.singbox.model.ProfileUi>
): String {
    return when (mode) {
        RuleSetOutboundMode.DIRECT -> "直连"
        RuleSetOutboundMode.BLOCK -> "拦截"
        RuleSetOutboundMode.PROXY -> "代理"
        RuleSetOutboundMode.NODE -> {
            if (value.isNullOrBlank()) return "未选择"
            val parts = value.split("::", limit = 2)
            val node = if (parts.size == 2) {
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

@Composable
private fun DomainRuleItem(rule: CustomRule, outboundText: String, onClick: () -> Unit) {
    StandardCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${rule.type.displayName}: ${rule.value}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1
                )
                Text(
                    text = outboundText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "编辑",
                tint = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val nodes by nodesViewModel.allNodes.collectAsState()
    val groups by nodesViewModel.allNodeGroups.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    val domainRules = remember(settings.customRules) {
        settings.customRules.filter {
            it.type == RuleType.DOMAIN || it.type == RuleType.DOMAIN_SUFFIX || it.type == RuleType.DOMAIN_KEYWORD
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }

    if (showAddDialog) {
        DomainRuleEditorDialog(
            nodes = nodes,
            profiles = profiles,
            groups = groups,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addCustomRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        DomainRuleEditorDialog(
            initialRule = editingRule,
            nodes = nodes,
            profiles = profiles,
            groups = groups,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateCustomRule(rule)
                editingRule = null
            },
            onDelete = {
                settingsViewModel.deleteCustomRule(editingRule!!.id)
                editingRule = null
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("域名分流", color = TextPrimary) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (domainRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无域名分流规则",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(domainRules) { rule ->
                    val mode = rule.outboundMode ?: when (rule.outbound) {
                        OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                        OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                        OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                    }
                    val outboundText = resolveOutboundText(mode, rule.outboundValue, nodes, profiles)
                    DomainRuleItem(rule = rule, outboundText = "${mode.displayName} → $outboundText", onClick = { editingRule = rule })
                }
            }
        }
    }
}

@Composable
private fun DomainRuleEditorDialog(
    initialRule: CustomRule? = null,
    nodes: List<com.kunk.singbox.model.NodeUi>,
    profiles: List<com.kunk.singbox.model.ProfileUi>,
    groups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (CustomRule) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var type by remember { mutableStateOf(initialRule?.type ?: RuleType.DOMAIN_SUFFIX) }
    var value by remember { mutableStateOf(initialRule?.value ?: "") }

    fun generateRuleNameFromValue(raw: String): String {
        val first = raw
            .split("\n", "\r", ",", "，")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return (first ?: raw.trim()).trim().take(120)
    }
    fun legacyMode(outbound: OutboundTag?): RuleSetOutboundMode {
        return when (outbound ?: OutboundTag.PROXY) {
            OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
            OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
            OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
        }
    }

    var outboundMode by remember { mutableStateOf(initialRule?.outboundMode ?: legacyMode(initialRule?.outbound)) }
    var outboundValue by remember { mutableStateOf(initialRule?.outboundValue) }

    var showTypeDialog by remember { mutableStateOf(false) }
    var showOutboundDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun resolveNodeByStoredValue(value: String?): com.kunk.singbox.model.NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return nodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return nodes.find { it.id == value } ?: nodes.find { it.name == value }
    }

    fun toNodeRef(node: com.kunk.singbox.model.NodeUi): String = "${node.sourceProfileId}::${node.name}"

    val allowedTypes = listOf(RuleType.DOMAIN, RuleType.DOMAIN_SUFFIX, RuleType.DOMAIN_KEYWORD)

    if (showTypeDialog) {
        val options = allowedTypes.map { it.displayName }
        SingleSelectDialog(
            title = "规则类型",
            options = options,
            selectedIndex = allowedTypes.indexOf(type).coerceAtLeast(0),
            onSelect = { index ->
                type = allowedTypes[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showOutboundDialog) {
        val options = RuleSetOutboundMode.entries.map { it.displayName }
        SingleSelectDialog(
            title = "出站",
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(outboundMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                outboundMode = selectedMode
                if (selectedMode != initialRule?.outboundMode) {
                    outboundValue = null
                }
                showOutboundDialog = false

                if (selectedMode == RuleSetOutboundMode.NODE ||
                    selectedMode == RuleSetOutboundMode.PROFILE ||
                    selectedMode == RuleSetOutboundMode.GROUP) {
                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            targetSelectionTitle = "选择节点"
                            targetOptions = nodes.map { node ->
                                val profileName = profiles.find { it.id == node.sourceProfileId }?.name ?: "未知"
                                "${node.name} ($profileName)" to toNodeRef(node)
                            }
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = "选择配置"
                            targetOptions = profiles.map { it.name to it.id }
                        }
                        RuleSetOutboundMode.GROUP -> {
                            targetSelectionTitle = "选择节点组"
                            targetOptions = groups.map { it to it }
                        }
                        else -> {}
                    }
                    showTargetSelectionDialog = true
                }
            },
            onDismiss = { showOutboundDialog = false }
        )
    }

    if (showTargetSelectionDialog) {
        val currentRef = resolveNodeByStoredValue(outboundValue)?.let { toNodeRef(it) } ?: outboundValue
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = targetOptions.indexOfFirst { it.second == currentRef }.coerceAtLeast(0),
            onSelect = { index ->
                outboundValue = targetOptions[index].second
                showTargetSelectionDialog = false
            },
            onDismiss = { showTargetSelectionDialog = false }
        )
    }

    if (showDeleteConfirm) {
        val displayName = initialRule?.name?.takeIf { it.isNotBlank() } ?: generateRuleNameFromValue(value)
        ConfirmDialog(
            title = "删除规则",
            message = "确定要删除规则 \"$displayName\" 吗？",
            confirmText = "删除",
            onConfirm = {
                onDelete?.invoke()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neutral800,
        title = {
            Text(
                text = if (initialRule == null) "添加规则" else "编辑规则",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTypeDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("类型", color = TextSecondary)
                    Text(type.displayName, color = TextPrimary)
                }

                StyledTextField(
                    label = "域名/内容",
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "支持多条：用换行或逗号分隔"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showOutboundDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("出站", color = TextSecondary)
                    Text(outboundMode.displayName, color = TextPrimary)
                }

                if (outboundMode == RuleSetOutboundMode.NODE ||
                    outboundMode == RuleSetOutboundMode.PROFILE ||
                    outboundMode == RuleSetOutboundMode.GROUP) {
                    val targetName = when (outboundMode) {
                        RuleSetOutboundMode.NODE -> {
                            val node = resolveNodeByStoredValue(outboundValue)
                            val profileName = profiles.find { it.id == node?.sourceProfileId }?.name
                            if (node != null && profileName != null) "${node.name} ($profileName)" else node?.name
                        }
                        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == outboundValue }?.name
                        RuleSetOutboundMode.GROUP -> outboundValue
                        else -> null
                    } ?: "点击选择..."
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (outboundMode) {
                                    RuleSetOutboundMode.NODE -> {
                                        targetSelectionTitle = "选择节点"
                                        targetOptions = nodes.map { node ->
                                            val profileName = profiles.find { it.id == node.sourceProfileId }?.name ?: "未知"
                                            "${node.name} ($profileName)" to toNodeRef(node)
                                        }
                                    }
                                    RuleSetOutboundMode.PROFILE -> {
                                        targetSelectionTitle = "选择配置"
                                        targetOptions = profiles.map { it.name to it.id }
                                    }
                                    RuleSetOutboundMode.GROUP -> {
                                        targetSelectionTitle = "选择节点组"
                                        targetOptions = groups.map { it to it }
                                    }
                                    else -> {}
                                }
                                showTargetSelectionDialog = true
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选择目标", color = TextSecondary)
                        Text(targetName, color = TextPrimary)
                    }
                }

                if (onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("删除")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalValue = value.trim()
                    if (finalValue.isEmpty()) return@TextButton

                    val finalName = generateRuleNameFromValue(finalValue)
                    if (finalName.isEmpty()) return@TextButton

                    if ((outboundMode == RuleSetOutboundMode.NODE ||
                            outboundMode == RuleSetOutboundMode.PROFILE ||
                            outboundMode == RuleSetOutboundMode.GROUP) && outboundValue.isNullOrBlank()) {
                        return@TextButton
                    }

                    val legacyOutbound = when (outboundMode) {
                        RuleSetOutboundMode.DIRECT -> OutboundTag.DIRECT
                        RuleSetOutboundMode.BLOCK -> OutboundTag.BLOCK
                        else -> OutboundTag.PROXY
                    }

                    val rule = CustomRule(
                        id = initialRule?.id ?: java.util.UUID.randomUUID().toString(),
                        name = finalName,
                        type = type,
                        value = finalValue,
                        outbound = legacyOutbound,
                        outboundMode = outboundMode,
                        outboundValue = outboundValue,
                        enabled = initialRule?.enabled ?: true
                    )
                    onConfirm(rule)
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
