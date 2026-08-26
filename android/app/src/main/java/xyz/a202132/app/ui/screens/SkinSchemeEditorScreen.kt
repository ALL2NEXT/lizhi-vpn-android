package xyz.a202132.app.ui.screens

import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.data.model.SkinScheme
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel
import java.io.File
import java.util.Locale

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
    defaultImageRes: Int,
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
            defaultImageRes = defaultImageRes,
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
            Text(
                text = if (imagePath == null) "使用默认图片" else "已使用自定义图片",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        AppScreenScaffold(title = "编辑皮肤方案", onBack = onBack) {
            Text(
                text = "正在加载方案…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (scheme.isDefault) {
        AppScreenScaffold(
            title = scheme.name,
            subtitle = "内置默认方案",
            onBack = onBack
        ) {
            Text(
                text = "默认方案不可编辑。请新增方案后进行自定义。",
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
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val previousPath = scheme.customImagePath
        scope.launch {
            val copiedPath = withContext(Dispatchers.IO) {
                copySkinImage(context, uri, scheme.id)
            }
            if (copiedPath == null) {
                Toast.makeText(context, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
            } else {
                if (previousPath != null && previousPath != copiedPath) {
                    withContext(Dispatchers.IO) { File(previousPath).delete() }
                }
                viewModel.upsertSkinScheme(
                    scheme.copy(customImagePath = copiedPath)
                )
            }
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
        title = "编辑方案",
        subtitle = scheme.name,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SkinImagePreview(
                imagePath = scheme.customImagePath,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "方案信息",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("方案名称") },
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
                        text = "方案图片",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "图片会显示在左侧设置栏顶部，建议选择横向图片。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                            Text("选择图片")
                        }
                        if (scheme.customImagePath != null) {
                            OutlinedButton(
                                onClick = {
                                    val oldPath = scheme.customImagePath
                                    viewModel.upsertSkinScheme(scheme.copy(customImagePath = null))
                                    scope.launch(Dispatchers.IO) {
                                        File(oldPath).delete()
                                    }
                                }
                            ) {
                                Text("使用默认图片")
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
                        text = "主页状态图片",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "分别设置主页未连接、连接中和已连接时显示的图片，未设置时使用默认图片。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    HomeStateImageRow(
                        title = "未连接",
                        imagePath = scheme.homeDisconnectedImagePath,
                        defaultImageRes = xyz.a202132.app.R.drawable.btn_disconnected,
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
                        defaultImageRes = xyz.a202132.app.R.drawable.btn_connecting,
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
                        defaultImageRes = xyz.a202132.app.R.drawable.btn_connected,
                        onChoose = { connectedImagePicker.launch(arrayOf("image/*")) },
                        onReset = {
                            val oldPath = scheme.homeConnectedImagePath
                            viewModel.upsertSkinScheme(scheme.copy(homeConnectedImagePath = null))
                            scope.launch(Dispatchers.IO) { oldPath?.let { File(it).delete() } }
                        }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "莫奈取色",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (monetSupported) {
                                    "使用系统壁纸生成的动态配色。"
                                } else {
                                    "当前系统不支持，将使用备用基础颜色；Android 12 及以上可用。"
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = scheme.monetEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.upsertSkinScheme(
                                    scheme.copy(monetEnabled = enabled)
                                )
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (scheme.monetEnabled) "备用基础颜色" else "基础颜色",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (scheme.monetEnabled) {
                            "系统不支持莫奈取色时使用，也用于颜色预览。"
                        } else {
                            "用于按钮、选中状态、开关和其他强调元素。"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        EDITOR_PRESET_COLORS.forEach { (_, colorArgb) ->
                            Surface(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape),
                                shape = CircleShape,
                                color = Color(colorArgb),
                                border = if (scheme.baseColorArgb == colorArgb) {
                                    BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                },
                                onClick = {
                                    colorInput = colorArgb.toSkinHex()
                                    colorError = false
                                    viewModel.upsertSkinScheme(
                                        scheme.copy(baseColorArgb = colorArgb)
                                    )
                                }
                            ) {}
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
                        supportingText = {
                            if (colorError) {
                                Text("请输入 6 位十六进制颜色代码")
                            } else {
                                Text("支持 #RRGGBB 格式")
                            }
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
                                    scheme.copy(baseColorArgb = color)
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
