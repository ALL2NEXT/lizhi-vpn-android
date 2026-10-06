package io.github.HanJason233.chimera.network

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okio.ByteString.Companion.decodeBase64

/**
 * 将混合输入展开为明文片段。JSON/YAML 内的字符串、密钥和 URL 属于配置，
 * 仅对配置之外的 Base64 文本递归解码，也不展开 vmess:// 等 URI 内部的编码。
 */
internal object SubscriptionTextNormalizer {
    private const val MAX_DECODE_DEPTH = 32
    private val base64Text = Regex("[A-Za-z0-9+/_=-]+")
    private val base64Token = Regex("""(?<!\S)(?:base64:)?[A-Za-z0-9+/_=-]{8,}(?!\S)""")
    private val yamlKey = Regex("[A-Za-z_][A-Za-z0-9_-]*\\s*:(?:\\s.*|$)")
    private val yamlList = Regex("""-\s+(?:[A-Za-z_][A-Za-z0-9_-]*\s*:.*|\{.*)""")
    private val uri = Regex(
        """(?i)(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive(?:\+https)?|wireguard|ss|socks(?:4a|4|5)?|https?)\\?://[^\s<>"\x60]+"""
    )
    private const val CODE_FENCE = "\u0060\u0060\u0060"
    private val markdownLink = Regex("""\[[^]]*]\(([^)\s]+)\)""")
    private val markdownStart = Regex("""^\[[^]]*]\(https?://""", RegexOption.IGNORE_CASE)
    private val uriSeparator = Regex("""[,;，；](?=(?:https?|vless|vmess|trojan)://)""", RegexOption.IGNORE_CASE)

    fun fragments(value: String): List<String> = expand(value, 0)

    /** 保留多份 YAML 之间的文档边界，避免合并后出现重复的 proxies 键。 */
    fun joinLocal(fragments: List<String>): String = buildString {
        fragments.forEachIndexed { index, fragment ->
            if (index > 0) {
                append(if (ClashYamlParser.isYamlContent(fragment) ||
                    ClashYamlParser.isYamlContent(fragments[index - 1])) "\n---\n" else "\n")
            }
            append(fragment)
        }
    }.trim()

    /** 只用于配置外的文本；保留 URI 的 query/fragment，兼容 Markdown 链接。 */
    fun uris(value: String): List<String> {
        val markdown = markdownLink.replace(value) {
            it.groupValues[1]
        }
        return uri.findAll(markdown).flatMap { match ->
            // 节点 URI 内的 URL（例如 ECH DNS 地址）也是连接参数，不能再次拆分。
            if (match.value.startsWith("http://", true) || match.value.startsWith("https://", true)) {
                uri.findAll(uriSeparator.replace(match.value, "\n")).map { it.value }
            } else {
                sequenceOf(match.value)
            }
        }.map {
            it.replace("\\://", "://").replace("\\@", "@")
        }.toList()
    }

    private fun expand(value: String, depth: Int): List<String> {
        val content = value.trim().removePrefix("\uFEFF")
        if (content.isEmpty()) return emptyList()
        if (depth < MAX_DECODE_DEPTH) {
            decode(content)?.let { return expand(it, depth + 1) }
        }

        val result = mutableListOf<String>()
        val lines = content.lines().toMutableList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isEmpty() || line == "---" || line == "..." || line.startsWith(CODE_FENCE)) {
                index++
                continue
            }
            if (isJsonContent(line)) {
                val block = StringBuilder()
                val balance = JsonBalance()
                do {
                    if (block.isNotEmpty()) block.append('\n')
                    val consumed = balance.accept(lines[index])
                    block.append(lines[index].take(consumed))
                    val rest = lines[index].drop(consumed)
                    if (balance.complete && rest.isNotBlank()) {
                        lines[index] = rest
                    } else {
                        index++
                    }
                    // 不完整 JSON 不能吞掉其后独立的订阅地址或编码内容。
                    if (!balance.complete && index < lines.size) {
                        val next = lines[index].trim()
                        if (next == "---" || yamlKey.matches(next) ||
                            uris(next).firstOrNull() == next || isEncodedCandidate(next)
                        ) break
                    }
                } while (index < lines.size && !balance.complete)
                result += block.toString().trim()
                continue
            }
            if (yamlKey.matches(line) || yamlList.matches(line)) {
                val block = mutableListOf<String>()
                do {
                    block += lines[index++]
                    if (index >= lines.size) break
                    val next = lines[index].trim()
                    // 顶层的独立链接、编码块和 JSON 表示下一份配置；缩进内容留在 YAML 中。
                    val topLevel = lines[index].firstOrNull()?.isWhitespace() != true
                    if (topLevel && (
                            next == "---" || next.startsWith(CODE_FENCE) || isJsonContent(next) ||
                                isEncodedCandidate(next) || uris(next).firstOrNull() == next ||
                                next.isNotEmpty() && !yamlKey.matches(next) &&
                                !next.startsWith("#") && !next.startsWith("-")
                            )
                    ) break
                } while (true)
                result += block.joinToString("\n").trim()
                continue
            }
            if (depth < MAX_DECODE_DEPTH && isEncodedCandidate(line)) {
                var end = index + 1
                while (end < lines.size && isEncodedCandidate(lines[end].trim())) end++
                val block = lines.subList(index, end).joinToString("\n")
                val decoded = decode(block)
                if (decoded != null) {
                    result += expand(decoded, depth + 1)
                    index = end
                    continue
                }
                val decodedLine = decode(line)
                if (decodedLine != null) {
                    result += expand(decodedLine, depth + 1)
                    index++
                    continue
                }
            }
            val expandedTokens = if (depth < MAX_DECODE_DEPTH) expandTokens(lines[index], depth) else null
            result += expandedTokens ?: listOf(lines[index])
            index++
        }
        return result
    }

    fun isJsonContent(value: String): Boolean =
        value.startsWith("{") || value.startsWith("[") &&
            !markdownStart.containsMatchIn(value)

    private fun expandTokens(value: String, depth: Int): List<String>? {
        val result = mutableListOf<String>()
        var start = 0
        base64Token.findAll(value).forEach { match ->
            val decoded = decode(match.value) ?: return@forEach
            value.substring(start, match.range.first).trim().takeIf(String::isNotEmpty)?.let(result::add)
            result += expand(decoded, depth + 1)
            start = match.range.last + 1
        }
        if (start == 0) return null
        value.substring(start).trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private fun isEncodedCandidate(value: String): Boolean {
        val candidate = value.removePrefix("base64:").trim()
        return candidate.length >= 8 && base64Text.matches(candidate)
    }

    private fun decode(value: String): String? {
        val compact = value.removePrefix("base64:").filterNot(Char::isWhitespace)
        if (!isEncodedCandidate(compact)) return null
        val bytes = compact.decodeBase64()?.toByteArray() ?: return null
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return null
        if (text.isBlank() || text == value || text.any {
                it.isISOControl() && it != '\n' && it != '\r' && it != '\t'
            }
        ) return null
        return text
    }

    private class JsonBalance {
        private var depth = 0
        private var quoted = false
        private var escaped = false
        private var started = false
        val complete: Boolean get() = started && depth == 0 && !quoted

        fun accept(value: String): Int {
            value.forEachIndexed { index, char ->
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                } else {
                    when (char) {
                        '"' -> quoted = true
                        '{', '[' -> { depth++; started = true }
                        '}', ']' -> depth--
                    }
                }
                if (complete) return index + 1
            }
            return value.length
        }
    }
}
