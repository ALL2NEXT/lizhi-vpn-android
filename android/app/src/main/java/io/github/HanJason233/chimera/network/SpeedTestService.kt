package io.github.HanJason233.chimera.network

import android.util.Log
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import io.github.HanJason233.chimera.AppConfig

private const val TAG = "SpeedTestService"

data class SpeedTestResult(
    val avgSpeedMbps: Float,
    val totalBytes: Long,
    val durationMs: Long
)

/** 在固定时长内持续传输数据，并按这段时间的总流量计算平均带宽。 */
class SpeedTestService(
    durationMs: Long = AppConfig.AUTO_TEST_BANDWIDTH_DURATION_MS,
    proxy: Proxy? = null,
    private val downloadUrl: String = AppConfig.SPEED_TEST_DOWNLOAD_URL
) {
    private val testDurationMs = durationMs.coerceAtLeast(MIN_DURATION_MS)

    private val downloadClient = NetworkClient.withUserAgent(OkHttpClient.Builder())
        .apply {
            if (proxy != null) proxy(proxy)
        }
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val uploadClient = NetworkClient.withUserAgent(OkHttpClient.Builder())
        .apply {
            if (proxy != null) proxy(proxy)
        }
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var isCancelled = false

    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        isCancelled = true
        activeCall?.cancel()
    }

    fun startDownloadTest(
        onProgress: (currentSpeedMbps: Float, progress: Float) -> Unit
    ): SpeedTestResult {
        isCancelled = false
        val request = Request.Builder().url(downloadUrl).build()
        val startedAt = System.nanoTime()
        val deadline = startedAt + testDurationMs * NANOS_PER_MILLISECOND
        var totalBytesRead = 0L
        var lastBytesRead = 0L
        var lastUpdateAt = startedAt

        Log.d(TAG, "Starting fixed-duration download test: url=$downloadUrl, durationMs=$testDurationMs")

        try {
            while (!isCancelled && System.nanoTime() < deadline) {
                val call = downloadClient.newCall(request)
                activeCall = call
                call.timeout().timeout(remainingMs(deadline), TimeUnit.MILLISECONDS)
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")

                        val source = response.body?.source()
                            ?: throw IOException("ResponseBody is null")
                        val buffer = ByteArray(8192)
                        while (!isCancelled && System.nanoTime() < deadline) {
                            val bytesRead = source.read(buffer)
                            if (bytesRead == -1) break
                            totalBytesRead += bytesRead

                            val now = System.nanoTime()
                            val updateNanos = now - lastUpdateAt
                            if (updateNanos >= PROGRESS_INTERVAL_NANOS) {
                                val currentSpeedMbps = speedMbps(
                                    totalBytesRead - lastBytesRead,
                                    updateNanos
                                )
                                onProgress(
                                    currentSpeedMbps,
                                    progress(now - startedAt)
                                )
                                lastUpdateAt = now
                                lastBytesRead = totalBytesRead
                            }
                        }
                    }
                } catch (error: IOException) {
                    if (!isCancelled && System.nanoTime() < deadline) throw error
                } finally {
                    if (activeCall === call) activeCall = null
                }
            }
        } finally {
            activeCall = null
        }

        val elapsedMs = elapsedMs(startedAt)
        val avgSpeed = speedMbps(totalBytesRead, elapsedMs * NANOS_PER_MILLISECOND)
        Log.d(TAG, "Download test finished: avg=${avgSpeed}Mbps, bytes=$totalBytesRead, durationMs=$elapsedMs")
        return SpeedTestResult(avgSpeed, totalBytesRead, elapsedMs)
    }

    fun startUploadTest(
        onProgress: (currentSpeedMbps: Float, progress: Float) -> Unit
    ): SpeedTestResult {
        isCancelled = false
        val startedAt = System.nanoTime()
        val deadline = startedAt + testDurationMs * NANOS_PER_MILLISECOND
        var totalBytesUploaded = 0L

        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            // 固定时长无法预先知道上传量，使用分块传输。
            override fun contentLength() = -1L

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(8192) { 1 }
                var lastBytesUploaded = 0L
                var lastUpdateAt = startedAt
                while (!isCancelled && System.nanoTime() < deadline) {
                    sink.write(buffer)
                    totalBytesUploaded += buffer.size

                    val now = System.nanoTime()
                    val updateNanos = now - lastUpdateAt
                    if (updateNanos >= PROGRESS_INTERVAL_NANOS) {
                        val currentSpeedMbps = speedMbps(
                            totalBytesUploaded - lastBytesUploaded,
                            updateNanos
                        )
                        onProgress(currentSpeedMbps, progress(now - startedAt))
                        lastUpdateAt = now
                        lastBytesUploaded = totalBytesUploaded
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(AppConfig.SPEED_TEST_UPLOAD_URL)
            .post(requestBody)
            .build()

        Log.d(TAG, "Starting fixed-duration upload test: url=${AppConfig.SPEED_TEST_UPLOAD_URL}, durationMs=$testDurationMs")

        val call = uploadClient.newCall(request)
        activeCall = call
        // 上传主体到时后还需要等待服务端响应，因此给请求留出少量收尾时间。
        call.timeout().timeout(testDurationMs + REQUEST_GRACE_MS, TimeUnit.MILLISECONDS)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
            }
        } catch (error: Exception) {
            if (!isCancelled && System.nanoTime() < deadline) throw error
        } finally {
            if (activeCall === call) activeCall = null
        }

        val elapsedMs = elapsedMs(startedAt)
        val avgSpeed = speedMbps(totalBytesUploaded, elapsedMs * NANOS_PER_MILLISECOND)
        Log.d(TAG, "Upload test finished: avg=${avgSpeed}Mbps, bytes=$totalBytesUploaded, durationMs=$elapsedMs")
        return SpeedTestResult(avgSpeed, totalBytesUploaded, elapsedMs)
    }

    private fun progress(elapsedNanos: Long): Float =
        (elapsedNanos.toDouble() / (testDurationMs * NANOS_PER_MILLISECOND))
            .toFloat()
            .coerceIn(0f, 1f)

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
            .coerceIn(1L, testDurationMs)

    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)

    private fun speedMbps(bytes: Long, elapsedNanos: Long): Float {
        if (bytes <= 0L || elapsedNanos <= 0L) return 0f
        return (bytes * 8.0 / elapsedNanos * 1_000_000_000.0 / 1_000_000.0).toFloat()
    }

    private companion object {
        const val MIN_DURATION_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val REQUEST_GRACE_MS = 15_000L
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
