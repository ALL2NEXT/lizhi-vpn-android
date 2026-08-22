package xyz.a202132.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import xyz.a202132.app.data.model.SkinScheme
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

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
        title = "皮肤方案",
        subtitle = "当前 ${skinSettings.activeScheme.name}",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "选择方案",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "默认方案不可删除。新增方案后可单独设置图片和基础颜色。",
                fontSize = 13.sp,
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
                Text("新增方案")
            }

            Divider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
            Text(
                text = "切换方案后会立即应用到按钮、选中状态和侧栏图片。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增皮肤方案") },
            text = {
                OutlinedTextField(
                    value = newSchemeName,
                    onValueChange = { newSchemeName = it },
                    label = { Text("方案名称") },
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
        AlertDialog(
            onDismissRequest = { schemeToDelete = null },
            title = { Text("删除皮肤方案") },
            text = { Text("确定删除“${scheme.name}”吗？图片和颜色设置也会一并删除。") },
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
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                SkinImagePreview(
                    imagePath = scheme.customImagePath,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(18.dp),
                    shape = CircleShape,
                    color = Color(scheme.baseColorArgb),
                    border = BorderStroke(1.dp, Color.White)
                ) {}
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scheme.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (scheme.isDefault) "内置默认方案，不可删除" else "自定义方案",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (scheme.isDefault) {
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
                        contentDescription = "编辑方案",
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
