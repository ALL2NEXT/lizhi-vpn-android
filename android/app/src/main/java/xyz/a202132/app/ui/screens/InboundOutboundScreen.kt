package xyz.a202132.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import xyz.a202132.app.data.model.LanInboundSettings
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.viewmodel.MainViewModel

/** 只展示核心实际拥有的入站、路由、出站及核心运行信息。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InboundOutboundScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lanInbound by viewModel.lanInboundSettings.collectAsStateWithLifecycle()
    val currentNode by viewModel.currentNode.collectAsStateWithLifecycle()
    val proxyMode by viewModel.proxyMode.collectAsStateWithLifecycle()
    val protocolCoreSettings by viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val hysteriaBandwidthEnabled by viewModel.hysteriaBandwidthEnabled.collectAsStateWithLifecycle()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsStateWithLifecycle()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsStateWithLifecycle()
    val lanIpv4Addresses by produceState(initialValue = emptyList<String>(), key1 = context) {
        while (true) {
            value = NetworkUtils.lanIpv4Addresses(context)
            // 热点可能在进入面板后才开启，定时刷新以便及时生成导入 URI。
            delay(2_000)
        }
    }

    var portInput by remember(lanInbound.port) { mutableStateOf(lanInbound.port.toString()) }
    var usernameInput by remember(lanInbound.username) { mutableStateOf(lanInbound.username) }
    var passwordInput by remember(lanInbound.password) { mutableStateOf(lanInbound.password) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    AppScreenScaffold(title = "出入站面板", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            InboundOutboundSection(title = "核心入站") {
                InboundCard(
                    title = "本地 SOCKS5 入站（HEV 对接）",
                    description = "核心实际监听 127.0.0.1 端口，接收 HEV 数据面转换后的 SOCKS5 请求。",
                    status = if (vpnState == VpnState.CONNECTED) "运行中" else "VPN 启动后运行"
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "局域网入站",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (lanInbound.enabled) {
                                        if (vpnState == VpnState.CONNECTED) "正在监听" else "等待 VPN 启动"
                                    } else {
                                        "已关闭"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Switch(
                                checked = lanInbound.enabled,
                                onCheckedChange = viewModel::setLanInboundEnabled
                            )
                        }

                        Text(
                            text = "VPN 运行后监听 0.0.0.0:$portInput，局域网设备通过 SOCKS5 使用当前出站节点和分流规则。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("用户名") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("密码") },
                            singleLine = true
                        )

                        validationMessage?.let { message ->
                            Text(
                                text = message,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val port = portInput.toIntOrNull()
                                val validPort = port?.takeIf { it in 1..65_535 }
                                validationMessage = when {
                                    validPort == null -> "请输入 1-65535 之间的端口"
                                    usernameInput.isBlank() -> "请输入用户名"
                                    passwordInput.isBlank() -> "请输入密码"
                                    else -> null
                                }
                                if (validationMessage == null && validPort != null) {
                                    viewModel.setLanInboundDetails(
                                        port = validPort,
                                        username = usernameInput,
                                        password = passwordInput
                                    )
                                }
                            }
                        ) {
                            Text("保存并应用")
                        }

                        LanShareUriPanel(
                            settings = lanInbound,
                            addresses = lanIpv4Addresses,
                            onCopy = { uri ->
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("Firefly SOCKS5", uri)
                                )
                                Toast.makeText(context, "URI 已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            InboundOutboundSection(title = "核心运行") {
                val node = currentNode
                val currentCore = node?.let { protocolCoreSettings.coreFor(it.type) }

                OutboundValueRow("运行核心", currentCore?.displayName ?: "未选择节点")
                Text(
                    text = "运行核心是流量处理引擎，不属于入站或出站对象。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            InboundOutboundSection(title = "核心路由") {
                OutboundValueRow("分流模式", if (proxyMode == ProxyMode.GLOBAL) "全局" else "智能")
                Text(
                    text = "全局全部走代理节点；智能按域名和 IP 规则在直连与代理之间选择。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            InboundOutboundSection(title = "核心出站") {
                OutboundValueRow("当前节点", currentNode?.name ?: "未选择节点")

                Text(
                    text = "局域网入站不会再次经过本机 TUN，直接复用当前节点出站。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutboundHysteriaCard(
                    enabled = hysteriaBandwidthEnabled,
                    upMbps = hysteriaUpMbps,
                    downMbps = hysteriaDownMbps
                )
            }
        }
    }
}

/** Hy2 参数只作用于对应的核心出站，集中放在出站区域避免误认为入站参数。 */
@Composable
private fun OutboundHysteriaCard(
    enabled: Boolean,
    upMbps: Int,
    downMbps: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hysteria2 出站加速",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "仅对 Hysteria2 节点的核心出站生效",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (enabled) "已启用" else "未启用",
                    fontSize = 12.sp,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            OutboundValueRow("上行带宽", "$upMbps Mbps")
            OutboundValueRow("下行带宽", "$downMbps Mbps")
        }
    }
}

@Composable
private fun LanShareUriPanel(
    settings: LanInboundSettings,
    addresses: List<String>,
    onCopy: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "导入配置",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        when {
            !settings.enabled -> Text(
                text = "开启局域网入站后生成可导入 URI。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            settings.password.isBlank() -> Text(
                text = "请先保存局域网入站密码。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
            addresses.isEmpty() -> Text(
                text = "未检测到 Wi-Fi 或以太网 IPv4 地址，请连接局域网后重试。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> addresses.forEach { address ->
                val uri = buildSocksImportUri(address, settings)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SOCKS5 · $address:${settings.port}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = uri,
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            singleLine = false,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                        ) {
                            TextButton(onClick = { onCopy(uri) }) {
                                Text("复制 URI")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildSocksImportUri(address: String, settings: LanInboundSettings): String {
    // v2rayN 等客户端的 SOCKS 导入器要求明文 URL 结构，不能使用整段 Base64。
    val username = Uri.encode(settings.username)
    val password = Uri.encode(settings.password)
    return "socks://${username}:${password}@${address}:${settings.port}#Firefly-LAN"
}

@Composable
private fun InboundOutboundSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        content()
    }
}

@Composable
private fun InboundCard(
    title: String,
    description: String,
    status: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OutboundValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
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
