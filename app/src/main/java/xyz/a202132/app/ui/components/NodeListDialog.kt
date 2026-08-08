package xyz.a202132.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import xyz.a202132.app.data.model.LatencyLevel
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.ui.theme.LatencyBad
import xyz.a202132.app.ui.theme.LatencyGood
import xyz.a202132.app.ui.theme.LatencyMedium
import xyz.a202132.app.ui.theme.Primary

@Composable
fun NodeListScreen(
    nodes: List<Node>,
    selectedNodeId: String?,
    category: NodeListCategory,
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    favoriteSourceNodeIds: Set<String>,
    skipFavoriteRemovalConfirmation: Boolean,
    isTesting: Boolean,
    testingLabel: String? = null,
    onNodeSelected: (Node) -> Unit,
    onCategoryChange: (NodeListCategory) -> Unit,
    onSubscriptionSourceChange: (String) -> Unit,
    onToggleFavorite: (Node) -> Unit,
    onSkipFavoriteRemovalConfirmationForSession: () -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isClosing by remember { mutableStateOf(false) }
    var favoriteRemovalCandidate by remember { mutableStateOf<Node?>(null) }
    var skipConfirmationForSessionChecked by remember { mutableStateOf(false) }

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

    AppScreenScaffold(
        title = "节点列表",
        subtitle = testingLabel,
        onBack = handleBack,
        backEnabled = !isClosing,
        actions = {
            NodeListTopActions(
                enabled = !isClosing,
                onImportFromClipboard = {
                    if (isClosing) return@NodeListTopActions
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
                },
                onScanQrCode = {
                    if (isClosing) return@NodeListTopActions
                    onScanQrCode()
                },
                onRefresh = onRefresh
            )
        }
    ) {
        NodeListContent(
            nodes = nodes,
            selectedNodeId = selectedNodeId,
            category = category,
            subscriptionGroups = subscriptionGroups,
            selectedSubscriptionSourceId = selectedSubscriptionSourceId,
            favoriteSourceNodeIds = favoriteSourceNodeIds,
            isTesting = isTesting,
            onNodeSelected = handleNodeSelected,
            onCategoryChange = onCategoryChange,
            onSubscriptionSourceChange = onSubscriptionSourceChange,
            onToggleFavorite = handleToggleFavorite,
            interactionEnabled = !isClosing,
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
}

@Composable
private fun NodeListTopActions(
    enabled: Boolean,
    onImportFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onRefresh: () -> Unit
) {
    var showImportMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { showImportMenu = true },
            enabled = enabled
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "\u5bfc\u5165\u8282\u70b9",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = showImportMenu,
            onDismissRequest = { showImportMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("\u4ece\u526a\u5207\u677f\u5bfc\u5165") },
                onClick = {
                    showImportMenu = false
                    onImportFromClipboard()
                }
            )
            DropdownMenuItem(
                text = { Text("\u626b\u63cf\u4e8c\u7ef4\u7801") },
                onClick = {
                    showImportMenu = false
                    onScanQrCode()
                }
            )
        }
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
private fun NodeListContent(
    nodes: List<Node>,
    selectedNodeId: String?,
    category: NodeListCategory,
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    favoriteSourceNodeIds: Set<String>,
    isTesting: Boolean,
    onNodeSelected: (Node) -> Unit,
    onCategoryChange: (NodeListCategory) -> Unit,
    onSubscriptionSourceChange: (String) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit,
    interactionEnabled: Boolean,
) {
    var frozenNodeOrderIds by remember { mutableStateOf<List<String>?>(null) }
    var wasTesting by remember { mutableStateOf(isTesting) }
    var pendingScrollToTop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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
        if (isTesting) {
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
            enabled = interactionEnabled && !isTesting,
            onCategoryChange = onCategoryChange,
            onSubscriptionSourceChange = onSubscriptionSourceChange
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            displayNodes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isTesting) "正在获取节点..." else "暂无可用节点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
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
                            onClick = { onNodeSelected(node) },
                            onToggleFavorite = { onToggleFavorite(node, isFavorite) }
                        )
                    }
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
private fun NodeListCategorySwitch(
    category: NodeListCategory,
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    enabled: Boolean,
    onCategoryChange: (NodeListCategory) -> Unit,
    onSubscriptionSourceChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        val segmentCount = (if (subscriptionGroups.isEmpty()) 1 else subscriptionGroups.size) + 1
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val segmentGap = 4.dp
            val availableWidth = maxWidth - 8.dp - segmentGap * (segmentCount - 1)
            val segmentWidth = if (segmentCount <= 4) {
                (availableWidth / segmentCount).coerceAtLeast(88.dp)
            } else {
                112.dp
            }

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
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
private fun NodeListItem(
    node: Node,
    isSelected: Boolean,
    isFavorite: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val backgroundColor =
        if (isSelected) Primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isSelected) Primary else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.getFlagEmoji(),
                        fontSize = 24.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )

                    Column {
                        Text(
                            text = node.getDisplayName(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (node.isAvailable) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                    }
                }

                IconButton(
                    onClick = onToggleFavorite,
                    enabled = enabled,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "\u6536\u85cf\u8282\u70b9",
                        tint = if (isFavorite) Primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NodeMetric(
                    label = "TCPing",
                    value = formatNodeLatency(node.tcpingLatency),
                    valueColor = nodeMetricColor(node.tcpingLatency),
                    modifier = Modifier.weight(1f)
                )
                NodeMetric(
                    label = "204 URLTest",
                    value = formatNodeLatency(node.urlTestLatency),
                    valueColor = nodeMetricColor(node.urlTestLatency),
                    modifier = Modifier.weight(1f)
                )
                NodeMetric(
                    label = "带宽",
                    value = formatBandwidth(node.downloadMbps),
                    valueColor = if (node.downloadMbps > 0f) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

}

@Composable
private fun NodeMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatNodeLatency(latency: Int): String = when {
    latency > 0 -> "${latency}ms"
    latency == -2 -> "失败"
    else -> "未测试"
}

private fun formatBandwidth(downloadMbps: Float): String {
    return if (downloadMbps > 0f) {
        String.format(Locale.US, "%.1f Mbps", downloadMbps)
    } else {
        "未测试"
    }
}

@Composable
private fun nodeMetricColor(latency: Int): Color {
    return when {
        latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        latency <= 200 -> LatencyGood
        latency <= 400 -> LatencyMedium
        else -> LatencyBad
    }
}

@Composable
fun LatencyBadge(node: Node, isTesting: Boolean = false) {
    val latencyColor = when (node.getLatencyLevel()) {
        LatencyLevel.GOOD -> LatencyGood
        LatencyLevel.MEDIUM -> LatencyMedium
        LatencyLevel.BAD -> LatencyBad
    }

    Surface(
        color = latencyColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = if (isTesting && node.latency == -1) "测试中" else node.getLatencyText(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = latencyColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
