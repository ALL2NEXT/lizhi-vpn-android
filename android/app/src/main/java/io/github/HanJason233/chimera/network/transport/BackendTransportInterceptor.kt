package io.github.HanJason233.chimera.network.transport

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.IOException

private const val TRANSPORT_TYPE = "application/vnd.chimera.encrypted"
private const val MAX_REQUEST_BYTES = 1024 * 1024
private const val MAX_RESPONSE_BYTES = 24 * 1024 * 1024

/** Marks both key bootstrap and encrypted calls so redirects cannot forward them. */
internal data class TransportDestination(val url: HttpUrl)

private fun isBackendApi(url: HttpUrl, base: HttpUrl): Boolean =
    url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
        url.encodedPath.startsWith("${base.encodedPath.trimEnd('/')}/api/")

internal class TransportDestinationGuard(private val backendUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val destination = request.tag(TransportDestination::class.java)
        if (destination != null && destination.url != request.url) {
            throw IOException("已阻止加密通信重定向")
        }
        if (destination == null && isBackendApi(request.url, backendUrl().trimEnd('/').toHttpUrl())) {
            throw IOException("已阻止绕过加密入口访问后端")
        }
        return chain.proceed(request)
    }
}

internal class BackendTransportInterceptor(
    private val backendUrl: () -> String,
    private val crypto: TransportCrypto = NativeTransportCrypto,
    private val clock: () -> Long = System::currentTimeMillis
) : Interceptor {
    private data class CachedKey(val url: HttpUrl, val expiresAt: Long, val bytes: ByteArray)
    @Volatile private var cachedKey: CachedKey? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val base = backendUrl().trimEnd('/').toHttpUrl()
        val prefix = base.encodedPath.trimEnd('/')
        if (!isBackendApi(original.url, base)) {
            return chain.proceed(original)
        }
        if (!base.isHttps && base.host !in setOf("127.0.0.1", "::1", "[::1]")) {
            throw IOException("加密通信的公钥必须通过 HTTPS 获取")
        }
        val endpoint = base.newBuilder().encodedPath("$prefix/api/transport").query(null).build()
        try {
            val publicKey = publicKey(chain, endpoint, original)
            val plaintext = requestPayload(original, prefix)
            crypto.create(publicKey).use { session ->
                val packet = try { session.seal(plaintext) } finally { plaintext.fill(0) }
                val encrypted = Request.Builder().url(endpoint)
                    .header("User-Agent", original.header("User-Agent") ?: "Chimera")
                    .header("Accept", TRANSPORT_TYPE)
                    .header("Cache-Control", "no-store")
                    .tag(TransportDestination::class.java, TransportDestination(endpoint))
                    .post(packet.toRequestBody(TRANSPORT_TYPE.toMediaType()))
                    .build()
                val response = chain.proceed(encrypted)
                try {
                    if (response.code != 200 || response.body?.contentType()?.toString() != TRANSPORT_TYPE) {
                        cachedKey = null
                        throw IOException("服务器未返回加密响应（HTTP ${response.code}）")
                    }
                    val opened = session.open(readLimited(response.body, MAX_RESPONSE_BYTES + 32))
                    return try { restoreResponse(response, original, opened) } finally {
                        opened.fill(0)
                        response.close()
                    }
                } catch (error: Exception) {
                    response.close()
                    throw error
                }
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("加密通信校验失败", error)
        }
    }

    @Synchronized
    private fun publicKey(chain: Interceptor.Chain, endpoint: HttpUrl, original: Request): ByteArray {
        val keyUrl = endpoint.newBuilder().addPathSegment("key").build()
        cachedKey?.takeIf { it.url == keyUrl && it.expiresAt > clock() }?.let { return it.bytes }
        val request = Request.Builder().url(keyUrl)
            .header("User-Agent", original.header("User-Agent") ?: "Chimera")
            .header("Accept", "application/pkix-key")
            .tag(TransportDestination::class.java, TransportDestination(keyUrl))
            .build()
        val key = chain.proceed(request).use { response ->
            if (!response.isSuccessful || response.body?.contentType()?.toString() != "application/pkix-key") {
                throw IOException("无法获取服务器加密公钥（HTTP ${response.code}）")
            }
            readLimited(response.body, 2048).also {
                if (it.isEmpty()) throw IOException("服务器加密公钥为空")
            }
        }
        cachedKey = CachedKey(keyUrl, clock() + 30 * 60 * 1000, key)
        return key
    }

    private fun requestPayload(request: Request, prefix: String): ByteArray {
        val body = request.body
        if (body != null && (body.isDuplex() || body.isOneShot() || body.contentLength() > MAX_REQUEST_BYTES)) {
            throw IOException("不支持的加密请求正文")
        }
        val buffer = Buffer()
        body?.writeTo(buffer)
        if (buffer.size > MAX_REQUEST_BYTES) throw IOException("加密请求正文过大")
        val bytes = buffer.readByteArray()
        val payload = JsonObject().apply {
            addProperty("method", request.method)
            addProperty("path", request.url.encodedPath.removePrefix(prefix) +
                (request.url.encodedQuery?.let { "?$it" } ?: ""))
            addProperty("contentType", body?.contentType()?.toString().orEmpty())
            addProperty("body", bytes.toByteString().base64())
            addProperty("timestamp", clock())
        }.toString().toByteArray(Charsets.UTF_8)
        bytes.fill(0)
        if (payload.size > MAX_REQUEST_BYTES) {
            payload.fill(0)
            throw IOException("加密请求正文过大")
        }
        return payload
    }

    private fun restoreResponse(outer: Response, original: Request, plaintext: ByteArray): Response {
        val payload = JsonParser.parseString(plaintext.toString(Charsets.UTF_8)).asJsonObject
        val status = payload.get("status").asInt
        if (status !in 200..599) throw IOException("无效的加密响应状态")
        val headers = Headers.Builder()
        payload.getAsJsonArray("headers").forEach { item ->
            val pair: JsonArray = item.asJsonArray
            if (pair.size() != 2) throw IOException("无效的加密响应头")
            headers.add(pair[0].asString, pair[1].asString)
        }
        val authenticatedHeaders = headers.build()
        val bytes = payload.get("body").asString.decodeBase64()?.toByteArray()
            ?: throw IOException("无效的加密响应正文")
        return outer.newBuilder().request(original).code(status).message("Encrypted response")
            .headers(authenticatedHeaders)
            .body(bytes.toResponseBody(authenticatedHeaders["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }

    private fun readLimited(body: ResponseBody?, limit: Int): ByteArray {
        val source = body?.source() ?: throw IOException("加密响应正文为空")
        if (body.contentLength() > limit || source.request(limit.toLong() + 1)) {
            throw IOException("加密响应正文过大")
        }
        return source.readByteArray()
    }
}
