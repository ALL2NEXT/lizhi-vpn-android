package io.github.HanJason233.chimera.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.RegionLookupMethod
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlin.math.abs

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun RegionLookupSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val lookupMethods by viewModel.regionLookupMethods.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    var displayedMethods by remember { mutableStateOf(lookupMethods) }
    var draggedMethod by remember { mutableStateOf<RegionLookupMethod?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var draggedItemHeight by remember { mutableFloatStateOf(1f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var reorderBaseline by remember { mutableFloatStateOf(0f) }
    var settlingMethod by remember { mutableStateOf<RegionLookupMethod?>(null) }
    var settleStartOffset by remember { mutableFloatStateOf(0f) }
    var settleAnimationReady by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val settleOffsetAnimation = remember { Animatable(0f) }

    LaunchedEffect(lookupMethods) {
        if (draggedMethod == null) displayedMethods = lookupMethods
    }

    LaunchedEffect(settlingMethod, settleStartOffset) {
        if (settlingMethod != null && abs(settleStartOffset) > 0.5f) {
            settleAnimationReady = false
            settleOffsetAnimation.snapTo(settleStartOffset)
            settleAnimationReady = true
            settleOffsetAnimation.animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            )
        }
        settlingMethod = null
        settleStartOffset = 0f
        settleAnimationReady = false
    }

    fun updateMethods(methods: List<RegionLookupMethod>) {
        val normalizedMethods = methods.distinct()
        displayedMethods = normalizedMethods
        viewModel.setRegionLookupMethods(normalizedMethods)
    }

    fun finishDrag(method: RegionLookupMethod) {
        val currentIndex = displayedMethods.indexOf(method)
        val settleOffset = if (currentIndex >= 0 && dragStartIndex >= 0) {
            dragDistance - (currentIndex - dragStartIndex) * draggedItemHeight
        } else {
            0f
        }
        if (abs(settleOffset) > 0.5f) {
            settlingMethod = method
            settleStartOffset = settleOffset
        }
        if (displayedMethods != lookupMethods) updateMethods(displayedMethods)
        draggedMethod = null
        dragStartIndex = -1
        dragDistance = 0f
        reorderBaseline = 0f
    }

    val startDrag = rememberUpdatedState<(RegionLookupMethod) -> Unit>(
        newValue = { method ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            settlingMethod = null
            val itemInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == "lookup:${method.name}" }
            draggedMethod = method
            dragStartIndex = displayedMethods.indexOf(method)
            draggedItemHeight = itemInfo?.size?.toFloat() ?: 100f
            dragDistance = 0f
            reorderBaseline = 0f
        }
    )
    val updateDrag = rememberUpdatedState<(RegionLookupMethod, Float) -> Unit>(
        newValue = { method, deltaY ->
            dragDistance += deltaY
            var baseline = reorderBaseline
            val reorderStep = draggedItemHeight * 0.55f
            val mutableMethods = displayedMethods.toMutableList()
            var currentIndex = mutableMethods.indexOf(method)
            var hasMoved = false

            while (
                dragDistance - baseline >= reorderStep &&
                currentIndex >= 0 && currentIndex < mutableMethods.lastIndex
            ) {
                mutableMethods.add(
                    currentIndex + 1,
                    mutableMethods.removeAt(currentIndex)
                )
                currentIndex += 1
                baseline += draggedItemHeight
                hasMoved = true
            }
            while (
                dragDistance - baseline <= -reorderStep &&
                currentIndex > 0
            ) {
                mutableMethods.add(
                    currentIndex - 1,
                    mutableMethods.removeAt(currentIndex)
                )
                currentIndex -= 1
                baseline -= draggedItemHeight
                hasMoved = true
            }
            reorderBaseline = baseline
            if (hasMoved) displayedMethods = mutableMethods
        }
    )
    val endDrag = rememberUpdatedState<(RegionLookupMethod) -> Unit>(::finishDrag)

    AppScreenScaffold(
        title = "地区查询",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp),
        actions = {
            IconButton(
                onClick = { showAddDialog = true },
                enabled = displayedMethods.size < RegionLookupMethod.entries.size
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加查询规则")
            }
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item(key = "lookup_intro") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "查询优先级",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "从上到下依次查询，识别到地区后停止。长按卡片拖动可调整顺序。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (displayedMethods.isEmpty()) {
                item(key = "lookup_empty") {
                    Text(
                        text = "还没有查询规则，点击右上角 + 添加。",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            itemsIndexed(
                items = displayedMethods,
                key = { _, method -> "lookup:${method.name}" }
            ) { index, method ->
                val isDragging = draggedMethod == method
                val isSettling = settlingMethod == method
                val currentIndex = displayedMethods.indexOf(method)
                val cardTranslation = when {
                    isDragging && currentIndex >= 0 && dragStartIndex >= 0 ->
                        dragDistance - (currentIndex - dragStartIndex) * draggedItemHeight
                    isSettling -> if (settleAnimationReady) {
                        settleOffsetAnimation.value
                    } else {
                        settleStartOffset
                    }
                    else -> 0f
                }
                val cardMovementModifier = Modifier
                    .zIndex(if (isDragging || isSettling) 1f else 0f)
                    .then(
                        if (isDragging || isSettling) {
                            Modifier.graphicsLayer { translationY = cardTranslation }
                        } else {
                            Modifier.animateItemPlacement()
                        }
                    )
                    .pointerInput(method) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startDrag.value(method) },
                            onDrag = { change, amount ->
                                change.consume()
                                updateDrag.value(method, amount.y)
                            },
                            onDragEnd = { endDrag.value(method) },
                            onDragCancel = { endDrag.value(method) }
                        )
                    }

                RegionLookupMethodCard(
                    method = method,
                    priority = index + 1,
                    isDragging = isDragging,
                    modifier = cardMovementModifier,
                    onDelete = {
                        updateMethods(displayedMethods.filterNot { it == method })
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        val availableMethods = RegionLookupMethod.entries.filterNot(displayedMethods::contains)
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加查询规则") },
            text = {
                if (availableMethods.isEmpty()) {
                    Text("三种查询方式都已添加。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        availableMethods.forEach { method ->
                            TextButton(
                                onClick = {
                                    updateMethods(displayedMethods + method)
                                    showAddDialog = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = method.title,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = method.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun RegionLookupMethodCard(
    method: RegionLookupMethod,
    priority: Int,
    isDragging: Boolean,
    modifier: Modifier,
    onDelete: () -> Unit
) {
    // 采用路由规则列表的平面行样式，拖动时再用阴影突出当前项。
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 4.dp else 0.dp,
        label = "RegionLookupRuleElevation"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = elevation
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = method.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = method.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "优先级 $priority",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF97910)
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = "长按拖动排序",
                        modifier = Modifier
                            .padding(12.dp)
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除${method.title}"
                        )
                    }
                }
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
