package xyz.a202132.windows.network

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class DirectBandwidthResult(
    val downloadMbps: Float,
    val uploadMbps: Float
)

class DirectBandwidthTestManager(
    private val downloadUrl: String = "https://speed.cloudflare.com/__down",
    private val uploadUrl: String = "https://speed.cloudflare.com/__up",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
) {
    fun test(sizeBytes: Int = 10_000_000, timeoutMs: Int = 25_000): DirectBandwidthResult {
        require(sizeBytes > 0) { "测速数据大小必须大于 0" }
        val timeout = Duration.ofMillis(timeoutMs.coerceAtLeast(1_000).toLong())
        val download = testDownload(sizeBytes, timeout)
        val upload = testUpload(sizeBytes, timeout)
        require(download > 0f && upload > 0f) { "测速结果无效" }
        return DirectBandwidthResult(download, upload)
    }

    private fun testDownload(sizeBytes: Int, timeout: Duration): Float {
        val request = HttpRequest.newBuilder(
            URI("$downloadUrl?bytes=$sizeBytes&r=${System.nanoTime()}")
        )
            .timeout(timeout)
            .header("user-agent", USER_AGENT)
            .GET()
            .build()
        val started = System.nanoTime()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() in 200..299) { "下载测速返回 HTTP ${response.statusCode()}" }
        val received = response.body().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (total < sizeBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), sizeBytes - total).toInt())
                if (count < 0) break
                total += count
            }
            total
        }
        return megabitsPerSecond(received, started)
    }

    private fun testUpload(sizeBytes: Int, timeout: Duration): Float {
        val body = ByteArray(sizeBytes) { 1 }
        val request = HttpRequest.newBuilder(URI(uploadUrl))
            .timeout(timeout)
            .header("user-agent", USER_AGENT)
            .header("content-type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val started = System.nanoTime()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        require(response.statusCode() in 200..299) { "上传测速返回 HTTP ${response.statusCode()}" }
        return megabitsPerSecond(sizeBytes.toLong(), started)
    }

    private fun megabitsPerSecond(bytes: Long, started: Long): Float {
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        return if (bytes <= 0L || seconds <= 0.0) 0f
        else (bytes * 8.0 / seconds / 1_000_000.0).toFloat()
    }

    private companion object {
        const val USER_AGENT = "FireflyVPN/3.0.2 (Windows)"
    }
}
