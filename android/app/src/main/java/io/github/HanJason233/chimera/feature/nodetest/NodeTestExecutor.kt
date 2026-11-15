package io.github.HanJason233.chimera.feature.nodetest

import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.data.local.NodeLatencyUpdate
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.feature.nodegrouping.domain.NodeTestResultSortMode
import io.github.HanJason233.chimera.network.nodetest.latency.LatencyTester

/** 封装 TCPing、URL Test、超时清理和测试结果持久化等底层操作。 */
internal class NodeTestExecutor(
    private val applicationContext: android.content.Context,
    private val nodeDao: suspend () -> NodeDao,
    private val settingsRepository: SettingsRepository,
    private val activeConnectionNode: kotlinx.coroutines.flow.StateFlow<Node?>,
    private val latencyTester: LatencyTester,
    private val tcpingConcurrency: kotlinx.coroutines.flow.StateFlow<Int>,
    private val tcpingTestTimeoutMs: kotlinx.coroutines.flow.StateFlow<Long>,
    private val hysteriaUpMbps: kotlinx.coroutines.flow.StateFlow<Int>,
    private val hysteriaDownMbps: kotlinx.coroutines.flow.StateFlow<Int>,
    private val protocolCoreSettings: kotlinx.coroutines.flow.StateFlow<ProtocolCoreSettings>,
    private val onNodeTestingChanged: (String, Boolean) -> Unit,
    private val _lastTestResultSortMode: kotlinx.coroutines.flow.MutableStateFlow<NodeTestResultSortMode>,
    private val _error: kotlinx.coroutines.flow.MutableStateFlow<String?>
) {
    internal suspend fun runUrlTestNodes(
        nodes: List<Node>,
        concurrency: Int,
        warmupTimeoutMs: Long,
        measurementTimeoutMs: Long,
        targetUrl: String,
        fallbackUrl: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        val batch = io.github.HanJason233.chimera.network.nodetest.latency.UrlTestManager.start(
            applicationContext, nodes, hysteriaUpMbps.value, hysteriaDownMbps.value,
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
                onProgress = onProgress,
                onNodeTestingChanged = onNodeTestingChanged,
                onNodeResult = ::persistUrlTestResult
            )
            val testedById = testedResults.associateBy(LatencyTester.LatencyResult::nodeId)
            val results = nodes.map { node ->
                testedById[node.id] ?: LatencyTester.LatencyResult(
                    nodeId = node.id,
                    latency = -2,
                    isAvailable = false
                )
            }
            results.filter { it.nodeId !in testedById }.forEach { persistUrlTestResult(it) }
            Log.d("NodeTestExecutor", "Got ${results.size} URL test results")
            return results
        } finally {
            withContext(NonCancellable) { io.github.HanJason233.chimera.network.nodetest.latency.UrlTestManager.stop() }
        }
    }

    private suspend fun persistUrlTestResult(result: LatencyTester.LatencyResult) {
        val updatedRows = nodeDao().updateUrlTestResults(
            listOf(NodeLatencyUpdate(result.nodeId, result.latency, result.isAvailable)),
            System.currentTimeMillis()
        )
        if (updatedRows != 1) {
            Log.w("NodeTestExecutor", "URL test result was not applied: ${result.nodeId}")
        }
    }

    internal suspend fun finishUrlTestResults(
        results: List<LatencyTester.LatencyResult>,
        autoDeleteTimeoutNodes: Boolean,
        rememberLastTestResultMode: Boolean = true
    ) {
        // 结果已在节点完成时写入；整批结束后只处理排序指标和自动删除。
        if (rememberLastTestResultMode) {
            _lastTestResultSortMode.value = NodeTestResultSortMode.URL_TEST_LATENCY
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

    internal suspend fun deleteNodes(nodeIds: List<String>, reason: String) {
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

    internal suspend fun internalTestNodes(
        currentNodes: List<Node>,
        concurrency: Int = tcpingConcurrency.value,
        timeoutMs: Long = tcpingTestTimeoutMs.value,
        autoDeleteTimeoutNodes: Boolean,
        rememberLastTestResultMode: Boolean = true,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyTester.LatencyResult> {
        if (currentNodes.isEmpty()) return emptyList()

        Log.d("NodeTestExecutor", "Testing ${currentNodes.size} nodes")

        // 统一使用 Socket 测试 (直接连接节点服务器测试可达性)
        // 即使 VPN 运行中也可以工作，因为测试的是节点服务器本身
        val results = latencyTester.testAllNodes(
            nodes = currentNodes,
            timeoutMs = timeoutMs,
            concurrency = concurrency,
            onProgress = onProgress,
            onNodeTestingChanged = onNodeTestingChanged,
            onNodeResult = { result ->
                nodeDao().updateTcpingResults(
                    listOf(NodeLatencyUpdate(result.nodeId, result.latency, result.isAvailable)),
                    System.currentTimeMillis()
                )
            }
        )
        Log.d("NodeTestExecutor", "Got ${results.size} test results")

        if (rememberLastTestResultMode) {
            _lastTestResultSortMode.value = NodeTestResultSortMode.TCPING_LATENCY
        }
        deleteTimedOutNodes(results, isTcping = true, enabled = autoDeleteTimeoutNodes)
        return results
    }


}
