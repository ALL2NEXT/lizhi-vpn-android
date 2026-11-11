package io.github.HanJason233.chimera.viewmodel

import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionImportService
import io.github.HanJason233.chimera.network.nodetest.common.GlobalTestExecution

import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionGroupCoordinator
import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionGroupCreateResult
import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionGroupEditResult
import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionGroupRefreshResult
import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionGroupNodeImportResult
import io.github.HanJason233.chimera.feature.nodegrouping.data.LocalGroupNodeEditResult
import io.github.HanJason233.chimera.feature.nodegrouping.data.LocalGroupNodeDeleteResult
import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionNodeDeleteResult
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.data.local.AppDatabase
import io.github.HanJason233.chimera.data.local.HomeNodeTestResultsEntity
import io.github.HanJason233.chimera.data.local.NodeLatencyUpdate
import io.github.HanJason233.chimera.data.local.NodeLocationUpdate
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.feature.account.AccountSessionCoordinator
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.feature.nodegrouping.domain.CountryGroupingStrategy
import io.github.HanJason233.chimera.feature.nodegrouping.domain.CountryGroupingSourceResolver
import io.github.HanJason233.chimera.feature.nodegrouping.domain.NodeTestResultOrdering
import io.github.HanJason233.chimera.feature.nodegrouping.domain.NodeTestResultSortMode
import io.github.HanJason233.chimera.feature.nodegrouping.domain.SpeedTestCandidateSelector
import io.github.HanJason233.chimera.feature.nodegrouping.domain.SubscriptionGroupingStrategy
import io.github.HanJason233.chimera.feature.nodegrouping.domain.WarehouseGroupingCountryResolver
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import io.github.HanJason233.chimera.network.nodetest.latency.LatencyTester
import io.github.HanJason233.chimera.network.nodetest.bandwidth.NodeDownloadBandwidthTester
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe
import io.github.HanJason233.chimera.network.nodetest.common.NodeTestProxyManager
import io.github.HanJason233.chimera.network.nodetest.bandwidth.SpeedTestService
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCatalog
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestClassifier
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestDefinition
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestResult
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestRunner
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestState
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkIpAddress
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestResult
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestRunner
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestState
import io.github.HanJason233.chimera.network.location.NodeLocationResolver
import io.github.HanJason233.chimera.network.account.AccountManager
import io.github.HanJason233.chimera.network.ip.PublicIpAddressDetector
import io.github.HanJason233.chimera.service.ServiceManager
import io.github.HanJason233.chimera.service.BoxVpnService
import io.github.HanJason233.chimera.util.NetworkUtils
import io.github.HanJason233.chimera.util.DatabasePassphraseManager
import java.io.File
import kotlin.math.ceil
import java.net.InetAddress

internal data class UnlockTestUiState(
    val items: List<UnlockTestResult> = emptyList(),
    val isRunning: Boolean = false,
    val message: String = ""
) {
    val completedCount: Int
        get() = items.count { it.state !in setOf(UnlockTestState.IDLE, UnlockTestState.TESTING) }
}

internal data class SpeedTestProgress(
    val title: String,
    val stage: String,
    val completed: Int = 0,
    val total: Int = 0,
    val isManualGroupTest: Boolean = false
)

private data class HomeCandidateFilterResult(
    val node: Node,
    val linkTypePassed: Boolean,
    val unlockTestsPassed: Boolean
)

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
    private val subscriptionImportService = SubscriptionImportService()
    private val accountManager = AccountManager(application)
    private val latencyTester = LatencyTester()
    private val bandwidthTester = NodeDownloadBandwidthTester(application)

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

    // 官方源点击后立即显示动画；私人源在刷新任务开始时登记到同一状态流。
    private val _pendingOfficialSourceRefreshCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _refreshingPersonalSourceCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val refreshingWarehouseGroupIds = combine(
        subscriptionDataLoader.fetchingSubscriptionSourceIds,
        _pendingOfficialSourceRefreshCounts,
        _refreshingPersonalSourceCounts
    ) { fetchingSourceIds, pendingRefreshCounts, personalRefreshCounts ->
        fetchingSourceIds + pendingRefreshCounts.keys +
            personalRefreshCounts.keys.map { NodeGroupKey.Imported(it).value }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()
    /** 保存当前持有全局测速锁的任务，供首页测试卡片中断。 */
    private var activeTestJob: Job? = null
    private var hysteriaBandwidthAdaptiveJob: Job? = null
    @Volatile
    private var activeAdaptiveSpeedTestService: SpeedTestService? = null

    private val _speedTestProgress = MutableStateFlow<SpeedTestProgress?>(null)
    internal val speedTestProgress = _speedTestProgress.asStateFlow()

    private val _unlockTestState = MutableStateFlow(UnlockTestUiState())
    internal val unlockTestState = _unlockTestState.asStateFlow()

    private val _linkTypeTestResult = MutableStateFlow(LinkTypeTestResult())
    internal val linkTypeTestResult = _linkTypeTestResult.asStateFlow()

    /** 线路信息按当前最近完成的测速阶段展示结果。 */
    private val _nodeTestResultSortMode = MutableStateFlow(
        NodeTestResultSortMode.TCPING_LATENCY
    )
    val nodeTestResultSortMode = _nodeTestResultSortMode.asStateFlow()

    private val _isHysteriaBandwidthAdapting = MutableStateFlow(false)
    val isHysteriaBandwidthAdapting = _isHysteriaBandwidthAdapting.asStateFlow()

    private val _hysteriaBandwidthAdaptiveStatus = MutableStateFlow<String?>(null)
    val hysteriaBandwidthAdaptiveStatus = _hysteriaBandwidthAdaptiveStatus.asStateFlow()
    
    val backendSettings = subscriptionDataLoader.backendSettings

    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    private val _infoDialogMessage = MutableStateFlow<String?>(null)
    val infoDialogMessage = _infoDialogMessage.asStateFlow()
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

    private val subscriptionGroupCoordinator = SubscriptionGroupCoordinator(
        context = application.applicationContext,
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        nodeDao = { nodeDao() },
        subscriptionImportService = subscriptionImportService,
        nodeCompatibilityCheck = { node -> this.isNodeCoreConfigCompatible(node) },
        isTesting = isTesting,
        activeConnectionNode = activeConnectionNode,
        activeSubscriptionSourceId = activeSubscriptionSourceId,
        refreshingPersonalSourceCounts = _refreshingPersonalSourceCounts
    )

    val selectedCountryCode = settingsRepository.selectedCountryCode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val nodeGroupingMode = settingsRepository.nodeGroupingMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, NodeGroupingMode.REGION)

    /** 所有节点组共用的当前选中标识，仅由统一节点组字段决定。 */
    val selectedNodeGroupId: StateFlow<String?> = combine(
        settingsRepository.selectedNodeGroupId,
        activeSubscriptionSourceId
    ) { groupId, sourceId ->
        NodeGroupKey.parse(groupId)?.value
            ?: sourceId?.let { NodeGroupKey.Subscription(it).value }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** DataStore 持久化完成前，暂存刚选中的节点组供连接和测速流程使用。 */
    private val pendingSelectedNodeGroup = MutableStateFlow<NodeGroupKey?>(null)

    val allSubscriptionNodes = nodeDaoFlow.flatMapLatest { dao ->
        dao.getSubscriptionNodes()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 数据库中的全部节点；地区组会再按已选仓库源筛选。 */
    val allWarehouseNodes = nodeDaoFlow.flatMapLatest { dao ->
        dao.getAllNodes()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 私人源摘要。 */
    val userSubscriptionGroups = nodeDaoFlow
        .flatMapLatest { it.getUserSubscriptionGroupsWithCount() }
        .combine(settingsRepository.autoRefreshOnStartupOverrides) { groups, overrides ->
            groups.map { item ->
                NodeGroupSummary(
                    id = NodeGroupKey.Imported(item.group.id).value,
                    name = item.group.name,
                    type = NodeGroupType.WAREHOUSE,
                    warehouseType = WarehouseGroupType.PERSONAL,
                    nodeCount = item.nodeCount,
                    subscriptionUrl = item.group.subscriptionUrl,
                    subscriptionUploadBytes = item.group.subscriptionUploadBytes,
                    subscriptionDownloadBytes = item.group.subscriptionDownloadBytes,
                    subscriptionTotalBytes = item.group.subscriptionTotalBytes,
                    subscriptionExpireAt = item.group.subscriptionExpireAt,
                    iconMode = item.group.iconMode,
                    customIconUrl = item.group.customIconUrl,
                    localIconFile = item.group.localIconFile,
                    userAgentMode = item.group.userAgentMode,
                    customUserAgent = item.group.customUserAgent,
                    subscriptionUpdatedAt = item.group.subscriptionUpdatedAt
                        .takeIf { it > 0L }
                        ?: item.group.createdAt.takeIf { it > 0L },
                    autoRefreshOnStartup = overrides[item.group.id] ?: false
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val countryGroupingSourceIds = settingsRepository.countryGroupingSourceIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 仓库源页面的地区筛选；首次使用时包含全部地区。 */
    val warehouseGroupingCountryCodes = settingsRepository.warehouseGroupingCountryCodes
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val countryGroupingSources = countryGroupingSourceIds
        .map(CountryGroupingSourceResolver::resolve)

    /** 地区组只汇总所选仓库源中的节点；首次使用时包含全部官方源和私人源。 */
    val countryGroupingNodes: StateFlow<List<Node>> = nodeDaoFlow.flatMapLatest { dao ->
        combine(allWarehouseNodes, countryGroupingSources) { allNodes, sources ->
            allNodes to sources
        }.flatMapLatest { (allNodes, sources) ->
            if (sources.personalGroupIds.isEmpty()) {
                flowOf(
                    CountryGroupingSourceResolver.nodes(
                        allNodes = allNodes,
                        personalGroupNodes = emptyList(),
                        sources = sources
                    )
                )
            } else {
                dao.getNodesByGroups(sources.personalGroupIds.toList()).map { personalNodes ->
                    CountryGroupingSourceResolver.nodes(
                        allNodes = allNodes,
                        personalGroupNodes = personalNodes,
                        sources = sources
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val countryOptions = countryGroupingNodes
        .map(CountryGroupingStrategy::groups)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val subscriptionNodes: Flow<List<Node>> = nodeDaoFlow
        .flatMapLatest { dao ->
            activeSubscriptionSourceId.flatMapLatest { sourceId ->
                if (sourceId.isNullOrBlank()) {
                    dao.getSubscriptionNodes()
                } else {
                    dao.getSubscriptionNodesBySource(sourceId)
                }
            }
        }
        .combine(warehouseGroupingCountryCodes) { nodes, countryCodes ->
            WarehouseGroupingCountryResolver.filter(nodes, countryCodes)
        }

    /** 仓库源详情页使用的私人源节点流。 */
    fun observeNodeGroupNodes(groupId: String): Flow<List<Node>> =
        nodeDaoFlow
            .flatMapLatest { it.getNodesByGroup(groupId) }
            .combine(warehouseGroupingCountryCodes) { nodes, countryCodes ->
                WarehouseGroupingCountryResolver.filter(nodes, countryCodes)
            }

    /** 按故障转移指定组加载组内节点，避免在线路选择入口展示全部节点。 */
    fun observeFailoverTargetGroupNodes(groupId: String): Flow<List<Node>> =
        when (val group = NodeGroupKey.parse(groupId)) {
            is NodeGroupKey.Subscription -> nodeDaoFlow.flatMapLatest {
                it.getSubscriptionNodesBySource(group.sourceId)
            }
            is NodeGroupKey.Imported -> nodeDaoFlow.flatMapLatest {
                it.getNodesByGroup(group.groupId)
            }
            is NodeGroupKey.Country -> countryGroupingNodes.map { nodes ->
                nodes.filter { it.getCountryCode() == group.countryCode }
            }
            null -> flowOf(emptyList())
        }

    /** 当前选中私人源的节点；没有选中私人源时不参与主页列表。 */
    private val importedNodes: Flow<List<Node>> = selectedNodeGroupId.flatMapLatest { groupId ->
        val group = NodeGroupKey.parse(groupId) as? NodeGroupKey.Imported
        if (group == null) {
            flowOf(emptyList())
        } else {
            nodeDaoFlow.flatMapLatest { it.getNodesByGroup(group.groupId) }
        }
    }

    // Data
    private val visibleNodes: Flow<List<Node>> = combine(
        subscriptionNodes,
        importedNodes,
        countryGroupingNodes,
        selectedNodeGroupId
    ) { subscription: List<Node>, imported: List<Node>, warehouseNodes: List<Node>, groupId: String? ->
        when (val group = NodeGroupKey.parse(groupId)) {
            is NodeGroupKey.Imported -> imported
            is NodeGroupKey.Country -> warehouseNodes.filter {
                it.getCountryCode() == group.countryCode
            }
            else -> subscription
        }
    }

    val nodes: StateFlow<List<Node>> = combine(
        visibleNodes,
        activeConnectionNode
    ) { visibleNodes: List<Node>, connectedNode: Node? ->
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

    val lanInboundSettings = settingsRepository.lanInboundSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LanInboundSettings()
    )

    val failoverSettings = settingsRepository.failoverSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        FailoverSettings()
    )
    val failoverRuntimeStatus = ServiceManager.failoverStatus

    val homeSpeedTestSettings = settingsRepository.homeSpeedTestSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SpeedTestSettings.default()
    )

    val groupManualSpeedTestSettings = settingsRepository.groupManualSpeedTestSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SpeedTestSettings.default()
    )

    val ipFamilySettings = settingsRepository.ipFamilySettings.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IpFamilyRoutingSettings.DEFAULT
    )

    val ipAddressTestSettings = settingsRepository.ipAddressTestSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        IpAddressTestSettings.DEFAULT
    )

    val homeModuleSettings = settingsRepository.homeModuleSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HomeModuleSettings.DEFAULT
    )

    private val _ipAddressDetectionState = MutableStateFlow(IpAddressDetectionState())
    val ipAddressDetectionState = _ipAddressDetectionState.asStateFlow()

    val dnsSettings = settingsRepository.dnsSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        DnsSettings()
    )

    val smartRoutingSettings = settingsRepository.smartRoutingSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SmartRoutingSettings()
    )
    val globalRoutingSettings = settingsRepository.globalRoutingSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SmartRoutingSettings()
    )

    /** 路由规则页面按用户正在编辑的模式选择独立配置。 */
    fun routingSettings(mode: ProxyMode): StateFlow<SmartRoutingSettings> =
        if (mode == ProxyMode.GLOBAL) globalRoutingSettings else smartRoutingSettings
    
    // 自动化测试设置
    val serviceRequestTimeoutMs = settingsRepository.serviceRequestTimeoutMs.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SettingsRepository.DEFAULT_SERVICE_REQUEST_TIMEOUT_MS
    )
    private val accountSession = AccountSessionCoordinator(
        scope = viewModelScope,
        accountManager = accountManager,
        serviceRequestTimeoutMs = serviceRequestTimeoutMs,
        vpnState = vpnState,
        onError = { message -> _error.value = message },
        refreshSubscriptions = { forceRefresh ->
            initializeSubscriptionsWithRefreshState(forceRefresh = forceRefresh)
        },
        loadStartupData = { loadStartupDataAfterAuthentication() },
        clearLocalData = { clearLocalApplicationData() }
    )

    val accountInfo = accountSession.accountInfo
    val clientNotifications = accountSession.clientNotifications
    val notificationUnreadCount = accountSession.notificationUnreadCount
    val isLoadingNotifications = accountSession.isLoadingNotifications
    val notificationError = accountSession.notificationError
    val offlineModeEnabled = accountSession.offlineModeEnabled
    val isActivatingVip = accountSession.isActivatingVip
    val isUpdatingNickname = accountSession.isUpdatingNickname
    val isSyncingAccount = accountSession.isSyncingAccount
    val isStartupAccountActionInProgress = accountSession.isStartupAccountActionInProgress
    val startupAccountAuthenticated = accountSession.startupAccountAuthenticated
    val isBindingAccount = accountSession.isBindingAccount
    val isSwitchingAccount = accountSession.isSwitchingAccount
    val isDeletingAccount = accountSession.isDeletingAccount
    val accountMessage = accountSession.accountMessage

    val regionLookupMethods = settingsRepository.regionLookupMethods.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RegionLookupMethod.defaultRules
    )
    val tcpingTestTimeoutMs = settingsRepository.tcpingTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.TCPING_TEST_TIMEOUT)
    val speedTestDownloadUrl = settingsRepository.speedTestDownloadUrl.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SPEED_TEST_DOWNLOAD_URL)
    val speedTestDurationMs = settingsRepository.speedTestDurationMs.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.AUTO_TEST_BANDWIDTH_DURATION_MS)
    val tcpingConcurrency = settingsRepository.tcpingConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.TCPING_CONCURRENCY)
    val bandwidthTestConcurrency = settingsRepository.bandwidthTestConcurrency.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppConfig.BANDWIDTH_TEST_CONCURRENCY
    )
    val hysteriaBandwidthEnabled = settingsRepository.hysteriaBandwidthEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_BANDWIDTH_ENABLED)
    val hysteriaUpMbps = settingsRepository.hysteriaUpMbps.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_UP_MBPS)
    val hysteriaDownMbps = settingsRepository.hysteriaDownMbps.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.HYSTERIA_DOWN_MBPS)
    val protocolCoreSettings = settingsRepository.protocolCoreSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ProtocolCoreSettings()
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

    /** 节点列表卡片批量读取每个节点最近一次的独立测试结果。 */
    internal val allNodeTestResults = nodeDaoFlow
        .flatMapLatest { dao -> dao.observeAllHomeNodeTestResults() }
        .map { results -> results.associateBy(HomeNodeTestResultsEntity::nodeId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val storedHomeTestResultsForCurrentNode = currentNode.flatMapLatest { node ->
        if (node == null) {
            flowOf(null)
        } else {
            nodeDaoFlow.flatMapLatest { dao ->
                dao.observeHomeNodeTestResults(node.id).map { results -> node.id to results }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val homeTestNodeSelection = storedHomeTestResultsForCurrentNode
        .distinctUntilChangedBy { it?.first }

    /** 首页卡片优先展示独立持久化的测试数据，订阅刷新替换节点时也能保留原结果。 */
    internal val homeTestNode = combine(
        currentNode,
        storedHomeTestResultsForCurrentNode
    ) { node, stored ->
        if (node == null) return@combine null
        val results = stored?.takeIf { it.first == node.id }?.second
        node.copy(
            tcpingLatency = results?.tcpingLatency?.takeIf { it >= 0 } ?: node.tcpingLatency,
            urlTestLatency = results?.urlTestLatency?.takeIf { it >= 0 } ?: node.urlTestLatency,
            downloadMbps = results?.downloadMbps?.takeIf { it > 0f } ?: node.downloadMbps
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    init {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.migrateLegacySpeedTestSettings()
        }
        viewModelScope.launch {
            homeTestNodeSelection.collect { stored ->
                val results = stored?.second
                _linkTypeTestResult.value = results?.linkTypeResultJson
                    ?.takeIf(String::isNotBlank)
                    ?.let(HomeTestResultCodec::decodeLinkType)
                    ?: LinkTypeTestResult()
                _unlockTestState.value = results?.unlockTestResultJson
                    ?.takeIf(String::isNotBlank)
                    ?.let(HomeTestResultCodec::decodeUnlockTests)
                    ?: UnlockTestUiState()
            }
        }
        // 地区查询只使用用户配置的三种方式，不依赖 Chimera 后台端点。
        viewModelScope.launch {
            nodeDaoFlow.flatMapLatest { dao -> dao.getAllNodes() }
                .map { nodes -> nodes.filter { it.country == null } }
                .distinctUntilChangedBy { nodes -> nodes.associate { it.id to it.server } }
                .collect { nodes -> scheduleNodeLocationEnrichment(nodes) }
        }
        viewModelScope.launch {
            // DataStore 也会因其他设置变化而发射；仅查询规则实际变化时才清空并重查地区。
            settingsRepository.regionLookupMethods
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    val nodes = nodeDao().getAllNodes().first()
                    if (nodes.isNotEmpty()) {
                        // 旧查询结果不能挡住新的卡片顺序，因此先清空再完整重查。
                        nodeLocationEnrichmentJob?.cancel()
                        nodeLocationEnrichmentJob = null
                        nodeDao().clearNodeLocations()
                        scheduleNodeLocationEnrichment(
                            nodes.map { it.copy(country = null, countryName = null) }
                        )
                    }
                }
        }
        // 链路类型结果属于节点的历史检测数据，VPN 断开后仍保留并在首页展示。
        viewModelScope.launch {
            try {
                // Keep the cached selection available immediately; refreshing
                // data should not blank and rewrite DataStore on every launch.
                _isInitialized.value = true

                // 离线模式不执行 Chimera 后台同步；在线账户才启动后台同步。

                subscriptionDataLoader.loadCachedSubscriptionList()
                if (!accountManager.isOfflineMode() && accountManager.currentAccount() != null) {
                    loadStartupDataAfterAuthentication()
                }
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
        if (nodes.isEmpty()) {
            nodeLocationEnrichmentJob?.cancel()
            nodeLocationEnrichmentJob = null
            return
        }
        nodeLocationEnrichmentJob?.cancel()
        nodeLocationEnrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1_500)
            enrichAndPersistNodeLocations(nodes)
        }
    }

    private suspend fun enrichAndPersistNodeLocations(nodes: List<Node>) {
        val sourceById = nodes.associateBy(Node::id)
        val geoIpDatabase = File(
            RoutingAssetsRepository.assetDirectory(getApplication()),
            "geoip.dat"
        )
        val updates = NodeLocationResolver.enrichNodes(
            nodes = nodes,
            methods = regionLookupMethods.value,
            geoIpDatabase = geoIpDatabase
        ).mapNotNull { enrichedNode ->
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

    private fun updateSpeedTestProgress(
        title: String,
        stage: String,
        completed: Int = 0,
        total: Int = 0,
        isManualGroupTest: Boolean = false
    ) {
        _speedTestProgress.value = SpeedTestProgress(
            title = title,
            stage = stage,
            completed = completed.coerceAtLeast(0),
            total = total.coerceAtLeast(0),
            isManualGroupTest = isManualGroupTest
        )
    }

    /** 对当前选中节点执行主页已启用的测试，只保存结果，不筛选或连接节点。 */
    fun startCurrentNodeDataTest() {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }

            val settings = homeSpeedTestSettings.value
            val enabledStages = normalizeSpeedTestStageOrder(settings.stageOrder).filter { stage ->
                when (stage) {
                    SpeedTestStage.TCPING -> settings.tcpingEnabled
                    SpeedTestStage.URL_TEST -> settings.urlTestEnabled
                    SpeedTestStage.BANDWIDTH -> settings.bandwidthEnabled
                    SpeedTestStage.LINK_TYPE -> settings.linkTypeTestEnabled
                    SpeedTestStage.IP_QUALITY ->
                        settings.unlockTestsEnabled && settings.unlockTestIds.isNotEmpty()
                }
            }
            if (enabledStages.isEmpty()) {
                _error.value = "请先在主页测速设置中启用至少一项测试"
                return@launch
            }
            if (!GlobalTestExecution.tryStart("当前节点数据测试")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            val testJob = currentCoroutineContext()[Job]
            activeTestJob = testJob
            _isTesting.value = true
            val progressTitle = "当前节点数据测试"
            updateSpeedTestProgress(progressTitle, "准备测试")
            try {
                val nodeId = activeConnectionNode.value?.id
                    ?: settingsRepository.selectedNodeId.first()
                val node = nodeId?.let { nodeDao().getNodeById(it) }
                if (node == null) {
                    _error.value = "请先选择一个节点"
                    return@launch
                }

                for (stage in enabledStages) {
                    currentCoroutineContext().ensureActive()
                    val progressStage = when (stage) {
                        SpeedTestStage.TCPING -> "TCPing"
                        SpeedTestStage.URL_TEST -> "URL Test"
                        SpeedTestStage.BANDWIDTH -> "带宽测速"
                        SpeedTestStage.LINK_TYPE -> "第 4 项链路类型检测"
                        SpeedTestStage.IP_QUALITY -> "第 5 项流媒体与 AI 解锁检测"
                    }
                    updateSpeedTestProgress(progressTitle, progressStage, total = 1)

                    when (stage) {
                        SpeedTestStage.TCPING -> {
                            internalTestNodes(
                                currentNodes = listOf(node),
                                concurrency = 1,
                                timeoutMs = settings.tcpingTimeoutMs,
                                autoDeleteTimeoutNodes = false,
                                updateSortMode = false,
                                onProgress = { completed, total ->
                                    updateSpeedTestProgress(
                                        progressTitle,
                                        progressStage,
                                        completed,
                                        total
                                    )
                                }
                            )
                        }

                        SpeedTestStage.URL_TEST -> {
                            val results = runUrlTestNodes(
                                nodes = listOf(node),
                                concurrency = 1,
                                warmupTimeoutMs = settings.urlTestWarmupTimeoutMs,
                                measurementTimeoutMs = settings.urlTestMeasurementTimeoutMs,
                                targetUrl = settings.urlTestUrl,
                                fallbackUrl = settings.urlTestBackupUrl,
                                onProgress = { completed, total ->
                                    updateSpeedTestProgress(
                                        progressTitle,
                                        progressStage,
                                        completed,
                                        total
                                    )
                                }
                            )
                            applyUrlTestResults(
                                results,
                                autoDeleteTimeoutNodes = false,
                                updateSortMode = false
                            )
                        }

                        SpeedTestStage.BANDWIDTH -> {
                            val speedMbps = testNodeDownloadBandwidthMbps(
                                node = node,
                                durationMs = settings.bandwidthTestDurationMs,
                                downloadUrl = settings.bandwidthTestUrl
                            )
                            nodeDao().updateDownloadMbps(node.id, speedMbps)
                            updateSpeedTestProgress(
                                progressTitle,
                                progressStage,
                                completed = 1,
                                total = 1
                            )
                        }

                        SpeedTestStage.LINK_TYPE -> {
                            runHomeCandidateFilterTests(
                                settings = settings.copy(
                                    linkTypeAcceptDirect = true,
                                    linkTypeAcceptRelay = true,
                                    linkTypeAcceptCdnRelay = true
                                ),
                                nodes = listOf(node),
                                runLinkType = true,
                                runUnlockTests = false,
                                title = progressTitle,
                                progressStage = progressStage
                            )
                            val storedResults = nodeDao().getHomeNodeTestResults(node.id)
                            _linkTypeTestResult.value = storedResults?.linkTypeResultJson
                                ?.takeIf(String::isNotBlank)
                                ?.let(HomeTestResultCodec::decodeLinkType)
                                ?: LinkTypeTestResult()
                        }

                        SpeedTestStage.IP_QUALITY -> {
                            runHomeCandidateFilterTests(
                                settings = settings,
                                nodes = listOf(node),
                                runLinkType = false,
                                runUnlockTests = true,
                                title = progressTitle,
                                progressStage = progressStage
                            )
                            val storedResults = nodeDao().getHomeNodeTestResults(node.id)
                            _unlockTestState.value = storedResults?.unlockTestResultJson
                                ?.takeIf(String::isNotBlank)
                                ?.let(HomeTestResultCodec::decodeUnlockTests)
                                ?: UnlockTestUiState()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "Current node data test failed", error)
                _error.value = "当前节点测试失败：${error.message ?: "未知错误"}"
            } finally {
                if (activeTestJob === testJob) activeTestJob = null
                _isTesting.value = false
                _speedTestProgress.value = null
                GlobalTestExecution.finish()
            }
        }
    }

    /** 按主页图标测速设置筛选节点并连接测速结果最好的节点。 */
    fun startHomeSpeedTest() {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            val settings = homeSpeedTestSettings.value
            val shouldRunUnlockTests =
                settings.unlockTestsEnabled && settings.unlockTestIds.isNotEmpty()
            val hasSelectionTests = settings.enabledQualityStages.isNotEmpty()
            if (!hasSelectionTests && !settings.linkTypeTestEnabled && !shouldRunUnlockTests
            ) {
                // 所有检测均关闭时按普通连接处理。
                startSelectedOrFirstConnection()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("主页图标测速")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            val testJob = currentCoroutineContext()[Job]
            activeTestJob = testJob
            _isTesting.value = true
            val progressTitle = "主页图标测速"
            updateSpeedTestProgress(progressTitle, "准备测试")
            try {
                val allNodes = currentNodeListSnapshot()
                if (allNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }

                val nodesById = allNodes.associateBy { it.id }
                val stageResults = mutableMapOf<SpeedTestStage, List<Node>>()

                fun stageCandidates(stage: SpeedTestStage) = SpeedTestCandidateSelector.candidatesForStage(
                    settings = settings, stage = stage, currentGroupCandidates = allNodes,
                    previousResults = stageResults
                )

                // 阶段顺序固定为 1 -> 2 -> 3；关闭任一阶段时跳过，但不改变后续顺序。
                if (settings.tcpingEnabled) {
                    val tcpingNodes = stageCandidates(SpeedTestStage.TCPING)
                    updateSpeedTestProgress(
                        title = progressTitle,
                        stage = "TCPing",
                        total = tcpingNodes.size
                    )
                    val tcpingResults = internalTestNodes(
                        currentNodes = tcpingNodes,
                        concurrency = SpeedTestCandidateSelector.concurrencyForStage(
                            settings,
                            SpeedTestStage.TCPING,
                            tcpingNodes.size
                        ),
                        timeoutMs = settings.tcpingTimeoutMs,
                        autoDeleteTimeoutNodes = settings.tcpingAutoDeleteTimeoutNodes,
                        onProgress = { completed, total ->
                            updateSpeedTestProgress(
                                title = progressTitle,
                                stage = "TCPing",
                                completed = completed,
                                total = total
                            )
                        }
                    )
                    val candidates = tcpingResults
                        .filter { it.isAvailable && it.latency > 0 }
                        .sortedBy { it.latency }
                        .mapNotNull { nodesById[it.nodeId] }
                    val retainedResults = SpeedTestCandidateSelector.retainResultsForStage(
                        settings, SpeedTestStage.TCPING, candidates
                    )
                    stageResults[SpeedTestStage.TCPING] = retainedResults
                    if (retainedResults.isEmpty()) {
                        _error.value = "主页测速：TCPing 未找到可用节点"
                        return@launch
                    }
                }

                if (settings.urlTestEnabled) {
                    val urlTestNodes = stageCandidates(SpeedTestStage.URL_TEST)
                    updateSpeedTestProgress(
                        title = progressTitle,
                        stage = "URL Test",
                        total = urlTestNodes.size
                    )
                    val urlTestResults = runUrlTestNodes(
                        nodes = urlTestNodes,
                        concurrency = SpeedTestCandidateSelector.concurrencyForStage(
                            settings,
                            SpeedTestStage.URL_TEST,
                            urlTestNodes.size
                        ),
                        warmupTimeoutMs = settings.urlTestWarmupTimeoutMs,
                        measurementTimeoutMs = settings.urlTestMeasurementTimeoutMs,
                        targetUrl = settings.urlTestUrl,
                        fallbackUrl = settings.urlTestBackupUrl,
                        onProgress = { completed, total ->
                            updateSpeedTestProgress(
                                title = progressTitle,
                                stage = "URL Test",
                                completed = completed,
                                total = total
                            )
                        }
                    )
                    applyUrlTestResults(
                        urlTestResults,
                        autoDeleteTimeoutNodes = settings.urlTestAutoDeleteTimeoutNodes
                    )
                    val candidates = urlTestResults
                        .filter { it.isAvailable && it.latency > 0 }
                        .sortedBy { it.latency }
                        .mapNotNull { nodesById[it.nodeId] }
                    val retainedResults = SpeedTestCandidateSelector.retainResultsForStage(
                        settings, SpeedTestStage.URL_TEST, candidates
                    )
                    stageResults[SpeedTestStage.URL_TEST] = retainedResults
                    if (retainedResults.isEmpty()) {
                        _error.value = "主页测速：URL Test 未找到可用节点"
                        return@launch
                    }
                }

                var eligibleNodes = if (settings.bandwidthEnabled) {
                    val speedTestNodes = stageCandidates(SpeedTestStage.BANDWIDTH)
                    updateSpeedTestProgress(
                        title = progressTitle,
                        stage = "带宽测速",
                        total = speedTestNodes.size
                    )
                    var completedBandwidthNodes = 0
                    val speedResults = speedTestNodes
                        .chunked(
                            SpeedTestCandidateSelector.concurrencyForStage(
                                settings,
                                SpeedTestStage.BANDWIDTH,
                                speedTestNodes.size
                            )
                        )
                        .flatMap { batch ->
                            val results = coroutineScope {
                                batch.map { node ->
                                    async {
                                        val speedMbps = testNodeDownloadBandwidthMbps(
                                            node = node,
                                            durationMs = settings.bandwidthTestDurationMs,
                                            downloadUrl = settings.bandwidthTestUrl
                                        )
                                        nodeDao().updateDownloadMbps(node.id, speedMbps)
                                        node to speedMbps
                                    }
                                }.awaitAll()
                            }
                            completedBandwidthNodes += batch.size
                            updateSpeedTestProgress(
                                title = progressTitle,
                                stage = "带宽测速",
                                completed = completedBandwidthNodes,
                                total = speedTestNodes.size
                            )
                            results
                        }
                    _nodeTestResultSortMode.value =
                        NodeTestResultSortMode.DOWNLOAD_BANDWIDTH
                    val deleteLowBandwidth = settings.bandwidthAutoDeleteLowSpeedNodes
                    val bandwidthThreshold = settings.bandwidthAutoDeleteThresholdMbps
                    if (deleteLowBandwidth) {
                        deleteNodes(
                            nodeIds = speedResults
                                .filter { (_, speedMbps) -> speedMbps < bandwidthThreshold.toFloat() }
                                .map { (node, _) -> node.id },
                            reason = "带宽低于 ${bandwidthThreshold} Mbps"
                        )
                    }
                    val orderedSpeedResults = speedResults
                        .filter { (_, speedMbps) ->
                            !deleteLowBandwidth || speedMbps >= bandwidthThreshold.toFloat()
                        }
                        .sortedWith(
                            compareByDescending<Pair<Node, Float>> { it.second }
                                .thenBy { it.first.latency }
                        )
                    val retainedNodes = SpeedTestCandidateSelector.retainResultsForStage(
                        settings,
                        SpeedTestStage.BANDWIDTH,
                        orderedSpeedResults
                    ).map { it.first }
                    stageResults[SpeedTestStage.BANDWIDTH] = retainedNodes
                    retainedNodes
                } else {
                    settings.enabledQualityStages.lastOrNull()
                        ?.let { stageResults[it] }
                        ?: allNodes
                }

                if (settings.linkTypeTestEnabled || shouldRunUnlockTests) {
                    val firstFilterStage = if (settings.linkTypeTestEnabled) {
                        SpeedTestStage.LINK_TYPE
                    } else {
                        SpeedTestStage.IP_QUALITY
                    }
                    val candidates = stageCandidates(firstFilterStage)
                    updateSpeedTestProgress(
                        title = progressTitle,
                        stage = "第 4、5 项筛选线路",
                        total = candidates.size
                    )
                    val filterResults = runHomeCandidateFilterTests(
                        settings = settings,
                        nodes = candidates,
                        runLinkType = settings.linkTypeTestEnabled,
                        runUnlockTests = shouldRunUnlockTests,
                        title = progressTitle
                    )
                    if (settings.linkTypeTestEnabled) {
                        val linkTypeCandidates = filterResults
                            .filter(HomeCandidateFilterResult::linkTypePassed)
                            .map(HomeCandidateFilterResult::node)
                        stageResults[SpeedTestStage.LINK_TYPE] = linkTypeCandidates
                        if (linkTypeCandidates.isEmpty()) {
                            _error.value = "主页测速：没有线路符合第 4 项的类型筛选"
                            return@launch
                        }
                    }
                    if (shouldRunUnlockTests) {
                        val unlockCandidates = filterResults
                            .filter(HomeCandidateFilterResult::unlockTestsPassed)
                            .map(HomeCandidateFilterResult::node)
                        stageResults[SpeedTestStage.IP_QUALITY] = unlockCandidates
                        if (unlockCandidates.isEmpty()) {
                            _error.value = "主页测速：没有线路通过全部所选解锁测试"
                            return@launch
                        }
                    }
                    eligibleNodes = when {
                        shouldRunUnlockTests -> stageResults[SpeedTestStage.IP_QUALITY].orEmpty()
                        settings.linkTypeTestEnabled -> stageResults[SpeedTestStage.LINK_TYPE].orEmpty()
                        else -> eligibleNodes
                    }
                }

                val bestNode = eligibleNodes.firstOrNull()
                if (bestNode == null) {
                    _error.value = if (settings.bandwidthEnabled && settings.bandwidthAutoDeleteLowSpeedNodes) {
                        "没有节点达到带宽阈值（${settings.bandwidthAutoDeleteThresholdMbps} Mbps）"
                    } else {
                        "测速未找到可连接节点"
                    }
                    return@launch
                }

                val storedResults = nodeDao().getHomeNodeTestResults(bestNode.id)
                if (settings.linkTypeTestEnabled) {
                    _linkTypeTestResult.value = storedResults?.linkTypeResultJson
                        ?.takeIf(String::isNotBlank)
                        ?.let(HomeTestResultCodec::decodeLinkType)
                        ?: LinkTypeTestResult()
                }
                if (shouldRunUnlockTests) {
                    _unlockTestState.value = storedResults?.unlockTestResultJson
                        ?.takeIf(String::isNotBlank)
                        ?.let(HomeTestResultCodec::decodeUnlockTests)
                        ?: UnlockTestUiState()
                }
                settingsRepository.setSelectedNodeId(bestNode.id)
                Log.i(
                    tag,
                    "Speed test filters retained ${eligibleNodes.size} routes; " +
                        "selected ${bestNode.getDisplayName()}"
                )
                ServiceManager.startVpn(getApplication(), bestNode, proxyMode.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Speed test settings failed", e)
                _error.value = "测速失败：${e.message ?: "未知错误"}"
            } finally {
                if (activeTestJob === testJob) activeTestJob = null
                _isTesting.value = false
                _speedTestProgress.value = null
                GlobalTestExecution.finish()
            }
        }
    }

    /** 清除当前节点列表分组内的全部测试结果。 */
    fun clearNodeTestResults(
        groupNodes: List<Node>,
        onComplete: (String, Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val clearedCount = nodeDao().clearNodeTestResults(groupNodes.map(Node::id))
                onComplete("已清理 $clearedCount 个节点的测试数据", true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Failed to clear node test results", e)
                onComplete("清理测试数据失败：${e.message ?: "未知错误"}", false)
            }
        }
    }

    /** 按手动测速设置执行当前线路详情页中的单阶段测试。 */
    fun runManualGroupTest(groupNodes: List<Node>, stage: ManualGroupTestStage) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            val settings = groupManualSpeedTestSettings.value
            if (stage == ManualGroupTestStage.IP_QUALITY && settings.unlockTestIds.isEmpty()) {
                _error.value = "请先在手动测试的第 5 项中选择要检测的服务"
                return@launch
            }
            if (!GlobalTestExecution.tryStart("分组内手动测试：$stage")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            val testJob = currentCoroutineContext()[Job]
            activeTestJob = testJob
            _isTesting.value = true
            val progressTitle = "分组手动测试"
            val progressStage = when (stage) {
                ManualGroupTestStage.TCPING -> "TCPing"
                ManualGroupTestStage.URL_TEST -> "URL Test"
                ManualGroupTestStage.BANDWIDTH -> "带宽测速"
                ManualGroupTestStage.LINK_TYPE -> "第 4 项链路类型检测"
                ManualGroupTestStage.IP_QUALITY -> "第 5 项流媒体与 AI 解锁检测"
            }
            updateSpeedTestProgress(
                title = progressTitle,
                stage = progressStage,
                isManualGroupTest = true
            )
            try {
                val latestNodesById = nodeDao().getAllNodes().first().associateBy(Node::id)
                val currentGroupNodes = groupNodes.mapNotNull { latestNodesById[it.id] }
                if (currentGroupNodes.isEmpty()) {
                    _error.value = "当前分组没有可测试节点"
                    return@launch
                }

                when (stage) {
                    ManualGroupTestStage.TCPING -> {
                        val candidates = currentGroupNodes
                        updateSpeedTestProgress(
                            title = progressTitle,
                            stage = progressStage,
                            total = candidates.size,
                            isManualGroupTest = true
                        )
                        internalTestNodes(
                            currentNodes = candidates,
                            concurrency = SpeedTestCandidateSelector.concurrencyForStage(
                                settings,
                                SpeedTestStage.TCPING,
                                candidates.size
                            ),
                            timeoutMs = settings.tcpingTimeoutMs,
                            autoDeleteTimeoutNodes = settings.tcpingAutoDeleteTimeoutNodes,
                            onProgress = { completed, total ->
                                updateSpeedTestProgress(
                                    title = progressTitle,
                                    stage = progressStage,
                                    completed = completed,
                                    total = total,
                                    isManualGroupTest = true
                                )
                            }
                        )
                    }

                    ManualGroupTestStage.URL_TEST -> {
                        val candidates = currentGroupNodes
                        updateSpeedTestProgress(
                            title = progressTitle,
                            stage = progressStage,
                            total = candidates.size,
                            isManualGroupTest = true
                        )
                        val results = runUrlTestNodes(
                            nodes = candidates,
                            concurrency = SpeedTestCandidateSelector.concurrencyForStage(
                                settings,
                                SpeedTestStage.URL_TEST,
                                candidates.size
                            ),
                            warmupTimeoutMs = settings.urlTestWarmupTimeoutMs,
                            measurementTimeoutMs = settings.urlTestMeasurementTimeoutMs,
                            targetUrl = settings.urlTestUrl,
                            fallbackUrl = settings.urlTestBackupUrl,
                            onProgress = { completed, total ->
                                updateSpeedTestProgress(
                                    title = progressTitle,
                                    stage = progressStage,
                                    completed = completed,
                                    total = total,
                                    isManualGroupTest = true
                                )
                            }
                        )
                        if (results.isEmpty()) {
                            _error.value = "URL Test 未能启动"
                            return@launch
                        }
                        applyUrlTestResults(
                            results,
                            autoDeleteTimeoutNodes = settings.urlTestAutoDeleteTimeoutNodes
                        )
                    }

                    ManualGroupTestStage.BANDWIDTH -> {
                        val candidates = currentGroupNodes
                        updateSpeedTestProgress(
                            title = progressTitle,
                            stage = progressStage,
                            total = candidates.size,
                            isManualGroupTest = true
                        )
                        var completedBandwidthNodes = 0
                        val speedResults = candidates
                            .chunked(
                                SpeedTestCandidateSelector.concurrencyForStage(
                                    settings,
                                    SpeedTestStage.BANDWIDTH,
                                    candidates.size
                                )
                            )
                            .flatMap { batch ->
                                val results = coroutineScope {
                                    batch.map { node ->
                                        async {
                                            val speedMbps = testNodeDownloadBandwidthMbps(
                                                node = node,
                                                durationMs = settings.bandwidthTestDurationMs,
                                                downloadUrl = settings.bandwidthTestUrl
                                            )
                                            nodeDao().updateDownloadMbps(node.id, speedMbps)
                                            node to speedMbps
                                        }
                                    }.awaitAll()
                                }
                                completedBandwidthNodes += batch.size
                                updateSpeedTestProgress(
                                    title = progressTitle,
                                    stage = progressStage,
                                    completed = completedBandwidthNodes,
                                    total = candidates.size,
                                    isManualGroupTest = true
                                )
                                results
                            }
                        if (settings.bandwidthAutoDeleteLowSpeedNodes) {
                            val threshold = settings.bandwidthAutoDeleteThresholdMbps
                            deleteNodes(
                                nodeIds = speedResults
                                    .filter { (_, speedMbps) -> speedMbps < threshold.toFloat() }
                                    .map { (node, _) -> node.id },
                                reason = "带宽低于 ${threshold} Mbps"
                            )
                        }
                        _nodeTestResultSortMode.value =
                            NodeTestResultSortMode.DOWNLOAD_BANDWIDTH
                    }

                    ManualGroupTestStage.LINK_TYPE -> {
                        runHomeCandidateFilterTests(
                            settings = settings.copy(
                                linkTypeAcceptDirect = true,
                                linkTypeAcceptRelay = true,
                                linkTypeAcceptCdnRelay = true
                            ),
                            nodes = currentGroupNodes,
                            runLinkType = true,
                            runUnlockTests = false,
                            title = progressTitle,
                            progressStage = progressStage,
                            isManualGroupTest = true
                        )
                    }

                    ManualGroupTestStage.IP_QUALITY -> {
                        runHomeCandidateFilterTests(
                            settings = settings,
                            nodes = currentGroupNodes,
                            runLinkType = false,
                            runUnlockTests = true,
                            title = progressTitle,
                            progressStage = progressStage,
                            isManualGroupTest = true
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "Manual group test failed: $stage", error)
                _error.value = "分组手动测试失败：${error.message ?: "未知错误"}"
            } finally {
                if (activeTestJob === testJob) activeTestJob = null
                _isTesting.value = false
                _speedTestProgress.value = null
                GlobalTestExecution.finish()
            }
        }
    }

    /** 在不创建 TUN/VPN 的情况下，用临时代理会话批量筛选候选线路。 */
    private suspend fun runHomeCandidateFilterTests(
        settings: SpeedTestSettings,
        nodes: List<Node>,
        runLinkType: Boolean,
        runUnlockTests: Boolean,
        title: String,
        progressStage: String = "第 4、5 项筛选线路",
        isManualGroupTest: Boolean = false
    ): List<HomeCandidateFilterResult> = coroutineScope {
        if (nodes.isEmpty()) return@coroutineScope emptyList()

        val definitions = if (runUnlockTests) {
            UnlockTestCatalog.definitions.filter { it.id in settings.unlockTestIds }
        } else {
            emptyList()
        }
        val linkTypeNodeConcurrency = if (runLinkType) {
            SpeedTestCandidateSelector.concurrencyForStage(
                settings,
                SpeedTestStage.LINK_TYPE,
                nodes.size
            )
        } else {
            1
        }
        val unlockNodeConcurrency = if (runUnlockTests) {
            SpeedTestCandidateSelector.concurrencyForStage(
                settings,
                SpeedTestStage.IP_QUALITY,
                nodes.size
            )
        } else {
            1
        }
        // 节点会话、链路检测、解锁节点数和服务请求数分别受各自并发上限约束。
        val nodeSemaphore = Semaphore(
            nodes.size.coerceAtMost(maxOf(linkTypeNodeConcurrency, unlockNodeConcurrency))
        )
        val linkTypeSemaphore = Semaphore(linkTypeNodeConcurrency)
        val unlockNodeSemaphore = Semaphore(unlockNodeConcurrency)
        val unlockRequestSemaphore = Semaphore(
            settings.unlockTestRequestConcurrency.coerceIn(
                1,
                SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
            )
        )
        val progressMutex = Mutex()
        var completed = 0

        nodes.map { node ->
            async {
                val outcome = nodeSemaphore.withPermit {
                    var proxySession: NodeTestProxyManager.Session? = null
                    try {
                        val session = NodeTestProxyManager.createSession(
                            context = getApplication(),
                            node = node,
                            hysteriaUpMbps = hysteriaUpMbps.value,
                            hysteriaDownMbps = hysteriaDownMbps.value,
                            core = protocolCoreSettings.value.coreFor(node.type)
                        )
                        proxySession = session
                        val started = withTimeoutOrNull(PRE_VPN_TEST_SESSION_START_TIMEOUT_MS) {
                            session.start()
                        } == true
                        if (!started) {
                            val message = "节点临时代理启动失败，无法执行筛选"
                            if (runLinkType) {
                                persistLinkTypeTestResult(
                                    node.id,
                                    LinkTypeTestResult(
                                        state = LinkTypeTestState.ERROR,
                                        serverAddress = node.server,
                                        message = message
                                    )
                                )
                            }
                            if (runUnlockTests) {
                                persistUnlockTestResult(
                                    node.id,
                                    unlockTestFailureState(
                                        definitions,
                                        message,
                                        UnlockTestState.ERROR
                                    )
                                )
                            }
                            return@withPermit HomeCandidateFilterResult(
                                node = node,
                                linkTypePassed = false,
                                unlockTestsPassed = false
                            )
                        }

                        val linkTypePassed = if (runLinkType) {
                            linkTypeSemaphore.withPermit {
                                val result = runLinkTypeTestForCandidate(node, session.endpoint)
                                when (result.state) {
                                    LinkTypeTestState.DIRECT -> settings.linkTypeAcceptDirect
                                    LinkTypeTestState.RELAY -> settings.linkTypeAcceptRelay
                                    LinkTypeTestState.CDN_RELAY -> settings.linkTypeAcceptCdnRelay
                                    else -> false
                                }
                            }
                        } else {
                            true
                        }

                        val unlockState = when {
                            !runUnlockTests -> null
                            !linkTypePassed -> unlockTestFailureState(
                                definitions,
                                "未通过第 4 项线路类型筛选，已跳过解锁检测",
                                UnlockTestState.NOT_CONNECTED
                            )
                            else -> unlockNodeSemaphore.withPermit {
                                runUnlockTestsForCandidate(
                                    node = node,
                                    endpoint = session.endpoint,
                                    definitions = definitions,
                                    requestSemaphore = unlockRequestSemaphore
                                )
                            }
                        }
                        if (runUnlockTests && unlockState != null && !linkTypePassed) {
                            persistUnlockTestResult(node.id, unlockState)
                        }
                        val unlockResults = unlockState?.items.orEmpty()
                        val unlockTestsPassed = !runUnlockTests || (
                            unlockResults.size == definitions.size &&
                                unlockResults.all { it.state == UnlockTestState.UNLOCKED }
                            )
                        HomeCandidateFilterResult(
                            node = node,
                            linkTypePassed = linkTypePassed,
                            unlockTestsPassed = unlockTestsPassed
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(tag, "Pre-VPN candidate test failed for ${node.id}", error)
                        val message = "临时代理测试失败：${error.message ?: "未知错误"}"
                        if (runLinkType) {
                            persistLinkTypeTestResult(
                                node.id,
                                LinkTypeTestResult(
                                    state = LinkTypeTestState.ERROR,
                                    serverAddress = node.server,
                                    message = message
                                )
                            )
                        }
                        if (runUnlockTests) {
                            persistUnlockTestResult(
                                node.id,
                                unlockTestFailureState(definitions, message, UnlockTestState.ERROR)
                            )
                        }
                        HomeCandidateFilterResult(
                            node = node,
                            linkTypePassed = false,
                            unlockTestsPassed = false
                        )
                    } finally {
                        proxySession?.let { session ->
                            withContext(NonCancellable + Dispatchers.IO) {
                                runCatching { session.stop() }
                            }
                        }
                    }
                }
                progressMutex.withLock {
                    completed += 1
                    updateSpeedTestProgress(
                        title = title,
                        stage = progressStage,
                        completed = completed,
                        total = nodes.size,
                        isManualGroupTest = isManualGroupTest
                    )
                }
                outcome
            }
        }.awaitAll()
    }

    private suspend fun runLinkTypeTestForCandidate(
        node: Node,
        endpoint: io.github.HanJason233.chimera.core.LocalSocksEndpoint
    ): LinkTypeTestResult {
        val result = try {
            LinkTypeTestRunner(endpoint, resolveServer = ::resolveNodeServerAddresses)
                .run(node.server)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LinkTypeTestResult(
                state = LinkTypeTestState.ERROR,
                serverAddress = node.server,
                message = "链路检测失败：${error.message ?: "请稍后重试"}"
            )
        }
        persistLinkTypeTestResult(node.id, result)
        return result
    }

    private fun resolveNodeServerAddresses(server: String): List<String> {
        val host = server.trim().removePrefix("[").removeSuffix("]")
        LinkIpAddress.normalize(host)?.let { return listOf(it) }
        val physicalNetwork = BoxVpnService.activePhysicalNetwork()
            ?: NetworkUtils.physicalNetwork(getApplication())
        val addresses = physicalNetwork?.getAllByName(host) ?: InetAddress.getAllByName(host)
        return addresses.mapNotNull { it.hostAddress }
    }

    private suspend fun runUnlockTestsForCandidate(
        node: Node,
        endpoint: io.github.HanJason233.chimera.core.LocalSocksEndpoint,
        definitions: List<UnlockTestDefinition>,
        requestSemaphore: Semaphore
    ): UnlockTestUiState {
        val results = UnlockTestRunner(endpoint).use { runner ->
            coroutineScope {
                definitions.map { definition ->
                    async {
                        requestSemaphore.withPermit {
                            try {
                                runner.run(definition)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                UnlockTestClassifier.error(definition, error)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val fullySupported = results.count { it.state == UnlockTestState.UNLOCKED }
        val state = UnlockTestUiState(
            items = results,
            isRunning = false,
            message = "检测完成：${fullySupported}/${results.size} 项完全支持"
        )
        persistUnlockTestResult(node.id, state)
        return state
    }

    private fun unlockTestFailureState(
        definitions: List<UnlockTestDefinition>,
        message: String,
        state: UnlockTestState
    ) = UnlockTestUiState(
        items = definitions.map { definition ->
            UnlockTestResult(
                definition = definition,
                state = state,
                statusText = if (state == UnlockTestState.NOT_CONNECTED) "未检测" else "检测失败",
                description = message
            )
        },
        message = message
    )

    private suspend fun persistLinkTypeTestResult(nodeId: String, result: LinkTypeTestResult) {
        runCatching {
            nodeDao().saveLinkTypeTestResult(nodeId, HomeTestResultCodec.encodeLinkType(result))
        }.onFailure { error -> Log.w(tag, "Failed to persist link type result", error) }
    }


    private suspend fun persistUnlockTestResult(nodeId: String, state: UnlockTestUiState) {
        if (state.isRunning || state.items.isEmpty()) return
        runCatching {
            nodeDao().saveUnlockTestResult(nodeId, HomeTestResultCodec.encodeUnlockTests(state))
        }.onFailure { error -> Log.w(tag, "Failed to persist IP test results", error) }
    }

    private companion object {
        const val PRE_VPN_TEST_SESSION_START_TIMEOUT_MS = 30_000L
    }

    /** Connect to the selected node, or to the first node in the active subscription. */
    fun startSelectedOrFirstConnection() {
        viewModelScope.launch {
            val selectedId = settingsRepository.selectedNodeId.first()
            val subscriptionNodes = currentNodeListSnapshot()
            val selectedNode = selectedId
                ?.let { nodeDao().getNodeById(it) }
                ?.takeIf { candidate -> subscriptionNodes.any { it.id == candidate.id } }
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

    fun setTcpingTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setTcpingTestTimeoutMs(value)
        }
    }

    fun setServiceRequestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setServiceRequestTimeoutMs(value)
        }
    }

    fun setRegionLookupMethods(methods: List<RegionLookupMethod>) {
        viewModelScope.launch { settingsRepository.setRegionLookupMethods(methods) }
    }

    fun setAutoRefreshOnStartup(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoRefreshOnStartup(sourceId, enabled)
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

    fun setBandwidthTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setBandwidthTestConcurrency(value)
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

    /**
     * 使用直连网络分别测试下行和上行带宽，并按 10 Mbps 档位向上取整后写入 Hy2 设置。
     */
    fun adaptHysteriaBandwidth() {
        if (_isHysteriaBandwidthAdapting.value) return
        _isHysteriaBandwidthAdapting.value = true
        val adaptiveJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val testJob = currentCoroutineContext()[Job]
            var service: SpeedTestService? = null
            _hysteriaBandwidthAdaptiveStatus.value = "正在测试直连网络上下行..."

            try {
                currentCoroutineContext().ensureActive()
                val testService = SpeedTestService(
                    durationMs = speedTestDurationMs.value,
                    downloadUrl = speedTestDownloadUrl.value
                )
                service = testService
                activeAdaptiveSpeedTestService = testService
                currentCoroutineContext().ensureActive()
                val downloadResult = testService.startDownloadTest { _, _ -> }
                currentCoroutineContext().ensureActive()
                val uploadResult = testService.startUploadTest { _, _ -> }
                currentCoroutineContext().ensureActive()
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
            } catch (cancelled: CancellationException) {
                _hysteriaBandwidthAdaptiveStatus.value = "自适应测试已中断"
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Hy2 bandwidth adaptation failed", e)
                _hysteriaBandwidthAdaptiveStatus.value = "自适应失败：${e.message ?: "测速不可用"}"
            } finally {
                if (activeAdaptiveSpeedTestService === service) {
                    activeAdaptiveSpeedTestService = null
                }
                if (hysteriaBandwidthAdaptiveJob === testJob) {
                    hysteriaBandwidthAdaptiveJob = null
                }
                _isHysteriaBandwidthAdapting.value = false
            }
        }
        hysteriaBandwidthAdaptiveJob = adaptiveJob
        adaptiveJob.start()
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

    fun updateSkinSettings(update: (SkinSettings) -> SkinSettings) {
        viewModelScope.launch {
            settingsRepository.updateSkinSettings(update)
        }
    }


    private suspend fun runUrlTestNodes(
        nodes: List<Node>,
        concurrency: Int,
        warmupTimeoutMs: Long,
        measurementTimeoutMs: Long,
        targetUrl: String,
        fallbackUrl: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        val batch = io.github.HanJason233.chimera.network.nodetest.latency.UrlTestManager.start(
            getApplication(), nodes, hysteriaUpMbps.value, hysteriaDownMbps.value,
            protocolCoreSettings.value
        ) ?: return emptyList()
        try {
            val testedResults = latencyTester.urlTestAllNodes(
                nodes = batch.nodes,
                targetUrl = targetUrl,
                fallbackUrl = fallbackUrl,
                warmupTimeoutMs = warmupTimeoutMs,
                measurementTimeoutMs = measurementTimeoutMs,
                concurrency = concurrency,
                onProgress = onProgress
            )
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
            withContext(NonCancellable) { io.github.HanJason233.chimera.network.nodetest.latency.UrlTestManager.stop() }
        }
    }

    private suspend fun applyUrlTestResults(
        results: List<LatencyTester.LatencyResult>,
        autoDeleteTimeoutNodes: Boolean,
        updateSortMode: Boolean = true
    ) {

        val updates = results.map { result ->
            NodeLatencyUpdate(result.nodeId, result.latency, result.isAvailable)
        }
        val updatedRows = nodeDao().updateUrlTestResults(updates, System.currentTimeMillis())
        if (updatedRows != updates.size) {
            Log.w(tag, "Only $updatedRows/${updates.size} URL test results were applied")
        }
        if (updateSortMode) {
            _nodeTestResultSortMode.value = NodeTestResultSortMode.URL_TEST_LATENCY
        }
        deleteTimedOutNodes(results, isTcping = false, enabled = autoDeleteTimeoutNodes)
    }

    private suspend fun deleteTimedOutNodes(
        results: List<LatencyTester.LatencyResult>,
        isTcping: Boolean,
        enabled: Boolean
    ) {
        if (!enabled) return

        deleteNodes(
            nodeIds = results.filter { it.latency == -2 }.map { it.nodeId },
            reason = if (isTcping) "TCPing 超时" else "URL Test 超时"
        )
    }

    private suspend fun deleteNodes(nodeIds: List<String>, reason: String) {
        val connectedNodeId = activeConnectionNode.value?.id
        val deletableNodeIds = nodeIds.distinct().filterNot { it == connectedNodeId }
        if (deletableNodeIds.isEmpty()) return

        val selectedNodeId = settingsRepository.selectedNodeId.first()
        val deletedCount = nodeDao().deleteNodesByIds(deletableNodeIds)
        if (deletedCount == 0) return
        if (selectedNodeId != null && selectedNodeId in deletableNodeIds) {
            settingsRepository.setSelectedNodeId(null)
        }
        _error.value = "已自动删除 $deletedCount 个${reason}节点"
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

    /** 供线路信息页根据最后完成的测试阶段排列节点。 */
    fun sortNodesByTestResult(
        list: List<Node>,
        mode: NodeTestResultSortMode
    ): List<Node> = NodeTestResultOrdering.sort(list, mode)

    /**
     * 内部测试逻辑 (Suspend)
     */
    private suspend fun internalTestNodes(
        currentNodes: List<Node>,
        concurrency: Int = tcpingConcurrency.value,
        timeoutMs: Long = tcpingTestTimeoutMs.value,
        autoDeleteTimeoutNodes: Boolean,
        updateSortMode: Boolean = true,
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
        if (updateSortMode) {
            _nodeTestResultSortMode.value = NodeTestResultSortMode.TCPING_LATENCY
        }
        deleteTimedOutNodes(results, isTcping = true, enabled = autoDeleteTimeoutNodes)
        return results
    }

    /** 统一选择节点组，并同步当前连接流程需要的筛选字段。 */
    fun selectNodeGroup(group: NodeGroupKey) {
        pendingSelectedNodeGroup.value = group
        viewModelScope.launch {
            persistSelectedNodeGroup(group)
        }
    }

    private suspend fun persistSelectedNodeGroup(group: NodeGroupKey) {
        try {
            settingsRepository.setSelectedNodeGroup(group)
        } finally {
            if (pendingSelectedNodeGroup.value == group) {
                pendingSelectedNodeGroup.value = null
            }
        }
    }

    /** 选择节点所属组并连接该节点。 */
    fun selectAndConnectNode(node: Node, group: NodeGroupKey) {
        pendingSelectedNodeGroup.value = group
        viewModelScope.launch {
            persistSelectedNodeGroup(group)
            connectNode(node)
        }
    }

    /** 选择主页智能测速使用的地区，不立即选取节点或发起 VPN 连接。 */
    fun selectCountry(countryCode: String) {
        selectNodeGroup(NodeGroupKey.Country(countryCode))
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
        selectNodeGroup(NodeGroupKey.Subscription(sourceId))
    }

    fun setWarehouseGroupingCountryCodes(countryCodes: Set<String>?) {
        viewModelScope.launch {
            settingsRepository.setWarehouseGroupingCountryCodes(countryCodes)
        }
    }

    fun setCountryGroupingSourceIds(sourceIds: Set<String>) {
        viewModelScope.launch {
            val allSourceIds = buildSet {
                subscriptionGroups.value.forEach {
                    add(NodeGroupKey.Subscription(it.sourceId).value)
                }
                userSubscriptionGroups.value.forEach { add(it.id) }
            }
            settingsRepository.setCountryGroupingSourceIds(
                sourceIds.takeUnless { it.containsAll(allSourceIds) }
            )
        }
    }

    fun createSubscriptionGroup(
        input: String?,
        onResult: (SubscriptionGroupCreateResult, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ) = subscriptionGroupCoordinator.createSubscriptionGroup(input, onResult)

    fun importNodeUrisIntoSubscriptionGroup(
        groupId: String,
        input: String,
        onResult: (SubscriptionGroupNodeImportResult, Int) -> Unit = { _, _ -> }
    ) = subscriptionGroupCoordinator.importNodeUrisIntoSubscriptionGroup(groupId, input, onResult)

    fun updateLocalGroupNode(
        groupId: String,
        nodeId: String,
        name: String,
        rawLink: String,
        onResult: (LocalGroupNodeEditResult) -> Unit = {}
    ) = subscriptionGroupCoordinator.updateLocalGroupNode(groupId, nodeId, name, rawLink, onResult)

    fun deleteLocalGroupNode(
        groupId: String,
        nodeId: String,
        onResult: (LocalGroupNodeDeleteResult) -> Unit = {}
    ) = subscriptionGroupCoordinator.deleteLocalGroupNode(groupId, nodeId, onResult)

    fun deleteSubscriptionNode(
        node: Node,
        onResult: (SubscriptionNodeDeleteResult) -> Unit = {}
    ) = subscriptionGroupCoordinator.deleteSubscriptionNode(node, onResult)

    fun updateSubscriptionGroup(
        groupId: String,
        name: String,
        input: String?,
        userAgentMode: PersonalSourceUserAgentMode,
        customUserAgent: String?,
        onResult: (SubscriptionGroupEditResult, Int) -> Unit = { _, _ -> }
    ) = subscriptionGroupCoordinator.updateSubscriptionGroup(
        groupId, name, input, userAgentMode, customUserAgent, onResult
    )

    fun refreshUserSubscriptionGroup(
        groupId: String,
        onResult: (SubscriptionGroupRefreshResult) -> Unit = {}
    ) = subscriptionGroupCoordinator.refreshUserSubscriptionGroup(groupId, onResult)

    fun deleteSubscriptionGroup(groupId: String, onResult: (Boolean) -> Unit = {}) =
        subscriptionGroupCoordinator.deleteSubscriptionGroup(groupId, onResult)

    fun updatePersonalSourceIcon(
        groupId: String,
        iconMode: String,
        customIconUrl: String?,
        localIconFile: String?,
        onResult: (Boolean) -> Unit = {}
    ) = subscriptionGroupCoordinator.updatePersonalSourceIcon(
        groupId, iconMode, customIconUrl, localIconFile, onResult
    )

    fun deleteOfficialSubscription(sourceId: String, onResult: (Boolean) -> Unit = {}) =
        subscriptionGroupCoordinator.deleteOfficialSubscription(sourceId, onResult)

    private suspend fun isNodeCoreConfigCompatible(node: Node): Boolean {
        return try {
            val backend = io.github.HanJason233.chimera.core.CoreBackends.registry.resolve(protocolCoreSettings.value.coreFor(node.type), node)
            backend.prepare(getApplication<Application>(), io.github.HanJason233.chimera.core.CoreRequest(node, 10808))
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

    fun setLanInboundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLanInboundEnabled(enabled)
            // 局域网入站由 VPN 核心配置创建，运行中修改后需要重启核心。
            restartVpnIfNeeded()
        }
    }

    fun setLanInboundDetails(port: Int, username: String, password: String) {
        viewModelScope.launch {
            settingsRepository.setLanInboundDetails(port, username, password)
            // 端口和认证信息属于核心启动配置，保存后立即应用到运行中的 VPN。
            restartVpnIfNeeded()
        }
    }

    fun setFailoverDetection(settings: FailoverDetectionSettings) {
        viewModelScope.launch { settingsRepository.setFailoverDetection(settings) }
    }

    fun setFailoverDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFailoverDetectionEnabled(enabled) }
    }

    fun setFailoverTransferStrategy(strategy: FailoverTransferStrategy?) {
        viewModelScope.launch { settingsRepository.setFailoverTransferStrategy(strategy) }
    }

    fun setHomeSpeedTestSettings(settings: SpeedTestSettings) {
        viewModelScope.launch { settingsRepository.setHomeSpeedTestSettings(settings) }
    }

    fun setGroupManualSpeedTestSettings(settings: SpeedTestSettings) {
        viewModelScope.launch { settingsRepository.setGroupManualSpeedTestSettings(settings) }
    }

    fun setLanInboundProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            val current = settingsRepository.lanInboundSettings.first()
            if (current.proxyMode == mode) return@launch
            settingsRepository.setLanInboundProxyMode(mode)
            if (current.enabled) restartVpnIfNeeded()
        }
    }

    /** 中断主页测速、分组手动测速和 Hy2 带宽自适应测试。 */
    fun forceCancelAllTests() {
        activeTestJob?.takeIf { it.isActive }?.cancel(
            CancellationException("Tests forcibly interrupted by user")
        )
        hysteriaBandwidthAdaptiveJob?.takeIf { it.isActive }?.cancel(
            CancellationException("Tests forcibly interrupted by user")
        )
        activeAdaptiveSpeedTestService?.cancel()
        latencyTester.cancelActiveTests()
        bandwidthTester.cancelActiveTests()
        Socks5HttpProbe.cancelActiveRequests()
        viewModelScope.launch(Dispatchers.IO) {
            io.github.HanJason233.chimera.network.nodetest.latency.UrlTestManager.stop()
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
    fun restartVpnIfNeeded(changedMode: ProxyMode? = null) {
        // 修改未运行的另一模式时不重启当前 VPN。
        if (changedMode != null && proxyMode.value != changedMode) return
        if (vpnState.value == VpnState.CONNECTED) {
            currentNode.value?.let { node ->
                Log.i(tag, "Settings changed, restarting VPN to apply...")
                Log.i(tag, "Restarting VPN after settings change")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    fun setIpFamilyEnabled(family: IpAddressFamily, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIpFamilyEnabled(family, enabled)
            restartVpnIfNeeded()
        }
    }

    fun setPreferredIpFamily(family: IpAddressFamily) {
        viewModelScope.launch {
            settingsRepository.setPreferredIpFamily(family)
            restartVpnIfNeeded()
        }
    }

    /** 通过当前 VPN 会话（如已连接）并发检测 IPv4 和 IPv6 出口。 */
    fun detectCurrentIpAddresses() {
        if (_ipAddressDetectionState.value.isChecking) return
        _ipAddressDetectionState.value = IpAddressDetectionState(isChecking = true)
        val settings = ipAddressTestSettings.value.normalized()
        viewModelScope.launch {
            val lookup = PublicIpAddressDetector.detect(
                socksEndpoint = BoxVpnService.activeSocksEndpoint(),
                settings = settings
            )
            _ipAddressDetectionState.value = IpAddressDetectionState(
                hasChecked = true,
                ipv4Results = lookup.ipv4Results,
                ipv6Results = lookup.ipv6Results
            )
        }
    }

    fun setIpAddressTestSettings(settings: IpAddressTestSettings) {
        viewModelScope.launch {
            settingsRepository.setIpAddressTestSettings(settings)
        }
    }

    fun setHomeModuleSettings(settings: HomeModuleSettings) {
        viewModelScope.launch {
            settingsRepository.setHomeModuleSettings(settings)
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearInfoDialogMessage() {
        _infoDialogMessage.value = null
    }

    fun updateNickname(nickname: String) = accountSession.updateNickname(nickname)

    fun activateVipCard(code: String) = accountSession.activateVipCard(code)

    fun syncCurrentAccount() = accountSession.syncCurrentAccount()

    fun syncAccountOnOverviewEntry() = accountSession.syncAccountOnOverviewEntry()

    fun refreshClientNotifications(force: Boolean = false) =
        accountSession.refreshClientNotifications(force)

    fun clearClientNotifications() = accountSession.clearClientNotifications()

    fun markClientNotificationRead(notificationId: Long) =
        accountSession.markClientNotificationRead(notificationId)

    fun markAllClientNotificationsRead() = accountSession.markAllClientNotificationsRead()

    fun bindEmailPassword(
        email: String,
        password: String,
        registrationCode: String = ""
    ) = accountSession.bindEmailPassword(email, password, registrationCode)

    fun loginAtStartup(email: String, password: String) =
        accountSession.loginAtStartup(email, password)

    fun registerAtStartup(email: String, password: String, registrationCode: String) =
        accountSession.registerAtStartup(email, password, registrationCode)

    fun continueAsGuestAtStartup() = accountSession.continueAsGuestAtStartup()

    fun continueOfflineAtStartup() = accountSession.continueOfflineAtStartup()

    fun exitOfflineMode() = accountSession.exitOfflineMode()

    fun clearAccountMessage() = accountSession.clearAccountMessage()

    fun switchAccount(email: String, password: String) =
        accountSession.switchAccount(email, password)

    fun deleteAccount(password: String, onDeleted: () -> Unit = {}) =
        accountSession.deleteAccount(password, onDeleted)

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

    private suspend fun loadStartupDataAfterAuthentication() {
        if (accountManager.isOfflineMode() || accountManager.currentAccount() == null) return
        try {
            val autoRefreshOverrides = settingsRepository.autoRefreshOnStartupOverrides.first()
            if (!initializeSubscriptionsWithRefreshState(forceRefresh = true)) {
                scheduleNodeLocationEnrichment(nodeDao().getAllNodes().first())
            }
            subscriptionGroupCoordinator.refreshUserSubscriptionGroupsOnStartup(autoRefreshOverrides)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(tag, "Post-authentication startup refresh failed", error)
        }
    }

    private suspend fun currentNodeListSnapshot(): List<Node> = withContext(Dispatchers.Default) {
        val selectedGroup = pendingSelectedNodeGroup.value
            ?: NodeGroupKey.parse(settingsRepository.selectedNodeGroupId.first())
            ?: run {
                val dao = nodeDao()
                val groups = dao.getSubscriptionGroups().first()
                val sourceId = SubscriptionGroupingStrategy.resolveActiveSourceId(
                    groups,
                    settingsRepository.selectedSubscriptionSourceId.first()
                )
                sourceId?.let { NodeGroupKey.Subscription(it) }
            }
        val snapshot = when (selectedGroup) {
            is NodeGroupKey.Country -> countryGroupingNodes.first().filter {
                it.getCountryCode() == selectedGroup.countryCode
            }
            is NodeGroupKey.Subscription -> nodeDao()
                .getSubscriptionNodesBySource(selectedGroup.sourceId)
                .first()
            is NodeGroupKey.Imported -> nodeDao().getNodesByGroup(selectedGroup.groupId).first()
            null -> nodeDao().getSubscriptionNodes().first()
        }
        sortNodesForList(
            WarehouseGroupingCountryResolver.filter(
                snapshot,
                warehouseGroupingCountryCodes.value.takeIf {
                    selectedGroup !is NodeGroupKey.Country
                }
            )
        )
    }

    fun setRemoteDns(value: String) {
        viewModelScope.launch {
            settingsRepository.setRemoteDns(value)
            restartVpnIfNeeded()
        }
    }

    fun setRemoteDnsStrategy(strategy: DnsDomainStrategy) {
        viewModelScope.launch {
            settingsRepository.setRemoteDnsStrategy(strategy)
            restartVpnIfNeeded()
        }
    }

    fun setDirectDns(value: String) {
        viewModelScope.launch {
            settingsRepository.setDirectDns(value)
            restartVpnIfNeeded()
        }
    }

    fun setDirectDnsStrategy(strategy: DnsDomainStrategy) {
        viewModelScope.launch {
            settingsRepository.setDirectDnsStrategy(strategy)
            restartVpnIfNeeded()
        }
    }

    fun setServerDnsStrategy(strategy: DnsDomainStrategy) {
        viewModelScope.launch {
            settingsRepository.setServerDnsStrategy(strategy)
            restartVpnIfNeeded()
        }
    }

    fun setDnsRoutingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDnsRoutingEnabled(enabled)
            restartVpnIfNeeded()
        }
    }

    fun setFakeDnsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFakeDnsEnabled(enabled)
            restartVpnIfNeeded()
        }
    }

    fun setSmartRoutingDomains(
        action: SmartRoutingRuleAction,
        domains: List<String>,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        viewModelScope.launch {
            settingsRepository.setSmartRoutingDomains(action, domains, mode)
            restartVpnIfNeeded(mode)
        }
    }

    fun saveSmartRoutingRule(rule: SmartRoutingRule, mode: ProxyMode = ProxyMode.SMART) {
        viewModelScope.launch {
            settingsRepository.saveSmartRoutingRule(rule, mode)
            restartVpnIfNeeded(mode)
        }
    }

    fun setSmartRoutingDomainStrategy(
        strategy: SmartRoutingDomainStrategy,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        viewModelScope.launch {
            settingsRepository.setSmartRoutingDomainStrategy(strategy, mode)
            restartVpnIfNeeded(mode)
        }
    }

    fun importSmartRoutingRules(
        json: String,
        mode: ProxyMode = ProxyMode.SMART,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val decoded = runCatching {
                withContext(Dispatchers.Default) { SmartRoutingRulesCodec.decode(json) }
            }.getOrNull()
            if (decoded == null) {
                onResult(false)
                return@launch
            }
            settingsRepository.replaceSmartRoutingRules(decoded, mode)
            restartVpnIfNeeded(mode)
            onResult(true)
        }
    }

    fun deleteSmartRoutingRule(ruleId: String, mode: ProxyMode = ProxyMode.SMART) {
        viewModelScope.launch {
            settingsRepository.deleteSmartRoutingRule(ruleId, mode)
            restartVpnIfNeeded(mode)
        }
    }

    fun setSmartRoutingRuleEnabled(
        ruleId: String,
        enabled: Boolean,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        viewModelScope.launch {
            settingsRepository.setSmartRoutingRuleEnabled(ruleId, enabled, mode)
            restartVpnIfNeeded(mode)
        }
    }

    fun moveSmartRoutingRule(
        fromRuleId: String,
        toRuleId: String,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        viewModelScope.launch {
            settingsRepository.moveSmartRoutingRule(fromRuleId, toRuleId, mode)
            restartVpnIfNeeded(mode)
        }
    }

    // 拖动结束时保存规则顺序，并按当前模式更新 VPN 配置。
    fun reorderSmartRoutingRules(orderedRuleIds: List<String>, mode: ProxyMode = ProxyMode.SMART) {
        viewModelScope.launch {
            settingsRepository.reorderSmartRoutingRules(orderedRuleIds, mode)
            restartVpnIfNeeded(mode)
        }
    }

    /** Refresh all subscriptions when no source is specified, otherwise only the selected source. */
    fun refreshNodes(sourceId: String? = null) {
        refreshSubscriptions(sourceId, onResult = null)
    }

    /** 刷新单个官方订阅源，并将结果返回给分组界面。 */
    fun refreshOfficialSubscription(sourceId: String, onResult: (Boolean) -> Unit) {
        refreshSubscriptions(sourceId, onResult)
    }

    private fun refreshSubscriptions(
        sourceId: String?,
        onResult: ((Boolean) -> Unit)?
    ) {
        val pendingSourceId = sourceId?.takeIf(String::isNotBlank)
        if (pendingSourceId != null) {
            _pendingOfficialSourceRefreshCounts.update { counts ->
                counts + (pendingSourceId to ((counts[pendingSourceId] ?: 0) + 1))
            }
        }
        viewModelScope.launch {
            try {
                if (GlobalTestExecution.isFetching()) {
                    _error.value = GlobalTestExecution.fetchingHint()
                    onResult?.invoke(false)
                    return@launch
                }
                if (_isTesting.value || GlobalTestExecution.mutex.isLocked) {
                    _error.value = GlobalTestExecution.busyHint()
                    onResult?.invoke(false)
                    return@launch
                }
                val fetchOk = initializeSubscriptionsWithRefreshState(
                    forceRefresh = true,
                    sourceId = sourceId,
                    forceSourceRefresh = true
                )
                onResult?.invoke(fetchOk)
            } finally {
                if (pendingSourceId != null) {
                    _pendingOfficialSourceRefreshCounts.update { counts ->
                        val count = counts[pendingSourceId] ?: return@update counts
                        if (count <= 1) {
                            counts - pendingSourceId
                        } else {
                            counts + (pendingSourceId to count - 1)
                        }
                    }
                }
            }
        }
    }

    /** 统一执行官方源初始化与刷新流程。 */
    private suspend fun initializeSubscriptionsWithRefreshState(
        forceRefresh: Boolean,
        sourceId: String? = null,
        forceSourceRefresh: Boolean = false
    ): Boolean {
        if (accountManager.isOfflineMode()) {
            _error.value = "离线模式下不会连接 Chimera 后台刷新线路"
            return false
        }
        return subscriptionDataLoader.initialize(
            forceRefresh = forceRefresh,
            sourceId = sourceId,
            forceSourceRefresh = forceSourceRefresh
        )
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
