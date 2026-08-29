package xyz.a202132.app.service

import android.app.Notification
import android.content.Intent
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.LocalTrafficUsageStore
import xyz.a202132.app.data.local.NodeDao
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.data.repository.VpnRuntimeSettings
import xyz.a202132.app.util.RuleManager
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.flow.first

/**
 * VPN服务 - 使用sing-box核心
 */
class BoxVpnService : VpnService() {
    private enum class TrafficSource {
        WAITING_FOR_CORE,
        SING_BOX_STATUS,
        TRAFFIC_STATS
    }

    companion object {
        private const val TAG = "BoxVpnService"
        private const val CORE_STATUS_FRESH_MS = 3_500L
        private const val CORE_STATUS_STARTUP_GRACE_MS = 6_000L
        // Reduce disk I/O while keeping normal stop/switch paths force-flushed.
        private const val LOCAL_TRAFFIC_PERSIST_INTERVAL_MS = 60_000L
        
        const val ACTION_START = "xyz.a202132.app.START_VPN"
        const val ACTION_STOP = "xyz.a202132.app.STOP_VPN"
        const val ACTION_RESTART = "xyz.a202132.app.RESTART_VPN"
        const val EXTRA_NODE_RAW_LINK = "node_raw_link"
        const val EXTRA_NODE_NAME = "node_name"
        const val EXTRA_PROXY_MODE = "proxy_mode"
        
        var isRunning = false
            private set
        
        var currentNodeName: String? = null
            private set
            
        // 褰撳墠娴侀噺缁熻
        @Volatile var uploadTotal: Long = 0L
            private set
        @Volatile var downloadTotal: Long = 0L
            private set

        @Volatile private var currentInternalSocksPort: Int? = null

        /**
         * 返回主 VPN 核心的本机 SOCKS 端口。
         * 仅用于让应用内测试复用当前 VPN 实例，避免另起核心造成重复或缺失统计。
         */
        fun getInternalSocksPort(): Int? {
            return currentInternalSocksPort?.takeIf { isRunning }
        }
    }
    
    private var commandServer: io.nekohasekai.libbox.CommandServer? = null
    private var platformInterface: BoxPlatformInterface? = null
    private val configPreparer = VpnConfigPreparer()
    private val notificationFactory = VpnNotificationFactory(this)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val xrayProcessManager by lazy {
        XrayProcessManager(applicationContext, serviceScope) { message ->
            serviceScope.launch(Dispatchers.Main) {
                if (!isStopping) {
                    Log.e(TAG, message)
                    ServiceManager.notifyError(message)
                    stopVpn()
                }
            }
        }
    }
    private val xrayConfigPreparer by lazy { XrayConfigPreparer(xrayProcessManager) }
    private lateinit var disconnectRecoveryManager: DisconnectRecoveryManager
    @Volatile private var isStopping = false
    
    override fun onCreate() {
        super.onCreate()
        disconnectRecoveryManager = DisconnectRecoveryManager(
            context = applicationContext,
            scope = serviceScope,
            socksPortProvider = { getInternalSocksPort() },
            isVpnRunning = { isRunning && !isStopping },
            reconnect = { node, mode ->
                withContext(Dispatchers.Main) {
                    ServiceManager.startVpn(applicationContext, node, mode)
                }
            }
        )
        notificationFactory.createChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rawLink = intent.getStringExtra(EXTRA_NODE_RAW_LINK)
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME) ?: "Unknown"
                val proxyModeName = intent.getStringExtra(EXTRA_PROXY_MODE) ?: ProxyMode.SMART.name
                val proxyMode = try {
                    ProxyMode.valueOf(proxyModeName)
                } catch (e: Exception) {
                    ProxyMode.SMART
                }

                // 立即启动前台服务，避免 ForegroundServiceDidNotStartInTimeException
                startForeground(AppConfig.NOTIFICATION_ID, createNotification(nodeName))
                
                if (rawLink != null) {
                    startVpn(rawLink, nodeName, proxyMode)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
            ACTION_RESTART -> {
                // 重置连接：停止后重新启动
                restartVpn()
            }
            else -> {
                // 未知 action，启动前台服务然后停止
                startForeground(AppConfig.NOTIFICATION_ID, createNotification("正在停止..."))
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun startVpn(rawLink: String, nodeName: String, proxyMode: ProxyMode) {
        Log.d(TAG, "Starting VPN for node: $nodeName")
        Log.i(TAG, "VPN start requested")
        
        serviceScope.launch {
            if (isRunning) {
                Log.w(TAG, "VPN is already running, stopping first")
                Log.w(TAG, "VPN was already running; stopping previous instance first")
                stopVpnInternal()
            }
            
            try {
                // 1. 获取所有节点 (用于生成包含所有节点的配置)
                val nodeDao = xyz.a202132.app.data.local.AppDatabase.getInstance(application).nodeDao()
                val settingsRepo = SettingsRepository(application)
                // 使用 Flow 的 first() 获取当前节点列表
                val rawNodes = try {
                    loadNodesForVpnStart(nodeDao, settingsRepo, rawLink, nodeName)
                } catch (e: Exception) {
                    emptyList<Node>()
                }
                
                if (rawNodes.isEmpty()) {
                    Log.e(TAG, "No nodes available")
                    Log.e(TAG, "VPN start failed: no nodes available")
                     withContext(Dispatchers.Main) {
                        ServiceManager.notifyError("娌℃湁鍙敤鑺傜偣")
                    }
                    return@launch
                }

                // 2. 确定选中的节点ID
                // 如果传入的是 specific node (非 Auto)，找到它的 ID
                // 这里的 rawLink 可能只是为了兼容旧逻辑，实际我们更关心 nodeName 或 ID
                // 但为了保险，我们尝试匹配 rawLink 对应的节点
                var selectedNodeId: String? = null
                if (nodeName != "自动选择") {
                    selectedNodeId = rawNodes.find { it.getRawLinkPlain() == rawLink }?.id
                }

                val runtimeSettings = settingsRepo.getVpnRuntimeSettings()
                val ipv6Mode = runtimeSettings.ipv6RoutingMode
                val tunStack = runtimeSettings.tunStack
                val hysteriaUpMbps = runtimeSettings.hysteriaUpMbps
                val hysteriaDownMbps = runtimeSettings.hysteriaDownMbps
                val internalSocksPort = pickFreeInternalSocksPort()
                
                Log.d(TAG, "Generating config with ${rawNodes.size} nodes, selected: $selectedNodeId, ipv6Mode: $ipv6Mode, tunStack=$tunStack, mtu: ${AppConfig.VPN_MTU}, hysteria=${hysteriaUpMbps}/${hysteriaDownMbps}Mbps, internalSocks=$internalSocksPort")

                // 3. 确保规则集文件存在（从 assets 拷贝兜底）
                RuleManager.ensureRuleSets(application)

                // 4. Generate and validate the complete config once. Per-node
                // validation is only used as a recovery path when it fails.
                val configContent = prepareAndStartProtocolCores(
                    nodes = rawNodes,
                    selectedNodeId = selectedNodeId,
                    proxyMode = proxyMode,
                    runtimeSettings = runtimeSettings,
                    internalSocksPort = internalSocksPort,
                )
                deleteLegacyConfigFile()

                // 初始化 libbox
                initializeLibbox(
                    configContent,
                    nodeName,
                    internalSocksPort,
                    runtimeSettings
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                Log.e(TAG, "VPN start failed", e)
                withContext(Dispatchers.Main) {
                    ServiceManager.notifyError("VPN启动失败: ${e.message}")
                }
                stopVpn()
            }
        }
    }
    
    private suspend fun initializeLibbox(
        configContent: String,
        nodeName: String,
        internalSocksPort: Int,
        runtimeSettings: VpnRuntimeSettings
    ) {
        withContext(Dispatchers.IO) {
            try {
                // 设置工作目录
                val workDir = File(filesDir, "sing-box")
                if (!workDir.exists()) {
                    workDir.mkdirs()
                }

                // libbox 1.13.x 使用 SetupOptions
                val options = io.nekohasekai.libbox.SetupOptions().apply {
                    basePath = workDir.absolutePath
                    workingPath = workDir.absolutePath
                    tempPath = cacheDir.absolutePath
                }
                Libbox.setup(options)

                // 读取配置文件内容
                Log.d(TAG, "Config content length: ${configContent.length}")

                // 创建平台接口并启动网络监控
                platformInterface = BoxPlatformInterface(
                    this@BoxVpnService,
                    runtimeSettings.perAppProxy
                )
                platformInterface?.startNetworkMonitor()

                // 创建 CommandServerHandler
                val serverHandler = object : io.nekohasekai.libbox.CommandServerHandler {
                    override fun serviceStop() {
                        stopVpn()
                    }
                    override fun serviceReload() {
                        restartVpn()
                    }
                    override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
                        return io.nekohasekai.libbox.SystemProxyStatus()
                    }
                    override fun setSystemProxyEnabled(enabled: Boolean) {
                        // Android 不支持系统代理设置
                    }
                    override fun writeDebugMessage(message: String?) {
                        Log.d(TAG, "Debug: $message")
                    }
                }

                // 创建 CommandServer（替代旧版 BoxService）
                commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
                commandServer?.start()

                // 启动或重载 sing-box 服务实例
                val overrideOptions = io.nekohasekai.libbox.OverrideOptions()
                commandServer?.startOrReloadService(configContent, overrideOptions)
                deleteLegacyConfigFile()

                // 启动流量监控和组状态监控
                startCommandClient()
                startTrafficMonitor()
                
                withContext(Dispatchers.Main) {
                    currentInternalSocksPort = internalSocksPort
                    isRunning = true
                    currentNodeName = nodeName

                    // 通知UI更新
                    ServiceManager.notifyStateChange()
                }
                disconnectRecoveryManager.start()
                
                Log.d(TAG, "VPN started successfully with libbox")
                Log.i(TAG, "VPN started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize libbox", e)
                Log.e(TAG, "Failed to initialize libbox", e)
                throw e
            }
        }
    }

    // Command Client 相关
    private var commandClientJob: kotlinx.coroutines.Job? = null
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null
    @Volatile private var currentTrafficSource: TrafficSource = TrafficSource.WAITING_FOR_CORE
    @Volatile private var lastSingBoxTrafficStatusAt: Long = 0L
    @Volatile private var trafficStatsFallbackActive = false
    private var trafficMonitorStartedAt = 0L
    private var fallbackUploadTotalOffset = 0L
    private var fallbackDownloadTotalOffset = 0L
    
    private fun startCommandClient() {
        commandClientJob = serviceScope.launch {
            try {
                delay(1000)  // 等待服务启动

                // 检查服务是否仍在运行，避免在 VPN 已停止后尝试连接
                if (!isRunning) {
                    Log.d(TAG, "Command client skipped: VPN already stopped")
                    return@launch
                }
                
                val options = io.nekohasekai.libbox.CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = 1_000_000_000L // 1秒，用于流量状态更新
                }
                
                val handler = object : io.nekohasekai.libbox.CommandClientHandler {
                    override fun connected() {
                         Log.d(TAG, "Command client connected")
                    }
                    override fun disconnected(message: String?) {
                        Log.d(TAG, "Command client disconnected: $message")
                    }
                    override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) {
                        if (message == null) return
                        handleSingBoxTrafficStatus(message)
                    }
                    
                    override fun writeGroups(message: io.nekohasekai.libbox.OutboundGroupIterator?) {}
                    
                    override fun clearLogs() {}
                    override fun setDefaultLogLevel(level: Int) {}
                    override fun writeLogs(messageList: io.nekohasekai.libbox.LogIterator?) {}
                    override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator?, currentMode: String?) {}
                    override fun updateClashMode(newMode: String?) {}
                    override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents?) {}
                }

                // 再次检查，因为创建 handler 可能有些耗时
                if (!isRunning) {
                    Log.d(TAG, "Command client skipped: VPN stopped during setup")
                    return@launch
                }
                
                commandClient = io.nekohasekai.libbox.CommandClient(handler, options)
                commandClient?.connect()
                Log.d(TAG, "Command client started")
                
            } catch (e: Exception) {
                // 只在服务仍在运行时记录错误，否则静默忽略
                if (isRunning) {
                    Log.e(TAG, "Error starting command client", e)
                    Log.w(TAG, "Command client failed to start", e)
                }
            }
        }
    }
    
    private fun stopCommandClient() {
        commandClientJob?.cancel()
        commandClientJob = null
        try {
            commandClient?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        commandClient = null
    }

    // Traffic Monitor (使用 TrafficStats)
    private var trafficMonitorJob: kotlinx.coroutines.Job? = null

    // 上次流量记录（用于计算速度）
    private var lastTxBytes = 0L
    private var lastRxBytes = 0L

    // 连接开始时的流量基准
    private var baseTxBytes = 0L
    private var baseRxBytes = 0L

    // Local daily storage baseline. The backend is contacted only by the 23:00 receiver.
    private val trafficPersistenceLock = Any()
    private var lastPersistedUploadTotal = 0L
    private var lastPersistedDownloadTotal = 0L
    private var lastTrafficPersistAt = 0L
    
    private fun startTrafficMonitor() {
        currentTrafficSource = TrafficSource.WAITING_FOR_CORE
        lastSingBoxTrafficStatusAt = 0L
        trafficStatsFallbackActive = false
        trafficMonitorStartedAt = System.currentTimeMillis()
        fallbackUploadTotalOffset = 0L
        fallbackDownloadTotalOffset = 0L
        uploadTotal = 0L
        downloadTotal = 0L
        lastPersistedUploadTotal = 0L
        lastPersistedDownloadTotal = 0L
        lastTrafficPersistAt = 0L
        Log.i(TAG, "Traffic source -> ${TrafficSource.WAITING_FOR_CORE.name}")
        // 记录连接开始时的流量基准
        val uid = android.os.Process.myUid()
        baseTxBytes = android.net.TrafficStats.getUidTxBytes(uid)
        baseRxBytes = android.net.TrafficStats.getUidRxBytes(uid)
        lastTxBytes = baseTxBytes
        lastRxBytes = baseRxBytes
        
        trafficMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(1000) // 每秒更新一次
                
                try {
                    val currentTime = System.currentTimeMillis()
                    val singBoxFresh =
                        lastSingBoxTrafficStatusAt > 0L &&
                            (currentTime - lastSingBoxTrafficStatusAt) <= CORE_STATUS_FRESH_MS
                    val waitingForFirstCoreStatus =
                        lastSingBoxTrafficStatusAt == 0L &&
                            (currentTime - trafficMonitorStartedAt) < CORE_STATUS_STARTUP_GRACE_MS
                    val currentTxBytes = android.net.TrafficStats.getUidTxBytes(uid)
                    val currentRxBytes = android.net.TrafficStats.getUidRxBytes(uid)
                    
                    if (currentTxBytes != android.net.TrafficStats.UNSUPPORTED.toLong() &&
                        currentRxBytes != android.net.TrafficStats.UNSUPPORTED.toLong()) {
                        if (singBoxFresh) {
                            // 保持基准更新，避免切回 TrafficStats 时速度突刺
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                            trafficStatsFallbackActive = false
                            continue
                        }
                        if (waitingForFirstCoreStatus) {
                            // 启动阶段只等待核心，不能先发布一帧 UID 流量，否则 UI 会闪现
                            // 本应用自身及本地回环产生的、并不属于 VPN 核心的数据。
                            baseTxBytes = currentTxBytes
                            baseRxBytes = currentRxBytes
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                            continue
                        }

                        if (!trafficStatsFallbackActive) {
                            // 从最后一份核心累计值继续计数，避免用整个 UID 会话值覆盖核心值。
                            baseTxBytes = currentTxBytes
                            baseRxBytes = currentRxBytes
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                            fallbackUploadTotalOffset = uploadTotal
                            fallbackDownloadTotalOffset = downloadTotal
                            trafficStatsFallbackActive = true
                            updateTrafficSource(TrafficSource.TRAFFIC_STATS, "sing-box status stale")
                            continue
                        }
                        
                        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        val activeNetwork = cm.activeNetwork
                        val isConnected = activeNetwork != null
                        
                        // 仅在有网络时计算总流量（避免断网重连产生的无效流量被计入）。
                        if (isConnected) {
                            val txTotal = currentTxBytes - baseTxBytes
                            val rxTotal = currentRxBytes - baseRxBytes

                            uploadTotal = fallbackUploadTotalOffset + txTotal.coerceAtLeast(0L)
                            downloadTotal = fallbackDownloadTotalOffset + rxTotal.coerceAtLeast(0L)
                            persistTrafficProgress(currentTime)

                            // 仅在有网络时更新基准值。
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating traffic stats", e)
                }
            }
        }
        
        Log.d(TAG, "Traffic monitor started in hybrid mode (prefer sing-box status, fallback TrafficStats)")
    }
    
    private fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        lastSingBoxTrafficStatusAt = 0L
        trafficMonitorStartedAt = 0L
        trafficStatsFallbackActive = false
        fallbackUploadTotalOffset = 0L
        fallbackDownloadTotalOffset = 0L
        currentTrafficSource = TrafficSource.WAITING_FOR_CORE
        Log.d(TAG, "Traffic monitor stopped")
    }

    private fun handleSingBoxTrafficStatus(message: io.nekohasekai.libbox.StatusMessage) {
        try {
            if (!message.trafficAvailable) return

            lastSingBoxTrafficStatusAt = System.currentTimeMillis()
            trafficStatsFallbackActive = false
            updateTrafficSource(TrafficSource.SING_BOX_STATUS, "writeStatus")

            uploadTotal = message.uplinkTotal.coerceAtLeast(0L)
            downloadTotal = message.downlinkTotal.coerceAtLeast(0L)
            persistTrafficProgress(System.currentTimeMillis())

        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sing-box status traffic, fallback TrafficStats: ${e.message}")
        }
    }

    private fun persistTrafficProgress(timestamp: Long, force: Boolean = false) {
        if (!force && !isRunning) return

        synchronized(trafficPersistenceLock) {
            if (!force && timestamp - lastTrafficPersistAt < LOCAL_TRAFFIC_PERSIST_INTERVAL_MS) {
                return
            }

            val currentUpload = uploadTotal.coerceAtLeast(0L)
            val currentDownload = downloadTotal.coerceAtLeast(0L)
            val uploadDelta = if (currentUpload >= lastPersistedUploadTotal) {
                currentUpload - lastPersistedUploadTotal
            } else {
                currentUpload
            }
            val downloadDelta = if (currentDownload >= lastPersistedDownloadTotal) {
                currentDownload - lastPersistedDownloadTotal
            } else {
                currentDownload
            }

            val persisted = if (uploadDelta > 0L || downloadDelta > 0L) {
                LocalTrafficUsageStore.addDelta(
                    context = applicationContext,
                    timestamp = timestamp,
                    uploadBytes = uploadDelta,
                    downloadBytes = downloadDelta,
                )
            } else {
                true
            }

            if (persisted) {
                lastPersistedUploadTotal = currentUpload
                lastPersistedDownloadTotal = currentDownload
                lastTrafficPersistAt = timestamp
            }
        }
    }

    private fun updateTrafficSource(source: TrafficSource, reason: String) {
        if (currentTrafficSource == source) return
        currentTrafficSource = source
        Log.i(TAG, "Traffic source -> ${source.name} ($reason)")
        Log.i(TAG, "Traffic statistics source changed: source=${source.name}, reason=$reason")
    }

    private fun deleteLegacyConfigFile() {
        try {
            val configFile = File(File(filesDir, "sing-box"), "config.json")
            if (configFile.exists() && !configFile.delete()) {
                Log.w(TAG, "Failed to delete legacy config file: ${configFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting legacy config file", e)
        }
    }

    private suspend fun loadNodesForVpnStart(
        nodeDao: NodeDao,
        settingsRepo: SettingsRepository,
        rawLink: String,
        nodeName: String
    ): List<Node> {
        val categoryNodes = loadNodesForCurrentCategory(nodeDao, settingsRepo)
        if (rawLink.isBlank() || categoryNodes.any { it.getRawLinkPlain() == rawLink }) {
            return categoryNodes
        }

        val selectedNode = nodeDao.getAllNodes().first().firstOrNull { it.getRawLinkPlain() == rawLink }
            ?: return categoryNodes
        Log.w(TAG, "Selected node $nodeName is outside current category, using source=${selectedNode.source}")
        return loadNodesForSource(
            nodeDao,
            selectedNode.source,
            selectedNode.subscriptionSourceId
        )
    }

    private suspend fun loadNodesForCurrentCategory(
        nodeDao: NodeDao,
        settingsRepo: SettingsRepository
    ): List<Node> {
        return when (settingsRepo.nodeListCategory.first()) {
            NodeListCategory.PRIMARY -> {
                val sourceId = settingsRepo.selectedSubscriptionSourceId.first()
                val nodes = nodeDao.getSubscriptionNodes().first()
                val filtered = if (sourceId.isNullOrBlank()) {
                    nodes
                } else {
                    nodes.filter { it.subscriptionSourceId == sourceId }
                }
                if (filtered.isEmpty() && nodes.isNotEmpty()) nodes else filtered
            }
            NodeListCategory.FAVORITES -> nodeDao.getFavoriteNodes().first()
        }
    }

    private suspend fun loadNodesForSource(
        nodeDao: NodeDao,
        source: NodeSource,
        subscriptionSourceId: String? = null
    ): List<Node> {
        return when (source) {
            NodeSource.SUBSCRIPTION -> {
                val nodes = nodeDao.getSubscriptionNodes().first()
                if (subscriptionSourceId.isNullOrBlank()) nodes
                else nodes.filter { it.subscriptionSourceId == subscriptionSourceId }
            }
            NodeSource.FAVORITE -> nodeDao.getFavoriteNodes().first()
        }
    }

    private fun setupLibboxForConfigCheck() {
        val workDir = File(filesDir, "sing-box")
        if (!workDir.exists()) workDir.mkdirs()
        val options = io.nekohasekai.libbox.SetupOptions().apply {
            basePath = workDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = cacheDir.absolutePath
        }
        Libbox.setup(options)
    }

    private fun stopVpn() {
        val stopStartTime = System.currentTimeMillis()
        Log.d(TAG, "Stopping VPN - start")
        Log.i(TAG, "VPN stop requested")

        // 立即关闭 TUN，确保状态栏 VPN 图标尽快消失
        try {
            if (isStopping) {
                Log.d(TAG, "Stopping VPN skipped: stop already in progress")
                return
            }
            isStopping = true

            // 先停掉监控与命令通道，避免关闭阶段仍有后台任务继续占用核心资源。
            stopCommandClient()
            persistTrafficProgress(System.currentTimeMillis(), force = true)
            stopTrafficMonitor()

            // 先解除底层网络绑定，帮助系统更早感知 VPN 已经开始 teardown。
            try {
                platformInterface?.prepareForVpnShutdown()
                Log.d(TAG, "Underlying network detached in ${System.currentTimeMillis() - stopStartTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error detaching underlying network", e)
            }

            platformInterface?.closeTun()
            Log.d(TAG, "TUN closed in ${System.currentTimeMillis() - stopStartTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN immediately", e)
        }

        // 立即停止前台服务（在主线程同步执行，不等待协程）
        // 这可能有助于加快 VPN 图标消失，因为系统可以更早地开始清理
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Foreground stopped in ${System.currentTimeMillis() - stopStartTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground", e)
        }

        serviceScope.launch {
            stopVpnInternal()
            
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Calling stopSelf after ${System.currentTimeMillis() - stopStartTime}ms")
                stopSelf()
            }
        }
    }
    
    private fun restartVpn() {
        Log.d(TAG, "Restarting VPN")
        Log.i(TAG, "VPN restart requested")
        
        serviceScope.launch {
            // 保存当前配置
            val savedNode = ServiceManager.currentNode.value
            val savedNodeName = savedNode?.name ?: currentNodeName
            ServiceManager.notifyRestarting()

            // 立即关闭 TUN，确保状态栏 VPN 图标尽快消失
            try {
                platformInterface?.closeTun()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TUN immediately", e)
            }

            // 停止当前连接
            stopVpnInternal()

            // 短暂延迟确保资源完全释放
            delay(500)

            // 重新启动（使用当前节点）
            if (savedNodeName != null) {
                // 通知 UI 更新
                withContext(Dispatchers.Main) {
                }

                // 获取节点信息并重新连接
                val nodeDao = xyz.a202132.app.data.local.AppDatabase.getInstance(application).nodeDao()
                val settingsRepo = xyz.a202132.app.data.repository.SettingsRepository(application)
                val rawNodes = try {
                    if (savedNode == null) {
                        loadNodesForCurrentCategory(nodeDao, settingsRepo)
                    } else {
                        val sourceNodes = loadNodesForSource(
                            nodeDao,
                            savedNode.source,
                            savedNode.subscriptionSourceId
                        )
                        if (sourceNodes.any { it.id == savedNode.id }) {
                            sourceNodes
                        } else {
                            listOf(savedNode) + sourceNodes
                        }
                    }
                } catch (e: Exception) {
                    listOfNotNull(savedNode)
                }

                if (rawNodes.isNotEmpty()) {
                    val runtimeSettings = settingsRepo.getVpnRuntimeSettings()
                    val proxyMode = runtimeSettings.proxyMode
                    val internalSocksPort = pickFreeInternalSocksPort()

                    // 获取选中的节点
                    val selectedNodeId = try {
                        savedNode?.id?.takeIf { savedId -> rawNodes.any { it.id == savedId } }
                            ?: settingsRepo.selectedNodeId.first()?.takeIf { selectedId ->
                                rawNodes.any { it.id == selectedId }
                            }
                    } catch (e: Exception) {
                        null
                    }

                    // 确保规则集文件存在
                    RuleManager.ensureRuleSets(application)
                    val configContent = prepareAndStartProtocolCores(
                        nodes = rawNodes,
                        selectedNodeId = selectedNodeId,
                        proxyMode = proxyMode,
                        runtimeSettings = runtimeSettings,
                        internalSocksPort = internalSocksPort,
                    )
                    deleteLegacyConfigFile()

                    initializeLibbox(
                        configContent,
                        savedNodeName,
                        internalSocksPort,
                        runtimeSettings
                    )
                }
            }
        }
    }

    private fun pickFreeInternalSocksPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private suspend fun prepareAndStartProtocolCores(
        nodes: List<Node>,
        selectedNodeId: String?,
        proxyMode: ProxyMode,
        runtimeSettings: VpnRuntimeSettings,
        internalSocksPort: Int
    ): String {
        xrayProcessManager.stop()
        val coreSettings = runtimeSettings.protocolCoreSettings
        val xrayNodes = nodes.filter { node -> coreSettings.usesXray(node.type) }
        var effectiveNodes = nodes
        var bridgePorts: Map<String, Int> = emptyMap()

        if (xrayNodes.isNotEmpty()) {
            val allocatedPorts = allocateXrayBridgePorts(xrayNodes)
            val selectedUsesXray = xrayNodes.any { it.id == selectedNodeId }
            val preparedXray = runCatching {
                xrayConfigPreparer.prepare(
                    xrayNodes,
                    allocatedPorts,
                    selectedNodeId,
                    runtimeSettings.hysteriaUpMbps,
                    runtimeSettings.hysteriaDownMbps
                )
            }.getOrElse { error ->
                if (selectedUsesXray) throw error
                Log.w(TAG, "No usable Xray nodes remained; continuing with sing-box nodes", error)
                null
            }

            if (preparedXray != null) {
                val compatibleIds = preparedXray.nodes.mapTo(mutableSetOf(), Node::id)
                effectiveNodes = nodes.filter { node ->
                    !coreSettings.usesXray(node.type) || node.id in compatibleIds
                }
                bridgePorts = preparedXray.bridgePorts
                xrayProcessManager.start(preparedXray.content, bridgePorts.values)
            } else {
                effectiveNodes = nodes.filterNot { node -> coreSettings.usesXray(node.type) }
            }
        }

        setupLibboxForConfigCheck()
        return configPreparer.prepare(
            nodes = effectiveNodes,
            selectedNodeId = selectedNodeId,
            proxyMode = proxyMode,
            ipv6Mode = runtimeSettings.ipv6RoutingMode,
            internalSocksPort = internalSocksPort,
            hysteriaUpMbps = runtimeSettings.hysteriaUpMbps,
            hysteriaDownMbps = runtimeSettings.hysteriaDownMbps,
            tunStack = runtimeSettings.tunStack,
            xrayBridgePorts = bridgePorts
        )
    }

    private fun allocateXrayBridgePorts(nodes: List<Node>): Map<String, Int> {
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
    
    private fun cleanupResources() {
        try {
            if (::disconnectRecoveryManager.isInitialized) {
                disconnectRecoveryManager.stop()
            }
            currentInternalSocksPort = null
            // 保存引用以便后续清理
            val pi = platformInterface
            platformInterface = null

            // 0. 最优先关闭 TUN 接口，确保系统状态栏 VPN 图标立即消失
            try {
                pi?.closeTun()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TUN", e)
            }

            // 1. 停止流量监控
            persistTrafficProgress(System.currentTimeMillis(), force = true)
            stopTrafficMonitor()
            stopCommandClient()

            // 2. 停止 Network Monitor
            pi?.prepareForVpnShutdown()

            // 3. 停止 CommandServer 和 sing-box 服务
            try {
                commandServer?.closeService()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing service", e)
            }
            try {
                commandServer?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing commandServer", e)
            }
            commandServer = null
            xrayProcessManager.stop()
            deleteLegacyConfigFile()
            
            Log.d(TAG, "Resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up resources", e)
        }
    }
    
    private suspend fun stopVpnInternal() {
        try {
            // 切换到 IO 线程执行清理，避免主线程卡顿（如果 cleanup 耗时）
            // 但对于 onDestroy，我们必须同步清理
            withContext(Dispatchers.IO) {
                cleanupResources()
            }
            
            withContext(Dispatchers.Main) {
                isRunning = false
                isStopping = false
                currentNodeName = null
                uploadTotal = 0L
                downloadTotal = 0L

                // 通知UI更新
                ServiceManager.notifyStateChange()
            }
            
            Log.d(TAG, "VPN stopped")
            Log.i(TAG, "VPN stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
            Log.e(TAG, "Error stopping VPN", e)
            withContext(Dispatchers.Main) {
                isStopping = false
            }
        }
    }
    
    override fun onRevoke() {
        Log.d(TAG, "VPN revoked by system")
        stopVpn()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // 立即取消所有协程
        serviceScope.cancel()

        // 确保资源被清理（特别是 TUN 接口）
        // 即使 stopVpnInternal 正在运行，这里再次调用也是安全的（已做空判断）
        cleanupResources()

        // 更新状态
        isRunning = false
        isStopping = false

        // 通知 UI (防止 UI 停留在已连接状态)
        // 注意：onDestroy 后 ServiceManager可能无法立即通知到已销毁的 Activity，但 StateFlow 会更新
        ServiceManager.notifyStateChange()
        
        super.onDestroy()
    }
    
    private fun createNotification(nodeName: String): Notification {
        return notificationFactory.build(nodeName)
    }
    
    /**
     * 提供给 BoxPlatformInterface 调用的 VPN Builder
     */
    fun createVpnBuilder(): Builder {
        return Builder()
    }
}
