package io.github.HanJason233.chimera.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.repository.RoutingAssetFileInfo
import io.github.HanJason233.chimera.data.repository.RoutingAssetRecord
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.util.RuleManager
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.RoutingAssetsViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class RoutingAssetDraft(val id: String?, val name: String, val url: String)

private enum class RoutingAssetAddAction(val title: String) {
    LOCAL_FILE("添加本地文件"),
    URL("添加 URL"),
    QR_CODE("扫描二维码")
}

@Composable
internal fun RoutingAssetsScreen(
    viewModel: RoutingAssetsViewModel,
    onBack: () -> Unit,
    onScanQrCode: () -> Unit,
    scannedAssetUrl: String?,
    onScannedAssetUrlConsumed: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var addMenuExpanded by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var assetDraft by remember { mutableStateOf<RoutingAssetDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<RoutingAssetRecord?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalFile(uri) { error ->
                Toast.makeText(context, error ?: "资源文件已添加", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(scannedAssetUrl) {
        val url = scannedAssetUrl?.trim()
        if (!url.isNullOrBlank()) {
            if (isValidAssetUrl(url)) {
                val name = Uri.parse(url).lastPathSegment?.takeIf(String::isNotBlank)
                    ?: "routing.dat"
                assetDraft = RoutingAssetDraft(null, name, url)
            } else {
                Toast.makeText(context, "二维码中没有有效的 HTTP(S) 地址", Toast.LENGTH_SHORT).show()
            }
            onScannedAssetUrlConsumed()
        }
    }

    AppScreenScaffold(
        title = "资源文件",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp),
        actions = {
            IconButton(onClick = { addMenuExpanded = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加资源文件")
            }
            DropdownMenu(
                expanded = addMenuExpanded,
                onDismissRequest = { addMenuExpanded = false }
            ) {
                RoutingAssetAddAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.title) },
                        onClick = {
                            addMenuExpanded = false
                            when (action) {
                                RoutingAssetAddAction.LOCAL_FILE -> filePicker.launch(arrayOf("*/*"))
                                RoutingAssetAddAction.URL -> assetDraft = RoutingAssetDraft(null, "", "")
                                RoutingAssetAddAction.QR_CODE -> onScanQrCode()
                            }
                        }
                    )
                }
            }
            IconButton(
                onClick = {
                    viewModel.downloadAll { result ->
                        val message = if (result.failed.isEmpty()) {
                            "已更新 ${result.downloaded} 个资源文件"
                        } else {
                            "已更新 ${result.downloaded} 个，失败：${result.failed.joinToString()}"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !state.loading
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = "更新 Xray 和 sing-box 资源文件")
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "geo_source") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sourceMenuExpanded = true }
                            .padding(16.dp)
                    ) {
                        Text(
                            "Xray Geo 文件来源（可选）",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            snapshot.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false }
                    ) {
                        RoutingAssetsRepository.GEO_SOURCES.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source) },
                                onClick = {
                                    sourceMenuExpanded = false
                                    viewModel.saveSource(source)
                                }
                            )
                        }
                    }
                }
            }
            item(key = "asset_heading") {
                Text(
                    "Xray GeoIP/GeoSite 数据随安装包提供，并在首次启动时初始化到本地。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    "Xray Geo 数据库",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
            items(snapshot.assets, key = RoutingAssetRecord::id) { asset ->
                val info = snapshot.files[asset.id]
                Column {
                    RoutingAssetRow(
                        asset = asset,
                        fileInfo = info,
                        onEdit = {
                            assetDraft = RoutingAssetDraft(asset.id, asset.name, asset.url)
                        },
                        onDelete = { deleteTarget = asset }
                    )
                    Divider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            item(key = "singbox_heading") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("sing-box 规则集", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "本页管理内置的 geoip-cn.srs 和 geosite-cn.srs；其他 Geo 标签由 sing-box 核心按需下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item(key = "singbox_auto_update") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动更新内置 sing-box 规则集", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "应用启动后每 24 小时检查；关闭后仍可手动更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.singBoxAutoUpdateEnabled,
                        onCheckedChange = viewModel::setSingBoxAutoUpdateEnabled,
                        enabled = !state.loading
                    )
                }
            }
            items(RuleManager.RULE_SET_FILES, key = { "singbox:$it" }) { name ->
                SingBoxRuleSetRow(name, state.singBoxRuleSets[name])
            }
        }
    }

    assetDraft?.let { draft ->
        RoutingAssetEditorDialog(
            draft = draft,
            onDismiss = { assetDraft = null },
            onSave = { name, url ->
                viewModel.saveAsset(draft.id, name, url) { error ->
                    if (error == null) {
                        assetDraft = null
                        Toast.makeText(context, "资源地址已保存", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    deleteTarget?.let { asset ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除资源文件") },
            text = { Text("删除“${asset.name}”及其本地文件？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAsset(asset)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SingBoxRuleSetRow(
    name: String,
    fileInfo: RoutingAssetFileInfo?
) {
    val details = remember(fileInfo) {
        if (fileInfo == null) {
            "本地文件缺失，使用 sing-box 时会从安装包初始化"
        } else {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(fileInfo.lastModified))
            "${fileInfo.length.toAssetSize()} · $date"
        }
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun RoutingAssetRow(
    asset: RoutingAssetRecord,
    fileInfo: io.github.HanJason233.chimera.data.repository.RoutingAssetFileInfo?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val details = remember(fileInfo) {
        if (fileInfo == null) "文件未找到" else {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(fileInfo.lastModified))
            "${fileInfo.length.toAssetSize()} · $date"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp)
        ) {
            Text(
                asset.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!asset.locked && asset.url != RoutingAssetsRepository.LOCAL_FILE_URL) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑 ${asset.name}")
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteForever, contentDescription = "删除 ${asset.name}")
        }
    }
}

@Composable
private fun RoutingAssetEditorDialog(
    draft: RoutingAssetDraft,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by rememberSaveable(draft.id, draft.url) { mutableStateOf(draft.name) }
    var url by rememberSaveable(draft.id, draft.url) { mutableStateOf(draft.url) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "添加资源文件" else "编辑资源文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("资源 URL") },
                    placeholder = { Text("https://example.com/geosite.dat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && isValidAssetUrl(url)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun isValidAssetUrl(value: String): Boolean =
    runCatching { Uri.parse(value).scheme?.lowercase() in setOf("http", "https") }
        .getOrDefault(false)

private fun Long.toAssetSize(): String = when {
    this < 1024L -> "$this B"
    this < 1024L * 1024L -> "${this / 1024L} KB"
    else -> String.format(Locale.getDefault(), "%.2f MB", this / (1024.0 * 1024.0))
}
