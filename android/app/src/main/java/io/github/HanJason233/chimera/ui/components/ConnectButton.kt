package io.github.HanJason233.chimera.ui.components

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import io.github.HanJason233.chimera.data.model.HomeIconEffect
import io.github.HanJason233.chimera.data.model.HomeIconMotionEffect
import io.github.HanJason233.chimera.data.model.VpnState

enum class ConnectButtonSwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** 主页连接图标的上下左右滑动回调。 */
    onSwipe: ((ConnectButtonSwipeDirection) -> Unit)? = null,
    customLabel: String? = null,
    /** 是否显示按钮下方的状态文字。 */
    showStatusLabel: Boolean = true,
    /** 无障碍服务读取的单击和长按动作名称。 */
    clickActionLabel: String = "测速方案",
    longClickActionLabel: String = "测速方案",
    enabled: Boolean = true,
    isTesting: Boolean = false,
    /** 四种连接状态的图片均可按当前皮肤方案单独覆盖，未设置时使用内置图片。 */
    customDisconnectedImagePath: String? = null,
    customConnectingImagePath: String? = null,
    customDisconnectingImagePath: String? = null,
    customConnectedImagePath: String? = null,
    homeIconEffect: HomeIconEffect = HomeIconEffect.RADIAL_PARTICLE,
    homeIconEffectEnabled: Boolean = true,
    disconnectedIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    connectingIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    disconnectingIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    connectedIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    maxButtonSize: Dp = 250.dp
) {
    // 测速方案执行期间底层状态仍可能是 DISCONNECTED，主页视觉上应展示“连接中”。
    val displayState = if (isTesting && vpnState == VpnState.DISCONNECTED) {
        VpnState.CONNECTING
    } else {
        vpnState
    }
    val isConnecting = displayState == VpnState.CONNECTING || displayState == VpnState.DISCONNECTING

    // 自适应按钮大小：取屏幕宽度的 65%，但不超过 250dp
    val configuration = LocalConfiguration.current
    val buttonSize = minOf(maxButtonSize.value.toInt(), (configuration.screenWidthDp * 0.65).toInt())

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
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
        label = "speedTestSchemeLongPressProgress"
    )
    val swipeEnabled = onSwipe != null && enabled && !isConnecting
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) {
        48.dp.toPx()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 各连接状态继续交叉淡入，图片本身支持 GIF 动图。
        Crossfade(
            targetState = displayState,
            animationSpec = tween(220),
            label = "buttonImage"
        ) { currentState ->
            val iconMotionEffect = when (currentState) {
                VpnState.DISCONNECTED -> disconnectedIconEffect
                VpnState.CONNECTING -> connectingIconEffect
                VpnState.DISCONNECTING -> disconnectingIconEffect
                VpnState.CONNECTED -> connectedIconEffect
            }
            val iconRotation = if (iconMotionEffect == HomeIconMotionEffect.ROTATE) {
                val rotationTransition = rememberInfiniteTransition(
                    label = "homeIconRotation-${currentState.name}"
                )
                val animatedRotation by rotationTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "homeIconRotation-${currentState.name}"
                )
                animatedRotation
            } else {
                0f
            }
            // 闪烁像星光一样渐暗后恢复，使用透明度往返变化而不影响图标颜色。
            val iconAlpha = if (iconMotionEffect == HomeIconMotionEffect.BLINK) {
                val blinkTransition = rememberInfiniteTransition(
                    label = "homeIconBlink-${currentState.name}"
                )
                val animatedAlpha by blinkTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "homeIconBlink-${currentState.name}"
                )
                animatedAlpha
            } else {
                1f
            }
            val currentImagePath = when (currentState) {
                VpnState.DISCONNECTED -> customDisconnectedImagePath
                VpnState.CONNECTING -> customConnectingImagePath
                VpnState.DISCONNECTING -> customDisconnectingImagePath
                VpnState.CONNECTED -> customConnectedImagePath
            }
            Box(
                modifier = Modifier.size((buttonSize * 1.42f).dp),
                contentAlignment = Alignment.Center
            ) {
                if (homeIconEffectEnabled) {
                    when (homeIconEffect) {
                        HomeIconEffect.RADIAL_PARTICLE ->
                            RadialParticleEffect(modifier = Modifier.matchParentSize())
                    }
                }
                Box(
                    modifier = Modifier.size(buttonSize.dp),
                    contentAlignment = Alignment.Center
                ) {
                    HomeConnectStateIcon(
                        imagePath = currentImagePath,
                        state = currentState,
                        contentDescription = when (currentState) {
                            VpnState.DISCONNECTED -> "点击连接 VPN"
                            VpnState.CONNECTING -> "VPN 连接中"
                            VpnState.DISCONNECTING -> "VPN 断开中"
                            VpnState.CONNECTED -> "VPN 已连接"
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = iconRotation
                                alpha = iconAlpha
                            }
                    )
                    // 手势层裁剪为圆形，图片本身保持完整显示，避免透明图片的角落可触发。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = enabled && !isConnecting,
                                indication = rememberRipple(bounded = true),
                                interactionSource = interactionSource,
                                onClickLabel = if (vpnState == VpnState.DISCONNECTED) clickActionLabel else null,
                                onLongClickLabel = if (onLongClick != null) longClickActionLabel else null,
                                onLongClick = onLongClick,
                                onClick = onClick
                            )
                            .connectButtonSwipeGesture(
                                enabled = swipeEnabled,
                                threshold = swipeThreshold,
                                onSwipe = onSwipe
                            )
                            .semantics {
                                this.contentDescription = when (displayState) {
                                    VpnState.CONNECTED -> "已连接"
                                    VpnState.CONNECTING -> "连接中"
                                    VpnState.DISCONNECTING -> "断开中"
                                    VpnState.DISCONNECTED -> "点击连接"
                                }
                            }
                    )
                    if (longPressEnabled && longPressProgress > 0f) {
                        CircularProgressIndicator(
                            progress = longPressProgress,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 5.dp
                        )
                    }
                }
            }
        }

        if (showStatusLabel) {
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
}

private data class RadialParticle(
    val angle: Float,
    val phase: Float,
    val speed: Float,
    val radiusDp: Float,
    val color: Color
)

@Composable
private fun RadialParticleEffect(modifier: Modifier = Modifier) {
    // 使用持续帧时间并让每个粒子拥有独立速度，避免整组粒子同步重播。
    val elapsedNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val startedAt = withFrameNanos { it }
        while (true) {
            elapsedNanos.longValue = withFrameNanos { it - startedAt }
        }
    }
    val particles = remember {
        val colors = listOf(
            Color(0xFF35D6FF),
            Color(0xFF6C63FF),
            Color(0xFFFF5CA8),
            Color(0xFFFFC857),
            Color(0xFF56E39F)
        )
        List(44) { index ->
            RadialParticle(
                angle = (index * 2.399963f) % (2f * Math.PI.toFloat()),
                phase = ((index * 37) % 100) / 100f,
                speed = 0.72f + index * 0.013f,
                radiusDp = 1.1f + (index % 3) * 0.35f,
                color = colors[index % colors.size]
            )
        }
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        // 从图标内部开始发散，点阵会透过 PNG 的透明区域显示出来，实色图案仍在上层遮挡点阵。
        val innerRadius = size.minDimension * 0.08f
        val outerRadius = size.minDimension * 0.49f
        val elapsedSeconds = elapsedNanos.longValue / 1_000_000_000f
        particles.forEachIndexed { index, particle ->
            val particleProgress = (elapsedSeconds * particle.speed / 3.2f + particle.phase) % 1f
            val distance = innerRadius + (outerRadius - innerRadius) * particleProgress
            val angle = particle.angle + kotlin.math.sin(elapsedSeconds * 0.4f + index) * 0.035f
            val alpha = kotlin.math.sin(particleProgress * Math.PI.toFloat()) * 0.72f
            val point = Offset(
                x = center.x + kotlin.math.cos(angle) * distance,
                y = center.y + kotlin.math.sin(angle) * distance
            )
            drawCircle(
                color = particle.color.copy(alpha = alpha.coerceAtLeast(0f)),
                radius = particle.radiusDp.dp.toPx() * (1f - particleProgress * 0.28f),
                center = point
            )
        }
    }
}

private fun Modifier.connectButtonSwipeGesture(
    enabled: Boolean,
    threshold: Float,
    onSwipe: ((ConnectButtonSwipeDirection) -> Unit)?
): Modifier {
    if (!enabled || onSwipe == null) return this

    return pointerInput(enabled, threshold, onSwipe) {
        var totalDrag = Offset.Zero
        detectDragGestures(
            onDragStart = {
                totalDrag = Offset.Zero
            },
            onDrag = { change, dragAmount ->
                change.consumePositionChange()
                totalDrag += dragAmount
            },
            onDragEnd = {
                if (totalDrag.getDistance() < threshold) return@detectDragGestures
                val direction = if (kotlin.math.abs(totalDrag.x) > kotlin.math.abs(totalDrag.y)) {
                    if (totalDrag.x > 0) {
                        ConnectButtonSwipeDirection.RIGHT
                    } else {
                        ConnectButtonSwipeDirection.LEFT
                    }
                } else if (totalDrag.y > 0) {
                    ConnectButtonSwipeDirection.DOWN
                } else {
                    ConnectButtonSwipeDirection.UP
                }
                onSwipe(direction)
            },
            onDragCancel = {
                totalDrag = Offset.Zero
            }
        )
    }
}
