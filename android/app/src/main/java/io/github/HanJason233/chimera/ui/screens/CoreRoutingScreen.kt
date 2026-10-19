package io.github.HanJason233.chimera.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.HanJason233.chimera.data.model.SmartRoutingDomainStrategy
import io.github.HanJason233.chimera.data.model.SmartRoutingPreset
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.data.model.SmartRoutingRulesCodec
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private enum class RoutingMenuAction(val title: String) {
    PRESETS("导入预设"),
    IMPORT_CLIPBOARD("从剪贴板导入"),
    EXPORT_CLIPBOARD("复制到剪贴板")
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun CoreRoutingScreen(
    viewModel: MainViewModel,
    mode: ProxyMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsFlow = remember(viewModel, mode) { viewModel.routingSettings(mode) }
    val routingSettings by settingsFlow.collectAsStateWithLifecycle()
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingRule by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var strategyMenuExpanded by remember { mutableStateOf(false) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    var displayedRules by remember { mutableStateOf(routingSettings.rules) }
    var draggedRuleId by remember { mutableStateOf<String?>(null) }
    var isMoveLayoutPending by remember { mutableStateOf(false) }
    var pendingLayoutIndex by remember { mutableIntStateOf(-1) }
    var predictedDraggedItemTop by remember { mutableFloatStateOf(0f) }
    var isDropPending by remember { mutableStateOf(false) }
    var settlingRuleId by remember { mutableStateOf<String?>(null) }
    var pendingSettleOffset by remember { mutableFloatStateOf(0f) }
    var draggedItemTop by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableFloatStateOf(1f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var autoScrollAmount by remember { mutableFloatStateOf(0f) }
    val settleOffsetAnimation = remember { Animatable(0f) }
    val density = LocalDensity.current
    val autoScrollThreshold = with(density) { 56.dp.toPx() }
    val maxAutoScrollPerFrame = with(density) { 12.dp.toPx() }
    val editingRule = routingSettings.rules.firstOrNull { it.id == editingRuleId }

    LaunchedEffect(routingSettings.rules) {
        val latestRules = routingSettings.rules
        if (draggedRuleId == null) {
            displayedRules = latestRules
        } else {
            // 拖动期间保留当前排序，同时同步规则内容的外部更新。
            val latestById = latestRules.associateBy(SmartRoutingRule::id)
            val displayedIds = displayedRules.map(SmartRoutingRule::id).toSet()
            displayedRules = displayedRules.mapNotNull { latestById[it.id] } +
                latestRules.filterNot { it.id in displayedIds }
        }
    }

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

    val moveDraggedRule = rememberUpdatedState<(String) -> Unit>(
        newValue = { activeRuleId ->
            if (!isMoveLayoutPending) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val dragTop = draggedItemTop + dragDistance
                val dragBottom = dragTop + draggedItemHeight
                val targetInfo = visibleItems.firstOrNull { item ->
                    val targetId = (item.key as? String)
                        ?.takeIf { it.startsWith("rule:") }
                        ?.removePrefix("rule:")
                    val targetCenter = item.offset + item.size / 2f
                    targetId != null && targetId != activeRuleId &&
                        targetCenter >= dragTop && targetCenter <= dragBottom
                }
                val targetId = (targetInfo?.key as? String)
                    ?.takeIf { it.startsWith("rule:") }
                    ?.removePrefix("rule:")
                val sourceIndex = displayedRules.indexOfFirst { it.id == activeRuleId }
                val targetIndex = displayedRules.indexOfFirst { it.id == targetId }

                if (
                    targetId != null && targetInfo != null &&
                    sourceIndex >= 0 && targetIndex >= 0 && sourceIndex != targetIndex
                ) {
                    // 每次换位后等待 LazyColumn 完成布局，再处理下一次换位。
                    predictedDraggedItemTop = if (targetIndex > sourceIndex) {
                        (targetInfo.offset + targetInfo.size - draggedItemHeight).toFloat()
                    } else {
                        targetInfo.offset.toFloat()
                    }
                    pendingLayoutIndex = targetInfo.index
                    isMoveLayoutPending = true
                    displayedRules = displayedRules.toMutableList().apply {
                        add(targetIndex, removeAt(sourceIndex))
                    }
                }
            }
        }
    )

    LaunchedEffect(isMoveLayoutPending, pendingLayoutIndex, draggedRuleId) {
        val activeRuleId = draggedRuleId
        val expectedIndex = pendingLayoutIndex
        if (!isMoveLayoutPending || activeRuleId == null || expectedIndex < 0) {
            return@LaunchedEffect
        }

        withTimeoutOrNull(1_000L) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "rule:$activeRuleId" }
                    ?.index
            }.first { it == expectedIndex }
        }
        if (draggedRuleId == activeRuleId && pendingLayoutIndex == expectedIndex) {
            isMoveLayoutPending = false
        }
    }

    val finishRuleDrag = rememberUpdatedState<() -> Unit>(
        newValue = {
            val activeRuleId = draggedRuleId
            val activeItemOffset = activeRuleId?.let { id ->
                val activeItemInfo = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "rule:$id" }
                if (
                    isMoveLayoutPending ||
                    (pendingLayoutIndex >= 0 && activeItemInfo?.index != pendingLayoutIndex)
                ) {
                    predictedDraggedItemTop
                } else {
                    activeItemInfo?.offset?.toFloat()
                }
            }
            if (activeRuleId != null && activeItemOffset != null) {
                val currentTranslation = draggedItemTop + dragDistance - activeItemOffset
                if (kotlin.math.abs(currentTranslation) > 0.5f) {
                    pendingSettleOffset = currentTranslation
                    settlingRuleId = activeRuleId
                }
            }
            val orderedIds = displayedRules.map(SmartRoutingRule::id)
            if (orderedIds != routingSettings.rules.map(SmartRoutingRule::id)) {
                // 松手后一次性保存顺序，避免拖动经过每条规则时都重启代理。
                viewModel.reorderSmartRoutingRules(orderedIds, mode)
            }
            draggedRuleId = null
            isMoveLayoutPending = false
            pendingLayoutIndex = -1
            isDropPending = false
            autoScrollAmount = 0f
        }
    )

    val requestFinishRuleDrag = rememberUpdatedState<() -> Unit>(
        newValue = {
            if (isMoveLayoutPending) {
                isDropPending = true
            } else {
                finishRuleDrag.value()
            }
        }
    )

    LaunchedEffect(isDropPending, isMoveLayoutPending, draggedRuleId) {
        if (isDropPending && !isMoveLayoutPending && draggedRuleId != null) {
            finishRuleDrag.value()
        }
    }

    LaunchedEffect(draggedRuleId) {
        if (draggedRuleId == null) return@LaunchedEffect
        while (true) {
            val scrollDelta = autoScrollAmount
            if (scrollDelta != 0f) {
                listState.scroll { scrollBy(scrollDelta) }
                val activeRuleId = draggedRuleId ?: break
                moveDraggedRule.value(activeRuleId)
            }
            withFrameNanos { }
        }
    }

    LaunchedEffect(settlingRuleId) {
        val ruleId = settlingRuleId ?: return@LaunchedEffect
        settleOffsetAnimation.snapTo(pendingSettleOffset)
        settleOffsetAnimation.animateTo(
            targetValue = 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        )
        if (settlingRuleId == ruleId) settlingRuleId = null
    }

    fun importRules(json: String) {
        viewModel.importSmartRoutingRules(json, mode) { success ->
            Toast.makeText(
                context,
                if (success) "路由规则已导入" else "导入失败，请检查规则 JSON",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    AppScreenScaffold(
        title = if (mode == ProxyMode.GLOBAL) "全局路由规则" else "智能路由规则",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp),
        actions = {
            IconButton(onClick = { addingRule = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加路由规则")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "路由规则菜单")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                RoutingMenuAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.title) },
                        onClick = {
                            menuExpanded = false
                            when (action) {
                                RoutingMenuAction.PRESETS -> showPresets = true
                                RoutingMenuAction.IMPORT_CLIPBOARD -> {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (text.isNullOrBlank()) {
                                        Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                    } else {
                                        importRules(text)
                                    }
                                }
                                RoutingMenuAction.EXPORT_CLIPBOARD -> {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Chimera 路由规则",
                                            SmartRoutingRulesCodec.encode(routingSettings.rules)
                                        )
                                    )
                                    Toast.makeText(context, "路由规则已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item(key = "domain_strategy") {
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { strategyMenuExpanded = true }
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "域名策略",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = routingSettings.domainStrategy.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = strategyMenuExpanded,
                        onDismissRequest = { strategyMenuExpanded = false }
                    ) {
                        SmartRoutingDomainStrategy.entries.forEach { strategy ->
                            DropdownMenuItem(
                                text = { Text(strategy.title) },
                                onClick = {
                                    strategyMenuExpanded = false
                                    viewModel.setSmartRoutingDomainStrategy(strategy, mode)
                                }
                            )
                        }
                    }
                }
            }
            item(key = "routing_rules_title") {
                Text(
                    text = "路由规则设置",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                Divider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            itemsIndexed(
                items = displayedRules,
                key = { _, rule -> "rule:${rule.id}" }
            ) { _, rule ->
                Column(
                    modifier = Modifier
                        .zIndex(if (draggedRuleId == rule.id || settlingRuleId == rule.id) 1f else 0f)
                        .then(
                            if (draggedRuleId == rule.id || settlingRuleId == rule.id) {
                                val itemOffset = if (
                                    draggedRuleId == rule.id && isMoveLayoutPending
                                ) {
                                    predictedDraggedItemTop
                                } else {
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "rule:${rule.id}" }
                                        ?.offset
                                        ?.toFloat() ?: draggedItemTop
                                }
                                Modifier.graphicsLayer {
                                    translationY = if (draggedRuleId == rule.id) {
                                        draggedItemTop + dragDistance - itemOffset
                                    } else {
                                        settleOffsetAnimation.value
                                    }
                                }
                            } else {
                                Modifier.animateItemPlacement()
                            }
                        )
                ) {
                    RoutingRuleItem(
                        rule = rule,
                        isDragging = draggedRuleId == rule.id,
                        onEdit = { editingRuleId = rule.id },
                        onEnabledChange = { enabled ->
                            viewModel.setSmartRoutingRuleEnabled(rule.id, enabled, mode)
                        },
                        // 整行作为长按拖动区域，保持与 v2rayNG 路由列表一致的操作方式。
                        dragModifier = Modifier.pointerInput(rule.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    // 长按后抬起当前项，并给出触觉提示。
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.LongPress
                                    )
                                    settlingRuleId = null
                                    val info = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "rule:${rule.id}" }
                                    isMoveLayoutPending = false
                                    pendingLayoutIndex = -1
                                    isDropPending = false
                                    draggedRuleId = rule.id
                                    draggedItemTop = info?.offset?.toFloat() ?: 0f
                                    draggedItemHeight = info?.size?.toFloat() ?: 1f
                                    predictedDraggedItemTop = draggedItemTop
                                    dragDistance = 0f
                                    updateAutoScrollAmount.value()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDistance += amount.y
                                    val sourceId = draggedRuleId
                                    if (sourceId != null) {
                                        updateAutoScrollAmount.value()
                                        moveDraggedRule.value(sourceId)
                                    }
                                },
                                onDragEnd = { requestFinishRuleDrag.value() },
                                onDragCancel = { requestFinishRuleDrag.value() }
                            )
                        }
                    )
                    Divider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }

    if (addingRule || editingRule != null) {
        SmartRoutingRuleEditorDialog(
            initialRule = editingRule,
            onDismiss = {
                addingRule = false
                editingRuleId = null
            },
            onSave = { rule ->
                viewModel.saveSmartRoutingRule(rule, mode)
                addingRule = false
                editingRuleId = null
            },
            onDelete = { ruleId ->
                viewModel.deleteSmartRoutingRule(ruleId, mode)
                addingRule = false
                editingRuleId = null
            }
        )
    }

    if (showPresets) {
        AppAlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("选择路由预设") },
            text = {
                Column {
                    SmartRoutingPreset.entries.forEach { preset ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showPresets = false
                                val json = runCatching {
                                    context.assets.open("routing-presets/${preset.assetName}")
                                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                                }.getOrNull()
                                if (json == null) {
                                    Toast.makeText(context, "无法读取预设", Toast.LENGTH_SHORT).show()
                                } else {
                                    importRules(json)
                                }
                            }
                        ) {
                            Text(preset.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) { Text("取消") }
            }
        )
    }

}

@Composable
private fun RoutingRuleItem(
    rule: SmartRoutingRule,
    isDragging: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    dragModifier: Modifier
) {
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 4.dp else 0.dp,
        label = "RoutingRuleDragElevation"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f),
        shadowElevation = elevation
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(dragModifier)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (rule.locked) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "锁定规则",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                rule.listDisplaySummary().takeIf(String::isNotBlank)?.let { summary ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (rule.outboundTag.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = rule.outboundTag,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF97910)
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑${rule.name}")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.scale(0.7f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                        checkedTrackColor = MaterialTheme.colorScheme.secondary
                    )
                )
            }
        }
    }
}

private fun SmartRoutingRule.listDisplaySummary(): String {
    val ruleDomains = domains.ifEmpty {
        if (type == SmartRoutingRuleType.CUSTOM_DOMAIN) values else emptyList()
    }
    val ruleIps = ips.ifEmpty {
        if (type == SmartRoutingRuleType.CUSTOM_IP) values else emptyList()
    }
    return when {
        ruleDomains.isNotEmpty() -> ruleDomains.toString()
        ruleIps.isNotEmpty() -> ruleIps.toString()
        processes.isNotEmpty() -> processes.toString()
        ports.isNotBlank() -> ports
        values.isNotEmpty() -> values.toString()
        else -> ""
    }
}
