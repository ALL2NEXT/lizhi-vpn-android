package xyz.a202132.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.abs
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeDisplaySettings
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.ProtocolCoreSettings
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.util.CountryCodeResolver

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun NodeListScreen(
    nodes: List<Node>,
    selectedNodeId: String?,
    category: NodeListCategory,
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    favoriteSourceNodeIds: Set<String>,
    availableRegionCodes: List<String>,
    selectedRegionCodes: Set<String>,
    skipFavoriteRemovalConfirmation: Boolean,
    isTesting: Boolean,
    isRefreshing: Boolean,
    isRefreshingAllSubscriptions: Boolean,
    hideFavoriteNodeGroup: Boolean,
    nodeDisplaySettings: NodeDisplaySettings,
    protocolCoreSettings: ProtocolCoreSettings,
    subscriptionSourceUpdatedAt: Map<String, Long>,
    testingLabel: String? = null,
    onNodeSelected: (Node) -> Unit,
    onCategoryChange: (NodeListCategory) -> Unit,
    onSubscriptionSourceChange: (String) -> Unit,
    onRegionFilterChange: (Set<String>) -> Unit,
    onToggleFavorite: (Node) -> Unit,
    onSkipFavoriteRemovalConfirmationForSession: () -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onTcpingTest: () -> Unit,
    onUrlTest: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshAll: () -> Unit,
    onBack: () -> Unit,
    showBackNavigation: Boolean = true,
    bottomContentPadding: Dp = 16.dp
) {
    val context = LocalContext.current
    var isClosing by remember { mutableStateOf(false) }
    var favoriteRemovalCandidate by remember { mutableStateOf<Node?>(null) }
    var skipConfirmationForSessionChecked by remember { mutableStateOf(false) }
    var showRegionFilterDialog by remember { mutableStateOf(false) }

    val handleBack = {
        if (!isClosing) {
            isClosing = true
            onBack()
        }
    }

    val handleNodeSelected: (Node) -> Unit = { node ->
        if (!isClosing) {
            isClosing = true
            onNodeSelected(node)
        }
    }

    val handleToggleFavorite: (Node, Boolean) -> Unit = { node, isFavorite ->
        when {
            !isFavorite || skipFavoriteRemovalConfirmation -> onToggleFavorite(node)
            else -> {
                skipConfirmationForSessionChecked = false
                favoriteRemovalCandidate = node
            }
        }
    }

    val handleImportFromClipboard = {
        if (!isClosing) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()
            if (clipText.isNullOrBlank()) {
                Toast.makeText(context, "\u526a\u5207\u677f\u4e3a\u7a7a", Toast.LENGTH_SHORT).show()
            } else {
                onImportFromText(clipText)
            }
        }
    }

    val handleScanQrCode = {
        if (!isClosing) {
            onScanQrCode()
        }
    }

    AppScreenScaffold(
        title = "节点列表",
        subtitle = if (isRefreshingAllSubscriptions) "节点刷新中..." else testingLabel,
        onBack = handleBack,
        backEnabled = !isClosing,
        showBackNavigation = showBackNavigation,
        actions = {
            NodeListTopActions(
                enabled = !isClosing,
                regionFilterEnabled = !isClosing && !isTesting,
                regionFilterActive = selectedRegionCodes.isNotEmpty(),
                onTcpingTest = onTcpingTest,
                onUrlTest = onUrlTest,
                onRegionFilter = { showRegionFilterDialog = true },
                onRefresh = onRefreshAll
            )
        },
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        NodeListContent(
            nodes = nodes,
            selectedNodeId = selectedNodeId,
            category = category,
            subscriptionGroups = subscriptionGroups,
            selectedSubscriptionSourceId = selectedSubscriptionSourceId,
            favoriteSourceNodeIds = favoriteSourceNodeIds,
            isTesting = isTesting,
            isRefreshing = isRefreshing,
            isRefreshingAllSubscriptions = isRefreshingAllSubscriptions,
            hideFavoriteNodeGroup = hideFavoriteNodeGroup,
            nodeDisplaySettings = nodeDisplaySettings,
            protocolCoreSettings = protocolCoreSettings,
            subscriptionSourceUpdatedAt = subscriptionSourceUpdatedAt,
            onRefresh = onRefresh,
            onImportFromClipboard = handleImportFromClipboard,
            onScanQrCode = handleScanQrCode,
            onNodeSelected = handleNodeSelected,
            onCategoryChange = onCategoryChange,
            onSubscriptionSourceChange = onSubscriptionSourceChange,
            onToggleFavorite = handleToggleFavorite,
            interactionEnabled = !isClosing,
            bottomContentPadding = bottomContentPadding
        )
    }

    favoriteRemovalCandidate?.let { node ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(text = "取消收藏")
            },
            text = {
                Column {
                    Text(text = "取消收藏的节点会从收藏节点中删除，是否确认删除？")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                skipConfirmationForSessionChecked =
                                    !skipConfirmationForSessionChecked
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = skipConfirmationForSessionChecked,
                            onCheckedChange = { skipConfirmationForSessionChecked = it }
                        )
                        Text(
                            text = "本次使用 APP 不再提示",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (skipConfirmationForSessionChecked) {
                            onSkipFavoriteRemovalConfirmationForSession()
                        }
                        favoriteRemovalCandidate = null
                    }
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (skipConfirmationForSessionChecked) {
                            onSkipFavoriteRemovalConfirmationForSession()
                        }
                        favoriteRemovalCandidate = null
                        onToggleFavorite(node)
                    }
                ) {
                    Text("确认")
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
    }

    if (showRegionFilterDialog) {
        NodeRegionFilterDialog(
            availableRegionCodes = availableRegionCodes,
            selectedRegionCodes = selectedRegionCodes,
            onDismiss = { showRegionFilterDialog = false },
            onConfirm = { selectedCodes ->
                showRegionFilterDialog = false
                onRegionFilterChange(selectedCodes)
            }
        )
    }

}
@Composable
private fun NodeListTopActions(
    enabled: Boolean,
    regionFilterEnabled: Boolean,
    regionFilterActive: Boolean,
    onTcpingTest: () -> Unit,
    onUrlTest: () -> Unit,
    onRegionFilter: () -> Unit,
    onRefresh: () -> Unit
) {
    IconButton(
        onClick = onTcpingTest,
        enabled = enabled
    ) {
        Icon(
            imageVector = Icons.Default.NetworkCheck,
            contentDescription = "TCPing",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    IconButton(
        onClick = onUrlTest,
        enabled = enabled
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = "URL Test",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    IconButton(
        onClick = onRegionFilter,
        enabled = regionFilterEnabled
    ) {
        Icon(
            imageVector = Icons.Default.Flag,
            contentDescription = if (regionFilterActive) "地区过滤已启用" else "地区过滤",
            tint = if (regionFilterActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }

    IconButton(
        onClick = onRefresh,
        enabled = enabled
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "刷新",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NodeRegionFilterDialog(
    availableRegionCodes: List<String>,
    selectedRegionCodes: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val availableCodeSet = remember(availableRegionCodes) { availableRegionCodes.toSet() }
    var pendingCodes by remember(availableRegionCodes, selectedRegionCodes) {
        mutableStateOf(selectedRegionCodes.intersect(availableCodeSet))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("地区过滤") },
        text = {
            Column {
                Text(
                    text = "白名单过滤：仅显示勾选地区的节点；全部不勾选时显示所有节点。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (availableRegionCodes.isEmpty()) {
                    Text(
                        text = "当前所有订阅组中暂无可识别的地区",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(
                            items = availableRegionCodes,
                            key = { it }
                        ) { code ->
                            val checked = code in pendingCodes
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        pendingCodes = if (checked) {
                                            pendingCodes - code
                                        } else {
                                            pendingCodes + code
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null
                                )
                                Text(
                                    text = CountryCodeResolver.flagEmoji(code) ?: "🌐",
                                    fontSize = 22.sp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = CountryCodeResolver.displayName(code) ?: code,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = code,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { pendingCodes = emptySet() },
                    enabled = pendingCodes.isNotEmpty()
                ) {
                    Text("清空")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingCodes) }) {
                Text("确定")
            }
        }
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun NodeListContent(
    nodes: List<Node>,
    selectedNodeId: String?,
    category: NodeListCategory,
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    favoriteSourceNodeIds: Set<String>,
    isTesting: Boolean,
    isRefreshing: Boolean,
    isRefreshingAllSubscriptions: Boolean,
    hideFavoriteNodeGroup: Boolean,
    nodeDisplaySettings: NodeDisplaySettings,
    protocolCoreSettings: ProtocolCoreSettings,
    subscriptionSourceUpdatedAt: Map<String, Long>,
    onRefresh: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onNodeSelected: (Node) -> Unit,
    onCategoryChange: (NodeListCategory) -> Unit,
    onSubscriptionSourceChange: (String) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit,
    interactionEnabled: Boolean,
    bottomContentPadding: Dp,
) {
    var frozenNodeOrderIds by remember { mutableStateOf<List<String>?>(null) }
    var wasTesting by remember { mutableStateOf(isTesting) }
    var pendingScrollToTop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val nodeGroupSwipeThresholdPx = with(density) { 72.dp.toPx() }
    var nodeGroupSwipeOffsetPx by remember { mutableFloatStateOf(0f) }
    var isNodeGroupSwiping by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pullRefreshRequested by remember { mutableStateOf(false) }
    LaunchedEffect(subscriptionSourceUpdatedAt[selectedSubscriptionSourceId]) {
        while (true) {
            delay(60_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullRefreshRequested = false
    }
    // 收藏节点和所有订阅组共用同一套页序，保证任意相邻页面都能左右滑动切换。
    val subscriptionPageCount = subscriptionGroups.size.coerceAtLeast(1)
    val nodeGroupPageCount =
        subscriptionPageCount + if (hideFavoriteNodeGroup) 0 else 1
    val currentNodeGroupPageIndex = when {
        category == NodeListCategory.FAVORITES && !hideFavoriteNodeGroup ->
            subscriptionPageCount
        subscriptionGroups.isEmpty() -> 0
        else -> subscriptionGroups.indexOfFirst {
            it.sourceId == selectedSubscriptionSourceId
        }.coerceAtLeast(0)
    }
    val lastUpdatedAt = selectedSubscriptionSourceId?.let(subscriptionSourceUpdatedAt::get)
    val canPullRefresh =
        interactionEnabled &&
            !isTesting &&
            category == NodeListCategory.PRIMARY &&
            !selectedSubscriptionSourceId.isNullOrBlank()
    val pullRefreshing = isRefreshing && pullRefreshRequested
    val pullRefreshState = rememberPullRefreshState(
        refreshing = pullRefreshing,
        onRefresh = {
            if (!isRefreshing) {
                pullRefreshRequested = true
                onRefresh()
            }
        }
    )
    val pullRefreshContentOffsetPx = with(density) {
        val offset = if (pullRefreshing) {
            56.dp.toPx()
        } else {
            80.dp.toPx() * pullRefreshState.progress.coerceIn(0f, 1f)
        }
        offset
    }
    val showPullRefreshStatus =
        canPullRefresh && (pullRefreshing || pullRefreshState.progress > 0f)
    val pullRefreshProgress = if (pullRefreshing) {
        1f
    } else {
        pullRefreshState.progress
    }
    val pullRefreshModifier = Modifier.pullRefresh(
        state = pullRefreshState,
        enabled = canPullRefresh
    )
    val canSwipeNodeGroups =
        interactionEnabled &&
            !isTesting &&
            nodeGroupPageCount > 1
    val nodeGroupSwipeVisualOffsetPx by animateFloatAsState(
        targetValue = if (isNodeGroupSwiping) nodeGroupSwipeOffsetPx else 0f,
        animationSpec = if (isNodeGroupSwiping) {
            snap()
        } else {
            tween(durationMillis = 180, easing = FastOutSlowInEasing)
        },
        label = "nodeGroupSwipeOffset"
    )
    val nodeGroupSwipeProgress =
        (abs(nodeGroupSwipeVisualOffsetPx) / nodeGroupSwipeThresholdPx)
            .coerceIn(0f, 1f)
    val nodeGroupSwipeModifier = Modifier
        .graphicsLayer {
            translationX = nodeGroupSwipeVisualOffsetPx
            alpha = 1f - nodeGroupSwipeProgress * 0.12f
        }
        .pointerInput(
            canSwipeNodeGroups,
            currentNodeGroupPageIndex,
            nodeGroupPageCount,
            subscriptionGroups,
            nodeGroupSwipeThresholdPx
        ) {
            if (!canSwipeNodeGroups) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = {
                    nodeGroupSwipeOffsetPx = 0f
                    isNodeGroupSwiping = true
                },
                onHorizontalDrag = { change, dragAmount ->
                    val requestedOffset = nodeGroupSwipeOffsetPx + dragAmount
                    val hasTarget = when {
                        requestedOffset < 0f ->
                            currentNodeGroupPageIndex < nodeGroupPageCount - 1
                        requestedOffset > 0f -> currentNodeGroupPageIndex > 0
                        else -> true
                    }
                    val adjustedDrag = if (hasTarget) dragAmount else dragAmount * 0.25f
                    nodeGroupSwipeOffsetPx =
                        (nodeGroupSwipeOffsetPx + adjustedDrag).coerceIn(
                            -nodeGroupSwipeThresholdPx * 1.2f,
                            nodeGroupSwipeThresholdPx * 1.2f
                        )
                    change.consume()
                },
                onDragEnd = {
                    val targetIndex = when {
                        nodeGroupSwipeOffsetPx <= -nodeGroupSwipeThresholdPx &&
                            currentNodeGroupPageIndex < nodeGroupPageCount - 1 ->
                            currentNodeGroupPageIndex + 1
                        nodeGroupSwipeOffsetPx >= nodeGroupSwipeThresholdPx &&
                            currentNodeGroupPageIndex > 0 -> currentNodeGroupPageIndex - 1
                        else -> currentNodeGroupPageIndex
                    }
                    isNodeGroupSwiping = false
                    nodeGroupSwipeOffsetPx = 0f
                    if (targetIndex != currentNodeGroupPageIndex) {
                        pendingScrollToTop = true
                        val targetSubscription = subscriptionGroups.getOrNull(targetIndex)
                        when {
                            targetSubscription != null ->
                                onSubscriptionSourceChange(targetSubscription.sourceId)
                            targetIndex == subscriptionPageCount ->
                                onCategoryChange(NodeListCategory.FAVORITES)
                            else -> onCategoryChange(NodeListCategory.PRIMARY)
                        }
                    }
                },
                onDragCancel = {
                    isNodeGroupSwiping = false
                    nodeGroupSwipeOffsetPx = 0f
                }
            )
        }

    LaunchedEffect(isTesting) {
        if (isTesting) {
            if (frozenNodeOrderIds == null) {
                frozenNodeOrderIds = nodes.map { it.id }
            }
        } else {
            if (wasTesting) {
                pendingScrollToTop = true
            }
            frozenNodeOrderIds = null
        }
        wasTesting = isTesting
    }

    val displayNodes = remember(nodes, isTesting, frozenNodeOrderIds) {
        if (!isTesting || frozenNodeOrderIds == null) {
            nodes
        } else {
            val nodeMap = nodes.associateBy { it.id }
            val frozenNodes = frozenNodeOrderIds.orEmpty().mapNotNull(nodeMap::get)
            val newNodes = nodes.filterNot { it.id in frozenNodeOrderIds.orEmpty() }
            frozenNodes + newNodes
        }
    }

    LaunchedEffect(pendingScrollToTop, isTesting, frozenNodeOrderIds, displayNodes.size) {
        if (pendingScrollToTop && !isTesting && frozenNodeOrderIds == null && displayNodes.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(0)
            pendingScrollToTop = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isTesting || isRefreshingAllSubscriptions) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        NodeListCategorySwitch(
            category = category,
            subscriptionGroups = subscriptionGroups,
            selectedSubscriptionSourceId = selectedSubscriptionSourceId,
            hideFavoriteNodeGroup = hideFavoriteNodeGroup,
            enabled = interactionEnabled && !isTesting,
            onCategoryChange = onCategoryChange,
            onSubscriptionSourceChange = onSubscriptionSourceChange
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (category == NodeListCategory.FAVORITES) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NodeListImportAction(
                    icon = Icons.Default.ContentPaste,
                    text = "剪贴板导入",
                    enabled = interactionEnabled && !isTesting,
                    onClick = onImportFromClipboard,
                    modifier = Modifier.weight(1f)
                )
                NodeListImportAction(
                    icon = Icons.Default.QrCodeScanner,
                    text = "二维码导入",
                    enabled = interactionEnabled && !isTesting,
                    onClick = onScanQrCode,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "自动识别节点 URI、订阅内容和订阅链接",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            displayNodes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(pullRefreshModifier)
                        .then(nodeGroupSwipeModifier)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isTesting) "正在获取节点..." else "暂无可用节点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                    PullRefreshStatus(
                        visible = showPullRefreshStatus,
                        refreshing = pullRefreshing,
                        progress = pullRefreshProgress,
                        contentOffsetPx = pullRefreshContentOffsetPx,
                        lastUpdatedAt = lastUpdatedAt,
                        nowMillis = nowMillis,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(pullRefreshModifier)
                        .then(nodeGroupSwipeModifier)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = pullRefreshContentOffsetPx
                            },
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            bottom = bottomContentPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = displayNodes,
                            key = { it.id }
                        ) { node ->
                            val isFavorite =
                                node.source == NodeSource.FAVORITE ||
                                    favoriteSourceNodeIds.contains(node.id)
                            NodeListItem(
                                node = node,
                                isSelected = node.id == selectedNodeId,
                                isFavorite = isFavorite,
                                enabled = interactionEnabled,
                                displaySettings = nodeDisplaySettings,
                                coreSettings = protocolCoreSettings,
                                onClick = { onNodeSelected(node) },
                                onToggleFavorite = { onToggleFavorite(node, isFavorite) }
                            )
                        }
                    }
                    PullRefreshStatus(
                        visible = showPullRefreshStatus,
                        refreshing = pullRefreshing,
                        progress = pullRefreshProgress,
                        contentOffsetPx = pullRefreshContentOffsetPx,
                        lastUpdatedAt = lastUpdatedAt,
                        nowMillis = nowMillis,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }

        if (!interactionEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            )
        }
    }
}

@Composable
private fun NodeListImportAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun NodeListCategorySwitch(
    category: NodeListCategory,
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    hideFavoriteNodeGroup: Boolean,
    enabled: Boolean,
    onCategoryChange: (NodeListCategory) -> Unit,
    onSubscriptionSourceChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        val subscriptionSegmentCount = if (subscriptionGroups.isEmpty()) 1 else subscriptionGroups.size
        val segmentCount = subscriptionSegmentCount + if (hideFavoriteNodeGroup) 0 else 1
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val segmentGap = 4.dp
            val availableWidth = maxWidth - 8.dp - segmentGap * (segmentCount - 1)
            val segmentWidth = if (segmentCount <= 4) {
                (availableWidth / segmentCount).coerceAtLeast(88.dp)
            } else {
                112.dp
            }
            val categoryScrollState = rememberScrollState()
            val density = LocalDensity.current
            val selectedSegmentIndex = when {
                category == NodeListCategory.FAVORITES && !hideFavoriteNodeGroup -> segmentCount - 1
                subscriptionGroups.isEmpty() -> 0
                else -> subscriptionGroups.indexOfFirst {
                    it.sourceId == selectedSubscriptionSourceId
                }.coerceAtLeast(0)
            }

            LaunchedEffect(selectedSegmentIndex, segmentWidth, maxWidth) {
                withFrameNanos { }
                val segmentStepPx = with(density) { (segmentWidth + segmentGap).roundToPx() }
                val centerOffsetPx = with(density) {
                    ((maxWidth - segmentWidth) / 2).roundToPx()
                }
                val targetOffset =
                    (selectedSegmentIndex * segmentStepPx - centerOffsetPx)
                        .coerceIn(0, categoryScrollState.maxValue)
                categoryScrollState.animateScrollTo(targetOffset)
            }

            Row(
                modifier = Modifier
                    .horizontalScroll(categoryScrollState)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(segmentGap)
            ) {
                if (subscriptionGroups.isEmpty()) {
                    NodeListCategorySegment(
                        text = "订阅节点",
                        selected = category == NodeListCategory.PRIMARY,
                        enabled = enabled,
                        modifier = Modifier.width(segmentWidth),
                        onClick = { onCategoryChange(NodeListCategory.PRIMARY) }
                    )
                } else {
                    subscriptionGroups.forEach { group ->
                        NodeListCategorySegment(
                            text = group.name,
                            selected = category == NodeListCategory.PRIMARY &&
                                selectedSubscriptionSourceId == group.sourceId,
                            enabled = enabled,
                            modifier = Modifier.width(segmentWidth),
                            onClick = { onSubscriptionSourceChange(group.sourceId) }
                        )
                    }
                }
                if (!hideFavoriteNodeGroup) {
                    NodeListCategorySegment(
                        text = "\u6536\u85cf\u8282\u70b9",
                        selected = category == NodeListCategory.FAVORITES,
                        enabled = enabled,
                        modifier = Modifier.width(segmentWidth),
                        onClick = { onCategoryChange(NodeListCategory.FAVORITES) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeListCategorySegment(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 9.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun PullRefreshStatus(
    visible: Boolean,
    refreshing: Boolean,
    progress: Float,
    contentOffsetPx: Float,
    lastUpdatedAt: Long?,
    nowMillis: Long,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val density = LocalDensity.current
    val statusHeightPx = with(density) { 50.dp.toPx() }
    val boundedProgress = progress.coerceIn(0f, 1f)
    val readyToRefresh = !refreshing && progress >= 1f
    val statusText = when {
        refreshing -> "\u6b63\u5728\u5237\u65b0"
        readyToRefresh -> "\u677e\u5f00\u5237\u65b0"
        else -> "\u7ee7\u7eed\u4e0b\u62c9"
    }
    val activeColor = MaterialTheme.colorScheme.primary
    val indicatorColor = if (readyToRefresh || refreshing) {
        activeColor
    } else {
        activeColor.copy(alpha = 0.6f)
    }
    Column(
        modifier = modifier.graphicsLayer {
            translationY = (contentOffsetPx - statusHeightPx).coerceAtLeast(0f)
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$statusText \u00b7 ${formatSubscriptionUpdatedText(lastUpdatedAt, nowMillis)}",
            color = if (readyToRefresh || refreshing) {
                activeColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (readyToRefresh) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1
        )
        Surface(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp),
            color = indicatorColor,
            shape = RoundedCornerShape(1.dp)
        ) {}
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        rotationZ = boundedProgress * 180f
                        val readyScale = if (readyToRefresh) 1.12f else 1f
                        scaleX = readyScale
                        scaleY = readyScale
                    },
                tint = indicatorColor
            )
        }
    }
}

private fun formatSubscriptionUpdatedText(updatedAt: Long?, nowMillis: Long): String {
    if (updatedAt == null || updatedAt <= 0L) {
        return "\u6682\u65e0\u8bb0\u5f55"
    }
    val minutes = (nowMillis - updatedAt).coerceAtLeast(0L) / 60_000L
    return "${minutes}\u5206\u949f\u524d"
}
