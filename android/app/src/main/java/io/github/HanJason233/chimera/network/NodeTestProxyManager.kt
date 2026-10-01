package io.github.HanJason233.chimera.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.*
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ProxyCore
import java.util.concurrent.atomic.AtomicBoolean

/** VPN 与测速复用同一套内核注册表和协议配置生成逻辑。 */
object NodeTestProxyManager {
    class Session(
        private val context: Context,
        private val node: Node,
        private val hysteriaUpMbps: Int,
        private val hysteriaDownMbps: Int,
        private val core: ProxyCore
    ) {
        private val reservation = PortReservation()
        // 显式保留完整端点，确保测速请求和内核实际监听地址始终一致。
        val endpoint = LocalSocksEndpoint.unauthenticated(reservation.port)
        val socksPort = endpoint.port
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stopped = AtomicBoolean()
        private val starting = AtomicBoolean()
        @Volatile private var running: CoreSession? = null

        suspend fun start(): Boolean {
            if (stopped.get() || !starting.compareAndSet(false, true)) return false
            return try {
                val backend = CoreBackends.registry.resolve(core, node)
                val request = CoreRequest(node, endpoint, hysteriaUpMbps = hysteriaUpMbps, hysteriaDownMbps = hysteriaDownMbps)
                val prepared = backend.prepare(context, request)
                currentCoroutineContext().ensureActive()
                if (stopped.get()) return false
                reservation.close()
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
                reservation.close()
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
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        core: ProxyCore = ProxyCore.SING_BOX
    ) = Session(context.applicationContext, node, hysteriaUpMbps, hysteriaDownMbps, core)
}
