package xyz.a202132.app.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.ProtocolCoreSettings
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Collections

/** Lazily starts bounded caller-scheduled probes, without cross-core SOCKS bridges. */
object UrlTestManager {
    data class StartResult(val nodes: List<Node>)
    data class ProbeResult(val delayMs: Int, val retryable: Boolean)
    private data class Batch(
        val context: Context, val nodes: Map<String, Node>, val settings: ProtocolCoreSettings,
        val up: Int, val down: Int
    )
    private val mutex = Mutex()
    // Native runtime memory, rather than socket count, limits safe probe parallelism.
    private val permits = Semaphore(4)
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
        batch = Batch(context.applicationContext, nodes.associateBy { it.id }, protocolCoreSettings, hysteriaUpMbps, hysteriaDownMbps)
        StartResult(nodes)
    }

    suspend fun testNode(node: Node, url: String, timeoutMs: Long): ProbeResult? = withContext(Dispatchers.IO) {
        permits.withPermit {
            val active = batch ?: return@withContext null
            if (node.id !in active.nodes) return@withContext ProbeResult(-2, false)
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
                val connection = URL(url).openConnection(Proxy(Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", created.socksPort))) as HttpURLConnection
                connection.connectTimeout = timeoutMs.coerceIn(500, 60_000).toInt()
                connection.readTimeout = connection.connectTimeout
                connection.setRequestProperty("Connection", "close")
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val code = connection.responseCode
                    if (code in 200..399) ProbeResult((SystemClock.elapsedRealtime() - startedAt).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), false)
                    else ProbeResult(-2, code == 503 || code == 504)
                } finally { connection.disconnect() }
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
        mutex.withLock {
            batch = null
            synchronized(sessions) { sessions.toList() }.forEach(::stopSafely)
            sessions.clear()
        }
    }
}
