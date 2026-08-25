package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.ProxyCore

/** Runs an isolated download test through a temporary node-specific SOCKS proxy. */
internal class NodeDownloadBandwidthTester(private val context: Context) {
    suspend fun test(
        node: Node,
        sizeMb: Int,
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
                .connectTimeout(AppConfig.SMART_CONNECT_SPEED_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(AppConfig.SMART_CONNECT_SPEED_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(AppConfig.SMART_CONNECT_SPEED_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val bytes = sizeMb.toLong() * 1_000_000L
            val request = Request.Builder()
                .url("${AppConfig.SPEED_TEST_DOWNLOAD_URL}?bytes=$bytes&r=${System.currentTimeMillis()}")
                .build()

            val start = System.currentTimeMillis()
            var total = 0L
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0f
                val source = response.body?.source() ?: return@withContext 0f
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                while (source.read(buffer).also { read = it } != -1) total += read
            }
            val durationMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            (total * 8f / 1_000_000f) / (durationMs / 1000f)
        } catch (error: Exception) {
            Log.e(TAG, "Download bandwidth test failed for ${node.getDisplayName()}", error)
            0f
        } finally {
            session.stop()
        }
    }

    private companion object {
        const val TAG = "NodeBandwidthTester"
    }
}
