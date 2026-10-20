package io.github.HanJason233.chimera.core.singbox

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.core.CoreSession
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.NativeCoreSession
import io.github.HanJason233.chimera.data.model.ProxyCore
import java.net.HttpURLConnection
import java.net.URL

/** sing-box runtime hosted by the standalone Android PIE runner. */
internal class SingBoxNativeSession(
    context: Context,
    override val endpoint: LocalSocksEndpoint,
    onFailure: (String) -> Unit
) : CoreSession {
    private val delegate = NativeCoreSession(
        context = context,
        core = ProxyCore.SING_BOX,
        endpoint = endpoint,
        executableName = "libsingbox.so",
        processLabel = "sing-box",
        onFailure = onFailure,
        extraArguments = listOf("--directory", context.filesDir.absolutePath)
    )

    override val core = ProxyCore.SING_BOX

    override fun lanTraffic() = delegate.lanTraffic()

    override fun selectedOutboundTag(): String? = runCatching {
        val connection = URL("http://127.0.0.1:9090/proxies/proxy").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 750
            connection.readTimeout = 750
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                JsonParser.parseReader(connection.inputStream.reader())
                    .asJsonObject.get("now")?.asString
            } else null
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    override fun selectOutboundTag(tag: String): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:9090/proxies/proxy")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = 750
            connection.readTimeout = 750
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = JsonObject().apply { addProperty("name", tag) }
                .toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    suspend fun start(config: String): SingBoxNativeSession {
        delegate.start(config)
        return this
    }

    override fun close() = delegate.close()
}
