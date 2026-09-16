package xyz.a202132.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.DnsDomainStrategy
import xyz.a202132.app.data.model.DnsSettings
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

/** 展示路由相关设置与当前路由决策。 */
@Composable
fun CoreRoutingScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val proxyMode by viewModel.proxyMode.collectAsStateWithLifecycle()
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsStateWithLifecycle()
    val dnsSettings by viewModel.dnsSettings.collectAsStateWithLifecycle()

    AppScreenScaffold(title = "路由", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RoutingSection(title = "路由设置") {
                Text(
                    text = "IPv6 路由",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                MultiPositionSwitch(
                    options = listOf("优先", "启用", "禁用"),
                    selectedIndex = when (ipv6RoutingMode) {
                        IPv6RoutingMode.ONLY -> 1
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

            RoutingSection(
                title = "DNS 设置",
                collapsible = true,
                initiallyExpanded = false
            ) {
                DnsEndpointField(
                    label = "远程 DNS",
                    value = dnsSettings.remoteDns,
                    onSave = viewModel::setRemoteDns
                )
                DnsStrategySelector(
                    label = "域名策略（远程）",
                    selected = dnsSettings.remoteDomainStrategy,
                    onSelected = viewModel::setRemoteDnsStrategy
                )
                DnsEndpointField(
                    label = "直连 DNS",
                    value = dnsSettings.directDns,
                    onSave = viewModel::setDirectDns
                )
                DnsStrategySelector(
                    label = "域名策略（直连）",
                    selected = dnsSettings.directDomainStrategy,
                    onSelected = viewModel::setDirectDnsStrategy
                )
                DnsStrategySelector(
                    label = "域名策略（服务器地址）",
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
                RoutingRuleText("DNS 请求由代理服务接管并处理。")
                RoutingRuleText("IPv6 路由模式会影响未单独指定策略时的 DNS 地址选择。")
            }

            RoutingSection(title = "嗅探") {
                RoutingRuleText("识别 HTTP、TLS、QUIC 等流量的目标域名信息，嗅探结果用于恢复目标信息并参与路由规则匹配。")
            }

            RoutingSection(title = "分流模式") {
                RoutingValueRow(
                    "当前模式",
                    if (proxyMode == ProxyMode.GLOBAL) "全局" else "智能"
                )
                Text(
                    text = "分流模式决定流量使用哪个出站；模式切换入口仍保留在主页。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RoutingSection(title = "路由规则") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoutingRuleText("DNS 请求交由代理服务处理。")
                        RoutingRuleText("内网地址、回环地址和当前节点服务器地址直连，避免路由循环。")
                        RoutingRuleText(
                            if (proxyMode == ProxyMode.SMART) {
                                "智能模式：国内域名和国内 IP 直连，其余流量走代理出站。"
                            } else {
                                "全局模式：除必要的内网和节点例外外，流量走代理出站。"
                            }
                        )
                        RoutingRuleText("未命中前置规则的流量使用代理出站。")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutingSection(
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
private fun RoutingValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RoutingRuleText(text: String) {
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
        onValueChange = {
            input = it
        },
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
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
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
