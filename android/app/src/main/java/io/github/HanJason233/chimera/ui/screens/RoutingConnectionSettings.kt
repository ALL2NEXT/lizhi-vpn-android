package io.github.HanJason233.chimera.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.ui.components.SettingsListChoiceRow
import io.github.HanJason233.chimera.ui.components.SettingsListRow
import io.github.HanJason233.chimera.ui.components.SettingsListSectionHeader
import io.github.HanJason233.chimera.ui.components.SettingsListSwitchRow
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun DnsSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val dnsSettings by viewModel.dnsSettings.collectAsStateWithLifecycle()

    AppScreenScaffold(
        title = "DNS 设置",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsListSectionHeader("DNS 服务器")
            DnsEndpointListRow(
                icon = Icons.Outlined.Dns,
                title = "远程 DNS 地址",
                value = dnsSettings.remoteDns,
                onSave = viewModel::setRemoteDns
            )
            DnsEndpointListRow(
                icon = Icons.Outlined.Dns,
                title = "直连 DNS 地址",
                value = dnsSettings.directDns,
                onSave = viewModel::setDirectDns
            )

            SettingsListSectionHeader("解析策略")
            SettingsListChoiceRow(
                icon = Icons.Outlined.SwapHoriz,
                title = "远程域名策略",
                subtitle = dnsSettings.remoteDomainStrategy.displayName,
                options = DnsDomainStrategy.entries,
                selected = dnsSettings.remoteDomainStrategy,
                optionLabel = { "${it.displayName}（${it.configValue}）" },
                onSelected = viewModel::setRemoteDnsStrategy
            )
            SettingsListChoiceRow(
                icon = Icons.Outlined.SwapHoriz,
                title = "直连域名策略",
                subtitle = dnsSettings.directDomainStrategy.displayName,
                options = DnsDomainStrategy.entries,
                selected = dnsSettings.directDomainStrategy,
                optionLabel = { "${it.displayName}（${it.configValue}）" },
                onSelected = viewModel::setDirectDnsStrategy
            )
            SettingsListChoiceRow(
                icon = Icons.Outlined.SwapHoriz,
                title = "服务器地址域名策略",
                subtitle = dnsSettings.serverDomainStrategy.displayName,
                options = DnsDomainStrategy.entries,
                selected = dnsSettings.serverDomainStrategy,
                optionLabel = { "${it.displayName}（${it.configValue}）" },
                onSelected = viewModel::setServerDnsStrategy
            )

            SettingsListSectionHeader("DNS 路由")
            SettingsListSwitchRow(
                icon = Icons.Outlined.Route,
                title = "启用 DNS 路由",
                subtitle = "使用直连 DNS 解析绕过路由中的 domains。注意潜在的 DNS 泄漏。",
                checked = dnsSettings.dnsRoutingEnabled,
                onCheckedChange = viewModel::setDnsRoutingEnabled
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Memory,
                title = "启用 FakeDNS",
                subtitle = "代理停止后，其他应用可能需要重新启动才能恢复网络连接。",
                checked = dnsSettings.fakeDnsEnabled,
                onCheckedChange = viewModel::setFakeDnsEnabled
            )
            Text(
                text = "DNS 请求由代理服务接管并处理。IPv6 路由模式会影响未单独指定策略时的 DNS 地址选择。",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RoutingConnectionSettings(viewModel: MainViewModel) {
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        RoutingSettingsSection(title = "路由设置") {
            Text(
                text = "IPv6 路由",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            MultiPositionSwitch(
                options = listOf("优先", "启用", "禁用"),
                selectedIndex = when (ipv6RoutingMode) {
                    IPv6RoutingMode.PREFER -> 0
                    IPv6RoutingMode.ENABLED -> 1
                    IPv6RoutingMode.DISABLED -> 2
                },
                onOptionSelected = { index ->
                    viewModel.setIPv6RoutingMode(
                        when (index) {
                            0 -> IPv6RoutingMode.PREFER
                            1 -> IPv6RoutingMode.ENABLED
                            else -> IPv6RoutingMode.DISABLED
                        }
                    )
                }
            )
        }

        RoutingSettingsSection(title = "嗅探") {
            RoutingSettingsRuleText("识别 HTTP、TLS、QUIC 等流量的目标域名信息，嗅探结果用于恢复目标信息并参与路由规则匹配。")
        }
    }
}

@Composable
private fun RoutingSettingsSection(
    title: String,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (collapsible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "折叠$title" else "展开$title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) content()
        } else {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun MultiPositionSwitch(
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
            .clip(RoundedCornerShape(24.dp))
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
                .clip(RoundedCornerShape(20.dp))
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
private fun RoutingSettingsRuleText(text: String) {
    Text(
        text = "• $text",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun DnsEndpointListRow(
    icon: ImageVector,
    title: String,
    value: String,
    onSave: (String) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }

    SettingsListRow(
        icon = icon,
        title = title,
        subtitle = value,
        onClick = { editing = true }
    )

    if (editing) {
        DnsEndpointEditDialog(
            title = title,
            value = value,
            onDismiss = { editing = false },
            onSave = onSave
        )
    }
}

@Composable
private fun DnsEndpointEditDialog(
    title: String,
    value: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedValue = DnsSettings.normalizeServerList(input)

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "支持 UDP、TCP、TLS 或 HTTPS 地址；多个 DNS 地址可分行填写。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DNS 地址") },
                    supportingText = {
                        Text(
                            if (normalizedValue == null) {
                                "请填写有效的 DNS 地址和端口。"
                            } else {
                                "每行一个地址，# 开头的行为注释。"
                            }
                        )
                    },
                    isError = normalizedValue == null,
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalizedValue != null,
                onClick = {
                    normalizedValue?.let(onSave)
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
