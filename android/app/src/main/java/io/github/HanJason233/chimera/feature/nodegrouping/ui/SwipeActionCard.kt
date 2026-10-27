package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class SwipeCardAction(
    val icon: ImageVector,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit
)

/** 左滑展开操作，右滑进入删除；展开项由列表持有，确保同一时间只显示一张。 */
@Composable
internal fun SwipeActionCard(
    itemKey: String,
    actions: List<SwipeCardAction>,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onSwipeRight: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val density = LocalDensity.current
    val actionWidth = 64.dp * actions.size
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val deleteWidthPx = with(density) { 88.dp.toPx() }
    val dismissThresholdPx = with(density) { 72.dp.toPx() }
    val offsetX = remember(itemKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var settleRevision by remember(itemKey) { mutableIntStateOf(0) }
    val latestRevealed by rememberUpdatedState(revealed)
    val latestOnRevealedChange by rememberUpdatedState(onRevealedChange)
    val latestOnSwipeRight by rememberUpdatedState(onSwipeRight)

    // 每次松手都递增修订号，即使“关闭”状态没有变化，也必须把卡片吸附回锚点。
    LaunchedEffect(revealed, actionWidthPx, settleRevision) {
        offsetX.animateTo(
            targetValue = if (revealed) -actionWidthPx else 0f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
        )
    }

    fun settleTo(open: Boolean) {
        latestOnRevealedChange(open)
        settleRevision += 1
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        // 先测量卡片内容，再用 matchParentSize 让操作背景铺满整张卡片。
        Box(modifier = Modifier.matchParentSize()) {
            if (onSwipeRight != null) {
                SwipeDeleteBackground(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(88.dp)
                        .fillMaxHeight()
                )
            }

            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(actionWidth)
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    actions.forEach { action ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    latestOnRevealedChange(false)
                                    action.onClick()
                                },
                            color = action.containerColor,
                            contentColor = action.contentColor
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = action.label,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = action.label,
                                    modifier = Modifier.padding(top = 4.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        val dragModifier = Modifier
            .fillMaxWidth()
            .offset {
                IntOffset(offsetX.value.roundToInt(), 0)
            }
            .pointerInput(itemKey, actionWidthPx, onSwipeRight != null) {
                var dragStartX = 0f
                var dragTotal = 0f
                var snapJob: kotlinx.coroutines.Job? = null

                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartX = offsetX.value
                        dragTotal = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                        val lowerBound = -actionWidthPx
                        val upperBound = if (latestOnSwipeRight != null) deleteWidthPx else 0f
                        val nextOffset = (dragStartX + dragTotal).coerceIn(lowerBound, upperBound)
                        snapJob?.cancel()
                        snapJob = scope.launch { offsetX.snapTo(nextOffset) }
                    },
                    onDragEnd = {
                        val draggedTo = (dragStartX + dragTotal)
                            .coerceIn(-actionWidthPx, if (latestOnSwipeRight != null) deleteWidthPx else 0f)
                        val startedOpen = latestRevealed ||
                            dragStartX <= -actionWidthPx * 0.45f
                        snapJob?.cancel()
                        snapJob = null
                        when {
                            dragTotal >= dismissThresholdPx && !startedOpen -> {
                                settleTo(false)
                                latestOnSwipeRight?.invoke()
                            }
                            dragTotal >= dismissThresholdPx && startedOpen -> {
                                settleTo(false)
                            }
                            else -> {
                                val shouldOpen = actions.isNotEmpty() &&
                                    (dragTotal <= -dismissThresholdPx ||
                                        draggedTo <= -actionWidthPx * 0.45f)
                                settleTo(shouldOpen)
                            }
                        }
                    },
                    onDragCancel = {
                        snapJob?.cancel()
                        snapJob = null
                        val shouldRemainOpen =
                            actions.isNotEmpty() &&
                                (dragStartX + dragTotal) <= -actionWidthPx * 0.45f
                        settleTo(shouldRemainOpen)
                    }
                )
            }

        content(dragModifier)
    }
}

@Composable
private fun SwipeDeleteBackground(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null)
            Text(
                text = "删除",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
