package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import xyz.a202132.app.AppConfig
import xyz.a202132.app.core.*
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.ProxyCore
import java.util.concurrent.atomic.AtomicBoolean

/** VPN and probes use exactly the same registered backend and protocol compiler. */
object NodeTestProxyManager {
    class Session(
        private val context: Context,
        private val node: Node,
        private val socksPort: Int,
        private val hysteriaUpMbps: Int,
        private val hysteriaDownMbps: Int,
        private val core: ProxyCore
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stopped = AtomicBoolean()
        private val starting = AtomicBoolean()
        @Volatile private var running: CoreSession? = null

        suspend fun start(): Boolean {
            if (stopped.get() || !starting.compareAndSet(false, true)) return false
            return try {
                val backend = CoreBackends.registry.resolve(core, node)
                val request = CoreRequest(node, socksPort, hysteriaUpMbps = hysteriaUpMbps, hysteriaDownMbps = hysteriaDownMbps)
                val prepared = backend.prepare(context, request)
                currentCoroutineContext().ensureActive()
                if (stopped.get()) return false
                val session = withContext(NonCancellable) { backend.start(context, scope, prepared, null) { stop() } }
                try { currentCoroutineContext().ensureActive() } catch (cancelled: CancellationException) {
                    session.close()
                    throw cancelled
                }
                synchronized(this) {
                    if (stopped.get()) { session.close(); false }
                    else { running = session; true }
                }
            } catch (cancelled: CancellationException) {
                stop()
                throw cancelled
            } catch (error: Exception) {
                Log.w("NodeTestProxyManager", "Probe startup failed: " + core + "/" + error.javaClass.simpleName)
                stop()
                false
            }
        }

        @Synchronized fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                running?.close()
            } finally {
                running = null
                scope.cancel()
            }
        }
    }

    fun createSession(
        context: Context,
        node: Node,
        socksPort: Int,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        core: ProxyCore = ProxyCore.SING_BOX
    ) = Session(context.applicationContext, node, socksPort, hysteriaUpMbps, hysteriaDownMbps, core)
}
