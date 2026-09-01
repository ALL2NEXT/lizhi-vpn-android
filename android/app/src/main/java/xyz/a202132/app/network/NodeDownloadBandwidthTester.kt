package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.ProxyCore

/** 通过临时节点代理，在固定时长内统计下载流量。 */
internal class NodeDownloadBandwidthTester(private val context: Context) {
    suspend fun test(
        node: Node,
        downloadUrl: String,
        durationMs: Long,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int,
        core: ProxyCore
    ): Float = withContext(Dispatchers.IO) {
        val port = ServerSocket(0).use { it.localPort }
        val session = NodeTestProxyManager.createSession(
            context,
            node,
            port,
            hysteriaUpMbps,
            hysteriaDownMbps,
            core
        )
        if (!session.start()) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val client = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(downloadUrl).build()
            val effectiveDurationMs = durationMs.coerceAtLeast(MIN_DURATION_MS)
            val started = System.nanoTime()
            val deadline = started + effectiveDurationMs * NANOS_PER_MILLISECOND
            var total = 0L

            // 小文件下载完后继续发起请求，保证测速窗口由“时长”而不是文件大小决定。
            while (System.nanoTime() < deadline) {
                val call = client.newCall(request)
                call.timeout().timeout(remainingMs(deadline), TimeUnit.MILLISECONDS)
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) return@withContext 0f
                        val source = response.body?.source()
                            ?: throw IOException("ResponseBody is null")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (System.nanoTime() < deadline) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            total += read
                        }
                    }
                } catch (error: IOException) {
                    if (System.nanoTime() < deadline) throw error
                }
            }

            val elapsedNanos = ((System.nanoTime() - started)
                .coerceAtMost(effectiveDurationMs * NANOS_PER_MILLISECOND))
                .coerceAtLeast(NANOS_PER_MILLISECOND)
            (total * 8.0 / elapsedNanos * 1_000_000_000.0 / 1_000_000.0).toFloat()
        } catch (error: Exception) {
            Log.e(TAG, "Download bandwidth test failed for ${node.getDisplayName()}", error)
            0f
        } finally {
            session.stop()
        }
    }

    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)

    private companion object {
        const val TAG = "NodeBandwidthTester"
        const val MIN_DURATION_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
