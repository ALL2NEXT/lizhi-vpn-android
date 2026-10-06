package io.github.HanJason233.chimera.feature.nodegrouping.data.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Persists user-selected personal-source icons in app-private storage. */
object PersonalSourceIconStore {
    private const val DIRECTORY = "personal_source_icons"
    private const val MAX_INPUT_BYTES = 12 * 1024 * 1024
    private const val MAX_STORED_BYTES = 1024 * 1024
    private const val MAX_DIMENSION = 256
    private val fileNamePattern = Regex("[0-9a-f]{16}-[0-9a-f-]{36}\\.png")

    /** Copies and normalizes a picked image, returning a filename safe to store in Room. */
    fun importImage(context: Context, groupId: String, uri: Uri): String? = runCatching {
        val inputBytes = context.contentResolver.openInputStream(uri)
            ?.use(::readInputLimited)
            ?: return null
        val decoded = decodeAndResize(inputBytes) ?: return null
        val png = try {
            ByteArrayOutputStream().use { output ->
                if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
                output.toByteArray()
            }
        } finally {
            decoded.recycle()
        }
        if (png.isEmpty() || png.size > MAX_STORED_BYTES) return null

        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null

        val groupToken = sha256(groupId).take(16)
        val fileName = "$groupToken-${UUID.randomUUID()}.png"
        val destination = File(directory, fileName)
        val temporary = File.createTempFile("icon-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(png) }
            if (!temporary.renameTo(destination)) return null
        } finally {
            temporary.delete()
        }
        fileName
    }.getOrNull()

    fun load(context: Context, fileName: String?): Bitmap? {
        val file = resolveFile(context, fileName) ?: return null
        return runCatching {
            if (!file.isFile || file.length() !in 1..MAX_STORED_BYTES.toLong()) {
                null
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            }
        }.getOrNull()
    }

    fun delete(context: Context, fileName: String?) {
        resolveFile(context, fileName)?.delete()
    }

    private fun resolveFile(context: Context, fileName: String?): File? {
        val safeName = fileName?.takeIf(fileNamePattern::matches) ?: return null
        return File(File(context.filesDir, DIRECTORY), safeName)
    }

    private fun decodeAndResize(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }
        val sampled = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null
        val scale = minOf(
            MAX_DIMENSION.toFloat() / sampled.width,
            MAX_DIMENSION.toFloat() / sampled.height,
            1f
        )
        if (scale >= 1f) return sampled
        return Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * scale).toInt().coerceAtLeast(1),
            (sampled.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== sampled) sampled.recycle() }
    }

    private fun readInputLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_INPUT_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
