package xyz.a202132.app.viewmodel
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.local.NodeLatencyUpdate
import xyz.a202132.app.data.local.NodeLocationUpdate
import xyz.a202132.app.data.local.NodeDao
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.feature.nodegrouping.domain.CountryGroupingStrategy
import xyz.a202132.app.feature.nodegrouping.domain.SmartConnectScopeResolver
import xyz.a202132.app.feature.nodegrouping.domain.SubscriptionGroupingStrategy
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NodeDownloadBandwidthTester
import xyz.a202132.app.network.SpeedTestService
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.SubscriptionImportUrl
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.network.NodeLocationResolver
import xyz.a202132.app.network.AccountManager
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.util.SingBoxConfigGenerator
import xyz.a202132.app.util.XrayConfigGenerator
import xyz.a202132.app.util.DatabasePassphraseManager
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.ceil
import java.util.UUID

enum class NodeImportResult {
    IMPORTED,
    DUPLICATE,
    INVALID,
    FETCH_FAILED
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
    private val latencyTester = LatencyTester()
    private val bandwidthTester = NodeDownloadBandwidthTester(application)
    // 节流控制
    private val THROTTLE_INTERVAL = 5000L // 5秒节流间隔
    private var lastCheckUpdateTime = 0L
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

    // 全部订阅刷新（启动自动刷新或右上角手动刷新）显示顶部提示；单组下拉刷新使用列表内置动效。
    private val _isRefreshingAllSubscriptions = MutableStateFlow(false)
    val isRefreshingAllSubscriptions = _isRefreshingAllSubscriptions.asStateFlow()
    
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
    private val _isSyncingAccount = MutableStateFlow(false)
    val isSyncingAccount = _isSyncingAccount.asStateFlow()
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
        SubscriptionGroupingStrategy.resolveActiveSourceId(groups, storedId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedSubscriptionSourceId = activeSubscriptionSourceId

    val selectedCountryCode = settingsRepository.selectedCountryCode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val nodeGroupingMode = settingsRepository.nodeGroupingMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, NodeGroupingMode.COUNTRY)

    val favoriteSourceNodeIds = nodeDaoFlow.flatMapLatest { it.getFavoriteSourceNodeIds() }
        .map { it.filterNotNull().toSet() }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptySet()
        )

    val allSubscriptionNodes = nodeDaoFlow.flatMapLatest { dao ->
        dao.getSubscriptionNodes()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 面向小白的地区选项跨全部普通订阅组聚合，不受订阅页筛选状态影响。 */
    val countryOptions = allSubscriptionNodes
        .map(CountryGroupingStrategy::groups)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    val favoriteNodes = nodeDaoFlow
        .flatMapLatest { it.getFavoriteNodes() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    val homeClickAction = settingsRepository.homeClickAction.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HomeConnectionAction.DIRECT
    )

    val homeLongPressAction = settingsRepository.homeLongPressAction.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HomeConnectionAction.SMART
    )

    val ipv6RoutingMode = settingsRepository.ipv6RoutingMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IPv6RoutingMode.DISABLED
    )
    
    val isUserAgreementAccepted = settingsRepository.isUserAgreementAccepted.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null
    )

    // 自动化测试设置
    val tcpingTestTimeoutMs = settingsRepository.tcpingTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.TCPING_TEST_TIMEOUT)
    val urlTestTimeoutMs = settingsRepository.urlTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.URL_TEST_TIMEOUT)
    val urlTestUrl = settingsRepository.urlTestUrl.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.URL_TEST_URL)
    val speedTestDownloadUrl = settingsRepository.speedTestDownloadUrl.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SPEED_TEST_DOWNLOAD_URL)
    val speedTestDurationMs = settingsRepository.speedTestDurationMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.AUTO_TEST_BANDWIDTH_DURATION_MS)
    val tcpingConcurrency = settingsRepository.tcpingConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.TCPING_CONCURRENCY)
    val urlTestConcurrency = settingsRepository.urlTestConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.URL_TEST_CONCURRENCY)
    val bandwidthTestConcurrency = settingsRepository.bandwidthTestConcurrency.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.BANDWIDTH_TEST_CONCURRENCY
    )
    val smartConnectTcpingPercent = settingsRepository.smartConnectTcpingPercent.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SMART_CONNECT_TCPING_PERCENT)
    val smartConnectUrlTestPercent = settingsRepository.smartConnectUrlTestPercent.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SMART_CONNECT_URL_TEST_PERCENT)
    val smartConnectSpeedTestPercent = settingsRepository.smartConnectSpeedTestPercent.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SMART_CONNECT_SPEED_TEST_PERCENT)
    val smartConnectFullTestEnabled = settingsRepository.smartConnectFullTestEnabled.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.SMART_CONNECT_FULL_TEST_ENABLED
    )
    val publicNodePoolOptimizationEnabled = settingsRepository.publicNodePoolOptimizationEnabled.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.PUBLIC_NODE_POOL_OPTIMIZATION_ENABLED
    )
    val publicNodePoolNodeThreshold = settingsRepository.publicNodePoolNodeThreshold.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.PUBLIC_NODE_POOL_NODE_THRESHOLD
    )
    val publicNodePoolTcpingPercent = settingsRepository.publicNodePoolTcpingPercent.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.SMART_CONNECT_TCPING_PERCENT
    )
    val publicNodePoolUrlTestPercent = settingsRepository.publicNodePoolUrlTestPercent.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.SMART_CONNECT_URL_TEST_PERCENT
    )
    val publicNodePoolSpeedTestPercent = settingsRepository.publicNodePoolSpeedTestPercent.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.SMART_CONNECT_SPEED_TEST_PERCENT
    )
    val publicNodePoolTcpingTestTimeoutMs = settingsRepository.publicNodePoolTcpingTestTimeoutMs.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.TCPING_TEST_TIMEOUT
    )
    val publicNodePoolUrlTestTimeoutMs = settingsRepository.publicNodePoolUrlTestTimeoutMs.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.URL_TEST_TIMEOUT
    )
    val publicNodePoolUrlTestUrl = settingsRepository.publicNodePoolUrlTestUrl.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.URL_TEST_URL
    )
    val publicNodePoolSpeedTestDownloadUrl =
        settingsRepository.publicNodePoolSpeedTestDownloadUrl.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppConfig.SPEED_TEST_DOWNLOAD_URL
        )
    val publicNodePoolSpeedTestDurationMs = settingsRepository.publicNodePoolSpeedTestDurationMs.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.AUTO_TEST_BANDWIDTH_DURATION_MS
    )
    val publicNodePoolTcpingConcurrency = settingsRepository.publicNodePoolTcpingConcurrency.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.PUBLIC_NODE_POOL_TCPING_CONCURRENCY
    )
    val publicNodePoolUrlTestConcurrency = settingsRepository.publicNodePoolUrlTestConcurrency.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.PUBLIC_NODE_POOL_URL_TEST_CONCURRENCY
    )
    val publicNodePoolBandwidthTestConcurrency =
        settingsRepository.publicNodePoolBandwidthTestConcurrency.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppConfig.PUBLIC_NODE_POOL_BANDWIDTH_TEST_CONCURRENCY
        )
    val disconnectRecoveryEnabled = settingsRepository.disconnectRecoveryEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.DISCONNECT_RECOVERY_ENABLED)
    val autoDeleteTimeoutNodes = settingsRepository.autoDeleteTimeoutNodes.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.AUTO_DELETE_TIMEOUT_NODES)
    val hideFavoriteNodeGroup = settingsRepository.hideFavoriteNodeGroup.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hysteriaBandwidthEnabled = settingsRepository.hysteriaBandwidthEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_BANDWIDTH_ENABLED)
    val hysteriaUpMbps = settingsRepository.hysteriaUpMbps.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_UP_MBPS)
    val hysteriaDownMbps = settingsRepository.hysteriaDownMbps.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_DOWN_MBPS)
    val protocolCoreSettings = settingsRepository.protocolCoreSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ProtocolCoreSettings()
    )
    val nodeDisplaySettings = settingsRepository.nodeDisplaySettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        NodeDisplaySettings()
    )
    val skinSettings = settingsRepository.skinSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SkinSettings()
    )
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
            try {
                // Keep the cached selection available immediately; refreshing
                // data should not blank and rewrite DataStore on every launch.
                _isInitialized.value = true

                // Preload cached and remote data during startup. User-agreement
                // presentation must not serialize the resource-loading pipeline.

                subscriptionDataLoader.loadCachedSubscriptionList()
                if (!initializeSubscriptionsWithRefreshState(forceRefresh = true)) {
                    // A successful refresh schedules enrichment for the fresh
                    // nodes. Only fall back to cached nodes when refresh failed.
                    scheduleNodeLocationEnrichment(nodeDao().getAllNodes().first())
                }
                checkNotice()
                checkUpdateInternal(isAuto = true)
            } catch (error: Exception) {
                Log.e(tag, "Startup preload failed", error)
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

    /** 智能连接：按设置取得候选范围，再依次执行 TCPing、URL Test 和带宽测速。 */
    fun startSmartConnection() {
        viewModelScope.launch {
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
                val fullTestEnabled = settingsRepository.smartConnectFullTestEnabled.first()
                val groupingMode = settingsRepository.nodeGroupingMode.first()
                val countryCode = settingsRepository.selectedCountryCode.first()
                val allNodes = currentSmartConnectNodes(
                    fullTestEnabled = fullTestEnabled,
                    groupingMode = groupingMode,
                    selectedCountryCode = countryCode
                )
                if (allNodes.isEmpty()) {

                    _error.value = "没有可用节点"
                    return@launch
                }
                if (fullTestEnabled) {
                    Log.i(tag, "Smart connection full test enabled: nodes=${allNodes.size}")
                }
                if (groupingMode == NodeGroupingMode.COUNTRY && countryCode != null) {
                    Log.i(
                        tag,
                        "Smart connection country scope enabled: country=$countryCode, " +
                            "nodes=${allNodes.size}"
                    )
                }

                val smartTestConcurrency = PublicNodePoolOptimization.resolveConcurrency(
                    nodes = allNodes,
                    enabled = publicNodePoolOptimizationEnabled.value,
                    nodeThreshold = publicNodePoolNodeThreshold.value,
                    publicPoolTcpingConcurrency = publicNodePoolTcpingConcurrency.value,
                    publicPoolUrlTestConcurrency = publicNodePoolUrlTestConcurrency.value,
                    defaultTcpingConcurrency = tcpingConcurrency.value,
                    defaultUrlTestConcurrency = urlTestConcurrency.value
                )
                if (smartTestConcurrency.isPublicNodePool) {
                    Log.i(
                        tag,
                        "Public node pool optimization enabled: nodes=${allNodes.size}, " +
                            "tcpingConcurrency=${smartTestConcurrency.tcping}, " +
                            "urlTestConcurrency=${smartTestConcurrency.urlTest}"
                        )
                }

                val tcpingPercent = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolTcpingPercent.value
                } else {
                    smartConnectTcpingPercent.value
                }
                val urlTestPercent = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolUrlTestPercent.value
                } else {
                    smartConnectUrlTestPercent.value
                }
                val speedTestPercent = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolSpeedTestPercent.value
                } else {
                    smartConnectSpeedTestPercent.value
                }
                val selectedTcpingTimeoutMs = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolTcpingTestTimeoutMs.value
                } else {
                    tcpingTestTimeoutMs.value
                }
                val selectedUrlTestTimeoutMs = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolUrlTestTimeoutMs.value
                } else {
                    urlTestTimeoutMs.value
                }
                val selectedUrlTestUrl = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolUrlTestUrl.value
                } else {
                    urlTestUrl.value
                }
                val selectedSpeedTestDownloadUrl = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolSpeedTestDownloadUrl.value
                } else {
                    speedTestDownloadUrl.value
                }
                val selectedSpeedTestDurationMs = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolSpeedTestDurationMs.value
                } else {
                    speedTestDurationMs.value
                }
                val selectedSpeedTestConcurrency = if (smartTestConcurrency.isPublicNodePool) {
                    publicNodePoolBandwidthTestConcurrency.value
                } else {
                    bandwidthTestConcurrency.value
                }

                val tcpingNodes = takeTopPercent(allNodes, tcpingPercent)
                _testingLabel.value = "智能连接：TCPing 测试中..."
                val tcpingResults = internalTestNodes(
                    currentNodes = tcpingNodes,
                    concurrency = smartTestConcurrency.tcping,
                    timeoutMs = selectedTcpingTimeoutMs
                ) { completed, total ->
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

                val urlTestNodes = takeTopPercent(tcpingCandidates, urlTestPercent)
                val urlTestResults = runUrlTestNodes(
                    nodes = urlTestNodes,
                    labelPrefix = "智能连接：URL Test 204",
                    concurrency = smartTestConcurrency.urlTest,
                    timeoutMs = selectedUrlTestTimeoutMs,
                    targetUrl = selectedUrlTestUrl
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

                val speedTestNodes = takeTopPercent(urlTestCandidates, speedTestPercent)
                val speedResults = speedTestNodes
                    .chunked(selectedSpeedTestConcurrency)
                    .flatMapIndexed { batchIndex, batch ->
                        coroutineScope {
                            batch.map { node ->
                                async {
                                    val speedMbps = testNodeDownloadBandwidthMbps(
                                        node = node,
                                        durationMs = selectedSpeedTestDurationMs,
                                        downloadUrl = selectedSpeedTestDownloadUrl
                                    )
                                    nodeDao().updateDownloadMbps(node.id, speedMbps)
                                    node to speedMbps
                                }
                            }.awaitAll()
                        }.also {
                            val completed = ((batchIndex + 1) * selectedSpeedTestConcurrency)
                                .coerceAtMost(speedTestNodes.size)
                            _testingLabel.value =
                                "智能连接：带宽测速 ($completed/${speedTestNodes.size})"
                        }
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
                if (fullTestEnabled || countryCode != null) {
                    bestNode.subscriptionSourceId?.takeIf(String::isNotBlank)?.let { sourceId ->
                        settingsRepository.setSelectedSubscriptionSourceId(sourceId)
                        settingsRepository.setNodeListCategory(NodeListCategory.PRIMARY)
                    }
                }
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
            val selectedId = settingsRepository.selectedNodeId.first()
            val selectedNode = selectedId?.let { nodeDao().getNodeById(it) }
            val subscriptionNodes = currentSubscriptionNodeSnapshot()
            val node = selectedNode ?: subscriptionNodes.firstOrNull()
            if (node == null) {
                _error.value = "没有可用节点"
                return@launch
            }

            settingsRepository.setSelectedNodeId(node.id)
            Log.i(
                tag,
                "Connecting to selected/default node: ${node.getDisplayName()}"
            )
            if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                Log.w(tag, "Default VPN start requested while network is unavailable")
                _error.value = "当前无网络连接节点！"
            }
            ServiceManager.startVpn(getApplication(), node, proxyMode.value)
        }
    }

    /**
     * 使用临时无头原生核心测试全部节点；sing-box 与 Xray 各自共享一个批量实例，
     * 每个节点通过独立 SOCKS 入站执行相同的 HTTP HEAD 探测。
     */
    fun urlTestAllNodes(targetNodes: List<Node>? = null) {
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
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "URL Test batch failed", error)
                _error.value = "延迟测速未完成，请重试（${error.javaClass.simpleName}）"
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                if (locked) {
                    GlobalTestExecution.finish()
                }
            }
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

    fun setUrlTestUrl(value: String) {
        viewModelScope.launch {
            settingsRepository.setUrlTestUrl(value)
        }
    }

    fun setSpeedTestDownloadUrl(value: String) {
        viewModelScope.launch {
            settingsRepository.setSpeedTestDownloadUrl(value)
        }
    }

    fun setSpeedTestDurationMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setSpeedTestDurationMs(value)
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

    fun setSmartConnectFullTestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmartConnectFullTestEnabled(enabled)
        }
    }

    fun setPublicNodePoolOptimizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolOptimizationEnabled(enabled)
        }
    }

    fun setPublicNodePoolNodeThreshold(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolNodeThreshold(value)
        }
    }

    fun setPublicNodePoolTcpingPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolTcpingPercent(value)
        }
    }

    fun setPublicNodePoolUrlTestPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolUrlTestPercent(value)
        }
    }

    fun setPublicNodePoolSpeedTestPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolSpeedTestPercent(value)
        }
    }

    fun setPublicNodePoolTcpingTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolTcpingTestTimeoutMs(value)
        }
    }

    fun setPublicNodePoolUrlTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolUrlTestTimeoutMs(value)
        }
    }

    fun setPublicNodePoolUrlTestUrl(value: String) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolUrlTestUrl(value)
        }
    }

    fun setPublicNodePoolSpeedTestDownloadUrl(value: String) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolSpeedTestDownloadUrl(value)
        }
    }

    fun setPublicNodePoolSpeedTestDurationMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolSpeedTestDurationMs(value)
        }
    }

    fun setPublicNodePoolTcpingConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolTcpingConcurrency(value)
        }
    }

    fun setPublicNodePoolUrlTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolUrlTestConcurrency(value)
        }
    }

    fun setPublicNodePoolBandwidthTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setPublicNodePoolBandwidthTestConcurrency(value)
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

    fun setHideFavoriteNodeGroup(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideFavoriteNodeGroup(enabled)
            if (enabled && nodeListCategory.value == NodeListCategory.FAVORITES) {
                settingsRepository.setNodeListCategory(NodeListCategory.PRIMARY)
            }
        }
    }

    /**
     * 使用直连网络分别测试下行和上行带宽，并按 10 Mbps 档位向上取整后写入 Hy2 设置。
     */
    fun adaptHysteriaBandwidth() {
        if (_isHysteriaBandwidthAdapting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isHysteriaBandwidthAdapting.value = true
            _hysteriaBandwidthAdaptiveStatus.value = "正在测试直连网络上下行..."

            try {
                val service = SpeedTestService(
                    durationMs = speedTestDurationMs.value,
                    downloadUrl = speedTestDownloadUrl.value
                )
                val downloadResult = service.startDownloadTest { _, _ -> }
                val uploadResult = service.startUploadTest { _, _ -> }
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

    fun setProtocolCore(nodeType: NodeType, core: ProxyCore) {
        if (protocolCoreSettings.value.coreFor(nodeType) == core) return
        viewModelScope.launch {
            settingsRepository.setProtocolCore(nodeType, core)
            restartVpnIfNeeded()
        }
    }

    fun setNodeLabelShowProtocol(show: Boolean) {
        viewModelScope.launch { settingsRepository.setNodeLabelShowProtocol(show) }
    }

    fun setNodeLabelShowSecurity(show: Boolean) {
        viewModelScope.launch { settingsRepository.setNodeLabelShowSecurity(show) }
    }

    fun setNodeLabelShowTransport(show: Boolean) {
        viewModelScope.launch { settingsRepository.setNodeLabelShowTransport(show) }
    }

    fun setNodeLabelShowCore(show: Boolean) {
        viewModelScope.launch { settingsRepository.setNodeLabelShowCore(show) }
    }

    fun upsertSkinScheme(scheme: SkinScheme, activate: Boolean = false) {
        viewModelScope.launch {
            settingsRepository.upsertSkinScheme(scheme, activate)
        }
    }

    fun setActiveSkinScheme(schemeId: String) {
        viewModelScope.launch {
            settingsRepository.setActiveSkinScheme(schemeId)
        }
    }

    fun deleteSkinScheme(schemeId: String) {
        viewModelScope.launch {
            settingsRepository.deleteSkinScheme(schemeId)
        }
    }


    private suspend fun runUrlTestNodes(
        nodes: List<Node>,
        labelPrefix: String,
        concurrency: Int = urlTestConcurrency.value,
        timeoutMs: Long = urlTestTimeoutMs.value,
        targetUrl: String = urlTestUrl.value,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        val batch = xyz.a202132.app.network.UrlTestManager.start(
            getApplication(), nodes, hysteriaUpMbps.value, hysteriaDownMbps.value,
            protocolCoreSettings.value
        ) ?: return emptyList()
        try {
            val testedResults = latencyTester.urlTestAllNodes(
                nodes = batch.nodes,
                targetUrl = targetUrl,
                timeoutMs = timeoutMs,
                concurrency = concurrency
            ) { completed, total ->
                _testingLabel.value = "$labelPrefix ($completed/$total)"
                onProgress?.invoke(completed, total)
            }
            val testedById = testedResults.associateBy(LatencyTester.LatencyResult::nodeId)
            val results = nodes.map { node ->
                testedById[node.id] ?: LatencyTester.LatencyResult(
                    nodeId = node.id,
                    latency = -2,
                    isAvailable = false
                )
            }
            Log.d(tag, "Got ${results.size} URL test results")
            return results
        } finally {
            withContext(NonCancellable) { xyz.a202132.app.network.UrlTestManager.stop() }
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
        concurrency: Int = tcpingConcurrency.value,
        timeoutMs: Long = tcpingTestTimeoutMs.value,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        if (currentNodes.isEmpty()) return emptyList()
        
        Log.d(tag, "Testing ${currentNodes.size} nodes")
        
        // 统一使用 Socket 测试 (直接连接节点服务器测试可达性)
        // 即使 VPN 运行中也可以工作，因为测试的是节点服务器本身
        val results = latencyTester.testAllNodes(
            nodes = currentNodes,
            timeoutMs = timeoutMs,
            concurrency = concurrency,
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

    fun setNodeListCategory(category: NodeListCategory) {
        viewModelScope.launch {
            settingsRepository.setSelectedCountryCode(null)
            settingsRepository.setNodeGroupingMode(NodeGroupingMode.SUBSCRIPTION)
            settingsRepository.setNodeListCategory(category)
        }
    }

    /** Select a node from the node list and connect to it immediately. */
    fun selectAndConnectNode(node: Node) {
        viewModelScope.launch {
            settingsRepository.setSelectedCountryCode(null)
            settingsRepository.setNodeGroupingMode(NodeGroupingMode.SUBSCRIPTION)
            connectNode(node)
        }
    }

    /** 选择主页智能测速使用的地区，不立即选取节点或发起 VPN 连接。 */
    fun selectCountry(countryCode: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedCountryCode(countryCode)
            settingsRepository.setNodeGroupingMode(NodeGroupingMode.COUNTRY)
            settingsRepository.setNodeListCategory(NodeListCategory.PRIMARY)
        }
    }

    private suspend fun connectNode(node: Node) {
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
            VpnState.CONNECTED -> ServiceManager.startVpn(
                getApplication(),
                node,
                proxyMode.value
            )
            else -> Log.i(tag, "Direct node connection ignored while VPN is transitioning")
        }
    }

    fun setSubscriptionSource(sourceId: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedCountryCode(null)
            settingsRepository.setNodeGroupingMode(NodeGroupingMode.SUBSCRIPTION)
            settingsRepository.setSelectedSubscriptionSourceId(sourceId)
            settingsRepository.setSelectedNodeId(null)
            settingsRepository.setNodeListCategory(NodeListCategory.PRIMARY)
        }
    }

    /** 切换到订阅分组时立即取消地区限定，保证主页智能测速使用当前订阅组。 */
    fun selectSubscriptionGrouping() {
        viewModelScope.launch {
            settingsRepository.setSelectedCountryCode(null)
            settingsRepository.setNodeGroupingMode(NodeGroupingMode.SUBSCRIPTION)
            settingsRepository.setNodeListCategory(NodeListCategory.PRIMARY)
        }
    }

    fun selectCountryGrouping() {
        viewModelScope.launch {
            settingsRepository.setNodeGroupingMode(NodeGroupingMode.COUNTRY)
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
            val subscriptionUrl = SubscriptionImportUrl.detect(text)
            val importResult = if (subscriptionUrl == null) {
                withContext(Dispatchers.IO) {
                    persistNodesToFavorites(subscriptionParser.parseSubscription(text))
                }
            } else {
                val fetchedNodes = withContext(Dispatchers.IO) {
                    subscriptionParser.fetchAndParse(subscriptionUrl)
                }
                fetchedNodes.fold(
                    onSuccess = { nodes ->
                        withContext(Dispatchers.IO) { persistNodesToFavorites(nodes) }
                    },
                    onFailure = { error ->
                        Log.e(tag, "Unable to import favorite subscription", error)
                        NodeImportResult.FETCH_FAILED to 0
                    }
                )
            }
            finishFavoriteImport(importResult, onResult)
        }
    }

    private suspend fun persistNodesToFavorites(
        parsedNodes: List<Node>
    ): Pair<NodeImportResult, Int> {
        if (parsedNodes.isEmpty()) return NodeImportResult.INVALID to 0

        val coreCompatibleNodes = parsedNodes.filter { node ->
            isNodeCoreConfigCompatible(node)
        }
        if (coreCompatibleNodes.isEmpty()) return NodeImportResult.INVALID to 0

        val existingFavoriteRawLinks = nodeDao().getFavoriteNodes().first()
            .map { it.getRawLinkPlain() }
            .toMutableSet()
        val uniqueNodes = coreCompatibleNodes.filter { node ->
            existingFavoriteRawLinks.add(node.getRawLinkPlain())
        }
        if (uniqueNodes.isEmpty()) return NodeImportResult.DUPLICATE to 0

        val now = System.currentTimeMillis()
        val favorites = uniqueNodes.mapIndexed { index, node ->
            node.copy(
                id = "fav_${UUID.randomUUID()}",
                source = NodeSource.FAVORITE,
                favoriteSourceNodeId = null,
                favoriteCreatedAt = now + index,
                subscriptionSourceId = null,
                subscriptionSourceName = null,
                latency = -1,
                tcpingLatency = -1,
                urlTestLatency = -1,
                isAvailable = true,
                lastTestedAt = 0L,
                downloadMbps = 0f,
                uploadMbps = 0f,
            )
        }
        nodeDao().insertNodes(favorites)
        return NodeImportResult.IMPORTED to favorites.size
    }

    private suspend fun finishFavoriteImport(
        importResult: Pair<NodeImportResult, Int>,
        onResult: (NodeImportResult, Int) -> Unit
    ) {
        val (result, importedCount) = importResult
        if (result == NodeImportResult.IMPORTED) {
            settingsRepository.setNodeListCategory(NodeListCategory.FAVORITES)
            Log.i(tag, "Imported $importedCount nodes to favorites")
        } else {
            Log.w(tag, "Node import finished without new nodes: result=$result")
        }
        onResult(result, importedCount)
    }

    private suspend fun isNodeCoreConfigCompatible(node: Node): Boolean {
        return try {
            val backend = xyz.a202132.app.core.CoreBackends.registry.resolve(protocolCoreSettings.value.coreFor(node.type), node)
            backend.prepare(getApplication<Application>(), xyz.a202132.app.core.CoreRequest(node, 10808))
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(tag, "Imported node is incompatible: " + node.type + "/" + error.javaClass.simpleName)
            false
        }
    }

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

    /** 设置主页图标单击动作。 */
    fun setHomeClickAction(action: HomeConnectionAction) {
        viewModelScope.launch {
            settingsRepository.setHomeClickAction(action)
        }
    }

    /** 设置主页图标长按动作。 */
    fun setHomeLongPressAction(action: HomeConnectionAction) {
        viewModelScope.launch {
            settingsRepository.setHomeLongPressAction(action)
        }
    }
    
    /**
     * 切换VPN连接
     */
    fun toggleVpn() {
        when (vpnState.value) {
            VpnState.DISCONNECTED -> {
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

    private suspend fun checkNotice() {
        val result = subscriptionDataLoader.noticeConfig?.let { Result.success(it) }
            ?: Result.failure<NoticeInfo>(IllegalStateException("backend config unavailable"))
        result.onSuccess { noticeInfo ->
            if (noticeInfo.hasNotice && isNoticeWithinTimeRange(noticeInfo)) {
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
            } else {
                _notice.value = null
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
    fun checkUpdate(isAuto: Boolean = false) {
        viewModelScope.launch {
            checkUpdateInternal(isAuto)
        }
    }

    private suspend fun checkUpdateInternal(
        isAuto: Boolean
    ) {
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

    fun syncCurrentAccount() {
        if (
            _isSyncingAccount.value ||
            _isUpdatingNickname.value ||
            _isBindingAccount.value ||
            _isSwitchingAccount.value ||
            _isDeletingAccount.value
        ) return
        viewModelScope.launch {
            _isSyncingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.syncCurrentAccount(
                    timeoutMs = backendSettings.value.nodeRequestTimeoutMs
                ).onSuccess {
                    _accountMessage.value = "账户信息已同步"
                }.onFailure { error ->
                    Log.w(tag, "Failed to sync account information", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
            } finally {
                _isSyncingAccount.value = false
            }
        }
    }

    private fun isNoticeWithinTimeRange(noticeInfo: NoticeInfo): Boolean {
        val now = Instant.now()
        val startAt = noticeInfo.startAt.toInstantOrNull()
        val endAt = noticeInfo.endAt.toInstantOrNull()
        return (startAt == null || !now.isBefore(startAt)) &&
            (endAt == null || !now.isAfter(endAt))
    }

    private fun String.toInstantOrNull(): Instant? {
        if (isBlank()) return null
        return runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
    }

    fun bindEmailPassword(
        email: String,
        password: String,
        registrationCode: String = "",
    ) {
        if (_isBindingAccount.value) return
        viewModelScope.launch {
            _isBindingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.bindEmailPassword(
                    email = email,
                    password = password,
                    registrationCode = registrationCode,
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

                    val refreshed = initializeSubscriptionsWithRefreshState(forceRefresh = true)
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

    fun deleteAccount(password: String, onDeleted: () -> Unit = {}) {
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
                    runCatching { clearLocalApplicationData() }
                        .onFailure { error ->
                            Log.e(tag, "Failed to clear local application data after account deletion", error)
                        }
                    onDeleted()
                }
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }

    /** 注销成功后清除本地数据，避免退出前再次自动注册游客账户。 */
    private suspend fun clearLocalApplicationData() {
        val appContext = getApplication<Application>()
        withContext(Dispatchers.IO) {
            settingsRepository.clearAll()
            appContext.deleteSharedPreferences("rule_manager")
            AppConfig.resetBackendUrl(appContext)
            File(appContext.filesDir, "sing-box").deleteRecursively()
            AppDatabase.clearAndClose(appContext)
            DatabasePassphraseManager.clear(appContext)
        }
    }

    private fun accountBindingErrorMessage(error: Throwable): String {
        return when (error.message) {
            "registration_disabled" -> "用户注册暂未开放"
            "registration_code_required" -> "必须使用管理员发放的一次性注册码注册"
            "invalid_registration_code" -> "注册码无效或已使用"
            "formal_registration_closed" -> "正式用户注册暂未开放"
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

    private suspend fun currentSmartConnectNodes(
        fullTestEnabled: Boolean,
        groupingMode: NodeGroupingMode,
        selectedCountryCode: String?
    ): List<Node> {
        val hasCountryScope =
            groupingMode == NodeGroupingMode.COUNTRY && selectedCountryCode != null
        if (!fullTestEnabled && !hasCountryScope) return currentNodeListSnapshot()
        return withContext(Dispatchers.Default) {
            val allSubscriptionNodes = nodeDao().getSubscriptionNodes().first()
            val candidates = SmartConnectScopeResolver.selectCandidates(
                currentNodes = emptyList(),
                allSubscriptionNodes = allSubscriptionNodes,
                groupingMode = groupingMode,
                fullTestEnabled = fullTestEnabled,
                selectedCountryCode = selectedCountryCode
            )
            sortNodesForList(candidates)
        }
    }

    private fun takeTopPercent(nodes: List<Node>, percent: Int): List<Node> {
        if (nodes.isEmpty()) return emptyList()
        val normalizedPercent = percent.coerceIn(1, 100)
        val count = ((nodes.size.toLong() * normalizedPercent + 99L) / 100L)
            .toInt()
            .coerceIn(1, nodes.size)
        return nodes.take(count)
    }

    /** Refresh all subscriptions when no source is specified, otherwise only the selected source. */
    fun refreshNodes(sourceId: String? = null) {
        viewModelScope.launch {
            if (subscriptionDataLoader.isLoading.value) return@launch
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            val fetchOk = initializeSubscriptionsWithRefreshState(
                forceRefresh = true,
                sourceId = sourceId
            )
            if (!fetchOk) return@launch
        }
    }

    /** 统一维护全量刷新提示，确保启动自动刷新与手动全部刷新表现一致。 */
    private suspend fun initializeSubscriptionsWithRefreshState(
        forceRefresh: Boolean,
        sourceId: String? = null
    ): Boolean {
        val isRefreshAll = sourceId.isNullOrBlank()
        if (isRefreshAll) _isRefreshingAllSubscriptions.value = true
        return try {
            subscriptionDataLoader.initialize(
                forceRefresh = forceRefresh,
                sourceId = sourceId
            )
        } finally {
            if (isRefreshAll) _isRefreshingAllSubscriptions.value = false
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

    private suspend fun testNodeDownloadBandwidthMbps(
        node: Node,
        durationMs: Long = speedTestDurationMs.value,
        downloadUrl: String = speedTestDownloadUrl.value
    ): Float =
        bandwidthTester.test(
            node,
            downloadUrl,
            durationMs,
            hysteriaUpMbps.value,
            hysteriaDownMbps.value,
            protocolCoreSettings.value.coreFor(node.type)
        )

}
