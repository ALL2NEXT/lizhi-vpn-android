package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.net.Socket
import java.util.UUID

/**
 * 为节点 IP 和带宽测试提供临时的无头 sing-box 代理会话。
 */
object NodeTestProxyManager {

    private const val TAG = "NodeTestProxyManager"

    private val setupLock = Any()

    class Session(
        private val context: Context,
        private val node: Node,
        private val socksPort: Int,
        private val hysteriaUpMbps: Int,
        private val hysteriaDownMbps: Int
    ) {
        private var commandServer: io.nekohasekai.libbox.CommandServer? = null
        private var isRunning = false
        private val sessionId = "${socksPort}_${UUID.randomUUID().toString().take(8)}"

        @Synchronized
        fun start(): Boolean {
            if (isRunning) {
                Log.w(TAG, "Node test session already running")
                return false
            }

            return try {
                val workDir = File(context.filesDir, "sing-box-node-test-$sessionId")
                if (!workDir.exists()) workDir.mkdirs()

                synchronized(setupLock) {
                    val options = io.nekohasekai.libbox.SetupOptions().apply {
                        basePath = workDir.absolutePath
                        workingPath = workDir.absolutePath
                        tempPath = context.cacheDir.absolutePath
                    }
                    Libbox.setup(options)
                }

                val config = SingBoxConfigGenerator().generateTestConfig(
                    node = node,
                    localPort = socksPort,
                    hysteriaUpMbps = hysteriaUpMbps,
                    hysteriaDownMbps = hysteriaDownMbps
                )
                val serverHandler = object : io.nekohasekai.libbox.CommandServerHandler {
                    override fun serviceStop() {}
                    override fun serviceReload() {}
                    override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
                        return io.nekohasekai.libbox.SystemProxyStatus()
                    }
                    override fun setSystemProxyEnabled(enabled: Boolean) {}
                    override fun writeDebugMessage(message: String?) {
                        Log.d(TAG, "Debug: $message")
                    }
                }

                val platform = HeadlessPlatformInterface(context)
                commandServer = Libbox.newCommandServer(serverHandler, platform)
                commandServer?.start()
                commandServer?.startOrReloadService(config, io.nekohasekai.libbox.OverrideOptions())

                isRunning = true
                val socksReady = waitSocksReady(socksPort)
                if (!socksReady) {
                    Log.e(TAG, "Session start failed: SOCKS not ready, sessionId=$sessionId, port=$socksPort")
                    cleanup()
                    return false
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start node test session", e)
                cleanup()
                false
            }
        }

        @Synchronized
        fun stop() {
            if (!isRunning) return
            cleanup()
        }

        private fun cleanup() {
            try {
                commandServer?.closeService()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing node test service", e)
            }
            try {
                commandServer?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing node test command server", e)
            }
            commandServer = null
            isRunning = false
        }
    }

    fun createSession(
        context: Context,
        node: Node,
        socksPort: Int,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS
    ): Session {
        return Session(
            context.applicationContext,
            node,
            socksPort,
            hysteriaUpMbps,
            hysteriaDownMbps
        )
    }

    private var legacySession: Session? = null

    @Synchronized
    fun start(
        context: Context,
        node: Node,
        socksPort: Int,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS
    ): Boolean {
        if (legacySession != null) {
            Log.w(TAG, "Node test instance already running")
            return false
        }
        val session = createSession(
            context,
            node,
            socksPort,
            hysteriaUpMbps,
            hysteriaDownMbps
        )
        val started = session.start()
        if (started) {
            legacySession = session
        }
        return started
    }

    @Synchronized
    fun stop() {
        legacySession?.stop()
        legacySession = null
    }

    private fun waitSocksReady(port: Int, retries: Int = 8, delayMs: Long = 500): Boolean {
        repeat(retries) {
            try {
                Socket("127.0.0.1", port).use {
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(delayMs)
            }
        }
        return false
    }
}
