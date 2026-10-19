package io.github.HanJason233.chimera.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import io.github.HanJason233.chimera.data.model.FailoverDetectionMethod
import io.github.HanJason233.chimera.data.model.FailoverPingTarget
import io.github.HanJason233.chimera.data.model.FailoverProbeStep
import io.github.HanJason233.chimera.data.model.FailoverRuntimeStatus
import io.github.HanJason233.chimera.data.model.FailoverTransferSettings
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.util.NetworkUtils
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlin.math.abs

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

    AppScreenScaffold(
        title = "入站",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
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

    AppScreenScaffold(
        title = "出站",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoreSection(title = "核心出站") {
                Text(
                    text = "局域网入站会直接复用当前代理出站，不会再次经过本机 TUN。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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

/** 故障检测按顺序列表配置；每轮逐项测试，全部失败后才转移节点。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailoverDetectionCard(
    settings: FailoverDetectionSettings,
    runtimeStatus: FailoverRuntimeStatus,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (FailoverDetectionSettings) -> Unit
) {
    var probeSteps by remember(settings.probeSteps) { mutableStateOf(settings.probeSteps) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<FailoverProbeStep?>(null) }
    var editingNewStep by remember { mutableStateOf(false) }
    val probeListState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    var draggedStepId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var draggedItemHeight by remember { mutableFloatStateOf(1f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var reorderBaseline by remember { mutableFloatStateOf(0f) }
    var settlingStepId by remember { mutableStateOf<String?>(null) }
    var settleStartOffset by remember { mutableFloatStateOf(0f) }
    var settleAnimationReady by remember { mutableStateOf(false) }
    val settleOffsetAnimation = remember { Animatable(0f) }

    LaunchedEffect(settlingStepId, settleStartOffset) {
        if (settlingStepId != null && abs(settleStartOffset) > 0.5f) {
            settleAnimationReady = false
            settleOffsetAnimation.snapTo(settleStartOffset)
            settleAnimationReady = true
            settleOffsetAnimation.animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            )
        }
        settlingStepId = null
        settleStartOffset = 0f
        settleAnimationReady = false
    }

    fun persistDetection(steps: List<FailoverProbeStep> = probeSteps) {
        probeSteps = steps
        onSave(settings.copy(probeSteps = steps))
    }

    // 记录卡片起始位置和高度，供跟手位移、排序阈值及释放回弹使用。
    fun startDragging(stepId: String) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        settlingStepId = null
        val itemInfo = probeListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == "failover:$stepId" }
        draggedStepId = stepId
        dragStartIndex = probeSteps.indexOfFirst { it.id == stepId }
        draggedItemHeight = itemInfo?.size?.toFloat() ?: 100f
        dragDistance = 0f
        reorderBaseline = 0f
    }

    // 拖动超过半个卡片高度时换位，列表动画负责让相邻卡片平滑让位。
    fun updateDragging(stepId: String, deltaY: Float) {
        dragDistance += deltaY
        var baseline = reorderBaseline
        val reorderStep = draggedItemHeight * 0.55f
        val reorderedSteps = probeSteps.toMutableList()
        var currentIndex = reorderedSteps.indexOfFirst { it.id == stepId }
        var hasMoved = false

        while (
            dragDistance - baseline >= reorderStep &&
            currentIndex >= 0 && currentIndex < reorderedSteps.lastIndex
        ) {
            reorderedSteps.add(currentIndex + 1, reorderedSteps.removeAt(currentIndex))
            currentIndex += 1
            baseline += draggedItemHeight
            hasMoved = true
        }
        while (
            dragDistance - baseline <= -reorderStep &&
            currentIndex > 0
        ) {
            reorderedSteps.add(currentIndex - 1, reorderedSteps.removeAt(currentIndex))
            currentIndex -= 1
            baseline -= draggedItemHeight
            hasMoved = true
        }
        reorderBaseline = baseline
        if (hasMoved) probeSteps = reorderedSteps
    }

    // 释放后保存最终顺序，并把卡片弹回它在列表中的位置。
    fun finishDragging(stepId: String) {
        val currentIndex = probeSteps.indexOfFirst { it.id == stepId }
        val settleOffset = if (currentIndex >= 0 && dragStartIndex >= 0) {
            dragDistance - (currentIndex - dragStartIndex) * draggedItemHeight
        } else {
            0f
        }
        if (abs(settleOffset) > 0.5f) {
            settlingStepId = stepId
            settleStartOffset = settleOffset
        }
        if (probeSteps != settings.probeSteps) persistDetection()
        draggedStepId = null
        dragStartIndex = -1
        dragDistance = 0f
        reorderBaseline = 0f
    }

    val startDrag = rememberUpdatedState<(String) -> Unit>(::startDragging)
    val updateDrag = rememberUpdatedState<(String, Float) -> Unit>(::updateDragging)
    val endDrag = rememberUpdatedState<(String) -> Unit>(::finishDragging)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    text = "按测试列表检查当前代理节点",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
        }
        Text(
            text = "从上到下依次测试；每项完成后按该项间隔等待。任一项通过即结束本轮，全部失败后才会转移节点。点按编辑，长按拖动排序。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = runtimeStatus.message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 12.sp,
            color = if (runtimeStatus.state.name in setOf("ERROR", "NO_BACKUP")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "测试顺序 · ${probeSteps.size} 项",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加测试项")
            }
        }

        if (probeSteps.isEmpty()) {
            Text(
                text = "还没有测试项，点击右侧 + 添加。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val listMaxHeight = (probeSteps.size * 120).coerceAtMost(480).dp
            LazyColumn(
                state = probeListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMaxHeight),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(
                    items = probeSteps,
                    key = { _, step -> "failover:${step.id}" }
                ) { index, step ->
                    val isDragging = draggedStepId == step.id
                    val isSettling = settlingStepId == step.id
                    val currentIndex = probeSteps.indexOfFirst { it.id == step.id }
                    val cardTranslation = when {
                        isDragging && currentIndex >= 0 && dragStartIndex >= 0 ->
                            dragDistance - (currentIndex - dragStartIndex) * draggedItemHeight
                        isSettling -> if (settleAnimationReady) {
                            settleOffsetAnimation.value
                        } else {
                            settleStartOffset
                        }
                        else -> 0f
                    }
                    val cardMovementModifier = Modifier
                        .zIndex(if (isDragging || isSettling) 1f else 0f)
                        .then(
                            if (isDragging || isSettling) {
                                Modifier.graphicsLayer { translationY = cardTranslation }
                            } else {
                                Modifier.animateItemPlacement()
                            }
                        )
                        .pointerInput(step.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startDrag.value(step.id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    updateDrag.value(step.id, amount.y)
                                },
                                onDragEnd = { endDrag.value(step.id) },
                                onDragCancel = { endDrag.value(step.id) }
                            )
                        }
                        .clickable {
                            editingStep = step
                            editingNewStep = false
                        }

                FailoverProbeStepRow(
                    step = step,
                    priority = index + 1,
                    isDragging = isDragging,
                    canDelete = probeSteps.size > 1,
                    modifier = cardMovementModifier,
                    onDelete = {
                        persistDetection(probeSteps.filterNot { it.id == step.id })
                    }
                )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加测试项") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FailoverDetectionMethod.entries.forEach { method ->
                        TextButton(
                            onClick = {
                                editingStep = FailoverProbeStep(method = method)
                                editingNewStep = true
                                showAddDialog = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = method.title,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = when (method) {
                                        FailoverDetectionMethod.HTTPS_PROBE -> "通过代理请求 HTTPS 地址"
                                        FailoverDetectionMethod.PROXY_TCP_PING -> "通过代理连接域名或 IP 的端口"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("关闭") }
            }
        )
    }

    editingStep?.let { step ->
        FailoverProbeStepDialog(
            step = step,
            isNewStep = editingNewStep,
            onDismiss = {
                editingStep = null
                editingNewStep = false
            },
            onSave = { updatedStep ->
                val updatedSteps = if (editingNewStep) {
                    probeSteps + updatedStep
                } else {
                    probeSteps.map { current ->
                        if (current.id == updatedStep.id) updatedStep else current
                    }
                }
                persistDetection(updatedSteps)
                editingStep = null
                editingNewStep = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailoverProbeStepRow(
    step: FailoverProbeStep,
    priority: Int,
    isDragging: Boolean,
    canDelete: Boolean,
    modifier: Modifier,
    onDelete: () -> Unit
) {
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 4.dp else 0.dp,
        label = "FailoverProbeStepElevation"
    )
    val target = when (step.method) {
        FailoverDetectionMethod.HTTPS_PROBE -> step.probeUrl
        FailoverDetectionMethod.PROXY_TCP_PING -> "${step.pingHost}:${step.pingPort}"
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = elevation
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "测试项 $priority · ${step.method.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = target,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "间隔 ${step.intervalSeconds} 秒 · 超时 ${step.timeoutMs} 毫秒",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = "长按拖动排序",
                        modifier = Modifier
                            .padding(12.dp)
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onDelete, enabled = canDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除测试项 $priority"
                        )
                    }
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
private fun FailoverProbeStepDialog(
    step: FailoverProbeStep,
    isNewStep: Boolean,
    onDismiss: () -> Unit,
    onSave: (FailoverProbeStep) -> Unit
) {
    var editedStep by remember(step.id) { mutableStateOf(step) }
    var pingPortInput by remember(step.id) { mutableStateOf(step.pingPort.toString()) }
    var intervalInput by remember(step.id) { mutableStateOf(step.intervalSeconds.toString()) }
    var timeoutInput by remember(step.id) { mutableStateOf(step.timeoutMs.toString()) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNewStep) "添加 ${step.method.title}" else "编辑测试项") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (step.method) {
                    FailoverDetectionMethod.HTTPS_PROBE -> OutlinedTextField(
                        value = editedStep.probeUrl,
                        onValueChange = { editedStep = editedStep.copy(probeUrl = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("HTTPS 测活地址") },
                        singleLine = true
                    )
                    FailoverDetectionMethod.PROXY_TCP_PING -> {
                        Text(
                            text = "经当前代理节点建立 TCP 连接；域名由代理端解析。这不是 ICMP Ping。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = editedStep.pingHost,
                            onValueChange = { editedStep = editedStep.copy(pingHost = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("域名或 IP") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = pingPortInput,
                            onValueChange = { pingPortInput = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("检测间隔（秒，1–2）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = timeoutInput,
                    onValueChange = { timeoutInput = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("单项超时（毫秒，500–2000）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                validationMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val interval = intervalInput.toIntOrNull()
                    val timeout = timeoutInput.toLongOrNull()
                    if (interval == null || interval !in 1..2 ||
                        timeout == null || timeout !in 500L..2_000L
                    ) {
                        validationMessage = "检测间隔需为 1–2 秒，单项超时需为 500–2000 毫秒"
                    } else {
                        val candidate = editedStep.copy(
                            intervalSeconds = interval,
                            timeoutMs = timeout,
                            pingPort = if (step.method == FailoverDetectionMethod.PROXY_TCP_PING) {
                                pingPortInput.toIntOrNull() ?: 0
                            } else {
                                editedStep.pingPort
                            }
                        )
                        val normalized = normalizeFailoverProbeStepInput(candidate)
                        if (normalized == null) {
                            validationMessage = when (step.method) {
                                FailoverDetectionMethod.HTTPS_PROBE -> "请输入有效的 HTTPS 地址"
                                FailoverDetectionMethod.PROXY_TCP_PING -> "请检查域名/IP 和端口（1–65535）"
                            }
                        } else {
                            onSave(normalized)
                        }
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun normalizeFailoverProbeStepInput(step: FailoverProbeStep): FailoverProbeStep? =
    when (step.method) {
        FailoverDetectionMethod.HTTPS_PROBE -> runCatching {
            java.net.URI(step.probeUrl.trim())
        }.getOrNull()?.takeIf { uri ->
            uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
        }?.let { step.copy(probeUrl = step.probeUrl.trim()) }
        FailoverDetectionMethod.PROXY_TCP_PING -> {
            val host = FailoverPingTarget.normalizeHost(step.pingHost)
            if (host == null || !FailoverPingTarget.isValidPort(step.pingPort)) {
                null
            } else {
                step.copy(pingHost = host)
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            .padding(vertical = 6.dp),
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
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
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
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
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
