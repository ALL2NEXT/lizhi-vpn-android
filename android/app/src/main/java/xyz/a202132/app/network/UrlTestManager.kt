package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.ProtocolCoreSettings
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.service.XrayConfigPreparer
import xyz.a202132.app.service.XrayProcessManager
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL

/**
 * URL Test 管理器
 * 启动临时无头 sing-box，并按核心设置桥接 Xray 进行 HTTP 握手延迟测试
 * 不需要 VPN 权限，配置中没有 TUN inbound
 */
object UrlTestManager {
    
    private const val TAG = "UrlTestManager"
    const val CLASH_API_PORT = 19090

    data class StartResult(val nodes: List<Node>)

    private val lifecycleMutex = Mutex()
    private val xrayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: io.nekohasekai.libbox.CommandServer? = null
    private var xrayProcessManager: XrayProcessManager? = null
    private var activeNodes: List<Node> = emptyList()
    private var isRunning = false
    
    /**
     * 启动临时 sing-box 实例用于 URL 测试
     */
    suspend fun start(
        context: Context,
        nodes: List<Node>,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        urlTestUrl: String = AppConfig.URL_TEST_URL,
        protocolCoreSettings: ProtocolCoreSettings = ProtocolCoreSettings()
    ): StartResult? = lifecycleMutex.withLock {
        if (isRunning) {
            Log.w(TAG, "URL Test instance already running")
            return@withLock StartResult(activeNodes)
        }

        try {
            val appContext = context.applicationContext
            val xrayNodes = nodes.filter { node -> protocolCoreSettings.usesXray(node.type) }
            var effectiveNodes = nodes
            var xrayBridgePorts: Map<String, Int> = emptyMap()

            if (xrayNodes.isNotEmpty()) {
                val manager = XrayProcessManager(
                    context = appContext,
                    scope = xrayScope,
                    onUnexpectedExit = { message -> Log.e(TAG, message) },
                    runtimeDirectoryName = XRAY_RUNTIME_DIRECTORY
                )
                xrayProcessManager = manager
                val allocatedPorts = allocateBridgePorts(xrayNodes)
                val prepared = runCatching {
                    XrayConfigPreparer(manager).prepare(
                        nodes = xrayNodes,
                        bridgePorts = allocatedPorts,
                        selectedNodeId = null,
                        hysteriaUpMbps = hysteriaUpMbps,
                        hysteriaDownMbps = hysteriaDownMbps
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Unable to prepare Xray URL Test nodes", error)
                }.getOrNull()

                if (prepared != null) {
                    val compatibleIds = prepared.nodes.mapTo(mutableSetOf(), Node::id)
                    effectiveNodes = nodes.filter { node ->
                        !protocolCoreSettings.usesXray(node.type) || node.id in compatibleIds
                    }
                    xrayBridgePorts = prepared.bridgePorts
                    manager.start(prepared.content, xrayBridgePorts.values)
                } else {
                    manager.stop()
                    xrayProcessManager = null
                    effectiveNodes = nodes.filterNot { node ->
                        protocolCoreSettings.usesXray(node.type)
                    }
                }
            }

            require(effectiveNodes.isNotEmpty()) { "没有可启动的 URL Test 节点" }
            Log.i(
                TAG,
                "Starting headless dual-core URL Test: " +
                    "total=${effectiveNodes.size}, xray=${xrayBridgePorts.size}"
            )
            
            // 使用与 VPN 服务相同的工作目录
            val workDir = File(appContext.filesDir, "sing-box")
            if (!workDir.exists()) workDir.mkdirs()
            
            // 日志文件 - 用于调试
            val logFile = File(workDir, "urltest.log")
            if (logFile.exists()) logFile.delete()
            
            val options = io.nekohasekai.libbox.SetupOptions().apply {
                basePath = workDir.absolutePath
                workingPath = workDir.absolutePath
                tempPath = appContext.cacheDir.absolutePath
            }
            Libbox.setup(options)
            
            // 生成测试配置 (无 TUN，有 ClashAPI，指定网络接口)
            val configGenerator = SingBoxConfigGenerator()
            val configContent = configGenerator.generateUrlTestConfig(
                nodes = effectiveNodes,
                clashApiPort = CLASH_API_PORT,
                logFile = logFile.absolutePath,
                hysteriaUpMbps = hysteriaUpMbps,
                hysteriaDownMbps = hysteriaDownMbps,
                urlTestUrl = urlTestUrl,
                xrayBridgePorts = xrayBridgePorts
            )
            Log.d(TAG, "Test config generated, length: ${configContent.length}")
            
            // 创建 CommandServerHandler
            val serverHandler = object : io.nekohasekai.libbox.CommandServerHandler {
                override fun serviceStop() {
                    Log.d(TAG, "URL Test service stop requested")
                }
                override fun serviceReload() {}
                override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
                    return io.nekohasekai.libbox.SystemProxyStatus()
                }
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun writeDebugMessage(message: String?) {
                    Log.d(TAG, "Debug: $message")
                }
            }
            
            // 使用无头平台接口 (不需要 VPN/TUN)
            val platformInterface = HeadlessPlatformInterface(appContext)
            
            // 创建并启动 CommandServer
            commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
            commandServer?.start()
            
            // 启动 sing-box 服务
            val overrideOptions = io.nekohasekai.libbox.OverrideOptions()
            commandServer?.startOrReloadService(configContent, overrideOptions)
            
            isRunning = true
            activeNodes = effectiveNodes
            Log.i(TAG, "Headless sing-box started, ClashAPI at 127.0.0.1:$CLASH_API_PORT")
            StartResult(effectiveNodes)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start headless dual-core URL Test", e)
            cleanup()
            null
        }
    }
    
    /**
     * 诊断：查询 ClashAPI /proxies 端点
     */
    fun diagnoseProxies(): String? {
        return try {
            val url = URL("http://127.0.0.1:$CLASH_API_PORT/proxies")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query /proxies", e)
            null
        }
    }
    
    /**
     * 读取 sing-box 核心日志文件（最后 100 行）
     */
    fun readLogFile(context: Context): String? {
        return try {
            val logFile = File(File(context.filesDir, "sing-box"), "urltest.log")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                val last100 = lines.takeLast(100)
                last100.joinToString("\n")
            } else {
                Log.w(TAG, "Log file not found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
            null
        }
    }
    
    /**
     * 停止临时 sing-box 实例
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (!isRunning && commandServer == null && xrayProcessManager == null) return@withLock

            Log.i(TAG, "Stopping headless dual-core URL Test")
            cleanup()
        }
    }
    
    private fun cleanup() {
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing service", e)
        }
        try {
            commandServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing command server", e)
        }
        commandServer = null
        xrayProcessManager?.stop()
        xrayProcessManager = null
        activeNodes = emptyList()
        isRunning = false
    }

    private fun allocateBridgePorts(nodes: List<Node>): Map<String, Int> {
        val reservations = mutableListOf<Pair<String, ServerSocket>>()
        return try {
            nodes.forEach { node ->
                val socket = ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress("127.0.0.1", 0))
                }
                reservations += node.id to socket
            }
            reservations.associate { (nodeId, socket) -> nodeId to socket.localPort }
        } finally {
            reservations.forEach { (_, socket) -> runCatching { socket.close() } }
        }
    }

    private const val XRAY_RUNTIME_DIRECTORY = "xray-url-test"
    
}
