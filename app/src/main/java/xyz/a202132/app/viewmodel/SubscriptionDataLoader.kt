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
    var isNodeAccessGranted: Boolean = false
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

    suspend fun initialize(forceRefresh: Boolean = false): Boolean = coroutineScope {
        isNodeAccessGranted = false
        val listRequest = async { fetchSubscriptionList(forceRefresh) }
        val accountRequest = async { registerAccountForSession() }
        val listResult = listRequest.await()
        val account = accountRequest.await()
        val subscriptionList = listResult.getOrNull()

        if (subscriptionList == null) {
            onMessage("获取订阅列表失败: ${listResult.exceptionOrNull()?.message ?: "未知错误"}")
            return@coroutineScope false
        }

        val accountLevel = account?.level
            ?: accountManager.currentAccount()?.level
            ?: UNKNOWN_ACCOUNT_LEVEL
        val accessibleSources = subscriptionList.sources.filter { source ->
            source.canBeRequestedBy(accountLevel)
        }
        val skippedSources = subscriptionList.sources - accessibleSources.toSet()
        if (skippedSources.isNotEmpty()) {
            Log.i(
                TAG,
                "Skipped ${skippedSources.size} inaccessible subscription source(s): " +
                    skippedSources.joinToString { "${it.name}(minLevel=${it.minLevel})" }
            )
        }
        if (accessibleSources.isEmpty()) {
            nodeDao().replaceSubscriptionNodes(emptyList())
            lastFetchedNodes = emptyList()
            isNodeAccessGranted = true
            if (subscriptionList.sources.isNotEmpty()) {
                onMessage("当前账户 Lv$accountLevel 未达到可用订阅线路的最低等级")
            }
            return@coroutineScope true
        }

        val fetched = fetchAndPersistNodes(subscriptionList.copy(sources = accessibleSources))
        isNodeAccessGranted = fetched
        fetched
    }

    private suspend fun registerAccountForSession() =
        accountManager.register(backendSettings.value.nodeRequestTimeoutMs)
            .onFailure { error ->
                Log.w(TAG, "Account registration failed; applying Lv0 subscription access", error)
                onMessage("账户同步失败，将按 Lv0 订阅权限获取线路")
            }
            .getOrNull()

    private suspend fun fetchSubscriptionList(forceRefresh: Boolean): Result<SubscriptionList> {
        if (AppConfig.BACKEND_URL.isBlank()) {
            return Result.failure(IllegalStateException("未配置订阅列表接口"))
        }

        return try {
            suspend fun request() = withTimeout(
                backendSettings.value.nodeRequestTimeoutMs.coerceIn(5_000L, 60_000L)
            ) {
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
    ): Result<List<Node>> = coroutineScope {
        val results = sources.map { subscription ->
            async {
                val requestUrl = subscriptionRequestUrl(subscription.url)
                var result = subscriptionParser.fetchAndParse(
                    requestUrl,
                    backendSettings.value.nodeRequestTimeoutMs,
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
                        backendSettings.value.nodeRequestTimeoutMs,
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
            results.forEach { (subscription, result) ->
                if (result.isFailure) {
                    failedCount++
                    firstFailure = firstFailure ?: result.exceptionOrNull()
                    return@forEach
                }
                result.getOrThrow().forEach { node ->
                    val scopedNodeId = "${subscription.id}:${node.id}"
                    val taggedNode = node.copy(
                        id = scopedNodeId,
                        source = NodeSource.SUBSCRIPTION,
                        favoriteSourceNodeId = null,
                        favoriteCreatedAt = 0L,
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
            if (mergedNodes.isEmpty()) {
                Result.failure(firstFailure ?: IllegalStateException("订阅列表未返回节点"))
            } else {
                if (failedCount > 0) {
                    Log.w(TAG, "$failedCount subscription source(s) failed; using successful sources")
                }
                Result.success(mergedNodes.values.toList())
            }
        }
    }

    private suspend fun fetchAndPersistNodes(subscriptionList: SubscriptionList): Boolean {
        if (GlobalTestExecution.mutex.isLocked) {
            onMessage(GlobalTestExecution.busyHint())
            return false
        }
        if (GlobalTestExecution.isFetching()) {
            onMessage(GlobalTestExecution.fetchingHint())
            return false
        }

        GlobalTestExecution.beginFetching("请求节点中")
        _isLoading.value = true
        try {
            if (!NetworkUtils.isNetworkAvailable(context)) {
                onMessage("当前无网络连接，无法获取节点")
                return false
            }
            val result = fetchNodesFromSubscriptions(subscriptionList.sources)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Log.e(TAG, "Failed to fetch nodes", error)
                onMessage("获取节点失败: ${error?.message ?: "未知错误"}")
                return false
            }

            val fetchedNodes = result.getOrThrow()
            val connectedNode = activeNode()?.takeIf { it.source == NodeSource.SUBSCRIPTION }
            val nodesToPersist = if (
                connectedNode != null && fetchedNodes.none { it.id == connectedNode.id }
            ) {
                Log.w(TAG, "Retaining active node missing from refreshed subscription: ${connectedNode.id}")
                fetchedNodes + connectedNode
            } else {
                fetchedNodes
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
            _isLoading.value = false
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
        val accountId = accountManager.currentAccount()?.id ?: accountManager.accountId()
        val separator = if ('?' in url) '&' else '?'
        val encodedAccountId = URLEncoder.encode(accountId, Charsets.UTF_8.name())
        return "$url${separator}accountId=$encodedAccountId"
    }

    private companion object {
        const val TAG = "SubscriptionDataLoader"
        const val UNKNOWN_ACCOUNT_LEVEL = 0
    }
}
