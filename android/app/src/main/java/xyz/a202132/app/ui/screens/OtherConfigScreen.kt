package xyz.a202132.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
fun OtherConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val autoDeleteTimeoutNodes by viewModel.autoDeleteTimeoutNodes.collectAsStateWithLifecycle()
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsStateWithLifecycle()
    val hideFavoriteNodeGroup by viewModel.hideFavoriteNodeGroup.collectAsStateWithLifecycle()
    val disconnectRecoveryEnabled by viewModel.disconnectRecoveryEnabled.collectAsStateWithLifecycle()
    val hysteriaBandwidthEnabled by viewModel.hysteriaBandwidthEnabled.collectAsStateWithLifecycle()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsStateWithLifecycle()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsStateWithLifecycle()
    val isHysteriaBandwidthAdapting by viewModel.isHysteriaBandwidthAdapting.collectAsStateWithLifecycle()
    val updateDownloadDirectoryUri by viewModel.updateDownloadDirectoryUri.collectAsStateWithLifecycle()
    val nodeDisplaySettings by viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()

    val updateDownloadDirectoryName = updateDownloadDirectoryUri?.let { uriString ->
        runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(uriString))?.name
        }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    val updateDownloadDirectoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        }.isSuccess
        if (persisted) {
            viewModel.setUpdateDownloadDirectoryUri(uri.toString())
        } else {
            Toast.makeText(context, "无法保存该目录的访问权限，请重新选择", Toast.LENGTH_SHORT).show()
        }
    }

    var hysteriaUpMbpsInput by remember(hysteriaUpMbps) {
        mutableStateOf(hysteriaUpMbps.toString())
    }
    var hysteriaDownMbpsInput by remember(hysteriaDownMbps) {
        mutableStateOf(hysteriaDownMbps.toString())
    }

    AppScreenScaffold(
        title = "基础设定",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OtherConfigSection(
                title = "网络配置"
            ) {
                Text(
                    text = "IPV6路由",
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

            OtherConfigSection(
                title = "节点标签显示",
                collapsible = true,
                initiallyExpanded = false
            ) {
                Text(
                    text = "协议、安全层、传输方式和核心可独立显示，默认全部关闭；全部关闭后隐藏节点标签。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                ConfigSwitchRow("显示协议", nodeDisplaySettings.showProtocol) {
                    viewModel.setNodeLabelShowProtocol(it)
                }
                ConfigSwitchRow("显示安全层", nodeDisplaySettings.showSecurity) {
                    viewModel.setNodeLabelShowSecurity(it)
                }
                ConfigSwitchRow("显示传输方式", nodeDisplaySettings.showTransport) {
                    viewModel.setNodeLabelShowTransport(it)
                }
                ConfigSwitchRow("显示核心", nodeDisplaySettings.showCore) {
                    viewModel.setNodeLabelShowCore(it)
                }
            }

            OtherConfigSection(
                title = "更新安装包下载目录"
            ) {
                Text(
                    text = if (updateDownloadDirectoryUri == null) {
                        "当前：应用缓存目录"
                    } else if (updateDownloadDirectoryName != null) {
                        "当前：$updateDownloadDirectoryName"
                    } else {
                        "当前自定义目录不可访问，请重新选择"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { updateDownloadDirectoryPicker.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("选择目录")
                    }
                    if (updateDownloadDirectoryUri != null) {
                        TextButton(
                            onClick = { viewModel.setUpdateDownloadDirectoryUri(null) }
                        ) {
                            Text("恢复默认")
                        }
                    }
                }
                Text(
                    text = "更新包将保存到所选文件夹，下载完成后由系统安装程序打开。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OtherConfigSection(
                title = "自动删除超时节点"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = autoDeleteTimeoutNodes,
                        onCheckedChange = { viewModel.setAutoDeleteTimeoutNodes(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "隐藏节点列表中的“收藏节点”订阅组"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = hideFavoriteNodeGroup,
                        onCheckedChange = { viewModel.setHideFavoriteNodeGroup(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "断线恢复",
                titleAction = {
                    Switch(
                        checked = disconnectRecoveryEnabled,
                        onCheckedChange = { viewModel.setDisconnectRecoveryEnabled(it) },
                        colors = visibleSwitchColors()
                    )
                }
            ) {}

            OtherConfigSection(
                title = "Hy2 加速",
                titleAction = {
                    Switch(
                        checked = hysteriaBandwidthEnabled,
                        onCheckedChange = { viewModel.setHysteriaBandwidthEnabled(it) },
                        colors = visibleSwitchColors()
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicNumberField(
                        label = "Up",
                        value = hysteriaUpMbpsInput,
                        onValueChange = {
                            hysteriaUpMbpsInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setHysteriaUpMbps(value.coerceIn(0, 100_000))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hysteriaBandwidthEnabled
                    )
                    BasicNumberField(
                        label = "Dw",
                        value = hysteriaDownMbpsInput,
                        onValueChange = {
                            hysteriaDownMbpsInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setHysteriaDownMbps(value.coerceIn(0, 100_000))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hysteriaBandwidthEnabled
                    )
                    TextButton(
                        onClick = { viewModel.adaptHysteriaBandwidth() },
                        enabled = hysteriaBandwidthEnabled && !isHysteriaBandwidthAdapting
                    ) {
                        Text(if (isHysteriaBandwidthAdapting) "测试中..." else "自适应")
                    }
                }
            }

        }
    }
}

@Composable
private fun ConfigSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = visibleSwitchColors()
        )
    }
}

@Composable
private fun BasicNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun OtherConfigSection(
    title: String? = null,
    titleAction: (@Composable () -> Unit)? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title ?: "") { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (collapsible) 48.dp else 0.dp)
                    .then(
                        if (collapsible) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                titleAction?.invoke()
                if (collapsible) {
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
            }
        }
        if (!collapsible || expanded) content()
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
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
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
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
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
private fun visibleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)
