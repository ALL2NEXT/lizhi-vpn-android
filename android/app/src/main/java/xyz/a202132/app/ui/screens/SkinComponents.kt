package xyz.a202132.app.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.R
import java.io.File
import java.util.Locale

@Composable
internal fun SkinImagePreview(
    imagePath: String?,
    modifier: Modifier = Modifier,
    defaultImageRes: Int = R.drawable.drawer_wallpaper,
    contentDescription: String = "皮肤图片"
) {
    val customImage by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = imagePath
    ) {
        value = withContext(Dispatchers.IO) {
            imagePath
                ?.takeIf { it.isNotBlank() }
                ?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val image = customImage
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "当前皮肤图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = defaultImageRes),
                contentDescription = "默认$contentDescription",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

internal fun copySkinImage(context: Context, uri: Uri, schemeId: String): String? {
    return copySkinAsset(context, uri, schemeId, "wallpaper")
}

/** 将方案中的主页状态图复制到应用私有目录，避免不同方案互相覆盖。 */
internal fun copySkinAsset(
    context: Context,
    uri: Uri,
    schemeId: String,
    assetName: String
): String? {
    val directory = File(context.filesDir, "skin").apply { mkdirs() }
    val safeSchemeId = schemeId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val safeAssetName = assetName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val target = File(directory, "custom_${safeAssetName}_$safeSchemeId")
    val temporary = File(directory, "custom_${safeAssetName}_$safeSchemeId.tmp")
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

internal fun parseSkinColor(value: String): Long? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return null
    return runCatching { 0xFF000000L or hex.toLong(16) }.getOrNull()
}

internal fun Long.toSkinHex(): String {
    return "#%06X".format(Locale.US, (this and 0x00FFFFFFL))
}
