package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.CoreRequest
import xyz.a202132.app.core.LocalSocksEndpoint
import xyz.a202132.app.core.PortReservation
import xyz.a202132.app.core.xray.XrayNativeSession
import xyz.a202132.app.core.xray.XrayVpnConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.UrlTestMode

/** 一个 URL Test 批次内的全部 Xray 节点共享一个原生进程。 */
internal class XrayUrlTestBatch(
    private val context: Context,
    private val nodes: List<Node>,
    private val hysteriaUpMbps: Int,
    private val hysteriaDownMbps: Int
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val reservations = mutableListOf<PortReservation>()
    private var endpoints = emptyMap<String, LocalSocksEndpoint>()
    private var session: XrayNativeSession? = null

    suspend fun start(): XrayUrlTestBatch = withContext(Dispatchers.IO) {
        try {
            require(nodes.map(Node::id).toSet().size == nodes.size) { "URL Test 节点 ID 必须唯一" }
            repeat(nodes.size) { reservations.add(PortReservation()) }
            val orderedEndpoints = reservations.map { LocalSocksEndpoint.unauthenticated(it.port) }
            endpoints = nodes.map(Node::id).zip(orderedEndpoints).toMap()
            val requests = nodes.zip(orderedEndpoints) { node, endpoint ->
                CoreRequest(
                    node = node,
                    endpoint = endpoint,
                    hysteriaUpMbps = hysteriaUpMbps,
                    hysteriaDownMbps = hysteriaDownMbps
                )
            }
            val config = XrayVpnConfig.generateUrlTestBatch(requests)
            reservations.forEach(PortReservation::close)
            reservations.clear()
            session = XrayNativeSession(context, orderedEndpoints.first()) { message ->
                Log.w(TAG, message)
            }.start(config)
            this@XrayUrlTestBatch
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun testNode(
        node: Node,
        targetUrl: String,
        timeoutMs: Long,
        mode: UrlTestMode
    ): UrlTestManager.ProbeResult =
        withContext(Dispatchers.IO) {
            val endpoint = endpoints[node.id]
            if (closed.get() || endpoint == null) {
                return@withContext UrlTestManager.ProbeResult(-2, false)
            }
            UrlTestManager.probeEndpoint(endpoint, targetUrl, timeoutMs, mode)
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
        const val TAG = "XrayUrlTestBatch"
    }
}
