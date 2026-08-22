package xyz.a202132.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.R
import xyz.a202132.app.data.model.VpnState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    customLabel: String? = null,
    enabled: Boolean = true,
    isTesting: Boolean = false,
    /** 三种连接状态的图片均可按当前皮肤方案单独覆盖，未设置时使用内置图片。 */
    customDisconnectedImagePath: String? = null,
    customConnectingImagePath: String? = null,
    customConnectedImagePath: String? = null
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
    val longPressEnabled = onLongClick != null && enabled && !isConnecting
    val longPressProgress by animateFloatAsState(
        targetValue = if (isPressed && longPressEnabled) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isPressed) {
                LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
            } else {
                120
            },
            easing = if (isPressed) LinearEasing else FastOutSlowInEasing
        ),
        label = "smartConnectionLongPressProgress"
    )

    // 根据状态选择图片
    val imageRes = when (vpnState) {
        VpnState.DISCONNECTED -> R.drawable.btn_disconnected
        VpnState.CONNECTING, VpnState.DISCONNECTING -> R.drawable.btn_connecting
        VpnState.CONNECTED -> R.drawable.btn_connected
    }
    val customImagePath = when (vpnState) {
        VpnState.DISCONNECTED -> customDisconnectedImagePath
        VpnState.CONNECTING, VpnState.DISCONNECTING -> customConnectingImagePath
        VpnState.CONNECTED -> customConnectedImagePath
    }
    val customImage by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = customImagePath
    ) {
        value = withContext(Dispatchers.IO) {
            customImagePath
                ?.takeIf { it.isNotBlank() }
                ?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
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
            Box(
                modifier = Modifier.size(buttonSize.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = currentImage),
                    contentDescription = when (vpnState) {
                        VpnState.CONNECTED -> "已连接"
                        VpnState.CONNECTING -> "连接中"
                        VpnState.DISCONNECTING -> "断开中"
                        VpnState.DISCONNECTED -> "点击连接"
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = pulseScale * pressScale
                            scaleX = scale
                            scaleY = scale
                        }
                        .combinedClickable(
                            enabled = enabled && !isConnecting,
                            indication = rememberRipple(bounded = true),
                            interactionSource = interactionSource,
                            onClickLabel = if (vpnState == VpnState.DISCONNECTED) "普通连接" else null,
                            onLongClickLabel = if (onLongClick != null) "智能连接" else null,
                            onLongClick = onLongClick,
                            onClick = onClick
                        )
                )
                // 自定义图片叠加在默认资源上方；图片加载失败时仍保留默认按钮。
                customImage?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = "自定义连接状态图片",
                        // 连接按钮图片通常包含透明留白，保持完整显示以免裁切主体。
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val scale = pulseScale * pressScale
                                scaleX = scale
                                scaleY = scale
                            }
                            .combinedClickable(
                                enabled = enabled && !isConnecting,
                                indication = rememberRipple(bounded = true),
                                interactionSource = remember { MutableInteractionSource() },
                                onLongClick = onLongClick,
                                onClick = onClick
                            )
                    )
                }
                if (isTesting) {
                    // 智能连接启动后继续显示旋转进度，避免长按进度圈停在满格。
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 5.dp
                    )
                } else if (longPressEnabled && longPressProgress > 0f) {
                    CircularProgressIndicator(
                        progress = longPressProgress,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 5.dp
                    )
                }
            }
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
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = if (customLabel != null) 26.sp else 20.sp
        )
    }
}
