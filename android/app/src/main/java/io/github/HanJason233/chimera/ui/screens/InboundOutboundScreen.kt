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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.HanJason233.chimera.data.model.FailoverTransferTarget
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.util.NetworkUtils
import io.github.HanJason233.chimera.viewmodel.MainViewModel

/** 入站与出站共用一个设置入口，通过顶部标签切换配置内容。 */
@Composable
fun InboundOutboundScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    AppScreenScaffold(
        title = "出入站",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("入站") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("出站") }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (selectedTab == 0) {
                    InboundContent(viewModel)
                } else {
                    OutboundContent(viewModel)
                }
            }
        }
    }
}

/** 只展示核心实际拥有的入站对象；应用过滤和 TUN 参数不属于核心入站。 */
@Composable
private fun InboundContent(viewModel: MainViewModel) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CoreSection(title = "核心入站") {
                InboundCard(
                    title = "本地 SOCKS5 入站（HEV 对接）",
                    description = "核心实际监听 127.0.0.1 端口，接收 HEV 数据面转换后的 SOCKS5 请求。",
                    status = if (vpnState == VpnState.CONNECTED) "运行中" else "VPN 启动后运行"
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    Divider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
        }
    }
}
/** 只展示核心实际的出站对象；节点选择仍由主页节点列表负责。 */
@Composable
private fun OutboundContent(viewModel: MainViewModel) {
    val currentNode by viewModel.currentNode.collectAsStateWithLifecycle()
    val failoverSettings by viewModel.failoverSettings.collectAsStateWithLifecycle()
    val failoverStatus by viewModel.failoverRuntimeStatus.collectAsStateWithLifecycle()
    val availableNodes by viewModel.allWarehouseNodes.collectAsStateWithLifecycle()
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val personalGroups by viewModel.userSubscriptionGroups.collectAsStateWithLifecycle()
    val countryOptions by viewModel.countryOptions.collectAsStateWithLifecycle()
    val hysteriaBandwidthEnabled by viewModel.hysteriaBandwidthEnabled.collectAsStateWithLifecycle()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsStateWithLifecycle()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsStateWithLifecycle()
    val isHysteriaBandwidthAdapting by viewModel.isHysteriaBandwidthAdapting.collectAsStateWithLifecycle()
    val failoverGroups = remember(availableNodes, subscriptionGroups, personalGroups, countryOptions) {
        buildList {
            subscriptionGroups.forEach { group ->
                add(
                    FailoverGroupOption(
                        id = NodeGroupKey.Subscription(group.sourceId).value,
                        name = "官方源 · ${group.name}",
                        nodeCount = availableNodes.count { it.subscriptionSourceId == group.sourceId }
                    )
                )
            }
            personalGroups.forEach { group ->
                add(
                    FailoverGroupOption(
                        id = group.id,
                        name = "私人源 · ${group.name}",
                        nodeCount = group.nodeCount
                    )
                )
            }
            countryOptions.forEach { group ->
                add(
                    FailoverGroupOption(
                        id = NodeGroupKey.Country(group.code).value,
                        name = "地区组 · ${group.name}",
                        nodeCount = group.nodeCount
                    )
                )
            }
        }.filter { it.nodeCount > 0 }.distinctBy(FailoverGroupOption::id)
    }

    var hysteriaUpMbpsInput by remember(hysteriaUpMbps) {
        mutableStateOf(hysteriaUpMbps.toString())
    }
    var hysteriaDownMbpsInput by remember(hysteriaDownMbps) {
        mutableStateOf(hysteriaDownMbps.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CoreSection(title = "核心出站") {
                OutboundHysteriaCard(
                    enabled = hysteriaBandwidthEnabled,
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
                groups = failoverGroups,
                viewModel = viewModel,
                onChange = viewModel::setFailoverTransferTarget
            )
        }
    }
}

/** 固定探测策略的故障检测，仅允许设置测试目标地址。 */
@Composable
private fun FailoverDetectionCard(
    settings: FailoverDetectionSettings,
    runtimeStatus: FailoverRuntimeStatus,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (FailoverDetectionSettings) -> Unit
) {
    var probeUrl by remember(settings.probeUrl) { mutableStateOf(settings.probeUrl) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "故障检测",
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
        }
        Text(
            text = runtimeStatus.message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (runtimeStatus.state.name in setOf("ERROR", "NO_BACKUP")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        OutlinedTextField(
            value = probeUrl,
            onValueChange = {
                probeUrl = it
                validationMessage = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("测试目标地址") },
            singleLine = true
        )
        validationMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = "核心自动探测出站节点，探测间隔约 2 秒；切换时保持 VPN 隧道",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            onClick = {
                val normalized = normalizeFailoverProbeUrlInput(probeUrl)
                if (normalized == null) {
                    validationMessage = "请输入有效的 HTTPS 地址"
                } else {
                    onSave(settings.copy(probeUrl = normalized))
                    probeUrl = normalized
                    validationMessage = null
                }
            }
        ) {
            Text("保存测试地址")
        }
    }
}

private fun normalizeFailoverProbeUrlInput(value: String): String? = runCatching {
    java.net.URI(value.trim())
}.getOrNull()?.takeIf { uri ->
    uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
}?.let { value.trim() }
private data class FailoverGroupOption(
    val id: String,
    val name: String,
    val nodeCount: Int
)

@Composable
private fun FailoverTransferCard(
    settings: FailoverTransferSettings,
    currentNodeId: String?,
    nodes: List<io.github.HanJason233.chimera.data.model.Node>,
    groups: List<FailoverGroupOption>,
    viewModel: MainViewModel,
    onChange: (FailoverTransferTarget?) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var nodePickerGroupId by remember { mutableStateOf<String?>(null) }
    val targetLabel = when (val target = settings.target) {
        is FailoverTransferTarget.Node -> {
            val nodeName = nodes.firstOrNull { it.id == target.nodeId }?.name ?: "节点已删除"
            "固定节点：$nodeName"
        }
        is FailoverTransferTarget.Group -> {
            val groupName = groups.firstOrNull { it.id == target.groupId }?.name ?: "节点组已删除"
            "随机组内节点：$groupName"
        }
        null -> "尚未选择备用目标"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "转移目标",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = targetLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = {
                    nodePickerGroupId = null
                    showPicker = true
                }) {
                    Text(if (settings.target == null) "选择" else "更改")
                }
                if (settings.target != null) {
                    TextButton(onClick = { onChange(null) }) { Text("清除") }
                }
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }

    if (showPicker) {
        val nodePickerGroup = groups.firstOrNull { it.id == nodePickerGroupId }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = {
                Text(nodePickerGroup?.let { "选择${it.name}中的节点" } ?: "选择节点组")
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (nodePickerGroup == null) {
                        if (groups.isEmpty()) {
                            Text(
                                text = "当前没有可用节点组",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            groups.forEach { group ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            onChange(FailoverTransferTarget.Group(group.id))
                                            showPicker = false
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = group.name,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${group.nodeCount} 个",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    TextButton(onClick = { nodePickerGroupId = group.id }) {
                                        Text("选节点")
                                        Icon(
                                            imageVector = Icons.Outlined.ChevronRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        TextButton(onClick = { nodePickerGroupId = null }) {
                            Text("返回节点组")
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        FailoverGroupNodeOptions(
                            group = nodePickerGroup,
                            currentNodeId = currentNodeId,
                            viewModel = viewModel,
                            onSelect = { node ->
                                onChange(FailoverTransferTarget.Node(node.id))
                                showPicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun FailoverGroupNodeOptions(
    group: FailoverGroupOption,
    currentNodeId: String?,
    viewModel: MainViewModel,
    onSelect: (io.github.HanJason233.chimera.data.model.Node) -> Unit
) {
    val groupNodesFlow = remember(group.id, viewModel) {
        viewModel.observeFailoverTargetGroupNodes(group.id)
    }
    val groupNodes by groupNodesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val eligibleNodes = remember(groupNodes, currentNodeId) {
        groupNodes.filter {
            it.id != currentNodeId &&
                it.type != io.github.HanJason233.chimera.data.model.NodeType.UNKNOWN &&
                it.server.isNotBlank() && it.port in 1..65_535
        }
    }

    if (eligibleNodes.isEmpty()) {
        Text(
            text = "该组没有可用的备用节点",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    } else {
        eligibleNodes.forEach { node ->
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelect(node) }
            ) {
                Text(
                    text = "${node.name} · ${node.type.protocol}",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Hy2 bandwidth controls only affect the matching protocol outbound. */
@Composable
private fun OutboundHysteriaCard(
    enabled: Boolean,
    upMbpsInput: String,
    downMbpsInput: String,
    isAdapting: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUpMbpsChange: (String) -> Unit,
    onDownMbpsChange: (String) -> Unit,
    onAdapt: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = upMbpsInput,
                    onValueChange = onUpMbpsChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("上行 Mbps") },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = downMbpsInput,
                    onValueChange = onDownMbpsChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("下行 Mbps") },
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
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
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
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
