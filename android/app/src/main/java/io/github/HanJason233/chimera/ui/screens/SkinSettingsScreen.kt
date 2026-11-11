package io.github.HanJason233.chimera.ui.screens

import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.data.model.SkinSettings
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle
import io.github.HanJason233.chimera.data.model.HomeIconMotionEffect
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.ui.components.SettingsListChoiceRow
import io.github.HanJason233.chimera.ui.components.SettingsListSectionHeader
import io.github.HanJason233.chimera.ui.components.SettingsListSwitchRow
import io.github.HanJason233.chimera.platform.LauncherIdentityPreset
import io.github.HanJason233.chimera.platform.LauncherIdentityPresets
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import java.io.File

// 固定图标、动画和粒子列宽，让各状态设置按表格纵向对齐。
@Composable
private fun HomeStateSettingsHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "状态",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "图标",
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = "动画",
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = "粒子特效",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}
@Composable
private fun HomeStateSettingsRow(
    title: String,
    imagePath: String?,
    state: VpnState,
    selectedEffect: HomeIconMotionEffect,
    selectedParticleEnabled: Boolean,
    onChoose: () -> Unit,
    onReset: () -> Unit,
    onEffectSelected: (HomeIconMotionEffect) -> Unit,
    onParticleEffectChanged: (Boolean) -> Unit
) {
    var imageMenuExpanded by remember { mutableStateOf(false) }
    var effectMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
        Box(modifier = Modifier.width(60.dp)) {
            OutlinedButton(
                onClick = { imageMenuExpanded = true },
                modifier = Modifier
                    .width(60.dp)
                    .height(52.dp),
                contentPadding = PaddingValues(2.dp)
            ) {
                SkinImagePreview(
                    imagePath = imagePath,
                    state = state,
                    contentDescription = "$title 图标，点击更换",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            DropdownMenu(
                expanded = imageMenuExpanded,
                onDismissRequest = { imageMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("选择图片") },
                    onClick = {
                        imageMenuExpanded = false
                        onChoose()
                    }
                )
                if (imagePath != null) {
                    DropdownMenuItem(
                        text = { Text("恢复默认图标") },
                        onClick = {
                            imageMenuExpanded = false
                            onReset()
                        }
                    )
                }
            }
        }
        Column(
            modifier = Modifier.width(68.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                OutlinedButton(
                    onClick = { effectMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${selectedEffect.displayName} ▾",
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                DropdownMenu(
                    expanded = effectMenuExpanded,
                    onDismissRequest = { effectMenuExpanded = false }
                ) {
                    HomeIconMotionEffect.entries.forEach { effect ->
                        DropdownMenuItem(
                            text = { Text(effect.displayName) },
                            onClick = {
                                effectMenuExpanded = false
                                onEffectSelected(effect)
                            }
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Switch(
                checked = selectedParticleEnabled,
                onCheckedChange = onParticleEffectChanged
            )
        }
    }
}

@Composable
private fun SkinColorOption(
    name: String,
    color: Color,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape),
            shape = CircleShape,
            color = if (enabled) color else color.copy(alpha = 0.38f),
            border = if (selected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            },
            onClick = onClick,
            enabled = enabled
        ) {}
        Text(
            text = name,
            fontSize = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.38f
            )
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SkinSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
    val homeModuleSettings by viewModel.homeModuleSettings.collectAsStateWithLifecycle()
    val settings = skinSettings

    fun updateGlobalSettings(update: SkinSettings.() -> SkinSettings) {
        viewModel.updateSkinSettings { current -> current.update() }
    }

    fun applyLauncherIdentity(preset: LauncherIdentityPreset) {
        val oldCustomIconPath = settings.customImagePath
        updateGlobalSettings {
            copy(
                appDisplayName = preset.displayName,
                appIconPresetId = preset.id,
                customImagePath = null
            )
        }
        if (oldCustomIconPath != null) {
            scope.launch(Dispatchers.IO) { File(oldCustomIconPath).delete() }
        }
    }

    // 主页四种连接状态图分别保存，未设置的状态回退到内置默认图片。
    fun saveHomeStateImage(
        uri: Uri?,
        assetName: String,
        previousPath: String?,
        updateImageSettings: (SkinSettings, String) -> SkinSettings
    ) {
        uri ?: return
        scope.launch {
            val copiedPath = withContext(Dispatchers.IO) {
                copySkinAsset(context, uri, assetName)
            }
            if (copiedPath == null) {
                Toast.makeText(context, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
            } else {
                if (previousPath != null && previousPath != copiedPath) {
                    withContext(Dispatchers.IO) { File(previousPath).delete() }
                }
                viewModel.updateSkinSettings { current ->
                    updateImageSettings(current, copiedPath)
                }
            }
        }
    }

    val disconnectedImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_disconnected",
            previousPath = settings.homeDisconnectedImagePath,
            updateImageSettings = { current, path -> current.copy(homeDisconnectedImagePath = path) }
        )
    }
    val connectingImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_connecting",
            previousPath = settings.homeConnectingImagePath,
            updateImageSettings = { current, path -> current.copy(homeConnectingImagePath = path) }
        )
    }
    val connectedImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_connected",
            previousPath = settings.homeConnectedImagePath,
            updateImageSettings = { current, path -> current.copy(homeConnectedImagePath = path) }
        )
    }
    val disconnectingImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_disconnecting",
            previousPath = settings.homeDisconnectingImagePath,
            updateImageSettings = { current, path -> current.copy(homeDisconnectingImagePath = path) }
        )
    }

    AppScreenScaffold(
        title = "主题设置",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            val selectedIdentity = LauncherIdentityPresets.find(settings.appIconPresetId)
            SettingsListSectionHeader("应用名称与图标")
            SettingsListChoiceRow(
                icon = Icons.Outlined.Palette,
                title = "启动器图标",
                subtitle = selectedIdentity.displayName,
                options = LauncherIdentityPresets.all,
                selected = selectedIdentity,
                optionLabel = LauncherIdentityPreset::displayName,
                onSelected = ::applyLauncherIdentity
            )

            SettingsListSectionHeader("全局外观")
            SettingsListChoiceRow(
                icon = Icons.Outlined.SwapHoriz,
                title = "导航栏样式",
                subtitle = settings.navigationStyle.displayName,
                options = HomeNavigationStyle.entries,
                selected = settings.navigationStyle,
                optionLabel = { it.displayName },
                onSelected = { style ->
                    updateGlobalSettings { copy(navigationStyle = style) }
                }
            )

            SettingsListSectionHeader("首页模块")
            SettingsListSwitchRow(
                icon = Icons.Outlined.Public,
                title = "显示 IP 出口检测",
                subtitle = "",
                checked = homeModuleSettings.showIpAddressTest,
                onCheckedChange = { enabled ->
                    viewModel.setHomeModuleSettings(
                        homeModuleSettings.copy(showIpAddressTest = enabled)
                    )
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.FolderOpen,
                title = "显示选中组",
                subtitle = "",
                checked = homeModuleSettings.showSelectedGroup,
                onCheckedChange = { enabled ->
                    viewModel.setHomeModuleSettings(
                        homeModuleSettings.copy(showSelectedGroup = enabled)
                    )
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Dns,
                title = "显示选中节点",
                subtitle = "",
                checked = homeModuleSettings.showSelectedNode,
                onCheckedChange = { enabled ->
                    viewModel.setHomeModuleSettings(
                        homeModuleSettings.copy(showSelectedNode = enabled)
                    )
                }
            )

            SettingsListSectionHeader("首页测试面板展示项")
            SettingsListSwitchRow(
                icon = Icons.Outlined.Dns,
                title = "TCPing",
                subtitle = "",
                checked = SpeedTestStage.TCPING in settings.homeTestDisplayStages,
                onCheckedChange = { enabled ->
                    updateGlobalSettings {
                        withHomeTestStageVisible(SpeedTestStage.TCPING, enabled)
                    }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Public,
                title = "204Test",
                subtitle = "",
                checked = SpeedTestStage.URL_TEST in settings.homeTestDisplayStages,
                onCheckedChange = { enabled ->
                    updateGlobalSettings {
                        withHomeTestStageVisible(SpeedTestStage.URL_TEST, enabled)
                    }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Memory,
                title = "带宽",
                subtitle = "",
                checked = SpeedTestStage.BANDWIDTH in settings.homeTestDisplayStages,
                onCheckedChange = { enabled ->
                    updateGlobalSettings {
                        withHomeTestStageVisible(SpeedTestStage.BANDWIDTH, enabled)
                    }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Route,
                title = "链路类型",
                subtitle = "",
                checked = SpeedTestStage.LINK_TYPE in settings.homeTestDisplayStages,
                onCheckedChange = { enabled ->
                    updateGlobalSettings {
                        withHomeTestStageVisible(SpeedTestStage.LINK_TYPE, enabled)
                    }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.VpnKey,
                title = "解锁检测",
                subtitle = "",
                checked = SpeedTestStage.IP_QUALITY in settings.homeTestDisplayStages,
                onCheckedChange = { enabled ->
                    updateGlobalSettings {
                        withHomeTestStageVisible(SpeedTestStage.IP_QUALITY, enabled)
                    }
                }
            )

            SettingsListSectionHeader("节点卡片设置")
            SettingsListSwitchRow(
                icon = Icons.Outlined.Dns,
                title = "显示域名/IP",
                subtitle = "",
                checked = settings.showNodeAddress,
                onCheckedChange = { enabled ->
                    updateGlobalSettings { copy(showNodeAddress = enabled) }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Numbers,
                title = "显示端口",
                subtitle = "",
                checked = settings.showNodePort,
                onCheckedChange = { enabled ->
                    updateGlobalSettings { copy(showNodePort = enabled) }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Language,
                title = "显示协议",
                subtitle = "",
                checked = settings.showNodeProtocol,
                onCheckedChange = { enabled ->
                    updateGlobalSettings { copy(showNodeProtocol = enabled) }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.VpnKey,
                title = "显示安全层",
                subtitle = "",
                checked = settings.showNodeSecurity,
                onCheckedChange = { enabled ->
                    updateGlobalSettings { copy(showNodeSecurity = enabled) }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Route,
                title = "显示传输方式",
                subtitle = "",
                checked = settings.showNodeTransport,
                onCheckedChange = { enabled ->
                    updateGlobalSettings { copy(showNodeTransport = enabled) }
                }
            )
            SettingsListSwitchRow(
                icon = Icons.Outlined.Memory,
                title = "显示核心",
                subtitle = "",
                checked = settings.showNodeCore,
                onCheckedChange = { enabled ->
                    updateGlobalSettings { copy(showNodeCore = enabled) }
                }
            )

            SettingsListSectionHeader("主页状态图标")
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeStateSettingsHeader()
                HomeStateSettingsRow(
                    title = "未连接",
                    imagePath = settings.homeDisconnectedImagePath,
                    state = VpnState.DISCONNECTED,
                    selectedEffect = settings.homeDisconnectedIconEffect,
                    selectedParticleEnabled = settings.homeDisconnectedParticleEffectEnabled,
                    onChoose = { disconnectedImagePicker.launch(arrayOf("image/*")) },
                    onReset = {
                        val oldPath = settings.homeDisconnectedImagePath
                        updateGlobalSettings { copy(homeDisconnectedImagePath = null) }
                        scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                    },
                    onEffectSelected = { effect -> updateGlobalSettings {
                        copy(homeDisconnectedIconEffect = effect)
                    } },
                    onParticleEffectChanged = { enabled -> updateGlobalSettings {
                        copy(
                            homeDisconnectedParticleEffectEnabled = enabled,
                            homeIconEffectEnabled = enabled ||
                                homeConnectingParticleEffectEnabled ||
                                homeDisconnectingParticleEffectEnabled ||
                                homeConnectedParticleEffectEnabled
                        )
                    } }
                )
                HomeStateSettingsRow(
                    title = "连接中",
                    imagePath = settings.homeConnectingImagePath,
                    state = VpnState.CONNECTING,
                    selectedEffect = settings.homeConnectingIconEffect,
                    selectedParticleEnabled = settings.homeConnectingParticleEffectEnabled,
                    onChoose = { connectingImagePicker.launch(arrayOf("image/*")) },
                    onReset = {
                        val oldPath = settings.homeConnectingImagePath
                        updateGlobalSettings { copy(homeConnectingImagePath = null) }
                        scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                    },
                    onEffectSelected = { effect -> updateGlobalSettings {
                        copy(homeConnectingIconEffect = effect)
                    } },
                    onParticleEffectChanged = { enabled -> updateGlobalSettings {
                        copy(
                            homeConnectingParticleEffectEnabled = enabled,
                            homeIconEffectEnabled = enabled ||
                                homeDisconnectedParticleEffectEnabled ||
                                homeDisconnectingParticleEffectEnabled ||
                                homeConnectedParticleEffectEnabled
                        )
                    } }
                )
                HomeStateSettingsRow(
                    title = "断开中",
                    imagePath = settings.homeDisconnectingImagePath,
                    state = VpnState.DISCONNECTING,
                    selectedEffect = settings.homeDisconnectingIconEffect,
                    selectedParticleEnabled = settings.homeDisconnectingParticleEffectEnabled,
                    onChoose = { disconnectingImagePicker.launch(arrayOf("image/*")) },
                    onReset = {
                        val oldPath = settings.homeDisconnectingImagePath
                        updateGlobalSettings { copy(homeDisconnectingImagePath = null) }
                        scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                    },
                    onEffectSelected = { effect -> updateGlobalSettings {
                        copy(homeDisconnectingIconEffect = effect)
                    } },
                    onParticleEffectChanged = { enabled -> updateGlobalSettings {
                        copy(
                            homeDisconnectingParticleEffectEnabled = enabled,
                            homeIconEffectEnabled = enabled ||
                                homeDisconnectedParticleEffectEnabled ||
                                homeConnectingParticleEffectEnabled ||
                                homeConnectedParticleEffectEnabled
                        )
                    } }
                )
                HomeStateSettingsRow(
                    title = "已连接",
                    imagePath = settings.homeConnectedImagePath,
                    state = VpnState.CONNECTED,
                    selectedEffect = settings.homeConnectedIconEffect,
                    selectedParticleEnabled = settings.homeConnectedParticleEffectEnabled,
                    onChoose = { connectedImagePicker.launch(arrayOf("image/*")) },
                    onReset = {
                        val oldPath = settings.homeConnectedImagePath
                        updateGlobalSettings { copy(homeConnectedImagePath = null) }
                        scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                    },
                    onEffectSelected = { effect -> updateGlobalSettings {
                        copy(homeConnectedIconEffect = effect)
                    } },
                    onParticleEffectChanged = { enabled -> updateGlobalSettings {
                        copy(
                            homeConnectedParticleEffectEnabled = enabled,
                            homeIconEffectEnabled = enabled ||
                                homeDisconnectedParticleEffectEnabled ||
                                homeConnectingParticleEffectEnabled ||
                                homeDisconnectingParticleEffectEnabled
                        )
                    } }
                )
            }

            SettingsListSectionHeader("配色")
            val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val isDarkTheme = isSystemInDarkTheme()
            val useMonetColors = settings.monetEnabled && monetSupported
            val monetSwatchColor = when {
                !monetSupported -> MaterialTheme.colorScheme.outline
                isDarkTheme -> dynamicDarkColorScheme(context).primary
                else -> dynamicLightColorScheme(context).primary
            }
            val monochromeSwatchColor = if (isDarkTheme) Color(0xFFC0C0C0) else Color.Black
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SkinColorOption(
                    name = "莫奈取色",
                    color = monetSwatchColor,
                    selected = useMonetColors,
                    enabled = monetSupported,
                    onClick = { updateGlobalSettings { copy(monetEnabled = true) } }
                )
                SkinColorOption(
                    name = "黑白配色",
                    color = monochromeSwatchColor,
                    selected = !useMonetColors,
                    onClick = { updateGlobalSettings { copy(monetEnabled = false) } }
                )
            }
        }
    }
}

private fun SkinSettings.withHomeTestStageVisible(
    stage: SpeedTestStage,
    visible: Boolean
): SkinSettings = copy(
    homeTestDisplayStages = SpeedTestStage.entries.filter { candidate ->
        if (candidate == stage) visible else candidate in homeTestDisplayStages
    }
)
