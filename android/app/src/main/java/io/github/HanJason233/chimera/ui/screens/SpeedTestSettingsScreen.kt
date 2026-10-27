package io.github.HanJason233.chimera.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.SpeedTestCategory
import io.github.HanJason233.chimera.data.model.SpeedTestSettings
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.isStageEnabled
import io.github.HanJason233.chimera.data.model.normalizeSpeedTestStageOrder
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedTestSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val homeSettings by viewModel.homeSpeedTestSettings.collectAsStateWithLifecycle()
    val manualSettings by viewModel.groupManualSpeedTestSettings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    AppScreenScaffold(
        title = "测速设置",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Home,
                            contentDescription = "主页图标测速设置"
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("手动测速", maxLines = 1) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                val isHomeTab = page == 0
                SpeedTestSettingsPage(
                    settings = if (isHomeTab) homeSettings else manualSettings,
                    isHomeTab = isHomeTab,
                    onSave = { settings ->
                        if (isHomeTab) {
                            viewModel.setHomeSpeedTestSettings(settings)
                        } else {
                            viewModel.setGroupManualSpeedTestSettings(settings)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedTestSettingsPage(
    settings: SpeedTestSettings,
    isHomeTab: Boolean,
    onSave: (SpeedTestSettings) -> Unit
) {
    var editingStage by remember { mutableStateOf<SpeedTestStage?>(null) }
    var displayedStages by remember(settings.stageOrder) {
        mutableStateOf(normalizeSpeedTestStageOrder(settings.stageOrder))
    }
    var draggedStage by remember { mutableStateOf<SpeedTestStage?>(null) }
    var draggedItemTop by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableFloatStateOf(1f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var autoScrollAmount by remember { mutableFloatStateOf(0f) }
    var isMoveLayoutPending by remember { mutableStateOf(false) }
    var isDropPending by remember { mutableStateOf(false) }
    var pendingLayoutIndex by remember { mutableIntStateOf(-1) }

    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val autoScrollThreshold = with(density) { 56.dp.toPx() }
    val maxAutoScrollPerFrame = with(density) { 12.dp.toPx() }
    val latestSettings by rememberUpdatedState(settings)
    val latestOnSave by rememberUpdatedState(onSave)
    val updateAutoScrollAmount = rememberUpdatedState<() -> Unit>(
        newValue = {
            val layoutInfo = listState.layoutInfo
            val dragTop = draggedItemTop + dragDistance
            val dragBottom = dragTop + draggedItemHeight
            val distanceFromTop = dragTop - layoutInfo.viewportStartOffset
            val distanceFromBottom = layoutInfo.viewportEndOffset - dragBottom
            autoScrollAmount = when {
                distanceFromTop < autoScrollThreshold ->
                    -maxAutoScrollPerFrame *
                        ((autoScrollThreshold - distanceFromTop) / autoScrollThreshold).coerceIn(0f, 1f)
                distanceFromBottom < autoScrollThreshold ->
                    maxAutoScrollPerFrame *
                        ((autoScrollThreshold - distanceFromBottom) / autoScrollThreshold).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    )

    val moveDraggedStage = rememberUpdatedState<(SpeedTestStage) -> Unit>(
        newValue = { activeStage ->
            if (!isMoveLayoutPending) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val dragTop = draggedItemTop + dragDistance
                val dragBottom = dragTop + draggedItemHeight
                val targetInfo = visibleItems.firstOrNull { item ->
                    val targetName = (item.key as? String)
                        ?.takeIf { it.startsWith("stage:") }
                        ?.removePrefix("stage:")
                    val targetCenter = item.offset + item.size / 2f
                    val targetStage = targetName?.let { name ->
                        SpeedTestStage.entries.firstOrNull { it.name == name }
                    }
                    targetStage != null && targetStage != activeStage &&
                        targetCenter >= dragTop && targetCenter <= dragBottom
                }
                val targetName = (targetInfo?.key as? String)
                    ?.takeIf { it.startsWith("stage:") }
                    ?.removePrefix("stage:")
                val targetStage = targetName?.let { name ->
                    SpeedTestStage.entries.firstOrNull { it.name == name }
                }
                val sourceIndex = displayedStages.indexOf(activeStage)
                val targetIndex = displayedStages.indexOf(targetStage)

                if (targetStage != null && sourceIndex >= 0 && targetIndex >= 0 &&
                    sourceIndex != targetIndex
                ) {
                    pendingLayoutIndex = targetIndex
                    isMoveLayoutPending = true
                    displayedStages = displayedStages.toMutableList().apply {
                        add(targetIndex, removeAt(sourceIndex))
                    }
                }
            }
        }
    )

    fun finishDrag() {
        val normalizedSavedOrder = normalizeSpeedTestStageOrder(latestSettings.stageOrder)
        if (displayedStages != normalizedSavedOrder) {
            // 卡片顺序按当前入口保存；执行时仍遵循各测试阶段的依赖关系。
            latestOnSave(latestSettings.copy(stageOrder = displayedStages))
        }
        draggedStage = null
        isMoveLayoutPending = false
        isDropPending = false
        pendingLayoutIndex = -1
        autoScrollAmount = 0f
    }

    LaunchedEffect(isMoveLayoutPending, pendingLayoutIndex, draggedStage) {
        val activeStage = draggedStage
        val expectedIndex = pendingLayoutIndex
        if (!isMoveLayoutPending || activeStage == null || expectedIndex < 0) {
            return@LaunchedEffect
        }
        withTimeoutOrNull(1_000L) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "stage:${activeStage.name}" }
                    ?.index
            }.first { it == expectedIndex }
        }
        if (draggedStage == activeStage && pendingLayoutIndex == expectedIndex) {
            isMoveLayoutPending = false
        }
    }

    LaunchedEffect(isDropPending, isMoveLayoutPending, draggedStage) {
        if (isDropPending && !isMoveLayoutPending && draggedStage != null) {
            finishDrag()
        }
    }

    LaunchedEffect(draggedStage) {
        if (draggedStage == null) return@LaunchedEffect
        while (true) {
            val scrollDelta = autoScrollAmount
            if (scrollDelta != 0f) {
                listState.scroll { scrollBy(scrollDelta) }
                draggedStage?.let(moveDraggedStage.value)
            }
            withFrameNanos { }
        }
    }

    BackHandler(enabled = editingStage != null) {
        editingStage = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (editingStage == null) {
                if (isHomeTab) {
                    "主页图标长按测速使用此配置。长按卡片拖动排序，点击卡片进入详细设置。"
                } else {
                    "分组详情中的手动测速使用此配置。长按卡片拖动排序，点击卡片进入详细设置。"
                }
            } else if (!isHomeTab && editingStage?.category !=
                SpeedTestCategory.LINK_QUALITY
            ) {
                "此项仅在主页测速连接成功后执行，手动测速不会运行。"
            } else {
                "修改${editingStage?.title}的测试参数。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
        )

        val selectedStage = editingStage
        if (selectedStage == null) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(
                    items = displayedStages,
                    key = { _, stage -> "stage:${stage.name}" }
                ) { _, stage ->
                    val isDragging = draggedStage == stage
                    val itemInfo = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "stage:${stage.name}" }
                    val itemOffset = itemInfo?.offset?.toFloat() ?: 0f
                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 6.dp else 1.dp,
                        label = "SpeedTestStageDragElevation"
                    )
                    val placementModifier = if (isDragging) {
                        Modifier.graphicsLayer {
                            translationY = draggedItemTop + dragDistance - itemOffset
                        }
                    } else {
                        Modifier.animateItemPlacement()
                    }

                    SpeedTestStageSummaryCard(
                        stage = stage,
                        settings = settings,
                        isHomeTab = isHomeTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(placementModifier)
                            .zIndex(if (isDragging) 1f else 0f)
                            .pointerInput(stage) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                        val info = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == "stage:${stage.name}" }
                                        isMoveLayoutPending = false
                                        isDropPending = false
                                        pendingLayoutIndex = -1
                                        draggedStage = stage
                                        draggedItemTop = info?.offset?.toFloat() ?: 0f
                                        draggedItemHeight = info?.size?.toFloat() ?: 1f
                                        dragDistance = 0f
                                        updateAutoScrollAmount.value()
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragDistance += amount.y
                                        updateAutoScrollAmount.value()
                                        draggedStage?.let(moveDraggedStage.value)
                                    },
                                    onDragEnd = {
                                        if (isMoveLayoutPending) {
                                            isDropPending = true
                                        } else {
                                            finishDrag()
                                        }
                                    },
                                    onDragCancel = {
                                        if (isMoveLayoutPending) {
                                            isDropPending = true
                                        } else {
                                            finishDrag()
                                        }
                                    }
                                )
                            },
                        elevation = elevation,
                        onClick = { editingStage = stage }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
            ) {
                TextButton(onClick = { editingStage = null }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                    Text("返回测试项")
                }
                SpeedTestSettingsEditorContent(
                    settings = settings,
                    stage = selectedStage,
                    isHomeTab = isHomeTab,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSave = { updatedSettings ->
                        onSave(updatedSettings.copy(stageOrder = displayedStages))
                        editingStage = null
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedTestStageSummaryCard(
    stage: SpeedTestStage,
    settings: SpeedTestSettings,
    isHomeTab: Boolean,
    modifier: Modifier = Modifier,
    elevation: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val summary = when (stage) {
        SpeedTestStage.TCPING -> "连接可达性 · ${settings.tcpingTimeoutMs} ms 超时"
        SpeedTestStage.URL_TEST -> "204 URL 连通性 · ${settings.urlTestUrl}"
        SpeedTestStage.BANDWIDTH -> "下载测速 · ${settings.bandwidthTestDurationMs / 1_000f} 秒"
        SpeedTestStage.LINK_TYPE -> if (isHomeTab) {
            "连接后识别直连或中转链路类型"
        } else {
            "仅在主页连接后执行"
        }
        SpeedTestStage.IP_QUALITY -> if (isHomeTab) {
            "已选择 ${settings.unlockTestIds.size} 项流媒体与 AI 解锁检测"
        } else {
            "仅在主页连接后执行"
        }
    }
    val enabled = isHomeTab || stage.number <= SpeedTestStage.BANDWIDTH.number

    Card(
        modifier = modifier.clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = "长按拖动排序",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp).size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${stage.number}. ${stage.title}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (enabled) {
                            if (settings.isStageEnabled(stage)) "已开启" else "已关闭"
                        } else {
                            "连接后检测"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled && settings.isStageEnabled(stage)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "设置${stage.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(20.dp)
            )
        }
    }
}
