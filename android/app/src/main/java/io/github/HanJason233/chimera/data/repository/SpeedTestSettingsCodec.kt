package io.github.HanJason233.chimera.data.repository

import androidx.datastore.preferences.core.Preferences
import org.json.JSONArray
import org.json.JSONObject
import io.github.HanJason233.chimera.data.model.BandwidthTestTargets
import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestSettings
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.data.model.normalizeSpeedTestStageOrder
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCatalog

internal object SpeedTestSettingsCodec {
    internal fun decodeSpeedTestSettings(
        preferences: Preferences,
        settingsKey: Preferences.Key<String>
    ): SpeedTestSettings {
        val defaults = SpeedTestSettings.default()
        val stored = preferences[settingsKey]?.takeIf(String::isNotBlank)
            ?: return defaults

        return runCatching {
            val item = JSONObject(stored)
            SpeedTestSettings(
                stageOrder = item.optJSONArray("stageOrder")?.let { savedOrder ->
                    buildList {
                        repeat(savedOrder.length()) { index ->
                            SpeedTestStage.entries
                                .firstOrNull { it.name == savedOrder.optString(index) }
                                ?.let(::add)
                        }
                    }
                } ?: defaults.stageOrder,
                tcpingEnabled = item.optBoolean("tcpingEnabled", defaults.tcpingEnabled),
                urlTestEnabled = item.optBoolean("urlTestEnabled", defaults.urlTestEnabled),
                bandwidthEnabled = item.optBoolean("bandwidthEnabled", defaults.bandwidthEnabled),
                linkTypeTestEnabled = item.optBoolean(
                    "linkTypeTestEnabled",
                    defaults.linkTypeTestEnabled
                ),
                linkTypeAcceptDirect = item.optBoolean(
                    "linkTypeAcceptDirect",
                    defaults.linkTypeAcceptDirect
                ),
                linkTypeAcceptRelay = item.optBoolean(
                    "linkTypeAcceptRelay",
                    defaults.linkTypeAcceptRelay
                ),
                linkTypeAcceptCdnRelay = item.optBoolean(
                    "linkTypeAcceptCdnRelay",
                    defaults.linkTypeAcceptCdnRelay
                ),
                unlockTestsEnabled = item.optBoolean(
                    "unlockTestsEnabled",
                    defaults.unlockTestsEnabled
                ),
                unlockTestIds = item.optJSONArray("unlockTestIds")?.let { ids ->
                    buildSet {
                        repeat(ids.length()) { index ->
                            canonicalUnlockTestId(ids.optString(index).trim())
                                .takeIf { id -> UnlockTestCatalog.definitions.any { it.id == id } }
                                ?.let(::add)
                        }
                    }
                } ?: defaults.unlockTestIds,
                linkTypeConcurrencyMode = readSelectionMode(
                    item,
                    "linkTypeConcurrencyMode",
                    defaults.linkTypeConcurrencyMode
                ),
                linkTypeConcurrency = item.optInt(
                    "linkTypeConcurrency",
                    defaults.linkTypeConcurrency
                ),
                unlockTestConcurrencyMode = readSelectionMode(
                    item,
                    "unlockTestConcurrencyMode",
                    defaults.unlockTestConcurrencyMode
                ),
                unlockTestConcurrency = item.optInt(
                    "unlockTestConcurrency",
                    defaults.unlockTestConcurrency
                ),
                unlockTestRequestConcurrency = item.optInt(
                    "unlockTestRequestConcurrency",
                    defaults.unlockTestRequestConcurrency
                ),
                tcpingSelectionMode = readSelectionMode(
                    item,
                    "tcpingSelectionMode",
                    defaults.tcpingSelectionMode
                ),
                tcpingSelectionValue = item.optInt(
                    "tcpingSelectionValue",
                    defaults.tcpingSelectionValue
                ),
                urlTestSelectionMode = readSelectionMode(
                    item,
                    "urlTestSelectionMode",
                    defaults.urlTestSelectionMode
                ),
                urlTestSelectionValue = item.optInt(
                    "urlTestSelectionValue",
                    defaults.urlTestSelectionValue
                ),
                bandwidthSelectionMode = readSelectionMode(
                    item,
                    "bandwidthSelectionMode",
                    defaults.bandwidthSelectionMode
                ),
                bandwidthSelectionValue = item.optInt(
                    "bandwidthSelectionValue",
                    defaults.bandwidthSelectionValue
                ),
                tcpingAutoDeleteTimeoutNodes = item.optBoolean(
                    "tcpingAutoDeleteTimeoutNodes",
                    defaults.tcpingAutoDeleteTimeoutNodes
                ),
                urlTestAutoDeleteTimeoutNodes = item.optBoolean(
                    "urlTestAutoDeleteTimeoutNodes",
                    defaults.urlTestAutoDeleteTimeoutNodes
                ),
                bandwidthAutoDeleteLowSpeedNodes = item.optBoolean(
                    "bandwidthAutoDeleteLowSpeedNodes",
                    defaults.bandwidthAutoDeleteLowSpeedNodes
                ),
                bandwidthAutoDeleteThresholdMbps = item.optInt(
                    "bandwidthAutoDeleteThresholdMbps",
                    defaults.bandwidthAutoDeleteThresholdMbps
                ),
                tcpingTimeoutMs = item.optLong("tcpingTimeoutMs", defaults.tcpingTimeoutMs),
                urlTestWarmupTimeoutMs = item.optLong(
                    "urlTestWarmupTimeoutMs",
                    defaults.urlTestWarmupTimeoutMs
                ),
                urlTestMeasurementTimeoutMs = item.optLong(
                    "urlTestMeasurementTimeoutMs",
                    defaults.urlTestMeasurementTimeoutMs
                ),
                urlTestUrl = item.optString("urlTestUrl", defaults.urlTestUrl),
                urlTestBackupUrl = item.optString(
                    "urlTestBackupUrl",
                    defaults.urlTestBackupUrl
                ),
                bandwidthTestDurationMs = item.optLong(
                    "bandwidthTestDurationMs",
                    defaults.bandwidthTestDurationMs
                ),
                bandwidthTestUrl = item.optString(
                    "bandwidthTestUrl",
                    defaults.bandwidthTestUrl
                ),
                tcpingConcurrencyMode = readSelectionMode(
                    item,
                    "tcpingConcurrencyMode",
                    defaults.tcpingConcurrencyMode
                ),
                tcpingConcurrency = item.optInt(
                    "tcpingConcurrency",
                    defaults.tcpingConcurrency
                ),
                urlTestConcurrencyMode = readSelectionMode(
                    item,
                    "urlTestConcurrencyMode",
                    defaults.urlTestConcurrencyMode
                ),
                urlTestConcurrency = item.optInt(
                    "urlTestConcurrency",
                    defaults.urlTestConcurrency
                ),
                bandwidthTestConcurrencyMode = readSelectionMode(
                    item,
                    "bandwidthTestConcurrencyMode",
                    defaults.bandwidthTestConcurrencyMode
                ),
                bandwidthTestConcurrency = item.optInt(
                    "bandwidthTestConcurrency",
                    defaults.bandwidthTestConcurrency
                )
            ).normalized()
        }.getOrDefault(defaults)
    }

    internal fun encodeSpeedTestSettings(settings: SpeedTestSettings): String {
        val normalized = settings.normalized()
        return JSONObject().apply {
            put("stageOrder", JSONArray(normalized.stageOrder.map { it.name }))
            put("tcpingEnabled", normalized.tcpingEnabled)
            put("urlTestEnabled", normalized.urlTestEnabled)
            put("bandwidthEnabled", normalized.bandwidthEnabled)
            put("linkTypeTestEnabled", normalized.linkTypeTestEnabled)
            put("linkTypeAcceptDirect", normalized.linkTypeAcceptDirect)
            put("linkTypeAcceptRelay", normalized.linkTypeAcceptRelay)
            put("linkTypeAcceptCdnRelay", normalized.linkTypeAcceptCdnRelay)
            put("unlockTestsEnabled", normalized.unlockTestsEnabled)
            put("unlockTestIds", JSONArray(normalized.unlockTestIds.sorted()))
            put("linkTypeConcurrencyMode", normalized.linkTypeConcurrencyMode.name)
            put("linkTypeConcurrency", normalized.linkTypeConcurrency)
            put("unlockTestConcurrencyMode", normalized.unlockTestConcurrencyMode.name)
            put("unlockTestConcurrency", normalized.unlockTestConcurrency)
            put("unlockTestRequestConcurrency", normalized.unlockTestRequestConcurrency)
            put("tcpingSelectionMode", normalized.tcpingSelectionMode.name)
            put("tcpingSelectionValue", normalized.tcpingSelectionValue)
            put("urlTestSelectionMode", normalized.urlTestSelectionMode.name)
            put("urlTestSelectionValue", normalized.urlTestSelectionValue)
            put("bandwidthSelectionMode", normalized.bandwidthSelectionMode.name)
            put("bandwidthSelectionValue", normalized.bandwidthSelectionValue)
            put("tcpingAutoDeleteTimeoutNodes", normalized.tcpingAutoDeleteTimeoutNodes)
            put("urlTestAutoDeleteTimeoutNodes", normalized.urlTestAutoDeleteTimeoutNodes)
            put("bandwidthAutoDeleteLowSpeedNodes", normalized.bandwidthAutoDeleteLowSpeedNodes)
            put("bandwidthAutoDeleteThresholdMbps", normalized.bandwidthAutoDeleteThresholdMbps)
            put("tcpingTimeoutMs", normalized.tcpingTimeoutMs)
            put("urlTestWarmupTimeoutMs", normalized.urlTestWarmupTimeoutMs)
            put("urlTestMeasurementTimeoutMs", normalized.urlTestMeasurementTimeoutMs)
            put("urlTestUrl", normalized.urlTestUrl)
            put("urlTestBackupUrl", normalized.urlTestBackupUrl)
            put("bandwidthTestDurationMs", normalized.bandwidthTestDurationMs)
            put("bandwidthTestUrl", normalized.bandwidthTestUrl)
            put("tcpingConcurrencyMode", normalized.tcpingConcurrencyMode.name)
            put("tcpingConcurrency", normalized.tcpingConcurrency)
            put("urlTestConcurrencyMode", normalized.urlTestConcurrencyMode.name)
            put("urlTestConcurrency", normalized.urlTestConcurrency)
            put("bandwidthTestConcurrencyMode", normalized.bandwidthTestConcurrencyMode.name)
            put("bandwidthTestConcurrency", normalized.bandwidthTestConcurrency)
        }.toString()
    }

    private fun readSelectionMode(
        item: JSONObject,
        key: String,
        default: SpeedTestSelectionMode
    ): SpeedTestSelectionMode = runCatching {
        SpeedTestSelectionMode.valueOf(item.optString(key))
    }.getOrDefault(default)

    private fun normalizeSelectionValue(
        mode: SpeedTestSelectionMode,
        value: Int
    ): Int = when (mode) {
        SpeedTestSelectionMode.PERCENT -> value.coerceIn(1, 100)
        SpeedTestSelectionMode.COUNT -> value.coerceAtLeast(1)
    }

    private fun canonicalUnlockTestId(id: String): String = when (id) {
        "openai" -> "chatgpt"
        else -> id
    }

    private fun SpeedTestSettings.normalized(): SpeedTestSettings = copy(
        stageOrder = normalizeSpeedTestStageOrder(stageOrder),
        unlockTestIds = unlockTestIds.map(::canonicalUnlockTestId).toSet().intersect(
            UnlockTestCatalog.definitions.mapTo(mutableSetOf()) { it.id }
        ),
        tcpingSelectionValue = normalizeSelectionValue(
            tcpingSelectionMode,
            tcpingSelectionValue
        ),
        urlTestSelectionValue = normalizeSelectionValue(
            urlTestSelectionMode,
            urlTestSelectionValue
        ),
        bandwidthSelectionValue = normalizeSelectionValue(
            bandwidthSelectionMode,
            bandwidthSelectionValue
        ),
        linkTypeConcurrency = normalizeSelectionValue(
            linkTypeConcurrencyMode,
            linkTypeConcurrency
        ).coerceAtMost(
            if (linkTypeConcurrencyMode == SpeedTestSelectionMode.COUNT) {
                SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
            } else {
                100
            }
        ),
        unlockTestConcurrency = normalizeSelectionValue(
            unlockTestConcurrencyMode,
            unlockTestConcurrency
        ).coerceAtMost(
            if (unlockTestConcurrencyMode == SpeedTestSelectionMode.COUNT) {
                SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
            } else {
                100
            }
        ),
        unlockTestRequestConcurrency = unlockTestRequestConcurrency.coerceIn(
            1,
            SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
        ),
        bandwidthAutoDeleteThresholdMbps = bandwidthAutoDeleteThresholdMbps.coerceIn(0, 100_000),
        tcpingTimeoutMs = tcpingTimeoutMs.coerceAtLeast(500L),
        urlTestWarmupTimeoutMs = urlTestWarmupTimeoutMs.coerceIn(500L, 60_000L),
        urlTestMeasurementTimeoutMs = urlTestMeasurementTimeoutMs.coerceIn(500L, 60_000L),
        urlTestUrl = UrlTestTargets.normalize(urlTestUrl) ?: UrlTestTargets.DEFAULT_URL,
        urlTestBackupUrl = UrlTestTargets.normalizeOptional(urlTestBackupUrl) ?: "",
        bandwidthTestDurationMs = bandwidthTestDurationMs.coerceAtLeast(1_000L),
        bandwidthTestUrl = BandwidthTestTargets.normalize(bandwidthTestUrl)
            ?: BandwidthTestTargets.DEFAULT_URL,
        tcpingConcurrency = normalizeSelectionValue(
            tcpingConcurrencyMode,
            tcpingConcurrency
        ).coerceAtMost(if (tcpingConcurrencyMode == SpeedTestSelectionMode.COUNT) 512 else 100),
        urlTestConcurrency = normalizeSelectionValue(
            urlTestConcurrencyMode,
            urlTestConcurrency
        ).coerceAtMost(if (urlTestConcurrencyMode == SpeedTestSelectionMode.COUNT) 128 else 100),
        bandwidthTestConcurrency = normalizeSelectionValue(
            bandwidthTestConcurrencyMode,
            bandwidthTestConcurrency
        ).coerceAtMost(if (bandwidthTestConcurrencyMode == SpeedTestSelectionMode.COUNT) 3 else 100)
    )
}
