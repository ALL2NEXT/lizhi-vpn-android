package io.github.HanJason233.chimera.ui.components

import android.graphics.BitmapFactory
import android.graphics.Movie
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.data.model.VpnState

private sealed interface DecodedImage {
    data class Animated(val movie: Movie) : DecodedImage
    data class Static(val bitmap: ImageBitmap) : DecodedImage
}

/** Displays a selected GIF or static image and falls back to the matching VPN state icon. */
@Composable
fun AnimatedImage(
    imagePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: String? = null,
    fallbackContent: @Composable () -> Unit
) {
    val decoded by produceState<DecodedImage?>(
        initialValue = null,
        key1 = imagePath
    ) {
        value = withContext(Dispatchers.IO) { decodeFile(imagePath) }
    }

    when (val image = decoded) {
        is DecodedImage.Animated -> AnimatedMovie(
            movie = image.movie,
            modifier = modifier,
            contentScale = contentScale,
            contentDescription = contentDescription
        )
        is DecodedImage.Static -> Image(
            bitmap = image.bitmap,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
        null -> fallbackContent()
    }
}

@Composable
fun HomeConnectStateIcon(
    imagePath: String?,
    state: VpnState,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val icon = when (state) {
        VpnState.DISCONNECTED -> Icons.Outlined.VpnKey
        VpnState.CONNECTING -> Icons.Outlined.Sync
        VpnState.DISCONNECTING -> Icons.Outlined.SyncDisabled
        VpnState.CONNECTED -> Icons.Outlined.VerifiedUser
    }
    val tint = when (state) {
        VpnState.DISCONNECTED -> MaterialTheme.colorScheme.primary
        VpnState.CONNECTING, VpnState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
        VpnState.CONNECTED -> MaterialTheme.colorScheme.primary
    }
    val description = contentDescription ?: when (state) {
        VpnState.DISCONNECTED -> "点击连接 VPN"
        VpnState.CONNECTING -> "VPN 连接中"
        VpnState.DISCONNECTING -> "VPN 断开中"
        VpnState.CONNECTED -> "VPN 已连接"
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val selectedImagePath = imagePath?.takeIf(String::isNotBlank)
        if (selectedImagePath == null) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.fillMaxSize(0.56f)
            )
        } else {
            AnimatedImage(
                imagePath = selectedImagePath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                contentDescription = description,
                fallbackContent = {
                    Icon(
                        imageVector = icon,
                        contentDescription = description,
                        tint = tint,
                        modifier = Modifier.fillMaxSize(0.56f)
                    )
                }
            )
        }
    }
}

@Composable
private fun AnimatedMovie(
    movie: Movie,
    modifier: Modifier,
    contentScale: ContentScale,
    contentDescription: String?
) {
    var elapsedNanos by remember(movie) { mutableLongStateOf(0L) }
    LaunchedEffect(movie) {
        val startedAt = withFrameNanos { it }
        while (isActive) {
            elapsedNanos = withFrameNanos { it - startedAt }
        }
    }

    val accessibleModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier = accessibleModifier.clipToBounds()) {
        val sourceWidth = movie.width().toFloat().coerceAtLeast(1f)
        val sourceHeight = movie.height().toFloat().coerceAtLeast(1f)
        val scaleX = size.width / sourceWidth
        val scaleY = size.height / sourceHeight
        val (drawScaleX, drawScaleY) = when (contentScale) {
            ContentScale.Crop -> {
                val scale = maxOf(scaleX, scaleY)
                scale to scale
            }
            ContentScale.FillBounds -> scaleX to scaleY
            else -> {
                val scale = minOf(scaleX, scaleY)
                scale to scale
            }
        }
        val drawWidth = sourceWidth * drawScaleX
        val drawHeight = sourceHeight * drawScaleY
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f
        val duration = movie.duration().coerceAtLeast(1).toLong()
        movie.setTime(((elapsedNanos / 1_000_000L) % duration).toInt())

        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val saveCount = canvas.save()
            canvas.clipRect(0f, 0f, size.width, size.height)
            canvas.translate(left, top)
            canvas.scale(drawScaleX, drawScaleY)
            movie.draw(canvas, 0f, 0f)
            canvas.restoreToCount(saveCount)
        }
    }
}

private fun decodeFile(path: String): DecodedImage? {
    val movie = Movie.decodeFile(path)
    if (movie != null && movie.duration() > 0) {
        return DecodedImage.Animated(movie)
    }
    return BitmapFactory.decodeFile(path)
        ?.asImageBitmap()
        ?.let(DecodedImage::Static)
}
