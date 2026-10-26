package io.github.HanJason233.chimera.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun DnsSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val dnsSettings by viewModel.dnsSettings.collectAsStateWithLifecycle()

    AppScreenScaffold(title = "DNS 设置", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RoutingSettingsSection(title = "远程 DNS") {
                DnsEndpointField(
                    label = "远程 DNS 地址",
                    value = dnsSettings.remoteDns,
                    onSave = viewModel::setRemoteDns
                )
                DnsStrategySelector(
                    label = "远程域名策略",
                    selected = dnsSettings.remoteDomainStrategy,
                    onSelected = viewModel::setRemoteDnsStrategy
                )
            }
            RoutingSettingsSection(title = "直连 DNS") {
                DnsEndpointField(
                    label = "直连 DNS 地址",
                    value = dnsSettings.directDns,
                    onSave = viewModel::setDirectDns
                )
                DnsStrategySelector(
                    label = "直连域名策略",
                    selected = dnsSettings.directDomainStrategy,
                    onSelected = viewModel::setDirectDnsStrategy
                )
            }
            RoutingSettingsSection(title = "DNS 路由") {
                DnsStrategySelector(
                    label = "服务器地址域名策略",
                    selected = dnsSettings.serverDomainStrategy,
                    onSelected = viewModel::setServerDnsStrategy
                )
                DnsSwitchRow(
                    title = "启用 DNS 路由",
                    description = "使用直连 DNS 解析绕过路由中的 domains。注意潜在的 DNS 泄漏",
                    checked = dnsSettings.dnsRoutingEnabled,
                    onCheckedChange = viewModel::setDnsRoutingEnabled
                )
                DnsSwitchRow(
                    title = "启用 FakeDNS",
                    description = "可能导致其他应用程序在代理停止后需要重新启动以重新连接到网络",
                    checked = dnsSettings.fakeDnsEnabled,
                    onCheckedChange = viewModel::setFakeDnsEnabled
                )
                RoutingSettingsRuleText("DNS 请求由代理服务接管并处理。")
                RoutingSettingsRuleText("IPv6 路由模式会影响未单独指定策略时的 DNS 地址选择。")
            }
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
private fun DnsEndpointField(
    label: String,
    value: String,
    onSave: (String) -> Unit
) {
    var input by rememberSaveable(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text("支持 UDP、TCP、TLS 或 HTTPS 地址") },
        singleLine = false,
        minLines = 1,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
    )
    if (input != value) {
        androidx.compose.material3.TextButton(
            onClick = { onSave(input) },
            enabled = DnsSettings.normalizeServerList(input) != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("应用 DNS 地址")
        }
    }
}

@Composable
private fun DnsStrategySelector(
    label: String,
    selected: DnsDomainStrategy,
    onSelected: (DnsDomainStrategy) -> Unit
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selected.configValue,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DnsDomainStrategy.entries.forEach { strategy ->
                    DropdownMenuItem(
                        text = { Text(strategy.configValue) },
                        onClick = {
                            expanded = false
                            onSelected(strategy)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
