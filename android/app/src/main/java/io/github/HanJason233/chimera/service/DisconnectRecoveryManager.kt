package io.github.HanJason233.chimera.service

import android.content.Context
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.local.AppDatabase
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeSource
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.SubscriptionSource
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.network.account.AccountManager
import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe
import io.github.HanJason233.chimera.network.subscription.SubscriptionListParser
import io.github.HanJason233.chimera.network.subscription.SubscriptionParser
import io.github.HanJason233.chimera.network.subscription.backendClientUrl
import io.github.HanJason233.chimera.network.subscription.subscriptionRequestUrl
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 监测当前 VPN 出口，并在确认断线后刷新原订阅、重连同一节点。 */
internal class DisconnectRecoveryManager(
    context: Context,
    private val scope: CoroutineScope,
    private val socksEndpointProvider: () -> LocalSocksEndpoint?,
    private val physicalNetworkProvider: () -> Network?,
    private val isVpnRunning: () -> Boolean,
    private val reconnect: suspend (Node, ProxyMode) -> Unit
) {
    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val nodeDao = AppDatabase.getInstance(appContext).nodeDao()
    private val accountManager = AccountManager(appContext)
    private val subscriptionParser = SubscriptionParser()
    private val subscriptionListParser = SubscriptionListParser()

    private var monitorJob: Job? = null
    fun start() {
        stop()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive && isVpnRunning()) {
                delay(AppConfig.DISCONNECT_RECOVERY_CHECK_INTERVAL_MS)
                if (!isVpnRunning() || !settingsRepository.disconnectRecoveryEnabled.first()) {
                    continue
                }

                if (probe204()) continue

                var reachable = false
                repeat(AppConfig.DISCONNECT_RECOVERY_CONFIRM_ATTEMPTS - 1) {
                    if (reachable) return@repeat
                    delay(AppConfig.DISCONNECT_RECOVERY_CONFIRM_INTERVAL_MS)
                    if (!isVpnRunning() || !settingsRepository.disconnectRecoveryEnabled.first()) {
                        reachable = true
                        return@repeat
                    }
                    if (probe204()) reachable = true
                }
                if (reachable || !isVpnRunning()) continue

                Log.w(TAG, "204 probe failed three times; starting disconnect recovery")
                if (recoverCurrentNode()) {
                    return@launch
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun probe204(): Boolean {
        val endpoint = socksEndpointProvider() ?: return false
        return runCatching {
            Socks5HttpProbe.hasStatus(
                endpoint,
                AppConfig.URL_TEST_URL,
                expectedStatus = 204,
                timeoutMs = AppConfig.URL_TEST_WARMUP_TIMEOUT
            )
        }.onFailure { error ->
            Log.w(TAG, "204 probe failed: ${error.message}")
        }.getOrDefault(false)
    }

    private suspend fun recoverCurrentNode(): Boolean {
        if (!settingsRepository.disconnectRecoveryEnabled.first() || !isVpnRunning()) return false
        val disconnectedNode = ServiceManager.currentNode.value ?: run {
            Log.w(TAG, "Disconnect recovery skipped: current node is unavailable")
            return false
        }
        val sourceId = disconnectedNode.subscriptionSourceId?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "Disconnect recovery skipped: current node has no subscription source")
            return false
        }
        val directClient = createPhysicalNetworkClient()
        val source = loadSubscriptionSource(sourceId, directClient) ?: run {
            Log.w(TAG, "Disconnect recovery skipped: subscription source $sourceId is unavailable")
            return false
        }
        val requestUrl = subscriptionRequestUrl(source.url, AppConfig.BACKEND_URL, accountManager.accountId())
        var result = subscriptionParser.fetchAndParseWithClient(requestUrl, directClient)
        if (result.isFailure) {
            Log.w(TAG, "Recovery subscription fetch failed, retrying once", result.exceptionOrNull())
            result = subscriptionParser.fetchAndParseWithClient(requestUrl, directClient)
        }
        val refreshedNodes = result.getOrNull()
            ?.map { node ->
                node.copy(
                    id = "${source.id}:${node.id}",
                    source = NodeSource.SUBSCRIPTION,
                    subscriptionSourceId = source.id,
                    subscriptionSourceName = source.name,
                    subscriptionUpdatedAt = System.currentTimeMillis()
                )
            }
            ?.distinctBy(Node::id)
            .orEmpty()
        if (refreshedNodes.isEmpty()) {
            Log.w(TAG, "Disconnect recovery failed: refreshed subscription has no nodes")
            return false
        }

        val recoveryNode = findRecoveryNode(disconnectedNode, refreshedNodes) ?: run {
            Log.w(TAG, "Disconnect recovery failed: original node was not found by server or name")
            return false
        }
        if (!settingsRepository.disconnectRecoveryEnabled.first() || !isVpnRunning()) return false

        nodeDao.replaceSubscriptionNodesBySource(source.id, refreshedNodes)
        settingsRepository.setSelectedNodeId(recoveryNode.id)
        Log.i(
            TAG,
            "Disconnect recovery matched node: server=${recoveryNode.server}, name=${recoveryNode.name}"
        )
        reconnect(recoveryNode, settingsRepository.proxyMode.first())
        return true
    }

    private suspend fun loadSubscriptionSource(sourceId: String, client: OkHttpClient): SubscriptionSource? =
        withContext(Dispatchers.IO) {
            // VPN 已断线，通过物理网络查询后台最新权限，避免使用已失效的订阅缓存。
            runCatching {
                val request = Request.Builder()
                    .url(backendClientUrl(AppConfig.BACKEND_URL, accountManager.accountId(), forceRefresh = true))
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "订阅列表请求返回 HTTP ${response.code}" }
                    subscriptionListParser.parse(response.body?.string().orEmpty()).getOrThrow()
                        .sources.firstOrNull { it.id == sourceId }
                }
            }.onFailure { error ->
                Log.w(TAG, "Recovery subscription list fetch failed", error)
            }.getOrNull()
        }

    private fun createPhysicalNetworkClient(): OkHttpClient {
        val timeoutMs = SUBSCRIPTION_FETCH_TIMEOUT_MS
        val builder = NetworkClient.withUserAgent(OkHttpClient.Builder())
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)

        val physicalNetwork = physicalNetworkProvider()
        if (physicalNetwork == null) {
            Log.w(TAG, "No physical network found for recovery subscription fetch; using default network")
        } else {
            builder.socketFactory(physicalNetwork.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    physicalNetwork.getAllByName(hostname).toList()
            })
        }
        return builder.build()
    }

    private companion object {
        const val TAG = "DisconnectRecovery"
        const val SUBSCRIPTION_FETCH_TIMEOUT_MS = 5_000L
    }
}
