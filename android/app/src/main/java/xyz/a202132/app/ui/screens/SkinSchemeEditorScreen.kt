package xyz.a202132.app.ui.screens

import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.data.model.SkinScheme
import xyz.a202132.app.data.model.HomeNavigationStyle
import xyz.a202132.app.data.model.HomeIconEffect
import xyz.a202132.app.data.model.DialogMaterialVersion
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.platform.LauncherIdentityPreset
import xyz.a202132.app.platform.LauncherIdentityPresets
import xyz.a202132.app.viewmodel.MainViewModel
import java.io.File
import java.util.Locale
import xyz.a202132.app.data.model.SkinThemeMode

private val EDITOR_PRESET_COLORS = listOf(
    "青绿色" to 0xFF00BFA5L,
    "蓝色" to 0xFF2979FFL,
    "紫色" to 0xFF7C4DFFL,
    "橙色" to 0xFFFF7043L,
    "粉色" to 0xFFEC407AL,
    "深灰色" to 0xFF37474FL
)

@Composable
private fun HomeStateImageRow(
    title: String,
    imagePath: String?,
    state: VpnState,
    onChoose: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkinImagePreview(
            imagePath = imagePath,
            state = state,
            contentDescription = title,
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChoose) {
                    Text("选择")
                }
                if (imagePath != null) {
                    OutlinedButton(onClick = onReset) {
                        Text("恢复默认")
                    }
                }
            }
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
fun SkinSchemeEditorScreen(
    viewModel: MainViewModel,
    schemeId: String,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
    val scheme = skinSettings.schemes.firstOrNull { it.id == schemeId }

    if (scheme == null) {
        AppScreenScaffold(title = "编辑主题", onBack = onBack) {
            Text(
                text = "正在加载主题…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var nameInput by remember(scheme.id, scheme.name) { mutableStateOf(scheme.name) }
    var colorInput by remember(scheme.id, scheme.baseColorArgb) {
        mutableStateOf(scheme.baseColorArgb.toSkinHex())
    }
    var colorError by remember { mutableStateOf(false) }
    val isActive = scheme.id == skinSettings.activeSchemeId

    fun applyLauncherIdentity(preset: LauncherIdentityPreset) {
        val oldCustomIconPath = scheme.customImagePath
        viewModel.upsertSkinScheme(
            scheme.copy(
                appDisplayName = preset.displayName,
                appIconPresetId = preset.id,
                customImagePath = null
            )
        )
        if (oldCustomIconPath != null) {
            scope.launch(Dispatchers.IO) { File(oldCustomIconPath).delete() }
        }
    }

    // 主页三种连接状态图分别保存，未设置的状态回退到内置默认图片。
    fun saveHomeStateImage(
        uri: Uri?,
        assetName: String,
        previousPath: String?,
        updateScheme: (String) -> SkinScheme
    ) {
        uri ?: return
        scope.launch {
            val copiedPath = withContext(Dispatchers.IO) {
                copySkinAsset(context, uri, scheme.id, assetName)
            }
            if (copiedPath == null) {
                Toast.makeText(context, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
            } else {
                if (previousPath != null && previousPath != copiedPath) {
                    withContext(Dispatchers.IO) { File(previousPath).delete() }
                }
                viewModel.upsertSkinScheme(updateScheme(copiedPath))
            }
        }
    }

    val disconnectedImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_disconnected",
            previousPath = scheme.homeDisconnectedImagePath,
            updateScheme = { path -> scheme.copy(homeDisconnectedImagePath = path) }
        )
    }
    val connectingImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_connecting",
            previousPath = scheme.homeConnectingImagePath,
            updateScheme = { path -> scheme.copy(homeConnectingImagePath = path) }
        )
    }
    val connectedImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        saveHomeStateImage(
            uri = uri,
            assetName = "home_connected",
            previousPath = scheme.homeConnectedImagePath,
            updateScheme = { path -> scheme.copy(homeConnectedImagePath = path) }
        )
    }

    AppScreenScaffold(
        title = "编辑主题",
        subtitle = scheme.name,
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
                        text = "名称与图标",
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
                                LauncherIdentityPresets.find(scheme.appIconPresetId).id == preset.id
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
                        text = "主题方案信息",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("主题名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val name = nameInput.trim()
                            if (name.isNotEmpty()) {
                                viewModel.upsertSkinScheme(scheme.copy(name = name))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存名称")
                    }
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
                            if (scheme.themeMode == mode) {
                                Button(
                                    onClick = {
                                        viewModel.upsertSkinScheme(scheme.copy(themeMode = mode))
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(mode.displayName(), maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.upsertSkinScheme(scheme.copy(themeMode = mode))
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
                        text = "经典、胶囊式或原生式；当前主题会立即生效。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HomeNavigationStyle.entries.forEach { style ->
                            val buttonModifier = Modifier.weight(1f)
                            if (scheme.navigationStyle == style) {
                                Button(
                                    onClick = {
                                        viewModel.upsertSkinScheme(
                                            scheme.copy(navigationStyle = style)
                                        )
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(style.displayName, fontSize = 12.sp, maxLines = 1)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.upsertSkinScheme(
                                            scheme.copy(navigationStyle = style)
                                        )
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(style.displayName, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "弹窗样式",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "选择标准弹窗和自定义弹窗容器的 Compose Material 实现。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DialogMaterialVersion.entries.forEach { version ->
                            val buttonModifier = Modifier.weight(1f)
                            if (scheme.dialogMaterialVersion == version) {
                                Button(
                                    onClick = {
                                        viewModel.upsertSkinScheme(
                                            scheme.copy(dialogMaterialVersion = version)
                                        )
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(version.displayName, maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.upsertSkinScheme(
                                            scheme.copy(dialogMaterialVersion = version)
                                        )
                                    },
                                    modifier = buttonModifier
                                ) {
                                    Text(version.displayName, maxLines = 1, softWrap = false)
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
                        text = "主页状态",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    HomeStateImageRow(
                        title = "未连接",
                        imagePath = scheme.homeDisconnectedImagePath,
                        state = VpnState.DISCONNECTED,
                        onChoose = { disconnectedImagePicker.launch(arrayOf("image/*")) },
                        onReset = {
                            val oldPath = scheme.homeDisconnectedImagePath
                            viewModel.upsertSkinScheme(scheme.copy(homeDisconnectedImagePath = null))
                            scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HomeStateImageRow(
                        title = "连接中",
                        imagePath = scheme.homeConnectingImagePath,
                        state = VpnState.CONNECTING,
                        onChoose = { connectingImagePicker.launch(arrayOf("image/*")) },
                        onReset = {
                            val oldPath = scheme.homeConnectingImagePath
                            viewModel.upsertSkinScheme(scheme.copy(homeConnectingImagePath = null))
                            scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HomeStateImageRow(
                        title = "已连接",
                        imagePath = scheme.homeConnectedImagePath,
                        state = VpnState.CONNECTED,
                        onChoose = { connectedImagePicker.launch(arrayOf("image/*")) },
                        onReset = {
                            val oldPath = scheme.homeConnectedImagePath
                            viewModel.upsertSkinScheme(scheme.copy(homeConnectedImagePath = null))
                            scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "主页图标特效",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "控制连接图标周围的动画效果",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = scheme.homeIconEffectEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.upsertSkinScheme(
                                    scheme.copy(homeIconEffectEnabled = enabled)
                                )
                            }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "连接中图标旋转",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "连接中或断开中时旋转主页图标",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = scheme.homeIconRotationEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.upsertSkinScheme(
                                    scheme.copy(homeIconRotationEnabled = enabled)
                                )
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeIconEffect.entries.forEach { effect ->
                            FilterChip(
                                selected = scheme.homeIconEffect == effect,
                                onClick = {
                                    viewModel.upsertSkinScheme(
                                        scheme.copy(homeIconEffect = effect)
                                    )
                                },
                                label = { Text(effect.displayName) }
                            )
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
                            Color(scheme.baseColorArgb)
                        }
                        SkinColorOption(
                            name = "莫奈色",
                            color = monetSwatchColor,
                            selected = scheme.monetEnabled,
                            onClick = {
                                viewModel.upsertSkinScheme(scheme.copy(monetEnabled = true))
                            }
                        )
                        EDITOR_PRESET_COLORS.forEach { (name, colorArgb) ->
                            SkinColorOption(
                                name = name,
                                color = Color(colorArgb),
                                selected = !scheme.monetEnabled && scheme.baseColorArgb == colorArgb,
                                onClick = {
                                    colorInput = colorArgb.toSkinHex()
                                    colorError = false
                                    viewModel.upsertSkinScheme(
                                        scheme.copy(baseColorArgb = colorArgb, monetEnabled = false)
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = colorInput,
                        onValueChange = { value ->
                            colorInput = value
                                .uppercase(Locale.US)
                                .filter { it == '#' || it in '0'..'9' || it in 'A'..'F' }
                                .take(7)
                            colorError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("颜色代码") },
                        placeholder = { Text("例如 #2979FF") },
                        supportingText = if (colorError) {
                            { Text("请输入 6 位十六进制颜色代码") }
                        } else {
                            null
                        },
                        isError = colorError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val color = parseSkinColor(colorInput)
                            if (color == null) {
                                colorError = true
                            } else {
                                colorError = false
                                viewModel.upsertSkinScheme(
                                    scheme.copy(baseColorArgb = color, monetEnabled = false)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用颜色")
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            Button(
                enabled = !isActive,
                onClick = { viewModel.setActiveSkinScheme(scheme.id) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isActive) "当前方案正在使用" else "应用此方案")
            }
        }
    }
}

private fun SkinThemeMode.displayName(): String = when (this) {
    SkinThemeMode.LIGHT -> "明亮"
    SkinThemeMode.DARK -> "暗黑"
    SkinThemeMode.SYSTEM -> "跟随系统"
}
