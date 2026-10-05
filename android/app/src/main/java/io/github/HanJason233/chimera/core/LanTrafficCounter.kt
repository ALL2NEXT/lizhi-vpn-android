package io.github.HanJason233.chimera.core

import com.google.gson.JsonObject

/** Session totals from the runner, including its final shutdown snapshot. */
internal class LanTrafficCounter {
    @Volatile var total: CoreTraffic? = null
        private set

    fun record(event: JsonObject) {
        if (event.get("event")?.asString != "lan-traffic") return
        val snapshot = runCatching {
            CoreTraffic(event.get("upload").asLong, event.get("download").asLong)
        }.getOrNull() ?: return
        if (snapshot.upload < 0 || snapshot.download < 0) return
        val previous = total
        total = CoreTraffic(
            maxOf(previous?.upload ?: 0, snapshot.upload),
            maxOf(previous?.download ?: 0, snapshot.download)
        )
    }
}
