package io.github.HanJason233.chimera.network.subscription

import io.github.HanJason233.chimera.data.model.Node

/** Standard traffic metadata returned by subscription providers. */
internal data class SubscriptionUserInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireAtMillis: Long? = null
) {
    companion object {
        fun parse(headerValue: String?): SubscriptionUserInfo? {
            if (headerValue.isNullOrBlank()) return null
            val values = linkedMapOf<String, Long>()
            headerValue.split(';', ',').forEach { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) return@forEach
                val key = item.substring(0, separator).trim().lowercase()
                if (key !in SUPPORTED_KEYS) return@forEach
                val value = item.substring(separator + 1).trim().toLongOrNull()
                    ?.takeIf { it >= 0L }
                    ?: return@forEach
                values[key] = value
            }
            if (values.isEmpty()) return null

            val expireAtMillis = values["expire"]
                ?.takeIf { it > 0L }
                ?.let { seconds ->
                    runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrNull()
                }
            return SubscriptionUserInfo(
                uploadBytes = values["upload"],
                downloadBytes = values["download"],
                totalBytes = values["total"],
                expireAtMillis = expireAtMillis
            )
        }

        private val SUPPORTED_KEYS = setOf("upload", "download", "total", "expire")
    }
}

internal data class ParsedSubscription(
    val nodes: List<Node>,
    val userInfo: SubscriptionUserInfo? = null,
    val name: String? = null
)
