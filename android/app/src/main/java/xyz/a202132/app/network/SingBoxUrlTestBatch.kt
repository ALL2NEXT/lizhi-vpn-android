package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.LocalSocksEndpoint
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
    private val directory = File(context.cacheDir, "sb-urltest-${UUID.randomUUID().toString().take(8)}")
    private val closed = AtomicBoolean()
    private val reservations = mutableListOf<PortReservation>()
    private var endpoints = emptyMap<String, LocalSocksEndpoint>()
    private var server: CommandServer? = null

    suspend fun start(): SingBoxUrlTestBatch = withContext(Dispatchers.IO) {
        try {
            repeat(nodes.size) { reservations.add(PortReservation()) }
            val orderedEndpoints = reservations.map { LocalSocksEndpoint.unauthenticated(it.port) }
            endpoints = nodes.map(Node::id).zip(orderedEndpoints).toMap()
            val config = SingBoxConfigGenerator().generateUrlTestBatchConfig(
                nodes = nodes,
                endpoints = orderedEndpoints,
                hysteriaUpMbps = hysteriaUpMbps,
                hysteriaDownMbps = hysteriaDownMbps
            )

            // Libbox.setup 会修改进程级状态，必须与 VPN 和其他测速会话串行初始化。
            synchronized(SingBoxBackend.setupLock) {
                SingBoxBackend.setup(context, directory)
                Libbox.checkConfig(config)
                reservations.forEach(PortReservation::close)
                reservations.clear()
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
            waitUntilReady(orderedEndpoints.first())
            this@SingBoxUrlTestBatch
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun testNode(
        node: Node,
        targetUrl: String,
        timeoutMs: Long
    ): UrlTestManager.ProbeResult =
        withContext(Dispatchers.IO) {
            val endpoint = endpoints[node.id]
            if (closed.get() || endpoint == null) {
                return@withContext UrlTestManager.ProbeResult(-2, false)
            }
            UrlTestManager.probeEndpoint(endpoint, targetUrl, timeoutMs)
        }

    private suspend fun waitUntilReady(endpoint: LocalSocksEndpoint) {
        repeat(READY_ATTEMPTS) {
            check(!closed.get()) { "批量 URL Test 在启动期间被停止" }
            val ready = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.address, endpoint.port), READY_TIMEOUT_MS)
                }
                true
            }.getOrDefault(false)
            if (ready) return
            delay(READY_RETRY_DELAY_MS)
        }
        error("批量 URL Test 核心启动超时")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 关闭顺序保证核心先停止，再清理仅属于本批次的临时目录。
        reservations.forEach(PortReservation::close)
        reservations.clear()
        endpoints = emptyMap()
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
        directory.delete()
    }

    private companion object {
        const val TAG = "SingBoxUrlTestBatch"
        const val READY_ATTEMPTS = 30
        const val READY_TIMEOUT_MS = 500
        const val READY_RETRY_DELAY_MS = 100L
    }
}
