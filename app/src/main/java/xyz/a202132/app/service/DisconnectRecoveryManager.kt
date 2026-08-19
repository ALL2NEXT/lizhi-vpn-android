package xyz.a202132.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.SubscriptionSource
import xyz.a202132.app.data.model.ACCOUNT_TYPE_BANNED
import xyz.a202132.app.data.model.canBeRequestedBy
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.AccountManager
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionListParser
import xyz.a202132.app.network.SubscriptionParser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 监测当前 VPN 出口，并在确认断线后刷新原订阅、重连同一节点。 */
internal class DisconnectRecoveryManager(
    context: Context,
    private val scope: CoroutineScope,
    private val socksPortProvider: () -> Int?,
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
    private var probeClientPort: Int? = null
    private var probeClient: OkHttpClient? = null

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
        probeClient = null
        probeClientPort = null
    }

    private fun probe204(): Boolean {
        val port = socksPortProvider() ?: return false
        val client = probeClientFor(port)
        val request = Request.Builder()
            .url(AppConfig.URL_TEST_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Connection", "close")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response -> response.code == 204 }
        }.onFailure { error ->
            Log.w(TAG, "204 probe failed: ${error.message}")
        }.getOrDefault(false)
    }

    private fun probeClientFor(port: Int): OkHttpClient {
        probeClient?.takeIf { probeClientPort == port }?.let { return it }
        return NetworkClient.withUserAgent(OkHttpClient.Builder())
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(AppConfig.URL_TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.URL_TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(AppConfig.URL_TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .callTimeout(AppConfig.URL_TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
            .also {
                probeClientPort = port
                probeClient = it
            }
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
        val source = loadSubscriptionSource(sourceId) ?: run {
            Log.w(TAG, "Disconnect recovery skipped: subscription source $sourceId is unavailable")
            return false
        }
        val accountType = accountManager.currentAccount()?.accountType ?: ACCOUNT_TYPE_BANNED
        if (!source.canBeRequestedBy(accountType)) {
            Log.w(
                TAG,
                "Disconnect recovery skipped: $accountType is below ${source.minAccountType}"
            )
            return false
        }
        val directClient = createPhysicalNetworkClient()
        val requestUrl = subscriptionRequestUrl(source.url)
        var result = subscriptionParser.fetchAndParseWithClient(requestUrl, directClient, source.encrypted)
        if (result.isFailure) {
            Log.w(TAG, "Recovery subscription fetch failed, retrying once", result.exceptionOrNull())
            result = subscriptionParser.fetchAndParseWithClient(requestUrl, directClient, source.encrypted)
        }
        val refreshedNodes = result.getOrNull()
            ?.map { node ->
                node.copy(
                    id = "${source.id}:${node.id}",
                    source = NodeSource.SUBSCRIPTION,
                    favoriteSourceNodeId = null,
                    favoriteCreatedAt = 0L,
                    subscriptionSourceId = source.id,
                    subscriptionSourceName = source.name
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

    private suspend fun loadSubscriptionSource(sourceId: String): SubscriptionSource? {
        val cachedList = settingsRepository.subscriptionListJson.first() ?: return null
        return subscriptionListParser.parse(cachedList).getOrNull()
            ?.sources
            ?.firstOrNull { it.id == sourceId }
    }

    private fun subscriptionRequestUrl(url: String): String {
        if (!isWorkerManagedSubscription(url)) return url
        val separator = if ('?' in url) '&' else '?'
        val encodedDeviceId = java.net.URLEncoder.encode(
            accountManager.accountId(),
            Charsets.UTF_8.name()
        )
        return "$url${separator}deviceId=$encodedDeviceId"
    }

    private fun isWorkerManagedSubscription(url: String): Boolean {
        val managedPrefix = "${AppConfig.BACKEND_URL.trimEnd('/')}/api/subscription/"
        return managedPrefix.length > "/api/subscription/".length &&
            url.startsWith(managedPrefix, ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun createPhysicalNetworkClient(): OkHttpClient {
        val timeoutMs = AppConfig.URL_TEST_TIMEOUT.coerceAtLeast(5000L)
        val builder = NetworkClient.withUserAgent(OkHttpClient.Builder())
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)

        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val physicalNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
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
    }
}
