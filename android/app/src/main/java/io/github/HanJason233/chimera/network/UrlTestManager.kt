package io.github.HanJason233.chimera.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import java.util.Collections

/** 按需启动有界并发测速；同一内核的节点共享进程，每个节点使用独立 SOCKS 入站。 */
object UrlTestManager {
    data class StartResult(val nodes: List<Node>)
    data class ProbeResult(val delayMs: Int, val retryable: Boolean)
    private data class Batch(
        val context: Context, val nodes: Map<String, Node>, val settings: ProtocolCoreSettings,
        val up: Int, val down: Int, val singBox: SingBoxUrlTestBatch?, val xray: XrayUrlTestBatch?
    )
    private val mutex = Mutex()
    // 仅在某个批量实例启动失败时，限制逐节点回退会话的并发进程数。
    private val perNodeRuntimePermits = Semaphore(4)
    @Volatile private var batch: Batch? = null
    private val sessions = Collections.synchronizedSet(mutableSetOf<NodeTestProxyManager.Session>())

    suspend fun start(
        context: Context,
        nodes: List<Node>,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        protocolCoreSettings: ProtocolCoreSettings = ProtocolCoreSettings()
    ): StartResult? = mutex.withLock {
        if (nodes.isEmpty()) return@withLock null
        check(batch == null) { "A native probe batch is already running" }
        val appContext = context.applicationContext
        val singBoxNodes = nodes.filter {
            protocolCoreSettings.coreFor(it.type) == ProxyCore.SING_BOX
        }
        val singBox = if (singBoxNodes.isEmpty()) null else runCatching {
            SingBoxUrlTestBatch(appContext, singBoxNodes, hysteriaUpMbps, hysteriaDownMbps).start()
        }.onFailure {
            Log.w(TAG, "无法启动共享 sing-box URL Test，将回退到逐节点测速", it)
        }.getOrNull()
        if (singBox != null) {
            Log.i(TAG, "共享 sing-box URL Test 已启动：nodes=${singBoxNodes.size}")
        }
        val xrayNodes = nodes.filter {
            protocolCoreSettings.coreFor(it.type) == ProxyCore.XRAY
        }
        val xray = if (xrayNodes.isEmpty()) null else runCatching {
            XrayUrlTestBatch(appContext, xrayNodes, hysteriaUpMbps, hysteriaDownMbps).start()
        }.onFailure {
            Log.w(TAG, "无法启动共享 Xray URL Test，将回退到逐节点测速", it)
        }.getOrNull()
        if (xray != null) {
            Log.i(TAG, "共享 Xray URL Test 已启动：nodes=${xrayNodes.size}")
        }
        batch = Batch(
            appContext, nodes.associateBy { it.id }, protocolCoreSettings,
            hysteriaUpMbps, hysteriaDownMbps, singBox, xray
        )
        StartResult(nodes)
    }

    suspend fun testNode(
        node: Node,
        url: String,
        warmupTimeoutMs: Long,
        measurementTimeoutMs: Long
    ): ProbeResult? = withContext(Dispatchers.IO) {
        val active = batch ?: return@withContext null
        if (node.id !in active.nodes) return@withContext ProbeResult(-2, false)
        when (active.settings.coreFor(node.type)) {
            ProxyCore.SING_BOX -> active.singBox?.let {
                return@withContext it.testNode(node, url, warmupTimeoutMs, measurementTimeoutMs)
            }
            ProxyCore.XRAY -> active.xray?.let {
                return@withContext it.testNode(node, url, warmupTimeoutMs, measurementTimeoutMs)
            }
        }

        perNodeRuntimePermits.withPermit {
            var session: NodeTestProxyManager.Session? = null
            try {
                val created = NodeTestProxyManager.createSession(
                    active.context, node, active.up, active.down, active.settings.coreFor(node.type)
                )
                session = created
                mutex.withLock {
                    if (batch !== active) return@withContext ProbeResult(-2, false)
                    sessions.add(created)
                }
                if (withTimeoutOrNull(20_000) { created.start() } != true) return@withContext ProbeResult(-2, false)
                if (batch !== active) return@withContext ProbeResult(-2, false)
                probeEndpoint(created.endpoint, url, warmupTimeoutMs, measurementTimeoutMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProbeResult(-2, true)
            } finally {
                session?.let {
                    stopSafely(it)
                    sessions.remove(it)
                }
            }
        }
    }

    private fun stopSafely(session: NodeTestProxyManager.Session) {
        try { session.stop() } catch (error: Exception) {
            Log.w("UrlTestManager", "Probe cleanup failed: ${error.javaClass.simpleName}")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val active = mutex.withLock {
            val previous = batch
            batch = null
            previous
        }
        synchronized(sessions) { sessions.toList() }.forEach(::stopSafely)
        sessions.clear()
        active?.singBox?.close()
        active?.xray?.close()
    }

    /** 双核心统一使用暖连接测速、相同截止时间和相同 HTTP 状态判定。 */
    internal fun probeEndpoint(
        endpoint: LocalSocksEndpoint,
        url: String,
        warmupTimeoutMs: Long,
        measurementTimeoutMs: Long
    ): ProbeResult = try {
        val result = Socks5HttpProbe.measureLatency(
            endpoint,
            url,
            warmupTimeoutMs.coerceIn(500L, 60_000L),
            measurementTimeoutMs.coerceIn(500L, 60_000L)
        )
        if (result.statusCode in 200..399) ProbeResult(result.delayMs, false)
        else ProbeResult(-2, result.statusCode == 503 || result.statusCode == 504)
    } catch (_: Exception) {
        ProbeResult(-2, true)
    }

    private const val TAG = "UrlTestManager"
}
