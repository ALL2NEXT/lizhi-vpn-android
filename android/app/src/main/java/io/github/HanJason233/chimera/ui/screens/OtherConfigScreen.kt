package io.github.HanJason233.chimera.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun OtherConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val disconnectRecoveryEnabled by viewModel.disconnectRecoveryEnabled.collectAsStateWithLifecycle()
    val updateDownloadDirectoryUri by viewModel.updateDownloadDirectoryUri.collectAsStateWithLifecycle()
    val protocolCoreSettings by viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val serviceRequestTimeoutMs by viewModel.serviceRequestTimeoutMs.collectAsStateWithLifecycle()
    var serviceRequestTimeoutSecondsInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(serviceRequestTimeoutMs) {
        serviceRequestTimeoutSecondsInput = (serviceRequestTimeoutMs / 1000L).toString()
    }

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

    AppScreenScaffold(
        title = "设置",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OtherConfigSection(
                title = "协议核心",
                collapsible = true,
                initiallyExpanded = false
            ) {
                Text(
                    text = "可为支持的协议选择官方 sing-box、sing-box-lx 或 Xray。XHTTP 可用 LX 或 Xray；MASQUE、AmneziaWG 和 LX 专属扩展需要选择 sing-box-lx。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                ProtocolCoreSettings.DISPLAY_PROTOCOLS.forEach { nodeType ->
                    ProtocolCoreRow(
                        label = nodeType.displayName(),
                        selectedCore = protocolCoreSettings.coreFor(nodeType),
                        availableCores = io.github.HanJason233.chimera.core.CoreBackends.registry.availableFor(nodeType),
                        onSelected = { core -> viewModel.setProtocolCore(nodeType, core) }
                    )
                }
                Text(
                    text = "内置 sing-box-lx ${BuildConfig.SING_BOX_LX_CORE_VERSION}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = "内置 Xray ${BuildConfig.XRAY_CORE_VERSION}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
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

            OtherConfigSection(title = "服务请求超时") {
                Text(
                    text = "设置本机请求后台接口和订阅源的最长等待时间。默认 25 秒，范围 5–60 秒。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = serviceRequestTimeoutSecondsInput,
                        onValueChange = { input ->
                            serviceRequestTimeoutSecondsInput = input.filter(Char::isDigit).take(2)
                        },
                        label = { Text("超时时间（秒）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    val timeoutSeconds = serviceRequestTimeoutSecondsInput.toLongOrNull()
                    TextButton(
                        enabled = timeoutSeconds?.let {
                            it in 5L..60L && it * 1000L != serviceRequestTimeoutMs
                        } == true,
                        onClick = {
                            timeoutSeconds?.let { viewModel.setServiceRequestTimeoutMs(it * 1000L) }
                        }
                    ) {
                        Text("保存")
                    }
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

        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun ProtocolCoreRow(
    label: String,
    selectedCore: ProxyCore,
    availableCores: List<ProxyCore>,
    onSelected: (ProxyCore) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            modifier = Modifier.weight(2f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            availableCores.forEach { core ->
                FilterChip(
                    selected = selectedCore == core,
                    onClick = { onSelected(core) },
                    label = { Text(core.displayName) }
                )
            }
        }
    }
}

private fun NodeType.displayName(): String = when (this) {
    NodeType.VLESS -> "VLESS"
    NodeType.VMESS -> "VMess"
    NodeType.TROJAN -> "Trojan"
    NodeType.HYSTERIA2 -> "Hysteria2"
    NodeType.ANYTLS -> "AnyTLS"
    NodeType.TUIC -> "TUIC"
    NodeType.NAIVE -> "Naive"
    NodeType.WIREGUARD -> "WireGuard"
    NodeType.MASQUE -> "MASQUE"
    NodeType.SHADOWSOCKS -> "Shadowsocks"
    NodeType.SOCKS -> "SOCKS"
    NodeType.HTTP -> "HTTP"
    NodeType.UNKNOWN -> "Unknown"
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
