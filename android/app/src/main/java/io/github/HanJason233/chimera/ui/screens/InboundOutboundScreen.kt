package io.github.HanJason233.chimera.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.data.model.LanInboundSettings
import io.github.HanJason233.chimera.data.model.FailoverDetectionSettings
import io.github.HanJason233.chimera.data.model.FailoverRuntimeStatus
import io.github.HanJason233.chimera.data.model.FailoverTransferSettings
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.util.NetworkUtils
import io.github.HanJason233.chimera.viewmodel.MainViewModel

/** 只展示核心实际拥有的入站对象；应用过滤和 TUN 参数不属于核心入站。 */
@Composable
fun InboundScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lanInbound by viewModel.lanInboundSettings.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
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

    AppScreenScaffold(title = "入站", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CoreSection(title = "核心入站") {
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
                                    text = "局域网 SOCKS5 入站",
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
                            text = "VPN 运行后监听 0.0.0.0:$portInput，局域网设备通过 SOCKS5 使用核心出站。",
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
                                    ClipData.newPlainText("Chimera SOCKS5", uri)
                                )
                                Toast.makeText(context, "URI 已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 只展示核心实际的出站对象；节点选择仍由主页节点列表负责。 */
@Composable
fun OutboundScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val currentNode by viewModel.currentNode.collectAsStateWithLifecycle()
    val failoverSettings by viewModel.failoverSettings.collectAsStateWithLifecycle()
    val failoverStatus by viewModel.failoverRuntimeStatus.collectAsStateWithLifecycle()
    val availableNodes by viewModel.allWarehouseNodes.collectAsStateWithLifecycle()
    val hysteriaBandwidthEnabled by viewModel.hysteriaBandwidthEnabled.collectAsStateWithLifecycle()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsStateWithLifecycle()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsStateWithLifecycle()
    val isHysteriaBandwidthAdapting by viewModel.isHysteriaBandwidthAdapting.collectAsStateWithLifecycle()

    var hysteriaUpMbpsInput by remember(hysteriaUpMbps) {
        mutableStateOf(hysteriaUpMbps.toString())
    }
    var hysteriaDownMbpsInput by remember(hysteriaDownMbps) {
        mutableStateOf(hysteriaDownMbps.toString())
    }

    AppScreenScaffold(title = "出站", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CoreSection(title = "核心出站") {
                OutboundValueRow("当前代理出站", currentNode?.name ?: "未选择节点")
                Text(
                    text = "局域网入站会直接复用当前代理出站，不会再次经过本机 TUN。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutboundHysteriaCard(
                    enabled = hysteriaBandwidthEnabled,
                    upMbps = hysteriaUpMbps,
                    downMbps = hysteriaDownMbps,
                    upMbpsInput = hysteriaUpMbpsInput,
                    downMbpsInput = hysteriaDownMbpsInput,
                    isAdapting = isHysteriaBandwidthAdapting,
                    onEnabledChange = viewModel::setHysteriaBandwidthEnabled,
                    onUpMbpsChange = { value ->
                        hysteriaUpMbpsInput = value.filter(Char::isDigit)
                        value.toIntOrNull()?.let { upMbpsValue ->
                            viewModel.setHysteriaUpMbps(upMbpsValue.coerceIn(0, 100_000))
                        }
                    },
                    onDownMbpsChange = { value ->
                        hysteriaDownMbpsInput = value.filter(Char::isDigit)
                        value.toIntOrNull()?.let { downMbpsValue ->
                            viewModel.setHysteriaDownMbps(downMbpsValue.coerceIn(0, 100_000))
                        }
                    },
                    onAdapt = viewModel::adaptHysteriaBandwidth
                )
            }
            CoreSection(title = "故障转移") {
                FailoverDetectionCard(
                    settings = failoverSettings.detection,
                    runtimeStatus = failoverStatus,
                    onEnabledChange = viewModel::setFailoverDetectionEnabled,
                    onSave = viewModel::setFailoverDetection
                )
                FailoverTransferCard(
                    settings = failoverSettings.transfer,
                    currentNodeId = currentNode?.id,
                    nodes = availableNodes,
                    onChange = viewModel::setFailoverTransferNodes
                )
            }
        }
    }
}

/** Runtime health check configuration; probe cadence and transfer targets remain separate. */
@Composable
private fun FailoverDetectionCard(
    settings: FailoverDetectionSettings,
    runtimeStatus: FailoverRuntimeStatus,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (FailoverDetectionSettings) -> Unit
) {
    val context = LocalContext.current
    var probeUrl by remember(settings.probeUrl) { mutableStateOf(settings.probeUrl) }
    var intervalInput by remember(settings.intervalSeconds) {
        mutableStateOf(settings.intervalSeconds.toString())
    }
    var timeoutInput by remember(settings.timeoutMs) { mutableStateOf(settings.timeoutMs.toString()) }
    var failureInput by remember(settings.consecutiveFailures) {
        mutableStateOf(settings.consecutiveFailures.toString())
    }
    var validationMessage by remember { mutableStateOf<String?>(null) }

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
                        text = "故障检测",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "通过当前代理出口发送 HTTPS 请求检查可用性",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                text = "检测方式：HTTPS 状态码探测。系统底层网络未验证时会暂停判定；连续失败达到阈值后启动转移。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = runtimeStatus.message,
                fontSize = 12.sp,
                color = if (runtimeStatus.state.name in setOf("ERROR", "NO_BACKUP")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            OutlinedTextField(
                value = probeUrl,
                onValueChange = { probeUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS 探测地址") },
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f),
                    label = { Text("间隔（秒）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = timeoutInput,
                    onValueChange = { timeoutInput = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f),
                    label = { Text("超时（毫秒）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            OutlinedTextField(
                value = failureInput,
                onValueChange = { failureInput = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("连续失败次数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            validationMessage?.let {
                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val parsedProbeUrl = Uri.parse(probeUrl.trim())
                    val interval = intervalInput.toIntOrNull()?.takeIf { it in 1..2 }
                    val timeout = timeoutInput.toLongOrNull()?.takeIf { it in 500L..2_000L }
                    val failures = failureInput.toIntOrNull()?.takeIf { it in 1..2 }
                    validationMessage = when {
                        parsedProbeUrl.scheme?.equals("https", ignoreCase = true) != true ||
                            parsedProbeUrl.host.isNullOrBlank() ->
                            "探测地址必须使用 HTTPS"
                        interval == null -> "检测间隔需为 1–2 秒"
                        timeout == null -> "超时需为 500–2000 毫秒"
                        failures == null -> "连续失败次数需为 1–2 次"
                        else -> null
                    }
                    if (validationMessage == null && interval != null && timeout != null && failures != null) {
                        onSave(
                            settings.copy(
                                probeUrl = probeUrl.trim(),
                                intervalSeconds = interval,
                                timeoutMs = timeout,
                                consecutiveFailures = failures
                            )
                        )
                        Toast.makeText(context, "故障检测设置已保存", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("保存检测设置")
            }
            val confirmationEstimateSeconds = (
                (settings.intervalSeconds.toLong() * 1_000L + settings.timeoutMs) *
                    settings.consecutiveFailures.toLong() + 999L
                ) / 1_000L
            Text(
                text = "按已保存参数估算，约 ${confirmationEstimateSeconds} 秒确认故障；之后还需启动转移节点。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FailoverTransferCard(
    settings: FailoverTransferSettings,
    currentNodeId: String?,
    nodes: List<io.github.HanJason233.chimera.data.model.Node>,
    onChange: (List<String>) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var pickedNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val nodeById = remember(nodes) { nodes.associateBy { it.id } }
    val eligibleNodes = remember(nodes, currentNodeId) {
        nodes.filter {
            it.id != currentNodeId &&
                it.type != io.github.HanJason233.chimera.data.model.NodeType.UNKNOWN &&
                it.server.isNotBlank() && it.port in 1..65_535
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "转移节点",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "按列表顺序尝试连接。当前连接和失效节点会跳过；切换后保持使用转移节点，不自动回切。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (settings.nodeIds.isEmpty()) {
                Text("尚未添加转移节点", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                settings.nodeIds.forEachIndexed { index, nodeId ->
                    val node = nodeById[nodeId]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${index + 1}. ${node?.name ?: "节点已删除"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            node?.let {
                                Text(
                                    text = "${it.type.protocol} · ${it.server}:${it.port}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                val orderedIds = settings.nodeIds.toMutableList()
                                val previous = orderedIds[index - 1]
                                orderedIds[index - 1] = orderedIds[index]
                                orderedIds[index] = previous
                                onChange(orderedIds)
                            }
                        ) { Text("上移") }
                        TextButton(
                            enabled = index < settings.nodeIds.lastIndex,
                            onClick = {
                                val orderedIds = settings.nodeIds.toMutableList()
                                val next = orderedIds[index + 1]
                                orderedIds[index + 1] = orderedIds[index]
                                orderedIds[index] = next
                                onChange(orderedIds)
                            }
                        ) { Text("下移") }
                        TextButton(onClick = { onChange(settings.nodeIds - nodeId) }) { Text("移除") }
                    }
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    pickedNodeIds = settings.nodeIds.toSet()
                    showPicker = true
                }
            ) {
                Text("添加或选择转移节点")
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择转移节点") },
            text = {
                if (eligibleNodes.isEmpty()) {
                    Text("没有可添加的节点")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        eligibleNodes.forEach { node ->
                            val checked = node.id in pickedNodeIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pickedNodeIds = if (checked) pickedNodeIds - node.id
                                        else pickedNodeIds + node.id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        pickedNodeIds = if (isChecked) pickedNodeIds + node.id
                                        else pickedNodeIds - node.id
                                    }
                                )
                                Column {
                                    Text(node.name, fontSize = 13.sp)
                                    Text(
                                        "${node.type.protocol} · ${node.server}:${node.port}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val retained = settings.nodeIds.filter { it in pickedNodeIds && it != currentNodeId }
                    val newlyPicked = eligibleNodes.map { it.id }
                        .filter { it in pickedNodeIds && it !in retained }
                    onChange(retained + newlyPicked)
                    showPicker = false
                }) { Text("完成") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        )
    }
}

/** Hy2 bandwidth controls only affect the matching protocol outbound. */
@Composable
private fun OutboundHysteriaCard(
    enabled: Boolean,
    upMbps: Int,
    downMbps: Int,
    upMbpsInput: String,
    downMbpsInput: String,
    isAdapting: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUpMbpsChange: (String) -> Unit,
    onDownMbpsChange: (String) -> Unit,
    onAdapt: () -> Unit
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
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "启用后仅对 Hysteria2 节点的核心出站生效。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = upMbpsInput,
                    onValueChange = onUpMbpsChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Up") },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = downMbpsInput,
                    onValueChange = onDownMbpsChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Dw") },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                TextButton(
                    onClick = onAdapt,
                    enabled = enabled && !isAdapting
                ) {
                    Text(if (isAdapting) "测试中..." else "自适应")
                }
            }
            Text(
                text = "当前上行 $upMbps Mbps，下行 $downMbps Mbps",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LanShareUriPanel(
    settings: LanInboundSettings,
    addresses: List<String>,
    onCopy: (String) -> Unit
) {
    var qrAddress by remember(settings, addresses) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "分享配置",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SOCKS5 · $address:${settings.port}",
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { onCopy(uri) }) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = "复制 URI"
                            )
                        }
                        IconButton(onClick = { qrAddress = address }) {
                            Icon(
                                imageVector = Icons.Outlined.QrCode2,
                                contentDescription = "显示分享二维码"
                            )
                        }
                    }
                }
            }
        }
    }

    qrAddress?.let { address ->
        LanShareQrDialog(
            address = "$address:${settings.port}",
            uri = buildSocksImportUri(address, settings),
            onDismiss = { qrAddress = null }
        )
    }
}

@Composable
private fun LanShareQrDialog(address: String, uri: String, onDismiss: () -> Unit) {
    val qrCode by produceState<Result<ImageBitmap>?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.Default) {
            runCatching { createShareQrCode(uri) }
        }
    }
    val image = qrCode?.getOrNull()

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码导入") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "SOCKS5 · $address", style = MaterialTheme.typography.bodySmall)
                when {
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = "SOCKS5 配置分享二维码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.White),
                        filterQuality = FilterQuality.None
                    )
                    qrCode == null -> CircularProgressIndicator()
                    else -> Text(
                        text = "二维码生成失败，请使用剪贴板图标复制配置。",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun createShareQrCode(uri: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        uri,
        BarcodeFormat.QR_CODE,
        512,
        512,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4
        )
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private fun buildSocksImportUri(address: String, settings: LanInboundSettings): String {
    // v2rayN 等客户端的 SOCKS 导入器要求明文 URL 结构，不能使用整段 Base64。
    val username = Uri.encode(settings.username)
    val password = Uri.encode(settings.password)
    return "socks://${username}:${password}@${address}:${settings.port}#Chimera-LAN"
}

@Composable
private fun CoreSection(
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
