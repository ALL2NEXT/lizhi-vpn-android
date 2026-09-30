package xyz.a202132.app.viewmodel
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.NodeDao
import xyz.a202132.app.data.model.BackendAppSettings
import xyz.a202132.app.data.model.ACCOUNT_TYPE_BANNED
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.NoticeInfo
import xyz.a202132.app.data.model.SubscriptionList
import xyz.a202132.app.data.model.SubscriptionSource
import xyz.a202132.app.data.model.UpdateInfo
import xyz.a202132.app.data.model.canBeRequestedBy
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.AccountManager
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionListParser
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.util.NetworkUtils

/** Owns backend metadata, account registration and subscription persistence. */
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
    private var cachedSubscriptionList: SubscriptionList? = null

    private data class SubscriptionFetchResult(
        val nodes: List<Node>,
        val successfulSourceIds: Set<String>,
        val failedSourceIds: Set<String>,
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _backendSettings = MutableStateFlow(BackendAppSettings())
    val backendSettings = _backendSettings.asStateFlow()

    var noticeConfig: NoticeInfo? = null
        private set
    var updateInfo: UpdateInfo? = null
        private set
    var lastFetchedNodes: List<Node>? = null
        private set
    suspend fun loadCachedSubscriptionList(): SubscriptionList? {
        cachedSubscriptionList?.let { return it }
        val cachedJson = settingsRepository.subscriptionListJson.first() ?: return null
        val parsed = withContext(Dispatchers.Default) {
            applyBackendConfig(cachedJson)
            subscriptionListParser.parse(cachedJson).getOrNull()
        }
        return parsed?.also { cachedSubscriptionList = it }
    }

    suspend fun initialize(
        forceRefresh: Boolean = false,
        sourceId: String? = null
    ): Boolean {
        _isLoading.value = true
        return try {
            initializeInternal(forceRefresh, sourceId)
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun initializeInternal(
        forceRefresh: Boolean = false,
        sourceId: String? = null
    ): Boolean = coroutineScope {
        val listRequest = async { fetchSubscriptionList(forceRefresh) }
        val accountRequest = async { registerAccountForSession() }
        val listResult = listRequest.await()
        val account = accountRequest.await()
        val subscriptionList = listResult.getOrNull()

        if (subscriptionList == null) {
            onMessage("获取订阅列表失败: ${listResult.exceptionOrNull()?.message ?: "未知错误"}")
            return@coroutineScope false
        }

        val accountType = account?.accountType
            ?: accountManager.currentAccount()?.accountType
            ?: ACCOUNT_TYPE_BANNED
        val accessibleSources = subscriptionList.sources.filter { source ->
            source.canBeRequestedBy(accountType)
        }
        val skippedSources = subscriptionList.sources - accessibleSources.toSet()
        if (skippedSources.isNotEmpty()) {
            Log.i(
                TAG,
                "Skipped inaccessible subscription sources for $accountType: " +
                    skippedSources.joinToString { "${it.name}(min=${it.minAccountType})" }
            )
        }
        if (accessibleSources.isEmpty()) {
            nodeDao().replaceSubscriptionNodes(emptyList())
            lastFetchedNodes = emptyList()
            onNodesPersisted(emptyList())
            if (subscriptionList.sources.isNotEmpty()) {
                onMessage("当前账户等级为$accountType，暂无可用线路")
            }
            return@coroutineScope true
        }

        val cachedNodes = nodeDao().getSubscriptionNodes().first()
        val accessibleSourceIds = accessibleSources.mapTo(mutableSetOf()) { it.id }
        val targetSources = if (sourceId.isNullOrBlank()) {
            accessibleSources
        } else {
            accessibleSources.filter { it.id == sourceId }
        }
        val cachedAccessibleNodes = cachedNodes.filter {
            it.subscriptionSourceId in accessibleSourceIds
        }
        if (targetSources.isEmpty()) {
            nodeDao().replaceSubscriptionNodes(cachedAccessibleNodes)
            lastFetchedNodes = cachedAccessibleNodes
            onNodesPersisted(cachedAccessibleNodes)
            return@coroutineScope true
        }
        val fetched = fetchAndPersistNodes(
            subscriptionList.copy(sources = accessibleSources),
            sourcesToRefresh = targetSources,
            cachedNodes = cachedAccessibleNodes,
        )
        fetched
    }

    private suspend fun registerAccountForSession() =
        accountManager.register(settingsRepository.serviceRequestTimeoutMs.first())
            .onFailure { error ->
                Log.w(TAG, "Account registration failed; continuing without a fresh account response", error)
            }
            .getOrNull()

    private suspend fun fetchSubscriptionList(forceRefresh: Boolean): Result<SubscriptionList> {
        if (AppConfig.BACKEND_URL.isBlank()) {
            return Result.failure(IllegalStateException("未配置订阅列表接口"))
        }

        if (!forceRefresh) {
            loadCachedSubscriptionList()?.let { return Result.success(it) }
        }

        return try {
            // 首次配置请求使用本机超时值，不能依赖本次响应下发的配置。
            val requestTimeoutMs = settingsRepository.serviceRequestTimeoutMs.first()
            suspend fun request() = withTimeout(requestTimeoutMs) {
                NetworkClient.apiService.getBackendConfig(backendClientUrl(forceRefresh))
            }
            var response = runCatching { request() }
            if (response.isFailure) response = runCatching { request() }

            val raw = response.getOrThrow()
            val parsed = withContext(Dispatchers.Default) {
                applyBackendConfig(raw)
                subscriptionListParser.parse(raw).getOrThrow()
            }
            settingsRepository.setSubscriptionList(raw)
            cachedSubscriptionList = parsed
            Result.success(parsed)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to fetch subscription list", error)
            Result.failure(error)
        }
    }

    private fun backendClientUrl(forceRefresh: Boolean): String {
        val endpoint = "${AppConfig.BACKEND_URL.trimEnd('/')}/api/client"
        return if (forceRefresh) "$endpoint?refresh=1" else endpoint
    }

    private fun applyBackendConfig(raw: String) {
        runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            noticeConfig = root.get("notice")
                ?.takeIf { !it.isJsonNull && it.isJsonObject }
                ?.let { gson.fromJson(it, NoticeInfo::class.java) }
            _backendSettings.value = root.get("settings")
                ?.takeIf { !it.isJsonNull && it.isJsonObject }
                ?.let { gson.fromJson(it, BackendAppSettings::class.java) }
                ?: BackendAppSettings()
            updateInfo = root.get("appUpdate")
                ?.takeIf { !it.isJsonNull && it.isJsonObject }
                ?.let { gson.fromJson(it, UpdateInfo::class.java) }
        }.onFailure { error ->
            Log.w(TAG, "Backend config does not contain valid notice/update metadata", error)
        }
    }

    private suspend fun fetchNodesFromSubscriptions(
        sources: List<SubscriptionSource>
    ): Result<SubscriptionFetchResult> = coroutineScope {
        val requestTimeoutMs = settingsRepository.serviceRequestTimeoutMs.first()
        val results = sources.map { subscription ->
            async {
                val requestUrl = subscriptionRequestUrl(subscription.url)
                var result = subscriptionParser.fetchAndParse(
                    requestUrl,
                    requestTimeoutMs,
                    subscription.encrypted
                )
                if (result.isFailure) {
                    Log.w(
                        TAG,
                        "Subscription fetch failed, retrying: ${subscription.name}",
                        result.exceptionOrNull()
                    )
                    result = subscriptionParser.fetchAndParse(
                        requestUrl,
                        requestTimeoutMs,
                        subscription.encrypted
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
                result.getOrThrow().forEach { node ->
                    val scopedNodeId = "${subscription.id}:${node.id}"
                    val taggedNode = node.copy(
                        id = scopedNodeId,
                        source = NodeSource.SUBSCRIPTION,
                        subscriptionSourceId = subscription.id,
                        subscriptionSourceName = subscription.name
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
            val shouldRetainConnectedNode = connectedNode != null && (
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
            Log.d(TAG, "Fetched ${fetchedNodes.size} nodes, persisted ${nodesToPersist.size}")
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Error fetching nodes", error)
            onMessage("网络错误")
            return false
        } finally {
            GlobalTestExecution.endFetching()
        }
    }

    private fun isWorkerManagedSubscription(url: String): Boolean {
        val managedPrefix = "${AppConfig.BACKEND_URL.trimEnd('/')}/api/subscription/"
        return managedPrefix.length > "/api/subscription/".length &&
            url.startsWith(managedPrefix, ignoreCase = true)
    }

    private fun subscriptionRequestUrl(url: String): String {
        if (!isWorkerManagedSubscription(url)) return url
        val separator = if ('?' in url) '&' else '?'
        val encodedDeviceId = URLEncoder.encode(accountManager.accountId(), Charsets.UTF_8.name())
        return "$url${separator}deviceId=$encodedDeviceId"
    }

    private companion object {
        const val TAG = "SubscriptionDataLoader"
    }
}
