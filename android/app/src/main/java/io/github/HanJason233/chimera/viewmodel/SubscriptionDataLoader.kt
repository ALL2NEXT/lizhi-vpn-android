package io.github.HanJason233.chimera.viewmodel
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.BackendAppSettings
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeSource
import io.github.HanJason233.chimera.data.model.SubscriptionList
import io.github.HanJason233.chimera.data.model.SubscriptionSource
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.network.account.AccountManager
import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.network.subscription.SubscriptionListParser
import io.github.HanJason233.chimera.network.subscription.SubscriptionParser
import io.github.HanJason233.chimera.network.subscription.backendClientUrl
import io.github.HanJason233.chimera.network.subscription.subscriptionCacheScope
import io.github.HanJason233.chimera.network.subscription.subscriptionRequestUrl
import io.github.HanJason233.chimera.util.NetworkUtils
import retrofit2.HttpException

/** Owns backend metadata and subscription persistence. */
internal class SubscriptionDataLoader(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val accountManager: AccountManager,
    private val nodeDao: suspend () -> NodeDao,
    private val activeNode: () -> Node?,
    private val onNodesPersisted: (List<Node>) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val subscriptionParser = SubscriptionParser()
    private val subscriptionListParser = SubscriptionListParser()
    private val gson = Gson()

    private data class SubscriptionFetchResult(
        val nodes: List<Node>,
        val successfulSourceIds: Set<String>,
        val failedSourceIds: Set<String>,
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val initializeMutex = Mutex()

    private val _fetchingSubscriptionSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val fetchingSubscriptionSourceIds = _fetchingSubscriptionSourceIds.asStateFlow()

    private val _backendSettings = MutableStateFlow(BackendAppSettings())
    val backendSettings = _backendSettings.asStateFlow()

    var lastFetchedNodes: List<Node>? = null
        private set
    suspend fun loadCachedSubscriptionList(): SubscriptionList? {
        val cachedJson = settingsRepository.subscriptionListJson(currentCacheScope()).first() ?: return null
        return withContext(Dispatchers.Default) {
            applyBackendConfig(cachedJson)
            subscriptionListParser.parse(cachedJson).getOrNull()
        }
    }

    suspend fun initialize(
        forceRefresh: Boolean = false,
        sourceId: String? = null,
        forceSourceRefresh: Boolean = false
    ): Boolean {
        if (accountManager.isOfflineMode()) {
            onMessage("离线模式下不会连接 Chimera 后台刷新线路")
            return false
        }
        return initializeMutex.withLock {
            _isLoading.value = true
            try {
                initializeInternal(forceRefresh, sourceId, forceSourceRefresh)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun initializeInternal(
        forceRefresh: Boolean = false,
        sourceId: String? = null,
        forceSourceRefresh: Boolean = false
    ): Boolean = coroutineScope {
        discardCacheForChangedAccount()
        val listRequest = async { fetchSubscriptionList(forceRefresh) }
        val listResult = listRequest.await()
        val subscriptionList = listResult.getOrNull()

        if (subscriptionList == null) {
            onMessage("获取订阅列表失败: ${listResult.exceptionOrNull()?.message ?: "未知错误"}")
            return@coroutineScope false
        }

        // 后台已经按账号、设备和版本筛选；客户端直接使用返回的可用列表。
        val accessibleSources = subscriptionList.sources
        val cacheScope = currentCacheScope()
        val serverSubscriptionVersions = subscriptionList.subscriptionVersions
        val accessibleSourceIds = accessibleSources.mapTo(mutableSetOf()) { it.id }
        val storedSubscriptionVersions = settingsRepository.officialSubscriptionVersions(cacheScope)
        val localSubscriptionVersions = storedSubscriptionVersions?.filterKeys { it in accessibleSourceIds }
        if (storedSubscriptionVersions != null && localSubscriptionVersions != storedSubscriptionVersions) {
            settingsRepository.setOfficialSubscriptionVersions(localSubscriptionVersions.orEmpty(), cacheScope)
        }
        if (accessibleSources.isEmpty()) {
            persistNodes(emptyList())
            settingsRepository.setOfficialSubscriptionVersions(emptyMap(), cacheScope)
            return@coroutineScope true
        }

        val cachedNodes = nodeDao().getSubscriptionNodes().first()
        // 仅拉取版本变化的订阅，并在拉取成功后单独确认对应版本。
        val changedSubscriptionIds = if (localSubscriptionVersions == null) {
            accessibleSourceIds
        } else {
            accessibleSourceIds.filterTo(mutableSetOf()) { subscriptionId ->
                !localSubscriptionVersions.containsKey(subscriptionId) ||
                    localSubscriptionVersions[subscriptionId] !=
                    (serverSubscriptionVersions[subscriptionId] ?: 0L)
            }
        }
        val targetSources = when {
            forceSourceRefresh && sourceId.isNullOrBlank() -> accessibleSources
            forceSourceRefresh || !sourceId.isNullOrBlank() ->
                accessibleSources.filter { it.id == sourceId }
            else -> accessibleSources.filter { it.id in changedSubscriptionIds }
        }
        val cachedAccessibleNodes = cachedNodes.filter {
            it.subscriptionSourceId in accessibleSourceIds
        }
        // 即使本次下载失败，也不能把已被后台移除的线路继续留在缓存中。
        if (cachedAccessibleNodes.size != cachedNodes.size) persistNodes(cachedAccessibleNodes)
        if (targetSources.isEmpty()) {
            persistNodes(cachedAccessibleNodes)
            return@coroutineScope true
        }
        val fetched = fetchAndPersistNodes(
            subscriptionList.copy(sources = accessibleSources),
            sourcesToRefresh = targetSources,
            cachedNodes = cachedAccessibleNodes,
            subscriptionVersionsToApply = accessibleSourceIds.associateWith {
                serverSubscriptionVersions[it] ?: 0L
            },
            subscriptionVersionsScope = cacheScope,
        )
        fetched
    }

    private suspend fun fetchSubscriptionList(forceRefresh: Boolean): Result<SubscriptionList> {
        if (AppConfig.BACKEND_URL.isBlank()) {
            return Result.failure(IllegalStateException("未配置订阅列表接口"))
        }

        return try {
            // 首次配置请求使用本机超时值，不能依赖本次响应下发的配置。
            val requestTimeoutMs = settingsRepository.serviceRequestTimeoutMs.first()
            // 先注册或同步设备，确保后台已保存当前客户端版本和设备关联。
            accountManager.register(requestTimeoutMs).getOrThrow()
            discardCacheForChangedAccount()
            val backendUrl = AppConfig.BACKEND_URL
            val deviceId = accountManager.accountId()
            val cacheScope = currentCacheScope()
            // 配置缓存只用于启动预览，实际刷新每次都获取后台最新的权限列表。
            suspend fun request() = withTimeout(requestTimeoutMs) {
                NetworkClient.apiService.getBackendConfig(backendClientUrl(backendUrl, deviceId, forceRefresh))
            }
            var response = runCatching { request() }
            if (response.isFailure) response = runCatching { request() }

            val raw = response.getOrThrow()
            check(cacheScope == currentCacheScope()) { "账号或后台已变化，请重新刷新线路" }
            val parsed = withContext(Dispatchers.Default) {
                subscriptionListParser.parse(raw).getOrThrow().also { applyBackendConfig(raw) }
            }
            settingsRepository.setSubscriptionList(raw, cacheScope)
            Result.success(parsed)
        } catch (error: Exception) {
            if (error is CancellationException &&
                (error !is TimeoutCancellationException || !currentCoroutineContext().isActive)
            ) throw error
            if (error is HttpException && error.code() in setOf(401, 403)) {
                settingsRepository.clearSubscriptionList()
                settingsRepository.setOfficialSubscriptionVersions(emptyMap(), currentCacheScope())
                persistNodes(emptyList())
            }
            Log.e(TAG, "Failed to fetch subscription list", error)
            Result.failure(error)
        }
    }

    private fun currentCacheScope(): String = subscriptionCacheScope(
        AppConfig.BACKEND_URL, accountManager.accountId(), accountManager.currentAccount(), BuildConfig.VERSION_NAME
    )

    private suspend fun discardCacheForChangedAccount() {
        if (settingsRepository.subscriptionListJson(currentCacheScope()).first() != null) return
        settingsRepository.clearSubscriptionList()
        persistNodes(emptyList())
    }

    private suspend fun persistNodes(nodes: List<Node>) {
        nodeDao().replaceSubscriptionNodes(nodes)
        lastFetchedNodes = nodes
        onNodesPersisted(nodes)
    }

    private fun applyBackendConfig(raw: String) {
        runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            _backendSettings.value = root.get("settings")
                ?.takeIf { !it.isJsonNull && it.isJsonObject }
                ?.let { gson.fromJson(it, BackendAppSettings::class.java) }
                ?: BackendAppSettings()
        }.onFailure { error ->
            Log.w(TAG, "Failed to parse backend settings", error)
        }
    }

    private suspend fun fetchNodesFromSubscriptions(
        sources: List<SubscriptionSource>
    ): Result<SubscriptionFetchResult> = coroutineScope {
        val requestTimeoutMs = settingsRepository.serviceRequestTimeoutMs.first()
        val results = sources.map { subscription ->
            async {
                val requestUrl = subscriptionRequestUrl(
                    subscription.url, AppConfig.BACKEND_URL, accountManager.accountId()
                )
                var result = subscriptionParser.fetchAndParse(
                    requestUrl,
                    requestTimeoutMs
                )
                if (result.isFailure) {
                    Log.w(
                        TAG,
                        "Subscription fetch failed, retrying: ${subscription.name}",
                        result.exceptionOrNull()
                    )
                    result = subscriptionParser.fetchAndParse(
                        requestUrl,
                        requestTimeoutMs
                    )
                }
                subscription to result
            }
        }.awaitAll()

        withContext(Dispatchers.Default) {
            val mergedNodes = LinkedHashMap<String, Node>()
            var failedCount = 0
            var firstFailure: Throwable? = null
            val successfulSourceIds = mutableSetOf<String>()
            val failedSourceIds = mutableSetOf<String>()
            results.forEach { (subscription, result) ->
                if (result.isFailure) {
                    failedCount++
                    failedSourceIds += subscription.id
                    firstFailure = firstFailure ?: result.exceptionOrNull()
                    return@forEach
                }
                successfulSourceIds += subscription.id
                val refreshedAt = System.currentTimeMillis()
                result.getOrThrow().forEach { node ->
                    val scopedNodeId = "${subscription.id}:${node.id}"
                    val taggedNode = node.copy(
                        id = scopedNodeId,
                        source = NodeSource.SUBSCRIPTION,
                        subscriptionSourceId = subscription.id,
                        subscriptionSourceName = subscription.name,
                        subscriptionUpdatedAt = refreshedAt
                    )
                    val existing = mergedNodes[scopedNodeId]
                    mergedNodes[scopedNodeId] = existing?.copy(
                        subscriptionSourceName = listOfNotNull(
                            existing.subscriptionSourceName,
                            subscription.name
                        ).distinct().joinToString("、")
                    ) ?: taggedNode
                }
            }
            if (successfulSourceIds.isEmpty()) {
                Result.failure(firstFailure ?: IllegalStateException("订阅列表未返回节点"))
            } else {
                if (failedCount > 0) {
                    Log.w(TAG, "$failedCount subscription source(s) failed; using successful sources")
                }
                Result.success(
                    SubscriptionFetchResult(
                        nodes = mergedNodes.values.toList(),
                        successfulSourceIds = successfulSourceIds,
                        failedSourceIds = failedSourceIds,
                    )
                )
            }
        }
    }

    private suspend fun fetchAndPersistNodes(
        subscriptionList: SubscriptionList,
        sourcesToRefresh: List<SubscriptionSource> = subscriptionList.sources,
        cachedNodes: List<Node>,
        subscriptionVersionsToApply: Map<String, Long>,
        subscriptionVersionsScope: String,
    ): Boolean {
        if (GlobalTestExecution.mutex.isLocked) {
            onMessage(GlobalTestExecution.busyHint())
            return false
        }
        if (GlobalTestExecution.isFetching()) {
            onMessage(GlobalTestExecution.fetchingHint())
            return false
        }

        GlobalTestExecution.beginFetching("请求节点中")
        _fetchingSubscriptionSourceIds.value = sourcesToRefresh.mapTo(mutableSetOf()) { it.id }
        try {
            if (!NetworkUtils.isNetworkAvailable(context)) {
                onMessage("当前无网络连接，无法获取节点")
                return false
            }
            val result = fetchNodesFromSubscriptions(sourcesToRefresh)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (cachedNodes.isNotEmpty()) {
                    Log.w(TAG, "Failed to refresh subscriptions; using cached nodes", error)
                    nodeDao().replaceSubscriptionNodes(cachedNodes)
                    lastFetchedNodes = cachedNodes
                    onNodesPersisted(cachedNodes)
                    return true
                }
                Log.e(TAG, "Failed to fetch nodes", error)
                onMessage("获取节点失败: ${error?.message ?: "未知错误"}")
                return false
            }

            val fetchedResult = result.getOrThrow()
            val fetchedNodes = fetchedResult.nodes
            val refreshedSourceIds = sourcesToRefresh.mapTo(mutableSetOf()) { it.id }
            val retainedNodes = cachedNodes.filter { node ->
                node.subscriptionSourceId !in refreshedSourceIds ||
                    node.subscriptionSourceId in fetchedResult.failedSourceIds
            }
            val connectedNode = activeNode()?.takeIf { it.source == NodeSource.SUBSCRIPTION }
            val mergedFetchedNodes = (retainedNodes + fetchedNodes).distinctBy(Node::id)
            val shouldRetainConnectedNode = connectedNode != null &&
                subscriptionList.sources.any { it.id == connectedNode.subscriptionSourceId } && (
                connectedNode.subscriptionSourceId !in refreshedSourceIds ||
                    connectedNode.subscriptionSourceId in fetchedResult.failedSourceIds
            )
            val nodesToPersist = if (
                shouldRetainConnectedNode &&
                    connectedNode != null && fetchedNodes.none { it.id == connectedNode.id }
            ) {
                Log.w(TAG, "Retaining active node missing from refreshed subscription: ${connectedNode.id}")
                mergedFetchedNodes + connectedNode
            } else {
                mergedFetchedNodes
            }
            nodeDao().replaceSubscriptionNodes(nodesToPersist)
            lastFetchedNodes = nodesToPersist
            onNodesPersisted(nodesToPersist)
            val refreshedSubscriptionVersions = fetchedResult.successfulSourceIds.mapNotNull { subscriptionId ->
                subscriptionVersionsToApply[subscriptionId]?.let { subscriptionId to it }
            }.toMap()
            if (refreshedSubscriptionVersions.isNotEmpty()) {
                val currentVersions = settingsRepository
                    .officialSubscriptionVersions(subscriptionVersionsScope)
                    .orEmpty()
                settingsRepository.setOfficialSubscriptionVersions(
                    currentVersions + refreshedSubscriptionVersions,
                    subscriptionVersionsScope,
                )
            }
            Log.d(TAG, "Fetched ${fetchedNodes.size} nodes, persisted ${nodesToPersist.size}")
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Error fetching nodes", error)
            onMessage("网络错误")
            return false
        } finally {
            _fetchingSubscriptionSourceIds.value = emptySet()
            GlobalTestExecution.endFetching()
        }
    }

    private companion object {
        const val TAG = "SubscriptionDataLoader"
    }
}
