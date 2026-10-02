package io.github.HanJason233.chimera.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import io.github.HanJason233.chimera.data.model.SkinScheme
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.platform.LauncherIdentityPresets
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun SkinScreen(
    viewModel: MainViewModel,
    onOpenEditor: (String) -> Unit,
    onBack: () -> Unit
) {
    val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var newSchemeName by remember { mutableStateOf("") }
    var schemeToDelete by remember { mutableStateOf<SkinScheme?>(null) }

    AppScreenScaffold(
        title = "主题",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "主题方案",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "每个主题都可以单独设置应用内名称、图标、配色和主页状态图。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            skinSettings.schemes.forEach { scheme ->
                SkinSchemeCard(
                    scheme = scheme,
                    selected = scheme.id == skinSettings.activeSchemeId,
                    onSelect = { viewModel.setActiveSkinScheme(scheme.id) },
                    onEdit = { onOpenEditor(scheme.id) },
                    onDelete = { schemeToDelete = scheme }
                )
            }

            OutlinedButton(
                onClick = {
                    newSchemeName = ""
                    showAddDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("新增主题")
            }

        }
    }

    if (showAddDialog) {
        AppAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增主题") },
            text = {
                OutlinedTextField(
                    value = newSchemeName,
                    onValueChange = { newSchemeName = it },
                    label = { Text("主题名称") },
                    placeholder = { Text("例如：深海蓝") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    enabled = newSchemeName.trim().isNotEmpty(),
                    onClick = {
                        val scheme = SkinScheme.create(newSchemeName)
                        viewModel.upsertSkinScheme(scheme, activate = true)
                        showAddDialog = false
                        onOpenEditor(scheme.id)
                    }
                ) {
                    Text("继续设置")
                }
            }
        )
    }

    schemeToDelete?.let { scheme ->
        AppAlertDialog(
            onDismissRequest = { schemeToDelete = null },
            title = { Text("删除主题") },
            text = { Text("确定删除“${scheme.name}”吗？自定义图标和颜色设置也会一并删除。") },
            dismissButton = {
                TextButton(onClick = { schemeToDelete = null }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val imagePaths = listOfNotNull(
                            scheme.customImagePath,
                            scheme.homeDisconnectedImagePath,
                            scheme.homeConnectingImagePath,
                            scheme.homeDisconnectingImagePath,
                            scheme.homeConnectedImagePath
                        )
                        viewModel.deleteSkinScheme(scheme.id)
                        scope.launch(Dispatchers.IO) {
                            imagePaths.forEach { File(it).delete() }
                        }
                        schemeToDelete = null
                    }
                ) {
                    Text("删除")
                }
            }
        )
    }
}

@Composable
private fun SkinSchemeCard(
    scheme: SkinScheme,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val previewColor = if (scheme.monetEnabled && monetSupported) {
        dynamicLightColorScheme(LocalContext.current).primary
    } else {
        Color(scheme.baseColorArgb)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape),
                shape = CircleShape,
                color = previewColor,
                border = BorderStroke(1.dp, Color.White)
            ) {}
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scheme.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = LauncherIdentityPresets.find(scheme.appIconPresetId).displayName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (scheme.isDefault) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "编辑主题",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "默认方案不可删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "编辑主题",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除方案",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "当前方案",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(20.dp)
                )
            }
        }
    }
}
