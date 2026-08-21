package xyz.a202132.windows.network

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Decodes the Worker-managed subscription format shared with the Android client. */
object SubscriptionCipher {
    private const val PRIVATE_ALPHABET =
        "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX"
    private const val STANDARD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(protectedText: String, clientIp: String): String {
        val shift = shiftFromClientIp(clientIp)
        val standard = buildString(protectedText.length) {
            protectedText.forEach { character ->
                when {
                    character.isWhitespace() -> Unit
                    character == '=' -> append(character)
                    else -> {
                        val shiftedIndex = PRIVATE_ALPHABET.indexOf(character)
                        require(shiftedIndex >= 0) { "加密订阅包含非法字符" }
                        val originalIndex = (shiftedIndex + 64 - shift) % 64
                        append(STANDARD_ALPHABET[originalIndex])
                    }
                }
            }
        }
        require(standard.length % 4 != 1) { "加密订阅长度无效" }
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    internal fun shiftFromClientIp(value: String): Int {
        var address = value.substringBefore(',').trim()
        if ("://" in address) {
            address = address.substringAfter("://").substringBefore('/').substringAfterLast('@')
        }
        if (address.startsWith('[')) {
            address = address.substringAfter('[').substringBefore(']')
        } else if (address.count { it == ':' } == 1) {
            val possiblePort = address.substringAfterLast(':')
            if (possiblePort.toIntOrNull() in 0..65535) address = address.substringBeforeLast(':')
        }
        val ipv4 = if (':' in address && '.' in address) address.substringAfterLast(':') else address
        val ipv4Parts = ipv4.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            return ipv4Parts.last().toInt() % 64
        }
        require(':' in address) { "加密订阅缺少有效响应地址" }
        val lastGroup = address.substringAfterLast(':')
        if (lastGroup.isEmpty()) return 0
        require(lastGroup.length <= 4 && lastGroup.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "加密订阅响应地址无效"
        }
        return lastGroup.toInt(16) % 64
    }
}
