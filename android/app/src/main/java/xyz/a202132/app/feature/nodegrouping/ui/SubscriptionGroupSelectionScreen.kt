package xyz.a202132.app.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeGroupKey
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
internal fun SubscriptionGroupSelectionDestination(
    viewModel: MainViewModel,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onFavoritesSelected: () -> Unit,
    onViewFavoritesDetails: () -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onSwitchToCountries: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 108.dp
) {
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val favoriteNodes by viewModel.favoriteNodes.collectAsStateWithLifecycle()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsStateWithLifecycle()
    val hideFavoriteNodeGroup by viewModel.hideFavoriteNodeGroup.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val nodeCounts = allSubscriptionNodes
        .asSequence()
        .mapNotNull(Node::subscriptionSourceId)
        .groupingBy { it }
        .eachCount()

    SubscriptionGroupSelectionScreen(
        subscriptionGroups = subscriptionGroups,
        selectedNodeGroupId = selectedNodeGroupId,
        nodeCounts = nodeCounts,
        favoriteNodeCount = favoriteNodes.size,
        hideFavoriteNodeGroup = hideFavoriteNodeGroup,
        isLoading = isLoading,
        onSubscriptionSelected = onSubscriptionSelected,
        onViewSubscriptionDetails = onViewSubscriptionDetails,
        onFavoritesSelected = onFavoritesSelected,
        onViewFavoritesDetails = onViewFavoritesDetails,
        onImportFromText = onImportFromText,
        onScanQrCode = onScanQrCode,
        onSwitchToCountries = onSwitchToCountries,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding
    )
}

@Composable
private fun SubscriptionGroupSelectionScreen(
    subscriptionGroups: List<SubscriptionGroup>,
    selectedNodeGroupId: String?,
    nodeCounts: Map<String, Int>,
    favoriteNodeCount: Int,
    hideFavoriteNodeGroup: Boolean,
    isLoading: Boolean,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onFavoritesSelected: () -> Unit,
    onViewFavoritesDetails: () -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onSwitchToCountries: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp
) {
    val favoriteSelected = selectedNodeGroupId == NodeGroupKey.Favorites.value
    val context = LocalContext.current
    var importMenuExpanded by remember { mutableStateOf(false) }

    val handleImportFromClipboard = {
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

    AppScreenScaffold(
        title = "按订阅仓库分组",
        subtitle = "选择订阅仓库用于主页筛选，点击右侧查看线路信息",
        onBack = onBack,
        showBackNavigation = false,
        actions = {
            IconButton(onClick = onSwitchToCountries) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = "切换为按地区分组"
                )
            }
            Box {
                IconButton(onClick = { importMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加订阅"
                    )
                }
                DropdownMenu(
                    expanded = importMenuExpanded,
                    onDismissRequest = { importMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("剪贴板导入") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            importMenuExpanded = false
                            handleImportFromClipboard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("二维码导入") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            importMenuExpanded = false
                            onScanQrCode()
                        }
                    )
                }
            }
        },
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        when {
            subscriptionGroups.isNotEmpty() || !hideFavoriteNodeGroup -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = subscriptionGroups,
                    key = SubscriptionGroup::sourceId
                ) { group ->
                    SubscriptionGroupCard(
                        group = group,
                        nodeCount = nodeCounts[group.sourceId] ?: 0,
                        // 收藏节点组和普通订阅组保持互斥选中状态。
                        selected = selectedNodeGroupId ==
                            NodeGroupKey.Subscription(group.sourceId).value,
                        onClick = { onSubscriptionSelected(group) },
                        onViewDetails = { onViewSubscriptionDetails(group) }
                    )
                }
                if (!hideFavoriteNodeGroup) {
                    item(key = "favorites") {
                        FavoriteGroupCard(
                            nodeCount = favoriteNodeCount,
                            selected = favoriteSelected,
                            onClick = onFavoritesSelected,
                            onViewDetails = onViewFavoritesDetails
                        )
                    }
                }
            }

            isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomContentPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无可用订阅线路",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FavoriteGroupCard(
    nodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = "收藏节点",
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = "$nodeCount 个节点",
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                maxLines = 1
            )
            IconButton(onClick = onViewDetails) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "查看收藏节点",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SubscriptionGroupCard(
    group: SubscriptionGroup,
    nodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Subscriptions,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = group.name,
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$nodeCount 个节点",
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                maxLines = 1
            )
            IconButton(onClick = onViewDetails) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "查看线路信息",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
