package io.github.HanJason233.chimera.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.BackendWebNavigationGroup
import io.github.HanJason233.chimera.data.model.BackendWebNavigationItem
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

@Composable
fun WebNavigationScreen(
    websiteGroups: List<BackendWebNavigationGroup>,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 16.dp
) {
    val context = LocalContext.current

    AppScreenScaffold(
        title = "导航",
        subtitle = "按类别整理的常用网站",
        onBack = onBack,
        showBackNavigation = false,
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        val visibleGroups = websiteGroups.filter { group ->
            group.title.isNotBlank() && group.items.isNotEmpty()
        }
        if (visibleGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = "暂无导航网站配置",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 72.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visibleGroups.forEachIndexed { groupIndex, group ->
                    item(
                        key = "group:$groupIndex:${group.title}",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        Text(
                            text = group.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (groupIndex == 0) 0.dp else 8.dp, bottom = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(
                        items = group.items,
                        key = { item -> "$groupIndex:${item.url}" }
                    ) { item ->
                        WebNavigationCard(
                            item = item,
                            onClick = { openWebsite(context, item.url) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebNavigationCard(
    item: BackendWebNavigationItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WebsiteFavicon(
                item = item,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WebsiteFavicon(
    item: BackendWebNavigationItem,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(item.iconBase64, item.iconMimeType) {
        item.iconBase64
            ?.takeIf { item.iconMimeType?.let { mimeType -> mimeType in SUPPORTED_NAVIGATION_ICON_TYPES } == true }
            ?.let { encoded ->
                decodeNavigationIcon(encoded, item.iconMimeType.orEmpty())
            }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = firstWebsiteCharacter(item.title),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

// 图标缺失或解码失败时，用网站名称首个字符作为客户端通用占位标识。
private fun firstWebsiteCharacter(title: String): String {
    val name = title.trim()
    if (name.isEmpty()) return "?"
    return String(Character.toChars(name.codePointAt(0))).uppercase(Locale.ROOT)
}

// 客户端只解码后台随配置下发的 Base64 图标，不再直接请求网站或第三方图标服务。
private val SUPPORTED_NAVIGATION_ICON_TYPES = setOf(
    "image/png", "image/jpeg", "image/webp", "image/gif", "image/x-icon", "image/vnd.microsoft.icon"
)

private fun decodeNavigationIcon(encoded: String, mimeType: String): Bitmap? {
    val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
    decodeRasterIcon(bytes)?.let { return it }
    if (mimeType !in setOf("image/x-icon", "image/vnd.microsoft.icon")) return null
    return decodeIcoIcon(bytes)
}

private fun decodeRasterIcon(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    // 图标由后台上传，先读取尺寸并缩小解码，避免异常尺寸图片占用过多内存。
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > 512 ||
        bounds.outHeight / sampleSize > 512 ||
        (bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize) > 512L * 512L
    ) {
        sampleSize *= 2
    }
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}.getOrNull()

private fun decodeIcoIcon(bytes: ByteArray): Bitmap? {
    if (bytes.size < 22 || bytes[0].toInt() != 0 || bytes[1].toInt() != 0 ||
        bytes[2].toInt() != 1 || bytes[3].toInt() != 0
    ) return null

    val entryCount = readUnsignedShort(bytes, 4).coerceAtMost(64)
    val entries = (0 until entryCount).mapNotNull { index ->
        val entryOffset = 6 + index * 16
        if (entryOffset + 16 > bytes.size) return@mapNotNull null
        val width = (bytes[entryOffset].toInt() and 0xff).let { if (it == 0) 256 else it }
        val height = (bytes[entryOffset + 1].toInt() and 0xff).let { if (it == 0) 256 else it }
        val imageLength = readUnsignedInt(bytes, entryOffset + 8)
        val imageOffset = readUnsignedInt(bytes, entryOffset + 12)
        if (imageLength <= 0 || imageLength > 128 * 1024 || imageOffset + imageLength > bytes.size) {
            null
        } else {
            Triple(width * height, imageOffset.toInt(), imageLength.toInt())
        }
    }.sortedByDescending { it.first }

    for ((_, imageOffset, imageLength) in entries) {
        val imageBytes = bytes.copyOfRange(imageOffset, imageOffset + imageLength)
        decodeRasterIcon(imageBytes)?.let { return it }
        decodeDibIcon(imageBytes)?.let { return it }
    }
    return null
}

private fun decodeDibIcon(bytes: ByteArray): Bitmap? = runCatching {
    if (bytes.size < 40) return@runCatching null
    val headerSize = readUnsignedInt(bytes, 0).toInt()
    val width = readInt(bytes, 4)
    val doubledHeight = readInt(bytes, 8)
    val height = (kotlin.math.abs(doubledHeight.toLong()) / 2L).toInt()
    val bitCount = readUnsignedShort(bytes, 14)
    val compression = readUnsignedInt(bytes, 16)
    val colorsUsed = readUnsignedInt(bytes, 32).toInt()
    if (
        headerSize < 40 || headerSize > bytes.size || width <= 0 || height <= 0 ||
        width > 512 || height > 512 || compression != 0L || bitCount !in setOf(1, 4, 8, 24, 32)
    ) return@runCatching null

    val paletteCount = if (bitCount <= 8) {
        (if (colorsUsed > 0) colorsUsed else 1 shl bitCount).coerceAtMost(256)
    } else {
        0
    }
    val pixelOffset = headerSize + paletteCount * 4
    if (pixelOffset > bytes.size) return@runCatching null
    val xorStride = ((width.toLong() * bitCount + 31L) / 32L) * 4L
    val maskStride = ((width.toLong() + 31L) / 32L) * 4L
    val xorBytes = xorStride * height
    val maskStart = pixelOffset.toLong() + xorBytes
    if (maskStart > bytes.size) return@runCatching null
    val maskBytes = maskStride * height
    val hasMask = maskStart + maskBytes <= bytes.size
    val topDown = doubledHeight < 0

    val palette = IntArray(paletteCount) { index ->
        val offset = headerSize + index * 4
        val blue = bytes[offset].toInt() and 0xff
        val green = bytes[offset + 1].toInt() and 0xff
        val red = bytes[offset + 2].toInt() and 0xff
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
    val hasAlphaChannel = if (bitCount == 32) {
        (0 until height).any { sourceRow ->
            (0 until width).any { x ->
                val offset = pixelOffset.toLong() + sourceRow * xorStride + x * 4L + 3L
                offset < maskStart && bytes[offset.toInt()].toInt() != 0
            }
        }
    } else {
        false
    }

    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val sourceRow = if (topDown) y else height - 1 - y
        val rowOffset = pixelOffset.toLong() + sourceRow * xorStride
        val maskRowOffset = maskStart + sourceRow * maskStride
        for (x in 0 until width) {
            val color: Int
            val alpha: Int
            when (bitCount) {
                32 -> {
                    val offset = (rowOffset + x * 4L).toInt()
                    val blue = bytes[offset].toInt() and 0xff
                    val green = bytes[offset + 1].toInt() and 0xff
                    val red = bytes[offset + 2].toInt() and 0xff
                    val sourceAlpha = bytes[offset + 3].toInt() and 0xff
                    color = (red shl 16) or (green shl 8) or blue
                    alpha = if (hasAlphaChannel) sourceAlpha else 0xff
                }
                24 -> {
                    val offset = (rowOffset + x * 3L).toInt()
                    val blue = bytes[offset].toInt() and 0xff
                    val green = bytes[offset + 1].toInt() and 0xff
                    val red = bytes[offset + 2].toInt() and 0xff
                    color = (red shl 16) or (green shl 8) or blue
                    alpha = 0xff
                }
                else -> {
                    val byteOffset = (rowOffset + x * bitCount / 8).toInt()
                    val value = bytes[byteOffset].toInt() and 0xff
                    val paletteIndex = when (bitCount) {
                        8 -> value
                        4 -> if (x % 2 == 0) value shr 4 else value and 0x0f
                        else -> (value shr (7 - x % 8)) and 1
                    }
                    color = palette.getOrElse(paletteIndex) { 0xff000000.toInt() }
                    alpha = 0xff
                }
            }
            val transparentByMask = hasMask &&
                (bytes[(maskRowOffset + x / 8).toInt()].toInt() and (1 shl (7 - x % 8))) != 0
            pixels[y * width + x] = ((if (transparentByMask) 0 else alpha) shl 24) or color
        }
    }
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}.getOrNull()

private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xffL) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xffL) shl 24)

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

private fun openWebsite(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
