package com.kunk.singbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.HubRuleSet
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.Neutral900
import com.kunk.singbox.ui.theme.SurfaceCard
import com.kunk.singbox.viewmodel.RuleSetViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetHubScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    ruleSetViewModel: RuleSetViewModel = viewModel()
) {
    // 强制使用 Activity 的 ViewModel 实例，避免重新创建
    // 注意：在实际项目中最好通过 Hilt 注入或在 MainActivity 中传递，这里简化处理
    val activityRuleSetViewModel: RuleSetViewModel = viewModel(
        viewModelStoreOwner = (navController.context as? androidx.activity.ComponentActivity)
            ?: throw IllegalStateException("Context is not a ComponentActivity")
    )
    
    var searchQuery by remember { mutableStateOf("") }
    val ruleSets by activityRuleSetViewModel.ruleSets.collectAsState()
    val isLoading by activityRuleSetViewModel.isLoading.collectAsState()
    val error by activityRuleSetViewModel.error.collectAsState()
    val downloadingRuleSets by settingsViewModel.downloadingRuleSets.collectAsState()
    // 收集 settings 以便在规则集列表变化时自动更新 UI（如删除规则集后）
    val ruleSetSettings by activityRuleSetViewModel.settings.collectAsState()
    
    // 创建一个 Set 用于快速查找已添加的规则集
    val addedRuleSetTags = remember(ruleSetSettings.ruleSets) {
        ruleSetSettings.ruleSets.map { it.tag }.toSet()
    }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filteredRuleSets = remember(searchQuery, ruleSets) {
        if (searchQuery.isBlank()) ruleSets
        else ruleSets.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("规则集中心", color = TextPrimary)
                        Text(
                            text = "数量: ${filteredRuleSets.size}", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                     IconButton(onClick = { activityRuleSetViewModel.fetchRuleSets() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            StandardCard(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索规则集...", color = TextSecondary) },
                    leadingIcon = { 
                        Icon(
                            Icons.Rounded.Search, 
                            contentDescription = "搜索",
                            tint = TextSecondary
                        ) 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = TextPrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
            
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextPrimary)
                }
            } else if (error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { activityRuleSetViewModel.fetchRuleSets() }) {
                            Text("重试")
                        }
                    }
                }
            } else {
                // Grid Content
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredRuleSets) { ruleSet ->
                HubRuleSetItem(
                    ruleSet = ruleSet,
                    isDownloading = downloadingRuleSets.contains(ruleSet.name),
                    isDownloaded = addedRuleSetTags.contains(ruleSet.name),
                    onAddSource = {
                                settingsViewModel.addRuleSet(
                                    RuleSet(
                                        tag = ruleSet.name,
                                        type = RuleSetType.REMOTE,
                                        format = "source",
                                        url = ruleSet.sourceUrl
                                    )
                                ) { _, message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            },
                            onAddBinary = {
                                settingsViewModel.addRuleSet(
                                    RuleSet(
                                        tag = ruleSet.name,
                                        type = RuleSetType.REMOTE,
                                        format = "binary",
                                        url = ruleSet.binaryUrl
                                    )
                                ) { _, message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HubRuleSetItem(
    ruleSet: HubRuleSet,
    isDownloading: Boolean = false,
    onAddSource: () -> Unit,
    onAddBinary: () -> Unit,
    isDownloaded: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ruleSet.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDownloading) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TextPrimary
                        )
                    } else if (isDownloaded) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF2E7D32),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "已下载",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ruleSet.tags.forEach { tag ->
                        Surface(
                            color = Neutral700,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = tag,
                                color = PureWhite,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "规则数量：${ruleSet.ruleCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                
                Icon(
                    imageVector = Icons.Rounded.Visibility,
                    contentDescription = "查看",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onAddSource,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("添加 源文件", fontSize = 12.sp, color = PureWhite)
                }
                
                TextButton(
                    onClick = onAddBinary,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("添加 二进制", fontSize = 12.sp, color = PureWhite)
                }
            }
        }
    }
}
