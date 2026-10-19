package io.github.HanJason233.chimera.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.HanJason233.chimera.data.repository.RoutingAssetDownloadResult
import io.github.HanJason233.chimera.data.repository.RoutingAssetFileInfo
import io.github.HanJason233.chimera.data.repository.RoutingAssetRecord
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.data.repository.RoutingAssetsSnapshot
import io.github.HanJason233.chimera.util.RuleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RoutingAssetsUiState(
    val snapshot: RoutingAssetsSnapshot,
    val loading: Boolean = false,
    val singBoxRuleSets: Map<String, RoutingAssetFileInfo> = emptyMap(),
    val singBoxAutoUpdateEnabled: Boolean = true
)

internal class RoutingAssetsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = RoutingAssetsRepository(application)
    private val _state = MutableStateFlow(
        RoutingAssetsUiState(
            snapshot = repository.load(),
            loading = true,
            singBoxRuleSets = RuleManager.ruleSetFileInfo(application),
            singBoxAutoUpdateEnabled = RuleManager.isAutoUpdateEnabled(application)
        )
    )
    val state: StateFlow<RoutingAssetsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.initializeBundledAssets()
                runCatching { RuleManager.ensureRuleSets(app) }
            }
            _state.value = loadState()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = loadState()
        }
    }

    fun saveSource(source: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSource(source)
            _state.value = _state.value.copy(snapshot = repository.load())
        }
    }

    fun saveAsset(id: String?, name: String, url: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { repository.saveAsset(id, name, url) }
            refresh()
            onResult(error)
        }
    }

    fun importLocalFile(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { repository.importLocalFile(uri) }
            refresh()
            onResult(error)
        }
    }

    fun removeAsset(asset: RoutingAssetRecord) {
        viewModelScope.launch {
            repository.removeAsset(asset.id, asset.name)
            _state.value = loadState()
        }
    }

    fun setSingBoxAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RuleManager.setAutoUpdateEnabled(app, enabled) }
            _state.value = _state.value.copy(singBoxAutoUpdateEnabled = enabled)
        }
    }

    fun downloadAll(onResult: (RoutingAssetDownloadResult) -> Unit) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val xrayResult = repository.downloadAll()
                val singBoxResult = RuleManager.updateRuleSets(app, force = true)
                RoutingAssetDownloadResult(
                    downloaded = xrayResult.downloaded + singBoxResult.updated.size,
                    failed = (xrayResult.failed + singBoxResult.failed).distinct()
                )
            }
            _state.value = loadState()
            onResult(result)
        }
    }

    private suspend fun loadState(): RoutingAssetsUiState = withContext(Dispatchers.IO) {
        RoutingAssetsUiState(
            snapshot = repository.load(),
            singBoxRuleSets = RuleManager.ruleSetFileInfo(app),
            singBoxAutoUpdateEnabled = RuleManager.isAutoUpdateEnabled(app)
        )
    }
}
