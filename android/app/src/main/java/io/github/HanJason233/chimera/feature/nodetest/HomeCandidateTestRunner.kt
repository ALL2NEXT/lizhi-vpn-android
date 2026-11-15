package io.github.HanJason233.chimera.feature.nodetest

import io.github.HanJason233.chimera.feature.nodetest.data.HomeTestResultCodec
import io.github.HanJason233.chimera.feature.nodetest.model.UnlockTestUiState
import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.feature.nodegrouping.domain.SpeedTestCandidateSelector
import io.github.HanJason233.chimera.network.nodetest.common.NodeTestProxyManager
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
import io.github.HanJason233.chimera.service.BoxVpnService
import io.github.HanJason233.chimera.util.NetworkUtils
import java.net.InetAddress

internal data class HomeCandidateFilterResult(
    val node: Node,
    val linkTypePassed: Boolean,
    val unlockTestsPassed: Boolean
)

/** 通过临时代理检测候选节点的链路类型和服务解锁能力。 */
internal class HomeCandidateTestRunner(
    private val applicationContext: android.content.Context,
    private val nodeDao: suspend () -> NodeDao,
    private val protocolCoreSettings: kotlinx.coroutines.flow.StateFlow<ProtocolCoreSettings>,
    private val hysteriaUpMbps: kotlinx.coroutines.flow.StateFlow<Int>,
    private val hysteriaDownMbps: kotlinx.coroutines.flow.StateFlow<Int>,
    private val onNodeTestingChanged: (String, Boolean) -> Unit,
    private val updateProgress: (String, String, Int, Int, Boolean) -> Unit
) {
    private fun updateSpeedTestProgress(
        title: String,
        stage: String,
        completed: Int = 0,
        total: Int = 0,
        isManualGroupTest: Boolean = false
    ) {
        updateProgress(
            title,
            stage,
            completed.coerceAtLeast(0),
            total.coerceAtLeast(0),
            isManualGroupTest
        )
    }

    internal suspend fun runHomeCandidateFilterTests(
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
                    onNodeTestingChanged(node.id, true)
                    var proxySession: NodeTestProxyManager.Session? = null
                    try {
                        val session = NodeTestProxyManager.createSession(
                            context = applicationContext,
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
                        Log.w("NodeTestCoordinator", "Pre-VPN candidate test failed for ${node.id}", error)
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
                        // 结果已持久化即可展示，代理清理不占用列表的测试中状态。
                        onNodeTestingChanged(node.id, false)
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
            ?: NetworkUtils.physicalNetwork(applicationContext)
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
        }.onFailure { error -> Log.w("NodeTestCoordinator", "Failed to persist link type result", error) }
    }


    private suspend fun persistUnlockTestResult(nodeId: String, state: UnlockTestUiState) {
        if (state.isRunning || state.items.isEmpty()) return
        runCatching {
            nodeDao().saveUnlockTestResult(nodeId, HomeTestResultCodec.encodeUnlockTests(state))
        }.onFailure { error -> Log.w("NodeTestCoordinator", "Failed to persist IP test results", error) }
    }

    private companion object {
        const val PRE_VPN_TEST_SESSION_START_TIMEOUT_MS = 30_000L
    }
}
