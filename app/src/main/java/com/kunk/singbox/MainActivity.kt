package com.kunk.singbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.model.ConnectionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前启用边到边显示
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            SingBoxApp()
        }
    }
}

@Composable
fun SingBoxApp() {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val dashboardViewModel: DashboardViewModel = viewModel()
    val connectionState by dashboardViewModel.connectionState.collectAsState()

    // 自动连接逻辑
    LaunchedEffect(settings?.autoConnect) {
        if (settings?.autoConnect == true && 
            connectionState == ConnectionState.Idle && 
            !SingBoxService.isRunning
        ) {
            dashboardViewModel.toggleConnection()
        }
    }

    SingBoxTheme {
        val navController = rememberNavController()
        var isNavigating by remember { mutableStateOf(false) }
        var navigationStartTime by remember { mutableStateOf(0L) }

        // Reset isNavigating after animation completes
        LaunchedEffect(navigationStartTime) {
            if (navigationStartTime > 0) {
                delay(NAV_ANIMATION_DURATION.toLong() + 50)
                isNavigating = false
            }
        }
        
        fun startNavigation() {
            isNavigating = true
            navigationStartTime = System.currentTimeMillis()
        }
        
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = { 
                    AppNavBar(
                        navController = navController,
                        onNavigationStart = { startNavigation() }
                    ) 
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0) // 不自动添加系统栏 insets
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()) // 只应用底部 padding
                ) {
                    AppNavigation(navController)
                }
            }

            // Global loading overlay during navigation
            AnimatedVisibility(
                visible = isNavigating,
                enter = fadeIn(animationSpec = tween(50)),
                exit = fadeOut(animationSpec = tween(50))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OLEDBlack.copy(alpha = 0.3f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                        color = PureWhite,
                        trackColor = Color.Transparent,
                        strokeCap = StrokeCap.Round
                    )
                }
            }
        }
    }
}