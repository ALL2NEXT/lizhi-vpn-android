package io.github.HanJason233.chimera.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Loads the site's root favicon for a personal subscription, without sending its URL path/query. */
object SubscriptionFaviconLoader {
    private const val MAX_ICON_BYTES = 1024 * 1024
    private const val MAX_ICON_DIMENSION = 256
    private const val FAILED_RETRY_DELAY_MS = 5 * 60 * 1000L
    private const val MAX_DISK_CACHE_BYTES = 16L * 1024 * 1024
    private const val MAX_DISK_CACHE_ENTRIES = 256
    private const val DISK_CACHE_DIRECTORY = "subscription_favicons"

    private val client by lazy {
        NetworkClient.withUserAgent(OkHttpClient.Builder())
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(2 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val failedRequestCache = LruCache<String, Long>(128)

    /** Returns an origin-only favicon URL for the first HTTP(S) URL in the subscription text. */
    fun faviconUrl(subscriptionText: String?): String? {
        val source = subscriptionText
            ?.lineSequence()
            ?.mapNotNull { it.trim().toHttpUrlOrNull() }
            ?.firstOrNull { it.scheme == "http" || it.scheme == "https" }
            ?: return null

        return source.newBuilder()
            .encodedPath("/favicon.ico")
            .query(null)
            .fragment(null)
            .username("")
            .password("")
            .build()
            .toString()
    }

    /** Validates a user-specified remote image URL without changing its path or query. */
    fun customImageUrl(value: String?): String? = value
        ?.trim()
        ?.toHttpUrlOrNull()
        ?.takeIf { it.scheme == "http" || it.scheme == "https" }
        ?.toString()

    fun load(context: Context, url: String): Bitmap? {
        synchronized(bitmapCache) {
            bitmapCache.get(url)?.let { return it }
            val failedAt = failedRequestCache.get(url)
            if (failedAt != null) {
                if (SystemClock.elapsedRealtime() - failedAt < FAILED_RETRY_DELAY_MS) return null
                failedRequestCache.remove(url)
            }
        }

        val iconFile = diskCacheFile(context, url)
        val cachedBitmap = iconFile?.let { file ->
            runCatching {
                if (!file.isFile || file.length() !in 1..MAX_ICON_BYTES.toLong()) {
                    null
                } else {
                    decodeIcon(file.readBytes())
                }
            }.getOrNull()
        }
        if (cachedBitmap != null) {
            iconFile.setLastModified(System.currentTimeMillis())
            synchronized(bitmapCache) { bitmapCache.put(url, cachedBitmap) }
            return cachedBitmap
        }
        iconFile?.takeIf { it.exists() }?.delete()

        val bitmap = runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > MAX_ICON_BYTES) return@use null

                val bytes = body.byteStream().use(::readLimitedBytes) ?: return@use null
                decodeIcon(bytes)?.let { decoded ->
                    iconFile?.let { file -> persistIcon(file, bytes) }
                    decoded
                }
            }
        }.getOrNull()

        synchronized(bitmapCache) {
            if (bitmap == null) {
                failedRequestCache.put(url, SystemClock.elapsedRealtime())
            } else {
                failedRequestCache.remove(url)
                bitmapCache.put(url, bitmap)
            }
        }
        return bitmap
    }

    private fun diskCacheFile(context: Context, url: String): File? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        File(File(context.filesDir, DISK_CACHE_DIRECTORY), "$digest.icon")
    }.getOrNull()

    private fun persistIcon(file: File, bytes: ByteArray) {
        runCatching {
            val directory = file.parentFile ?: return
            if (!directory.exists() && !directory.mkdirs()) return

            val temporaryFile = File.createTempFile("favicon-", ".tmp", directory)
            try {
                temporaryFile.outputStream().use { it.write(bytes) }
                if (file.exists()) {
                    temporaryFile.delete()
                } else if (!temporaryFile.renameTo(file)) {
                    temporaryFile.delete()
                }
                file.setLastModified(System.currentTimeMillis())
                pruneDiskCache(directory)
            } finally {
                temporaryFile.delete()
            }
        }
    }

    private fun pruneDiskCache(directory: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension == "icon" }
            ?.sortedBy(File::lastModified)
            ?: return
        var totalBytes = files.sumOf(File::length)
        var remainingFiles = files.size
        for (file in files) {
            if (totalBytes <= MAX_DISK_CACHE_BYTES &&
                remainingFiles <= MAX_DISK_CACHE_ENTRIES
            ) {
                break
            }
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
                remainingFiles--
            }
        }
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ) { decoder, info, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.setTargetSampleSize(
                        sampleSizeFor(info.size.width, info.size.height)
                    )
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > MAX_ICON_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun readLimitedBytes(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_ICON_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
