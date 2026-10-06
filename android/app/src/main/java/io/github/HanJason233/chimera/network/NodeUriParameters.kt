package io.github.HanJason233.chimera.network

import com.google.gson.JsonParser
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.net.URLEncoder

/** 将节点连接参数写入 URI；VMess 的参数放入其标准 JSON 载荷。 */
internal object NodeUriParameters {
    fun add(uri: String, parameters: Map<String, String>): String {
        if (parameters.isEmpty()) return uri
        if (uri.startsWith("vmess://")) {
            val payload = JsonParser.parseString(uri.removePrefix("vmess://").decodeBase64()!!.utf8()).asJsonObject
            parameters.forEach { (key, value) ->
                val vmessKey = when (key) {
                    "headerType" -> "type"
                    "quicSecurity" -> "host"
                    "key" -> "path"
                    else -> key
                }
                payload.addProperty(vmessKey, value)
            }
            return "vmess://" + payload.toString().encodeUtf8().base64()
        }
        val base = uri.substringBefore('#')
        val query = parameters.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return base + (if ('?' in base) '&' else '?') + query + "#" + uri.substringAfter('#', "")
    }
}
