package io.github.HanJason233.chimera.network

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.PortReservation
import io.github.HanJason233.chimera.core.singbox.SingBoxNativeSession
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.util.SingBoxConfigGenerator

/** All sing-box nodes in one URL-test batch share one native process. */
internal class SingBoxUrlTestBatch(
    private val context: Context,
    private val nodes: List<Node>,
    private val hysteriaUpMbps: Int,
    private val hysteriaDownMbps: Int
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val reservations = mutableListOf<PortReservation>()
    private var endpoints = emptyMap<String, LocalSocksEndpoint>()
    private var session: SingBoxNativeSession? = null

    suspend fun start(): SingBoxUrlTestBatch = withContext(Dispatchers.IO) {
        try {
            require(nodes.map(Node::id).toSet().size == nodes.size) {
                "URL Test 节点 ID 必须唯一"
            }
            repeat(nodes.size) { reservations.add(PortReservation()) }
            val orderedEndpoints = reservations.map { LocalSocksEndpoint.unauthenticated(it.port) }
            endpoints = nodes.map(Node::id).zip(orderedEndpoints).toMap()
            val config = SingBoxConfigGenerator().generateUrlTestBatchConfig(
                nodes = nodes,
                endpoints = orderedEndpoints,
                hysteriaUpMbps = hysteriaUpMbps,
                hysteriaDownMbps = hysteriaDownMbps
            )
            reservations.forEach(PortReservation::close)
            reservations.clear()
            session = SingBoxNativeSession(context, orderedEndpoints.first()) { message ->
                Log.w(TAG, message)
            }.start(config)
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
        warmupTimeoutMs: Long,
        measurementTimeoutMs: Long
    ): UrlTestManager.ProbeResult = withContext(Dispatchers.IO) {
        val endpoint = endpoints[node.id]
        if (closed.get() || endpoint == null) {
            return@withContext UrlTestManager.ProbeResult(-2, false)
        }
        UrlTestManager.probeEndpoint(endpoint, targetUrl, warmupTimeoutMs, measurementTimeoutMs)
    }

    private suspend fun waitUntilReady(endpoint: LocalSocksEndpoint) {
        repeat(READY_ATTEMPTS) {
            check(!closed.get()) { "批量 URL Test 核心在启动期间被停止" }
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
        reservations.forEach(PortReservation::close)
        reservations.clear()
        endpoints = emptyMap()
        runCatching { session?.close() }
        session = null
    }

    private companion object {
        const val TAG = "SingBoxUrlTestBatch"
        const val READY_ATTEMPTS = 30
        const val READY_TIMEOUT_MS = 500
        const val READY_RETRY_DELAY_MS = 100L
    }
}
