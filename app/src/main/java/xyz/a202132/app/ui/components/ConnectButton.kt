package xyz.a202132.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.R
import xyz.a202132.app.data.model.VpnState

@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customLabel: String? = null,
    enabled: Boolean = true
) {
    val isConnecting = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING

    // 自适应按钮大小：取屏幕宽度的 65%，但不超过 250dp
    val configuration = LocalConfiguration.current
    val buttonSize = minOf(250, (configuration.screenWidthDp * 0.65).toInt())

    // 只在连接状态创建循环动画；缩放仅发生在绘制阶段，不触发布局。
    val pulseScale = if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "connectionPulse")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 0.99f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(560, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        animatedScale
    } else {
        1f
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !isConnecting) 0.97f else 1f,
        animationSpec = tween(durationMillis = if (isPressed) 90 else 140),
        label = "pressScale"
    )

    // 根据状态选择图片
    val imageRes = when (vpnState) {
        VpnState.DISCONNECTED -> R.drawable.btn_disconnected
        VpnState.CONNECTING, VpnState.DISCONNECTING -> R.drawable.btn_connecting
        VpnState.CONNECTED -> R.drawable.btn_connected
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 按钮图片 - 使用 Crossfade 实现平滑过渡，去除涟漪效果
        Crossfade(
            targetState = imageRes,
            animationSpec = tween(220),
            label = "buttonImage"
        ) { currentImage ->
            Image(
                painter = painterResource(id = currentImage),
                contentDescription = when (vpnState) {
                    VpnState.CONNECTED -> "已连接"
                    VpnState.CONNECTING -> "连接中"
                    VpnState.DISCONNECTING -> "断开中"
                    VpnState.DISCONNECTED -> "点击连接"
                },
                modifier = Modifier
                    .size(buttonSize.dp)
                    .graphicsLayer {
                        val scale = pulseScale * pressScale
                        scaleX = scale
                        scaleY = scale
                    }
                    .clickable(
                        enabled = enabled && !isConnecting,
                        indication = rememberRipple(bounded = true),
                        interactionSource = interactionSource
                    ) { onClick() }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 状态文本
        Text(
            text = customLabel ?: when (vpnState) {
                VpnState.CONNECTED -> "已连接"
                VpnState.CONNECTING -> "连接中..."
                VpnState.DISCONNECTING -> "断开中..."
                VpnState.DISCONNECTED -> "点击连接"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (customLabel != null) 18.sp else 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
