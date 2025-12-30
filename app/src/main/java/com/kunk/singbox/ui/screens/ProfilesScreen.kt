package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import kotlinx.coroutines.delay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.navigation.NavController
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.kunk.singbox.repository.FakeRepository
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.model.UpdateStatus
import com.kunk.singbox.ui.components.ProfileCard
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SurfaceCard
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ProfilesScreen(
    navController: NavController,
    viewModel: com.kunk.singbox.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    
    var showSearchDialog by remember { mutableStateOf(false) }
    var showImportSelection by remember { mutableStateOf(false) }
    var showSubscriptionInput by remember { mutableStateOf(false) }
    var showClipboardInput by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<com.kunk.singbox.model.ProfileUi?>(null) }
    
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Handle update state feedback
    androidx.compose.runtime.LaunchedEffect(updateStatus) {
        updateStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    // Handle import state feedback
    androidx.compose.runtime.LaunchedEffect(importState) {
        when (val state = importState) {
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Success -> {
                Toast.makeText(context, "导入成功: ${state.profile.name}", Toast.LENGTH_SHORT).show()
                viewModel.resetImportState()
            }
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Error -> {
                Toast.makeText(context, "导入失败: ${state.message}", Toast.LENGTH_LONG).show()
                viewModel.resetImportState()
            }
            // Loading state is now handled by ImportLoadingDialog
            else -> {}
        }
    }

    if (importState is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading) {
        ImportLoadingDialog(
            message = (importState as com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading).message
        )
    }

    if (showImportSelection) {
        ImportSelectionDialog(
            onDismiss = { showImportSelection = false },
            onTypeSelected = { type ->
                showImportSelection = false
                when (type) {
                    ProfileImportType.Subscription -> showSubscriptionInput = true
                    ProfileImportType.Clipboard -> showClipboardInput = true
                    ProfileImportType.File -> {
                        Toast.makeText(context, "暂不支持文件导入", Toast.LENGTH_SHORT).show()
                    }
                    ProfileImportType.QRCode -> {
                        Toast.makeText(context, "暂不支持二维码扫描", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showSubscriptionInput) {
        SubscriptionInputDialog(
            onDismiss = { showSubscriptionInput = false },
            onConfirm = { name, url ->
                viewModel.importSubscription(name, url)
                showSubscriptionInput = false
            }
        )
    }

    if (showClipboardInput) {
        InputDialog(
            title = "导入剪贴板",
            placeholder = "配置名称",
            initialValue = "",
            confirmText = "导入",
            onConfirm = { name ->
                if (name.contains("://")) {
                    Toast.makeText(context, "配置名称不能包含链接", Toast.LENGTH_SHORT).show()
                    return@InputDialog
                }

                val content = clipboardManager.getText()?.text ?: ""
                if (content.isNotBlank()) {
                    viewModel.importFromContent(if (name.isBlank()) "剪贴板导入" else name, content)
                    showClipboardInput = false
                } else {
                    Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showClipboardInput = false }
        )
    }

    if (showSearchDialog) {
        InputDialog(
            title = "搜索配置",
            placeholder = "输入关键词...",
            confirmText = "搜索",
            onConfirm = { showSearchDialog = false },
            onDismiss = { showSearchDialog = false }
        )
    }

    if (editingProfile != null) {
        val profile = editingProfile!!
        SubscriptionInputDialog(
            initialName = profile.name,
            initialUrl = profile.url ?: "",
            title = "编辑配置",
            onDismiss = { editingProfile = null },
            onConfirm = { name, url ->
                viewModel.updateProfileMetadata(profile.id, name, url)
                editingProfile = null
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImportSelection = true },
                containerColor = PureWhite,
                contentColor = Color.Black
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Profile")
            }
        }
    ) { padding ->
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "配置管理",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = { showSearchDialog = true }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = PureWhite)
                }
            }

            // List
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
                    var visible by remember { mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (index < 15) {
                            delay(index * 30L)
                        }
                        visible = true
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(durationMillis = 300),
                        label = "alpha"
                    )
                    val translateY by animateFloatAsState(
                        targetValue = if (visible) 0f else 40f,
                        animationSpec = tween(durationMillis = 300),
                        label = "translateY"
                    )

                    ProfileCard(
                        name = profile.name,
                        type = profile.type.name,
                        isSelected = profile.id == activeProfileId,
                        isEnabled = profile.enabled,
                        isUpdating = profile.updateStatus == UpdateStatus.Updating,
                        expireDate = profile.expireDate,
                        totalTraffic = profile.totalTraffic,
                        usedTraffic = profile.usedTraffic,
                        onClick = { viewModel.setActiveProfile(profile.id) },
                        onUpdate = {
                            viewModel.updateProfile(profile.id)
                        },
                        onToggle = {
                            viewModel.toggleProfileEnabled(profile.id)
                        },
                        onEdit = {
                            if (profile.type == com.kunk.singbox.model.ProfileType.Subscription ||
                                profile.type == com.kunk.singbox.model.ProfileType.Imported) {
                                editingProfile = profile
                            } else {
                                navController.navigate(Screen.ProfileEditor.route)
                            }
                        },
                        onDelete = {
                            viewModel.deleteProfile(profile.id)
                        },
                        modifier = Modifier.graphicsLayer(
                            alpha = alpha,
                            translationY = translateY
                        )
                    )
                }
            }
        }
    }
}

private enum class ProfileImportType { Subscription, File, Clipboard, QRCode }

@Composable
private fun ImportSelectionDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (ProfileImportType) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportOptionCard(
                icon = Icons.Rounded.Link,
                title = "订阅链接",
                subtitle = "从 URL 导入",
                onClick = { onTypeSelected(ProfileImportType.Subscription) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.Description,
                title = "本地文件",
                subtitle = "从 JSON/YAML 文件导入",
                onClick = { onTypeSelected(ProfileImportType.File) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.ContentPaste,
                title = "剪贴板",
                subtitle = "从剪贴板内容导入",
                onClick = { onTypeSelected(ProfileImportType.Clipboard) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.QrCodeScanner,
                title = "扫描二维码",
                subtitle = "使用相机扫描",
                onClick = { onTypeSelected(ProfileImportType.QRCode) }
            )
        }
    }
}

@Composable
private fun ImportLoadingDialog(message: String) {
    // 尝试解析进度信息 (例如 "正在提取节点 (50/1000)...")
    val progress = remember(message) {
        val regex = Regex("\\((\\d+)/(\\d+)\\)")
        val match = regex.find(message)
        if (match != null) {
            val (current, total) = match.destructured
            current.toFloat() / total.toFloat()
        } else {
            null
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (progress != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = PureWhite,
                    trackColor = Neutral500.copy(alpha = 0.3f),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    color = PureWhite
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SubscriptionInputDialog(
    initialName: String = "",
    initialUrl: String = "",
    title: String = "添加订阅",
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PureWhite,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = PureWhite,
                    unfocusedLabelColor = Neutral500
                )
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("订阅链接") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PureWhite,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = PureWhite,
                    unfocusedLabelColor = Neutral500
                )
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            val context = LocalContext.current
            
            Button(
                onClick = {
                    // 校验订阅链接是否为单节点链接
                    val isNodeLink = url.trim().let {
                        it.startsWith("vmess://") || it.startsWith("vless://") ||
                        it.startsWith("ss://") || it.startsWith("ssr://") ||
                        it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                        it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                        it.startsWith("tuic://") || it.startsWith("bean://") ||
                        it.startsWith("wireguard://") || it.startsWith("ssh://")
                    }
                    
                    if (isNodeLink) {
                        Toast.makeText(context,
                            "禁止在订阅链接中填入单节点，请使用'剪贴板'导入或'添加节点'功能",
                            Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    
                    // 校验名称是否非法（看起来像链接）
                    if (name.contains("://")) {
                        Toast.makeText(context, "配置名称不能包含链接", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    onConfirm(name, url)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Color.Black),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text("确定", fontWeight = FontWeight.Bold, color = Color.Black)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Neutral500)
            ) {
                Text("取消")
            }
        }
    }
}
