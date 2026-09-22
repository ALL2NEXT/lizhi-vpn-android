package xyz.a202132.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.a202132.app.network.UnlockTestCatalog
import xyz.a202132.app.network.UnlockTestClassifier
import xyz.a202132.app.network.UnlockTestResult
import xyz.a202132.app.network.UnlockTestRunner
import xyz.a202132.app.network.UnlockTestState
import xyz.a202132.app.service.BoxVpnService

internal data class UnlockTestUiState(
    val items: List<UnlockTestResult> = UnlockTestCatalog.initialResults(),
    val isRunning: Boolean = false,
    val message: String = "连接 VPN 后，检测请求会通过当前节点发出。"
) {
    val completedCount: Int
        get() = items.count { it.state !in setOf(UnlockTestState.IDLE, UnlockTestState.TESTING) }
}

internal class UnlockTestViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UnlockTestUiState())
    val uiState = _uiState.asStateFlow()
    private var testJob: Job? = null

    fun startAllTests() {
        if (testJob?.isActive == true) return
        val endpoint = BoxVpnService.activeSocksEndpoint()
        if (endpoint == null) {
            _uiState.value = UnlockTestUiState(
                items = UnlockTestCatalog.definitions.map { definition ->
                    UnlockTestResult(
                        definition = definition,
                        state = UnlockTestState.NOT_CONNECTED,
                        statusText = "未连接",
                        description = "请先连接 VPN，再检测当前节点。"
                    )
                },
                message = "VPN 未连接，无法通过节点执行检测。"
            )
            return
        }

        _uiState.value = UnlockTestUiState(
            items = UnlockTestCatalog.definitions.map { definition ->
                UnlockTestResult(
                    definition = definition,
                    state = UnlockTestState.TESTING,
                    statusText = "检测中…",
                    description = definition.testingDescription
                )
            },
            isRunning = true,
            message = "正在通过当前 VPN 节点检测，最多同时执行 4 项。"
        )

        testJob = viewModelScope.launch {
            try {
                val semaphore = Semaphore(MAX_CONCURRENT_TESTS)
                UnlockTestRunner(endpoint).use { runner ->
                    coroutineScope {
                        UnlockTestCatalog.definitions.map { definition ->
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
                                _uiState.update { state ->
                                    state.copy(items = state.items.map {
                                        if (it.definition.id == definition.id) result else it
                                    })
                                }
                            }
                        }.awaitAll()
                    }
                }
                _uiState.update { state ->
                    val usable = state.items.count {
                        it.state == UnlockTestState.UNLOCKED || it.state == UnlockTestState.PARTIAL
                    }
                    state.copy(
                        isRunning = false,
                        message = "检测完成：${state.items.size} 项中 $usable 项可用或部分可用。"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        items = state.items.map { item ->
                            if (item.state == UnlockTestState.TESTING) {
                                UnlockTestClassifier.error(item.definition, throwable)
                            } else {
                                item
                            }
                        },
                        isRunning = false,
                        message = "检测未能完成，请稍后重试。"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        testJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_CONCURRENT_TESTS = 4
    }
}
