package io.github.HanJason233.chimera.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.HanJason233.chimera.security.detection.NativeSecurityDetector
import io.github.HanJason233.chimera.security.detection.SecurityInspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SecuritySelfCheckUiState(
    val checking: Boolean = false,
    val inspection: SecurityInspection? = null
)

internal class SecuritySelfCheckViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SecuritySelfCheckUiState())
    val state = mutableState.asStateFlow()

    fun refresh() {
        if (mutableState.value.checking) return
        mutableState.value = mutableState.value.copy(checking = true)
        viewModelScope.launch {
            try {
                val inspection = NativeSecurityDetector.inspectEnvironment(getApplication())
                mutableState.value = SecuritySelfCheckUiState(inspection = inspection)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = SecuritySelfCheckUiState(
                    inspection = SecurityInspection(setOf("native_scan_error"), System.currentTimeMillis(), 0)
                )
            }
        }
    }
}
