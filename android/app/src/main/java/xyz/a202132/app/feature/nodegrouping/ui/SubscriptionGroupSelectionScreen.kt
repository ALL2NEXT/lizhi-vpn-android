package xyz.a202132.app.feature.nodegrouping.ui

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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
internal fun SubscriptionGroupSelectionDestination(
    viewModel: MainViewModel,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onSwitchToCountries: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 108.dp
) {
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val selectedSubscriptionSourceId by
        viewModel.selectedSubscriptionSourceId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val nodeCounts = allSubscriptionNodes
        .asSequence()
        .mapNotNull(Node::subscriptionSourceId)
        .groupingBy { it }
        .eachCount()

    SubscriptionGroupSelectionScreen(
        subscriptionGroups = subscriptionGroups,
        selectedSubscriptionSourceId = selectedSubscriptionSourceId,
        nodeCounts = nodeCounts,
        isLoading = isLoading,
        onSubscriptionSelected = onSubscriptionSelected,
        onSwitchToCountries = onSwitchToCountries,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding
    )
}

@Composable
private fun SubscriptionGroupSelectionScreen(
    subscriptionGroups: List<SubscriptionGroup>,
    selectedSubscriptionSourceId: String?,
    nodeCounts: Map<String, Int>,
    isLoading: Boolean,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onSwitchToCountries: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp
) {
    AppScreenScaffold(
        title = "订阅分组",
        subtitle = "选择订阅线路查看线路信息",
        onBack = onBack,
        showBackNavigation = false,
        actions = {
            IconButton(onClick = onSwitchToCountries) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = "切换为按地区分组"
                )
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
            subscriptionGroups.isNotEmpty() -> LazyColumn(
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
                        selected = group.sourceId == selectedSubscriptionSourceId,
                        onClick = { onSubscriptionSelected(group) }
                    )
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
private fun SubscriptionGroupCard(
    group: SubscriptionGroup,
    nodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit
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
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "查看线路信息",
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
