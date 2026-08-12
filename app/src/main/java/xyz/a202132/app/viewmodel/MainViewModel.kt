package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import io.nekohasekai.libbox.Libbox
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.local.NodeLatencyUpdate
import xyz.a202132.app.data.local.NodeLocationUpdate
import xyz.a202132.app.data.local.NodeDao
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NodeDownloadBandwidthTester
import xyz.a202132.app.network.SpeedTestService
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.network.NodeLocationResolver
import xyz.a202132.app.network.AccountManager
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import kotlin.math.ceil
import java.util.UUID

enum class NodeImportResult {
    IMPORTED,
    DUPLICATE,
    INVALID
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tag = "MainViewModel"
    // SQLCipher/KeyStore initialization and the first database open can be
    // expensive. Start it immediately for splash-screen preloading, but never
    // execute it on the UI thread.
    private val nodeDaoDeferred = viewModelScope.async(Dispatchers.IO) {
        AppDatabase.getInstance(application).nodeDao()
    }
    private val nodeDaoFlow: Flow<NodeDao> = flow { emit(nodeDaoDeferred.await()) }

    private suspend fun nodeDao(): NodeDao = nodeDaoDeferred.await()
    private val settingsRepository = SettingsRepository(application)
    private val subscriptionParser = SubscriptionParser()
    private val accountManager = AccountManager(application)
    private val configGenerator = SingBoxConfigGenerator()
    private val latencyTester = LatencyTester()
    private val bandwidthTester = NodeDownloadBandwidthTester(application)
    // 节流控制
    private val THROTTLE_INTERVAL = 5000L // 5秒节流间隔
    private var lastCheckUpdateTime = 0L
    private var trafficSessionId: String? = null
    private var trafficSessionStartedAt: Long? = null
    private var trafficSubscriptionGroupName: String = ""
    private var nodeLocationEnrichmentJob: Job? = null
    private val subscriptionDataLoader = SubscriptionDataLoader(
        context = application,
        settingsRepository = settingsRepository,
        accountManager = accountManager,
        nodeDao = { nodeDao() },
        activeNode = { activeConnectionNode.value },
        onNodesPersisted = ::scheduleNodeLocationEnrichment,
        onMessage = { message -> _error.value = message }
    )
    
    // UI状态
    val isLoading = subscriptionDataLoader.isLoading
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    private val _isHysteriaBandwidthAdapting = MutableStateFlow(false)
    val isHysteriaBandwidthAdapting = _isHysteriaBandwidthAdapting.asStateFlow()

    private val _hysteriaBandwidthAdaptiveStatus = MutableStateFlow<String?>(null)
    val hysteriaBandwidthAdaptiveStatus = _hysteriaBandwidthAdaptiveStatus.asStateFlow()
    
    // 测试类型标签 (用于 UI 显示)
    private val _testingLabel = MutableStateFlow<String?>(null)
    val testingLabel = _testingLabel.asStateFlow()
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    val backendSettings = subscriptionDataLoader.backendSettings

    val accountInfo = accountManager.accountInfo
    private val _isUpdatingNickname = MutableStateFlow(false)
    val isUpdatingNickname = _isUpdatingNickname.asStateFlow()
    private val _isBindingAccount = MutableStateFlow(false)
    val isBindingAccount = _isBindingAccount.asStateFlow()
    private val _isSwitchingAccount = MutableStateFlow(false)
    val isSwitchingAccount = _isSwitchingAccount.asStateFlow()
    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount = _isDeletingAccount.asStateFlow()
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage = _accountMessage.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    private val _startupUpdateCheckCompleted =
        MutableStateFlow(AppConfig.STARTUP_SPLASH_DURATION_SECONDS <= 0)
    val startupUpdateCheckCompleted = _startupUpdateCheckCompleted.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    private val _infoDialogMessage = MutableStateFlow<String?>(null)
    val infoDialogMessage = _infoDialogMessage.asStateFlow()
    // 仅在本次 APP 进程存活期间有效，不写入 DataStore。
    private val _skipFavoriteRemovalConfirmation = MutableStateFlow(false)
    val skipFavoriteRemovalConfirmation = _skipFavoriteRemovalConfirmation.asStateFlow()

    val vpnState = ServiceManager.vpnState

    /** The node owned by the live VPN session, independent of Room refreshes and UI filters. */
    private val activeConnectionNode = combine(
        vpnState,
        ServiceManager.currentNode
    ) { state, node ->
        node.takeIf { state != VpnState.DISCONNECTED }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ServiceManager.currentNode.value.takeIf {
            ServiceManager.vpnState.value != VpnState.DISCONNECTED
        }
    )
    
    val nodeListCategory = settingsRepository.nodeListCategory.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        NodeListCategory.PRIMARY
    )

    val subscriptionGroups = nodeDaoFlow.flatMapLatest { it.getSubscriptionGroups() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val storedSubscriptionSourceId = settingsRepository.selectedSubscriptionSourceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val activeSubscriptionSourceId = combine(
        subscriptionGroups,
        storedSubscriptionSourceId
    ) { groups, storedId ->
        storedId?.takeIf { id -> groups.any { it.sourceId == id } }
            ?: groups.firstOrNull()?.sourceId
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedSubscriptionSourceId = activeSubscriptionSourceId

    val favoriteSourceNodeIds = nodeDaoFlow.flatMapLatest { it.getFavoriteSourceNodeIds() }
        .map { it.filterNotNull().toSet() }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptySet()
        )

    private val subscriptionNodes = nodeDaoFlow
        .flatMapLatest { dao ->
            activeSubscriptionSourceId.flatMapLatest { sourceId ->
                if (sourceId.isNullOrBlank()) {
                    dao.getSubscriptionNodes()
                } else {
                    dao.getSubscriptionNodesBySource(sourceId)
                }
            }
        }

    private val favoriteNodes = nodeDaoFlow.flatMapLatest { it.getFavoriteNodes() }

    // Data
    private val visibleNodes = combine(
        subscriptionNodes,
        favoriteNodes,
        nodeListCategory
    ) { subscription, favorites, category ->
        if (category == NodeListCategory.FAVORITES) favorites else subscription
    }

    val nodes = combine(
        visibleNodes,
        activeConnectionNode
    ) { visibleNodes, connectedNode ->
        sortNodesForList(visibleNodes, pinnedNodeId = connectedNode?.id)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        emptyList()
    )
    
    // 初始化完成标志 - 用于防止 UI 闪烁
    private val _isInitialized = MutableStateFlow(false)
    
    val selectedNodeId = combine(
        settingsRepository.selectedNodeId,
        _isInitialized,
        activeConnectionNode
    ) { nodeId, initialized, connectedNode ->
        // 只有在初始化完成后才发出真实值，否则返回 null
        if (initialized) connectedNode?.id ?: nodeId else null
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    
    val proxyMode = settingsRepository.proxyMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        ProxyMode.SMART
    )
    
    val ipv6RoutingMode = settingsRepository.ipv6RoutingMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IPv6RoutingMode.DISABLED
    )
    
    val isUserAgreementAccepted = settingsRepository.isUserAgreementAccepted.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false
    )

    // 自动化测试设置
    val rememberLastSelectedNodeEnabled = settingsRepository.rememberLastSelectedNodeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val tcpingTestTimeoutMs = settingsRepository.tcpingTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.TCPING_TEST_TIMEOUT)
    val urlTestTimeoutMs = settingsRepository.urlTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.URL_TEST_TIMEOUT)
    val speedTestDownloadTimeoutMs = settingsRepository.speedTestDownloadTimeoutMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS)
    val tcpingConcurrency = settingsRepository.tcpingConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.TCPING_CONCURRENCY)
    val urlTestConcurrency = settingsRepository.urlTestConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.URL_TEST_CONCURRENCY)
    val bandwidthTestConcurrency = settingsRepository.bandwidthTestConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    val smartConnectTcpingPercent = settingsRepository.smartConnectTcpingPercent.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SMART_CONNECT_TCPING_PERCENT)
    val smartConnectUrlTestPercent = settingsRepository.smartConnectUrlTestPercent.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SMART_CONNECT_URL_TEST_PERCENT)
    val smartConnectSpeedTestPercent = settingsRepository.smartConnectSpeedTestPercent.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SMART_CONNECT_SPEED_TEST_PERCENT)
    val disconnectRecoveryEnabled = settingsRepository.disconnectRecoveryEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.DISCONNECT_RECOVERY_ENABLED)
    val autoDeleteTimeoutNodes = settingsRepository.autoDeleteTimeoutNodes.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.AUTO_DELETE_TIMEOUT_NODES)
    val hysteriaBandwidthEnabled = settingsRepository.hysteriaBandwidthEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_BANDWIDTH_ENABLED)
    val hysteriaUpMbps = settingsRepository.hysteriaUpMbps.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_UP_MBPS)
    val hysteriaDownMbps = settingsRepository.hysteriaDownMbps.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_DOWN_MBPS)
    val vpnMtu = settingsRepository.vpnMtu.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.VPN_MTU)
    val tunStack = settingsRepository.tunStack.stateIn(viewModelScope, SharingStarted.Eagerly, TunStack.GVISOR)
    private val selectedNodeFromDatabase = selectedNodeId
        .flatMapLatest { selectedId ->
            if (selectedId == null) {
                flowOf(null)
            } else {
                nodeDaoFlow.flatMapLatest { it.observeNodeById(selectedId) }
            }
        }

    val currentNode = combine(
        selectedNodeFromDatabase,
        activeConnectionNode
    ) { databaseNode, connectedNode ->
        when {
            connectedNode == null -> databaseNode
            databaseNode?.id == connectedNode.id -> databaseNode
            else -> connectedNode
        }
    }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    init {
        viewModelScope.launch {
            _startupUpdateCheckCompleted.value = false
            try {
                // Keep the cached selection available immediately; refreshing
                // data should not blank and rewrite DataStore on every launch.
                _isInitialized.value = true

                // Preload cached and remote data while the startup splash is
                // visible. User-agreement presentation must not serialize the
                // resource-loading pipeline.
                subscriptionDataLoader.loadCachedSubscriptionList()
                if (subscriptionDataLoader.initialize()) {
                    restoreRememberedLastSelectedNodeIfNeeded(
                        subscriptionDataLoader.lastFetchedNodes
                    )
                } else {
                    // A successful refresh schedules enrichment for the fresh
                    // nodes. Only fall back to cached nodes when refresh failed.
                    scheduleNodeLocationEnrichment(nodeDao().getAllNodes().first())
                }
                checkNotice()
                checkUpdateInternal(isAuto = true)
            } catch (error: Exception) {
                Log.e(tag, "Startup preload failed", error)
            } finally {
                _startupUpdateCheckCompleted.value = true
            }
        }
        
        // 监听 ServiceManager 的错误消息
        viewModelScope.launch {
            ServiceManager.errorMessage.collect { message ->
                if (message != null) {
                    _error.value = message
                    ServiceManager.clearError()
                }
            }
        }
        
        observeTrafficDisconnects()
    }

    private fun observeTrafficDisconnects() {
        viewModelScope.launch {
            var previousState = VpnState.DISCONNECTED
            ServiceManager.vpnState.collect { state ->
                if (state == VpnState.CONNECTED && previousState != VpnState.CONNECTED) {
                    trafficSessionId = UUID.randomUUID().toString()
                    trafficSessionStartedAt = System.currentTimeMillis()
                    trafficSubscriptionGroupName =
                        ServiceManager.currentNode.value?.subscriptionSourceName.orEmpty()
                }
                if (state == VpnState.DISCONNECTING) {
                    reportTrafficOnDisconnect()
                }
                previousState = state
            }
        }
    }

    private fun reportTrafficOnDisconnect() {
        val sessionId = trafficSessionId ?: return
        val startedAt = trafficSessionStartedAt ?: System.currentTimeMillis()
        val endedAt = System.currentTimeMillis()
        val subscriptionGroupName = trafficSubscriptionGroupName
        trafficSessionId = null
        trafficSessionStartedAt = null
        trafficSubscriptionGroupName = ""
        // Read the service counters directly before the stop routine resets them.
        val uploadBytes = BoxVpnService.uploadTotal.coerceAtLeast(0L)
        val downloadBytes = BoxVpnService.downloadTotal.coerceAtLeast(0L)
        viewModelScope.launch(Dispatchers.IO) {
            accountManager.reportTraffic(
                sessionId = sessionId,
                subscriptionGroupName = subscriptionGroupName,
                startedAt = startedAt,
                endedAt = endedAt,
                uploadBytes = uploadBytes,
                downloadBytes = downloadBytes
            ).onFailure { error ->
                Log.w(tag, "Traffic report failed", error)
            }
        }
    }

    private fun scheduleNodeLocationEnrichment(nodes: List<Node>) {
        if (nodes.isEmpty()) return
        nodeLocationEnrichmentJob?.cancel()
        nodeLocationEnrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1_500)
            enrichAndPersistNodeLocations(nodes)
        }
    }

    private suspend fun enrichAndPersistNodeLocations(nodes: List<Node>) {
        val sourceById = nodes.associateBy(Node::id)
        val updates = NodeLocationResolver.enrichNodes(nodes).mapNotNull { enrichedNode ->
            val sourceNode = sourceById[enrichedNode.id] ?: return@mapNotNull null
            if (
                sourceNode.country == enrichedNode.country &&
                sourceNode.countryName == enrichedNode.countryName
            ) {
                return@mapNotNull null
            }
            NodeLocationUpdate(
                nodeId = enrichedNode.id,
                country = enrichedNode.country,
                countryName = enrichedNode.countryName
            )
        }
        if (updates.isNotEmpty()) nodeDao().updateNodeLocations(updates)
    }

    fun testAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            if (rejectUnavailableAccount()) return@launch
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("TCPing 测试")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            _isTesting.value = true
            _testingLabel.value = "TCPing 测试中..."
            try {
                val currentNodes = targetNodes ?: currentNodeListSnapshot()
                internalTestNodes(currentNodes) { completed, total ->
                    _testingLabel.value = "TCPing 测试中 ($completed/$total)"
                }
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                GlobalTestExecution.finish()
            }
        }
    }

    /**
     * 智能连接：TCPing 全量筛选 → 204 URL Test → 5MB 下载测速，最后连接测速结果第一名。
     */
    fun startSmartConnection() {
        viewModelScope.launch {
            if (rejectUnavailableAccount()) return@launch
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("智能连接")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            _isTesting.value = true
            try {
                val allNodes = currentSmartConnectNodes()
                if (allNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }

                val tcpingNodes = takeTopPercent(allNodes, smartConnectTcpingPercent.value)
                _testingLabel.value = "智能连接：TCPing 测试中..."
                val tcpingResults = internalTestNodes(tcpingNodes) { completed, total ->
                    _testingLabel.value = "智能连接：TCPing ($completed/$total)"
                }
                val nodesById = allNodes.associateBy { it.id }
                val tcpingCandidates = tcpingResults
                    .filter { it.isAvailable && it.latency > 0 }
                    .sortedBy { it.latency }
                    .mapNotNull { nodesById[it.nodeId] }
                if (tcpingCandidates.isEmpty()) {
                    _error.value = "TCPing 未找到可用节点"
                    return@launch
                }

                val urlTestNodes = takeTopPercent(tcpingCandidates, smartConnectUrlTestPercent.value)
                val urlTestResults = runUrlTestNodes(
                    nodes = urlTestNodes,
                    labelPrefix = "智能连接：URL Test 204"
                )
                applyUrlTestResults(urlTestResults)
                val urlTestCandidates = urlTestResults
                    .filter { it.isAvailable && it.latency > 0 }
                    .sortedBy { it.latency }
                    .mapNotNull { nodesById[it.nodeId] }
                if (urlTestCandidates.isEmpty()) {
                    _error.value = "URL Test 未找到可用节点"
                    return@launch
                }

                val speedTestNodes = takeTopPercent(urlTestCandidates, smartConnectSpeedTestPercent.value)
                val speedResults = mutableListOf<Pair<Node, Float>>()
                speedTestNodes.forEachIndexed { index, node ->
                    _testingLabel.value = "智能连接：5MB 测速 (${index + 1}/${speedTestNodes.size})"
                    val speedMbps = testNodeDownloadBandwidthMbps(
                        node,
                        AppConfig.SMART_CONNECT_SPEED_TEST_SIZE_MB
                    )
                    nodeDao().updateDownloadMbps(node.id, speedMbps)
                    speedResults += node to speedMbps
                }

                val bestNode = speedResults
                    .sortedWith(
                        compareByDescending<Pair<Node, Float>> { it.second }
                            .thenBy { it.first.latency }
                    )
                    .firstOrNull()?.first
                    ?: speedTestNodes.firstOrNull()
                if (bestNode == null) {
                    _error.value = "测速未找到可连接节点"
                    return@launch
                }

                settingsRepository.setSelectedNodeId(bestNode.id)
                Log.i(tag, "Smart connection selected node: ${bestNode.getDisplayName()}")
                ServiceManager.startVpn(getApplication(), bestNode, proxyMode.value)
            } catch (e: Exception) {
                Log.e(tag, "Smart connection failed", e)
                _error.value = "智能连接失败: ${e.message ?: "未知错误"}"
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                GlobalTestExecution.finish()
            }
        }
    }

    /** Connect to the selected node, or to the first node in the active subscription. */
    fun startSelectedOrFirstConnection() {
        viewModelScope.launch {
            if (rejectUnavailableAccount()) return@launch
            val selectedId = settingsRepository.selectedNodeId.first()
            val selectedNode = selectedId?.let { nodeDao().getNodeById(it) }
            val node = selectedNode ?: currentSubscriptionNodeSnapshot().firstOrNull()
            if (node == null) {
                _error.value = "没有可用节点"
                return@launch
            }

            settingsRepository.setSelectedNodeId(node.id)
            Log.i(tag, "Connecting to selected/default node: ${node.getDisplayName()}")
            if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                Log.w(tag, "Default VPN start requested while network is unavailable")
                _error.value = "当前无网络连接节点！"
            }
            ServiceManager.startVpn(getApplication(), node, proxyMode.value)
        }
    }

    /**
     * URL Test 所有节点延迟 (通过 ClashAPI)
     * VPN 运行中 → 用现有 ClashAPI (port 9090)
     * VPN 未运行 → 启动临时无头 sing-box 实例 (port 19090)
     */
    fun urlTestAllNodes(targetNodes: List<Node>? = null) {
        if (rejectUnavailableAccount()) return
        urlTestAllNodesInternal(
            targetNodes = targetNodes,
            requireGlobalLock = true
        )
    }

    private fun urlTestAllNodesInternal(
        targetNodes: List<Node>? = null,
        requireGlobalLock: Boolean,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            var locked = false
            if (requireGlobalLock) {
                if (GlobalTestExecution.isFetching()) {
                    _error.value = GlobalTestExecution.fetchingHint()
                    return@launch
                }
                if (!GlobalTestExecution.tryStart("URL Test 测试")) {
                    _error.value = GlobalTestExecution.busyHint()
                    return@launch
                }
                locked = true
            }
            _isTesting.value = true
            _testingLabel.value = "URL Test 测试中..."
            try {
                val currentNodes = targetNodes ?: currentNodeListSnapshot()
                if (currentNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }
                val results = runUrlTestNodes(
                    nodes = currentNodes,
                    labelPrefix = "URL Test",
                    onProgress = onProgress
                )
                applyUrlTestResults(results)
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                if (locked) {
                    GlobalTestExecution.finish()
                }
            }
        }
    }
    
    fun setRememberLastSelectedNodeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRememberLastSelectedNodeEnabled(enabled)
        }
    }

    fun setTcpingTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setTcpingTestTimeoutMs(value)
        }
    }

    fun setUrlTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setUrlTestTimeoutMs(value)
        }
    }

    fun setSpeedTestDownloadTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setSpeedTestDownloadTimeoutMs(value)
        }
    }

    fun setTcpingConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setTcpingConcurrency(value)
        }
    }

    fun setUrlTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setUrlTestConcurrency(value)
        }
    }

    fun setBandwidthTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setBandwidthTestConcurrency(value)
        }
    }

    fun setSmartConnectTcpingPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setSmartConnectTcpingPercent(value)
        }
    }

    fun setDisconnectRecoveryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDisconnectRecoveryEnabled(enabled)
        }
    }

    fun setSmartConnectUrlTestPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setSmartConnectUrlTestPercent(value)
        }
    }

    fun setSmartConnectSpeedTestPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setSmartConnectSpeedTestPercent(value)
        }
    }

    fun setHysteriaUpMbps(value: Int) {
        viewModelScope.launch {
            settingsRepository.setHysteriaUpMbps(value)
        }
    }

    fun setHysteriaBandwidthEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHysteriaBandwidthEnabled(enabled)
        }
    }

    fun setHysteriaDownMbps(value: Int) {
        viewModelScope.launch {
            settingsRepository.setHysteriaDownMbps(value)
        }
    }

    fun setAutoDeleteTimeoutNodes(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoDeleteTimeoutNodes(enabled)
        }
    }

    /**
     * 使用直连网络分别测试下行和上行带宽，并按 10 Mbps 档位向上取整后写入 Hy2 设置。
     */
    fun adaptHysteriaBandwidth() {
        if (_isHysteriaBandwidthAdapting.value) return
        if (rejectUnavailableAccount()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isHysteriaBandwidthAdapting.value = true
            _hysteriaBandwidthAdaptiveStatus.value = "正在测试直连网络上下行..."

            try {
                val service = SpeedTestService(
                    downloadTimeoutMs = 25_000L,
                    uploadTimeoutMs = 25_000L
                )
                val testSize = 10_000_000L
                val downloadResult = service.startDownloadTest(testSize) { _, _ -> }
                val uploadResult = service.startUploadTest(testSize) { _, _ -> }
                val downMbps = roundUpHysteriaBandwidth(downloadResult.avgSpeedMbps)
                val upMbps = roundUpHysteriaBandwidth(uploadResult.avgSpeedMbps)

                if (downMbps <= 0 || upMbps <= 0) {
                    throw IllegalStateException("测速结果无效")
                }

                settingsRepository.setHysteriaDownMbps(downMbps)
                settingsRepository.setHysteriaUpMbps(upMbps)
                _hysteriaBandwidthAdaptiveStatus.value =
                    "下行 ${formatMbps(downloadResult.avgSpeedMbps)} → ${downMbps} Mbps；" +
                        "上行 ${formatMbps(uploadResult.avgSpeedMbps)} → ${upMbps} Mbps"
            } catch (e: Exception) {
                Log.e(tag, "Hy2 bandwidth adaptation failed", e)
                _hysteriaBandwidthAdaptiveStatus.value = "自适应失败：${e.message ?: "测速不可用"}"
            } finally {
                _isHysteriaBandwidthAdapting.value = false
            }
        }
    }

    private fun roundUpHysteriaBandwidth(speedMbps: Float): Int {
        if (!speedMbps.isFinite() || speedMbps <= 0f) return 0
        return (ceil(speedMbps / 10f).toInt() * 10).coerceIn(10, 100_000)
    }

    private fun formatMbps(speedMbps: Float): String {
        return "%.1f".format(java.util.Locale.US, speedMbps)
    }

    fun setVpnMtu(value: Int) {
        viewModelScope.launch {
            settingsRepository.setVpnMtu(value)
        }
    }

    private suspend fun runUrlTestNodes(
        nodes: List<Node>,
        labelPrefix: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        val isVpnRunning = vpnState.value == VpnState.CONNECTED
        val clashApiPort: Int
        var startedHeadless = false

        try {
            if (isVpnRunning) {
                clashApiPort = 9090
                Log.d(tag, "URL Test via existing VPN ClashAPI (port $clashApiPort)")
            } else {
                clashApiPort = xyz.a202132.app.network.UrlTestManager.CLASH_API_PORT
                Log.d(tag, "URL Test via headless instance (port $clashApiPort)")
                _testingLabel.value = "$labelPrefix：启动测试引擎..."

                val started = withContext(Dispatchers.IO) {
                    xyz.a202132.app.network.UrlTestManager.start(
                        getApplication(),
                        nodes,
                        hysteriaUpMbps.value,
                        hysteriaDownMbps.value
                    )
                }
                if (!started) {
                    _error.value = "启动测试引擎失败"
                    return emptyList()
                }
                startedHeadless = true

                delay(2000)
                val clashReady = withContext(Dispatchers.IO) {
                    var ready = false
                    for (retry in 1..6) {
                        try {
                            val checkUrl = java.net.URL("http://127.0.0.1:$clashApiPort/proxies")
                            val conn = checkUrl.openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 2000
                            conn.readTimeout = 2000
                            if (conn.responseCode == 200) {
                                ready = true
                                conn.disconnect()
                                break
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.d(tag, "ClashAPI not ready yet (attempt $retry/6): ${e.message}")
                        }
                        delay(1000)
                    }
                    ready
                }

                if (!clashReady) {
                    _error.value = "测试引擎启动超时"
                    return emptyList()
                }
                _testingLabel.value = "$labelPrefix 测试中..."
            }

            if (startedHeadless) {
                withContext(Dispatchers.IO) {
                    val proxiesResponse = xyz.a202132.app.network.UrlTestManager.diagnoseProxies()
                    Log.d(tag, "ClashAPI proxies: ${proxiesResponse?.take(500)}")
                }
            }

            Log.d(tag, "URL Testing ${nodes.size} nodes via ClashAPI (port=$clashApiPort)")
            val results = latencyTester.urlTestAllNodes(
                nodes = nodes,
                clashApiPort = clashApiPort,
                targetUrl = AppConfig.URL_TEST_URL,
                timeoutMs = urlTestTimeoutMs.value,
                concurrency = urlTestConcurrency.value
            ) { completed, total ->
                _testingLabel.value = "$labelPrefix 测试中 ($completed/$total)"
                onProgress?.invoke(completed, total)
            }
            Log.d(tag, "Got ${results.size} URL test results")
            return results
        } finally {
            if (startedHeadless) {
                withContext(Dispatchers.IO) {
                    val coreLog = xyz.a202132.app.network.UrlTestManager.readLogFile(getApplication())
                    if (coreLog != null) {
                        coreLog.lines().forEach { line ->
                            Log.d("SingBoxCoreLog", line)
                        }
                    } else {
                        Log.w(tag, "No sing-box core log available")
                    }
                }
                xyz.a202132.app.network.UrlTestManager.stop()
            }
        }
    }

    private suspend fun applyUrlTestResults(results: List<LatencyTester.LatencyResult>) {
        val updates = results.map { result ->
            NodeLatencyUpdate(result.nodeId, result.latency, result.isAvailable)
        }
        val updatedRows = nodeDao().updateUrlTestResults(updates, System.currentTimeMillis())
        if (updatedRows != updates.size) {
            Log.w(tag, "Only $updatedRows/${updates.size} URL test results were applied")
        }
        deleteTimedOutNodes(results)
    }

    private suspend fun deleteTimedOutNodes(results: List<LatencyTester.LatencyResult>) {
        if (!autoDeleteTimeoutNodes.value) return

        val connectedNodeId = activeConnectionNode.value?.id
        val timedOutNodeIds = results
            .filter { it.latency == -2 }
            .map { it.nodeId }
            .distinct()
            .filterNot { it == connectedNodeId }
        if (timedOutNodeIds.isEmpty()) return

        val selectedNodeId = settingsRepository.selectedNodeId.first()
        nodeDao().deleteNodesByIds(timedOutNodeIds)
        if (selectedNodeId != null && selectedNodeId in timedOutNodeIds) {
            settingsRepository.setSelectedNodeId(null)
        }
        _error.value = "已自动删除${timedOutNodeIds.size}个超时节点"
    }

    private fun sortNodesForList(
        list: List<Node>,
        pinnedNodeId: String? = null
    ): List<Node> {
        return list.sortedWith(
            compareByDescending<Node> { it.id == pinnedNodeId }
                .thenByDescending { it.isAvailable }
                .thenBy { it.sortOrder }
                .thenBy { if (it.latency >= 0) it.latency else Int.MAX_VALUE }
        )
    }

    /**
     * 内部测试逻辑 (Suspend)
     */
    private suspend fun internalTestNodes(
        currentNodes: List<Node>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        if (currentNodes.isEmpty()) return emptyList()
        
        Log.d(tag, "Testing ${currentNodes.size} nodes")
        
        // 统一使用 Socket 测试 (直接连接节点服务器测试可达性)
        // 即使 VPN 运行中也可以工作，因为测试的是节点服务器本身
        val results = latencyTester.testAllNodes(
            nodes = currentNodes,
            timeoutMs = tcpingTestTimeoutMs.value,
            concurrency = tcpingConcurrency.value,
            onProgress = onProgress
        )
        Log.d(tag, "Got ${results.size} test results")
        
        val updates = results.map { result ->
            NodeLatencyUpdate(result.nodeId, result.latency, result.isAvailable)
        }
        nodeDao().updateTcpingResults(updates, System.currentTimeMillis())
        deleteTimedOutNodes(results)
        return results
    }

    /**
     * 选择节点
     */
    fun selectNode(node: Node) {
        viewModelScope.launch {
            if (rejectUnavailableAccount()) return@launch
            settingsRepository.setSelectedNodeId(node.id)
            Log.i(tag, "Selected node changed: source=${node.source}, type=${node.type}")
            
            // If VPN is connected, restart to switch to new node
            if (vpnState.value == VpnState.CONNECTED) {
                // Notify user (Optional)
                Log.i(tag, "Restarting VPN to apply new Node: ${node.name}")
                Log.i(tag, "Restarting VPN after node selection")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }

    fun setNodeListCategory(category: NodeListCategory) {
        viewModelScope.launch {
            settingsRepository.setNodeListCategory(category)
        }
    }

    /** Select a node from the node list and connect to it immediately. */
    fun selectAndConnectNode(node: Node) {
        viewModelScope.launch {
            if (rejectUnavailableAccount()) return@launch
            settingsRepository.setSelectedNodeId(node.id)
            Log.i(tag, "Connecting directly to selected node: ${node.getDisplayName()}")

            when (vpnState.value) {
                VpnState.DISCONNECTED -> {
                    if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                        Log.w(tag, "Direct VPN start requested while network is unavailable")
                        _error.value = "当前无网络连接节点！"
                    }
                    ServiceManager.startVpn(getApplication(), node, proxyMode.value)
                }
                VpnState.CONNECTED -> {
                    ServiceManager.startVpn(getApplication(), node, proxyMode.value)
                }
                else -> {
                    Log.i(tag, "Direct node connection ignored while VPN is transitioning")
                }
            }
        }
    }

    fun setSubscriptionSource(sourceId: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedSubscriptionSourceId(sourceId)
            settingsRepository.setSelectedNodeId(null)
            settingsRepository.setNodeListCategory(NodeListCategory.PRIMARY)
        }
    }

    fun skipFavoriteRemovalConfirmationForSession() {
        _skipFavoriteRemovalConfirmation.value = true
    }

    fun toggleFavoriteNode(node: Node) {
        viewModelScope.launch(Dispatchers.IO) {
            if (node.source == NodeSource.FAVORITE) {
                nodeDao().deleteNodeById(node.id)
                if (settingsRepository.selectedNodeId.first() == node.id) {
                    settingsRepository.setSelectedNodeId(null)
                }
                return@launch
            }

            val existingFavorite = nodeDao().getFavoriteBySourceNodeId(node.id)
            if (existingFavorite != null) {
                nodeDao().deleteNodeById(existingFavorite.id)
                if (settingsRepository.selectedNodeId.first() == existingFavorite.id) {
                    settingsRepository.setSelectedNodeId(null)
                }
                return@launch
            }

            val now = System.currentTimeMillis()
            nodeDao().insertNode(
                node.copy(
                    id = "fav_${UUID.randomUUID()}",
                    source = NodeSource.FAVORITE,
                    favoriteSourceNodeId = node.id,
                    favoriteCreatedAt = now
                )
            )
        }
    }

    fun importNodesToFavoritesFromText(text: String, onResult: (NodeImportResult, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val parsedNodes = subscriptionParser.parseSubscription(text)
                if (parsedNodes.isEmpty()) return@withContext NodeImportResult.INVALID to 0

                setupLibboxForConfigCheck()
                val coreCompatibleNodes = parsedNodes.filter { node ->
                    isNodeCoreConfigCompatible(node)
                }
                if (coreCompatibleNodes.isEmpty()) return@withContext NodeImportResult.INVALID to 0

                val existingFavoriteRawLinks = nodeDao().getFavoriteNodes().first()
                    .map { it.getRawLinkPlain() }
                    .toMutableSet()
                val uniqueNodes = coreCompatibleNodes.filter { node ->
                    existingFavoriteRawLinks.add(node.getRawLinkPlain())
                }
                if (uniqueNodes.isEmpty()) return@withContext NodeImportResult.DUPLICATE to 0

                val favorites = uniqueNodes.mapIndexed { index, node ->
                    node.copy(
                        id = "fav_${UUID.randomUUID()}",
                        source = NodeSource.FAVORITE,
                        favoriteSourceNodeId = null,
                        favoriteCreatedAt = now + index,
                        latency = -1,
                        isAvailable = true,
                        lastTestedAt = 0L,
                        downloadMbps = 0f,
                        uploadMbps = 0f,
                    )
                }
                nodeDao().insertNodes(favorites)
                NodeImportResult.IMPORTED to favorites.size
            }
            val result = importResult.first
            val importedCount = importResult.second

            if (result == NodeImportResult.IMPORTED) {
                settingsRepository.setNodeListCategory(NodeListCategory.FAVORITES)
                Log.i(tag, "Imported $importedCount nodes to favorites")
            } else {
                Log.w(tag, "Node import finished without new nodes: result=$result")
            }
            onResult(result, importedCount)
        }
    }

    private fun setupLibboxForConfigCheck() {
        val app = getApplication<Application>()
        val workDir = File(app.filesDir, "sing-box")
        if (!workDir.exists()) workDir.mkdirs()
        val options = io.nekohasekai.libbox.SetupOptions().apply {
            basePath = workDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = app.cacheDir.absolutePath
        }
        Libbox.setup(options)
    }

    private fun isNodeCoreConfigCompatible(node: Node): Boolean {
        return runCatching {
            val config = configGenerator.generateTestConfig(node)
            Libbox.checkConfig(config)
        }.onFailure { error ->
            Log.w(tag, "Skip incompatible imported node: type=${node.type}, server=${node.server}, error=${error.message}")
            Log.w(tag, "Skip incompatible imported node: type=${node.type}", error)
        }.isSuccess
    }
    
    /**
     * 切换代理模式
     */
    fun setProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            settingsRepository.setProxyMode(mode)
            
            // If VPN is connected, restart to apply new mode config
            if (vpnState.value == VpnState.CONNECTED) {
                currentNode.value?.let { node ->
                    // Notify user (Optional, usually ConnectButton shows 'Connecting...')
                    Log.i(tag, "Restarting VPN to apply Proxy Mode: $mode")
                    ServiceManager.startVpn(getApplication(), node, mode)
                }
            }
        }
    }
    
    /**
     * 切换VPN连接
     */
    fun toggleVpn() {
        when (vpnState.value) {
            VpnState.DISCONNECTED -> {
                if (rejectUnavailableAccount()) return
                val node = currentNode.value ?: run {
                    Log.w(tag, "VPN start ignored: no selected node")
                    _error.value = "请先选择节点"
                    return
                }

                viewModelScope.launch {
                    // 4. 无网络连接节点时给用户发Toast，但不阻止
                    if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                        Log.w(tag, "VPN start requested while network is unavailable")
                        _error.value = "当前无网络连接节点！"
                    }
                    Log.i(tag, "VPN start requested from UI")
                    ServiceManager.startVpn(getApplication(), node, proxyMode.value)
                }
            }
            VpnState.CONNECTED -> {
                Log.i(tag, "VPN stop requested from UI")
                ServiceManager.stopVpn(getApplication())
            }
            else -> {
                // 正在连接或断开中，忽略
            }
        }
    }
    
    /**
     * 重启 VPN 如果正在运行 (用于应用设置变更)
     */
    fun restartVpnIfNeeded() {
        if (rejectUnavailableAccount()) return
        if (vpnState.value == VpnState.CONNECTED) {
            currentNode.value?.let { node ->
                Log.i(tag, "Settings changed, restarting VPN to apply...")
                Log.i(tag, "Restarting VPN after settings change")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    /**
     * 设置 IPv6 路由模式
     */
    fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        viewModelScope.launch {
            settingsRepository.setIPv6RoutingMode(mode)
            // 如果 VPN 正在运行，重启以应用新设置
            restartVpnIfNeeded()
        }
    }

    fun setTunStack(stack: TunStack) {
        viewModelScope.launch {
            settingsRepository.setTunStack(stack)
            restartVpnIfNeeded()
        }
    }

    private suspend fun checkNotice() {
        val result = subscriptionDataLoader.noticeConfig?.let { Result.success(it) }
            ?: Result.failure<NoticeInfo>(IllegalStateException("backend config unavailable"))
        result.onSuccess { noticeInfo ->
            if (noticeInfo.hasNotice) {
                // 如果 showOnce 为 true，检查是否已显示过
                // 如果 showOnce 为 false，则每次都显示
                if (!noticeInfo.showOnce) {
                    _notice.value = noticeInfo
                    // 更新最后显示的 ID，以便后续如果服务器端改为 true，也能正确判断
                    settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                } else {
                    val lastNoticeId = settingsRepository.lastNoticeId.first()
                    if (lastNoticeId != noticeInfo.noticeId) {
                        _notice.value = noticeInfo
                        settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                    }
                }
            }
        }.onFailure { e ->
            Log.e(tag, "Failed to check notice", e)
            Log.w(tag, "Failed to check notice", e)
        }
    }
    
    
    /**
     * 检查更新
     * @param isAuto 是否为自动检查 (不显示"已是最新"提示)
     */
    fun checkUpdate(isAuto: Boolean = false, trackStartupSplash: Boolean = false) {
        viewModelScope.launch {
            checkUpdateInternal(isAuto, trackStartupSplash)
        }
    }

    private suspend fun checkUpdateInternal(
        isAuto: Boolean,
        trackStartupSplash: Boolean = false
    ) {
        if (trackStartupSplash) {
            _startupUpdateCheckCompleted.value = false
        }
        try {
            if (subscriptionDataLoader.updateInfo == null) {
                return
            }
            // 节流检查 (自动检查可跳过)
            if (!isAuto) {
                val now = System.currentTimeMillis()
                if (now - lastCheckUpdateTime < THROTTLE_INTERVAL) {
                    _error.value = "操作过于频繁，请稍后再试"
                    return
                }
                lastCheckUpdateTime = now
            }
            
            val result = subscriptionDataLoader.updateInfo?.let { Result.success(it) }
                ?: Result.failure<UpdateInfo>(IllegalStateException("backend update metadata unavailable"))
            result.onSuccess { info ->
                val currentVersionCode = getApplication<Application>().packageManager
                    .getPackageInfo(getApplication<Application>().packageName, 0)
                    .let { 
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            it.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            it.versionCode
                        }
                    }
                
                if (info.versionCode > currentVersionCode) {
                    val ignoredVersionCode =
                        settingsRepository.ignoredUpdateVersionCode.first()
                    val isIgnoredOptionalUpdate =
                        info.isForce != 1 && ignoredVersionCode == info.versionCode

                    if (isIgnoredOptionalUpdate) {
                        _updateInfo.value = null
                        if (!isAuto) {
                            _error.value = "当前新版本已被忽略"
                        }
                    } else {
                        _updateInfo.value = info
                    }
                } else {
                    if (!isAuto) {
                        _error.value = "已是最新版本"
                    }
                }
            }.onFailure { e ->
                Log.e(tag, "Failed to check update", e)
                Log.w(tag, "Failed to check update", e)
            }
        } finally {
            if (trackStartupSplash) {
                _startupUpdateCheckCompleted.value = true
            }
        }
    }
    
    // 下载状态
    val downloadState = DownloadManager.downloadState
    
    /**
     * 打开下载链接 (现在改为应用内下载)
     */
    fun openDownloadUrl() {
        updateInfo.value?.let { info ->
            // 使用 DownloadManager 检查文件是否已下载且有效
            val existingFile = DownloadManager.isApkReady(getApplication(), info.version)
            if (existingFile != null) {
                // 此处强制执行 installApk 逻辑
                installApk()
                return
            }
            
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun pauseDownload() {
        DownloadManager.pauseDownload()
    }
    
    fun resumeDownload() {
        updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun retryDownload() {
         updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun cancelDownload() {
        DownloadManager.cancelDownload()
    }
    
    fun installApk() {
        val targetFile = downloadState.value.file ?: run {
             updateInfo.value?.let { info ->
                 DownloadManager.isApkReady(getApplication(), info.version)
             }
        }

        targetFile?.let { file ->
            val context = getApplication<Application>()
            try {
                if (file.exists()) { 
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                } else {
                     _error.value = "安装文件丢失，请重新下载"
                     // 重置状态以允许重新下载
                     DownloadManager.resetState()
                }
            } catch (e: Exception) {
                Log.e(tag, "Install failed", e)
                _error.value = "无法启动安装程序: ${e.message}"
            }
        } ?: run {
             _error.value = "找不到安装包，请重试下载"
             DownloadManager.resetState()
        }
    }
    
    fun dismissNotice() {
        _notice.value = null
    }
    
    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun ignoreCurrentUpdate() {
        val info = _updateInfo.value ?: return
        if (info.isForce == 1) return

        _updateInfo.value = null
        viewModelScope.launch {
            settingsRepository.setIgnoredUpdateVersionCode(info.versionCode)
            Log.i(tag, "Ignored optional update: versionCode=${info.versionCode}")
        }
    }
    
    fun clearError() {
        _error.value = null
    }

    fun clearInfoDialogMessage() {
        _infoDialogMessage.value = null
    }

    fun updateNickname(nickname: String) {
        if (_isUpdatingNickname.value) return
        viewModelScope.launch {
            _isUpdatingNickname.value = true
            _accountMessage.value = null
            try {
                accountManager.updateNickname(
                    nickname = nickname,
                    timeoutMs = backendSettings.value.nodeRequestTimeoutMs
                ).onFailure { error ->
                    Log.w(tag, "Failed to update nickname", error)
                    val message = error.message ?: "昵称修改失败"
                    _error.value = message
                    _accountMessage.value = message
                }.onSuccess {
                    _accountMessage.value = "昵称已更新"
                }
            } finally {
                _isUpdatingNickname.value = false
            }
        }
    }

    fun bindEmailPassword(email: String, password: String) {
        if (_isBindingAccount.value) return
        viewModelScope.launch {
            _isBindingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.bindEmailPassword(
                    email = email,
                    password = password,
                    timeoutMs = backendSettings.value.nodeRequestTimeoutMs
                ).onSuccess {
                    _accountMessage.value = "绑定成功，已获得绑定经验"
                }.onFailure { error ->
                    Log.w(tag, "Failed to bind account credentials", error)
                    _accountMessage.value = accountBindingErrorMessage(error)
                }
            } finally {
                _isBindingAccount.value = false
            }
        }
    }

    fun clearAccountMessage() {
        _accountMessage.value = null
    }

    fun switchAccount(email: String, password: String) {
        if (_isSwitchingAccount.value || _isDeletingAccount.value) return
        if (vpnState.value != VpnState.DISCONNECTED) {
            _accountMessage.value = "请先断开 VPN，再切换账户"
            return
        }
        viewModelScope.launch {
            _isSwitchingAccount.value = true
            _accountMessage.value = null
            try {
                val result = accountManager.loginAccount(
                    email = email,
                    password = password,
                    timeoutMs = backendSettings.value.nodeRequestTimeoutMs
                )
                result.onFailure { error ->
                    Log.w(tag, "Failed to switch account", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
                if (result.isSuccess) {
                    val refreshed = subscriptionDataLoader.initialize(forceRefresh = true)
                    _accountMessage.value = if (refreshed) {
                        "账户切换成功"
                    } else {
                        "账户切换成功，但订阅线路刷新失败"
                    }
                }
            } finally {
                _isSwitchingAccount.value = false
            }
        }
    }

    fun deleteAccount(password: String) {
        if (_isDeletingAccount.value || _isSwitchingAccount.value) return
        if (vpnState.value != VpnState.DISCONNECTED) {
            _accountMessage.value = "请先断开 VPN，再注销账户"
            return
        }
        viewModelScope.launch {
            _isDeletingAccount.value = true
            _accountMessage.value = null
            try {
                val result = accountManager.deleteAccount(
                    password = password,
                    timeoutMs = backendSettings.value.nodeRequestTimeoutMs
                )
                result.onFailure { error ->
                    Log.w(tag, "Failed to delete account", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
                if (result.isSuccess) {
                    val refreshed = subscriptionDataLoader.initialize(forceRefresh = true)
                    _accountMessage.value = if (refreshed) {
                        "账户已注销，当前设备已重新注册为游客"
                    } else {
                        "账户已注销，但游客账户和订阅线路重新注册失败"
                    }
                }
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }

    private fun accountBindingErrorMessage(error: Throwable): String {
        return when (error.message) {
            "invalid_bind_credentials" -> "邮箱或密码格式无效"
            "account_banned" -> "账户已被封禁，无法绑定"
            "account_already_bound" -> "账户已经绑定邮箱"
            "email_already_bound" -> "该邮箱已经绑定其他账户"
            "account_not_found" -> "账户不存在，请重新同步账户"
            else -> error.message ?: "账户绑定失败"
        }
    }

    private fun accountActionErrorMessage(error: Throwable): String {
        return when (error.message) {
            "invalid_login_credentials" -> "邮箱或密码错误"
            "invalid_account_password" -> "当前账户密码错误"
            "account_banned" -> "账户已被封禁，无法切换"
            "account_not_found" -> "账户不存在，请重新同步账户"
            else -> error.message ?: "账户操作失败"
        }
    }

    private fun rejectUnavailableAccount(requireNodeAccess: Boolean = true): Boolean {
        return when {
            requireNodeAccess && !subscriptionDataLoader.isNodeAccessGranted -> {
                _error.value = "订阅权限尚未通过后台验证，请刷新节点"
                true
            }
            else -> false
        }
    }

    fun acceptUserAgreement() {
        viewModelScope.launch {
            settingsRepository.setUserAgreementAccepted(true)
        }
    }

    private suspend fun currentNodeListSnapshot(): List<Node> = withContext(Dispatchers.Default) {
        val category = settingsRepository.nodeListCategory.first()
        val snapshot = when (category) {
            NodeListCategory.PRIMARY -> currentSubscriptionNodeSnapshot()
            NodeListCategory.FAVORITES -> nodeDao().getFavoriteNodes().first()
        }
        sortNodesForList(snapshot)
    }

    private suspend fun currentSubscriptionNodeSnapshot(): List<Node> = withContext(Dispatchers.Default) {
        val groups = nodeDao().getSubscriptionGroups().first()
        val storedSourceId = settingsRepository.selectedSubscriptionSourceId.first()
        val sourceId = storedSourceId
            ?.takeIf { id -> groups.any { it.sourceId == id } }
            ?: groups.firstOrNull()?.sourceId
        val all = nodeDao().getSubscriptionNodes().first()
        val scoped = if (sourceId.isNullOrBlank()) {
            all
        } else {
            all.filter { it.subscriptionSourceId == sourceId }
        }
        sortNodesForList(scoped)
    }

    private suspend fun currentSmartConnectNodes(): List<Node> = currentNodeListSnapshot()

    private fun takeTopPercent(nodes: List<Node>, percent: Int): List<Node> {
        if (nodes.isEmpty()) return emptyList()
        val normalizedPercent = percent.coerceIn(1, 100)
        val count = ((nodes.size.toLong() * normalizedPercent + 99L) / 100L)
            .toInt()
            .coerceIn(1, nodes.size)
        return nodes.take(count)
    }

    private suspend fun restoreRememberedLastSelectedNodeIfNeeded(freshNodes: List<Node>? = null) {
        if (!settingsRepository.rememberLastSelectedNodeEnabled.first()) return
        val rememberedNodeId = settingsRepository.lastSelectedNodeId.first()?.takeIf { it.isNotBlank() } ?: return
        val candidateNodes = freshNodes ?: nodeDao().getAllNodes().first()
        val rememberedNode = candidateNodes.firstOrNull { it.id == rememberedNodeId }
            ?: nodeDao().getNodeById(rememberedNodeId)
        if (rememberedNode?.source != NodeSource.SUBSCRIPTION && rememberedNode != null) {
            settingsRepository.setSelectedNodeId(rememberedNodeId)
        }
    }

    fun refreshNodes() {
        viewModelScope.launch {
            if (rejectUnavailableAccount(requireNodeAccess = false)) return@launch
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            val fetchOk = subscriptionDataLoader.initialize(forceRefresh = true)
            if (!fetchOk) return@launch
        }
    }

    private suspend fun <T> requestWithAutoRetry(
        requestName: String,
        requestBlock: suspend () -> T
    ): Result<T> {
        val first = runCatching { requestBlock() }
        if (first.isSuccess) return Result.success(first.getOrThrow())

        _error.value = "${requestName}请求失败，已自动重试..."
        val second = runCatching { requestBlock() }
        if (second.isSuccess) return Result.success(second.getOrThrow())

        _error.value = "${requestName}请求失败，自动重试后仍失败，请稍后再试"
        return Result.failure(second.exceptionOrNull() ?: first.exceptionOrNull() ?: IllegalStateException("${requestName}请求失败"))
    }

    private suspend fun testNodeDownloadBandwidthMbps(node: Node, sizeMb: Int): Float =
        bandwidthTester.test(
            node,
            sizeMb,
            hysteriaUpMbps.value,
            hysteriaDownMbps.value
        )

}
