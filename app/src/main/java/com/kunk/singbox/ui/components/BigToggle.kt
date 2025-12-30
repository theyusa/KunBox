package com.kunk.singbox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.ui.theme.OLEDBlack
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import com.kunk.singbox.R

@Composable
fun BigToggle(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Scale animation on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ScaleAnimation"
    )

    // Use updateTransition for coordinated animations
    val transition = updateTransition(targetState = isRunning, label = "BigToggleTransition")
    
    // Vertical offset animation - 关闭时下移 (使用明确时长的 tween 动画)
    val verticalOffset by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = 600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        },
        label = "VerticalOffset"
    ) { running ->
        if (running) 0.dp else 20.dp
    }
    
    // 控制晃动动画的 key，每次 isRunning 变为 true 时重置
    // 使用 mutableStateOf 并显式类型，避免 MutableIntState 委托的兼容性问题
    var shakeKey by remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            shakeKey = shakeKey + 1
        }
    }
    
    // 晃动动画 - 使用 Animatable 手动控制
    val rotation = remember { Animatable(0f) }
    
    // 弹跳动画 - 开启时先弹起再落下
    val bounceOffset = remember { Animatable(0f) }
    
    LaunchedEffect(shakeKey) {
        if (isRunning) {
            // 并行执行弹跳和抖动动画
            bounceOffset.snapTo(0f)
            rotation.snapTo(0f)
            
            // 同时启动弹跳和抖动
            val bounceJob = launch {
                // 慢速弹起到 -100dp (负值表示向上)
                bounceOffset.animateTo(
                    targetValue = -100f,
                    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
                // 落回到 0dp，使用更慢的弹簧效果
                bounceOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            
            val shakeJob = launch {
                // 晃动动画 - 仅在弹起阶段进行 (约300ms)
                // 快速晃动几下
                if (isRunning) {
                    rotation.animateTo(
                        targetValue = 8f,
                        animationSpec = tween(75, easing = LinearEasing)
                    )
                    rotation.animateTo(
                        targetValue = -8f,
                        animationSpec = tween(150, easing = LinearEasing)
                    )
                    rotation.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(75, easing = LinearEasing)
                    )
                }
                // 确保最后回到 0
                rotation.snapTo(0f)
            }
            
            // 等待两个动画都完成
            bounceJob.join()
            shakeJob.join()
        } else {
            rotation.snapTo(0f)
            bounceOffset.snapTo(0f)
        }
    }

    // Color animations
    // 移除绿色背景，改为透明或极淡的颜色
    val backgroundColor = Color.Transparent
    
    // 移除边框颜色动画
    val borderColor = Color.Transparent

    // 使用 Box 保持居中，移除硬编码的 padding
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // 动态偏移 - 关闭时下移
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.offset(y = verticalOffset)
        ) {
            // Main Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .scale(scale)
                    .offset(y = bounceOffset.value.dp) // 应用弹跳偏移
            ) {
                // 点击区域和背景 (保持圆形)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(backgroundColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                )

                // 动态表情逻辑
                val emoji = if (isRunning) "😳" else "😴"

                // 表情层 (允许超出圆形边界)
                Text(
                    text = emoji,
                    fontSize = 130.sp,
                    modifier = Modifier
                        .offset(x = (-4).dp, y = 8.dp)
                        .graphicsLayer {
                            rotationZ = rotation.value
                        }
                )
            }
        }
    }
}
