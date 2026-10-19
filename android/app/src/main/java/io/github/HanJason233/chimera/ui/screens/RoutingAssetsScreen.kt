package io.github.HanJason233.chimera.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.HanJason233.chimera.util.SingBoxRuleSetCatalog
import io.github.HanJason233.chimera.util.SingBoxRuleSetCatalogEntry
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.RoutingAssetsViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private data class RoutingAssetDraft(val id: String?, val name: String, val url: String)

private enum class RoutingAssetAddAction(val title: String) {
    LOCAL_FILE("添加本地文件"),
    URL("添加 URL"),
    QR_CODE("扫描二维码")
}

@OptIn(ExperimentalFoundationApi::class)
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
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    var addMenuExpanded by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var ruleSetSourceMenuExpanded by remember { mutableStateOf(false) }
    var assetDraft by remember { mutableStateOf<RoutingAssetDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<RoutingAssetRecord?>(null) }
    var deleteRuleSetTarget by remember { mutableStateOf<SingBoxRuleSetCatalogEntry?>(null) }
    var deleteBuiltInRuleSetTarget by remember { mutableStateOf<String?>(null) }
    val downloadedRuleSets = SingBoxRuleSetCatalog.entries.filter { entry ->
        state.singBoxRuleSets.containsKey(entry.fileName)
    }
    val visibleRuleSets = (downloadedRuleSets + SingBoxRuleSetCatalog.entries(state.singBoxProvider))
        .distinctBy(SingBoxRuleSetCatalogEntry::tag)

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
                pagerState.animateScrollToPage(0)
                val name = Uri.parse(url).lastPathSegment?.takeIf(String::isNotBlank)
                    ?: "routing.dat"
                assetDraft = RoutingAssetDraft(null, name, url)
            } else {
                Toast.makeText(context, "二维码中没有有效的 HTTP(S) 地址", Toast.LENGTH_SHORT).show()
            }
            onScannedAssetUrlConsumed()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) addMenuExpanded = false
    }

    AppScreenScaffold(
        title = "资源文件",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp),
        actions = {
            if (pagerState.currentPage == 0) {
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
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("Xray 资源文件") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("sing-box 资源文件") }
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (page == 0) {
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
                    }
                    if (page == 1) {
            item(key = "singbox_heading") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("sing-box 规则集", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "内置、已下载和当前来源可用的规则集统一显示在下方。",
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
                        Text("自动更新规则集", style = MaterialTheme.typography.bodyLarge)
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
            item(key = "singbox_provider") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ruleSetSourceMenuExpanded = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("规则集来源", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.singBoxProvider.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = ruleSetSourceMenuExpanded,
                        onDismissRequest = { ruleSetSourceMenuExpanded = false }
                    ) {
                        SingBoxRuleSetCatalog.providers.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(provider.title)
                                        Text(
                                            provider.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    ruleSetSourceMenuExpanded = false
                                    viewModel.selectSingBoxProvider(provider)
                                }
                            )
                        }
                    }
                }
            }
            item(key = "singbox_catalog_hint") {
                Text(
                    "规则集标签可填写到自定义分流规则中；云朵按钮用于下载或更新，垃圾桶用于移除本地文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            items(RuleManager.RULE_SET_FILES, key = { "singbox:builtin:$it" }) { name ->
                BuiltInRuleSetRow(
                    name = name,
                    fileInfo = state.singBoxRuleSets[name],
                    enabled = !state.loading,
                    onRemove = { deleteBuiltInRuleSetTarget = name }
                )
            }
            items(visibleRuleSets, key = { "catalog:${it.tag}" }) { entry ->
                CatalogRuleSetRow(
                    entry = entry,
                    fileInfo = state.singBoxRuleSets[entry.fileName],
                    enabled = !state.loading,
                    onInstallOrUpdate = {
                        val installed = state.singBoxRuleSets.containsKey(entry.fileName)
                        val operation: ((Boolean) -> Unit) -> Unit = if (installed) {
                            { callback -> viewModel.updateSingBoxRuleSet(entry, callback) }
                        } else {
                            { callback -> viewModel.installSingBoxRuleSet(entry, callback) }
                        }
                        operation { success ->
                            Toast.makeText(
                                context,
                                if (success) {
                                    if (installed) "已更新：${entry.title}" else "已下载：${entry.title}"
                                } else {
                                    "下载失败：${entry.title}"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onRemove = { deleteRuleSetTarget = entry }
                )
            }
                    }
        }
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

    deleteRuleSetTarget?.let { entry ->
        AppAlertDialog(
            onDismissRequest = { deleteRuleSetTarget = null },
            title = { Text("删除 sing-box 规则集") },
            text = { Text("删除“${entry.title}”的本地文件？规则集来源仍保留，可重新下载。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSingBoxRuleSet(entry)
                    deleteRuleSetTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteRuleSetTarget = null }) { Text("取消") }
            }
        )
    }

    deleteBuiltInRuleSetTarget?.let { name ->
        AppAlertDialog(
            onDismissRequest = { deleteBuiltInRuleSetTarget = null },
            title = { Text("删除 sing-box 规则集") },
            text = { Text("删除“$name”的本地文件？应用需要时会从安装包重新初始化。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBuiltInSingBoxRuleSet(name)
                    deleteBuiltInRuleSetTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteBuiltInRuleSetTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BuiltInRuleSetRow(
    name: String,
    fileInfo: RoutingAssetFileInfo?,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    val details = remember(fileInfo) {
        if (fileInfo == null) {
            "内置文件缺失，启动时会尝试从安装包初始化"
        } else {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(fileInfo.lastModified))
            "内置 · ${fileInfo.length.toAssetSize()} · $date"
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
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.DeleteForever, contentDescription = "删除 $name")
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun CatalogRuleSetRow(
    entry: SingBoxRuleSetCatalogEntry,
    fileInfo: RoutingAssetFileInfo?,
    enabled: Boolean,
    onInstallOrUpdate: () -> Unit,
    onRemove: () -> Unit
) {
    val details = remember(entry, fileInfo) {
        val fileStatus = if (fileInfo == null) {
            "未下载"
        } else {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(fileInfo.lastModified))
            "本地 · ${fileInfo.length.toAssetSize()} · $date"
        }
        "${entry.provider.title} · ${entry.routeExpression}\n${entry.sourcePath} · $fileStatus"
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onInstallOrUpdate, enabled = enabled) {
                Icon(
                    if (fileInfo == null) Icons.Filled.CloudDownload else Icons.Filled.Refresh,
                    contentDescription = if (fileInfo == null) "下载 ${entry.title}" else "更新 ${entry.title}"
                )
            }
            IconButton(onClick = onRemove, enabled = enabled && fileInfo != null) {
                Icon(Icons.Filled.DeleteForever, contentDescription = "删除 ${entry.title} 的本地文件")
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
