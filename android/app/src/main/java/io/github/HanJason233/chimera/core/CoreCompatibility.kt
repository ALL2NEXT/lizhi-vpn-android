package io.github.HanJason233.chimera.core

import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.util.decodeBase64Compat
import io.github.HanJason233.chimera.util.queryParamFirst

/** Selects a core for extended URI parameters and rejects features neither core implements. */
internal object CoreCompatibility {
    private val tlsExtensionParameters = listOf(
        "key_share", "key-share", "keyShare",
        "fragment", "fragment_fallback_delay", "fragment-fallback-delay",
        "fragmentFallbackDelay", "record_fragment", "record-fragment", "recordFragment"
    )
    private val tlsCapableTypes = setOf(
        NodeType.VLESS, NodeType.VMESS, NodeType.TROJAN, NodeType.HYSTERIA2,
        NodeType.ANYTLS, NodeType.TUIC, NodeType.NAIVE, NodeType.HTTP, NodeType.MASQUE
    )
    private val amneziaWireGuardParameters = listOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "id", "header_protection_key", "content_padding_addition",
        "rekey_after_time", "rekey_timeout", "reject_after_time",
        "keepalive_timeout", "max_handshake_attempts", "random_trailers", "disable_cookies",
        "persistent_keepalive_interval"
    )

    fun requiresXray(node: Node): Boolean {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        return when (node.type) {
            NodeType.VLESS, NodeType.VMESS, NodeType.TROJAN -> {
                val vmess = if (node.type == NodeType.VMESS) vmessConfig(rawLink) else null
                val network = if (node.type == NodeType.VMESS) {
                    vmess?.get("net")?.asString
                } else queryParamFirst(uri, "type", "network")
                val encryption = if (node.type == NodeType.VMESS) {
                    vmess?.get("encryption")?.asString
                } else queryParamFirst(uri, "encryption")
                network?.lowercase()?.let { it == "xhttp" || it == "splithttp" || it == "split-http" } == true ||
                    encryption?.let { it.isNotBlank() && !it.equals("none", ignoreCase = true) } == true
            }
            else -> false
        }
    }

    fun xraySupports(node: Node): Boolean = !hasUnsupportedSpecialFeatures(node)

    fun hasUnsupportedSpecialFeatures(node: Node): Boolean {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        if (node.type == NodeType.MASQUE) {
            val profile = queryParamFirst(uri, "profile")?.lowercase() ?: "cloudflare"
            return profile != "cloudflare"
        }
        if (node.type in tlsCapableTypes && tlsExtensionParameters.any { queryParamFirst(uri, it) != null }) return true
        if (node.type == NodeType.VMESS && hasVmessTlsExtension(vmessConfig(rawLink))) return true
        if (node.type == NodeType.WIREGUARD) {
            return amneziaWireGuardParameters.any { queryParamFirst(uri, it) != null } ||
                queryParamFirst(uri, "ip")?.lowercase()
                    ?.let { it in setOf("quic", "dns", "stun", "sip") } == true
        }
        return false
    }

    private fun vmessConfig(rawLink: String): JsonObject? = runCatching {
        JsonParser.parseString(
            decodeBase64Compat(rawLink.substringAfter("vmess://").substringBefore('#'))
        ).asJsonObject
    }.getOrNull()

    private fun hasVmessTlsExtension(config: JsonObject?): Boolean {
        if (config == null) return false
        if (tlsExtensionParameters.any(config::has)) return true
        val tls = config.get("tls")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        if (tlsExtensionParameters.any(tls::has)) return true
        val reality = tls.get("reality")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return tlsExtensionParameters.any(reality::has)
    }
}
