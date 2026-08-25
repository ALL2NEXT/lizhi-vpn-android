package xyz.a202132.windows.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import xyz.a202132.windows.core.NodeLinkParser
import xyz.a202132.windows.data.DesktopPreferencesStore
import xyz.a202132.windows.model.DesktopAccount
import xyz.a202132.windows.model.DesktopBackendSettings
import xyz.a202132.windows.model.DesktopNode

data class BackendLoadResult(
    val account: DesktopAccount,
    val nodes: List<DesktopNode>,
    val sourceCount: Int,
    val settings: DesktopBackendSettings
)

internal data class BackendSubscriptionSource(
    val id: String,
    val name: String,
    val url: String,
    val encrypted: Boolean,
    val minAccountType: String
)

class BackendClient(
    private val preferencesStore: DesktopPreferencesStore,
    private val nodeParser: NodeLinkParser = NodeLinkParser(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    private val gson = Gson()

    suspend fun load(forceRefresh: Boolean = false): BackendLoadResult = withContext(Dispatchers.IO) {
        val preferences = preferencesStore.snapshot()
        val backendUrl = preferences.backendUrl.trimEnd('/')
        require(
            backendUrl.startsWith("https://", ignoreCase = true) ||
                backendUrl.startsWith("http://127.0.0.1", ignoreCase = true)
        ) {
            "后台地址必须使用 HTTPS"
        }
        val deviceId = deviceId(preferences.installId)
        val account = register(backendUrl, deviceId)
        val root = getJson(
            "$backendUrl/api/client${if (forceRefresh) "?refresh=1" else ""}",
            DEFAULT_TIMEOUT_MS
        )
        val timeoutMs = root.getAsJsonObject("settings")
            ?.get("nodeRequestTimeoutMs")
            ?.asLong
            ?.coerceIn(5_000L, 60_000L)
            ?: DEFAULT_TIMEOUT_MS
        val backendSettings = parseSettings(root.getAsJsonObject("settings"))
        val sources = parseSources(root).filter { source ->
            accountRank(account.accountType) >= accountRank(source.minAccountType)
        }
        val sourceResults = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching { fetchSource(source, backendUrl, deviceId, timeoutMs) }
                }
            }.awaitAll()
        }
        val successful = sourceResults.mapNotNull(Result<List<DesktopNode>>::getOrNull)
        if (sources.isNotEmpty() && successful.isEmpty()) {
            throw sourceResults.firstNotNullOfOrNull(Result<List<DesktopNode>>::exceptionOrNull)
                ?: IllegalStateException("所有订阅线路均获取失败")
        }
        val nodes = successful.flatten().distinctBy(DesktopNode::id)
        BackendLoadResult(
            account = account,
            nodes = nodes,
            sourceCount = sources.size,
            settings = backendSettings
        )
    }

    suspend fun updateNickname(nickname: String): DesktopAccount = withContext(Dispatchers.IO) {
        val normalized = nickname.trim()
        require(normalized.length in 1..24) { "昵称长度必须为 1～24 个字符" }
        val (backendUrl, deviceId) = endpointContext()
        accountFromResponse(
            patchJson(
                "$backendUrl/api/account/profile",
                JsonObject().apply {
                    addProperty("deviceId", deviceId)
                    addProperty("nickname", normalized)
                },
                DEFAULT_TIMEOUT_MS
            )
        )
    }

    suspend fun bindAccount(
        uid: String,
        email: String,
        password: String,
        registrationCode: String
    ): DesktopAccount = withContext(Dispatchers.IO) {
        require(email.trim().isNotEmpty()) { "请输入邮箱" }
        require(password.length in 8..128) { "密码长度必须为 8～128 个字符" }
        val (backendUrl, deviceId) = endpointContext()
        accountFromResponse(
            postJson(
                "$backendUrl/api/account/bind",
                JsonObject().apply {
                    addProperty("deviceId", deviceId)
                    addProperty("uid", uid)
                    addProperty("email", email.trim())
                    addProperty("password", password)
                    addProperty(
                        "registrationCode",
                        registrationCode.trim().uppercase().replace("-", "").replace(" ", "")
                    )
                },
                DEFAULT_TIMEOUT_MS
            )
        )
    }

    suspend fun loginAccount(email: String, password: String): DesktopAccount =
        withContext(Dispatchers.IO) {
            require(email.trim().isNotEmpty()) { "请输入账户邮箱" }
            require(password.length in 8..128) { "密码长度必须为 8～128 个字符" }
            val (backendUrl, deviceId) = endpointContext()
            accountFromResponse(
                postJson(
                    "$backendUrl/api/account/login",
                    JsonObject().apply {
                        addProperty("deviceId", deviceId)
                        addProperty("email", email.trim())
                        addProperty("password", password)
                        addProperty("appVersion", APP_VERSION)
                        addProperty("brand", "Microsoft")
                        addProperty("model", System.getProperty("os.name", "Windows"))
                        addProperty("androidVersion", System.getProperty("os.version", ""))
                        addProperty("androidSdk", 0)
                    },
                    DEFAULT_TIMEOUT_MS
                )
            )
        }

    suspend fun deleteAccount(uid: String, password: String) = withContext(Dispatchers.IO) {
        val (backendUrl, deviceId) = endpointContext()
        postJson(
            "$backendUrl/api/account/delete",
            JsonObject().apply {
                addProperty("deviceId", deviceId)
                addProperty("uid", uid)
                addProperty("password", password)
            },
            DEFAULT_TIMEOUT_MS
        )
        Unit
    }

    suspend fun reportTraffic(
        sessionId: String,
        subscriptionGroupName: String,
        startedAt: Long,
        endedAt: Long,
        uploadBytes: Long,
        downloadBytes: Long
    ) = withContext(Dispatchers.IO) {
        val (backendUrl, deviceId) = endpointContext()
        postJson(
            "$backendUrl/api/account/report",
            JsonObject().apply {
                addProperty("deviceId", deviceId)
                addProperty("sessionId", sessionId)
                addProperty("subscriptionGroupName", subscriptionGroupName)
                addProperty("startedAt", startedAt)
                addProperty("endedAt", endedAt)
                addProperty("uploadBytes", uploadBytes.coerceAtLeast(0L))
                addProperty("downloadBytes", downloadBytes.coerceAtLeast(0L))
            },
            DEFAULT_TIMEOUT_MS
        )
        Unit
    }

    private fun register(backendUrl: String, deviceId: String): DesktopAccount {
        val requestBody = JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("appVersion", APP_VERSION)
            addProperty("brand", "Microsoft")
            addProperty("model", System.getProperty("os.name", "Windows"))
            addProperty("androidVersion", System.getProperty("os.version", ""))
            addProperty("androidSdk", 0)
        }
        val root = postJson("$backendUrl/api/account/register", requestBody, DEFAULT_TIMEOUT_MS)
        val accountJson = root.getAsJsonObject("account")
            ?: throw IllegalStateException(root.string("error") ?: "后台账户注册失败")
        return parseAccount(accountJson)
    }

    private fun accountFromResponse(root: JsonObject): DesktopAccount {
        val account = root.getAsJsonObject("account")
            ?: throw IllegalStateException(root.string("error") ?: "后台未返回账户信息")
        return parseAccount(account)
    }

    private fun parseAccount(accountJson: JsonObject): DesktopAccount = DesktopAccount(
            uid = accountJson.string("uid") ?: throw IllegalStateException("后台未返回账户编号"),
            nickname = accountJson.string("nickname") ?: "流萤用户",
            accountType = accountJson.string("accountType") ?: "guest",
            email = accountJson.string("email").orEmpty(),
            brand = accountJson.string("brand").orEmpty(),
            model = accountJson.string("model").orEmpty(),
            osVersion = accountJson.string("androidVersion").orEmpty(),
            appVersion = accountJson.string("appVersion").orEmpty(),
            deviceBlocked = accountJson.get("deviceBlocked")?.asBoolean ?: false
        )

    private fun parseSettings(settings: JsonObject?): DesktopBackendSettings =
        DesktopBackendSettings(
            websiteUrl = settings?.string("websiteUrl").orEmpty(),
            feedbackEmail = settings?.string("feedbackEmail").orEmpty(),
            feedbackUrl = settings?.string("feedbackUrl").orEmpty(),
            githubUrl = settings?.string("githubUrl").orEmpty(),
            telegramUrl = settings?.string("telegramUrl").orEmpty(),
            registrationEnabled = settings?.get("registrationEnabled")?.asBoolean ?: true,
            requireAdminRegistrationCode =
                settings?.get("requireAdminRegistrationCode")?.asBoolean ?: false
        )

    private fun endpointContext(): Pair<String, String> {
        val preferences = preferencesStore.snapshot()
        return preferences.backendUrl.trimEnd('/') to deviceId(preferences.installId)
    }

    private fun parseSources(root: JsonObject): List<BackendSubscriptionSource> {
        val array = listOf("subscriptions", "sources", "list", "data")
            .firstNotNullOfOrNull { key -> root.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
            ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val source = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapIndexedNotNull null
            val url = source.string("url", "subscribeUrl", "subscriptionUrl")
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://127.0.0.1") }
                ?: return@mapIndexedNotNull null
            if (source.get("enabled")?.asBoolean == false) return@mapIndexedNotNull null
            BackendSubscriptionSource(
                id = source.string("id") ?: (index + 1).toString(),
                name = source.string("name", "title", "label") ?: "线路${index + 1}",
                url = url,
                encrypted = source.get("encrypted")?.asBoolean
                    ?: source.get("isEncrypted")?.asBoolean
                    ?: false,
                minAccountType = source.string(
                    "minAccountType",
                    "requiredAccountType",
                    "minimumAccountType"
                ) ?: "guest"
            )
        }
    }

    private fun fetchSource(
        source: BackendSubscriptionSource,
        backendUrl: String,
        deviceId: String,
        timeoutMs: Long
    ): List<DesktopNode> {
        val managedPrefix = "$backendUrl/api/subscription/"
        val requestUrl = if (source.url.startsWith(managedPrefix, ignoreCase = true)) {
            val separator = if ('?' in source.url) '&' else '?'
            source.url + separator + "deviceId=" +
                URLEncoder.encode(deviceId, StandardCharsets.UTF_8)
        } else {
            source.url
        }
        val response = get(requestUrl, timeoutMs)
        val content = if (source.encrypted) {
            val clientIp = response.headers().firstValue(CLIENT_IP_HEADER).orElse(null)
                ?: throw IllegalStateException("加密订阅缺少 $CLIENT_IP_HEADER 响应头")
            SubscriptionCipher.decode(response.body(), clientIp)
        } else {
            response.body()
        }
        return nodeParser.parseSubscription(content).map { node ->
            node.copy(
                subscriptionSourceId = source.id,
                subscriptionSourceName = source.name
            )
        }
    }

    private fun getJson(url: String, timeoutMs: Long): JsonObject =
        JsonParser.parseString(get(url, timeoutMs).body()).asJsonObject

    private fun postJson(url: String, body: JsonObject, timeoutMs: Long): JsonObject {
        val request = requestBuilder(url, timeoutMs)
            .header("content-type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
            .build()
        return JsonParser.parseString(send(request).body()).asJsonObject
    }

    private fun patchJson(url: String, body: JsonObject, timeoutMs: Long): JsonObject {
        val request = requestBuilder(url, timeoutMs)
            .header("content-type", "application/json; charset=utf-8")
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8)
            )
            .build()
        return JsonParser.parseString(send(request).body()).asJsonObject
    }

    private fun get(url: String, timeoutMs: Long): HttpResponse<String> =
        send(requestBuilder(url, timeoutMs).GET().build())

    private fun requestBuilder(url: String, timeoutMs: Long): HttpRequest.Builder =
        HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(timeoutMs.coerceIn(5_000L, 60_000L)))
            .header("accept", "application/json, text/plain, */*;q=0.1")
            .header("user-agent", "FireflyVPN/$APP_VERSION (Windows)")

    private fun send(request: HttpRequest): HttpResponse<String> {
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        if (response.statusCode() !in 200..299) {
            val error = runCatching {
                JsonParser.parseString(response.body()).asJsonObject.string("error")
            }.getOrNull()
            throw IllegalStateException(error ?: "后台请求失败（HTTP ${response.statusCode()}）")
        }
        return response
    }

    private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun accountRank(value: String?): Int = when (value) {
        "formal" -> 2
        "guest" -> 1
        else -> 0
    }

    companion object {
        const val APP_VERSION = "3.0.3"
        private const val DEFAULT_TIMEOUT_MS = 25_000L
        private const val CLIENT_IP_HEADER = "X-Firefly-Client-IP"

        internal fun deviceId(installId: String): String = MessageDigest.getInstance("SHA-256")
            .digest("fireflyvpn-account:$installId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
