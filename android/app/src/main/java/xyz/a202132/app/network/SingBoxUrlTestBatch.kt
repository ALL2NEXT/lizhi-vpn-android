package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.PortReservation
import xyz.a202132.app.core.singbox.SingBoxBackend
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.util.SingBoxConfigGenerator

/** 一个 URL Test 批次内的全部 sing-box 节点共享同一个原生运行实例。 */
internal class SingBoxUrlTestBatch(
    private val context: Context,
    private val nodes: List<Node>,
    private val hysteriaUpMbps: Int,
    private val hysteriaDownMbps: Int
) : AutoCloseable {
    // 先占住 Clash API 端口，完成配置校验后再释放给 sing-box 绑定。
    private val reservation = PortReservation()
    private val apiPort = reservation.port
    private val nodeIds = nodes.mapTo(hashSetOf(), Node::id)
    private val directory = File(context.cacheDir, "sb-urltest-${UUID.randomUUID().toString().take(8)}")
    private val closed = AtomicBoolean()
    private var server: CommandServer? = null

    suspend fun start(): SingBoxUrlTestBatch = withContext(Dispatchers.IO) {
        val config = SingBoxConfigGenerator().generateUrlTestBatchConfig(
            nodes = nodes,
            clashApiPort = apiPort,
            hysteriaUpMbps = hysteriaUpMbps,
            hysteriaDownMbps = hysteriaDownMbps
        )
        try {
            // Libbox.setup 会修改进程级状态，必须与 VPN 和其他测速会话串行初始化。
            synchronized(SingBoxBackend.setupLock) {
                SingBoxBackend.setup(context, directory)
                Libbox.checkConfig(config)
                reservation.close()
                val platform: PlatformInterface = HeadlessPlatformInterface(context)
                server = Libbox.newCommandServer(object : CommandServerHandler {
                    override fun serviceStop() {
                        Log.w(TAG, "批量 URL Test 核心请求停止")
                    }
                    override fun serviceReload() {}
                    override fun getSystemProxyStatus() = SystemProxyStatus()
                    override fun setSystemProxyEnabled(enabled: Boolean) {}
                    override fun writeDebugMessage(message: String?) {}
                }, platform).also {
                    it.start()
                    it.startOrReloadService(config, OverrideOptions())
                }
            }
            waitUntilReady()
            this@SingBoxUrlTestBatch
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun testNode(node: Node, targetUrl: String, timeoutMs: Long): UrlTestManager.ProbeResult =
        withContext(Dispatchers.IO) {
            if (closed.get() || node.id !in nodeIds) {
                return@withContext UrlTestManager.ProbeResult(-2, false)
            }
            val timeout = timeoutMs.coerceIn(500L, 60_000L).toInt()
            val encodedId = URLEncoder.encode(node.id, Charsets.UTF_8.name())
            val encodedUrl = URLEncoder.encode(targetUrl, Charsets.UTF_8.name())
            val connection = URL(
                "http://127.0.0.1:$apiPort/proxies/$encodedId/delay?timeout=$timeout&url=$encodedUrl"
            ).openConnection() as HttpURLConnection
            // 真正的测速截止时间由核心控制；这里额外预留本地 API 调度时间。
            connection.connectTimeout = timeout + API_TIMEOUT_GRACE_MS
            connection.readTimeout = timeout + API_TIMEOUT_GRACE_MS
            connection.instanceFollowRedirects = false
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val delayMs = connection.inputStream.bufferedReader().use { reader ->
                        JsonParser.parseReader(reader).asJsonObject.get("delay")?.asInt
                    }
                    if (delayMs != null && delayMs > 0) {
                        UrlTestManager.ProbeResult(delayMs, false)
                    } else {
                        UrlTestManager.ProbeResult(-2, false)
                    }
                } else {
                    UrlTestManager.ProbeResult(
                        -2,
                        code == HttpURLConnection.HTTP_UNAVAILABLE ||
                            code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT
                    )
                }
            } catch (error: Exception) {
                UrlTestManager.ProbeResult(-2, true)
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun waitUntilReady() {
        repeat(READY_ATTEMPTS) {
            check(!closed.get()) { "批量 URL Test 在启动期间被停止" }
            val ready = runCatching {
                val connection = URL("http://127.0.0.1:$apiPort/version")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = READY_TIMEOUT_MS
                connection.readTimeout = READY_TIMEOUT_MS
                try {
                    connection.responseCode in 200..299
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (ready) return
            delay(READY_RETRY_DELAY_MS)
        }
        error("批量 URL Test 核心启动超时")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 关闭顺序保证核心先停止，再清理仅属于本批次的临时目录。
        reservation.close()
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
        directory.delete()
    }

    private companion object {
        const val TAG = "SingBoxUrlTestBatch"
        const val API_TIMEOUT_GRACE_MS = 1_500
        const val READY_ATTEMPTS = 20
        const val READY_TIMEOUT_MS = 500
        const val READY_RETRY_DELAY_MS = 100L
    }
}
