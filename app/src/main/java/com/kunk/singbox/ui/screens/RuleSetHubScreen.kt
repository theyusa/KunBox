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
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.SettingsViewModel

data class HubRuleSet(
    val name: String,
    val ruleCount: Int,
    val tags: List<String>,
    val description: String = "",
    val sourceUrl: String = "",
    val binaryUrl: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetHubScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    
    // Mock data based on the screenshot
    val ruleSets = remember {
        listOf(
            HubRuleSet("0x0", 1, listOf("geosite"), sourceUrl = "https://github.com/lyc8503/sing-box-rules/raw/master/rule-set-geosite/0x0.srs"),
            HubRuleSet("115", 14, listOf("geosite"), sourceUrl = "https://github.com/lyc8503/sing-box-rules/raw/master/rule-set-geosite/115.srs"),
            HubRuleSet("1337x", 10, listOf("geosite"), sourceUrl = "https://github.com/lyc8503/sing-box-rules/raw/master/rule-set-geosite/1337x.srs"),
            HubRuleSet("17zuoye", 3, listOf("geosite")),
            HubRuleSet("18comic", 51, listOf("geosite")),
            HubRuleSet("2ch", 4, listOf("geosite")),
            HubRuleSet("2kgames", 4, listOf("geosite")),
            HubRuleSet("36kr", 4, listOf("geosite")),
            HubRuleSet("4399", 55, listOf("geosite")),
            HubRuleSet("4chan", 3, listOf("geosite")),
            HubRuleSet("4plebs", 2, listOf("geosite")),
            HubRuleSet("51job", 5, listOf("geosite")),
            HubRuleSet("54647", 4, listOf("geosite")),
            HubRuleSet("58tongcheng", 39, listOf("geosite")),
            HubRuleSet("5ch", 2, listOf("geosite")),
            HubRuleSet("6park", 1, listOf("geosite")),
            HubRuleSet("7tv", 1, listOf("geosite")),
            HubRuleSet("800best", 1, listOf("geosite"))
        )
    }

    val filteredRuleSets = remember(searchQuery) {
        if (searchQuery.isBlank()) ruleSets
        else ruleSets.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("规则集中心", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    StyledTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "搜索规则集...",
                        modifier = Modifier.weight(1f),
                        label = ""
                    )
                }
            }
            
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
                        onAddSource = {
                            settingsViewModel.addRuleSet(
                                RuleSet(
                                    tag = ruleSet.name,
                                    type = RuleSetType.REMOTE,
                                    format = "source",
                                    url = ruleSet.sourceUrl.ifEmpty { "https://raw.githubusercontent.com/lyc8503/sing-box-rules/master/rule-set-geosite/${ruleSet.name}.json" }
                                )
                            )
                            navController.popBackStack()
                        },
                        onAddBinary = {
                            settingsViewModel.addRuleSet(
                                RuleSet(
                                    tag = ruleSet.name,
                                    type = RuleSetType.REMOTE,
                                    format = "binary",
                                    url = ruleSet.binaryUrl.ifEmpty { "https://raw.githubusercontent.com/lyc8503/sing-box-rules/master/rule-set-geosite/${ruleSet.name}.srs" }
                                )
                            )
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun HubRuleSetItem(
    ruleSet: HubRuleSet,
    onAddSource: () -> Unit,
    onAddBinary: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
        shape = RoundedCornerShape(8.dp),
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
                Text(
                    text = ruleSet.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ruleSet.tags.forEach { tag ->
                        Surface(
                            color = Color(0xFF006C6C),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = tag,
                                color = Color.White,
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
                    Text("添加 源文件", fontSize = 12.sp)
                }
                
                TextButton(
                    onClick = onAddBinary,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("添加 二进制", fontSize = 12.sp)
                }
            }
        }
    }
}
