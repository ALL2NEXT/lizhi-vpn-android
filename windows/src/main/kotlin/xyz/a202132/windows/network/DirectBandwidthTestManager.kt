package xyz.a202132.windows.network

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import xyz.a202132.windows.model.BandwidthTestTargets

data class DirectBandwidthResult(
    val downloadMbps: Float,
    val uploadMbps: Float
)

/** 在固定时长内统计直连网络的上下行流量。 */
class DirectBandwidthTestManager(
    private val downloadUrl: String = BandwidthTestTargets.DEFAULT_URL,
    private val uploadUrl: String = "https://speed.cloudflare.com/__up",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
) {
    fun test(
        durationMs: Int = 5_000,
        downloadUrl: String? = null
    ): DirectBandwidthResult {
        val duration = Duration.ofMillis(durationMs.coerceAtLeast(1).toLong())
        val download = testDownload(downloadUrl ?: this.downloadUrl, duration)
        val upload = testUpload(duration)
        require(download > 0f && upload > 0f) { "测速结果无效" }
        return DirectBandwidthResult(download, upload)
    }

    private fun testDownload(downloadUrl: String, duration: Duration): Float {
        val started = System.nanoTime()
        val deadline = started + duration.toNanos()
        var received = 0L

        try {
            while (System.nanoTime() < deadline) {
                val request = HttpRequest.newBuilder(URI(downloadUrl))
                    .timeout(remainingDuration(deadline))
                    .header("user-agent", USER_AGENT)
                    .GET()
                    .build()
                try {
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    require(response.statusCode() in 200..299) {
                        "下载测速返回 HTTP ${response.statusCode()}"
                    }
                    response.body().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (System.nanoTime() < deadline) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                        }
                    }
                } catch (error: IOException) {
                    if (System.nanoTime() < deadline) throw error
                }
            }
        } catch (error: IOException) {
            if (System.nanoTime() < deadline) throw error
        }

        return megabitsPerSecond(received, started, duration)
    }

    private fun testUpload(duration: Duration): Float {
        val started = System.nanoTime()
        val deadline = started + duration.toNanos()
        val uploaded = AtomicLong()
        val body = HttpRequest.BodyPublishers.ofInputStream {
            object : InputStream() {
                private val buffer = ByteArray(32 * 1024) { 1 }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (System.nanoTime() >= deadline) return -1
                    val count = minOf(length, buffer.size)
                    System.arraycopy(buffer, 0, bytes, offset, count)
                    uploaded.addAndGet(count.toLong())
                    return count
                }

                override fun read(): Int {
                    if (System.nanoTime() >= deadline) return -1
                    uploaded.incrementAndGet()
                    return 1
                }
            }
        }
        val request = HttpRequest.newBuilder(URI(uploadUrl))
            .timeout(duration.plusMillis(REQUEST_GRACE_MS))
            .header("user-agent", USER_AGENT)
            .header("content-type", "application/octet-stream")
            .POST(body)
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            require(response.statusCode() in 200..299) {
                "上传测速返回 HTTP ${response.statusCode()}"
            }
        } catch (error: IOException) {
            if (System.nanoTime() < deadline) throw error
        }

        return megabitsPerSecond(uploaded.get(), started, duration)
    }

    private fun remainingDuration(deadline: Long): Duration =
        Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(1_000_000L))

    private fun megabitsPerSecond(bytes: Long, started: Long, duration: Duration): Float {
        val elapsedNanos = (System.nanoTime() - started)
            .coerceAtMost(duration.toNanos())
            .coerceAtLeast(1L)
        if (bytes <= 0L) return 0f
        return (bytes * 8.0 / elapsedNanos * 1_000_000_000.0 / 1_000_000.0).toFloat()
    }

    private companion object {
        const val REQUEST_GRACE_MS = 15_000L
        const val USER_AGENT = "Firefly/3.0.4 (Windows)"
    }
}
