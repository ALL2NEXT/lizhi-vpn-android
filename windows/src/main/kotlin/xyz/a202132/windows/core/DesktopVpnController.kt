package xyz.a202132.windows.core

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil
import xyz.a202132.windows.data.DesktopPreferencesStore
import xyz.a202132.windows.model.AppScreen
import xyz.a202132.windows.model.CoreStatus
import xyz.a202132.windows.model.DesktopAppState
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.BandwidthTestTargets
import xyz.a202132.windows.model.NodeCategory
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.ProtocolCoreSettings
import xyz.a202132.windows.model.ProxyCore
import xyz.a202132.windows.model.VpnState
import xyz.a202132.windows.model.UrlTestTargets
import xyz.a202132.windows.network.BackendClient
import xyz.a202132.windows.network.DirectBandwidthTestManager
import xyz.a202132.windows.network.NodeLocationResolver

class DesktopVpnController(
    private val locator: SingBoxLocator = SingBoxLocator(),
    private val xrayLocator: XrayLocator = XrayLocator(),
    private val nodeParser: NodeLinkParser = NodeLinkParser(),
    private val processManager: SingBoxProcessManager = SingBoxProcessManager(),
    private val xrayProcessManager: XrayProcessManager = XrayProcessManager(),
    private val urlTestManager: SingBoxUrlTestManager = SingBoxUrlTestManager(),
    private val bandwidthTestManager: SingBoxBandwidthTestManager = SingBoxBandwidthTestManager(),
    private val directBandwidthTestManager: DirectBandwidthTestManager = DirectBandwidthTestManager(),
    private val preferencesStore: DesktopPreferencesStore = DesktopPreferencesStore(),
    private val backendClient: BackendClient = BackendClient(preferencesStore)
) : VpnController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storedPreferences = preferencesStore.snapshot()
    private var refreshJob: Job? = null
    private var nodeLocationJob: Job? = null
    private var backendGeneration: Long = 0L
    private var recoveryJob: Job? = null
    private var trafficJob: Job? = null
    private var trafficSession: TrafficSession? = null
    private val mutableState = MutableStateFlow(
        DesktopAppState(
            tunEnabled = storedPreferences.tunEnabled,
            proxyMode = storedPreferences.proxyMode,
            selectedNodeId = storedPreferences.selectedNodeId,
            backendUrl = storedPreferences.backendUrl,
            settings = storedPreferences.settings,
            protocolCoreSettings = ProtocolCoreSettings.fromStored(storedPreferences.xrayProtocols)
        )
    )

    override val state: StateFlow<DesktopAppState> = mutableState.asStateFlow()

    override fun refreshCore() {
        mutableState.update {
            it.copy(coreStatus = CoreStatus.Checking, xrayCoreStatus = CoreStatus.Checking)
        }
        scope.launch {
            val singBoxResult = locator.locate()
            val xrayResult = xrayLocator.locate()
            val message = when {
                singBoxResult is CoreDiscoveryResult.Missing -> "未找到源码构建的 sing-box.exe"
                singBoxResult is CoreDiscoveryResult.Failed -> "sing-box 不可用：${singBoxResult.reason}"
                xrayResult is CoreDiscoveryResult.Missing -> "未找到源码构建的 xray.exe，Xray 协议暂不可用"
                xrayResult is CoreDiscoveryResult.Failed -> "Xray 不可用：${xrayResult.reason}"
                else -> null
            }
            mutableState.update {
                it.copy(
                    coreStatus = singBoxResult.toCoreStatus(),
                    xrayCoreStatus = xrayResult.toCoreStatus(),
                    message = message
                )
            }
        }
    }

    override fun setProxyMode(mode: ProxyMode) {
        val shouldRestart = mutableState.value.vpnState == VpnState.CONNECTED
        mutableState.update { it.copy(proxyMode = mode) }
        preferencesStore.update { it.copy(proxyMode = mode) }
        if (shouldRestart) {
            mutableState.value.selectedNodeId?.let(::startNode)
        }
    }

    override fun setTunEnabled(enabled: Boolean) {
        val current = mutableState.value
        if (current.tunEnabled == enabled) return
        mutableState.update { it.copy(tunEnabled = enabled) }
        preferencesStore.update { it.copy(tunEnabled = enabled) }
        if (current.vpnState == VpnState.CONNECTED) {
            current.selectedNodeId?.let(::startNode)
        }
    }

    override fun openDrawer() = mutableState.update { it.copy(drawerOpen = true) }

    override fun closeDrawer() = mutableState.update { it.copy(drawerOpen = false) }

    override fun navigate(screen: AppScreen) = mutableState.update {
        it.copy(screen = screen, drawerOpen = false)
    }

    override fun navigateBack() = mutableState.update {
        it.copy(screen = AppScreen.MAIN, drawerOpen = false)
    }

    override fun setNodeCategory(category: NodeCategory) = mutableState.update {
        it.copy(nodeCategory = category)
    }

    override fun setSubscriptionSource(sourceId: String) = mutableState.update {
        it.copy(
            nodeCategory = NodeCategory.SUBSCRIPTION,
            selectedSubscriptionSourceId = sourceId
        )
    }

    override fun importNodes(text: String) {
        scope.launch {
            val imported = nodeParser.parseSubscription(text)
            if (imported.isEmpty()) {
                mutableState.update { it.copy(message = "剪贴板中没有可识别的节点") }
                return@launch
            }
            mutableState.update { current ->
                val merged = mergeImportedNodes(current.nodes, imported)
                val favoriteIds = merged
                    .filter(DesktopNode::isFavorite)
                    .mapTo(mutableSetOf(), DesktopNode::id)
                preferencesStore.update { preferences ->
                    preferences.copy(favoriteNodeIds = favoriteIds)
                }
                current.copy(
                    nodes = merged,
                    nodeCategory = NodeCategory.FAVORITES,
                    selectedNodeId = current.selectedNodeId ?: imported.first().id,
                    message = "已导入 ${imported.size} 个节点"
                )
            }
        }
    }

    override fun toggleFavorite(nodeId: String) = mutableState.update { current ->
        val nodes = current.nodes.map { node ->
            if (node.id == nodeId) node.copy(isFavorite = !node.isFavorite) else node
        }
        preferencesStore.update { preferences ->
            preferences.copy(
                favoriteNodeIds = nodes.filter(DesktopNode::isFavorite)
                    .mapTo(mutableSetOf()) { it.id }
            )
        }
        current.copy(nodes = nodes)
    }

    override fun testTcping() {
        val nodes = mutableState.value.visibleNodes
        if (nodes.isEmpty()) {
            mutableState.update { it.copy(message = "没有可测试的节点") }
            return
        }
        scope.launch { runTcping(nodes) }
    }

    override fun testUrl() {
        val snapshot = mutableState.value
        val nodes = snapshot.visibleNodes
        val core = snapshot.coreStatus as? CoreStatus.Available
        if (nodes.isEmpty() || core == null || snapshot.isTesting) {
            mutableState.update { it.copy(message = "当前没有可执行 URL Test 的节点") }
            return
        }
        scope.launch {
            runUrlTest(core, nodes, snapshot.settings)
        }
    }

    private fun runUrlTest(
        core: CoreStatus.Available,
        nodes: List<DesktopNode>,
        settings: DesktopSettings
    ): List<DesktopNode> {
        mutableState.update {
            it.copy(
                isTesting = true,
                testingLabel = "URL Test 204 测试中 (0/${nodes.size})",
                message = null
            )
        }
        val latencies = runCatching { urlTestManager.test(core.executable, nodes, settings) }
            .getOrElse { error ->
                mutableState.update {
                    it.copy(
                        isTesting = false,
                        testingLabel = null,
                        message = "URL Test 失败：${error.message ?: "核心启动错误"}"
                    )
                }
                return emptyList()
            }
        val updated = nodes.map { node ->
            val latency = latencies[node.id] ?: DesktopNode.FAILED
            node.copy(urlTestLatency = latency, isAvailable = latency > 0)
        }
        val byId = updated.associateBy(DesktopNode::id)
        mutableState.update { current ->
            current.copy(
                nodes = current.nodes.map { byId[it.id] ?: it },
                isTesting = false,
                testingLabel = null
            )
        }
        return updated
    }

    override fun refreshNodes() {
        if (refreshJob?.isActive == true) return
        val generation = backendGeneration
        mutableState.update { it.copy(isLoadingNodes = true, message = null) }
        refreshJob = scope.launch {
            runCatching { backendClient.load(forceRefresh = true) }
                .onSuccess { result ->
                    if (generation != backendGeneration) return@onSuccess
                    val preferences = preferencesStore.snapshot()
                    var nodesForLocation = emptyList<DesktopNode>()
                    mutableState.update { current ->
                        val localFavorites = current.nodes.filter {
                            it.subscriptionSourceId == null && it.isFavorite
                        }
                        val remoteNodes = result.nodes.map { node ->
                            node.copy(isFavorite = node.id in preferences.favoriteNodeIds)
                        }
                        val nodes = (remoteNodes + localFavorites).distinctBy(DesktopNode::id)
                        val remembered = preferences.selectedNodeId?.takeIf { selected ->
                            nodes.any { it.id == selected }
                        }
                        val selected = remembered ?: nodes.firstOrNull()?.id
                        val selectedSource = current.selectedSubscriptionSourceId
                            ?.takeIf { sourceId -> remoteNodes.any { it.subscriptionSourceId == sourceId } }
                            ?: remoteNodes.firstOrNull()?.subscriptionSourceId
                        if (selected != preferences.selectedNodeId && current.settings.rememberLastSelectedNode) {
                            preferencesStore.update { it.copy(selectedNodeId = selected) }
                        }
                        nodesForLocation = nodes
                        current.copy(
                            nodes = nodes,
                            selectedNodeId = selected,
                            selectedSubscriptionSourceId = selectedSource,
                            account = result.account,
                            backendSettings = result.settings,
                            backendConnected = true,
                            isLoadingNodes = false,
                            subscriptionSourceCount = result.sourceCount,
                            message = if (nodes.isEmpty()) "当前账户暂无可用节点" else null
                        )
                    }
                    scheduleNodeLocationEnrichment(nodesForLocation, generation)
                }
                .onFailure { error ->
                    if (generation != backendGeneration) return@onFailure
                    mutableState.update {
                        it.copy(
                            backendConnected = false,
                            isLoadingNodes = false,
                            message = "节点同步失败：${error.message ?: "网络错误"}"
                        )
                    }
                }
            if (generation == backendGeneration) refreshJob = null
        }
    }

    override fun updateBackendUrl(value: String): Boolean {
        val normalized = DesktopPreferencesStore.normalizeBackendUrl(value)
        if (normalized == null) {
            mutableState.update { it.copy(message = "后台地址无效，请输入域名或 http(s) 地址") }
            return false
        }
        backendGeneration += 1
        refreshJob?.cancel()
        refreshJob = null
        nodeLocationJob?.cancel()
        nodeLocationJob = null
        preferencesStore.update { it.copy(backendUrl = normalized) }
        mutableState.update {
            it.copy(
                backendUrl = normalized,
                backendConnected = false,
                account = null,
                nodes = emptyList(),
                selectedNodeId = null,
                selectedSubscriptionSourceId = null,
                message = "后端地址已切换，正在刷新订阅"
            )
        }
        refreshNodes()
        return true
    }

    private fun scheduleNodeLocationEnrichment(nodes: List<DesktopNode>, generation: Long) {
        if (nodes.isEmpty()) return
        nodeLocationJob?.cancel()
        nodeLocationJob = scope.launch {
            delay(1_500)
            val enrichedNodes = NodeLocationResolver.enrichNodes(nodes)
            if (generation != backendGeneration) return@launch
            val byId = enrichedNodes.associateBy(DesktopNode::id)
            mutableState.update { current ->
                current.copy(nodes = current.nodes.map { byId[it.id] ?: it })
            }
            nodeLocationJob = null
        }
    }

    override fun updateSettings(settings: DesktopSettings) {
        val previous = mutableState.value.settings
        val sanitizedSettings = settings.copy(
            urlTestUrl = UrlTestTargets.normalize(settings.urlTestUrl) ?: previous.urlTestUrl,
            speedTestDownloadUrl = BandwidthTestTargets.normalize(settings.speedTestDownloadUrl)
                ?: previous.speedTestDownloadUrl
        )
        mutableState.update { current ->
            current.copy(
                settings = sanitizedSettings,
                nodeCategory = if (sanitizedSettings.hideFavoriteNodeGroup &&
                    current.nodeCategory == NodeCategory.FAVORITES
                ) NodeCategory.SUBSCRIPTION else current.nodeCategory
            )
        }
        preferencesStore.update { preferences ->
            preferences.copy(
                settings = sanitizedSettings,
                selectedNodeId = if (sanitizedSettings.rememberLastSelectedNode) {
                    preferences.selectedNodeId
                } else {
                    null
                }
            )
        }
        if (previous.disconnectRecoveryEnabled != sanitizedSettings.disconnectRecoveryEnabled) {
            if (sanitizedSettings.disconnectRecoveryEnabled &&
                mutableState.value.vpnState == VpnState.CONNECTED
            ) {
                mutableState.value.selectedNodeId?.let(::startRecoveryMonitor)
            } else {
                recoveryJob?.cancel()
                recoveryJob = null
            }
        }
    }

    override fun setProtocolCore(nodeType: NodeType, core: ProxyCore) {
        if (nodeType !in ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS) return
        val current = mutableState.value.protocolCoreSettings
        if (current.coreFor(nodeType) == core) return
        val selected = current.xrayProtocols.toMutableSet().apply {
            if (core == ProxyCore.XRAY) add(nodeType) else remove(nodeType)
        }
        val updated = ProtocolCoreSettings(selected)
        val shouldRestart = mutableState.value.vpnState == VpnState.CONNECTED
        mutableState.update { it.copy(protocolCoreSettings = updated) }
        preferencesStore.update { it.copy(xrayProtocols = updated.xrayProtocols) }
        if (shouldRestart) mutableState.value.selectedNodeId?.let(::startNode)
    }

    override fun adaptHysteriaBandwidth() {
        if (mutableState.value.isHysteriaBandwidthAdapting) return
        mutableState.update {
            it.copy(isHysteriaBandwidthAdapting = true, message = "正在测试直连网络上下行…")
        }
        scope.launch {
            runCatching {
                directBandwidthTestManager.test(
                    durationMs = mutableState.value.settings.speedTestDurationMs,
                    downloadUrl = mutableState.value.settings.speedTestDownloadUrl
                )
            }
                .onSuccess { result ->
                    val downMbps = roundUpHysteriaBandwidth(result.downloadMbps)
                    val upMbps = roundUpHysteriaBandwidth(result.uploadMbps)
                    val updated = mutableState.value.settings.copy(
                        hysteriaDownMbps = downMbps,
                        hysteriaUpMbps = upMbps
                    )
                    updateSettings(updated)
                    mutableState.update {
                        it.copy(
                            isHysteriaBandwidthAdapting = false,
                            message = "下行 %.1f → %d Mbps；上行 %.1f → %d Mbps".format(
                                java.util.Locale.US,
                                result.downloadMbps,
                                downMbps,
                                result.uploadMbps,
                                upMbps
                            )
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isHysteriaBandwidthAdapting = false,
                            message = "自适应失败：${error.message ?: "测速不可用"}"
                        )
                    }
                }
        }
    }

    private fun roundUpHysteriaBandwidth(speedMbps: Float): Int {
        if (!speedMbps.isFinite() || speedMbps <= 0f) return 0
        return (ceil(speedMbps / 10f).toInt() * 10).coerceIn(10, 100_000)
    }

    override fun updateNickname(nickname: String) = runAccountAction("昵称已更新") {
        backendClient.updateNickname(nickname)
    }

    override fun bindAccount(email: String, password: String, registrationCode: String) {
        val uid = mutableState.value.account?.uid
        if (uid == null) {
            mutableState.update { it.copy(message = "账户尚未同步") }
            return
        }
        runAccountAction("正式账户注册成功", refreshSubscriptions = true) {
            backendClient.bindAccount(uid, email, password, registrationCode)
        }
    }

    override fun loginAccount(email: String, password: String) =
        runAccountAction("账户切换成功", refreshSubscriptions = true) {
            backendClient.loginAccount(email, password)
        }

    override fun deleteAccount(password: String) {
        val account = mutableState.value.account
        if (account == null) {
            mutableState.update { it.copy(message = "账户尚未同步") }
            return
        }
        if (mutableState.value.vpnState != VpnState.DISCONNECTED) {
            mutableState.update { it.copy(message = "请先断开 VPN 再注销账户") }
            return
        }
        mutableState.update { it.copy(isAccountBusy = true, message = null) }
        scope.launch {
            runCatching { backendClient.deleteAccount(account.uid, password) }
                .onSuccess {
                    backendGeneration += 1
                    refreshJob?.cancel()
                    refreshJob = null
                    mutableState.update {
                        it.copy(
                            account = null,
                            nodes = emptyList(),
                            selectedNodeId = null,
                            backendConnected = false,
                            isAccountBusy = false,
                            screen = AppScreen.MAIN,
                            message = "账户已注销，本地节点已清除"
                        )
                    }
                    preferencesStore.update {
                        it.copy(
                            backendUrl = DesktopPreferencesStore.DEFAULT_BACKEND_URL,
                            selectedNodeId = null,
                            favoriteNodeIds = emptySet()
                        )
                    }
                    mutableState.update {
                        it.copy(backendUrl = DesktopPreferencesStore.DEFAULT_BACKEND_URL)
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isAccountBusy = false,
                            message = "账户注销失败：${error.message ?: "未知错误"}"
                        )
                    }
                }
        }
    }

    private fun runAccountAction(
        successMessage: String,
        refreshSubscriptions: Boolean = false,
        action: suspend () -> xyz.a202132.windows.model.DesktopAccount
    ) {
        if (mutableState.value.isAccountBusy) return
        mutableState.update { it.copy(isAccountBusy = true, message = null) }
        scope.launch {
            runCatching { action() }
                .onSuccess { account ->
                    mutableState.update {
                        it.copy(account = account, isAccountBusy = false, message = successMessage)
                    }
                    if (refreshSubscriptions) refreshNodes()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isAccountBusy = false,
                            message = "账户操作失败：${error.message ?: "未知错误"}"
                        )
                    }
                }
        }
    }

    override fun startSelectedOrFirstConnection() {
        val current = mutableState.value
        if (current.vpnState == VpnState.CONNECTED) {
            requestDisconnect()
            return
        }
        val node = current.currentNode ?: current.nodes.firstOrNull()
        if (node == null) {
            mutableState.update {
                it.copy(screen = AppScreen.NODE_LIST, message = "请先导入并选择节点")
            }
            return
        }
        startNode(node.id)
    }

    override fun startSmartConnection() {
        val current = mutableState.value
        if (current.vpnState != VpnState.DISCONNECTED || current.isTesting) return
        val scopedNodes = current.visibleNodes
        if (scopedNodes.isEmpty()) {
            mutableState.update {
                it.copy(screen = AppScreen.NODE_LIST, message = "当前节点组没有可用节点")
            }
            return
        }
        val core = current.coreStatus as? CoreStatus.Available
        if (core == null) {
            mutableState.update { it.copy(message = "sing-box 核心尚未就绪") }
            return
        }
        scope.launch {
            val tcpingNodes = takeTopPercent(scopedNodes, current.settings.smartConnectTcpingPercent)
            val tcpingCandidates = runTcping(tcpingNodes)
                .filter { it.isAvailable && it.tcpingLatency > 0 }
                .sortedBy(DesktopNode::tcpingLatency)
            if (tcpingCandidates.isEmpty()) {
                mutableState.update {
                    it.copy(isTesting = false, testingLabel = null, message = "TCPing 未找到可用节点")
                }
                return@launch
            }

            val urlNodes = takeTopPercent(
                tcpingCandidates,
                current.settings.smartConnectUrlTestPercent
            )
            val urlCandidates = runUrlTest(core, urlNodes, current.settings)
                .filter { it.isAvailable && it.urlTestLatency > 0 }
                .sortedBy(DesktopNode::urlTestLatency)
            if (urlCandidates.isEmpty()) {
                mutableState.update { it.copy(message = "URL Test 未找到可用节点") }
                return@launch
            }

            val speedNodes = takeTopPercent(
                urlCandidates,
                current.settings.smartConnectSpeedTestPercent
            )
            val speedCandidates = runBandwidthTest(core, speedNodes, current.settings)
                .filter { it.downloadMbps > 0f }
            val best = speedCandidates.maxByOrNull(DesktopNode::downloadMbps)
            if (best == null) {
                mutableState.update { it.copy(message = "带宽测速未找到可用节点") }
                return@launch
            }
            mutableState.update {
                it.copy(
                    selectedNodeId = best.id,
                    screen = AppScreen.MAIN,
                    isTesting = false,
                    testingLabel = null
                )
            }
            startNode(best.id)
        }
    }

    private fun runBandwidthTest(
        core: CoreStatus.Available,
        nodes: List<DesktopNode>,
        settings: DesktopSettings
    ): List<DesktopNode> {
        mutableState.update {
            it.copy(
                isTesting = true,
                testingLabel = "智能连接：带宽测速 (0/${nodes.size})",
                message = null
            )
        }
        val speeds = runCatching {
            bandwidthTestManager.test(core.executable, nodes, settings)
        }.getOrElse { error ->
            mutableState.update {
                it.copy(
                    isTesting = false,
                    testingLabel = null,
                    message = "带宽测试失败：${error.message ?: "核心启动错误"}"
                )
            }
            return emptyList()
        }
        val updated = nodes.map { node -> node.copy(downloadMbps = speeds[node.id] ?: 0f) }
        val byId = updated.associateBy(DesktopNode::id)
        mutableState.update { currentState ->
            currentState.copy(
                nodes = currentState.nodes.map { byId[it.id] ?: it },
                isTesting = false,
                testingLabel = null
            )
        }
        return updated
    }

    private fun <T> takeTopPercent(values: List<T>, percent: Int): List<T> {
        if (values.isEmpty()) return emptyList()
        val count = ceil(values.size * percent.coerceIn(1, 100) / 100.0)
            .toInt()
            .coerceIn(1, values.size)
        return values.take(count)
    }

    override fun selectAndConnectNode(nodeId: String) {
        if (mutableState.value.nodes.none { it.id == nodeId }) return
        mutableState.update {
            it.copy(selectedNodeId = nodeId, screen = AppScreen.MAIN, drawerOpen = false)
        }
        if (mutableState.value.settings.rememberLastSelectedNode) {
            preferencesStore.update { it.copy(selectedNodeId = nodeId) }
        }
        startNode(nodeId)
    }

    override fun requestDisconnect() {
        val state = mutableState.value.vpnState
        if (state == VpnState.DISCONNECTED || state == VpnState.DISCONNECTING) return
        mutableState.update { it.copy(vpnState = VpnState.DISCONNECTING, message = null) }
        recoveryJob?.cancel()
        recoveryJob = null
        scope.launch {
            val traffic = finishTrafficSession()
            processManager.stop()
            xrayProcessManager.stop()
            traffic?.let { reportTraffic(it) }
            delay(180)
            mutableState.update {
                it.copy(vpnState = VpnState.DISCONNECTED, message = "已断开连接")
            }
        }
    }

    override fun clearMessage() = mutableState.update { it.copy(message = null) }

    override fun close() {
        recoveryJob?.cancel()
        trafficJob?.cancel()
        processManager.close()
        xrayProcessManager.close()
        scope.cancel()
    }

    private fun startNode(nodeId: String) {
        val snapshot = mutableState.value
        if (snapshot.vpnState == VpnState.CONNECTING || snapshot.vpnState == VpnState.DISCONNECTING) return
        val core = snapshot.coreStatus as? CoreStatus.Available
        if (core == null) {
            mutableState.update { it.copy(message = "sing-box 核心尚未就绪") }
            return
        }
        val node = snapshot.nodes.firstOrNull { it.id == nodeId }
        if (node == null) {
            mutableState.update { it.copy(message = "所选节点已不存在") }
            return
        }

        mutableState.update {
            it.copy(
                selectedNodeId = node.id,
                vpnState = VpnState.CONNECTING,
                message = null
            )
        }
        if (snapshot.settings.rememberLastSelectedNode) {
            preferencesStore.update { it.copy(selectedNodeId = node.id) }
        }
        scope.launch {
            val preparedCores = runCatching {
                prepareAndStartProtocolCores(
                    nodes = mutableState.value.nodes,
                    selectedNodeId = node.id,
                    coreSettings = mutableState.value.protocolCoreSettings,
                    xrayStatus = mutableState.value.xrayCoreStatus,
                    settings = mutableState.value.settings
                )
            }.getOrElse { error ->
                xrayProcessManager.stop()
                mutableState.update {
                    it.copy(
                        vpnState = VpnState.DISCONNECTED,
                        message = formatConnectionFailure(error.message ?: "Xray 启动失败")
                    )
                }
                return@launch
            }
            when (
                val result = processManager.start(
                    executable = core.executable,
                    nodes = preparedCores.nodes,
                    selectedNodeId = node.id,
                    mode = mutableState.value.proxyMode,
                    tunEnabled = mutableState.value.tunEnabled,
                    settings = mutableState.value.settings,
                    xrayBridgePorts = preparedCores.bridgePorts
                )
            ) {
                CoreStartResult.Started -> {
                    mutableState.update { it.copy(vpnState = VpnState.CONNECTED, message = null) }
                    startTrafficSession(node)
                    if (mutableState.value.settings.disconnectRecoveryEnabled) {
                        startRecoveryMonitor(node.id)
                    }
                }
                is CoreStartResult.Failed -> {
                    xrayProcessManager.stop()
                    mutableState.update {
                        it.copy(
                            vpnState = VpnState.DISCONNECTED,
                            message = formatConnectionFailure(result.reason)
                        )
                    }
                }
            }
        }
    }

    private fun prepareAndStartProtocolCores(
        nodes: List<DesktopNode>,
        selectedNodeId: String,
        coreSettings: ProtocolCoreSettings,
        xrayStatus: CoreStatus,
        settings: DesktopSettings
    ): PreparedProtocolCores {
        xrayProcessManager.stop()
        val xrayNodes = nodes.filter { coreSettings.usesXray(it.type) }
        if (xrayNodes.isEmpty()) return PreparedProtocolCores(nodes, emptyMap())

        val selectedUsesXray = xrayNodes.any { it.id == selectedNodeId }
        val xrayExecutable = (xrayStatus as? CoreStatus.Available)?.executable
        if (xrayExecutable == null) {
            require(!selectedUsesXray) { "所选协议需要 Xray，但 Xray 核心尚未就绪" }
            val singBoxNodes = nodes.filterNot { coreSettings.usesXray(it.type) }
            require(singBoxNodes.isNotEmpty()) { "没有可由 sing-box 启动的节点" }
            return PreparedProtocolCores(singBoxNodes, emptyMap())
        }

        val allocatedPorts = allocateXrayBridgePorts(xrayNodes)
        val prepared = runCatching {
            XrayConfigPreparer(xrayProcessManager).prepare(
                executable = xrayExecutable,
                nodes = xrayNodes,
                bridgePorts = allocatedPorts,
                selectedNodeId = selectedNodeId,
                settings = settings
            )
        }.getOrElse { error ->
            if (selectedUsesXray) throw error
            val singBoxNodes = nodes.filterNot { coreSettings.usesXray(it.type) }
            require(singBoxNodes.isNotEmpty()) { error.message ?: "当前没有可用节点" }
            return PreparedProtocolCores(singBoxNodes, emptyMap())
        }

        val compatibleIds = prepared.nodes.mapTo(hashSetOf(), DesktopNode::id)
        val effectiveNodes = nodes.filter { node ->
            !coreSettings.usesXray(node.type) || node.id in compatibleIds
        }
        xrayProcessManager.start(
            executable = xrayExecutable,
            config = prepared.content,
            bridgePorts = prepared.bridgePorts.values,
            onUnexpectedExit = ::handleXrayUnexpectedExit
        ).getOrThrow()
        return PreparedProtocolCores(effectiveNodes, prepared.bridgePorts)
    }

    private fun allocateXrayBridgePorts(nodes: List<DesktopNode>): Map<String, Int> {
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

    private fun handleXrayUnexpectedExit(reason: String) {
        scope.launch {
            val snapshot = mutableState.value
            if (snapshot.vpnState == VpnState.DISCONNECTED || snapshot.vpnState == VpnState.DISCONNECTING) {
                return@launch
            }
            recoveryJob?.cancel()
            recoveryJob = null
            finishTrafficSession()?.let { reportTraffic(it) }
            processManager.stop()
            mutableState.update {
                it.copy(
                    vpnState = VpnState.DISCONNECTED,
                    message = if (snapshot.settings.disconnectRecoveryEnabled) {
                        "$reason，正在恢复连接"
                    } else {
                        reason
                    }
                )
            }
            if (snapshot.settings.disconnectRecoveryEnabled) {
                delay(1_000)
                snapshot.selectedNodeId?.let(::startNode)
            }
        }
    }

    private fun formatConnectionFailure(reason: String): String {
        val normalized = reason.lowercase()
        val permissionFailure = listOf(
            "access is denied",
            "permission denied",
            "requires administrator",
            "administrator privileges",
            "create tun",
            "start tun",
            "tun device"
        ).any(normalized::contains)
        return if (permissionFailure) {
            "连接失败：$reason。TUN 模式需以管理员身份运行"
        } else {
            "连接失败：$reason"
        }
    }

    private suspend fun runTcping(nodes: List<DesktopNode>): List<DesktopNode> {
        if (mutableState.value.isTesting) return nodes
        mutableState.update {
            it.copy(isTesting = true, testingLabel = "TCPing 测试中 (0/${nodes.size})", message = null)
        }
        var completed = 0
        val settings = mutableState.value.settings
        val semaphore = Semaphore(settings.tcpingConcurrency.coerceIn(1, 128))
        val results = nodes.map { node ->
            scope.async {
                val latency = semaphore.withPermit {
                    measureTcpLatency(node.server, node.port, settings.tcpingTimeoutMs)
                }
                completed += 1
                mutableState.update {
                    it.copy(testingLabel = "TCPing 测试中 ($completed/${nodes.size})")
                }
                node.copy(
                    tcpingLatency = latency,
                    isAvailable = latency > 0
                )
            }
        }.awaitAll()
        val resultsById = results.associateBy(DesktopNode::id)
        mutableState.update { current ->
            val updatedNodes = current.nodes.map { resultsById[it.id] ?: it }
            current.copy(
                nodes = if (settings.autoDeleteTimeoutNodes) {
                    updatedNodes.filter { node ->
                        node.id !in resultsById || node.tcpingLatency != DesktopNode.FAILED
                    }
                } else {
                    updatedNodes
                },
                isTesting = false,
                testingLabel = null
            )
        }
        return results
    }

    private fun measureTcpLatency(server: String, port: Int, timeoutMs: Int): Int {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, port), timeoutMs.coerceAtLeast(500))
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L).toInt()
        }.getOrDefault(DesktopNode.FAILED)
    }

    private fun startRecoveryMonitor(nodeId: String) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            while (mutableState.value.vpnState == VpnState.CONNECTED &&
                mutableState.value.settings.disconnectRecoveryEnabled
            ) {
                delay(5_000)
                if (processManager.isRunning()) continue
                repeat(2) { delay(1_000) }
                if (!processManager.isRunning() &&
                    mutableState.value.vpnState == VpnState.CONNECTED
                ) {
                    finishTrafficSession()?.let { reportTraffic(it) }
                    mutableState.update {
                        it.copy(vpnState = VpnState.DISCONNECTED, message = "连接已中断，正在恢复")
                    }
                    startNode(nodeId)
                    return@launch
                }
            }
        }
    }

    private fun startTrafficSession(node: DesktopNode) {
        trafficJob?.cancel()
        trafficSession = TrafficSession(
            id = UUID.randomUUID().toString(),
            subscriptionGroupName = node.subscriptionSourceName.orEmpty(),
            startedAt = System.currentTimeMillis()
        )
        trafficJob = scope.launch {
            while (processManager.isRunning()) {
                delay(5_000)
                processManager.trafficTotals()?.let { (upload, download) ->
                    trafficSession = trafficSession?.copy(
                        uploadBytes = maxOf(trafficSession?.uploadBytes ?: 0L, upload),
                        downloadBytes = maxOf(trafficSession?.downloadBytes ?: 0L, download)
                    )
                }
            }
        }
    }

    private fun finishTrafficSession(): TrafficSession? {
        trafficJob?.cancel()
        trafficJob = null
        processManager.trafficTotals()?.let { (upload, download) ->
            trafficSession = trafficSession?.copy(
                uploadBytes = maxOf(trafficSession?.uploadBytes ?: 0L, upload),
                downloadBytes = maxOf(trafficSession?.downloadBytes ?: 0L, download)
            )
        }
        return trafficSession?.copy(endedAt = System.currentTimeMillis()).also {
            trafficSession = null
        }
    }

    private suspend fun reportTraffic(session: TrafficSession) {
        runCatching {
            backendClient.reportTraffic(
                sessionId = session.id,
                subscriptionGroupName = session.subscriptionGroupName,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                uploadBytes = session.uploadBytes,
                downloadBytes = session.downloadBytes
            )
        }
    }

    private data class TrafficSession(
        val id: String,
        val subscriptionGroupName: String,
        val startedAt: Long,
        val endedAt: Long = startedAt,
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L
    )

}

private data class PreparedProtocolCores(
    val nodes: List<DesktopNode>,
    val bridgePorts: Map<String, Int>
)

private fun CoreDiscoveryResult.toCoreStatus(): CoreStatus = when (this) {
    is CoreDiscoveryResult.Available -> CoreStatus.Available(executable, version)
    is CoreDiscoveryResult.Missing -> CoreStatus.Missing
    is CoreDiscoveryResult.Failed -> CoreStatus.Failed(executable, reason)
}

internal fun mergeImportedNodes(
    existing: List<DesktopNode>,
    imported: List<DesktopNode>
): List<DesktopNode> {
    val importedIds = imported.mapTo(hashSetOf(), DesktopNode::id)
    val existingIds = existing.mapTo(hashSetOf(), DesktopNode::id)
    return existing.map { node ->
        if (node.id in importedIds) node.copy(isFavorite = true) else node
    } + imported
        .filterNot { it.id in existingIds }
        .map { it.copy(isFavorite = true) }
}
