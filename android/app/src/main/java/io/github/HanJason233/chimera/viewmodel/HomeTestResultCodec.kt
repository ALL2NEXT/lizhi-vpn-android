package io.github.HanJason233.chimera.viewmodel

import io.github.HanJason233.chimera.network.LinkTypeTestResult
import io.github.HanJason233.chimera.network.LinkTypeTestState
import io.github.HanJason233.chimera.network.UnlockTestCatalog
import io.github.HanJason233.chimera.network.UnlockTestResult
import io.github.HanJason233.chimera.network.UnlockTestState
import org.json.JSONArray
import org.json.JSONObject

/** 将首页测试结果编码为本地数据库可保存的 JSON，并兼容后续检测项调整。 */
internal object HomeTestResultCodec {
    fun encodeLinkType(result: LinkTypeTestResult): String = JSONObject().apply {
        put("state", result.state.name)
        put("serverAddress", result.serverAddress)
        put("entryIps", JSONArray(result.entryIps))
        put("exitIp", result.exitIp)
        put("entryCdnProviders", JSONObject().apply {
            result.entryCdnProviders.forEach { (ip, provider) -> put(ip, provider) }
        })
        put("message", result.message)
    }.toString()

    fun decodeLinkType(json: String): LinkTypeTestResult? = runCatching {
        val value = JSONObject(json)
        val providers = value.optJSONObject("entryCdnProviders") ?: JSONObject()
        LinkTypeTestResult(
            state = runCatching {
                LinkTypeTestState.valueOf(value.optString("state"))
            }.getOrDefault(LinkTypeTestState.IDLE),
            serverAddress = value.optString("serverAddress"),
            entryIps = value.optJSONArray("entryIps")?.let { items ->
                buildList { for (index in 0 until items.length()) add(items.optString(index)) }
            }.orEmpty(),
            exitIp = value.optString("exitIp"),
            entryCdnProviders = buildMap {
                val keys = providers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, providers.optString(key))
                }
            },
            message = value.optString("message")
        )
    }.getOrNull()

    fun encodeUnlockTests(state: UnlockTestUiState): String = JSONObject().apply {
        put("message", state.message)
        put("items", JSONArray().apply {
            state.items.forEach { result ->
                put(JSONObject().apply {
                    put("definitionId", result.definition.id)
                    put("state", result.state.name)
                    put("statusText", result.statusText)
                    put("description", result.description)
                    put("region", result.region)
                    result.delayMs?.let { put("delayMs", it) }
                })
            }
        })
    }.toString()

    fun decodeUnlockTests(json: String): UnlockTestUiState? = runCatching {
        val value = JSONObject(json)
        val definitions = UnlockTestCatalog.definitions.associateBy { it.id }
        val items = value.optJSONArray("items")?.let { storedItems ->
            buildList {
                for (index in 0 until storedItems.length()) {
                    val stored = storedItems.optJSONObject(index) ?: continue
                    val definition = definitions[stored.optString("definitionId")] ?: continue
                    val testState = runCatching {
                        UnlockTestState.valueOf(stored.optString("state"))
                    }.getOrDefault(UnlockTestState.IDLE)
                    if (testState == UnlockTestState.IDLE || testState == UnlockTestState.TESTING) continue
                    add(
                        UnlockTestResult(
                            definition = definition,
                            state = testState,
                            statusText = stored.optString("statusText"),
                            description = stored.optString("description"),
                            region = stored.optString("region"),
                            delayMs = if (stored.has("delayMs")) stored.optInt("delayMs") else null
                        )
                    )
                }
            }
        }.orEmpty()
        UnlockTestUiState(
            items = items,
            isRunning = false,
            message = value.optString("message")
        )
    }.getOrNull()
}
