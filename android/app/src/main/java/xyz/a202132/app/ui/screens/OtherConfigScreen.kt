package xyz.a202132.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.BandwidthTestTargets
import xyz.a202132.app.data.model.HomeNavigationMode
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.TunStack
import xyz.a202132.app.data.model.UrlTestTargets
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val urlTestUrl by viewModel.urlTestUrl.collectAsStateWithLifecycle()
    val speedTestDownloadUrl by viewModel.speedTestDownloadUrl.collectAsStateWithLifecycle()
    val autoDeleteTimeoutNodes by viewModel.autoDeleteTimeoutNodes.collectAsStateWithLifecycle()
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsStateWithLifecycle()
    val tunStack by viewModel.tunStack.collectAsStateWithLifecycle()
    val hideFavoriteNodeGroup by viewModel.hideFavoriteNodeGroup.collectAsStateWithLifecycle()
    val homeNavigationPreference by
        viewModel.homeNavigationPreference.collectAsStateWithLifecycle()

    var urlTestUrlInput by remember(urlTestUrl) { mutableStateOf(urlTestUrl) }
    var showUrlTestPresets by remember { mutableStateOf(false) }
    var speedTestDownloadUrlInput by remember(speedTestDownloadUrl) { mutableStateOf(speedTestDownloadUrl) }
    var showBandwidthPresets by remember { mutableStateOf(false) }

    LaunchedEffect(speedTestDownloadUrl) {
        speedTestDownloadUrlInput = speedTestDownloadUrl
    }

    AppScreenScaffold(
        title = "基础设定",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OtherConfigSection(
                title = "主页导航方式"
            ) {
                MultiPositionSwitch(
                    options = listOf("滑动式", "底部导航式"),
                    selectedIndex = if (
                        homeNavigationPreference.mode ==
                        HomeNavigationMode.BOTTOM_NAVIGATION
                    ) 1 else 0,
                    onOptionSelected = { index ->
                        viewModel.setHomeNavigationMode(
                            if (index == 0) {
                                HomeNavigationMode.SWIPE
                            } else {
                                HomeNavigationMode.BOTTOM_NAVIGATION
                            }
                        )
                    }
                )
                Text(
                    text = if (
                        homeNavigationPreference.mode ==
                        HomeNavigationMode.BOTTOM_NAVIGATION
                    ) {
                        "主页与订阅使用底部悬浮胶囊导航切换。"
                    } else {
                        "在主页向上滑动打开节点列表。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OtherConfigSection(
                title = "网络配置"
            ) {
                Text(
                    text = "IPV6路由",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                MultiPositionSwitch(
                    options = listOf("仅", "优先", "启用", "禁用"),
                    selectedIndex = when (ipv6RoutingMode) {
                        IPv6RoutingMode.ONLY -> 0
                        IPv6RoutingMode.PREFER -> 1
                        IPv6RoutingMode.ENABLED -> 2
                        IPv6RoutingMode.DISABLED -> 3
                    },
                    onOptionSelected = { index ->
                        viewModel.setIPv6RoutingMode(
                            when (index) {
                                0 -> IPv6RoutingMode.ONLY
                                1 -> IPv6RoutingMode.PREFER
                                2 -> IPv6RoutingMode.ENABLED
                                else -> IPv6RoutingMode.DISABLED
                            }
                        )
                    }
                )
            }

            OtherConfigSection(
                title = "TUN实现"
            ) {
                val tunStacks = TunStack.values()
                MultiPositionSwitch(
                    options = tunStacks.map(TunStack::configValue),
                    selectedIndex = tunStacks.indexOf(tunStack),
                    onOptionSelected = { index -> viewModel.setTunStack(tunStacks[index]) }
                )
            }

            OtherConfigSection(
                title = "自动删除超时节点"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = autoDeleteTimeoutNodes,
                        onCheckedChange = { viewModel.setAutoDeleteTimeoutNodes(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "隐藏节点列表中的“收藏节点”订阅组"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = hideFavoriteNodeGroup,
                        onCheckedChange = { viewModel.setHideFavoriteNodeGroup(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "204 测速地址"
            ) {
                val normalizedUrl = UrlTestTargets.normalize(urlTestUrlInput)
                OutlinedTextField(
                    value = urlTestUrlInput,
                    onValueChange = { urlTestUrlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义 URL") },
                    singleLine = true,
                    isError = normalizedUrl == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                if (normalizedUrl == null) {
                    Text(
                        text = "请输入有效的 HTTP 或 HTTPS 地址",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        TextButton(onClick = { showUrlTestPresets = true }) {
                            Text("选择预设")
                        }
                        DropdownMenu(
                            expanded = showUrlTestPresets,
                            onDismissRequest = { showUrlTestPresets = false }
                        ) {
                            UrlTestTargets.PRESETS.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(preset.name)
                                            Text(
                                                text = preset.url,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        urlTestUrlInput = preset.url
                                        viewModel.setUrlTestUrl(preset.url)
                                        showUrlTestPresets = false
                                    }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { normalizedUrl?.let(viewModel::setUrlTestUrl) },
                        enabled = normalizedUrl != null && normalizedUrl != urlTestUrl
                    ) {
                        Text("保存")
                    }
                }
                Text(
                    text = "当前使用：$urlTestUrl",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val normalizedBandwidthUrl = BandwidthTestTargets.normalize(speedTestDownloadUrlInput)
                Text(
                    text = "带宽测速地址",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = speedTestDownloadUrlInput,
                    onValueChange = { speedTestDownloadUrlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义 URL") },
                    singleLine = true,
                    isError = normalizedBandwidthUrl == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                if (normalizedBandwidthUrl == null) {
                    Text(
                        text = "请输入有效的 HTTP 或 HTTPS 地址",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        TextButton(onClick = { showBandwidthPresets = true }) {
                            Text("选择预设")
                        }
                        DropdownMenu(
                            expanded = showBandwidthPresets,
                            onDismissRequest = { showBandwidthPresets = false }
                        ) {
                            BandwidthTestTargets.PRESETS.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(preset.name)
                                            Text(
                                                text = preset.url,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        speedTestDownloadUrlInput = preset.url
                                        viewModel.setSpeedTestDownloadUrl(preset.url)
                                        showBandwidthPresets = false
                                    }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { normalizedBandwidthUrl?.let(viewModel::setSpeedTestDownloadUrl) },
                        enabled = normalizedBandwidthUrl != null && normalizedBandwidthUrl != speedTestDownloadUrl
                    ) {
                        Text("保存")
                    }
                }
                Text(
                    text = "当前使用：$speedTestDownloadUrl",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "内置 Cloudflare 5 MB 和 25 MB 两个预设，也可以手动填写完整 URL。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

@Composable
private fun OtherConfigSection(
    title: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        content()
    }
}

@Composable
internal fun MultiPositionSwitch(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    if (options.isEmpty()) return

    val safeSelectedIndex = selectedIndex.coerceIn(options.indices)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * safeSelectedIndex,
            label = "multi-position-switch"
        )

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onOptionSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (index == safeSelectedIndex) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun visibleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)
