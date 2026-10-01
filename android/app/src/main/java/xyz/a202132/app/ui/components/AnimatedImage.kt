package xyz.a202132.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Movie
import androidx.annotation.RawRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private sealed interface DecodedImage {
    data class Animated(val movie: Movie) : DecodedImage
    data class Static(val bitmap: ImageBitmap) : DecodedImage
}

/** Displays a GIF when the selected image is animated and static images otherwise. */
@Composable
fun AnimatedImage(
    imagePath: String?,
    @RawRes defaultResource: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val decoded by produceState<DecodedImage?>(
        initialValue = null,
        key1 = imagePath,
        key2 = defaultResource
    ) {
        value = withContext(Dispatchers.IO) {
            imagePath
                ?.takeIf(String::isNotBlank)
                ?.let(::decodeFile)
                ?: decodeResource(context, defaultResource)
        }
    }

    val semanticsModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    when (val image = decoded) {
        is DecodedImage.Animated -> AnimatedMovie(
            movie = image.movie,
            modifier = semanticsModifier,
            contentScale = contentScale
        )
        is DecodedImage.Static -> Image(
            bitmap = image.bitmap,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = semanticsModifier
        )
        null -> Canvas(modifier = semanticsModifier) { }
    }
}

@Composable
private fun AnimatedMovie(
    movie: Movie,
    modifier: Modifier,
    contentScale: ContentScale
) {
    var elapsedNanos by remember(movie) { mutableLongStateOf(0L) }
    LaunchedEffect(movie) {
        val startedAt = withFrameNanos { it }
        while (isActive) {
            elapsedNanos = withFrameNanos { it - startedAt }
        }
    }

    Canvas(modifier = modifier.clipToBounds()) {
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

private fun decodeResource(context: Context, @RawRes resourceId: Int): DecodedImage? {
    val movie = context.resources.openRawResource(resourceId).use { Movie.decodeStream(it) }
    if (movie != null && movie.duration() > 0) {
        return DecodedImage.Animated(movie)
    }
    return BitmapFactory.decodeResource(context.resources, resourceId)
        ?.asImageBitmap()
        ?.let(DecodedImage::Static)
}
