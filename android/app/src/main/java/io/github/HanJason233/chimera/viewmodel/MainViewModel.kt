package io.github.HanJason233.chimera.viewmodel

import io.github.HanJason233.chimera.feature.nodetest.NodeTestCoordinator
import io.github.HanJason233.chimera.feature.nodetest.NodeTestExecutor
import io.github.HanJason233.chimera.feature.nodetest.data.HomeTestResultCodec
import io.github.HanJason233.chimera.feature.nodetest.model.SpeedTestProgress
import io.github.HanJason233.chimera.feature.nodetest.model.UnlockTestUiState

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.data.local.AppDatabase
import io.github.HanJason233.chimera.data.local.HomeNodeTestResultsEntity
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
import io.github.HanJason233.chimera.feature.nodegrouping.domain.SubscriptionGroupingStrategy
import io.github.HanJason233.chimera.feature.nodegrouping.domain.WarehouseGroupingCountryResolver
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import io.github.HanJason233.chimera.network.nodetest.latency.LatencyTester
import io.github.HanJason233.chimera.network.nodetest.bandwidth.NodeDownloadBandwidthTester
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe
import io.github.HanJason233.chimera.network.nodetest.bandwidth.SpeedTestService
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestResult
import io.github.HanJason233.chimera.network.location.NodeLocationResolver
import io.github.HanJason233.chimera.network.account.AccountManager
import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.network.ip.PublicIpAddressDetector
import io.github.HanJason233.chimera.service.ServiceManager
import io.github.HanJason233.chimera.service.BoxVpnService
import io.github.HanJason233.chimera.util.NetworkUtils
import io.github.HanJason233.chimera.util.compareAppVersions
import io.github.HanJason233.chimera.util.DatabasePassphraseManager
import java.io.File
import kotlin.math.ceil

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
    /** 仅记录已取得并发执行名额的节点，排队节点不显示测试中。 */
    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    internal val testingNodeIds = _testingNodeIds.asStateFlow()

    private fun updateNodeTestingState(nodeId: String, testing: Boolean) {
        _testingNodeIds.update { ids -> if (testing) ids + nodeId else ids - nodeId }
    }
    /** 保存当前持有全局测速锁的任务，供首页测试卡片中断。 */
    private var hysteriaBandwidthAdaptiveJob: Job? = null
    @Volatile
    private var activeAdaptiveSpeedTestService: SpeedTestService? = null

    private val _speedTestProgress = MutableStateFlow<SpeedTestProgress?>(null)
    internal val speedTestProgress = _speedTestProgress.asStateFlow()

    private val _unlockTestState = MutableStateFlow(UnlockTestUiState())
    internal val unlockTestState = _unlockTestState.asStateFlow()

    private val _linkTypeTestResult = MutableStateFlow(LinkTypeTestResult())
    internal val linkTypeTestResult = _linkTypeTestResult.asStateFlow()

    /** 手动排序时使用的最近一次延迟或带宽测试指标。 */
    private val _lastTestResultSortMode = MutableStateFlow(
        NodeTestResultSortMode.TCPING_LATENCY
    )
    val lastTestResultSortMode = _lastTestResultSortMode.asStateFlow()

    /** 持久化节点列表当前测试项和本轮起始时间，进程重启后继续展示对应结果。 */
    internal val nodeTestResultDisplaySelection =
        settingsRepository.nodeTestResultDisplaySelection.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NodeTestResultDisplaySelection()
        )

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
    private val nodeTestExecutor = NodeTestExecutor(
        applicationContext = application.applicationContext,
        nodeDao = ::nodeDao,
        settingsRepository = settingsRepository,
        activeConnectionNode = activeConnectionNode,
        latencyTester = latencyTester,
        onNodeTestingChanged = ::updateNodeTestingState,
        tcpingConcurrency = tcpingConcurrency,
        tcpingTestTimeoutMs = tcpingTestTimeoutMs,
        hysteriaUpMbps = hysteriaUpMbps,
        hysteriaDownMbps = hysteriaDownMbps,
        protocolCoreSettings = protocolCoreSettings,
        _lastTestResultSortMode = _lastTestResultSortMode,
        _error = _error
    )
    private val nodeTestCoordinator = NodeTestCoordinator(
        applicationContext = application.applicationContext,
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        nodeDao = ::nodeDao,
        currentNodeListSnapshot = ::currentNodeListSnapshot,
        activeConnectionNode = activeConnectionNode,
        homeSpeedTestSettings = homeSpeedTestSettings,
        groupManualSpeedTestSettings = groupManualSpeedTestSettings,
        protocolCoreSettings = protocolCoreSettings,
        proxyMode = proxyMode,
        hysteriaUpMbps = hysteriaUpMbps,
        hysteriaDownMbps = hysteriaDownMbps,
        startSelectedOrFirstConnection = ::startSelectedOrFirstConnection,
        bandwidthTest = ::testNodeDownloadBandwidthMbps,
        executor = nodeTestExecutor,
        onNodeTestingChanged = ::updateNodeTestingState,
        _isTesting = _isTesting,
        _speedTestProgress = _speedTestProgress,
        _unlockTestState = _unlockTestState,
        _linkTypeTestResult = _linkTypeTestResult,
        _lastTestResultSortMode = _lastTestResultSortMode,
        _error = _error
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

    fun startCurrentNodeDataTest(displayStages: List<SpeedTestStage>) =
        nodeTestCoordinator.startCurrentNodeDataTest(displayStages)

    fun startHomeSpeedTest() = nodeTestCoordinator.startHomeSpeedTest()

    fun clearNodeTestResults(
        groupNodes: List<Node>,
        onComplete: (String, Boolean) -> Unit = { _, _ -> }
    ) = nodeTestCoordinator.clearNodeTestResults(groupNodes, onComplete)

    fun runManualGroupTest(groupNodes: List<Node>, stage: ManualGroupTestStage) {
        val selection = NodeTestResultDisplaySelection(
            stage = stage,
            runStartedAt = System.currentTimeMillis(),
            testedNodeIds = groupNodes.mapTo(mutableSetOf(), Node::id)
        )
        viewModelScope.launch {
            val previousSelection = settingsRepository.nodeTestResultDisplaySelection.first()
            nodeDao().initializeMissingLastTestStages(previousSelection.stage.name)
            settingsRepository.setNodeTestResultDisplaySelection(selection)
            nodeTestCoordinator.runManualGroupTest(groupNodes, stage)
        }
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


    private fun sortNodesForList(
        list: List<Node>,
        pinnedNodeId: String? = null
    ): List<Node> {
        return list.sortedWith(
            compareByDescending<Node> { it.id == pinnedNodeId }
                .thenBy { it.sortOrder }
        )
    }

    /** 供线路信息页根据最后完成的测试阶段排列节点。 */
    fun sortNodesByTestResult(
        list: List<Node>,
        mode: NodeTestResultSortMode
    ): List<Node> = NodeTestResultOrdering.sort(list, mode)

    /** 仅在用户从节点列表手动触发时，按测试结果保存节点顺序。 */
    fun sortNodesByTestResults(
        nodes: List<Node>,
        mode: NodeTestResultSortMode,
        groupId: String? = null
    ) {
        viewModelScope.launch {
            val dao = nodeDao()
            val nodesToSort = if (groupId == null) {
                nodes
            } else {
                dao.getNodesByGroup(groupId).first()
            }
            val orderedNodeIds = sortNodesByTestResult(nodesToSort, mode).map(Node::id)
            if (orderedNodeIds.isEmpty()) return@launch
            try {
                val reordered = withContext(Dispatchers.IO) {
                    dao.reorderNodesByTestResults(orderedNodeIds, groupId)
                }
                if (!reordered) {
                    _error.value = "节点列表已更新，请重试排序"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MainViewModel", "Failed to sort nodes by test results", error)
                _error.value = "按测试结果排序失败：${error.message ?: "未知错误"}"
            }
        }
    }

    /**
     * 内部测试逻辑 (Suspend)
     */
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
        nodeTestCoordinator.cancelActiveTest()
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

    suspend fun checkForAppUpdate(): Result<AppUpdateCheckResult> = withContext(Dispatchers.IO) {
        try {
            check(AppConfig.BACKEND_URL.isNotBlank()) { "backend_url_missing" }
            val response = withTimeout(20_000L) {
                NetworkClient.apiService.getAppUpdateInfo(
                    "${AppConfig.BACKEND_URL.trimEnd('/')}/api/app/update"
                )
            }
            val latestVersion = response.latestAppVersion.trim()
            val downloadUrl = response.appDownloadUrl.trim()
            check(latestVersion.isNotBlank() && downloadUrl.isNotBlank()) {
                "app_update_not_configured"
            }
            val comparison = compareAppVersions(latestVersion, BuildConfig.VERSION_NAME)
                ?: throw IllegalStateException("invalid_app_version")
            Result.success(
                AppUpdateCheckResult(
                    currentVersion = BuildConfig.VERSION_NAME,
                    latestVersion = latestVersion,
                    downloadUrl = downloadUrl,
                    updateAvailable = comparison > 0
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun refreshClientNotifications(force: Boolean = false) =
        accountSession.refreshClientNotifications(force)

    fun clearClientNotifications() = accountSession.clearClientNotifications()

    fun markClientNotificationRead(notificationId: Long) =
        accountSession.markClientNotificationRead(notificationId)

    fun markAllClientNotificationsRead() = accountSession.markAllClientNotificationsRead()

    fun loginAtStartup(email: String, password: String) =
        accountSession.loginAtStartup(email, password)

    fun registerAtStartup(email: String, password: String, registrationCode: String) =
        accountSession.registerAtStartup(email, password, registrationCode)

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
