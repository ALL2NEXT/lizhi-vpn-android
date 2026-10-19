package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SplitRoutingSettingsScreen(
    onBack: () -> Unit,
    onOpenPerAppInbound: (ProxyMode) -> Unit,
    onOpenRoutingRules: (ProxyMode) -> Unit
) {
    val modes = listOf(ProxyMode.GLOBAL, ProxyMode.SMART)
    val pagerState = rememberPagerState(pageCount = { modes.size })
    val coroutineScope = rememberCoroutineScope()
    var showPerAppInformation by remember { mutableStateOf(false) }
    var showRoutingInformation by remember { mutableStateOf(false) }

    AppScreenScaffold(
        title = "分流设置",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                modes.forEachIndexed { index, mode ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = {
                            Text(
                                text = mode.title,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                val mode = modes[page]
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "以下设置仅作用于${mode.title}。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                    SplitRoutingEntry(
                        title = "VPN 作用范围",
                        icon = Icons.Outlined.Android,
                        infoContentDescription = stringResource(R.string.per_app_inbound_information),
                        onClick = { onOpenPerAppInbound(mode) },
                        onInfoClick = { showPerAppInformation = true }
                    )
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SplitRoutingEntry(
                        title = "路由规则",
                        icon = Icons.Outlined.Route,
                        infoContentDescription = stringResource(R.string.routing_rules_information),
                        onClick = { onOpenRoutingRules(mode) },
                        onInfoClick = { showRoutingInformation = true }
                    )
                }
            }
        }
    }

    if (showPerAppInformation) {
        AppAlertDialog(
            onDismissRequest = { showPerAppInformation = false },
            title = { Text(stringResource(R.string.per_app_inbound_information)) },
            text = { Text(stringResource(R.string.per_app_inbound_summary)) },
            confirmButton = {
                TextButton(onClick = { showPerAppInformation = false }) {
                    Text(stringResource(R.string.per_app_inbound_ok))
                }
            }
        )
    }

    if (showRoutingInformation) {
        AppAlertDialog(
            onDismissRequest = { showRoutingInformation = false },
            title = { Text(stringResource(R.string.routing_rules_information)) },
            text = { Text(stringResource(R.string.routing_rules_summary)) },
            confirmButton = {
                TextButton(onClick = { showRoutingInformation = false }) {
                    Text(stringResource(R.string.routing_rules_ok))
                }
            }
        )
    }
}

private val ProxyMode.title: String
    get() = when (this) {
        ProxyMode.GLOBAL -> "全局模式设定"
        ProxyMode.SMART -> "智能模式设定"
    }

@Composable
private fun SplitRoutingEntry(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    infoContentDescription: String,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onInfoClick) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = infoContentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
