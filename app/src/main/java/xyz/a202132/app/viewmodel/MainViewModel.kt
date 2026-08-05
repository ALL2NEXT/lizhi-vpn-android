package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import io.nekohasekai.libbox.Libbox
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.network.NodeTestProxyManager
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.util.RuntimeLog
import xyz.a202132.app.util.SingBoxConfigGenerator
import xyz.a202132.app.util.CryptoUtils
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.UUID

enum class NodeImportResult {
    IMPORTED,
    DUPLICATE,
    INVALID
}

data class ScheduledNodeUpdateSettings(
    val enabled: Boolean = false,
    val hours: Int = 0,
    val minutes: Int = 30,
    val nodeAutoReconnect: Boolean = false,
    val toastEnabled: Boolean = true,
    val scheduledOverriddenByNotice: Boolean = false,
    val reconnectOverriddenByNotice: Boolean = false,
    val toastOverriddenByNotice: Boolean = false
) {
    val intervalMillis: Long
        get() = ((hours * 60L) + minutes).coerceAtLeast(1L) * 60_000L

    val intervalLabel: String
        get() = buildList {
            if (hours > 0) add("${hours}小时")
            if (minutes > 0) add("${minutes}分钟")
        }.ifEmpty { listOf("1分钟") }.joinToString("")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tag = "MainViewModel"
    private val database = AppDatabase.getInstance(application)
    private val nodeDao = database.nodeDao()
    private val settingsRepository = SettingsRepository(application)
    private val subscriptionParser = SubscriptionParser()
    private val configGenerator = SingBoxConfigGenerator()
    private val latencyTester = LatencyTester()
    private val gson = Gson()

    private data class NodeIpInfoAttemptResult(
        val result: Result<NodeIpInfo>,
        val shouldRetry: Boolean
    )
    
    // 节流控制
    private val THROTTLE_INTERVAL = 5000L // 5秒节流间隔
    private var lastFetchNodesTime = 0L
    private var lastBackupSwitchTime = 0L
    private var lastCheckUpdateTime = 0L
    // 缓存最新拉取的节点，避免 Room Flow 延迟导致后续操作使用旧节点
    private var lastFetchedNodes: List<Node>? = null
    private val lastNodeFetchFinishedAt = MutableStateFlow(0L)
    private var lastSuccessfulNodeFetchUsedBackup: Boolean? = null
    
    // UI状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()
    
    // 测试类型标签 (用于 UI 显示)
    private val _testingLabel = MutableStateFlow<String?>(null)
    val testingLabel = _testingLabel.asStateFlow()
    
    // 过滤不可用节点
    private val _filterUnavailable = MutableStateFlow(false)
    val filterUnavailable = _filterUnavailable.asStateFlow()
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    // 持久通知配置（与对话框可见性无关）
    private val _noticeConfig = MutableStateFlow<NoticeInfo?>(null)
    val noticeConfig = _noticeConfig.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    private val _startupUpdateCheckCompleted =
        MutableStateFlow(AppConfig.STARTUP_SPLASH_DURATION_SECONDS <= 0)
    val startupUpdateCheckCompleted = _startupUpdateCheckCompleted.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    private val _infoDialogMessage = MutableStateFlow<String?>(null)
    val infoDialogMessage = _infoDialogMessage.asStateFlow()
    // 仅在本次 APP 进程存活期间有效，不写入 DataStore。
    private val _skipFavoriteRemovalConfirmation = MutableStateFlow(false)
    val skipFavoriteRemovalConfirmation = _skipFavoriteRemovalConfirmation.asStateFlow()
    
    val nodeListCategory = settingsRepository.nodeListCategory.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        NodeListCategory.PRIMARY
    )

    val favoriteSourceNodeIds = nodeDao.getFavoriteSourceNodeIds()
        .map { it.filterNotNull().toSet() }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptySet()
        )

    private val subscriptionNodes = combine(
        nodeDao.getSubscriptionNodes(),
        _filterUnavailable
    ) { list, filterOut ->
        sortNodesForList(list, filterOut)
    }

    private val favoriteNodes = combine(
        nodeDao.getFavoriteNodes(),
        _filterUnavailable
    ) { list, filterOut ->
        sortNodesForList(list, filterOut)
    }

    // Data
    val nodes = combine(
        subscriptionNodes,
        favoriteNodes,
        nodeListCategory
    ) { subscription, favorites, category ->
        if (category == NodeListCategory.FAVORITES) favorites else subscription
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        emptyList()
    )
    
    // 初始化完成标志 - 用于防止 UI 闪烁
    private val _isInitialized = MutableStateFlow(false)
    
    val selectedNodeId = combine(
        settingsRepository.selectedNodeId,
        _isInitialized
    ) { nodeId, initialized ->
        // 只有在初始化完成后才发出真实值，否则返回 null
        if (initialized) nodeId else null
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
    
    val bypassLan = settingsRepository.bypassLan.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认开启绕过局域网
    )
    
    val ipv6RoutingMode = settingsRepository.ipv6RoutingMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IPv6RoutingMode.DISABLED
    )
    
    val isUserAgreementAccepted = settingsRepository.isUserAgreementAccepted.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认 true 防止闪烁，init 里会 reset 状态或者第一次启动时读出来是 false
    )

    // 自动化测试设置
    val appThemeMode = settingsRepository.appThemeMode.stateIn(viewModelScope, SharingStarted.Lazily, AppThemeMode.SYSTEM)
    val rememberLastSelectedNodeEnabled = settingsRepository.rememberLastSelectedNodeEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val nodeIpInfoTestOnVpnStart = settingsRepository.nodeIpInfoTestOnVpnStart.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val scheduledNodeUpdateEnabled = settingsRepository.scheduledNodeUpdateEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val scheduledNodeUpdateHours = settingsRepository.scheduledNodeUpdateHours.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val scheduledNodeUpdateMinutes = settingsRepository.scheduledNodeUpdateMinutes.stateIn(viewModelScope, SharingStarted.Lazily, 30)
    val nodeAutoReconnect = settingsRepository.nodeAutoReconnect.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val scheduledNodeUpdateToastEnabled = settingsRepository.scheduledNodeUpdateToastEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    private val localScheduledNodeUpdateSettings = combine(
        scheduledNodeUpdateEnabled,
        scheduledNodeUpdateHours,
        scheduledNodeUpdateMinutes,
        nodeAutoReconnect,
        scheduledNodeUpdateToastEnabled
    ) { enabled, hours, minutes, reconnect, toast ->
        ScheduledNodeUpdateSettings(
            enabled = enabled,
            hours = hours.coerceIn(0, 168),
            minutes = minutes.coerceIn(0, 59),
            nodeAutoReconnect = reconnect,
            toastEnabled = toast
        )
    }
    val effectiveScheduledNodeUpdateSettings = combine(
        localScheduledNodeUpdateSettings,
        noticeConfig
    ) { local, notice ->
        val remoteSchedule = notice?.scheduledNodeUpdate
        val enabled = remoteSchedule?.enabled ?: local.enabled
        val hours = (remoteSchedule?.hours ?: local.hours).coerceIn(0, 168)
        val minutes = (remoteSchedule?.minutes ?: local.minutes).coerceIn(0, 59)
        val normalizedMinutes = if (enabled && hours == 0 && minutes == 0) 1 else minutes
        val remoteReconnect = remoteSchedule?.nodeAutoReconnect ?: notice?.nodeAutoReconnect
        ScheduledNodeUpdateSettings(
            enabled = enabled,
            hours = hours,
            minutes = normalizedMinutes,
            nodeAutoReconnect = remoteReconnect ?: local.nodeAutoReconnect,
            toastEnabled = remoteSchedule?.toastEnabled ?: local.toastEnabled,
            scheduledOverriddenByNotice = remoteSchedule != null,
            reconnectOverriddenByNotice = remoteSchedule?.nodeAutoReconnect != null || notice?.nodeAutoReconnect != null,
            toastOverriddenByNotice = remoteSchedule?.toastEnabled != null
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, ScheduledNodeUpdateSettings())
    val tcpingTestTimeoutMs = settingsRepository.tcpingTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.TCPING_TEST_TIMEOUT)
    val urlTestTimeoutMs = settingsRepository.urlTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.URL_TEST_TIMEOUT)
    val nodeIpInfoTimeoutMs = settingsRepository.nodeIpInfoTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.NODE_IP_INFO_TIMEOUT_MS)
    val speedTestDownloadTimeoutMs = settingsRepository.speedTestDownloadTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS)
    val tcpingConcurrency = settingsRepository.tcpingConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.TCPING_CONCURRENCY)
    val urlTestConcurrency = settingsRepository.urlTestConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.URL_TEST_CONCURRENCY)
    val bandwidthTestConcurrency = settingsRepository.bandwidthTestConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, 1)
    val vpnMtu = settingsRepository.vpnMtu.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.VPN_MTU)
    val vpnState = ServiceManager.vpnState
    
    // 流量统计
    val uploadSpeed = ServiceManager.uploadSpeed
    val downloadSpeed = ServiceManager.downloadSpeed
    val uploadTotal = ServiceManager.uploadTotal
    val downloadTotal = ServiceManager.downloadTotal
    
    val currentNode = combine(nodeDao.getAllNodes(), selectedNodeId) { nodeList, selectedId ->
        nodeList.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    init {
        // 将历史明文 rawLink 迁移为加密存储（一次性后台处理）
        viewModelScope.launch(Dispatchers.IO) {
            migrateRawLinksToEncrypted()
            logRawLinkEncryptionStats("startup_migration")
        }

        // 启动时加载数据
        viewModelScope.launch {
            // 每次启动重置选择状态 (不记住上次选择)
            settingsRepository.setSelectedNodeId(null)
            
            // 标记初始化完成，此时 selectedNodeId 才开始发出真实值
            // 这样 UI 不会看到旧的选中状态闪烁
            _isInitialized.value = true
            
            // 监听用户协议状态，只有同意后才初始化网络请求
            isUserAgreementAccepted.collect { accepted ->
                if (accepted) {
                    val fetchOk = fetchNodesInternal(
                        bypassThrottle = true,
                        fetchingLabel = "请求节点中"
                    )
                    if (!fetchOk) return@collect
                    restoreRememberedLastSelectedNodeIfNeeded(lastFetchedNodes)
                    checkNotice()
                    checkUpdate(isAuto = true, trackStartupSplash = true)
                }
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
        
observeScheduledNodeUpdates()
    }

    private fun observeScheduledNodeUpdates() {
        viewModelScope.launch {
            effectiveScheduledNodeUpdateSettings
                .collectLatest { settings ->
                    if (!settings.enabled) return@collectLatest

                    while (true) {
                        val anchor = lastNodeFetchFinishedAt.value.takeIf { it > 0L }
                            ?: lastNodeFetchFinishedAt.first { it > 0L }
                        val delayMs = (anchor + settings.intervalMillis - System.currentTimeMillis())
                            .coerceAtLeast(0L)
                        delay(delayMs)

                        val latestSettings = effectiveScheduledNodeUpdateSettings.value
                        if (!latestSettings.enabled) continue

                        val latestAnchor = lastNodeFetchFinishedAt.value
                        val remainingMs = latestAnchor + latestSettings.intervalMillis - System.currentTimeMillis()
                        if (latestAnchor > anchor && remainingMs > 0L) {
                            continue
                        }

                        runScheduledNodeUpdate(latestSettings)
                    }
                }
        }
    }

    private suspend fun runScheduledNodeUpdate(settings: ScheduledNodeUpdateSettings) {
        val connectedNodeBeforeUpdate = if (vpnState.value == VpnState.CONNECTED) {
            ServiceManager.currentNode.value ?: currentNode.value
        } else {
            null
        }
        val fetchOk = fetchNodesInternal(
            skipBackupMode = lastSuccessfulNodeFetchUsedBackup == false,
            bypassThrottle = true,
            fetchingLabel = "定时更新节点中",
            allowBackupFallback = false,
            showToast = settings.toastEnabled
        )
        if (!fetchOk) {
            lastNodeFetchFinishedAt.value = System.currentTimeMillis()
            return
        }

        val freshNodes = lastFetchedNodes ?: nodes.value
        if (
            settings.nodeAutoReconnect &&
            connectedNodeBeforeUpdate != null &&
            vpnState.value == VpnState.CONNECTED
        ) {
            val matchedNode = freshNodes.firstOrNull { hasSameEndpoint(it, connectedNodeBeforeUpdate) }
            if (matchedNode != null) {
                settingsRepository.setSelectedNodeId(matchedNode.id)
                ServiceManager.startVpn(getApplication(), matchedNode, proxyMode.value)
                showScheduledNodeUpdateToast(settings, "节点已定时更新并自动重连: ${matchedNode.name}")
            } else {
                showScheduledNodeUpdateToast(settings, "节点已定时更新，当前连接节点已不存在，保持原连接")
            }
        } else {
            showScheduledNodeUpdateToast(settings, "节点已定时更新")
        }
    }

    private fun showScheduledNodeUpdateToast(settings: ScheduledNodeUpdateSettings, message: String) {
        if (settings.toastEnabled) {
            _error.value = message
        }
    }

    private fun hasSameEndpoint(first: Node, second: Node): Boolean {
        return first.server == second.server && first.port == second.port
    }

    
    /**
     * 获取节点列表
     */
    val backupNodeEnabled = settingsRepository.backupNodeEnabled.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        false
    )
    
    /**
     * 获取节点列表
     */
    // 备用节点 URL (本地存储)
    val backupNodeUrl = settingsRepository.backupNodeUrl.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    
    /**
     * 获取节点列表
     */
    /**
     * @param skipBackupMode 强制跳过备用模式逻辑 (用于回退场景，避免 DataStore 异步更新导致重复触发)
     */
    fun fetchNodes(
        skipBackupMode: Boolean = false,
        bypassThrottle: Boolean = false,
        allowWhenTestRunning: Boolean = false,
        allowWhenFetchingInProgress: Boolean = false,
        fetchingLabel: String = "请求节点中"
    ) {
        viewModelScope.launch {
            fetchNodesInternal(
                skipBackupMode = skipBackupMode,
                bypassThrottle = bypassThrottle,
                allowWhenTestRunning = allowWhenTestRunning,
                allowWhenFetchingInProgress = allowWhenFetchingInProgress,
                fetchingLabel = fetchingLabel
            )
        }
    }

    private suspend fun fetchNodesInternal(
        skipBackupMode: Boolean = false,
        bypassThrottle: Boolean = false,
        allowWhenTestRunning: Boolean = false,
        allowWhenFetchingInProgress: Boolean = false,
        fetchingLabel: String = "请求节点中",
        allowBackupFallback: Boolean = true,
        showToast: Boolean = true
    ): Boolean {
        if (!allowWhenTestRunning && GlobalTestExecution.mutex.isLocked) {
            showNodeFetchToast(showToast, GlobalTestExecution.busyHint())
            return false
        }
        if (!allowWhenFetchingInProgress && GlobalTestExecution.isFetching()) {
            showNodeFetchToast(showToast, GlobalTestExecution.fetchingHint())
            return false
        }

        // 节流检查 (内部调用可跳过)
        if (!bypassThrottle) {
            val now = System.currentTimeMillis()
            if (now - lastFetchNodesTime < THROTTLE_INTERVAL) {
                showNodeFetchToast(showToast, "操作过于频繁，请稍后再试")
                return false
            }
            lastFetchNodesTime = now
        }

        GlobalTestExecution.beginFetching(fetchingLabel)
        _isLoading.value = true
        _filterUnavailable.value = false // 刷新时重置过滤
        val shouldUpdateFetchAnchor = true
        try {
            // 1. 检查网络状态
            if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                showNodeFetchToast(showToast, "当前无网络连接，无法获取节点")
                return false
            }

            // 2. 检查是否开启备用节点 (skipBackupMode 时强制为 false)
            val isBackupEnabled = if (skipBackupMode) false else settingsRepository.backupNodeEnabled.first()
            var targetUrl: String? = null

            if (isBackupEnabled) {
                // 每次刷新都获取最新 Notice 配置，确保能检测到远程配置变化
                Log.d(tag, "Backup mode enabled, fetching latest notice config...")
                val notice = fetchNoticeSync()

                if (notice == null) {
                    // Notice 请求失败 (服务器错误等)，触发回退
                    Log.e(tag, "Failed to fetch notice for backup node")
                    if (allowBackupFallback) {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已退回默认节点！")
                        handleBackupFallback()
                    } else {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已保持原连接")
                    }
                    return false
                }

                // 验证备用节点配置有效性
                val backupUrl = notice.backupNodes?.url
                val isValidUrl = backupUrl != null &&
                    backupUrl.isNotBlank() &&
                    (backupUrl.startsWith("http://") || backupUrl.startsWith("https://"))

                if (isValidUrl) {
                    targetUrl = backupUrl
                    Log.d(tag, "Using backup node URL: $targetUrl")
                } else {
                    // 配置无效 (无 backupNodes / 无 url / url 为空 / url 格式错误)
                    // 触发完整回退
                    Log.w(tag, "Backup node config invalid: backupNodes=$${notice.backupNodes}, url=$backupUrl")
                    if (allowBackupFallback) {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已退回默认节点！")
                        handleBackupFallback()
                    } else {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已保持原连接")
                    }
                    return false
                }
            }

            // 请求节点（失败自动重试一次）
            var result = if (targetUrl != null) {
                subscriptionParser.fetchAndParse(targetUrl)
            } else {
                subscriptionParser.fetchAndParse()
            }
            if (result.isFailure) {
                Log.w(tag, "First fetch attempt failed, retrying...", result.exceptionOrNull())
                showNodeFetchToast(showToast, "请求节点失败，已自动重试...")
                result = if (targetUrl != null) {
                    subscriptionParser.fetchAndParse(targetUrl)
                } else {
                    subscriptionParser.fetchAndParse()
                }
            }

            if (result.isSuccess) {
                val fetchedNodes = result.getOrThrow().map {
                    it.copy(source = NodeSource.SUBSCRIPTION, favoriteSourceNodeId = null, favoriteCreatedAt = 0L)
                }
                // 检查由备用节点返回的空列表
                if (isBackupEnabled && fetchedNodes.isEmpty()) {
                    Log.w(tag, "Backup node returned empty list, treating as failure")
                    if (allowBackupFallback) {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已退回默认节点！")
                        handleBackupFallback()
                    } else {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已保持原连接")
                    }
                    return false
                }

                // 保存到数据库
                nodeDao.replaceSubscriptionNodes(fetchedNodes)
                // 缓存最新节点，避免 Room Flow 延迟导致后续操作使用旧节点
                lastFetchedNodes = fetchedNodes
                lastSuccessfulNodeFetchUsedBackup = targetUrl != null

                if (BuildConfig.DEBUG) {
                    viewModelScope.launch(Dispatchers.IO) {
                        logRawLinkEncryptionStats("after_fetch_insert")
                    }
                }

                Log.d(tag, "Fetched ${fetchedNodes.size} nodes")
                return true
            } else {
                val e = result.exceptionOrNull()
                Log.e(tag, "Failed to fetch nodes", e)
                if (isBackupEnabled) {
                    if (allowBackupFallback) {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已退回默认节点！")
                        // 执行回退操作 (关闭，清除URL，重试默认)
                        handleBackupFallback()
                    } else {
                        showNodeFetchToast(showToast, "备用节点当前不可用，已保持原连接")
                    }
                } else {
                    showNodeFetchToast(showToast, "获取节点失败: ${e?.message ?: "未知错误"}")
                }
                return false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error fetching nodes", e)
            showNodeFetchToast(showToast, "网络错误")
            return false
        } finally {
            _isLoading.value = false
            GlobalTestExecution.endFetching()
            if (shouldUpdateFetchAnchor) {
                lastNodeFetchFinishedAt.value = System.currentTimeMillis()
            }
        }
    }

    private fun showNodeFetchToast(showToast: Boolean, message: String) {
        if (showToast) {
            _error.value = message
        }
    }
    
    // 处理备用节点回退逻辑
    private suspend fun handleBackupFallback() {
        // 0. 如果 VPN 正在运行，先停止（因为节点来源已失效）
        if (vpnState.value != VpnState.DISCONNECTED) {
            Log.d(tag, "Stopping VPN before backup fallback")
            ServiceManager.stopVpn(getApplication())
            delay(500) // 等待 VPN 停止
        }
        
        // 1. 关闭备用节点开关
        settingsRepository.setBackupNodeEnabled(false)
        // 2. 清除备用节点 URL
        settingsRepository.setBackupNodeUrl(null)
        // 3. 清除当前选中的节点（备用节点 ID 在默认列表中不存在）
        settingsRepository.setSelectedNodeId(null)
        
        // 4. 更新 Notice Config 以隐藏按钮
        val currentConfig = _noticeConfig.value
        if (currentConfig != null) {
            _noticeConfig.value = currentConfig.copy(backupNodes = null)
        }
        
        // 5. 重新请求，强制跳过备用模式 (避免 DataStore 异步更新导致重复触发)
        val fetchOk = fetchNodesInternal(
            skipBackupMode = true,
            bypassThrottle = true,
            allowWhenFetchingInProgress = true,
            fetchingLabel = "请求节点中"
        )
        if (!fetchOk) return
    }
    
    // 辅助: 同步获取 Notice
    private suspend fun fetchNoticeSync(): NoticeInfo? {
        return try {
            val info = withTimeout(AppConfig.NOTICE_REQUEST_TIMEOUT_MS) {
                NetworkClient.apiService.getNoticeInfo(AppConfig.NOTICE_URL)
            }
            updateNoticeConfig(info)
            info
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun updateNoticeConfig(info: NoticeInfo) {
        _noticeConfig.value = info
        
        val backupInfo = info.backupNodes
        val isValid = backupInfo?.url?.let { 
             it.startsWith("http://") || it.startsWith("https://") 
        } == true
        
        if (isValid) {
            settingsRepository.setBackupNodeUrl(backupInfo!!.url)
        } else {
            // 无效配置，清除本地记录
            settingsRepository.setBackupNodeUrl(null)
            // 注意: 不在这里触发 handleBackupFallback，让 fetchNodes 的调用者统一处理
            // 避免重复触发 Toast
        }
    }
    
    fun setBackupNodeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }

            // 节流检查
            val now = System.currentTimeMillis()
            if (now - lastBackupSwitchTime < THROTTLE_INTERVAL) {
                _error.value = "切换过于频繁，请稍后再试"
                return@launch
            }
            lastBackupSwitchTime = now
            
            // 如果 VPN 正在运行，先停止（因为节点来源将改变）
            if (vpnState.value != VpnState.DISCONNECTED) {
                Log.d(tag, "Stopping VPN before switching node source")
                ServiceManager.stopVpn(getApplication())
                // 等待 VPN 停止
                delay(500)
            }
            
            // 清除当前选中的节点（因为它可能不在新列表中）
            settingsRepository.setSelectedNodeId(null)
            
            // 切换设置
            settingsRepository.setBackupNodeEnabled(enabled)
            delay(100)
            
            // 获取新的节点列表 (跳过节流检查，因为这是内部调用；测试行为跟随 APP 默认设置)
            val fetchOk = fetchNodesInternal(
                bypassThrottle = true,
                fetchingLabel = "请求节点中"
            )
            if (!fetchOk) return@launch
        }
    }
    
    fun testAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("TCPing 测试")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            _isTesting.value = true
            _testingLabel.value = "TCPing 测试中..."
            try {
                val currentNodes = targetNodes ?: currentNodeListSnapshot()
                internalTestNodes(currentNodes) { completed, total ->
                    _testingLabel.value = "TCPing 测试中 ($completed/$total)"
                }
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                GlobalTestExecution.finish()
            }
        }
    }
    
    /**
     * URL Test 所有节点延迟 (通过 ClashAPI)
     * VPN 运行中 → 用现有 ClashAPI (port 9090)
     * VPN 未运行 → 启动临时无头 sing-box 实例 (port 19090)
     */
    fun urlTestAllNodes(targetNodes: List<Node>? = null) {
        urlTestAllNodesInternal(
            targetNodes = targetNodes,
            requireGlobalLock = true
        )
    }

    private fun urlTestAllNodesInternal(
        targetNodes: List<Node>? = null,
        requireGlobalLock: Boolean,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            var locked = false
            if (requireGlobalLock) {
                if (GlobalTestExecution.isFetching()) {
                    _error.value = GlobalTestExecution.fetchingHint()
                    return@launch
                }
                if (!GlobalTestExecution.tryStart("URL Test 测试")) {
                    _error.value = GlobalTestExecution.busyHint()
                    return@launch
                }
                locked = true
            }
            _isTesting.value = true
            _testingLabel.value = "URL Test 测试中..."
            
            val isVpnRunning = vpnState.value == VpnState.CONNECTED
            val clashApiPort: Int
            var startedHeadless = false
            
            try {
                val currentNodes = targetNodes ?: currentNodeListSnapshot()
                if (currentNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }
                
                if (isVpnRunning) {
                    // VPN 已连接，直接使用现有 ClashAPI
                    clashApiPort = 9090
                    Log.d(tag, "URL Test via existing VPN ClashAPI (port $clashApiPort)")
                } else {
                    // VPN 未连接，启动临时无头 sing-box 实例
                    clashApiPort = xyz.a202132.app.network.UrlTestManager.CLASH_API_PORT
                    Log.d(tag, "URL Test via headless instance (port $clashApiPort)")
                    _testingLabel.value = "启动测试引擎..."
                    
                    val started = withContext(Dispatchers.IO) {
                        xyz.a202132.app.network.UrlTestManager.start(getApplication(), currentNodes)
                    }
                    if (!started) {
                        _error.value = "启动测试引擎失败"
                        return@launch
                    }
                    startedHeadless = true
                    
                    // 等待 sing-box 初始化 + ClashAPI 就绪
                    delay(2000)
                    
                    // 健康检查：等待 ClashAPI 可用 (必须在 IO 线程)
                    val clashReady = withContext(Dispatchers.IO) {
                        var ready = false
                        for (retry in 1..6) {
                            try {
                                val checkUrl = java.net.URL("http://127.0.0.1:$clashApiPort/proxies")
                                val conn = checkUrl.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 2000
                                conn.readTimeout = 2000
                                if (conn.responseCode == 200) {
                                    ready = true
                                    conn.disconnect()
                                    break
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                Log.d(tag, "ClashAPI not ready yet (attempt $retry/6): ${e.message}")
                            }
                            delay(1000)
                        }
                        ready
                    }
                    
                    if (!clashReady) {
                        _error.value = "测试引擎启动超时"
                        return@launch
                    }
                    
                    _testingLabel.value = "URL Test 测试中..."
                }
                
                // 诊断：查询 ClashAPI 注册的代理
                if (startedHeadless) {
                    withContext(Dispatchers.IO) {
                        val proxiesResponse = xyz.a202132.app.network.UrlTestManager.diagnoseProxies()
                        Log.d(tag, "ClashAPI proxies: ${proxiesResponse?.take(500)}")
                    }
                }
                
                Log.d(tag, "URL Testing ${currentNodes.size} nodes via ClashAPI (port=$clashApiPort)")
                
                val results = latencyTester.urlTestAllNodes(
                    nodes = currentNodes,
                    clashApiPort = clashApiPort,
                    targetUrl = AppConfig.URL_TEST_URL,
                    timeoutMs = urlTestTimeoutMs.value,
                    concurrency = urlTestConcurrency.value
                ) { completed, total ->
                    _testingLabel.value = "URL Test 测试中 ($completed/$total)"
                    onProgress?.invoke(completed, total)
                }
                Log.d(tag, "Got ${results.size} URL test results")
                
                // 更新数据库
                results.forEach { result ->
                    val updatedRows = nodeDao.updateLatency(
                        nodeId = result.nodeId,
                        latency = result.latency,
                        isAvailable = result.isAvailable,
                        testedAt = System.currentTimeMillis()
                    )
                    if (updatedRows == 0) {
                        Log.w(tag, "URL Test result not applied, node not found in DB: ${result.nodeId}")
                    }
                }
            } finally {
                // 读取 sing-box 核心日志
                if (startedHeadless) {
                    withContext(Dispatchers.IO) {
                        val coreLog = xyz.a202132.app.network.UrlTestManager.readLogFile(getApplication())
                        if (coreLog != null) {
                            // 分段输出日志（logcat 单条消息有长度限制）
                            coreLog.lines().forEach { line ->
                                Log.d("SingBoxCoreLog", line)
                            }
                        } else {
                            Log.w(tag, "No sing-box core log available")
                        }
                    }
                }
                // 如果启动了临时实例，关闭它
                if (startedHeadless) {
                    xyz.a202132.app.network.UrlTestManager.stop()
                }
                _isTesting.value = false
                _testingLabel.value = null
                if (locked) {
                    GlobalTestExecution.finish()
                }
            }
        }
    }
    
    /**
     * 打开节点列表并开始指定类型的测试
     */
    fun showNodeListForTest(testType: String): Boolean {
        if (GlobalTestExecution.isFetching()) {
            _error.value = GlobalTestExecution.fetchingHint()
            return false
        }
        if (_isTesting.value || GlobalTestExecution.mutex.isLocked) {
            _error.value = GlobalTestExecution.busyHint()
            return false
        }
        if (testType != "tcping" && testType != "urltest") {
            return false
        }

        when (testType) {
            "tcping" -> testAllNodes()
            "urltest" -> urlTestAllNodes()
        }
        return true
    }

    fun setRememberLastSelectedNodeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRememberLastSelectedNodeEnabled(enabled)
        }
    }

    fun setNodeIpInfoTestOnVpnStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNodeIpInfoTestOnVpnStart(enabled)
        }
    }

    fun setScheduledNodeUpdateSettings(
        enabled: Boolean,
        hours: Int,
        minutes: Int,
        nodeAutoReconnect: Boolean,
        toastEnabled: Boolean
    ) {
        viewModelScope.launch {
            val savedHours = hours.coerceIn(0, 168)
            val savedMinutes = minutes.coerceIn(0, 59)
            val normalizedMinutes = if (enabled && savedHours == 0 && savedMinutes == 0) {
                1
            } else {
                savedMinutes
            }
            settingsRepository.setScheduledNodeUpdateEnabled(enabled)
            settingsRepository.setScheduledNodeUpdateHours(savedHours)
            settingsRepository.setScheduledNodeUpdateMinutes(normalizedMinutes)
            settingsRepository.setNodeAutoReconnect(nodeAutoReconnect)
            settingsRepository.setScheduledNodeUpdateToastEnabled(toastEnabled)
        }
    }

    fun setTcpingTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setTcpingTestTimeoutMs(value)
        }
    }

    fun setUrlTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setUrlTestTimeoutMs(value)
        }
    }

    fun setNodeIpInfoTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setNodeIpInfoTimeoutMs(value)
        }
    }

    fun setSpeedTestDownloadTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setSpeedTestDownloadTimeoutMs(value)
        }
    }

    fun setTcpingConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setTcpingConcurrency(value)
        }
    }

    fun setUrlTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setUrlTestConcurrency(value)
        }
    }

    fun setBandwidthTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setBandwidthTestConcurrency(value)
        }
    }

    fun setVpnMtu(value: Int) {
        viewModelScope.launch {
            settingsRepository.setVpnMtu(value)
        }
    }

    /**
     * 清理不可用节点 (UI 过滤，不删除数据库)
     */
    fun cleanUnavailableNodes() {
        val hiddenCount = nodes.value.count { shouldHideByQuickCleanup(it) }
        _filterUnavailable.value = true
        _error.value = if (hiddenCount > 0) {
            "已隐藏${hiddenCount}个不可用或超时节点"
        } else {
            "没有可隐藏的不可用节点"
        }
    }

    private fun sortNodesForList(list: List<Node>, filterOut: Boolean): List<Node> {
        val filtered = if (filterOut) list.filterNot { shouldHideByQuickCleanup(it) } else list
        return filtered.sortedWith(
            compareByDescending<Node> { it.isAvailable }
                .thenBy { it.sortOrder }
                .thenBy { if (it.latency >= 0) it.latency else Int.MAX_VALUE }
        )
    }

    private fun shouldHideByQuickCleanup(node: Node): Boolean {
        return node.latency == -2 ||
            !node.isAvailable
    }

    /**
     * 内部测试逻辑 (Suspend)
     */
    private suspend fun internalTestNodes(
        currentNodes: List<Node>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) {
        if (currentNodes.isEmpty()) return
        
        Log.d(tag, "Testing ${currentNodes.size} nodes")
        
        // 统一使用 Socket 测试 (直接连接节点服务器测试可达性)
        // 即使 VPN 运行中也可以工作，因为测试的是节点服务器本身
        val results = latencyTester.testAllNodes(
            nodes = currentNodes,
            timeoutMs = tcpingTestTimeoutMs.value,
            concurrency = tcpingConcurrency.value,
            onProgress = onProgress
        )
        Log.d(tag, "Got ${results.size} test results")
        
        // 更新数据库
        results.forEach { result ->
            nodeDao.updateLatency(
                nodeId = result.nodeId,
                latency = result.latency,
                isAvailable = result.isAvailable,
                testedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * 开始自动选择并连接
     * (Blocking UI Flow)
     */
fun autoSelectBestNode() {
        viewModelScope.launch {
            val bestNode = currentNodeListSnapshot()
                .filter { it.isAvailable }
                .minWithOrNull(compareBy<Node> { if (it.latency >= 0) it.latency else Int.MAX_VALUE })
            if (bestNode != null) {
                selectNode(bestNode)
            }
        }
    }
    
    /**
     * 选择节点
     */
    fun selectNode(node: Node) {
        viewModelScope.launch {
            settingsRepository.setSelectedNodeId(node.id)
            RuntimeLog.info(tag, "Selected node changed: source=${node.source}, type=${node.type}")
            
            // If VPN is connected, restart to switch to new node
            if (vpnState.value == VpnState.CONNECTED) {
                // Notify user (Optional)
                Log.i(tag, "Restarting VPN to apply new Node: ${node.name}")
                RuntimeLog.info(tag, "Restarting VPN after node selection")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }

    fun setNodeListCategory(category: NodeListCategory) {
        viewModelScope.launch {
            settingsRepository.setNodeListCategory(category)
        }
    }

    fun skipFavoriteRemovalConfirmationForSession() {
        _skipFavoriteRemovalConfirmation.value = true
    }

    fun toggleFavoriteNode(node: Node) {
        viewModelScope.launch(Dispatchers.IO) {
            if (node.source == NodeSource.FAVORITE) {
                nodeDao.deleteNodeById(node.id)
                if (settingsRepository.selectedNodeId.first() == node.id) {
                    settingsRepository.setSelectedNodeId(null)
                }
                return@launch
            }

            val existingFavorite = nodeDao.getFavoriteBySourceNodeId(node.id)
            if (existingFavorite != null) {
                nodeDao.deleteNodeById(existingFavorite.id)
                if (settingsRepository.selectedNodeId.first() == existingFavorite.id) {
                    settingsRepository.setSelectedNodeId(null)
                }
                return@launch
            }

            val now = System.currentTimeMillis()
            nodeDao.insertNode(
                node.copy(
                    id = "fav_${UUID.randomUUID()}",
                    source = NodeSource.FAVORITE,
                    favoriteSourceNodeId = node.id,
                    favoriteCreatedAt = now
                )
            )
        }
    }

    fun importNodesToFavoritesFromText(text: String, onResult: (NodeImportResult, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val parsedNodes = subscriptionParser.parseSubscription(text)
                if (parsedNodes.isEmpty()) return@withContext NodeImportResult.INVALID to 0

                setupLibboxForConfigCheck()
                val coreCompatibleNodes = parsedNodes.filter { node ->
                    isNodeCoreConfigCompatible(node)
                }
                if (coreCompatibleNodes.isEmpty()) return@withContext NodeImportResult.INVALID to 0

                val existingFavoriteRawLinks = nodeDao.getFavoriteNodes().first()
                    .map { it.getRawLinkPlain() }
                    .toMutableSet()
                val uniqueNodes = coreCompatibleNodes.filter { node ->
                    existingFavoriteRawLinks.add(node.getRawLinkPlain())
                }
                if (uniqueNodes.isEmpty()) return@withContext NodeImportResult.DUPLICATE to 0

                val favorites = uniqueNodes.mapIndexed { index, node ->
                    node.copy(
                        id = "fav_${UUID.randomUUID()}",
                        source = NodeSource.FAVORITE,
                        favoriteSourceNodeId = null,
                        favoriteCreatedAt = now + index,
                        latency = -1,
                        isAvailable = true,
                        lastTestedAt = 0L,
                        downloadMbps = 0f,
                        uploadMbps = 0f,
                    )
                }
                nodeDao.insertNodes(favorites)
                NodeImportResult.IMPORTED to favorites.size
            }
            val result = importResult.first
            val importedCount = importResult.second

            if (result == NodeImportResult.IMPORTED) {
                settingsRepository.setNodeListCategory(NodeListCategory.FAVORITES)
                RuntimeLog.info(tag, "Imported $importedCount nodes to favorites")
            } else {
                RuntimeLog.warn(tag, "Node import finished without new nodes: result=$result")
            }
            onResult(result, importedCount)
        }
    }

    private fun setupLibboxForConfigCheck() {
        val app = getApplication<Application>()
        val workDir = File(app.filesDir, "sing-box")
        if (!workDir.exists()) workDir.mkdirs()
        val options = io.nekohasekai.libbox.SetupOptions().apply {
            basePath = workDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = app.cacheDir.absolutePath
        }
        Libbox.setup(options)
    }

    private fun isNodeCoreConfigCompatible(node: Node): Boolean {
        return runCatching {
            val config = configGenerator.generateTestConfig(node)
            Libbox.checkConfig(config)
        }.onFailure { error ->
            Log.w(tag, "Skip incompatible imported node: type=${node.type}, server=${node.server}, error=${error.message}")
            RuntimeLog.warn(tag, "Skip incompatible imported node: type=${node.type}", error)
        }.isSuccess
    }
    
    /**
     * 切换代理模式
     */
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
    
    /**
     * 切换VPN连接
     */
    fun toggleVpn() {
        val node = currentNode.value ?: run {
            RuntimeLog.warn(tag, "VPN toggle ignored: no selected node")
            _error.value = "请先选择节点"
            return
        }
        
        when (vpnState.value) {
            VpnState.DISCONNECTED -> {
                // 4. 无网络连接节点时给用户发Toast，但不阻止
                if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                    RuntimeLog.warn(tag, "VPN start requested while network is unavailable")
                     _error.value = "当前无网络连接节点！"
                }
                RuntimeLog.info(tag, "VPN start requested from UI")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
            VpnState.CONNECTED -> {
                RuntimeLog.info(tag, "VPN stop requested from UI")
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
                RuntimeLog.info(tag, "Restarting VPN after settings change")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    /**
     * 设置绕过局域网
     */
    fun setBypassLan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBypassLan(enabled)
            // 如果 VPN 正在运行，重启以应用新设置
            restartVpnIfNeeded()
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
        val result = requestWithAutoRetry("公告通知") {
            withTimeout(AppConfig.NOTICE_REQUEST_TIMEOUT_MS) {
                NetworkClient.apiService.getNoticeInfo(AppConfig.NOTICE_URL)
            }
        }
        result.onSuccess { noticeInfo ->
            _noticeConfig.value = noticeInfo

            if (noticeInfo.hasNotice) {
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
            }
        }.onFailure { e ->
            Log.e(tag, "Failed to check notice", e)
            RuntimeLog.warn(tag, "Failed to check notice", e)
        }
    }
    
    
    /**
     * 检查更新
     * @param isAuto 是否为自动检查 (不显示"已是最新"提示)
     */
    fun checkUpdate(isAuto: Boolean = false, trackStartupSplash: Boolean = false) {
        viewModelScope.launch {
            if (trackStartupSplash) {
                _startupUpdateCheckCompleted.value = false
            }
            if (AppConfig.UPDATE_URL.isBlank()) {
                if (trackStartupSplash) {
                    _startupUpdateCheckCompleted.value = true
                }
                return@launch
            }
            // 节流检查 (自动检查可跳过)
            if (!isAuto) {
                val now = System.currentTimeMillis()
                if (now - lastCheckUpdateTime < THROTTLE_INTERVAL) {
                    _error.value = "操作过于频繁，请稍后再试"
                    return@launch
                }
                lastCheckUpdateTime = now
            }
            
            val result = requestWithAutoRetry("版本更新") {
                withTimeout(AppConfig.UPDATE_REQUEST_TIMEOUT_MS) {
                    NetworkClient.apiService.getUpdateInfo(AppConfig.UPDATE_URL)
                }
            }
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
                    val ignoredVersionCode =
                        settingsRepository.ignoredUpdateVersionCode.first()
                    val isIgnoredOptionalUpdate =
                        info.isForce != 1 && ignoredVersionCode == info.versionCode

                    if (isIgnoredOptionalUpdate) {
                        _updateInfo.value = null
                        if (!isAuto) {
                            _error.value = "当前新版本已被忽略"
                        }
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
                RuntimeLog.warn(tag, "Failed to check update", e)
            }.also {
                if (trackStartupSplash) {
                    _startupUpdateCheckCompleted.value = true
                }
            }
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
            val existingFile = DownloadManager.isApkReady(getApplication(), info.version)
            if (existingFile != null) {
                // 此处强制执行 installApk 逻辑
                installApk()
                return
            }
            
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun pauseDownload() {
        DownloadManager.pauseDownload()
    }
    
    fun resumeDownload() {
        updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun retryDownload() {
         updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun cancelDownload() {
        DownloadManager.cancelDownload()
    }
    
    fun installApk() {
        val targetFile = downloadState.value.file ?: run {
             updateInfo.value?.let { info ->
                 DownloadManager.isApkReady(getApplication(), info.version)
             }
        }

        targetFile?.let { file ->
            val context = getApplication<Application>()
            try {
                if (file.exists()) { 
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                } else {
                     _error.value = "安装文件丢失，请重新下载"
                     // 重置状态以允许重新下载
                     DownloadManager.resetState()
                }
            } catch (e: Exception) {
                Log.e(tag, "Install failed", e)
                _error.value = "无法启动安装程序: ${e.message}"
            }
        } ?: run {
             _error.value = "找不到安装包，请重试下载"
             DownloadManager.resetState()
        }
    }
    
    // 用户界面操作
    fun resetFilter() {
        _filterUnavailable.value = false
    }
    
    fun dismissNotice() {
        _notice.value = null
    }
    
    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun ignoreCurrentUpdate() {
        val info = _updateInfo.value ?: return
        if (info.isForce == 1) return

        _updateInfo.value = null
        viewModelScope.launch {
            settingsRepository.setIgnoredUpdateVersionCode(info.versionCode)
            RuntimeLog.info(tag, "Ignored optional update: versionCode=${info.versionCode}")
        }
    }
    
    fun clearError() {
        _error.value = null
    }

    fun clearInfoDialogMessage() {
        _infoDialogMessage.value = null
    }
    
    fun acceptUserAgreement() {
        viewModelScope.launch {
            settingsRepository.setUserAgreementAccepted(true)
        }
    }

    fun setAppThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settingsRepository.setAppThemeMode(mode) }
    }

private suspend fun currentNodeListSnapshot(): List<Node> {
        val category = settingsRepository.nodeListCategory.first()
        val snapshot = when (category) {
            NodeListCategory.PRIMARY -> nodeDao.getSubscriptionNodes().first()
            NodeListCategory.FAVORITES -> nodeDao.getFavoriteNodes().first()
        }
        return sortNodesForList(snapshot, _filterUnavailable.value)
    }

private suspend fun restoreRememberedLastSelectedNodeIfNeeded(freshNodes: List<Node>? = null) {
        if (!settingsRepository.rememberLastSelectedNodeEnabled.first()) return
        val rememberedNodeId = settingsRepository.lastSelectedNodeId.first()?.takeIf { it.isNotBlank() } ?: return
        val candidateNodes = freshNodes ?: nodeDao.getAllNodes().first()
        if (candidateNodes.any { it.id == rememberedNodeId } || nodeDao.getNodeById(rememberedNodeId) != null) {
            settingsRepository.setSelectedNodeId(rememberedNodeId)
        }
    }

    fun refreshNodes() {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            val fetchOk = fetchNodesInternal(
                bypassThrottle = true,
                fetchingLabel = "请求节点中"
            )
            if (!fetchOk) return@launch
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

    suspend fun fetchNodeIpInfo(node: Node): Result<NodeIpInfo> = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = NodeTestProxyManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext Result.failure(IllegalStateException("启动节点测试代理失败"))

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val timeoutMs = nodeIpInfoTimeoutMs.value.coerceAtLeast(1000L)
            val client = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            var lastResult: Result<NodeIpInfo> = Result.failure(IllegalStateException("节点 IP 信息请求失败"))
            repeat(AppConfig.NODE_IP_INFO_RETRY_COUNT.coerceAtLeast(0) + 1) { attempt ->
                val attemptResult = fetchNodeIpInfoOnce(node, client)
                lastResult = attemptResult.result
                if (lastResult.isSuccess || !attemptResult.shouldRetry || attempt >= AppConfig.NODE_IP_INFO_RETRY_COUNT) {
                    return@withContext lastResult
                }
                Log.w(
                    tag,
                    "Node IP info retry ${attempt + 1}/${AppConfig.NODE_IP_INFO_RETRY_COUNT}: ${node.getDisplayName()} (${node.id})"
                )
                delay(300)
            }

            lastResult
        } catch (e: Exception) {
            Log.e(tag, "Fetch node IP info failed for ${node.getDisplayName()}: ${e.message}")
            Result.failure(e)
        } finally {
            session.stop()
        }
    }

    private fun fetchNodeIpInfoOnce(
        node: Node,
        client: OkHttpClient
    ): NodeIpInfoAttemptResult {
        return try {
            val request = Request.Builder()
                .url(AppConfig.NODE_IP_INFO_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val retryable = response.code in listOf(429, 500, 502, 503, 504)
                    val error = IllegalStateException("请求失败: HTTP ${response.code}")
                    NodeIpInfoAttemptResult(Result.failure(error), retryable)
                } else {
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        NodeIpInfoAttemptResult(
                            Result.failure(IllegalStateException("接口返回为空")),
                            shouldRetry = false
                        )
                    } else {
                        val parsed = gson.fromJson(body, NodeIpInfo::class.java)
                        if (parsed == null || parsed.ip.isBlank()) {
                            NodeIpInfoAttemptResult(
                                Result.failure(IllegalStateException("解析 IP 信息失败")),
                                shouldRetry = false
                            )
                        } else {
                            NodeIpInfoAttemptResult(Result.success(parsed), shouldRetry = false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Fetch node IP info attempt failed for ${node.getDisplayName()}: ${e.message}")
            NodeIpInfoAttemptResult(Result.failure(e), shouldRetry = shouldRetryNodeIpInfo(e))
        }
    }

    private fun shouldRetryNodeIpInfo(error: Exception): Boolean {
        return error is IOException // 涵盖 SocketTimeoutException、InterruptedIOException 等
    }

    private suspend fun testNodeDownloadBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = NodeTestProxyManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val builder = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
            if (speedTestDownloadTimeoutMs.value > 0L) {
                builder.callTimeout(speedTestDownloadTimeoutMs.value, TimeUnit.MILLISECONDS)
            }
            val client = builder.build()

            val bytes = sizeMb.toLong() * 1_000_000L
            val url = "${AppConfig.SPEED_TEST_DOWNLOAD_URL}?bytes=$bytes&r=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()

            val start = System.currentTimeMillis()
            var total = 0L
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0f
                val source = resp.body?.source() ?: return@withContext 0f
                val buffer = ByteArray(8192)
                var read: Int
                while (source.read(buffer).also { read = it } != -1) {
                    total += read
                }
            }
            val durationMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            (total * 8f / 1_000_000f) / (durationMs / 1000f)
        } catch (e: Exception) {
            Log.e(tag, "Download bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            session.stop()
        }
    }

    private suspend fun testNodeUploadBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = NodeTestProxyManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val builder = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
            if (AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS > 0L) {
                builder.callTimeout(AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            val client = builder.build()

            val bytes = sizeMb.toLong() * 1_000_000L
            val randomBuffer = ByteArray(8192) { 0x5A.toByte() }
            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength(): Long = bytes

                override fun writeTo(sink: BufferedSink) {
                    var uploaded = 0L
                    while (uploaded < bytes) {
                        val remain = bytes - uploaded
                        val writeSize = minOf(remain.toInt(), randomBuffer.size)
                        sink.write(randomBuffer, 0, writeSize)
                        uploaded += writeSize
                    }
                }
            }

            val request = Request.Builder()
                .url(AppConfig.SPEED_TEST_UPLOAD_URL)
                .post(requestBody)
                .build()

            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0f
                resp.body?.close()
            }
            val durationMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            (bytes * 8f / 1_000_000f) / (durationMs / 1000f)
        } catch (e: Exception) {
            Log.e(tag, "Upload bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            session.stop()
        }
    }

    private fun pickFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private suspend fun migrateRawLinksToEncrypted() {
        runCatching {
            val current = nodeDao.getAllNodes().first()
            var migrated = 0
            current.forEach { node ->
                if (!node.isRawLinkEncrypted()) {
                    val encrypted = CryptoUtils.encryptForStorage(node.rawLink)
                    if (encrypted != node.rawLink) {
                        nodeDao.updateNode(node.copy(rawLink = encrypted))
                        migrated++
                    }
                }
            }
            if (migrated > 0) {
                Log.i(tag, "Migrated $migrated node rawLink entries to encrypted storage")
            }
        }.onFailure {
            Log.w(tag, "RawLink migration skipped: ${it.message}")
        }
    }

    private suspend fun logRawLinkEncryptionStats(scene: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val current = nodeDao.getAllNodes().first()
            val total = current.size
            val encrypted = current.count { it.isRawLinkEncrypted() }
            val plain = total - encrypted
            Log.d("RawLinkCryptoStats", "scene=$scene total=$total encrypted=$encrypted plain=$plain")
        }.onFailure {
            Log.w("RawLinkCryptoStats", "scene=$scene stats_failed=${it.message}")
        }
    }
}
