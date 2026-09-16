package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.util.ProxyAppDetector

/** 应用信息数据类。 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

/** 分应用代理 ViewModel。 */
class PerAppProxyViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "PerAppProxyViewModel"
    }

    private val settingsRepository = SettingsRepository(application)
    private val packageManager = application.packageManager
    private var refreshJob: Job? = null

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAutoDetecting = MutableStateFlow(false)
    val isAutoDetecting: StateFlow<Boolean> = _isAutoDetecting.asStateFlow()

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val searchQuery = MutableStateFlow("")
    val showSystemApps = MutableStateFlow(false)

    val isEnabled: StateFlow<Boolean> = settingsRepository.perAppProxyEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val selectedPackages: StateFlow<Set<String>> = settingsRepository.selectedPackages
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val _hasChanges = MutableStateFlow(false)
    val hasChanges: StateFlow<Boolean> = _hasChanges.asStateFlow()

    val filteredApps: StateFlow<List<AppInfo>> = combine(
        _allApps,
        searchQuery,
        showSystemApps
    ) { apps, query, showSystem ->
        apps.filter { app ->
            val passSystemFilter = showSystem || !app.isSystemApp
            val passSearchFilter = if (query.isBlank()) {
                true
            } else {
                app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            passSystemFilter && passSearchFilter
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val selectedCount: StateFlow<Int> = selectedPackages
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _isPermissionDenied = MutableStateFlow(false)
    val isPermissionDenied: StateFlow<Boolean> = _isPermissionDenied.asStateFlow()

    private val _scrollToTopEvent = Channel<Unit>(Channel.BUFFERED)
    val scrollToTopEvent = _scrollToTopEvent.receiveAsFlow()

    // 自动识别完成事件，界面用 Toast 告知用户本次新增的应用数量。
    private val _autoDetectResult = Channel<Int>(Channel.BUFFERED)
    val autoDetectResult = _autoDetectResult.receiveAsFlow()

    init {
        refreshApps()
    }

    /** 刷新已安装应用列表。 */
    fun refreshApps() {
        if (refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            _isLoading.value = true

            val apps = withContext(Dispatchers.IO) {
                try {
                    val installedPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    val selfPackage = getApplication<Application>().packageName
                    val currentSelected = settingsRepository.selectedPackages.first()

                    val parsedApps = installedPackages
                        .filter { it.packageName != selfPackage }
                        .map { appInfo ->
                            val appName = try {
                                packageManager.getApplicationLabel(appInfo).toString()
                            } catch (_: Exception) {
                                appInfo.packageName
                            }

                            val isPreinstalled = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            val isUpdatedPreinstalled =
                                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                            val isSystemApp = isPreinstalled && !isUpdatedPreinstalled

                            AppInfo(
                                packageName = appInfo.packageName,
                                appName = appName,
                                isSystemApp = isSystemApp
                            )
                        }

                    sortApps(parsedApps, currentSelected)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load installed apps", e)
                    emptyList()
                }
            }

            _allApps.value = apps
            _isLoading.value = false
            _isPermissionDenied.value = apps.size < 5

            val userApps = apps.count { !it.isSystemApp }
            val systemApps = apps.count { it.isSystemApp }
            Log.d(TAG, "Loaded ${apps.size} apps total ($userApps user apps, $systemApps system apps)")

            _scrollToTopEvent.send(Unit)
        }
    }

    private fun sortApps(apps: List<AppInfo>, selected: Set<String>): List<AppInfo> {
        return apps.sortedWith(
            compareByDescending<AppInfo> { it.packageName in selected }
                .thenBy { it.appName }
        )
    }

    /** 设置是否启用分应用代理。 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPerAppProxyEnabled(enabled)
            _hasChanges.value = true
        }
    }

    /** 切换应用的代理选择状态。 */
    fun togglePackage(packageName: String) {
        viewModelScope.launch {
            val current = selectedPackages.value.toMutableSet()
            if (packageName in current) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            settingsRepository.setSelectedPackages(current)
            _hasChanges.value = true
        }
    }

    /**
     * 自动识别常见的国际网络应用并加入代理列表。
     * 已经手动选择的应用会保留，避免自动识别覆盖用户配置。
     */
    fun autoSelectProxyApps() {
        if (_isLoading.value || _isAutoDetecting.value) return

        viewModelScope.launch {
            _isAutoDetecting.value = true
            try {
                val candidates = withContext(Dispatchers.Default) {
                    _allApps.value
                        .filter { app ->
                            ProxyAppDetector.isProxyCandidate(
                                packageName = app.packageName,
                                appName = app.appName,
                                isSystemApp = app.isSystemApp
                            )
                        }
                        .map { it.packageName }
                        .toSet()
                }
                val current = selectedPackages.value
                val newlySelected = candidates - current

                if (newlySelected.isNotEmpty()) {
                    val updated = current + newlySelected
                    settingsRepository.setSelectedPackages(updated)
                    _hasChanges.value = true
                    _allApps.value = sortApps(_allApps.value, updated)
                    _scrollToTopEvent.send(Unit)
                }

                _autoDetectResult.send(newlySelected.size)
            } finally {
                _isAutoDetecting.value = false
            }
        }
    }
}
