package io.github.HanJason233.chimera.ui.screens

import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.platform.LauncherIdentityPreset
import io.github.HanJason233.chimera.platform.LauncherIdentityPresets
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import java.io.File
import java.util.Locale
import io.github.HanJason233.chimera.data.model.SkinThemeMode

private val EDITOR_PRESET_COLORS = listOf(
    "青绿色" to 0xFF00BFA5L,
    "蓝色" to 0xFF2979FFL,
    "紫色" to 0xFF7C4DFFL,
    "橙色" to 0xFFFF7043L,
    "粉色" to 0xFFEC407AL,
    "深灰色" to 0xFF37474FL
)

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
            color = color,
            border = if (selected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            },
            onClick = onClick
        ) {}
        Text(
            text = name,
            fontSize = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val settings = skinSettings
    var colorInput by remember(settings.baseColorArgb) {
        mutableStateOf(settings.baseColorArgb.toSkinHex())
    }
    var colorError by remember { mutableStateOf(false) }

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
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "应用名称与图标",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "选择一个组合，预览布局与手机主页图标一致。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LauncherIdentityPresets.all.forEach { preset ->
                            val selected =
                                LauncherIdentityPresets.find(settings.appIconPresetId).id == preset.id
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(104.dp),
                                onClick = { applyLauncherIdentity(preset) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    AndroidView(
                                        factory = { viewContext ->
                                            ImageView(viewContext).apply {
                                                setImageResource(preset.previewIconResource)
                                                scaleType = ImageView.ScaleType.FIT_CENTER
                                                contentDescription = preset.displayName
                                            }
                                        },
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Text(
                                        text = preset.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "全局外观设置",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "此处修改会立即应用到整个应用。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "主题模式",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SkinThemeMode.entries.forEach { mode ->
                            // “跟随系统”文字较长，增加按钮宽度并保持单行显示。
                            val buttonModifier = Modifier.weight(
                                if (mode == SkinThemeMode.SYSTEM) 1.5f else 1f
                            )
                            if (settings.themeMode == mode) {
                                Button(
                                    onClick = {
                                        updateGlobalSettings { copy(themeMode = mode) }
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(mode.displayName(), maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        updateGlobalSettings { copy(themeMode = mode) }
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(mode.displayName(), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "导航栏样式",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "经典、胶囊式或原生式；选择后立即全局生效。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HomeNavigationStyle.entries.forEach { style ->
                            val buttonModifier = Modifier.weight(1f)
                            if (settings.navigationStyle == style) {
                                Button(
                                    onClick = {
                                        updateGlobalSettings { copy(navigationStyle = style) }
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(style.displayName, fontSize = 12.sp, maxLines = 1)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        updateGlobalSettings { copy(navigationStyle = style) }
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(style.displayName, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "节点标签显示",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "这些选项全局生效。全部关闭后隐藏节点标签。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NodeDisplaySettingsSwitchRow("显示协议", settings.showNodeProtocol) {
                        updateGlobalSettings { copy(showNodeProtocol = it) }
                    }
                    NodeDisplaySettingsSwitchRow("显示安全层", settings.showNodeSecurity) {
                        updateGlobalSettings { copy(showNodeSecurity = it) }
                    }
                    NodeDisplaySettingsSwitchRow("显示传输方式", settings.showNodeTransport) {
                        updateGlobalSettings { copy(showNodeTransport = it) }
                    }
                    NodeDisplaySettingsSwitchRow("显示核心", settings.showNodeCore) {
                        updateGlobalSettings { copy(showNodeCore = it) }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "主页状态",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HomeStateSettingsHeader()
                    Spacer(modifier = Modifier.height(8.dp))
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
                    Spacer(modifier = Modifier.height(6.dp))
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
                    Spacer(modifier = Modifier.height(6.dp))
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
                    Spacer(modifier = Modifier.height(6.dp))
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
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "颜色",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "莫奈色会根据系统壁纸自适应（Android 12 及以上）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val monetSwatchColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            dynamicLightColorScheme(context).primary
                        } else {
                            Color(settings.baseColorArgb)
                        }
                        SkinColorOption(
                            name = "莫奈色",
                            color = monetSwatchColor,
                            selected = settings.monetEnabled,
                            onClick = {
                                updateGlobalSettings { copy(monetEnabled = true) }
                            }
                        )
                        EDITOR_PRESET_COLORS.forEach { (name, colorArgb) ->
                            SkinColorOption(
                                name = name,
                                color = Color(colorArgb),
                                selected = !settings.monetEnabled && settings.baseColorArgb == colorArgb,
                                onClick = {
                                    colorInput = colorArgb.toSkinHex()
                                    colorError = false
                                    updateGlobalSettings {
                                        copy(baseColorArgb = colorArgb, monetEnabled = false)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = colorInput,
                        onValueChange = { value ->
                            val normalizedValue = value
                                .uppercase(Locale.US)
                                .filter { it == '#' || it in '0'..'9' || it in 'A'..'F' }
                                .take(7)
                            colorInput = normalizedValue
                            val color = parseSkinColor(normalizedValue)
                            colorError = normalizedValue.length == 7 && color == null
                            if (color != null) {
                                updateGlobalSettings {
                                    copy(baseColorArgb = color, monetEnabled = false)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("颜色代码") },
                        placeholder = { Text("例如 #2979FF") },
                        supportingText = {
                            Text(
                                if (colorError) "请输入 6 位十六进制颜色代码"
                                else "输入完整颜色代码后立即生效"
                            )
                        },
                        isError = colorError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                }
            }

        }
    }
}

@Composable
private fun NodeDisplaySettingsSwitchRow(
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun SkinThemeMode.displayName(): String = when (this) {
    SkinThemeMode.LIGHT -> "明亮"
    SkinThemeMode.DARK -> "暗黑"
    SkinThemeMode.SYSTEM -> "跟随系统"
}
