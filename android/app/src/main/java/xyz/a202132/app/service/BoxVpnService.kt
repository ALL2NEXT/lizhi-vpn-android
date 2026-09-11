package xyz.a202132.app.service

import android.content.Intent
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.a202132.app.AppConfig
import xyz.a202132.app.core.*
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.local.LocalTrafficUsageStore
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.platform.AndroidVpnHost

/** Android foreground host; native details live behind CoreBackend. */
class BoxVpnService : VpnService() {
    companion object {
        private const val TAG = "BoxVpnService"
        private val operations = Mutex()
        @Volatile private var owner: BoxVpnService? = null
        const val ACTION_START = "xyz.a202132.app.START_VPN"
        const val ACTION_STOP = "xyz.a202132.app.STOP_VPN"
        const val ACTION_RESTART = "xyz.a202132.app.RESTART_VPN"
        const val ACTION_SET_PROXY_MODE = "xyz.a202132.app.SET_PROXY_MODE"
        const val EXTRA_NODE_RAW_LINK = "node_raw_link"
        const val EXTRA_NODE_NAME = "node_name"
        const val EXTRA_NODE_GROUP_NAME = "node_group_name"
        const val EXTRA_PROXY_MODE = "proxy_mode"
        @Volatile var isRunning = false
            private set
        @Volatile var currentNodeName: String? = null
            private set
        @Volatile var currentNodeGroupName: String? = null
            private set
        @Volatile var currentCore: ProxyCore? = null
            private set
        @Volatile var uploadTotal = 0L
            private set
        @Volatile var downloadTotal = 0L
            private set
        @Volatile private var internalSocksEndpoint: LocalSocksEndpoint? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = CoreSessionController()
    private val sessionFactory = VpnSessionFactory()
    private val notificationFactory = VpnNotificationFactory(this)
    private var operation: Job? = null
    private var trafficJob: Job? = null
    private var host: AndroidVpnHost? = null
    private var activeNode: Node? = null
    private lateinit var recovery: DisconnectRecoveryManager
    private var persistedUpload = 0L
    private var persistedDownload = 0L
    private var lastPersistAt = 0L
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        notificationFactory.createChannel()
        recovery = DisconnectRecoveryManager(applicationContext, serviceScope,
            socksEndpointProvider = { internalSocksEndpoint?.takeIf { isRunning } },
            physicalNetworkProvider = { host?.currentNetwork }, isVpnRunning = { isRunning },
            reconnect = { node, mode -> withContext(Dispatchers.Main) {
                ServiceManager.startVpn(applicationContext, node, mode)
            } })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_NODE_RAW_LINK)
                val name = intent.getStringExtra(EXTRA_NODE_NAME) ?: "自动选择"
                val mode = parseMode(intent.getStringExtra(EXTRA_PROXY_MODE)) ?: ProxyMode.SMART
                foreground(name, intent.getStringExtra(EXTRA_NODE_GROUP_NAME), mode)
                if (raw != null) connect(raw, name, mode,
                    ServiceManager.currentNode.value?.takeIf { it.getRawLinkPlain() == raw }) else stopVpn()
            }
            ACTION_RESTART -> restart(null)
            ACTION_SET_PROXY_MODE -> parseMode(intent.getStringExtra(EXTRA_PROXY_MODE))?.let(::restart)
            null, SERVICE_INTERFACE -> {
                foreground("恢复连接...", null, ProxyMode.SMART)
                connect("", "自动选择", null)
            }
            else -> {
                foreground("正在停止...", null, ProxyMode.SMART)
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun foreground(name: String, group: String?, mode: ProxyMode) {
        startForeground(AppConfig.NOTIFICATION_ID, notificationFactory.build(name, group, mode))
    }

    private fun restart(mode: ProxyMode?) {
        val node = ServiceManager.currentNode.value ?: activeNode
        if (node == null) { stopVpn(); return }
        ServiceManager.notifyRestarting()
        connect(node.getRawLinkPlain(), node.name, mode, node)
    }

    private fun connect(raw: String, name: String, mode: ProxyMode?, knownNode: Node? = null) {
        val ticket = controller.invalidate()
        operation?.cancel()
        operation = serviceScope.launch {
            operations.withLock {
                if (owner !== this@BoxVpnService) {
                    owner?.let { previous ->
                        previous.controller.invalidate()
                        withContext(NonCancellable) { previous.cleanup() }
                    }
                    owner = this@BoxVpnService
                }
                withContext(NonCancellable) { cleanup() }
                try {
                    ensureActive()
                    val settingsRepo = SettingsRepository(applicationContext)
                    if (mode != null) settingsRepo.setProxyMode(mode)
                    val settings = settingsRepo.getVpnRuntimeSettings()
                    val node = knownNode ?: findNode(raw, name, settingsRepo)
                    require(settings.ipv6RoutingMode != IPv6RoutingMode.ONLY) {
                        "HEV tun2socks does not support IPv6-only routing"
                    }
                    val prepared = sessionFactory.prepare(applicationContext, node, settings)
                    try {
                        ensureActive()
                        val vpnHost = AndroidVpnHost(this@BoxVpnService, settings.perAppProxy,
                            onCellular = ServiceManager::notifyCellularNetwork)
                        host = vpnHost
                        vpnHost.start()
                        val session = controller.replace(ticket) {
                            sessionFactory.start(applicationContext, serviceScope, prepared, vpnHost) { message ->
                                if (controller.isCurrent(ticket) && !destroyed) {
                                    ServiceManager.notifyError(message)
                                    serviceScope.launch(Dispatchers.Main) {
                                        if (controller.isCurrent(ticket)) stopVpn()
                                    }
                                }
                            }
                        }
                        ensureActive()
                        if (!controller.isCurrent(ticket)) throw CancellationException()
                        activeNode = node
                        uploadTotal = 0
                        downloadTotal = 0
                        persistedUpload = 0
                        persistedDownload = 0
                        lastPersistAt = 0
                        currentNodeName = node.name
                        currentNodeGroupName = node.getConnectionGroupName()
                        currentCore = prepared.selectedCore
                        internalSocksEndpoint = session.endpoint
                        isRunning = true
                        withContext(Dispatchers.Main) {
                            foreground(node.name, currentNodeGroupName, settings.proxyMode)
                            ServiceManager.notifyConnected(node)
                        }
                        TrafficReporter.reportPendingAsync(applicationContext, "vpn_connect")
                        trafficJob = serviceScope.launch {
                            while (isActive && controller.isCurrent(ticket)) {
                                delay(1000)
                                updateTraffic(controller.current?.traffic())
                                persistTraffic(false)
                            }
                        }
                        recovery.start()
                        Log.i(TAG, "Native VPN started: core=" + prepared.selectedCore)
                    } finally {
                        prepared.close()
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { cleanup() }
                    throw cancelled
                } catch (error: Exception) {
                    withContext(NonCancellable) { cleanup() }
                    if (controller.isCurrent(ticket)) {
                        Log.e(TAG, "VPN startup failed: " + error.javaClass.simpleName)
                        ServiceManager.notifyError("VPN 启动失败：" + error.message)
                        withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    }
                }
            }
        }
    }

    private suspend fun findNode(raw: String, name: String, settings: SettingsRepository): Node {
        val dao = AppDatabase.getInstance(applicationContext).nodeDao()
        val all = dao.getAllNodes().first()
        if (raw.isNotBlank() && name != "自动选择") {
            return all.firstOrNull { it.getRawLinkPlain() == raw } ?: error("所选节点已不存在")
        }
        val selectedId = settings.selectedNodeId.first()
        all.firstOrNull { it.id == selectedId && it.type != NodeType.UNKNOWN }?.let { return it }
        val selectedGroup = NodeGroupKey.parse(settings.selectedNodeGroupId.first())
        val candidates = when (selectedGroup) {
            NodeGroupKey.Favorites -> dao.getFavoriteNodes().first()
            is NodeGroupKey.Country -> dao.getSubscriptionNodes().first().filter {
                it.getCountryCode() == selectedGroup.countryCode
            }
            is NodeGroupKey.Subscription -> {
                val nodes = dao.getSubscriptionNodes().first()
                nodes.filter { it.subscriptionSourceId == selectedGroup.sourceId }.ifEmpty { nodes }
            }
            is NodeGroupKey.Imported -> dao.getNodesByGroup(selectedGroup.groupId).first()
            null -> when (settings.nodeListCategory.first()) {
                NodeListCategory.FAVORITES -> dao.getFavoriteNodes().first()
                NodeListCategory.PRIMARY -> {
                    val source = settings.selectedSubscriptionSourceId.first()
                    val nodes = dao.getSubscriptionNodes().first()
                    nodes.filter { source.isNullOrBlank() || it.subscriptionSourceId == source }
                        .ifEmpty { nodes }
                }
            }
        }
        return candidates.firstOrNull { it.type != NodeType.UNKNOWN && it.server.isNotBlank() && it.port in 1..65535 }
            ?: error("没有可用节点")
    }

    private fun stopVpn() {
        val ticket = controller.invalidate()
        isRunning = false
        operation?.cancel()
        operation = serviceScope.launch {
            operations.withLock {
                withContext(NonCancellable) { cleanup() }
                if (controller.isCurrent(ticket)) withContext(Dispatchers.Main) {
                    ServiceManager.notifyStopped()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun cleanup() {
        if (owner !== this) return
        recovery.stop()
        trafficJob?.cancelAndJoin()
        trafficJob = null
        val previous = controller.current
        try {
            updateTraffic(previous?.traffic())
            controller.stop()
            updateTraffic(previous?.traffic())
        } finally {
            persistTraffic(true)
            host?.close()
            host = null
            internalSocksEndpoint = null
            isRunning = false
            currentCore = null
            currentNodeName = null
            currentNodeGroupName = null
            activeNode = null
            ServiceManager.notifyStateChange()
            TrafficReporter.reportPendingAsync(applicationContext, "vpn_disconnect")
        }
    }

    private fun updateTraffic(stats: CoreTraffic?) {
        if (stats == null) return
        uploadTotal = maxOf(uploadTotal, stats.upload)
        downloadTotal = maxOf(downloadTotal, stats.download)
    }

    @Synchronized private fun persistTraffic(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 60_000) return
        val upload = (uploadTotal - persistedUpload).coerceAtLeast(0)
        val download = (downloadTotal - persistedDownload).coerceAtLeast(0)
        if ((upload == 0L && download == 0L) || LocalTrafficUsageStore.addDelta(applicationContext, now, upload, download)) {
            persistedUpload = uploadTotal
            persistedDownload = downloadTotal
            lastPersistAt = now
        }
    }

    private fun parseMode(value: String?): ProxyMode? = value?.let { runCatching { ProxyMode.valueOf(it) }.getOrNull() }
    override fun onRevoke() { stopVpn() }
    override fun onDestroy() {
        destroyed = true
        controller.invalidate()
        serviceScope.cancel()
        if (owner === this) {
            isRunning = false
            ServiceManager.notifyStopped()
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            operations.withLock {
                cleanup()
                if (owner === this@BoxVpnService) owner = null
            }
            cancel()
        }
        super.onDestroy()
    }
}
