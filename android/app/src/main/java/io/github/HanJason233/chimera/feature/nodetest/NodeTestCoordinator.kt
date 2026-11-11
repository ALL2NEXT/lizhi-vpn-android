package io.github.HanJason233.chimera.feature.nodetest

import io.github.HanJason233.chimera.feature.nodetest.data.HomeTestResultCodec
import io.github.HanJason233.chimera.feature.nodetest.model.SpeedTestProgress
import io.github.HanJason233.chimera.feature.nodetest.model.UnlockTestUiState
import io.github.HanJason233.chimera.network.nodetest.common.GlobalTestExecution
import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.feature.nodegrouping.domain.NodeTestResultSortMode
import io.github.HanJason233.chimera.feature.nodegrouping.domain.SpeedTestCandidateSelector
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestResult
import io.github.HanJason233.chimera.service.ServiceManager

/** 协调首页、当前节点和分组手动测速流程，并维护测速界面状态。 */
internal class NodeTestCoordinator(
    private val applicationContext: android.content.Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val nodeDao: suspend () -> NodeDao,
    private val currentNodeListSnapshot: suspend () -> List<Node>,
    private val activeConnectionNode: kotlinx.coroutines.flow.StateFlow<Node?>,
    private val homeSpeedTestSettings: kotlinx.coroutines.flow.StateFlow<SpeedTestSettings>,
    private val groupManualSpeedTestSettings: kotlinx.coroutines.flow.StateFlow<SpeedTestSettings>,
    private val protocolCoreSettings: kotlinx.coroutines.flow.StateFlow<ProtocolCoreSettings>,
    private val proxyMode: kotlinx.coroutines.flow.StateFlow<ProxyMode>,
    private val hysteriaUpMbps: kotlinx.coroutines.flow.StateFlow<Int>,
    private val hysteriaDownMbps: kotlinx.coroutines.flow.StateFlow<Int>,
    private val startSelectedOrFirstConnection: () -> Unit,
    private val bandwidthTest: suspend (Node, Long, String) -> Float,
    private val executor: NodeTestExecutor,
    private val _isTesting: kotlinx.coroutines.flow.MutableStateFlow<Boolean>,
    private val _speedTestProgress: kotlinx.coroutines.flow.MutableStateFlow<SpeedTestProgress?>,
    private val _unlockTestState: kotlinx.coroutines.flow.MutableStateFlow<UnlockTestUiState>,
    private val _linkTypeTestResult: kotlinx.coroutines.flow.MutableStateFlow<LinkTypeTestResult>,
    private val _nodeTestResultSortMode: kotlinx.coroutines.flow.MutableStateFlow<NodeTestResultSortMode>,
    private val _error: kotlinx.coroutines.flow.MutableStateFlow<String?>
) {
    private val candidateTestRunner = HomeCandidateTestRunner(
        applicationContext = applicationContext,
        nodeDao = nodeDao,
        protocolCoreSettings = protocolCoreSettings,
        hysteriaUpMbps = hysteriaUpMbps,
        hysteriaDownMbps = hysteriaDownMbps,
        updateProgress = ::updateSpeedTestProgress
    )
    private var activeTestJob: kotlinx.coroutines.Job? = null

    private suspend fun testNodeDownloadBandwidthMbps(
        node: Node,
        durationMs: Long,
        downloadUrl: String
    ): Float = bandwidthTest(node, durationMs, downloadUrl)

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
        scope.launch {
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
                            executor.internalTestNodes(
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
                            val results = executor.runUrlTestNodes(
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
                            executor.applyUrlTestResults(
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
                            candidateTestRunner.runHomeCandidateFilterTests(
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
                            candidateTestRunner.runHomeCandidateFilterTests(
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
                Log.e("NodeTestCoordinator", "Current node data test failed", error)
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
        scope.launch {
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
                    val tcpingResults = executor.internalTestNodes(
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
                    val urlTestResults = executor.runUrlTestNodes(
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
                    executor.applyUrlTestResults(
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
                        executor.deleteNodes(
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
                    val filterResults = candidateTestRunner.runHomeCandidateFilterTests(
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
                    "NodeTestCoordinator",
                    "Speed test filters retained ${eligibleNodes.size} routes; " +
                        "selected ${bestNode.getDisplayName()}"
                )
                ServiceManager.startVpn(applicationContext, bestNode, proxyMode.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("NodeTestCoordinator", "Speed test settings failed", e)
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
        scope.launch {
            try {
                val clearedCount = nodeDao().clearNodeTestResults(groupNodes.map(Node::id))
                onComplete("已清理 $clearedCount 个节点的测试数据", true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("NodeTestCoordinator", "Failed to clear node test results", e)
                onComplete("清理测试数据失败：${e.message ?: "未知错误"}", false)
            }
        }
    }

    /** 按手动测速设置执行当前线路详情页中的单阶段测试。 */
    fun runManualGroupTest(groupNodes: List<Node>, stage: ManualGroupTestStage) {
        scope.launch {
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
                        executor.internalTestNodes(
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
                        val results = executor.runUrlTestNodes(
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
                        executor.applyUrlTestResults(
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
                            executor.deleteNodes(
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
                        candidateTestRunner.runHomeCandidateFilterTests(
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
                        candidateTestRunner.runHomeCandidateFilterTests(
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
                Log.e("NodeTestCoordinator", "Manual group test failed: $stage", error)
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


    fun cancelActiveTest() {
        activeTestJob?.takeIf { it.isActive }?.cancel(
            CancellationException("Tests forcibly interrupted by user")
        )
    }
}
