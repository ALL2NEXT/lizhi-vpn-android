package io.github.HanJason233.chimera.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.HomeConnectStateIcon
import java.io.File

@Composable
internal fun SkinImagePreview(
    imagePath: String?,
    state: VpnState,
    modifier: Modifier = Modifier,
    contentDescription: String = "样式图片"
) {
    HomeConnectStateIcon(
        imagePath = imagePath,
        state = state,
        modifier = modifier,
        contentDescription = contentDescription
    )
}

/** 将全局主页状态图复制到应用私有目录。 */
internal fun copySkinAsset(
    context: Context,
    uri: Uri,
    assetName: String
): String? {
    val directory = File(context.filesDir, "skin").apply { mkdirs() }
    val safeAssetName = assetName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val target = File(directory, "custom_${safeAssetName}_global")
    val temporary = File(directory, "custom_${safeAssetName}_global.tmp")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target.absolutePath
    }.getOrNull()
}
