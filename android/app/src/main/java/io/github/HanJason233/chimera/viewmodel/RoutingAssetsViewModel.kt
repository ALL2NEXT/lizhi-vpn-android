package io.github.HanJason233.chimera.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.HanJason233.chimera.data.repository.RoutingAssetDownloadResult
import io.github.HanJason233.chimera.data.repository.RoutingAssetRecord
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.data.repository.RoutingAssetsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RoutingAssetsUiState(
    val snapshot: RoutingAssetsSnapshot,
    val loading: Boolean = false
)

internal class RoutingAssetsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RoutingAssetsRepository(application)
    private val _state = MutableStateFlow(RoutingAssetsUiState(repository.load()))
    val state: StateFlow<RoutingAssetsUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = repository.load()
            _state.value = _state.value.copy(snapshot = snapshot)
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
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeAsset(asset.id, asset.name)
            _state.value = _state.value.copy(snapshot = repository.load())
        }
    }

    fun downloadAll(onResult: (RoutingAssetDownloadResult) -> Unit) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.downloadAll() }
            _state.value = RoutingAssetsUiState(
                snapshot = withContext(Dispatchers.IO) { repository.load() },
                loading = false
            )
            onResult(result)
        }
    }
}
