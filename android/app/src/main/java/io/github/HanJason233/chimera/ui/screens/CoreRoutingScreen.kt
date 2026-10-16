package io.github.HanJason233.chimera.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.HanJason233.chimera.data.model.SmartRoutingDomainStrategy
import io.github.HanJason233.chimera.data.model.SmartRoutingPreset
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.data.model.SmartRoutingRulesCodec
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class RoutingMenuAction(val title: String) {
    PRESETS("导入预设"),
    IMPORT_CLIPBOARD("从剪贴板导入"),
    IMPORT_QR("扫描二维码导入"),
    EXPORT_CLIPBOARD("复制到剪贴板"),
    EXPORT_QR("生成分享二维码")
}

@Composable
fun CoreRoutingScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onScanQrCode: () -> Unit,
    scannedRulesText: String?,
    onScannedRulesTextConsumed: () -> Unit
) {
    val context = LocalContext.current
    val smartRoutingSettings by viewModel.smartRoutingSettings.collectAsStateWithLifecycle()
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingRule by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var strategyMenuExpanded by remember { mutableStateOf(false) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var qrRulesText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var draggedRuleId by remember { mutableStateOf<String?>(null) }
    var lastMovedTargetId by remember { mutableStateOf<String?>(null) }
    var draggedItemTop by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableFloatStateOf(1f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val editingRule = smartRoutingSettings.rules.firstOrNull { it.id == editingRuleId }

    fun importRules(json: String) {
        viewModel.importSmartRoutingRules(json) { success ->
            Toast.makeText(
                context,
                if (success) "路由规则已导入" else "导入失败，请检查规则 JSON",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(scannedRulesText) {
        val text = scannedRulesText
        if (!text.isNullOrBlank()) {
            importRules(text)
            onScannedRulesTextConsumed()
        }
    }

    AppScreenScaffold(
        title = "路由规则",
        subtitle = "按列表顺序匹配，首条命中后使用对应出口",
        onBack = onBack,
        actions = {
            IconButton(onClick = { addingRule = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加路由规则")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "路由规则菜单")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                RoutingMenuAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.title) },
                        onClick = {
                            menuExpanded = false
                            when (action) {
                                RoutingMenuAction.PRESETS -> showPresets = true
                                RoutingMenuAction.IMPORT_CLIPBOARD -> {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (text.isNullOrBlank()) {
                                        Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                    } else {
                                        importRules(text)
                                    }
                                }
                                RoutingMenuAction.IMPORT_QR -> onScanQrCode()
                                RoutingMenuAction.EXPORT_CLIPBOARD -> {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Chimera 路由规则",
                                            SmartRoutingRulesCodec.encode(smartRoutingSettings.rules)
                                        )
                                    )
                                    Toast.makeText(context, "路由规则已复制", Toast.LENGTH_SHORT).show()
                                }
                                RoutingMenuAction.EXPORT_QR -> {
                                    qrRulesText = SmartRoutingRulesCodec.encode(smartRoutingSettings.rules)
                                }
                            }
                        }
                    )
                }
            }
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "routing_header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "域名策略",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = { strategyMenuExpanded = true }) {
                            Text(smartRoutingSettings.domainStrategy.title)
                        }
                        DropdownMenu(
                            expanded = strategyMenuExpanded,
                            onDismissRequest = { strategyMenuExpanded = false }
                        ) {
                            SmartRoutingDomainStrategy.entries.forEach { strategy ->
                                DropdownMenuItem(
                                    text = { Text(strategy.title) },
                                    onClick = {
                                        strategyMenuExpanded = false
                                        viewModel.setSmartRoutingDomainStrategy(strategy)
                                    }
                                )
                            }
                        }
                    }
                    Text("路由规则", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "按列表顺序匹配。长按拖动规则可调整顺序；锁定规则会在导入时保留。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(
                items = smartRoutingSettings.rules,
                key = { _, rule -> "rule:${rule.id}" }
            ) { _, rule ->
                RoutingRuleItem(
                    rule = rule,
                    onEdit = { editingRuleId = rule.id },
                    onEnabledChange = { enabled ->
                        viewModel.setSmartRoutingRuleEnabled(rule.id, enabled)
                    },
                    dragHandleModifier = Modifier.pointerInput(rule.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == "rule:${rule.id}" }
                                draggedRuleId = rule.id
                                lastMovedTargetId = rule.id
                                draggedItemTop = info?.offset?.toFloat() ?: 0f
                                draggedItemHeight = info?.size?.toFloat() ?: 1f
                                dragDistance = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDistance += amount.y
                                val fingerCenter = draggedItemTop + draggedItemHeight / 2f + dragDistance
                                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    fingerCenter >= it.offset && fingerCenter <= it.offset + it.size
                                }
                                val targetId = (target?.key as? String)
                                    ?.takeIf { it.startsWith("rule:") }
                                    ?.removePrefix("rule:")
                                val sourceId = draggedRuleId
                                if (
                                    targetId != null && sourceId != null &&
                                    targetId != sourceId && targetId != lastMovedTargetId
                                ) {
                                    viewModel.moveSmartRoutingRule(sourceId, targetId)
                                    lastMovedTargetId = targetId
                                }
                            },
                            onDragEnd = {
                                draggedRuleId = null
                                lastMovedTargetId = null
                            },
                            onDragCancel = {
                                draggedRuleId = null
                                lastMovedTargetId = null
                            }
                        )
                    }
                )
            }
        }
    }

    if (addingRule || editingRule != null) {
        SmartRoutingRuleEditorDialog(
            initialRule = editingRule,
            onDismiss = {
                addingRule = false
                editingRuleId = null
            },
            onSave = { rule ->
                viewModel.saveSmartRoutingRule(rule)
                addingRule = false
                editingRuleId = null
            },
            onDelete = { ruleId ->
                viewModel.deleteSmartRoutingRule(ruleId)
                addingRule = false
                editingRuleId = null
            }
        )
    }

    if (showPresets) {
        AppAlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("选择路由预设") },
            text = {
                Column {
                    SmartRoutingPreset.entries.forEach { preset ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showPresets = false
                                val json = runCatching {
                                    context.assets.open("routing-presets/${preset.assetName}")
                                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                                }.getOrNull()
                                if (json == null) {
                                    Toast.makeText(context, "无法读取预设", Toast.LENGTH_SHORT).show()
                                } else {
                                    importRules(json)
                                }
                            }
                        ) {
                            Text(preset.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) { Text("取消") }
            }
        )
    }

    qrRulesText?.let { rulesText ->
        RoutingRulesQrDialog(
            json = rulesText,
            onDismiss = { qrRulesText = null }
        )
    }
}

@Composable
private fun RoutingRulesQrDialog(json: String, onDismiss: () -> Unit) {
    val qrCode by produceState<Result<ImageBitmap>?>(initialValue = null, key1 = json) {
        value = withContext(Dispatchers.Default) {
            runCatching { createRoutingQrCode(json) }
        }
    }
    val image = qrCode?.getOrNull()

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("路由规则二维码") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "可用 Chimera 或 v2rayNG 扫描导入",
                    style = MaterialTheme.typography.bodySmall
                )
                when {
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = "路由规则分享二维码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.White),
                        filterQuality = FilterQuality.None
                    )
                    qrCode == null -> CircularProgressIndicator()
                    else -> Text(
                        text = "规则内容超过单个二维码容量，请改用剪贴板导出。",
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

private fun createRoutingQrCode(json: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        json,
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
    return android.graphics.Bitmap.createBitmap(
        pixels,
        matrix.width,
        matrix.height,
        android.graphics.Bitmap.Config.ARGB_8888
    ).asImageBitmap()
}

@Composable
private fun RoutingRuleItem(
    rule: SmartRoutingRule,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    dragHandleModifier: Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onEdit)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rule.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (rule.locked) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "锁定规则",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "${rule.outboundTag} · ${rule.matchSummary()}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onEnabledChange
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑${rule.name}")
                }
                androidx.compose.foundation.layout.Box(
                    modifier = dragHandleModifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = "拖动排序 ${rule.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun SmartRoutingRule.matchSummary(): String = buildList {
    val ruleDomains = domains.ifEmpty {
        if (type == SmartRoutingRuleType.CUSTOM_DOMAIN) values else emptyList()
    }
    val ruleIps = ips.ifEmpty {
        if (type == SmartRoutingRuleType.CUSTOM_IP) values else emptyList()
    }
    if (ruleDomains.isNotEmpty()) add("域名：${ruleDomains.joinToString()}")
    if (ruleIps.isNotEmpty()) add("IP：${ruleIps.joinToString()}")
    if (processes.isNotEmpty()) add("进程：${processes.joinToString()}")
    if (ports.isNotBlank()) add("端口：$ports")
    if (protocols.isNotEmpty()) add("协议：${protocols.joinToString()}")
    if (network.isNotBlank()) add("网络：$network")
}.joinToString("；").ifBlank { "未配置匹配条件" }
