package xyz.a202132.app.feature.perappinbound.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.R
import xyz.a202132.app.data.model.PerAppInboundSettings
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.Socks5HttpProbe
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import java.text.Collator
import java.util.concurrent.TimeUnit

data class InstalledInboundApp(
    val label: String,
    val packageName: String,
    val isSystemApp: Boolean
)

class PerAppInboundViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val settingsMutex = Mutex()
    private val _allApps = MutableStateFlow<List<InstalledInboundApp>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private var loadJob: Job? = null
    private var appsLoaded = false

    val settings = settingsRepository.perAppInboundSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PerAppInboundSettings()
    )
    val isLoading = _isLoading.asStateFlow()
    val messages = _messages.asSharedFlow()
    val displayedApps = combine(_allApps, _query, settings) { apps, query, perAppSettings ->
        val collator = Collator.getInstance()
        apps.asSequence()
            .filter {
                query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith { first, second ->
                val firstSelected = first.packageName in perAppSettings.selectedPackages
                val secondSelected = second.packageName in perAppSettings.selectedPackages
                when {
                    firstSelected && !secondSelected -> -1
                    !firstSelected && secondSelected -> 1
                    first.isSystemApp && !second.isSystemApp -> 1
                    !first.isSystemApp && second.isSystemApp -> -1
                    else -> collator.compare(first.label, second.label)
                }
            }
            .toList()
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    init {
        loadApps()
    }

    fun filterApps(query: String) {
        _query.value = query
    }

    fun setPerAppInboundEnabled(enabled: Boolean) {
        updateSettings { it.copy(enabled = enabled) }
    }

    fun setBypassMode(enabled: Boolean) {
        updateSettings { it.copy(bypassMode = enabled) }
    }

    fun toggle(packageName: String) {
        updateSettings { current ->
            val selected = current.selectedPackages.toMutableSet()
            if (!selected.add(packageName)) selected.remove(packageName)
            current.copy(selectedPackages = selected)
        }
    }

    fun selectAll() {
        val packageNames = displayedApps.value.map { it.packageName }
        if (packageNames.isEmpty()) return
        updateSettings { current ->
            val allSelected = packageNames.all { it in current.selectedPackages }
            val selected = current.selectedPackages.toMutableSet()
            packageNames.forEach { packageName ->
                if (allSelected) selected.remove(packageName) else selected.add(packageName)
            }
            current.copy(enabled = true, selectedPackages = selected)
        }
    }

    fun invertSelection() {
        val packageNames = displayedApps.value.map { it.packageName }
        if (packageNames.isEmpty()) return
        updateSettings { current ->
            val selected = current.selectedPackages.toMutableSet()
            packageNames.forEach { packageName ->
                if (!selected.add(packageName)) selected.remove(packageName)
            }
            current.copy(enabled = true, selectedPackages = selected)
        }
    }

    fun selectProxyAppsAutomatically() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val proxyList = downloadProxyAppList()
                val packages = _allApps.value.map { it.packageName }
                val current = settingsRepository.perAppInboundSettings.first()
                val selected = withContext(Dispatchers.Default) {
                    selectFromProxyList(packages, proxyList, current.bypassMode, forceGoogleApps = true)
                }
                updateSettings { it.copy(enabled = true, selectedPackages = selected) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Automatic per-app inbound selection failed", error)
                _messages.emit(R.string.per_app_inbound_auto_select_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importProxyAppsFromClipboard() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val content = withContext(Dispatchers.Main.immediate) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
            }
            if (content.isNullOrBlank()) {
                _messages.emit(R.string.per_app_inbound_import_empty)
                return@launch
            }
            val current = settingsRepository.perAppInboundSettings.first()
            val packages = _allApps.value.map { it.packageName }
            val selected = withContext(Dispatchers.Default) {
                selectFromProxyList(packages, content, current.bypassMode, forceGoogleApps = false)
            }
            updateSettings { it.copy(enabled = true, selectedPackages = selected) }
        }
    }

    fun exportProxyAppsToClipboard() {
        viewModelScope.launch {
            val current = settingsRepository.perAppInboundSettings.first()
            val content = buildString {
                append(current.bypassMode)
                current.selectedPackages.sorted().forEach { packageName ->
                    append('\n')
                    append(packageName)
                }
            }
            val context = getApplication<Application>()
            withContext(Dispatchers.Main.immediate) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.per_app_inbound_clipboard_label), content)
                )
            }
            _messages.emit(R.string.per_app_inbound_exported)
        }
    }

    private fun loadApps() {
        if (appsLoaded || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val context = getApplication<Application>()
                val apps = withContext(Dispatchers.IO) {
                    val packageManager = context.packageManager
                    val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getInstalledApplications(
                            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    }
                    installedApps.mapNotNull { info ->
                        runCatching {
                            InstalledInboundApp(
                                label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                                packageName = info.packageName,
                                isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                            )
                        }.getOrNull()
                    }
                }
                _allApps.value = apps
                appsLoaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Installed app list load failed", error)
                _messages.emit(R.string.per_app_inbound_load_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun downloadProxyAppList(): String = withContext(Dispatchers.IO) {
        // 先直连获取最新名单，网络不可达时再尝试当前 VPN 核心的 SOCKS 入站。
        var directFailure: Exception? = null
        val directContent = try {
            val request = Request.Builder()
                .url(PROXY_APP_LIST_URL)
                .header("Accept", "text/plain")
                .header("User-Agent", "Firefly-Android")
                .build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Proxy app list request failed: ${response.code}" }
                response.body?.string().orEmpty().also {
                    check(it.isNotBlank()) { "Proxy app list is empty" }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            directFailure = error
            Log.w(TAG, "Direct download of automatic app list failed", error)
            null
        }
        directContent?.let { return@withContext it }

        val activeEndpoint = BoxVpnService.activeSocksEndpoint()
        var inboundFailure: Exception? = null
        if (activeEndpoint != null) {
            val inboundContent = try {
                val response = Socks5HttpProbe.fetch(
                    endpoint = activeEndpoint,
                    targetUrl = PROXY_APP_LIST_URL,
                    timeoutMs = PROXY_APP_LIST_TIMEOUT_MS,
                    headers = mapOf(
                        "Accept" to "text/plain",
                        "User-Agent" to "Firefly-Android"
                    ),
                    maxBodyBytes = MAX_PROXY_APP_LIST_BYTES
                )
                check(response.statusCode in 200..299) {
                    "Proxy app list request failed through the active inbound: ${response.statusCode}"
                }
                response.body.also { check(it.isNotBlank()) { "Proxy app list is empty" } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                inboundFailure = error
                directFailure?.let(error::addSuppressed)
                Log.w(TAG, "Download of automatic app list through active inbound failed", error)
                null
            }
            inboundContent?.let { return@withContext it }
        }

        // 直连和当前入站都不可用时，使用随应用打包的名单，保证离线时也能自动选择。
        val bundledContent = try {
            getApplication<Application>().assets.open(PROXY_APP_LIST_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText().trim() }
                .also { check(it.isNotBlank()) { "Bundled proxy app list is empty" } }
        } catch (error: Exception) {
            inboundFailure?.let(error::addSuppressed) ?: directFailure?.let(error::addSuppressed)
            throw error
        }
        Log.w(TAG, "Using bundled app list because remote downloads failed", inboundFailure ?: directFailure)
        bundledContent
    }

    private fun updateSettings(transform: (PerAppInboundSettings) -> PerAppInboundSettings) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.perAppInboundSettings.first()
                val updated = transform(current)
                if (updated == current) return@withLock
                settingsRepository.setPerAppInboundSettings(updated)
                restartVpnIfConnected()
            }
        }
    }

    private suspend fun restartVpnIfConnected() {
        if (ServiceManager.vpnState.value != VpnState.CONNECTED) return
        val node = ServiceManager.currentNode.value ?: return
        val mode = settingsRepository.proxyMode.first()
        ServiceManager.startVpn(getApplication(), node, mode)
    }

    private fun selectFromProxyList(
        packageNames: Collection<String>,
        proxyAppList: String,
        bypassMode: Boolean,
        forceGoogleApps: Boolean
    ): Set<String> = buildSet(packageNames.size) {
        packageNames.forEach { packageName ->
            val shouldProxy = if (forceGoogleApps && packageName.startsWith("com.google")) {
                packageName != "com.google.android.webview"
            } else {
                proxyAppList.contains(packageName)
            }
            if (if (bypassMode) !shouldProxy else shouldProxy) add(packageName)
        }
    }

    private companion object {
        const val TAG = "PerAppInbound"
        const val PROXY_APP_LIST_URL =
            "https://raw.githubusercontent.com/2dust/androidpackagenamelist/master/proxy.txt"
        const val PROXY_APP_LIST_ASSET = "proxy_package_name"
        const val PROXY_APP_LIST_TIMEOUT_MS = 8_000L
        const val MAX_PROXY_APP_LIST_BYTES = 1_048_576
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
