package io.github.HanJason233.chimera.viewmodel
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.sync.withPermit
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.data.local.AppDatabase
import io.github.HanJason233.chimera.data.local.NodeLatencyUpdate
import io.github.HanJason233.chimera.data.local.NodeLocationUpdate
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.SettingsRepository
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
import io.github.HanJason233.chimera.network.nodetest.bandwidth.SpeedTestService
import io.github.HanJason233.chimera.network.subscription.SubscriptionParser
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.PersonalSourceIconStore
import io.github.HanJason233.chimera.network.subscription.importer.LocalSubscriptionContent
import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionImportInput
import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionInputDetector
import io.github.HanJason233.chimera.network.subscription.ParsedSubscription
import io.github.HanJason233.chimera.network.download.DownloadManager
import io.github.HanJason233.chimera.network.download.DownloadArtifact
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
import io.github.HanJason233.chimera.service.ServiceManager
import io.github.HanJason233.chimera.service.BoxVpnService
import io.github.HanJason233.chimera.util.NetworkUtils
import io.github.HanJason233.chimera.util.SingBoxConfigGenerator
import io.github.HanJason233.chimera.util.XrayConfigGenerator
import io.github.HanJason233.chimera.util.DatabasePassphraseManager
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.ceil
import java.util.UUID

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

    private data class ParsedSubscriptionPart(
        val url: String?,
        val subscription: ParsedSubscription,
        val groupLabel: String? = null
    )

    private suspend fun fetchRemoteSubscriptionResults(
        urls: List<String>,
        userAgentMode: PersonalSourceUserAgentMode = PersonalSourceUserAgentMode.AUTO,
        customUserAgent: String? = null
    ): List<Result<ParsedSubscription>> = coroutineScope {
        urls.map { url ->
            async(Dispatchers.IO) {
                subscriptionParser.fetchAndParseWithMetadata(
                    url = url,
                    userAgentMode = userAgentMode,
                    customUserAgent = customUserAgent
                )
            }
        }.awaitAll()
    }

    private suspend fun fetchRemoteSubscriptions(
        urls: List<String>,
        userAgentMode: PersonalSourceUserAgentMode = PersonalSourceUserAgentMode.AUTO,
        customUserAgent: String? = null
    ): Result<List<ParsedSubscription>> {
        val results = fetchRemoteSubscriptionResults(urls, userAgentMode, customUserAgent)
        val failure = results.firstOrNull { it.isFailure }
        return if (failure != null) {
            Result.failure(
                failure.exceptionOrNull()
                    ?: IllegalStateException("Failed to fetch remote subscription")
            )
        } else {
            Result.success(results.map { it.getOrThrow() })
        }
    }

    private suspend fun parseFullSubscriptionContent(content: LocalSubscriptionContent): ParsedSubscription =
        withContext(Dispatchers.Default) {
            ParsedSubscription(subscriptionParser.parseSubscription(content))
        }

    private suspend fun parseSubscriptionInput(
        input: SubscriptionImportInput,
        userAgentMode: PersonalSourceUserAgentMode = PersonalSourceUserAgentMode.AUTO,
        customUserAgent: String? = null
    ): Result<List<ParsedSubscriptionPart>> {
        return when (input) {
            is SubscriptionImportInput.RemoteSubscription -> {
                fetchRemoteSubscriptions(listOf(input.url), userAgentMode, customUserAgent).map { parsed ->
                    listOf(ParsedSubscriptionPart(input.url, parsed.single()))
                }
            }

            is SubscriptionImportInput.MultipleRemoteSubscriptions -> {
                fetchRemoteSubscriptions(input.urls, userAgentMode, customUserAgent).map { parsed ->
                    input.urls.mapIndexed { index, url ->
                        ParsedSubscriptionPart(url, parsed[index])
                    }
                }
            }

            is SubscriptionImportInput.MixedSubscription -> {
                fetchRemoteSubscriptions(input.urls, userAgentMode, customUserAgent).map { parsed ->
                    buildList {
                        add(
                            ParsedSubscriptionPart(
                                url = null,
                                subscription = parseFullSubscriptionContent(input.content),
                                groupLabel = "导入内容"
                            )
                        )
                        input.urls.forEachIndexed { index, url ->
                            add(
                                ParsedSubscriptionPart(
                                    url = url,
                                    subscription = parsed[index]
                                )
                            )
                        }
                    }
                }
            }

            is SubscriptionImportInput.FullContent -> if (input.content.isBlank()) {
                Result.success(emptyList())
            } else {
                Result.success(
                    listOf(
                        ParsedSubscriptionPart(
                            url = null,
                            subscription = parseFullSubscriptionContent(input.content)
                        )
                    )
                )
            }
        }
    }
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
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    val backendSettings = subscriptionDataLoader.backendSettings

    val accountInfo = accountManager.accountInfo
    private val _isActivatingVip = MutableStateFlow(false)
    val isActivatingVip = _isActivatingVip.asStateFlow()
    private val _isUpdatingNickname = MutableStateFlow(false)

    val isUpdatingNickname = _isUpdatingNickname.asStateFlow()
    private val _isSyncingAccount = MutableStateFlow(false)
    val isSyncingAccount = _isSyncingAccount.asStateFlow()
    private val _isStartupAccountActionInProgress = MutableStateFlow(false)
    val isStartupAccountActionInProgress = _isStartupAccountActionInProgress.asStateFlow()
    private val _startupAccountAuthenticated = MutableStateFlow(accountManager.currentAccount() != null)
    val startupAccountAuthenticated = _startupAccountAuthenticated.asStateFlow()
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

    val autoRefreshOnStartupOverrides = settingsRepository.autoRefreshOnStartupOverrides
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, NodeGroupingMode.REGION)

    /** 所有节点组共用的当前选中标识，仅由统一节点组字段决定。 */
    val selectedNodeGroupId = combine(
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
    val countryGroupingNodes = nodeDaoFlow.flatMapLatest { dao ->
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

    /** 当前选中私人源的节点；没有选中私人源时不参与主页列表。 */
    private val importedNodes = selectedNodeGroupId.flatMapLatest { groupId ->
        val group = NodeGroupKey.parse(groupId) as? NodeGroupKey.Imported
        if (group == null) {
            flowOf(emptyList())
        } else {
            nodeDaoFlow.flatMapLatest { it.getNodesByGroup(group.groupId) }
        }
    }

    // Data
    private val visibleNodes = combine(
        subscriptionNodes,
        importedNodes,
        countryGroupingNodes,
        selectedNodeGroupId
    ) { subscription, imported, warehouseNodes, groupId ->
        when (val group = NodeGroupKey.parse(groupId)) {
            is NodeGroupKey.Imported -> imported
            is NodeGroupKey.Country -> warehouseNodes.filter {
                it.getCountryCode() == group.countryCode
            }
            else -> subscription
        }
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

    val lanInboundSettings = settingsRepository.lanInboundSettings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LanInboundSettings()
    )

    val speedTestSchemes = settingsRepository.speedTestSchemes.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        listOf(SpeedTestScheme.default())
    )

    val homeClickSpeedTestSchemeId = settingsRepository.homeClickSpeedTestSchemeId.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SpeedTestScheme.DEFAULT_ID
    )

    val homeLongPressSpeedTestSchemeId = settingsRepository.homeLongPressSpeedTestSchemeId.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SpeedTestScheme.DEFAULT_ID
    )

    val groupManualSpeedTestSchemeId = settingsRepository.groupManualSpeedTestSchemeId.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SpeedTestScheme.DEFAULT_ID
    )

    val groupManualSpeedTestEnabled = settingsRepository.groupManualSpeedTestEnabled.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true
    )

    val homeClickSpeedTestEnabled = settingsRepository.homeClickSpeedTestEnabled.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false
    )

    val homeLongPressSpeedTestEnabled = settingsRepository.homeLongPressSpeedTestEnabled.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true
    )

    val ipv6RoutingMode = settingsRepository.ipv6RoutingMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IPv6RoutingMode.DISABLED
    )

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
    
    // 自动化测试设置
    val serviceRequestTimeoutMs = settingsRepository.serviceRequestTimeoutMs.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SettingsRepository.DEFAULT_SERVICE_REQUEST_TIMEOUT_MS
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
    val disconnectRecoveryEnabled = settingsRepository.disconnectRecoveryEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.DISCONNECT_RECOVERY_ENABLED)
    val updateDownloadDirectoryUri = settingsRepository.updateDownloadDirectoryUri.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null
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
        // 链路类型结果属于节点的历史检测数据，VPN 断开后仍保留并在首页展示。
        viewModelScope.launch {
            try {
                // Keep the cached selection available immediately; refreshing
                // data should not blank and rewrite DataStore on every launch.
                _isInitialized.value = true

                // 无本地账户时只读缓存；已有账户直接恢复订阅数据，不再要求重新选择登录方式。

                subscriptionDataLoader.loadCachedSubscriptionList()
                if (_startupAccountAuthenticated.value) {
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

    /** 按用户选择的测速方案筛选节点并连接测速结果最好的节点。 */
    fun startSpeedTestScheme(schemeId: String) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            val scheme = speedTestSchemes.value.firstOrNull { it.id == schemeId }
                ?: speedTestSchemes.value.firstOrNull { it.id == SpeedTestScheme.DEFAULT_ID }
                ?: run {
                    _error.value = "测速方案不存在"
                    return@launch
                }
            val shouldRunUnlockTests =
                scheme.unlockTestsEnabled && scheme.unlockTestIds.isNotEmpty()
            val hasSelectionTests = scheme.enabledQualityStages.isNotEmpty()
            if (!hasSelectionTests && !scheme.linkTypeTestEnabled && !shouldRunUnlockTests
            ) {
                // 所有检测均关闭时按普通连接处理。
                startSelectedOrFirstConnection()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("测速方案：${scheme.name}")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            val testJob = currentCoroutineContext()[Job]
            activeTestJob = testJob
            _isTesting.value = true
            val progressTitle = "测速方案：${scheme.name}"
            updateSpeedTestProgress(progressTitle, "准备测试")
            try {
                // 只启用连接后检测时复用当前连接；不遍历分组，也不切换到组内首个节点。
                if (!hasSelectionTests) {
                    val node = ServiceManager.currentNode.value
                        ?.takeIf { vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING }
                        ?: run {
                            val nodes = currentNodeListSnapshot()
                            val selectedId = settingsRepository.selectedNodeId.first()
                            nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()
                        }
                    if (node == null) {
                        _error.value = "没有可用节点"
                        return@launch
                    }
                    if (vpnState.value != VpnState.CONNECTED && vpnState.value != VpnState.CONNECTING) {
                        settingsRepository.setSelectedNodeId(node.id)
                        ServiceManager.startVpn(getApplication(), node, proxyMode.value)
                    }
                    runPostConnectionTests(scheme, node, progressTitle)
                    return@launch
                }
                val allNodes = currentNodeListSnapshot()
                if (allNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }

                val nodesById = allNodes.associateBy { it.id }
                val stageResults = mutableMapOf<SpeedTestStage, List<Node>>()

                fun stageCandidates(stage: SpeedTestStage) = SpeedTestCandidateSelector.candidatesForStage(
                    scheme = scheme, stage = stage, currentGroupCandidates = allNodes,
                    previousResults = stageResults
                )

                // 阶段顺序固定为 1 -> 2 -> 3；关闭任一阶段时跳过，但不改变后续顺序。
                if (scheme.tcpingEnabled) {
                    val tcpingNodes = stageCandidates(SpeedTestStage.TCPING)
                    updateSpeedTestProgress(
                        title = progressTitle,
                        stage = "TCPing",
                        total = tcpingNodes.size
                    )
                    val tcpingResults = internalTestNodes(
                        currentNodes = tcpingNodes,
                        concurrency = SpeedTestCandidateSelector.concurrencyForStage(
                            scheme,
                            SpeedTestStage.TCPING,
                            tcpingNodes.size
                        ),
                        timeoutMs = scheme.tcpingTimeoutMs,
                        autoDeleteTimeoutNodes = scheme.tcpingAutoDeleteTimeoutNodes,
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
                        scheme, SpeedTestStage.TCPING, candidates
                    )
                    stageResults[SpeedTestStage.TCPING] = retainedResults
                    if (retainedResults.isEmpty()) {
                        _error.value = "${scheme.name}：TCPing 未找到可用节点"
                        return@launch
                    }
                }

                if (scheme.urlTestEnabled) {
                    val urlTestNodes = stageCandidates(SpeedTestStage.URL_TEST)
                    updateSpeedTestProgress(
                        title = progressTitle,
                        stage = "URL Test",
                        total = urlTestNodes.size
                    )
                    val urlTestResults = runUrlTestNodes(
                        nodes = urlTestNodes,
                        concurrency = SpeedTestCandidateSelector.concurrencyForStage(
                            scheme,
                            SpeedTestStage.URL_TEST,
                            urlTestNodes.size
                        ),
                        warmupTimeoutMs = scheme.urlTestWarmupTimeoutMs,
                        measurementTimeoutMs = scheme.urlTestMeasurementTimeoutMs,
                        targetUrl = scheme.urlTestUrl,
                        fallbackUrl = scheme.urlTestBackupUrl,
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
                        autoDeleteTimeoutNodes = scheme.urlTestAutoDeleteTimeoutNodes
                    )
                    val candidates = urlTestResults
                        .filter { it.isAvailable && it.latency > 0 }
                        .sortedBy { it.latency }
                        .mapNotNull { nodesById[it.nodeId] }
                    val retainedResults = SpeedTestCandidateSelector.retainResultsForStage(
                        scheme, SpeedTestStage.URL_TEST, candidates
                    )
                    stageResults[SpeedTestStage.URL_TEST] = retainedResults
                    if (retainedResults.isEmpty()) {
                        _error.value = "${scheme.name}：URL Test 未找到可用节点"
                        return@launch
                    }
                }

                val bestNode = if (scheme.bandwidthEnabled) {
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
                                scheme,
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
                                            durationMs = scheme.bandwidthTestDurationMs,
                                            downloadUrl = scheme.bandwidthTestUrl
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
                    val deleteLowBandwidth = scheme.bandwidthAutoDeleteLowSpeedNodes
                    val bandwidthThreshold = scheme.bandwidthAutoDeleteThresholdMbps
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
                    SpeedTestCandidateSelector.retainResultsForStage(
                        scheme,
                        SpeedTestStage.BANDWIDTH,
                        orderedSpeedResults
                    ).firstOrNull()?.first
                } else {
                    stageResults[scheme.enabledQualityStages.last()]?.firstOrNull()
                }
                if (bestNode == null) {
                    _error.value = if (scheme.bandwidthEnabled && scheme.bandwidthAutoDeleteLowSpeedNodes) {
                        "没有节点达到带宽阈值（${scheme.bandwidthAutoDeleteThresholdMbps} Mbps）"
                    } else {
                        "测速未找到可连接节点"
                    }
                    return@launch
                }

                settingsRepository.setSelectedNodeId(bestNode.id)
                Log.i(tag, "Speed test scheme selected node: ${bestNode.getDisplayName()}")
                ServiceManager.startVpn(getApplication(), bestNode, proxyMode.value)
                runPostConnectionTests(scheme, bestNode, progressTitle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Speed test scheme failed", e)
                _error.value = "测速失败：${e.message ?: "未知错误"}"
            } finally {
                if (activeTestJob === testJob) activeTestJob = null
                _isTesting.value = false
                _speedTestProgress.value = null
                GlobalTestExecution.finish()
            }
        }
    }

    /** 按配置方案的单阶段参数手动测试当前线路详情页中的节点。 */
    fun runManualGroupTest(groupNodes: List<Node>, stage: ManualGroupTestStage) {
        viewModelScope.launch {
            if (!groupManualSpeedTestEnabled.value) {
                _error.value = "分组内手动测试已关闭"
                return@launch
            }
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            val scheme = speedTestSchemes.value.firstOrNull {
                it.id == groupManualSpeedTestSchemeId.value
            } ?: speedTestSchemes.value.firstOrNull { it.id == SpeedTestScheme.DEFAULT_ID }
            if (scheme == null) {
                _error.value = "测速方案不存在"
                return@launch
            }

            val stageEnabled = when (stage) {
                ManualGroupTestStage.TCPING -> scheme.tcpingEnabled
                ManualGroupTestStage.URL_TEST -> scheme.urlTestEnabled
                ManualGroupTestStage.BANDWIDTH -> scheme.bandwidthEnabled
            }
            if (!stageEnabled) {
                _error.value = "所选方案未启用该测试阶段"
                return@launch
            }
            if (!GlobalTestExecution.tryStart("分组内手动测试：$stage")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            val testJob = currentCoroutineContext()[Job]
            activeTestJob = testJob
            _isTesting.value = true
            val progressTitle = "分组手动测速"
            val progressStage = when (stage) {
                ManualGroupTestStage.TCPING -> "TCPing"
                ManualGroupTestStage.URL_TEST -> "URL Test"
                ManualGroupTestStage.BANDWIDTH -> "带宽测速"
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
                                scheme,
                                SpeedTestStage.TCPING,
                                candidates.size
                            ),
                            timeoutMs = scheme.tcpingTimeoutMs,
                            autoDeleteTimeoutNodes = scheme.tcpingAutoDeleteTimeoutNodes,
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
                                scheme,
                                SpeedTestStage.URL_TEST,
                                candidates.size
                            ),
                            warmupTimeoutMs = scheme.urlTestWarmupTimeoutMs,
                            measurementTimeoutMs = scheme.urlTestMeasurementTimeoutMs,
                            targetUrl = scheme.urlTestUrl,
                            fallbackUrl = scheme.urlTestBackupUrl,
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
                            autoDeleteTimeoutNodes = scheme.urlTestAutoDeleteTimeoutNodes
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
                                    scheme,
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
                                                durationMs = scheme.bandwidthTestDurationMs,
                                                downloadUrl = scheme.bandwidthTestUrl
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
                        if (scheme.bandwidthAutoDeleteLowSpeedNodes) {
                            val threshold = scheme.bandwidthAutoDeleteThresholdMbps
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

    /** 第四、第五项按顺序执行，始终绑定方案最终连接的节点。 */
    private suspend fun runPostConnectionTests(scheme: SpeedTestScheme, node: Node, title: String) {
        if (scheme.linkTypeTestEnabled) {
            updateSpeedTestProgress(title, "4. 链路类型检测")
            runLinkTypeTest(node)
        }
        if (scheme.unlockTestsEnabled && scheme.unlockTestIds.isNotEmpty()) {
            runUnlockTests(scheme.unlockTestIds, node)
        }
    }

    private suspend fun runLinkTypeTest(node: Node) {
        _linkTypeTestResult.value = LinkTypeTestResult(
            state = LinkTypeTestState.TESTING,
            serverAddress = node.server,
            message = "等待 VPN 节点连接"
        )
        val finalState = withTimeoutOrNull(90_000L) {
            ServiceManager.vpnState.first { it == VpnState.CONNECTED || it == VpnState.DISCONNECTED }
        }
        val endpoint = BoxVpnService.activeSocksEndpoint()
        if (finalState != VpnState.CONNECTED || endpoint == null || !isCurrentTestNode(node)) {
            val result = LinkTypeTestResult(
                state = LinkTypeTestState.NOT_CONNECTED,
                serverAddress = node.server,
                message = if (finalState == null) "等待 VPN 连接超时" else "当前节点未连接或连接已切换"
            )
            _linkTypeTestResult.value = result
            persistLinkTypeTestResult(node.id, result)
            return
        }
        _linkTypeTestResult.update { it.copy(message = "正在检测中转点与落地点") }
        val result = try {
            LinkTypeTestRunner(endpoint, resolveServer = { server ->
                val host = server.trim().removePrefix("[").removeSuffix("]")
                LinkIpAddress.normalize(host)?.let { listOf(it) }
                    ?: checkNotNull(BoxVpnService.activePhysicalNetwork()) { "当前物理网络不可用" }
                        .getAllByName(host).mapNotNull { it.hostAddress }
            }).run(node.server)
        } catch (cancelled: CancellationException) {
            _linkTypeTestResult.update { current ->
                if (current.state == LinkTypeTestState.TESTING) {
                    current.copy(state = LinkTypeTestState.IDLE, message = "检测已中断")
                } else {
                    current
                }
            }
            throw cancelled
        } catch (_: Exception) {
            LinkTypeTestResult(LinkTypeTestState.ERROR, node.server, message = "链路检测失败，请稍后重试")
        }
        // A result from a replaced session must not be shown as the new node's result.
        if (!isCurrentTestNode(node) || BoxVpnService.activeSocksEndpoint() != endpoint) {
            _linkTypeTestResult.value = nodeDao().getHomeNodeTestResults(node.id)
                ?.linkTypeResultJson
                ?.takeIf(String::isNotBlank)
                ?.let(HomeTestResultCodec::decodeLinkType)
                ?: LinkTypeTestResult()
            return
        }
        _linkTypeTestResult.value = result
        persistLinkTypeTestResult(node.id, result)
    }

    private suspend fun persistLinkTypeTestResult(nodeId: String, result: LinkTypeTestResult) {
        runCatching {
            nodeDao().saveLinkTypeTestResult(nodeId, HomeTestResultCodec.encodeLinkType(result))
        }.onFailure { error -> Log.w(tag, "Failed to persist link type result", error) }
    }

    private fun isCurrentTestNode(node: Node): Boolean =
        ServiceManager.vpnState.value == VpnState.CONNECTED &&
            ServiceManager.currentNode.value?.getRawLinkPlain() == node.getRawLinkPlain()

    /** 等待方案选出的节点连通，再运行用户勾选的流媒体与 AI 检测。 */
    private suspend fun runUnlockTests(selectedIds: Set<String>, expectedNode: Node) {
        val definitions = UnlockTestCatalog.schemeDefinitions.filter { it.id in selectedIds }
        if (definitions.isEmpty()) return

        _unlockTestState.value = UnlockTestUiState(
            items = definitions.map { definition ->
                UnlockTestResult(
                    definition = definition,
                    state = UnlockTestState.TESTING,
                    statusText = "等待连接",
                    description = definition.testingDescription
                )
            },
            isRunning = true,
            message = "等待 VPN 节点连接"
        )

        val finalVpnState = withTimeoutOrNull(90_000L) {
            ServiceManager.vpnState.first {
                it == VpnState.CONNECTED || it == VpnState.DISCONNECTED
            }
        }
        if (finalVpnState != VpnState.CONNECTED) {
            finishUnlockTestsAsNotConnected(
                definitions,
                expectedNode.id,
                if (finalVpnState == null) "等待 VPN 连接超时" else "VPN 连接失败"
            )
            return
        }

        val endpoint = BoxVpnService.activeSocksEndpoint()
        if (endpoint == null || !isCurrentTestNode(expectedNode)) {
            finishUnlockTestsAsNotConnected(
                definitions,
                expectedNode.id,
                "当前节点未连接或连接已切换"
            )
            return
        }

        _unlockTestState.update { it.copy(message = "正在通过当前节点检测") }
        try {
            val semaphore = Semaphore(MAX_CONCURRENT_UNLOCK_TESTS)
            UnlockTestRunner(endpoint).use { runner ->
                coroutineScope {
                    definitions.map { definition ->
                        async {
                            val result = semaphore.withPermit {
                                try {
                                    runner.run(definition)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    UnlockTestClassifier.error(definition, throwable)
                                }
                            }
                            _unlockTestState.update { state ->
                                state.copy(items = state.items.map {
                                    if (it.definition.id == definition.id) result else it
                                })
                            }
                        }
                    }.awaitAll()
                }
            }
            _unlockTestState.update { state ->
                val usable = state.items.count {
                    it.state == UnlockTestState.UNLOCKED || it.state == UnlockTestState.PARTIAL
                }
                state.copy(
                    isRunning = false,
                    message = "检测完成：${state.items.size} 项中 $usable 项可用或部分可用"
                )
            }
            persistUnlockTestResult(expectedNode.id, _unlockTestState.value)
        } catch (cancelled: CancellationException) {
            _unlockTestState.update { state ->
                state.copy(
                    items = state.items.map { item ->
                        if (item.state == UnlockTestState.TESTING) {
                            item.copy(
                                state = UnlockTestState.IDLE,
                                statusText = "已取消",
                                description = "用户已取消测试"
                            )
                        } else {
                            item
                        }
                    },
                    isRunning = false,
                    message = "测试已取消"
                )
            }
            throw cancelled
        } catch (throwable: Throwable) {
            _unlockTestState.update { state ->
                state.copy(
                    items = state.items.map { item ->
                        if (item.state == UnlockTestState.TESTING) {
                            UnlockTestClassifier.error(item.definition, throwable)
                        } else {
                            item
                        }
                    },
                    isRunning = false,
                    message = "检测未能完成，请稍后重试"
                )
            }
            persistUnlockTestResult(expectedNode.id, _unlockTestState.value)
        }
    }

    private suspend fun persistUnlockTestResult(nodeId: String, state: UnlockTestUiState) {
        if (state.isRunning || state.items.isEmpty()) return
        runCatching {
            nodeDao().saveUnlockTestResult(nodeId, HomeTestResultCodec.encodeUnlockTests(state))
        }.onFailure { error -> Log.w(tag, "Failed to persist IP test results", error) }
    }

    private suspend fun finishUnlockTestsAsNotConnected(
        definitions: List<UnlockTestDefinition>,
        nodeId: String,
        message: String
    ) {
        _unlockTestState.value = UnlockTestUiState(
            items = definitions.map { definition ->
                UnlockTestResult(
                    definition = definition,
                    state = UnlockTestState.NOT_CONNECTED,
                    statusText = "未连接",
                    description = message
                )
            },
            message = message
        )
        persistUnlockTestResult(nodeId, _unlockTestState.value)
    }

    private companion object {
        const val MAX_CONCURRENT_UNLOCK_TESTS = 4
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

    fun setDisconnectRecoveryEnabled(enabled: Boolean) {

        viewModelScope.launch {
            settingsRepository.setDisconnectRecoveryEnabled(enabled)
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
        autoDeleteTimeoutNodes: Boolean
    ) {

        val updates = results.map { result ->
            NodeLatencyUpdate(result.nodeId, result.latency, result.isAvailable)
        }
        val updatedRows = nodeDao().updateUrlTestResults(updates, System.currentTimeMillis())
        if (updatedRows != updates.size) {
            Log.w(tag, "Only $updatedRows/${updates.size} URL test results were applied")
        }
        _nodeTestResultSortMode.value = NodeTestResultSortMode.URL_TEST_LATENCY
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
        _nodeTestResultSortMode.value = NodeTestResultSortMode.TCPING_LATENCY
        deleteTimedOutNodes(results, isTcping = true, enabled = autoDeleteTimeoutNodes)
        return results
    }

    /** 统一选择节点组，并同步当前连接流程需要的筛选字段。 */
    fun selectNodeGroup(group: NodeGroupKey) {
        pendingSelectedNodeGroup.value = group
        viewModelScope.launch {
            try {
                settingsRepository.setSelectedNodeGroup(group)
            } finally {
                if (pendingSelectedNodeGroup.value == group) {
                    pendingSelectedNodeGroup.value = null
                }
            }
        }
    }

    /** Select a node from the node list and connect to it immediately. */
    fun selectAndConnectNode(node: Node) {
        viewModelScope.launch {
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

    enum class SubscriptionGroupCreateResult {
        CREATED,
        MULTIPLE_CREATED,
        PARTIAL_CREATED,
        INVALID,
        FETCH_FAILED
    }

    enum class SubscriptionGroupEditResult {
        UPDATED,
        INVALID,
        FETCH_FAILED,
        NOT_FOUND
    }

    enum class SubscriptionGroupRefreshResult {
        REFRESHED,
        INVALID,
        FETCH_FAILED,
        NOT_FOUND,
        BUSY
    }

    enum class SubscriptionGroupNodeImportResult {
        IMPORTED,
        ALREADY_EXISTS,
        INVALID,
        FETCH_FAILED,
        NOT_FOUND
    }

    enum class LocalGroupNodeEditResult {
        UPDATED,
        INVALID,
        NOT_FOUND
    }

    enum class LocalGroupNodeDeleteResult {
        DELETED,
        NOT_FOUND,
        IN_USE
    }

    /**
     * 创建用户本地订阅组。输入可以是订阅链接、节点链接列表，也可以为空以先创建空组。
     */
    fun createSubscriptionGroup(
        input: String?,
        onResult: (SubscriptionGroupCreateResult, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ) {
        viewModelScope.launch {
            val normalizedInput = input?.trim().orEmpty()
            val importInput = SubscriptionInputDetector.detect(normalizedInput)
            var failedSourceCount = 0
            val hasMultipleRemoteInput = importInput is SubscriptionImportInput.MultipleRemoteSubscriptions ||
                importInput is SubscriptionImportInput.MixedSubscription
            // 多个远程订阅逐条处理，单个链接失败不阻断其他链接创建。
            val parsedParts = if (hasMultipleRemoteInput) {
                val remoteUrls = when (importInput) {
                    is SubscriptionImportInput.MultipleRemoteSubscriptions -> importInput.urls
                    is SubscriptionImportInput.MixedSubscription -> importInput.urls
                    else -> emptyList()
                }
                val contentParts = if (importInput is SubscriptionImportInput.MixedSubscription) {
                    val contentPart = parseFullSubscriptionContent(importInput.content)
                    if (contentPart.nodes.isEmpty()) {
                        // 本地配置只提取可用节点，其余内容丢弃，不创建空的本地来源。
                        emptyList()
                    } else {
                        listOf(ParsedSubscriptionPart(null, contentPart, "导入内容"))
                    }
                } else {
                    emptyList()
                }
                val remoteParts = fetchRemoteSubscriptionResults(remoteUrls)
                    .mapIndexedNotNull { index, result ->
                        val parsed = result.getOrNull()
                        if (parsed == null || parsed.nodes.isEmpty()) {
                            failedSourceCount++
                            Log.e(
                                tag,
                                "Unable to import remote subscription #${index + 1}",
                                result.exceptionOrNull()
                            )
                            null
                        } else {
                            val groupLabel = if (importInput is SubscriptionImportInput.MixedSubscription) {
                                "远程 ${index + 1}"
                            } else {
                                null
                            }
                            ParsedSubscriptionPart(remoteUrls[index], parsed, groupLabel)
                        }
                    }
                contentParts + remoteParts
            } else {
                val parsedSubscriptions = parseSubscriptionInput(importInput)
                if (parsedSubscriptions.isFailure) {
                    Log.e(
                        tag,
                        "Unable to fetch user subscription group",
                        parsedSubscriptions.exceptionOrNull()
                    )
                    onResult(SubscriptionGroupCreateResult.FETCH_FAILED, 0, 0, 1)
                    return@launch
                }
                parsedSubscriptions.getOrThrow()
            }.ifEmpty {
                listOf(ParsedSubscriptionPart(null, ParsedSubscription(emptyList())))
            }

            if (
                hasMultipleRemoteInput &&
                parsedParts.all { it.subscription.nodes.isEmpty() }
            ) {
                onResult(
                    SubscriptionGroupCreateResult.FETCH_FAILED,
                    0,
                    0,
                    failedSourceCount
                )
                return@launch
            }

            // 订阅导入不应依赖当前选中的核心。
            // 保留原始 URI，等实际准备核心会话时再校验兼容性。
            if (normalizedInput.isNotBlank() && parsedParts.any { it.subscription.nodes.isEmpty() }) {
                onResult(SubscriptionGroupCreateResult.INVALID, 0, 0, failedSourceCount)
                return@launch
            }

            data class PreparedGroup(
                val group: NodeGroupEntity,
                val nodes: List<Node>,
                val members: List<NodeGroupMemberEntity>
            )

            val preparedGroups = parsedParts.map { part ->
                val groupId = "group_${UUID.randomUUID()}"
                val groupName = subscriptionGroupName(part)
                val parsed = part.subscription
                val createdAt = System.currentTimeMillis()
                val group = NodeGroupEntity(
                    id = groupId,
                    name = groupName,
                    type = NodeGroupStorageType.SUBSCRIPTION.name,
                    subscriptionUrl = part.url,
                    subscriptionUploadBytes = parsed.userInfo?.uploadBytes,
                    subscriptionDownloadBytes = parsed.userInfo?.downloadBytes,
                    subscriptionTotalBytes = parsed.userInfo?.totalBytes,
                    subscriptionExpireAt = parsed.userInfo?.expireAtMillis,
                    subscriptionUpdatedAt = createdAt,
                    createdAt = createdAt
                )
                val localNodes = parsed.nodes
                    .distinctBy(Node::getRawLinkPlain)
                    .mapIndexed { index, node ->
                        node.copy(
                            id = "${groupId}_$index",
                            source = NodeSource.LOCAL_GROUP,
                            subscriptionSourceId = groupId,
                            subscriptionSourceName = groupName,
                            latency = -1,
                            tcpingLatency = -1,
                            urlTestLatency = -1,
                            isAvailable = true,
                            lastTestedAt = 0L,
                            downloadMbps = 0f,
                            uploadMbps = 0f
                        )
                    }
                val members = localNodes.mapIndexed { index, node ->
                    NodeGroupMemberEntity(
                        groupId = groupId,
                        nodeId = node.id,
                        sortOrder = index
                    )
                }
                PreparedGroup(group, localNodes, members)
            }

            withContext(Dispatchers.IO) {
                val dao = nodeDao()
                preparedGroups.forEach { prepared ->
                    dao.insertNodeGroupWithNodes(
                        prepared.group,
                        prepared.nodes,
                        prepared.members
                    )
                }
            }
            settingsRepository.setSelectedNodeGroup(
                NodeGroupKey.Imported(preparedGroups.first().group.id)
            )
            val totalImportedNodes = preparedGroups.sumOf { it.nodes.size }
            val result = if (failedSourceCount > 0) {
                SubscriptionGroupCreateResult.PARTIAL_CREATED
            } else if (preparedGroups.size > 1) {
                SubscriptionGroupCreateResult.MULTIPLE_CREATED
            } else {
                SubscriptionGroupCreateResult.CREATED
            }
            onResult(result, totalImportedNodes, preparedGroups.size, failedSourceCount)
        }
    }

    private fun subscriptionGroupName(part: ParsedSubscriptionPart): String =
        part.subscription.name?.trim()?.takeIf(String::isNotEmpty)
            ?: part.url?.let { url ->
                Uri.parse(url).host
                    ?.removePrefix("www.")
                    ?.takeIf(String::isNotEmpty)
            }
            ?: part.groupLabel?.trim()?.takeIf(String::isNotEmpty)
            ?: if (part.subscription.nodes.isEmpty()) "新建私人源" else "导入内容"

    /** 将节点 URI 追加到本地订阅组，保留组内已有节点。 */
    fun importNodeUrisIntoSubscriptionGroup(
        groupId: String,
        input: String,
        onResult: (SubscriptionGroupNodeImportResult, Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val normalizedInput = input.trim()
            if (normalizedInput.isBlank()) {
                onResult(SubscriptionGroupNodeImportResult.INVALID, 0)
                return@launch
            }

            val dao = nodeDao()
            val group = withContext(Dispatchers.IO) {
                dao.getNodeGroupById(groupId)
            }
            if (group == null || group.type !in setOf(
                    NodeGroupStorageType.IMPORTED.name,
                    NodeGroupStorageType.SUBSCRIPTION.name
                )
            ) {
                onResult(SubscriptionGroupNodeImportResult.NOT_FOUND, 0)
                return@launch
            }

            val importInput = SubscriptionInputDetector.detect(normalizedInput)
            val parsedSubscriptions = parseSubscriptionInput(importInput)
            if (parsedSubscriptions.isFailure) {
                Log.e(
                    tag,
                    "Unable to import nodes into local subscription group",
                    parsedSubscriptions.exceptionOrNull()
                )
                onResult(SubscriptionGroupNodeImportResult.FETCH_FAILED, 0)
                return@launch
            }

            // 即使当前核心无法处理包含 ECH 或证书字段的 URI，也要保留订阅节点。
            val nodesToImport = parsedSubscriptions.getOrThrow().flatMap { it.subscription.nodes }
            if (nodesToImport.isEmpty()) {
                onResult(SubscriptionGroupNodeImportResult.INVALID, 0)
                return@launch
            }

            val existingNodes = withContext(Dispatchers.IO) {
                dao.getNodesByGroup(groupId).first()
            }
            val existingRawLinks = existingNodes.map(Node::getRawLinkPlain).toSet()
            val nodesToAdd = nodesToImport
                .distinctBy(Node::getRawLinkPlain)
                .filterNot { it.getRawLinkPlain() in existingRawLinks }
            if (nodesToAdd.isEmpty()) {
                onResult(SubscriptionGroupNodeImportResult.ALREADY_EXISTS, 0)
                return@launch
            }

            val localNodes = nodesToAdd.mapIndexed { index, node ->
                node.copy(
                    id = "${groupId}_${UUID.randomUUID()}",
                    source = NodeSource.LOCAL_GROUP,
                    subscriptionSourceId = groupId,
                    subscriptionSourceName = group.name,
                    latency = -1,
                    tcpingLatency = -1,
                    urlTestLatency = -1,
                    isAvailable = true,
                    lastTestedAt = 0L,
                    sortOrder = existingNodes.size + index,
                    downloadMbps = 0f,
                    uploadMbps = 0f
                )
            }
            val members = localNodes.mapIndexed { index, node ->
                NodeGroupMemberEntity(
                    groupId = groupId,
                    nodeId = node.id,
                    sortOrder = existingNodes.size + index
                )
            }

            withContext(Dispatchers.IO) {
                dao.appendNodesToGroup(localNodes, members)
            }
            onResult(SubscriptionGroupNodeImportResult.IMPORTED, localNodes.size)
        }
    }

    /** 更新本地订阅组中的单个节点，保留节点 ID 和组内排序。 */
    fun updateLocalGroupNode(
        groupId: String,
        nodeId: String,
        name: String,
        rawLink: String,
        onResult: (LocalGroupNodeEditResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) {
                onResult(LocalGroupNodeEditResult.INVALID)
                return@launch
            }

            val dao = nodeDao()
            val group = withContext(Dispatchers.IO) {
                dao.getNodeGroupById(groupId)
            } ?: run {
                onResult(LocalGroupNodeEditResult.NOT_FOUND)
                return@launch
            }
            val existingNode = withContext(Dispatchers.IO) {
                dao.getNodesByGroup(groupId).first().firstOrNull { it.id == nodeId }
            } ?: run {
                onResult(LocalGroupNodeEditResult.NOT_FOUND)
                return@launch
            }

            val normalizedLink = rawLink.trim()
            if (normalizedLink.isBlank() || normalizedLink == existingNode.rawLink) {
                withContext(Dispatchers.IO) {
                    dao.insertNode(existingNode.copy(name = normalizedName))
                }
                onResult(LocalGroupNodeEditResult.UPDATED)
                return@launch
            }

            val parsedNode = withContext(Dispatchers.Default) {
                subscriptionParser.parseSubscription(normalizedLink).singleOrNull()
            } ?: run {
                onResult(LocalGroupNodeEditResult.INVALID)
                return@launch
            }
            if (!withContext(Dispatchers.IO) { isNodeCoreConfigCompatible(parsedNode) }) {
                onResult(LocalGroupNodeEditResult.INVALID)
                return@launch
            }

            val updatedNode = parsedNode.copy(
                id = existingNode.id,
                name = normalizedName,
                subscriptionSourceId = groupId,
                subscriptionSourceName = group.name,
                source = NodeSource.LOCAL_GROUP,
                latency = -1,
                tcpingLatency = -1,
                urlTestLatency = -1,
                isAvailable = true,
                lastTestedAt = 0L,
                sortOrder = existingNode.sortOrder,
                downloadMbps = 0f,
                uploadMbps = 0f
            )
            withContext(Dispatchers.IO) {
                dao.insertNode(updatedNode)
            }
            onResult(LocalGroupNodeEditResult.UPDATED)
        }
    }

    /** 从本地订阅组移除一个节点；当前正在使用的节点不能删除。 */
    fun deleteLocalGroupNode(
        groupId: String,
        nodeId: String,
        onResult: (LocalGroupNodeDeleteResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (activeConnectionNode.value?.id == nodeId) {
                onResult(LocalGroupNodeDeleteResult.IN_USE)
                return@launch
            }

            val deleted = withContext(Dispatchers.IO) {
                nodeDao().deleteLocalGroupNodeFromGroup(groupId, nodeId)
            }
            if (!deleted) {
                onResult(LocalGroupNodeDeleteResult.NOT_FOUND)
                return@launch
            }
            if (settingsRepository.selectedNodeId.first() == nodeId) {
                settingsRepository.setSelectedNodeId(null)
            }
            onResult(LocalGroupNodeDeleteResult.DELETED)
        }
    }

    /** 更新本地订阅组名称及可选订阅内容。输入链接变化时会重新导入组内节点。 */
    fun updateSubscriptionGroup(
        groupId: String,
        name: String,
        input: String?,
        userAgentMode: PersonalSourceUserAgentMode,
        customUserAgent: String?,
        onResult: (SubscriptionGroupEditResult, Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) {
                onResult(SubscriptionGroupEditResult.INVALID, 0)
                return@launch
            }
            val normalizedCustomUserAgent = customUserAgent?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeIf { userAgentMode == PersonalSourceUserAgentMode.CUSTOM }
            if (userAgentMode == PersonalSourceUserAgentMode.CUSTOM &&
                normalizedCustomUserAgent == null
            ) {
                onResult(SubscriptionGroupEditResult.INVALID, 0)
                return@launch
            }

            val dao = nodeDao()
            val existingGroup = withContext(Dispatchers.IO) {
                dao.getNodeGroupById(groupId)
            }
            if (existingGroup == null) {
                onResult(SubscriptionGroupEditResult.NOT_FOUND, 0)
                return@launch
            }

            val normalizedInput = input?.trim().orEmpty()
            val currentUrl = existingGroup.subscriptionUrl.orEmpty()
            if (normalizedInput.isBlank() || normalizedInput == currentUrl) {
                val updated = withContext(Dispatchers.IO) {
                    dao.updateNodeGroupMetadata(
                        groupId = groupId,
                        name = normalizedName,
                        subscriptionUrl = existingGroup.subscriptionUrl,
                        userAgentMode = userAgentMode.name,
                        customUserAgent = normalizedCustomUserAgent
                    )
                }
                onResult(
                    if (updated) SubscriptionGroupEditResult.UPDATED
                    else SubscriptionGroupEditResult.NOT_FOUND,
                    0
                )
                return@launch
            }

            val importInput = SubscriptionInputDetector.detect(normalizedInput)
            val parsedSubscriptions = parseSubscriptionInput(
                importInput,
                userAgentMode,
                normalizedCustomUserAgent
            )
            if (parsedSubscriptions.isFailure) {
                Log.e(
                    tag,
                    "Unable to fetch updated user subscription group",
                    parsedSubscriptions.exceptionOrNull()
                )
                onResult(SubscriptionGroupEditResult.FETCH_FAILED, 0)
                return@launch
            }
            val parsedParts = parsedSubscriptions.getOrThrow()
            val parsed = ParsedSubscription(
                nodes = parsedParts.flatMap { it.subscription.nodes },
                userInfo = parsedParts.singleOrNull()?.subscription?.userInfo
            )
            val subscriptionUrl = parsedParts.mapNotNull { it.url }.joinToString("\n").ifBlank { null }

            val importedNodes = parsed.nodes
            if (importedNodes.isEmpty()) {
                onResult(SubscriptionGroupEditResult.INVALID, 0)
                return@launch
            }

            val localNodes = importedNodes
                .distinctBy(Node::getRawLinkPlain)
                .mapIndexed { index, node ->
                    node.copy(
                        id = "${groupId}_$index",
                        source = NodeSource.LOCAL_GROUP,
                        subscriptionSourceId = groupId,
                        subscriptionSourceName = normalizedName,
                        latency = -1,
                        tcpingLatency = -1,
                        urlTestLatency = -1,
                        isAvailable = true,
                        lastTestedAt = 0L,
                        downloadMbps = 0f,
                        uploadMbps = 0f
                    )
                }
            val members = localNodes.mapIndexed { index, node ->
                NodeGroupMemberEntity(
                    groupId = groupId,
                    nodeId = node.id,
                    sortOrder = index
                )
            }
            val updated = withContext(Dispatchers.IO) {
                dao.replaceLocalGroupNodes(
                    groupId = groupId,
                    name = normalizedName,
                    subscriptionUrl = subscriptionUrl,
                    userAgentMode = userAgentMode.name,
                    customUserAgent = normalizedCustomUserAgent,
                    subscriptionUploadBytes = parsed.userInfo?.uploadBytes,
                    subscriptionDownloadBytes = parsed.userInfo?.downloadBytes,
                    subscriptionTotalBytes = parsed.userInfo?.totalBytes,
                    subscriptionExpireAt = parsed.userInfo?.expireAtMillis,
                    subscriptionUpdatedAt = System.currentTimeMillis(),
                    nodes = localNodes,
                    members = members
                )
            }
            onResult(
                if (updated) SubscriptionGroupEditResult.UPDATED
                else SubscriptionGroupEditResult.NOT_FOUND,
                localNodes.size
            )
        }
    }

    /** 立即重新获取带订阅链接的本地分组，并替换组内节点。 */
    fun refreshUserSubscriptionGroup(
        groupId: String,
        onResult: (SubscriptionGroupRefreshResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (
                subscriptionDataLoader.isLoading.value ||
                GlobalTestExecution.isFetching() ||
                _isTesting.value ||
                GlobalTestExecution.mutex.isLocked
            ) {
                onResult(SubscriptionGroupRefreshResult.BUSY)
                return@launch
            }

            onResult(refreshUserSubscriptionGroupInternal(groupId))
        }
    }

    /** 冷启动时顺序更新启用了自动更新的远程私人源。 */
    private suspend fun refreshUserSubscriptionGroupsOnStartup(
        autoRefreshOverrides: Map<String, Boolean>
    ) {
        val groups = withContext(Dispatchers.IO) {
            nodeDao().getUserSubscriptionGroups().first()
        }
        groups.forEach { group ->
            val importInput = SubscriptionInputDetector.detect(group.subscriptionUrl.orEmpty())
            if (
                autoRefreshOverrides[group.id] == true &&
                    (importInput is SubscriptionImportInput.RemoteSubscription ||
                        importInput is SubscriptionImportInput.MultipleRemoteSubscriptions)
            ) {
                when (refreshUserSubscriptionGroupInternal(group.id)) {
                    SubscriptionGroupRefreshResult.REFRESHED -> Unit
                    else -> Log.w(tag, "Unable to refresh personal subscription group on startup: ${group.id}")
                }
            }
        }
    }

    private suspend fun refreshUserSubscriptionGroupInternal(
        groupId: String
    ): SubscriptionGroupRefreshResult {
        val dao = nodeDao()
        val group = withContext(Dispatchers.IO) {
            dao.getNodeGroupById(groupId)
        } ?: return SubscriptionGroupRefreshResult.NOT_FOUND
        if (group.type !in setOf(
                NodeGroupStorageType.IMPORTED.name,
                NodeGroupStorageType.SUBSCRIPTION.name
            )
        ) {
            return SubscriptionGroupRefreshResult.NOT_FOUND
        }

        val importInput = SubscriptionInputDetector.detect(group.subscriptionUrl.orEmpty())
        if (
            importInput !is SubscriptionImportInput.RemoteSubscription &&
            importInput !is SubscriptionImportInput.MultipleRemoteSubscriptions
        ) {
            return SubscriptionGroupRefreshResult.INVALID
        }
        val parsedSubscriptions = parseSubscriptionInput(
            importInput,
            PersonalSourceUserAgentMode.fromStorage(group.userAgentMode),
            group.customUserAgent
        )
        if (parsedSubscriptions.isFailure) {
            Log.e(
                tag,
                "Unable to refresh user subscription group",
                parsedSubscriptions.exceptionOrNull()
            )
            return SubscriptionGroupRefreshResult.FETCH_FAILED
        }
        val parsedParts = parsedSubscriptions.getOrThrow()
        val parsed = ParsedSubscription(
            nodes = parsedParts.flatMap { it.subscription.nodes },
            userInfo = parsedParts.singleOrNull()?.subscription?.userInfo
        )
        val subscriptionUrl = parsedParts.mapNotNull { it.url }.joinToString("\n")

        val importedNodes = parsed.nodes.distinctBy(Node::getRawLinkPlain)
        if (importedNodes.isEmpty()) {
            return SubscriptionGroupRefreshResult.INVALID
        }

        val localNodes = importedNodes.mapIndexed { index, node ->
            node.copy(
                id = "${groupId}_$index",
                source = NodeSource.LOCAL_GROUP,
                subscriptionSourceId = groupId,
                subscriptionSourceName = group.name,
                latency = -1,
                tcpingLatency = -1,
                urlTestLatency = -1,
                isAvailable = true,
                lastTestedAt = 0L,
                sortOrder = index,
                downloadMbps = 0f,
                uploadMbps = 0f
            )
        }
        val members = localNodes.mapIndexed { index, node ->
            NodeGroupMemberEntity(
                groupId = groupId,
                nodeId = node.id,
                sortOrder = index
            )
        }
        val refreshed = withContext(Dispatchers.IO) {
            dao.replaceLocalGroupNodes(
                groupId = groupId,
                name = group.name,
                subscriptionUrl = subscriptionUrl,
                userAgentMode = group.userAgentMode,
                customUserAgent = group.customUserAgent,
                subscriptionUploadBytes = parsed.userInfo?.uploadBytes,
                subscriptionDownloadBytes = parsed.userInfo?.downloadBytes,
                subscriptionTotalBytes = parsed.userInfo?.totalBytes,
                subscriptionExpireAt = parsed.userInfo?.expireAtMillis,
                subscriptionUpdatedAt = System.currentTimeMillis(),
                nodes = localNodes,
                members = members
            )
        }
        return if (refreshed) {
            SubscriptionGroupRefreshResult.REFRESHED
        } else {
            SubscriptionGroupRefreshResult.NOT_FOUND
        }
    }

    /** 删除本地订阅组及其节点。 */
    fun deleteSubscriptionGroup(groupId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val selectedGroupId = settingsRepository.selectedNodeGroupId.first()
                val dao = nodeDao()
                val group = dao.getNodeGroupById(groupId)
                if (group == null) {
                    false
                } else {
                    dao.deleteNodeGroupWithNodes(groupId)
                    PersonalSourceIconStore.delete(getApplication<Application>(), group.localIconFile)
                    if (selectedGroupId == NodeGroupKey.Imported(groupId).value) {
                        val fallbackSourceId = activeSubscriptionSourceId.value
                        settingsRepository.setSelectedCountryCode(null)
                        settingsRepository.setSelectedSubscriptionSourceId(fallbackSourceId)
                        settingsRepository.setSelectedNodeGroupId(
                            fallbackSourceId
                                ?.takeIf(String::isNotBlank)
                                ?.let { NodeGroupKey.Subscription(it).value }
                        )
                        settingsRepository.setSelectedNodeId(null)
                    }
                    true
                }
            }
            onResult(deleted)
        }
    }

    /** 保存私人源卡片图标来源及可选远程链接/本地图片。 */
    fun updatePersonalSourceIcon(
        groupId: String,
        iconMode: String,
        customIconUrl: String?,
        localIconFile: String?,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                nodeDao().updateNodeGroupIcon(
                    groupId = groupId,
                    iconMode = iconMode,
                    customIconUrl = customIconUrl,
                    localIconFile = localIconFile
                ) > 0
            }
            onResult(updated)
        }
    }

    /** 从本机移除一个官方订阅源的节点；远端订阅源仍可在后续刷新时重新导入。 */
    fun deleteOfficialSubscription(sourceId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (sourceId.isBlank()) {
                onResult(false)
                return@launch
            }

            val dao = nodeDao()
            val sourceNodes = withContext(Dispatchers.IO) {
                dao.getSubscriptionNodesBySource(sourceId).first()
            }
            if (sourceNodes.isEmpty()) {
                onResult(false)
                return@launch
            }

            withContext(Dispatchers.IO) {
                dao.deleteSubscriptionNodesBySource(sourceId)
            }

            val sourceNodeIds = sourceNodes.mapTo(mutableSetOf(), Node::id)
            if (settingsRepository.selectedNodeId.first() in sourceNodeIds) {
                settingsRepository.setSelectedNodeId(null)
            }
            if (settingsRepository.selectedNodeGroupId.first() == NodeGroupKey.Subscription(sourceId).value) {
                settingsRepository.setSelectedNodeGroupId(null)
            }
            if (settingsRepository.selectedSubscriptionSourceId.first() == sourceId) {
                settingsRepository.setSelectedSubscriptionSourceId(null)
            }
            onResult(true)
        }
    }

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

    fun setHomeClickSpeedTestScheme(schemeId: String) {
        viewModelScope.launch {
            settingsRepository.setHomeClickSpeedTestSchemeId(schemeId)
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

    fun setHomeLongPressSpeedTestScheme(schemeId: String) {
        viewModelScope.launch {
            settingsRepository.setHomeLongPressSpeedTestSchemeId(schemeId)
        }
    }

    fun setGroupManualSpeedTestScheme(schemeId: String) {
        viewModelScope.launch {
            settingsRepository.setGroupManualSpeedTestSchemeId(schemeId)
        }
    }

    fun setGroupManualSpeedTestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGroupManualSpeedTestEnabled(enabled)
        }
    }

    fun setHomeClickSpeedTestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHomeClickSpeedTestEnabled(enabled)
        }
    }

    fun setLanInboundProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            val current = settingsRepository.lanInboundSettings.first()
            if (current.proxyMode == mode) return@launch
            settingsRepository.setLanInboundProxyMode(mode)
            if (current.enabled) restartVpnIfNeeded()
        }
    }

    /** 中断测速方案、分组手动测速和 Hy2 带宽自适应测试。 */
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

    fun setHomeLongPressSpeedTestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHomeLongPressSpeedTestEnabled(enabled)
        }
    }

    fun upsertSpeedTestScheme(scheme: SpeedTestScheme) {
        viewModelScope.launch { settingsRepository.upsertSpeedTestScheme(scheme) }
    }

    fun deleteSpeedTestScheme(schemeId: String) {
        viewModelScope.launch { settingsRepository.deleteSpeedTestScheme(schemeId) }
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
                    val isIgnoredOptionalUpdate = isAuto &&
                        info.isForce != 1 &&
                        settingsRepository.ignoredUpdateVersionCode.first() == info.versionCode

                    if (isIgnoredOptionalUpdate) {
                        _updateInfo.value = null
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
            val existingArtifact = DownloadManager.isApkReady(
                getApplication(),
                info.version,
                updateDownloadDirectoryUri.value
            )
            if (existingArtifact != null) {
                // 此处强制执行 installApk 逻辑
                installApk()
                return
            }
            
            viewModelScope.launch {
                DownloadManager.startDownload(
                    info.downloadUrl,
                    getApplication(),
                    info.version,
                    updateDownloadDirectoryUri.value
                )
            }
        }
    }
    
    fun pauseDownload() {
        DownloadManager.pauseDownload()
    }
    
    fun resumeDownload() {
        updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(
                    info.downloadUrl,
                    getApplication(),
                    info.version,
                    updateDownloadDirectoryUri.value
                )
            }
        }
    }
    
    fun retryDownload() {
         updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(
                    info.downloadUrl,
                    getApplication(),
                    info.version,
                    updateDownloadDirectoryUri.value
                )
            }
        }
    }
    
    fun cancelDownload() {
        DownloadManager.cancelDownload()
    }
    
    fun installApk() {
        val context = getApplication<Application>()
        val targetArtifact = updateInfo.value?.let { info ->
            DownloadManager.isApkReady(
                context,
                info.version,
                updateDownloadDirectoryUri.value
            )
        }
            ?: downloadState.value.file?.let { DownloadArtifact(file = it) }
            ?: downloadState.value.fileUri?.let { DownloadArtifact(uri = it) }

        targetArtifact?.let { artifact ->
            try {
                val uri = artifact.uri ?: artifact.file?.takeIf { it.exists() }?.let { file ->
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } ?: run {
                    _error.value = "安装文件丢失，请重新下载"
                    DownloadManager.resetState()
                    return
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    clipData = ClipData.newRawUri("APK", uri)
                }
                // 交给系统安装器打开 APK，不在应用内请求“安装未知来源”设置。
                context.startActivity(intent)
                // 安装器打开后消费本次完成事件，避免返回应用或重建主界面时重复弹出。
                // 不删除安装包，用户仍可通过手动检查更新再次打开它。
                DownloadManager.resetState()
            } catch (e: Exception) {
                Log.e(tag, "Install failed", e)
                _error.value = "无法打开系统安装程序: ${e.message}"
                // 即使系统没有可用的安装器，也不要让完成状态持续触发重复弹窗。
                DownloadManager.resetState()
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

    fun skipUpdate(ignoreCurrentVersion: Boolean) {
        val info = _updateInfo.value ?: return
        _updateInfo.value = null
        if (ignoreCurrentVersion && info.isForce != 1) {
            viewModelScope.launch {
                settingsRepository.setIgnoredUpdateVersionCode(info.versionCode)
                Log.i(tag, "Ignored optional update: versionCode=${info.versionCode}")
            }
        }
    }

    fun setUpdateDownloadDirectoryUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setUpdateDownloadDirectoryUri(uri)
        }
    }
    
    fun clearError() {
        _error.value = null
    }

    fun clearInfoDialogMessage() {
        _infoDialogMessage.value = null
    }

    fun updateNickname(nickname: String) {
        if (_isUpdatingNickname.value || _isActivatingVip.value) return
        viewModelScope.launch {
            _isUpdatingNickname.value = true
            _accountMessage.value = null
            try {
                accountManager.updateNickname(
                    nickname = nickname,
                    timeoutMs = serviceRequestTimeoutMs.value
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

    fun activateVipCard(code: String) {
        if (
            _isActivatingVip.value || _isSyncingAccount.value || _isUpdatingNickname.value ||
            _isBindingAccount.value || _isSwitchingAccount.value || _isDeletingAccount.value
        ) return
        viewModelScope.launch {
            _isActivatingVip.value = true
            _accountMessage.value = null
            try {
                accountManager.activateVipCard(
                    code = code,
                    timeoutMs = serviceRequestTimeoutMs.value
                ).onSuccess {
                    _accountMessage.value = "VIP 激活成功，有效期已更新"
                }.onFailure { error ->
                    Log.w(tag, "Failed to activate VIP card", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
            } finally {
                _isActivatingVip.value = false
            }
        }
    }

    fun syncCurrentAccount() {
        if (
            _isSyncingAccount.value ||
            _isUpdatingNickname.value ||
            _isBindingAccount.value ||
            _isSwitchingAccount.value ||
            _isDeletingAccount.value ||
            _isActivatingVip.value
        ) return
        viewModelScope.launch {
            _isSyncingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.syncCurrentAccount(
                    timeoutMs = serviceRequestTimeoutMs.value
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
        if (_isBindingAccount.value || _isActivatingVip.value) return
        viewModelScope.launch {
            _isBindingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.bindEmailPassword(
                    email = email,
                    password = password,
                    registrationCode = registrationCode,
                    timeoutMs = serviceRequestTimeoutMs.value
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

    fun loginAtStartup(email: String, password: String) {
        runStartupAccountAction {
            accountManager.loginAccount(
                email = email,
                password = password,
                timeoutMs = serviceRequestTimeoutMs.value
            ).getOrThrow()
        }
    }

    fun registerAtStartup(
        email: String,
        password: String,
        registrationCode: String
    ) {
        runStartupAccountAction {
            if (accountManager.currentAccount() == null) {
                accountManager.register(serviceRequestTimeoutMs.value).getOrThrow()
            }
            accountManager.bindEmailPassword(
                email = email,
                password = password,
                registrationCode = registrationCode,
                timeoutMs = serviceRequestTimeoutMs.value
            ).getOrThrow()
        }
    }

    fun continueAsGuestAtStartup() {
        runStartupAccountAction {
            accountManager.register(serviceRequestTimeoutMs.value).getOrThrow()
        }
    }

    private fun runStartupAccountAction(action: suspend () -> AccountInfo) {
        if (_isStartupAccountActionInProgress.value || _startupAccountAuthenticated.value) return
        viewModelScope.launch {
            _isStartupAccountActionInProgress.value = true
            _accountMessage.value = null
            try {
                action()
                _startupAccountAuthenticated.value = true
                viewModelScope.launch { loadStartupDataAfterAuthentication() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(tag, "Startup account action failed", error)
                _accountMessage.value = startupAccountErrorMessage(error)
            } finally {
                _isStartupAccountActionInProgress.value = false
            }
        }
    }

    private suspend fun loadStartupDataAfterAuthentication() {
        try {
            val autoRefreshOverrides = settingsRepository.autoRefreshOnStartupOverrides.first()
            if (!initializeSubscriptionsWithRefreshState(
                    forceRefresh = true,
                    shouldRefreshSource = { sourceId ->
                        autoRefreshOverrides[sourceId] ?: true
                    }
                )
            ) {
                scheduleNodeLocationEnrichment(nodeDao().getAllNodes().first())
            }
            refreshUserSubscriptionGroupsOnStartup(autoRefreshOverrides)
            checkNotice()
            checkUpdateInternal(isAuto = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(tag, "Post-authentication startup refresh failed", error)
        }
    }

    fun clearAccountMessage() {
        _accountMessage.value = null
    }

    fun switchAccount(email: String, password: String) {
        if (_isSwitchingAccount.value || _isDeletingAccount.value || _isActivatingVip.value) return
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
                    timeoutMs = serviceRequestTimeoutMs.value
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
        if (_isDeletingAccount.value || _isSwitchingAccount.value || _isActivatingVip.value) return
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
                    timeoutMs = serviceRequestTimeoutMs.value
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

    private fun startupAccountErrorMessage(error: Throwable): String = when (error.message) {
        "invalid_email" -> "邮箱格式无效"
        "invalid_password" -> "密码长度需在 8～128 个字符之间"
        "device_banned" -> "当前设备已被封禁"
        "registration_disabled",
        "registration_code_required",
        "invalid_registration_code",
        "formal_registration_closed",
        "invalid_bind_credentials",
        "account_already_bound",
        "email_already_bound" -> accountBindingErrorMessage(error)
        else -> accountActionErrorMessage(error)
    }

    private fun accountBindingErrorMessage(error: Throwable): String {
        return when (error.message) {
            "registration_disabled" -> "用户注册暂未开放"
            "registration_code_required" -> "必须使用管理员发放的注册码注册"
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
            "backend_url_missing" -> "未配置服务地址"
            "invalid_login_credentials" -> "邮箱或密码错误"
            "invalid_account_password" -> "当前账户密码错误"
            "account_banned" -> "账户已被封禁，无法切换"
            "account_not_found" -> "账户不存在，请重新同步账户"
            "invalid_vip_activation_request" -> "VIP 卡密格式无效"
            "invalid_or_used_vip_card" -> "VIP 卡密无效或已兑换"
            "vip_activation_failed" -> "VIP 激活失败，请稍后重试"
            else -> error.message ?: "账户操作失败"
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

    fun setSmartRoutingDomains(action: SmartRoutingRuleAction, domains: List<String>) {
        viewModelScope.launch {
            settingsRepository.setSmartRoutingDomains(action, domains)
            restartVpnIfNeeded()
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
        viewModelScope.launch {
            if (subscriptionDataLoader.isLoading.value) {
                onResult?.invoke(false)
                return@launch
            }
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
                sourceId = sourceId
            )
            onResult?.invoke(fetchOk)
        }
    }

    /** 统一维护官方源刷新提示，覆盖启动更新和手动刷新。 */
    private suspend fun initializeSubscriptionsWithRefreshState(
        forceRefresh: Boolean,
        sourceId: String? = null,
        shouldRefreshSource: ((String) -> Boolean)? = null
    ): Boolean {
        val isRefreshAll = sourceId.isNullOrBlank()
        if (isRefreshAll) _isRefreshingAllSubscriptions.value = true
        return try {
            subscriptionDataLoader.initialize(
                forceRefresh = forceRefresh,
                sourceId = sourceId,
                shouldRefreshSource = shouldRefreshSource
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
