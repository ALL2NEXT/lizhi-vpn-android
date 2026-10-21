package io.github.HanJason233.chimera.service

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
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
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe
import io.github.HanJason233.chimera.platform.AndroidVpnHost
import io.github.HanJason233.chimera.core.xray.XrayVpnConfig
import java.util.concurrent.atomic.AtomicInteger

/** Android foreground host; native details live behind CoreBackend. */
class BoxVpnService : VpnService() {
    companion object {
        private const val TAG = "BoxVpnService"
        private const val TRAFFIC_REPORT_INTERVAL_MS = 30L * 60L * 1000L
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
    @Volatile private var failoverPrimaryNode: Node? = null
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
        val node = failoverPrimaryNode ?: ServiceManager.currentNode.value ?: activeNode
        if (node == null) { stopVpn(); return }
        ServiceManager.notifyRestarting()
        connect(node.getRawLinkPlain(), node.name, mode, node, preserveFailoverState = true)
    }

    private fun connect(
        raw: String,
        name: String,
        mode: ProxyMode?,
        knownNode: Node? = null,
        preserveFailoverState: Boolean = false
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
                try {
                    ensureActive()
                    val settingsRepo = SettingsRepository(applicationContext)
                    if (mode != null) settingsRepo.setProxyMode(mode)
                    val settings = settingsRepo.getVpnRuntimeSettings()
                    val node = knownNode ?: findNode(raw, name, settingsRepo)
                    if (!preserveFailoverState) failoverPrimaryNode = node
                    val failoverSettings = settingsRepo.failoverSettings.first()
                    val failoverCandidates = if (failoverSettings.detection.enabled) {
                        resolveFailoverCandidates(
                            failoverSettings.transfer.strategy,
                            node.id,
                            settingsRepo
                        )
                            .filterNot { it.id == node.id }
                    } else {
                        emptyList()
                    }
                    val prepared = sessionFactory.prepare(
                        applicationContext,
                        node,
                        settings,
                        failoverCandidates
                    )
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
                        startFailoverMonitor(
                            settingsRepo,
                            ticket,
                            node,
                            prepared.outboundNodes,
                            settings.proxyMode,
                            prepared.selectedCore,
                            failoverSettings,
                            prepared.core.request.failoverProbeEndpoints
                        )
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
                        ServiceManager.notifyError("VPN 启动失败：" + error.message)
                        withContext(Dispatchers.Main) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private fun startFailoverMonitor(
        settingsRepository: SettingsRepository,
        ticket: Long,
        primaryNode: Node,
        outboundNodes: List<Node>,
        proxyMode: ProxyMode,
        core: ProxyCore,
        loadedSettings: FailoverSettings,
        probeEndpoints: Map<String, LocalSocksEndpoint>
    ) {
        failoverJob?.cancel()
        failoverJob = serviceScope.launch {
            settingsRepository.failoverSettings.collectLatest { settings ->
                if (!controller.isCurrent(ticket)) return@collectLatest
                val settingsChanged = settings != loadedSettings
                if (!loadedSettings.detection.enabled || outboundNodes.size <= 1) {
                    ServiceManager.notifyFailoverStatus(
                        when {
                            settingsChanged -> FailoverRuntimeState.DETECTING
                            loadedSettings.detection.enabled -> FailoverRuntimeState.NO_BACKUP
                            else -> FailoverRuntimeState.DISABLED
                        },
                        when {
                            settingsChanged -> "故障转移设置已更改，重连后生效"
                            !loadedSettings.detection.enabled -> "故障检测已关闭"
                            loadedSettings.transfer.strategy == null -> "请设置转移策略"
                            loadedSettings.transfer.strategy == FailoverTransferStrategy.SecondBestTestResult ->
                                "没有可用的第二名测速线路，请先完成 URL 测速"
                            else -> "转移策略中没有当前核心支持的备用节点"
                        },
                        primaryNode.id
                    )
                    return@collectLatest
                }

                if (outboundNodes.any { it.id !in probeEndpoints }) {
                    ServiceManager.notifyFailoverStatus(
                        FailoverRuntimeState.NO_BACKUP,
                        "核心未准备好故障探测入口，请重连后重试",
                        primaryNode.id
                    )
                    return@collectLatest
                }

                coroutineScope {
                    var current = primaryNode
                    var consecutiveFailures = 0
                    var primaryRecoverySuccesses = 0
                    var warmupAttemptedFor: String? = null
                    var warmupJob: Deferred<Node?>? = null
                    var warmedCandidate: Node? = null
                    var switchNoticeShownFor: String? = null

                    while (isActive && controller.isCurrent(ticket)) {
                        val cycleStartedAt = SystemClock.elapsedRealtime()
                        val session = controller.current ?: break
                        val selectedTag = withContext(Dispatchers.IO) {
                            session.selectedOutboundTag()
                        }
                        val selectedNode = outboundNodes.mapIndexed { index, node ->
                            outboundTag(core, node, index) to node
                        }.firstOrNull { it.first == selectedTag }?.second ?: activeNode ?: current

                        if (selectedNode.id != current.id) {
                            current = selectedNode
                            consecutiveFailures = 0
                            primaryRecoverySuccesses = 0
                            warmupJob?.cancel()
                            warmupJob = null
                            warmedCandidate = null
                            warmupAttemptedFor = null
                            switchNoticeShownFor = null
                            updateActiveFailoverNode(selectedNode, proxyMode, ticket)
                        }

                        // 只探测当前出站。处于备用节点时，额外探测主节点用于自动恢复。
                        val activeProbe = async { probeFailoverNode(current, probeEndpoints, loadedSettings) }
                        val primaryProbe = if (current.id != primaryNode.id) {
                            async { probeFailoverNode(primaryNode, probeEndpoints, loadedSettings) }
                        } else null
                        val activeHealthy = activeProbe.await()

                        // 当前节点首次超时就立即预检策略选出的备用线路；之后不再周期探测。
                        if (!activeHealthy && warmupAttemptedFor != current.id) {
                            warmupAttemptedFor = current.id
                            warmedCandidate = null
                            warmupJob?.cancel()
                            warmupJob = async {
                                probeFailoverCandidates(
                                    activeNode = current,
                                    outboundNodes = outboundNodes,
                                    probeEndpoints = probeEndpoints,
                                    settings = loadedSettings
                                )
                            }
                        }

                        val primaryHealthy = primaryProbe?.await()
                        if (warmupJob?.isCompleted == true) {
                            warmedCandidate = warmupJob.await()
                            warmupJob = null
                        }

                        if (activeHealthy) {
                            consecutiveFailures = 0
                            warmupAttemptedFor = null
                            switchNoticeShownFor = null
                            warmupJob?.cancel()
                            warmupJob = null
                            warmedCandidate = null

                            if (current.id == primaryNode.id) {
                                primaryRecoverySuccesses = 0
                                ServiceManager.notifyFailoverStatus(
                                    FailoverRuntimeState.MONITORING,
                                    if (settingsChanged) {
                                        "主节点正常，设置更改将在重连后生效"
                                    } else {
                                        "正在检测当前节点：${primaryNode.name}"
                                    },
                                    primaryNode.id
                                )
                            } else {
                                if (primaryHealthy == true) primaryRecoverySuccesses++
                                else primaryRecoverySuccesses = 0

                                if (primaryRecoverySuccesses >= FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD) {
                                    if (selectFailoverNode(primaryNode, outboundNodes, core, proxyMode, ticket)) {
                                        current = primaryNode
                                        primaryRecoverySuccesses = 0
                                        warmupAttemptedFor = null
                                        ServiceManager.notifyFailoverStatus(
                                            FailoverRuntimeState.MONITORING,
                                            "主节点已恢复，已切回：${primaryNode.name}",
                                            primaryNode.id
                                        )
                                    }
                                } else {
                                    ServiceManager.notifyFailoverStatus(
                                        FailoverRuntimeState.USING_BACKUP,
                                        if (primaryRecoverySuccesses > 0) {
                                            "当前使用备用节点：${current.name}；正在确认主节点恢复"
                                        } else {
                                            "当前使用备用节点：${current.name}；VPN 隧道保持运行"
                                        },
                                        current.id
                                    )
                                }
                            }
                        } else {
                            consecutiveFailures++
                            primaryRecoverySuccesses = 0
                            val message = if (consecutiveFailures < FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD) {
                                if (current.id == primaryNode.id) {
                                    "主节点首次探测超时，正在预检备用线路"
                                } else {
                                    "备用节点首次探测超时，正在预检其他转移节点"
                                }
                            } else {
                                "当前节点连续探测超时，正在切换出站"
                            }
                            ServiceManager.notifyFailoverStatus(
                                FailoverRuntimeState.SWITCHING,
                                if (settingsChanged) "$message；设置更改将在重连后生效" else message,
                                current.id
                            )

                            if (consecutiveFailures >= FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD) {
                                if (switchNoticeShownFor != current.id) {
                                    switchNoticeShownFor = current.id
                                    showFailoverToast("当前节点连续两次探测失败，正在进行故障转移")
                                }
                                val candidate = warmedCandidate ?: warmupJob?.await()
                                if (candidate != null && candidate.id != current.id) {
                                    if (selectFailoverNode(candidate, outboundNodes, core, proxyMode, ticket)) {
                                        current = candidate
                                        consecutiveFailures = 0
                                        primaryRecoverySuccesses = 0
                                        warmupAttemptedFor = null
                                        warmupJob = null
                                        warmedCandidate = null
                                        ServiceManager.notifyFailoverStatus(
                                            FailoverRuntimeState.USING_BACKUP,
                                            "已切换到可用节点：${candidate.name}；VPN 隧道保持运行",
                                            candidate.id
                                        )
                                    } else {
                                        ServiceManager.notifyFailoverStatus(
                                            FailoverRuntimeState.ERROR,
                                            "核心切换到 ${candidate.name} 失败",
                                            current.id
                                        )
                                    }
                                } else {
                                    ServiceManager.notifyFailoverStatus(
                                        FailoverRuntimeState.NO_BACKUP,
                                        "备用线路预检失败，没有可用线路",
                                        current.id
                                    )
                                }
                            }
                        }

                        val elapsed = SystemClock.elapsedRealtime() - cycleStartedAt
                        delay((FailoverDetectionSettings.PROBE_INTERVAL_SECONDS * 1_000L - elapsed).coerceAtLeast(0L))
                    }
                }
            }
        }
    }

    private suspend fun probeFailoverNode(
        node: Node,
        probeEndpoints: Map<String, LocalSocksEndpoint>,
        settings: FailoverSettings
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = probeEndpoints[node.id] ?: return@withContext false
        try {
            Socks5HttpProbe.probeHttpStatusCode(
                endpoint,
                settings.detection.probeUrl,
                FailoverDetectionSettings.PROBE_TIMEOUT_MS
            ) in 200..399
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeFailoverCandidates(
        activeNode: Node,
        outboundNodes: List<Node>,
        probeEndpoints: Map<String, LocalSocksEndpoint>,
        settings: FailoverSettings
    ): Node? = coroutineScope {
        val candidates = outboundNodes.filter { it.id != activeNode.id && it.id in probeEndpoints }.shuffled()
        if (candidates.isEmpty()) return@coroutineScope null

        val nextCandidate = AtomicInteger()
        val winner = CompletableDeferred<Node?>()
        val workers = List(minOf(8, candidates.size)) {
            launch {
                while (isActive && !winner.isCompleted) {
                    val index = nextCandidate.getAndIncrement()
                    if (index >= candidates.size) break
                    val candidate = candidates[index]
                    if (probeFailoverNode(candidate, probeEndpoints, settings)) {
                        winner.complete(candidate)
                    }
                }
            }
        }
        launch {
            workers.joinAll()
            winner.complete(null)
        }
        val candidate = winner.await()
        workers.forEach { it.cancel() }
        candidate
    }

    private fun outboundTag(core: ProxyCore, node: Node, index: Int): String = when (core) {
        ProxyCore.SING_BOX -> node.id
        ProxyCore.XRAY -> XrayVpnConfig.failoverOutboundTag(index)
    }

    private suspend fun selectFailoverNode(
        node: Node,
        outboundNodes: List<Node>,
        core: ProxyCore,
        proxyMode: ProxyMode,
        ticket: Long
    ): Boolean {
        val session = controller.current ?: return false
        val index = outboundNodes.indexOfFirst { it.id == node.id }
        if (index < 0) return false
        val tag = outboundTag(core, node, index)
        val selected = withContext(Dispatchers.IO) { session.selectOutboundTag(tag) }
        if (!selected) return false

        repeat(20) {
            if (withContext(Dispatchers.IO) { session.selectedOutboundTag() } == tag) {
                updateActiveFailoverNode(node, proxyMode, ticket)
                showFailoverToast("已切换到节点：${node.name}，VPN 隧道保持运行")
                return true
            }
            delay(100)
        }
        return false
    }

    private suspend fun updateActiveFailoverNode(node: Node, proxyMode: ProxyMode, ticket: Long) {
        activeNode = node
        currentNodeName = node.name
        currentNodeGroupName = node.getConnectionGroupName()
        withContext(Dispatchers.Main) {
            if (controller.isCurrent(ticket)) {
                foreground(node.name, currentNodeGroupName, proxyMode)
                ServiceManager.notifyConnected(node)
            }
        }
    }

    private suspend fun showFailoverToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun resolveFailoverCandidates(
        strategy: FailoverTransferStrategy?,
        primaryNodeId: String,
        settingsRepository: SettingsRepository
    ): List<Node> {
        if (strategy == null) return emptyList()
        val dao = AppDatabase.getInstance(applicationContext).nodeDao()
        val candidates = when (strategy) {
            is FailoverTransferStrategy.SpecifiedNode ->
                listOfNotNull(dao.getNodeById(strategy.nodeId))
            is FailoverTransferStrategy.SpecifiedGroup -> when (val group = NodeGroupKey.parse(strategy.groupId)) {
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
            FailoverTransferStrategy.SecondBestTestResult -> {
                val rankedNodes = dao.getAllNodes().first()
                    .filter {
                        it.urlTestLatency > 0 && it.type != NodeType.UNKNOWN && it.server.isNotBlank() &&
                            it.port in 1..65_535
                    }
                    .sortedWith(
                        compareBy<Node> { it.urlTestLatency }
                            .thenBy { it.sortOrder }
                            .thenBy { it.id }
                    )
                val runnerUp = rankedNodes.getOrNull(1)
                val selected = if (runnerUp?.id == primaryNodeId) {
                    // 当前线路本身排第二时顺延到下一条可用线路，避免故障后选回当前线路。
                    rankedNodes.drop(2).firstOrNull { it.id != primaryNodeId }
                } else {
                    runnerUp
                }
                listOfNotNull(selected)
            }
        }
        return candidates.distinctBy(Node::id).filter {
            it.type != NodeType.UNKNOWN && it.server.isNotBlank() && it.port in 1..65_535
        }
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
