package io.github.HanJason233.chimera.service

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.*
import io.github.HanJason233.chimera.data.local.AppDatabase
import io.github.HanJason233.chimera.data.local.LocalTrafficUsageStore
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.feature.nodegrouping.domain.CountryGroupingSourceResolver
import io.github.HanJason233.chimera.feature.nodegrouping.domain.WarehouseGroupingCountryResolver
import io.github.HanJason233.chimera.platform.AndroidVpnHost
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe

/** Android foreground host; native details live behind CoreBackend. */
class BoxVpnService : VpnService() {
    companion object {
        private const val TAG = "BoxVpnService"
        private const val TRAFFIC_REPORT_INTERVAL_MS = 30L * 60L * 1000L
        private const val FAILOVER_RETRY_BACKOFF_MS = 30_000L
        private val operations = Mutex()
        @Volatile private var owner: BoxVpnService? = null
        const val ACTION_START = "io.github.HanJason233.chimera.START_VPN"
        const val ACTION_STOP = "io.github.HanJason233.chimera.STOP_VPN"
        const val ACTION_RESTART = "io.github.HanJason233.chimera.RESTART_VPN"
        const val ACTION_SET_PROXY_MODE = "io.github.HanJason233.chimera.SET_PROXY_MODE"
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

        /** 当前 VPN 的应用内探测出口；仅在核心和 TUN 均已就绪时返回。 */
        internal fun activeSocksEndpoint(): LocalSocksEndpoint? =
            internalSocksEndpoint?.takeIf { isRunning }

        /** Resolve node entrances on the underlay, avoiding the VPN's fake-IP DNS. */
        internal fun activePhysicalNetwork(): android.net.Network? =
            owner?.host?.currentNetwork?.takeIf { isRunning }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = CoreSessionController()
    private val sessionFactory = VpnSessionFactory()
    private val notificationFactory = VpnNotificationFactory(this)
    private var operation: Job? = null
    private var trafficJob: Job? = null
    private var failoverJob: Job? = null
    private var host: AndroidVpnHost? = null
    private var activeNode: Node? = null
    private var persistedUpload = 0L
    private var persistedDownload = 0L
    private var lastPersistAt = 0L
    private val failoverStateLock = Any()
    private var failoverPrimaryNodeId: String? = null
    private val attemptedFailoverNodeIds = mutableSetOf<String>()
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        notificationFactory.createChannel()
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
            // Android 通过 sticky/always-on 机制重建服务时，恢复 VPN 前台会话。
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
        connect(node.getRawLinkPlain(), node.name, mode, node, preserveFailoverState = true)
    }

    private fun connect(
        raw: String,
        name: String,
        mode: ProxyMode?,
        knownNode: Node? = null,
        preserveFailoverState: Boolean = false,
        isFailoverAttempt: Boolean = false
    ) {
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
                var resolvedNodeForAttempt: Node? = knownNode
                try {
                    ensureActive()
                    val settingsRepo = SettingsRepository(applicationContext)
                    if (mode != null) settingsRepo.setProxyMode(mode)
                    val settings = settingsRepo.getVpnRuntimeSettings()
                    val node = knownNode ?: findNode(raw, name, settingsRepo)
                    resolvedNodeForAttempt = node
                    if (!preserveFailoverState) {
                        synchronized(failoverStateLock) {
                            failoverPrimaryNodeId = node.id
                            attemptedFailoverNodeIds.clear()
                        }
                    }
                    val prepared = sessionFactory.prepare(applicationContext, node, settings)
                    try {
                        ensureActive()
                        val vpnHost = AndroidVpnHost(this@BoxVpnService,
                            onCellular = ServiceManager::notifyCellularNetwork,
                            perAppInboundSettings = settings.perAppInboundSettings)
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
                        startFailoverMonitor(settingsRepo, ticket)
                        TrafficReporter.reportPendingAsync(applicationContext, "vpn_connect")
                        trafficJob = serviceScope.launch {
                            var nextReportAt = SystemClock.elapsedRealtime() + TRAFFIC_REPORT_INTERVAL_MS
                            while (isActive && controller.isCurrent(ticket)) {
                                delay(1000)
                                updateTraffic(controller.current?.traffic())
                                val reportDue = SystemClock.elapsedRealtime() >= nextReportAt
                                persistTraffic(force = reportDue)
                                if (reportDue) {
                                    if (isOfficialSourceNode(activeNode)) {
                                        TrafficReporter.reportPendingAsync(applicationContext, "vpn_periodic_30m")
                                    }
                                    nextReportAt = SystemClock.elapsedRealtime() + TRAFFIC_REPORT_INTERVAL_MS
                                }
                            }
                        }
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
                        if (isFailoverAttempt) {
                            val failedNode = resolvedNodeForAttempt
                            val latestSettingsRepository = SettingsRepository(applicationContext)
                            val latestFailover = latestSettingsRepository.failoverSettings.first()
                            val nextNode = if (latestFailover.detection.enabled) {
                                val primaryNodeId = synchronized(failoverStateLock) {
                                    failoverPrimaryNodeId
                                }
                                val candidates = resolveFailoverCandidates(
                                    latestFailover.transfer.target,
                                    latestSettingsRepository
                                ).filter { it.id != primaryNodeId }
                                nextFailoverNode(candidates, failedNode?.id)
                            } else {
                                null
                            }
                            if (nextNode != null) {
                                ServiceManager.notifyFailoverStatus(
                                    FailoverRuntimeState.SWITCHING,
                                    "转移节点 ${failedNode?.name ?: "当前节点"} 连接失败，继续尝试 ${nextNode.name}",
                                    failedNode?.id
                                )
                                serviceScope.launch {
                                    yield()
                                    connect(
                                        nextNode.getRawLinkPlain(), nextNode.name, null, nextNode,
                                        preserveFailoverState = true,
                                        isFailoverAttempt = true
                                    )
                                }
                            } else {
                                ServiceManager.notifyFailoverStatus(
                                    FailoverRuntimeState.ERROR,
                                    "转移节点均无法连接，VPN 已停止",
                                    failedNode?.id
                                )
                                ServiceManager.notifyError("故障转移失败：没有可连接的转移节点")
                                withContext(Dispatchers.Main) {
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        } else {
                            ServiceManager.notifyError("VPN 启动失败：" + error.message)
                            withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                        }
                    }
                }
            }
        }
    }

    private fun startFailoverMonitor(settingsRepository: SettingsRepository, ticket: Long) {
        failoverJob?.cancel()
        failoverJob = serviceScope.launch {
            var previousTransferTarget: FailoverTransferTarget? = null
            var hasTransferTargetSnapshot = false
            settingsRepository.failoverSettings.collectLatest { settings ->
                if (!controller.isCurrent(ticket)) return@collectLatest
                if (hasTransferTargetSnapshot && previousTransferTarget != settings.transfer.target) {
                    synchronized(failoverStateLock) { attemptedFailoverNodeIds.clear() }
                }
                previousTransferTarget = settings.transfer.target
                hasTransferTargetSnapshot = true
                val detection = settings.detection
                if (!detection.enabled) {
                    ServiceManager.notifyFailoverStatus(
                        FailoverRuntimeState.DISABLED,
                        "故障检测已关闭",
                        activeNode?.id
                    )
                    return@collectLatest
                }

                val primaryId = synchronized(failoverStateLock) { failoverPrimaryNodeId }
                val connectedNode = activeNode
                val isUsingBackup = connectedNode != null && connectedNode.id != primaryId
                ServiceManager.notifyFailoverStatus(
                    if (isUsingBackup) FailoverRuntimeState.USING_BACKUP else FailoverRuntimeState.MONITORING,
                    if (settings.transfer.target == null) {
                        "检测已开启，请选择备用节点或节点组"
                    } else if (isUsingBackup) {
                        "正在使用转移节点：${connectedNode?.name.orEmpty()}"
                    } else {
                        "每 ${FailoverDetectionSettings.PROBE_INTERVAL_SECONDS} 秒检测一次；转移目标已设置"
                    },
                    connectedNode?.id
                )

                var consecutiveFailures = 0
                val probeIntervalMs = FailoverDetectionSettings.PROBE_INTERVAL_SECONDS * 1_000L
                var nextProbeAt = SystemClock.elapsedRealtime() + probeIntervalMs
                while (isActive && controller.isCurrent(ticket)) {
                    val waitMs = (nextProbeAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    if (waitMs > 0L) delay(waitMs)
                    if (!isRunning) break
                    nextProbeAt = SystemClock.elapsedRealtime() + probeIntervalMs
                    val node = activeNode ?: break
                    if (host?.currentNetwork == null) {
                        ServiceManager.notifyFailoverStatus(
                            FailoverRuntimeState.WAITING_FOR_NETWORK,
                            "系统网络尚未验证，暂不切换节点",
                            node.id
                        )
                        continue
                    }
                    val endpoint = activeSocksEndpoint() ?: continue
                    ServiceManager.notifyFailoverStatus(
                        FailoverRuntimeState.DETECTING,
                        "正在检测当前节点",
                        node.id
                    )
                    val probeSucceeded = withContext(Dispatchers.IO) {
                        runCatching {
                            Socks5HttpProbe.probeHttpStatusCode(
                                endpoint,
                                detection.probeUrl,
                                FailoverDetectionSettings.PROBE_TIMEOUT_MS
                            ) in 200..399
                        }.getOrDefault(false)
                    }
                    if (probeSucceeded) {
                        consecutiveFailures = 0
                        val primaryStillActive = node.id == primaryId
                        ServiceManager.notifyFailoverStatus(
                            if (primaryStillActive) FailoverRuntimeState.MONITORING
                            else FailoverRuntimeState.USING_BACKUP,
                            if (primaryStillActive) {
                                "当前节点可用：HTTPS 测活通过"
                            } else {
                                "转移节点可用：${node.name}"
                            },
                            node.id
                        )
                        continue
                    }

                    consecutiveFailures += 1
                    if (consecutiveFailures < FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD) {
                        ServiceManager.notifyFailoverStatus(
                            FailoverRuntimeState.DETECTING,
                            "测活失败（$consecutiveFailures/${FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD}），等待下一次检测",
                            node.id
                        )
                        continue
                    }
                    consecutiveFailures = 0
                    ServiceManager.notifyFailoverStatus(
                        FailoverRuntimeState.DETECTING,
                        "连续失败 ${FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD} 次，正在尝试转移节点",
                        node.id
                    )

                    val primaryNodeId = synchronized(failoverStateLock) { failoverPrimaryNodeId }
                    val configuredCandidates = resolveFailoverCandidates(
                        settings.transfer.target,
                        settingsRepository
                    ).filter { it.id != primaryNodeId }
                    val nextNode = nextFailoverNode(configuredCandidates, node.id)
                    if (nextNode == null) {
                        val configuredBackups = configuredCandidates.size
                        val attemptedCount = synchronized(failoverStateLock) {
                            configuredCandidates.count { it.id in attemptedFailoverNodeIds }
                        }
                        ServiceManager.notifyFailoverStatus(
                            FailoverRuntimeState.NO_BACKUP,
                            when {
                                settings.transfer.target == null -> "请选择备用节点或节点组"
                                configuredBackups == 0 -> "备用目标中没有可用节点"
                                else -> "备用目标中的节点均已尝试，30 秒后重试"
                            },
                            node.id
                        )
                        if (configuredBackups > 0 && attemptedCount >= configuredBackups) {
                            delay(FAILOVER_RETRY_BACKOFF_MS)
                            nextProbeAt = SystemClock.elapsedRealtime() + probeIntervalMs
                            synchronized(failoverStateLock) {
                                attemptedFailoverNodeIds.removeAll { it != primaryNodeId && it != node.id }
                            }
                        }
                        continue
                    }

                    ServiceManager.notifyFailoverStatus(
                        FailoverRuntimeState.SWITCHING,
                        "检测到节点故障，正在切换到 ${nextNode.name}",
                        node.id
                    )
                    ServiceManager.notifyRestarting()
                    connect(
                        nextNode.getRawLinkPlain(),
                        nextNode.name,
                        null,
                        nextNode,
                        preserveFailoverState = true,
                        isFailoverAttempt = true
                    )
                    return@collectLatest
                }
            }
        }
    }
    private suspend fun resolveFailoverCandidates(
        target: FailoverTransferTarget?,
        settingsRepository: SettingsRepository
    ): List<Node> {
        if (target == null) return emptyList()
        val dao = AppDatabase.getInstance(applicationContext).nodeDao()
        val candidates = when (target) {
            is FailoverTransferTarget.Node -> listOfNotNull(dao.getNodeById(target.nodeId))
            is FailoverTransferTarget.Group -> when (val group = NodeGroupKey.parse(target.groupId)) {
                is NodeGroupKey.Subscription -> dao.getSubscriptionNodes().first()
                    .filter { it.subscriptionSourceId == group.sourceId }
                is NodeGroupKey.Imported -> dao.getNodesByGroup(group.groupId).first()
                is NodeGroupKey.Country -> countryGroupingNodes(
                    dao = dao,
                    allNodes = dao.getAllNodes().first(),
                    selectedSourceIds = settingsRepository.countryGroupingSourceIds.first()
                ).filter { it.getCountryCode() == group.countryCode }
                null -> emptyList()
            }
        }
        return candidates.distinctBy(Node::id).filter {
            it.type != NodeType.UNKNOWN && it.server.isNotBlank() && it.port in 1..65_535
        }
    }

    private fun nextFailoverNode(candidates: List<Node>, currentNodeId: String?): Node? {
        for (node in candidates
            .filter { it.id != currentNodeId }
            .shuffled()
        ) {
            val accepted = synchronized(failoverStateLock) {
                node.id !in attemptedFailoverNodeIds && attemptedFailoverNodeIds.add(node.id)
            }
            if (accepted) return node
        }
        return null
    }

    private suspend fun findNode(raw: String, name: String, settings: SettingsRepository): Node {
        val dao = AppDatabase.getInstance(applicationContext).nodeDao()
        val all = dao.getAllNodes().first()
        if (raw.isNotBlank() && name != "自动选择") {
            return all.firstOrNull { it.getRawLinkPlain() == raw } ?: error("所选节点已不存在")
        }
        val selectedId = settings.selectedNodeId.first()
        val selectedGroup = NodeGroupKey.parse(settings.selectedNodeGroupId.first())
        val warehouseCountryCodes = settings.warehouseGroupingCountryCodes.first()
        if (selectedGroup is NodeGroupKey.Country) {
            val regionNodes = countryGroupingNodes(
                dao = dao,
                allNodes = all,
                selectedSourceIds = settings.countryGroupingSourceIds.first()
            )
            val candidates = regionNodes.filter {
                it.getCountryCode() == selectedGroup.countryCode
            }
            candidates.firstOrNull { it.id == selectedId && it.type != NodeType.UNKNOWN }
                ?.let { return it }
            return candidates.firstOrNull {
                it.type != NodeType.UNKNOWN && it.server.isNotBlank() && it.port in 1..65535
            } ?: error("没有可用节点")
        }
        WarehouseGroupingCountryResolver.filter(all, warehouseCountryCodes)
            .firstOrNull { it.id == selectedId && it.type != NodeType.UNKNOWN }
            ?.let { return it }
        val candidates = when (selectedGroup) {
            is NodeGroupKey.Country -> emptyList()
            is NodeGroupKey.Subscription -> {
                val nodes = dao.getSubscriptionNodes().first()
                nodes.filter { it.subscriptionSourceId == selectedGroup.sourceId }.ifEmpty { nodes }
            }
            is NodeGroupKey.Imported -> dao.getNodesByGroup(selectedGroup.groupId).first()
            null -> dao.getSubscriptionNodes().first()
        }
        return WarehouseGroupingCountryResolver
            .filter(candidates, warehouseCountryCodes)
            .firstOrNull { it.type != NodeType.UNKNOWN && it.server.isNotBlank() && it.port in 1..65535 }
            ?: error("没有可用节点")
    }

    private suspend fun countryGroupingNodes(
        dao: NodeDao,
        allNodes: List<Node>,
        selectedSourceIds: Set<String>?
    ): List<Node> {
        if (selectedSourceIds == null) return allNodes
        val sources = CountryGroupingSourceResolver.resolve(selectedSourceIds)
        val personalNodes = if (sources.personalGroupIds.isEmpty()) {
            emptyList()
        } else {
            dao.getNodesByGroups(sources.personalGroupIds.toList()).first()
        }
        return CountryGroupingSourceResolver.nodes(allNodes, personalNodes, sources)
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
        failoverJob?.cancelAndJoin()
        failoverJob = null
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
        if (!isOfficialSourceNode(activeNode) || stats == null) return
        uploadTotal = maxOf(uploadTotal, stats.upload)
        downloadTotal = maxOf(downloadTotal, stats.download)
    }

    @Synchronized private fun persistTraffic(force: Boolean) {
        if (!isOfficialSourceNode(activeNode)) return
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

    // 仅统计官方订阅节点；私人源节点属于本地分组，不纳入流量汇总和服务器上报。
    private fun isOfficialSourceNode(node: Node?): Boolean =
        node?.source == NodeSource.SUBSCRIPTION && !node.subscriptionSourceId.isNullOrBlank()

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
