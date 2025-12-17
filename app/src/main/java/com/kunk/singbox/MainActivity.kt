package com.kunk.singbox

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.kunk.singbox.ui.navigation.AppNavigation
import com.kunk.singbox.ui.theme.SingBoxTheme
import com.kunk.singbox.ui.components.AppNavBar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前启用边到边显示
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            SingBoxApp()
        }
    }
}

@Composable
fun SingBoxApp() {
    SingBoxTheme {
        val navController = rememberNavController()
        
        Scaffold(
            bottomBar = { AppNavBar(navController) },
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
    }
}