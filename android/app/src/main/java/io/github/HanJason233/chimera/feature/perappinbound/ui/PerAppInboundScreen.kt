package io.github.HanJason233.chimera.feature.perappinbound.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.ui.components.SegmentedToggle
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PerAppInboundScreen(
    viewModel: PerAppInboundViewModel,
    mode: ProxyMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsFlow = remember(viewModel, mode) { viewModel.settings(mode) }
    val appsFlow = remember(viewModel, mode) { viewModel.displayedApps(mode) }
    val settings by settingsFlow.collectAsStateWithLifecycle()
    val apps by appsFlow.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showModeInformation by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { messageId ->
            Toast.makeText(context, context.getString(messageId), Toast.LENGTH_SHORT).show()
        }
    }

    AppScreenScaffold(
        title = "${if (mode == ProxyMode.GLOBAL) "全局" else "智能"} VPN 作用范围",
        onBack = onBack,
        isSearchActive = searchActive,
        searchQuery = searchQuery,
        searchPlaceholder = stringResource(R.string.per_app_inbound_search_hint),
        onSearchQueryChange = { query ->
            searchQuery = query
            viewModel.filterApps(query)
        },
        onSearchClose = {
            searchQuery = ""
            viewModel.filterApps("")
            searchActive = false
        },
        actions = {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(22.dp),
                    strokeWidth = 2.dp
                )
            }
            if (!searchActive) {
                IconButton(onClick = { searchActive = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.per_app_inbound_search)
                    )
                }
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.per_app_inbound_more_actions)
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.per_app_inbound_select_all)) },
                    enabled = !isLoading && apps.isNotEmpty(),
                    onClick = { showMenu = false; viewModel.selectAll(mode) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.per_app_inbound_invert_selection)) },
                    enabled = !isLoading && apps.isNotEmpty(),
                    onClick = { showMenu = false; viewModel.invertSelection(mode) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.per_app_inbound_auto_select)) },
                    enabled = !isLoading,
                    onClick = { showMenu = false; viewModel.selectProxyAppsAutomatically(mode) }
                )
            }
        },
        contentPadding = PaddingValues(0.dp),
        contentWindowInsets = WindowInsets(0)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // 两个分应用开关和说明入口放在同一行，保持与 v2rayNG 设置栏一致。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.per_app_inbound_enable),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Switch(
                        checked = settings.enabled,
                        modifier = Modifier.scale(0.72f),
                        onCheckedChange = { viewModel.setPerAppInboundEnabled(it, mode) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                            checkedTrackColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
                Text(
                    text = stringResource(R.string.per_app_inbound_mode_label),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                SegmentedToggle(
                    firstLabel = stringResource(R.string.per_app_inbound_whitelist_option),
                    secondLabel = stringResource(R.string.per_app_inbound_blacklist_option),
                    selectedFirst = !settings.bypassMode,
                    onSelectionChange = { isWhitelist ->
                        viewModel.setBypassMode(!isWhitelist, mode)
                    }
                )
                IconButton(onClick = { showModeInformation = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.per_app_inbound_mode_information)
                    )
                }
            }
            Divider()

            if (!isLoading && apps.isEmpty()) {
                Text(
                    text = if (searchQuery.isNotBlank()) {
                        stringResource(R.string.per_app_inbound_no_search_results)
                    } else {
                        stringResource(R.string.per_app_inbound_no_apps)
                    },
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
                )
            ) {
                items(items = apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in settings.selectedPackages
                    InstalledAppRow(
                        app = app,
                        checked = checked,
                        selectedLabel = stringResource(R.string.per_app_inbound_selected),
                        notSelectedLabel = stringResource(R.string.per_app_inbound_not_selected),
                        onClick = { viewModel.toggle(app.packageName, mode) }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (showModeInformation) {
        AppAlertDialog(
            onDismissRequest = { showModeInformation = false },
            title = { Text(stringResource(R.string.per_app_inbound_mode_information)) },
            text = { Text(stringResource(R.string.per_app_inbound_mode_summary)) },
            confirmButton = {
                TextButton(onClick = { showModeInformation = false }) {
                    Text(stringResource(R.string.per_app_inbound_ok))
                }
            }
        )
    }
}

@Composable
private fun InstalledAppRow(
    app: InstalledInboundApp,
    checked: Boolean,
    selectedLabel: String,
    notSelectedLabel: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val iconBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = if (checked) selectedLabel else notSelectedLabel
            }
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap == null) {
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                modifier = Modifier.size(40.dp).padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Image(
                bitmap = iconBitmap!!,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp, end = 12.dp)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
