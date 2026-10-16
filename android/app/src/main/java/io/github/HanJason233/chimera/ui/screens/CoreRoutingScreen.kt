package io.github.HanJason233.chimera.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.HanJason233.chimera.data.model.SmartRoutingDomainStrategy
import io.github.HanJason233.chimera.data.model.SmartRoutingPreset
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    Text(
                        text = "Xray 核心使用此策略；sing-box 按规则中的域名和 IP 条件处理",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text(
                text = "规则列表",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "规则可通过编辑调整条件和出口，也可启停或移动顺序。进程条件会随规则导入导出。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            smartRoutingSettings.rules.forEachIndexed { index, rule ->
                key(rule.id) {
                    RoutingRuleItem(
                        rule = rule,
                        canMoveUp = index > 0,
                        canMoveDown = index < smartRoutingSettings.rules.lastIndex,
                        onEdit = { editingRuleId = rule.id },
                        onEnabledChange = { enabled ->
                            viewModel.setSmartRoutingRuleEnabled(rule.id, enabled)
                        },
                        onMoveUp = {
                            viewModel.moveSmartRoutingRule(
                                fromRuleId = rule.id,
                                toRuleId = smartRoutingSettings.rules[index - 1].id
                            )
                        },
                        onMoveDown = {
                            viewModel.moveSmartRoutingRule(
                                fromRuleId = rule.id,
                                toRuleId = smartRoutingSettings.rules[index + 1].id
                            )
                        }
                    )
                }
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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
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
                        .clickable(enabled = !rule.locked, onClick = onEdit)
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
                        text = "${rule.action.displayName()} · ${rule.matchSummary()}",
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
                IconButton(onClick = onEdit, enabled = !rule.locked) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑${rule.name}")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移${rule.name}")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移${rule.name}")
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
    if (type == SmartRoutingRuleType.DOMESTIC_DOMAIN) add("国内域名")
    if (type == SmartRoutingRuleType.DOMESTIC_IP) add("国内 IP")
    if (ruleDomains.isNotEmpty()) add("域名：${ruleDomains.joinToString()}")
    if (ruleIps.isNotEmpty()) add("IP：${ruleIps.joinToString()}")
    if (processes.isNotEmpty()) add("进程：${processes.joinToString()}")
    if (ports.isNotBlank()) add("端口：$ports")
    if (protocols.isNotEmpty()) add("协议：${protocols.joinToString()}")
    if (network.isNotBlank()) add("网络：$network")
}.joinToString("；").ifBlank { "未配置匹配条件" }

private fun SmartRoutingRuleAction.displayName(): String = when (this) {
    SmartRoutingRuleAction.DIRECT -> "直连"
    SmartRoutingRuleAction.PROXY -> "代理"
    SmartRoutingRuleAction.BLOCK -> "阻断"
}
