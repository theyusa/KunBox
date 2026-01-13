package com.kunk.singbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.kunk.singbox.R
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomConfigScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val configRepository = remember { ConfigRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(initial = null)

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.custom_config_view),
        stringResource(R.string.custom_config_outbounds),
        stringResource(R.string.custom_config_route_rules),
        stringResource(R.string.custom_config_dns_rules)
    )

    // 编辑状态
    var customOutboundsJson by remember(settings) { mutableStateOf(settings?.customOutboundsJson ?: "") }
    var customRouteRulesJson by remember(settings) { mutableStateOf(settings?.customRouteRulesJson ?: "") }
    var customDnsRulesJson by remember(settings) { mutableStateOf(settings?.customDnsRulesJson ?: "") }

    // 运行配置内容
    var runningConfigJson by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_config_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    if (selectedTab > 0) {
                        val savedMsg = stringResource(R.string.custom_config_saved)
                        val invalidJsonMsg = stringResource(R.string.custom_config_invalid_json)
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    when (selectedTab) {
                                        1 -> {
                                            if (customOutboundsJson.isNotBlank()) {
                                                JsonParser.parseString(customOutboundsJson)
                                            }
                                            settingsRepository.setCustomOutboundsJson(customOutboundsJson)
                                        }
                                        2 -> {
                                            if (customRouteRulesJson.isNotBlank()) {
                                                JsonParser.parseString(customRouteRulesJson)
                                            }
                                            settingsRepository.setCustomRouteRulesJson(customRouteRulesJson)
                                        }
                                        3 -> {
                                            if (customDnsRulesJson.isNotBlank()) {
                                                JsonParser.parseString(customDnsRulesJson)
                                            }
                                            settingsRepository.setCustomDnsRulesJson(customDnsRulesJson)
                                        }
                                    }
                                    Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, invalidJsonMsg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onBackground)
                        }
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
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            if (index == 0 && runningConfigJson == null) {
                                // 加载运行配置
                                val configFile = File(context.filesDir, "configs/running_config.json")
                                runningConfigJson = if (configFile.exists()) {
                                    try {
                                        val gson = GsonBuilder().setPrettyPrinting().create()
                                        val jsonElement = JsonParser.parseString(configFile.readText())
                                        gson.toJson(jsonElement)
                                    } catch (e: Exception) {
                                        "Error: ${e.message}"
                                    }
                                } else {
                                    context.getString(R.string.custom_config_no_running_config)
                                }
                            }
                        },
                        text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            when (selectedTab) {
                0 -> ViewConfigTab(runningConfigJson ?: stringResource(R.string.common_loading))
                1 -> EditJsonTab(
                    value = customOutboundsJson,
                    onValueChange = { customOutboundsJson = it },
                    hint = stringResource(R.string.custom_config_outbounds_hint)
                )
                2 -> EditJsonTab(
                    value = customRouteRulesJson,
                    onValueChange = { customRouteRulesJson = it },
                    hint = stringResource(R.string.custom_config_route_rules_hint)
                )
                3 -> EditJsonTab(
                    value = customDnsRulesJson,
                    onValueChange = { customDnsRulesJson = it },
                    hint = stringResource(R.string.custom_config_dns_rules_hint)
                )
            }
        }
    }
}

@Composable
private fun ViewConfigTab(configJson: String) {
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp)
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState)
    ) {
        Text(
            text = configJson,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun EditJsonTab(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String
) {
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .padding(8.dp)
                .verticalScroll(verticalScrollState)
                .horizontalScroll(horizontalScrollState),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = "[]",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
                innerTextField()
            }
        )
    }
}
