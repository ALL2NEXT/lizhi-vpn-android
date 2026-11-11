package io.github.HanJason233.chimera.data.repository

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import io.github.HanJason233.chimera.data.model.BandwidthTestTargets
import io.github.HanJason233.chimera.data.model.LegacySpeedTestSettings
import io.github.HanJason233.chimera.data.model.SpeedTestSettings
import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.normalizeSpeedTestStageOrder
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCatalog

internal object SpeedTestSettingsCodec {
    internal fun decodeSpeedTestSettings(
        preferences: Preferences,
        settingsKey: Preferences.Key<String>,
        legacySelectionKey: Preferences.Key<String>
    ): SpeedTestSettings {
        val stored = preferences[settingsKey]?.takeIf(String::isNotBlank)
        if (stored == null) return decodeLegacySpeedTestSettings(preferences, legacySelectionKey)

        return runCatching {
            val item = JSONObject(stored)
            val stageOrder = item.optJSONArray("stageOrder")?.let { savedOrder ->
                buildList {
                    repeat(savedOrder.length()) { index ->
                        SpeedTestStage.entries
                            .firstOrNull { it.name == savedOrder.optString(index) }
                            ?.let(::add)
                    }
                }
            } ?: SpeedTestStage.entries
            item.apply {
                put("id", LegacySpeedTestSettings.DEFAULT_ID)
                put("name", "迁移后的测速设置")
                put("builtIn", true)
                put("defaultsVersion", SettingsRepository.DEFAULT_SPEED_TEST_PRESET_REVISION)
            }
            val legacyPreferences = preferences.toMutablePreferences().apply {
                this[SettingsRepository.LEGACY_SPEED_TEST_SETTINGS_JSON] = JSONArray().put(item).toString()
                this[SettingsRepository.LEGACY_HOME_SPEED_TEST_SETTINGS_ID] = LegacySpeedTestSettings.DEFAULT_ID
            }
            decodeLegacySpeedTestSettingsRecords(legacyPreferences).first().toSettings().copy(
                stageOrder = normalizeSpeedTestStageOrder(stageOrder)
            )
        }.getOrElse { SpeedTestSettings.default() }
    }

    private fun decodeLegacySpeedTestSettings(
        preferences: Preferences,
        selectionKey: Preferences.Key<String>
    ): SpeedTestSettings {
        val settings = decodeLegacySpeedTestSettingsRecords(preferences)
        val selectedId = preferences[selectionKey]
        val selected = settings.firstOrNull { it.id == selectedId }
            ?: settings.firstOrNull { it.id == LegacySpeedTestSettings.DEFAULT_ID }
            ?: LegacySpeedTestSettings.default()
        return selected.toSettings()
    }

    /** 将旧版选中的方案分别迁移到两个入口的独立配置，再清理旧方案数据。 */
    internal fun migrateLegacySpeedTestSettings(preferences: MutablePreferences) {
        if (preferences[SettingsRepository.HOME_SPEED_TEST_SETTINGS_JSON] == null) {
            preferences[SettingsRepository.HOME_SPEED_TEST_SETTINGS_JSON] = encodeSpeedTestSettings(
                decodeLegacySpeedTestSettings(preferences, SettingsRepository.LEGACY_HOME_SPEED_TEST_SETTINGS_ID)
            )
        }
        if (preferences[SettingsRepository.GROUP_MANUAL_SPEED_TEST_SETTINGS_JSON] == null) {
            preferences[SettingsRepository.GROUP_MANUAL_SPEED_TEST_SETTINGS_JSON] = encodeSpeedTestSettings(
                decodeLegacySpeedTestSettings(preferences, SettingsRepository.LEGACY_GROUP_MANUAL_SPEED_TEST_SETTINGS_ID)
            )
        }
        preferences.remove(SettingsRepository.LEGACY_SPEED_TEST_SETTINGS_JSON)
        preferences.remove(SettingsRepository.LEGACY_HOME_SPEED_TEST_SETTINGS_ID)
        preferences.remove(SettingsRepository.LEGACY_GROUP_MANUAL_SPEED_TEST_SETTINGS_ID)
        preferences.remove(SettingsRepository.LEGACY_GROUP_MANUAL_SPEED_TEST_ENABLED)
        preferences.remove(SettingsRepository.LEGACY_HOME_LONG_PRESS_SPEED_TEST_ENABLED)
        preferences.remove(SettingsRepository.AUTO_DELETE_TCPING_TIMEOUT_NODES)
        preferences.remove(SettingsRepository.AUTO_DELETE_URL_TEST_TIMEOUT_NODES)
        preferences.remove(SettingsRepository.AUTO_DELETE_LOW_BANDWIDTH_NODES)
        preferences.remove(SettingsRepository.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS)
    }

    internal fun encodeSpeedTestSettings(settings: SpeedTestSettings): String {
        val storedSettings = settings.toLegacySettingsRecord().let(::normalizeLegacySpeedTestSettings)
        return JSONArray(encodeLegacySpeedTestSettingsRecords(listOf(storedSettings)))
            .getJSONObject(0)
            .apply {
                remove("id")
                remove("name")
                remove("builtIn")
                remove("defaultsVersion")
                remove("tcpingPercent")
                remove("urlTestPercent")
                remove("bandwidthPercent")
                remove("urlTestTimeoutMs")
                put(
                    "stageOrder",
                    JSONArray(normalizeSpeedTestStageOrder(settings.stageOrder).map { it.name })
                )
            }
            .toString()
    }

    private fun LegacySpeedTestSettings.toSettings() = SpeedTestSettings(
        tcpingEnabled = tcpingEnabled,
        urlTestEnabled = urlTestEnabled,
        bandwidthEnabled = bandwidthEnabled,
        linkTypeTestEnabled = linkTypeTestEnabled,
        linkTypeAcceptDirect = linkTypeAcceptDirect,
        linkTypeAcceptRelay = linkTypeAcceptRelay,
        linkTypeAcceptCdnRelay = linkTypeAcceptCdnRelay,
        unlockTestsEnabled = unlockTestsEnabled,
        unlockTestIds = unlockTestIds,
        linkTypeConcurrencyMode = linkTypeConcurrencyMode,
        linkTypeConcurrency = linkTypeConcurrency,
        unlockTestConcurrencyMode = unlockTestConcurrencyMode,
        unlockTestConcurrency = unlockTestConcurrency,
        unlockTestRequestConcurrency = unlockTestRequestConcurrency,
        tcpingSelectionMode = tcpingSelectionMode,
        tcpingSelectionValue = tcpingSelectionValue,
        urlTestSelectionMode = urlTestSelectionMode,
        urlTestSelectionValue = urlTestSelectionValue,
        bandwidthSelectionMode = bandwidthSelectionMode,
        bandwidthSelectionValue = bandwidthSelectionValue,
        tcpingAutoDeleteTimeoutNodes = tcpingAutoDeleteTimeoutNodes,
        urlTestAutoDeleteTimeoutNodes = urlTestAutoDeleteTimeoutNodes,
        bandwidthAutoDeleteLowSpeedNodes = bandwidthAutoDeleteLowSpeedNodes,
        bandwidthAutoDeleteThresholdMbps = bandwidthAutoDeleteThresholdMbps,
        tcpingTimeoutMs = tcpingTimeoutMs,
        urlTestWarmupTimeoutMs = urlTestWarmupTimeoutMs,
        urlTestMeasurementTimeoutMs = urlTestMeasurementTimeoutMs,
        urlTestUrl = urlTestUrl,
        urlTestBackupUrl = urlTestBackupUrl,
        bandwidthTestDurationMs = bandwidthTestDurationMs,
        bandwidthTestUrl = bandwidthTestUrl,
        tcpingConcurrencyMode = tcpingConcurrencyMode,
        tcpingConcurrency = tcpingConcurrency,
        urlTestConcurrencyMode = urlTestConcurrencyMode,
        urlTestConcurrency = urlTestConcurrency,
        bandwidthTestConcurrencyMode = bandwidthTestConcurrencyMode,
        bandwidthTestConcurrency = bandwidthTestConcurrency
    )

    private fun SpeedTestSettings.toLegacySettingsRecord() = LegacySpeedTestSettings.default().copy(
        tcpingEnabled = tcpingEnabled,
        urlTestEnabled = urlTestEnabled,
        bandwidthEnabled = bandwidthEnabled,
        linkTypeTestEnabled = linkTypeTestEnabled,
        linkTypeAcceptDirect = linkTypeAcceptDirect,
        linkTypeAcceptRelay = linkTypeAcceptRelay,
        linkTypeAcceptCdnRelay = linkTypeAcceptCdnRelay,
        unlockTestsEnabled = unlockTestsEnabled,
        unlockTestIds = unlockTestIds,
        linkTypeConcurrencyMode = linkTypeConcurrencyMode,
        linkTypeConcurrency = linkTypeConcurrency,
        unlockTestConcurrencyMode = unlockTestConcurrencyMode,
        unlockTestConcurrency = unlockTestConcurrency,
        unlockTestRequestConcurrency = unlockTestRequestConcurrency,
        tcpingSelectionMode = tcpingSelectionMode,
        tcpingSelectionValue = tcpingSelectionValue,
        urlTestSelectionMode = urlTestSelectionMode,
        urlTestSelectionValue = urlTestSelectionValue,
        bandwidthSelectionMode = bandwidthSelectionMode,
        bandwidthSelectionValue = bandwidthSelectionValue,
        tcpingAutoDeleteTimeoutNodes = tcpingAutoDeleteTimeoutNodes,
        urlTestAutoDeleteTimeoutNodes = urlTestAutoDeleteTimeoutNodes,
        bandwidthAutoDeleteLowSpeedNodes = bandwidthAutoDeleteLowSpeedNodes,
        bandwidthAutoDeleteThresholdMbps = bandwidthAutoDeleteThresholdMbps,
        tcpingTimeoutMs = tcpingTimeoutMs,
        urlTestWarmupTimeoutMs = urlTestWarmupTimeoutMs,
        urlTestMeasurementTimeoutMs = urlTestMeasurementTimeoutMs,
        urlTestUrl = urlTestUrl,
        urlTestBackupUrl = urlTestBackupUrl,
        bandwidthTestDurationMs = bandwidthTestDurationMs,
        bandwidthTestUrl = bandwidthTestUrl,
        tcpingConcurrencyMode = tcpingConcurrencyMode,
        tcpingConcurrency = tcpingConcurrency,
        urlTestConcurrencyMode = urlTestConcurrencyMode,
        urlTestConcurrency = urlTestConcurrency,
        bandwidthTestConcurrencyMode = bandwidthTestConcurrencyMode,
        bandwidthTestConcurrency = bandwidthTestConcurrency
    )

    private fun decodeLegacySpeedTestSettingsRecords(preferences: Preferences): List<LegacySpeedTestSettings> {
        val stored = preferences[SettingsRepository.LEGACY_SPEED_TEST_SETTINGS_JSON]
            ?.takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching {
                    val array = JSONArray(json)
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val id = item.optString("id").trim()
                            if (id.isBlank()) continue
                            // 仅在内置预置版本升级时刷新默认字段；自定义方案保持原样。
                            if (id == LegacySpeedTestSettings.DEFAULT_ID &&
                                item.optInt("defaultsVersion", 0) < SettingsRepository.DEFAULT_SPEED_TEST_PRESET_REVISION
                            ) {
                                val defaults = LegacySpeedTestSettings.default()
                                item.put("tcpingSelectionMode", defaults.tcpingSelectionMode.name)
                                item.put("tcpingSelectionValue", defaults.tcpingSelectionValue)
                                item.put("urlTestSelectionMode", defaults.urlTestSelectionMode.name)
                                item.put("urlTestSelectionValue", defaults.urlTestSelectionValue)
                                item.put("linkTypeTestEnabled", defaults.linkTypeTestEnabled)
                                item.put("linkTypeConcurrencyMode", defaults.linkTypeConcurrencyMode.name)
                                item.put("linkTypeConcurrency", defaults.linkTypeConcurrency)
                                item.put("unlockTestConcurrencyMode", defaults.unlockTestConcurrencyMode.name)
                                item.put("unlockTestConcurrency", defaults.unlockTestConcurrency)
                                item.put("unlockTestRequestConcurrency", defaults.unlockTestRequestConcurrency)
                                item.put("tcpingConcurrencyMode", defaults.tcpingConcurrencyMode.name)
                                item.put("tcpingConcurrency", defaults.tcpingConcurrency)
                                item.put("urlTestConcurrencyMode", defaults.urlTestConcurrencyMode.name)
                                item.put("urlTestConcurrency", defaults.urlTestConcurrency)
                                item.put(
                                    "bandwidthTestConcurrencyMode",
                                    defaults.bandwidthTestConcurrencyMode.name
                                )
                                item.put("bandwidthTestConcurrency", defaults.bandwidthTestConcurrency)
                            }
                            add(
                                normalizeLegacySpeedTestSettings(
                                    LegacySpeedTestSettings(
                                        id = id,
                                        name = item.optString("name", "我的方案"),
                                        builtIn = item.optBoolean("builtIn", false),
                                        tcpingEnabled = item.optBoolean("tcpingEnabled", true),
                                        urlTestEnabled = item.optBoolean("urlTestEnabled", true),
                                        bandwidthEnabled = item.optBoolean("bandwidthEnabled", true),
                                        linkTypeTestEnabled = item.optBoolean("linkTypeTestEnabled", false),
                                        linkTypeAcceptDirect = item.optBoolean("linkTypeAcceptDirect", true),
                                        linkTypeAcceptRelay = item.optBoolean("linkTypeAcceptRelay", true),
                                        linkTypeAcceptCdnRelay = item.optBoolean("linkTypeAcceptCdnRelay", true),
                                        unlockTestsEnabled = item.optBoolean("unlockTestsEnabled", false),
                                        unlockTestIds = buildSet {
                                            item.optJSONArray("unlockTestIds")?.let { tests ->
                                                for (testIndex in 0 until tests.length()) {
                                                    canonicalUnlockTestId(tests.optString(testIndex).trim())
                                                        .takeIf { id -> UnlockTestCatalog.definitions.any { it.id == id } }
                                                        ?.let(::add)
                                                }
                                            }
                                        },
                                        linkTypeConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "linkTypeConcurrencyMode"
                                        ),
                                        linkTypeConcurrency = item.optInt("linkTypeConcurrency", 3),
                                        unlockTestConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "unlockTestConcurrencyMode"
                                        ),
                                        unlockTestConcurrency = item.optInt("unlockTestConcurrency", 3),
                                        unlockTestRequestConcurrency = item.optInt(
                                            "unlockTestRequestConcurrency",
                                            4
                                        ),
                                        tcpingSelectionMode = readSelectionMode(
                                            item,
                                            "tcping",
                                            SpeedTestSelectionMode.PERCENT
                                        ),
                                        tcpingSelectionValue = readSelectionValue(
                                            item,
                                            "tcping",
                                            legacyKey = "tcpingPercent",
                                            legacyDefault = 100
                                        ),
                                        urlTestSelectionMode = readSelectionMode(
                                            item,
                                            "urlTest",
                                            SpeedTestSelectionMode.PERCENT
                                        ),
                                        urlTestSelectionValue = readSelectionValue(
                                            item,
                                            "urlTest",
                                            legacyKey = "urlTestPercent",
                                            legacyDefault = 50
                                        ),
                                        bandwidthSelectionMode = readSelectionMode(
                                            item,
                                            "bandwidth",
                                            SpeedTestSelectionMode.PERCENT
                                        ),
                                        bandwidthSelectionValue = readSelectionValue(
                                            item,
                                            "bandwidth",
                                            legacyKey = "bandwidthPercent",
                                            legacyDefault = 50
                                        ),
                                        tcpingAutoDeleteTimeoutNodes = item.optBoolean(
                                            "tcpingAutoDeleteTimeoutNodes",
                                            preferences[SettingsRepository.AUTO_DELETE_TCPING_TIMEOUT_NODES]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_TCPING_TIMEOUT_NODES
                                        ),
                                        urlTestAutoDeleteTimeoutNodes = item.optBoolean(
                                            "urlTestAutoDeleteTimeoutNodes",
                                            preferences[SettingsRepository.AUTO_DELETE_URL_TEST_TIMEOUT_NODES]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_URL_TEST_TIMEOUT_NODES
                                        ),
                                        bandwidthAutoDeleteLowSpeedNodes = item.optBoolean(
                                            "bandwidthAutoDeleteLowSpeedNodes",
                                            preferences[SettingsRepository.AUTO_DELETE_LOW_BANDWIDTH_NODES]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_LOW_BANDWIDTH_NODES
                                        ),
                                        bandwidthAutoDeleteThresholdMbps = item.optInt(
                                            "bandwidthAutoDeleteThresholdMbps",
                                            preferences[SettingsRepository.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS
                                        ),
                                        tcpingTimeoutMs = item.optLong("tcpingTimeoutMs", 1_000L),
                                        urlTestWarmupTimeoutMs = item.optLong(
                                            "urlTestWarmupTimeoutMs",
                                            io.github.HanJason233.chimera.AppConfig.URL_TEST_WARMUP_TIMEOUT
                                        ),
                                        urlTestMeasurementTimeoutMs = item.optLong(
                                            "urlTestMeasurementTimeoutMs",
                                            io.github.HanJason233.chimera.AppConfig.URL_TEST_MEASUREMENT_TIMEOUT
                                        ),
                                        urlTestUrl = item.optString("urlTestUrl", UrlTestTargets.DEFAULT_URL),
                                        urlTestBackupUrl = item.optString(
                                            "urlTestBackupUrl",
                                            UrlTestTargets.DEFAULT_BACKUP_URL
                                        ),
                                        bandwidthTestDurationMs = item.optLong(
                                            "bandwidthTestDurationMs",
                                            3_000L
                                        ),
                                        bandwidthTestUrl = item.optString(
                                            "bandwidthTestUrl",
                                            io.github.HanJason233.chimera.AppConfig.SPEED_TEST_DOWNLOAD_URL
                                        ),
                                        tcpingConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "tcpingConcurrencyMode"
                                        ),
                                        tcpingConcurrency = item.optInt("tcpingConcurrency", 16),
                                        urlTestConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "urlTestConcurrencyMode"
                                        ),
                                        urlTestConcurrency = item.optInt("urlTestConcurrency", 10),
                                        bandwidthTestConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "bandwidthTestConcurrencyMode"
                                        ),
                                        bandwidthTestConcurrency = item.optInt(
                                            "bandwidthTestConcurrency",
                                            1
                                        )
                                    )
                                )
                            )
                        }
                    }
                }.getOrNull()
            }

        val defaultScheme = stored?.firstOrNull { it.id == LegacySpeedTestSettings.DEFAULT_ID }
            ?: LegacySpeedTestSettings.default().copy(
                tcpingAutoDeleteTimeoutNodes = preferences[SettingsRepository.AUTO_DELETE_TCPING_TIMEOUT_NODES]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_TCPING_TIMEOUT_NODES,
                urlTestAutoDeleteTimeoutNodes = preferences[SettingsRepository.AUTO_DELETE_URL_TEST_TIMEOUT_NODES]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_URL_TEST_TIMEOUT_NODES,
                bandwidthAutoDeleteLowSpeedNodes = preferences[SettingsRepository.AUTO_DELETE_LOW_BANDWIDTH_NODES]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_LOW_BANDWIDTH_NODES,
                bandwidthAutoDeleteThresholdMbps = preferences[SettingsRepository.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS
            )
        val custom = stored.orEmpty()
            .filterNot { it.id == LegacySpeedTestSettings.DEFAULT_ID }
            .filterNot { it.builtIn }
            .distinctBy(LegacySpeedTestSettings::id)
        return listOf(defaultScheme.copy(builtIn = true)) + custom
    }

    private fun readSelectionMode(
        item: JSONObject,
        prefix: String,
        default: SpeedTestSelectionMode
    ): SpeedTestSelectionMode = runCatching {
        SpeedTestSelectionMode.valueOf(item.optString("${prefix}SelectionMode"))
    }.getOrDefault(default)

    private fun readSelectionValue(
        item: JSONObject,
        prefix: String,
        legacyKey: String,
        legacyDefault: Int
    ): Int = item.optInt(
        "${prefix}SelectionValue",
        item.optInt(legacyKey, legacyDefault)
    )

    private fun readConcurrencyMode(item: JSONObject, key: String): SpeedTestSelectionMode = runCatching {
        SpeedTestSelectionMode.valueOf(item.optString(key))
    }.getOrDefault(SpeedTestSelectionMode.COUNT)

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

    private fun normalizeLegacySpeedTestSettings(scheme: LegacySpeedTestSettings): LegacySpeedTestSettings {
        // 规范空名称，同时保留用户对方案名称的自定义修改。
        val trimmedName = scheme.name.trim()
        val normalizedName = when {
            scheme.id == LegacySpeedTestSettings.DEFAULT_ID && trimmedName.isBlank() -> "默认测速"
            else -> trimmedName.ifBlank { if (scheme.builtIn) "测速方案" else "我的方案" }
        }
        return scheme.copy(
            name = normalizedName,
            unlockTestIds = scheme.unlockTestIds.map(::canonicalUnlockTestId).toSet().intersect(
                UnlockTestCatalog.definitions.mapTo(mutableSetOf()) { it.id }
            ),
            tcpingSelectionValue = normalizeSelectionValue(
                scheme.tcpingSelectionMode,
                scheme.tcpingSelectionValue
            ),
            urlTestSelectionValue = normalizeSelectionValue(
                scheme.urlTestSelectionMode,
                scheme.urlTestSelectionValue
            ),
            bandwidthSelectionValue = normalizeSelectionValue(
                scheme.bandwidthSelectionMode,
                scheme.bandwidthSelectionValue
            ),
            linkTypeConcurrency = normalizeSelectionValue(
                scheme.linkTypeConcurrencyMode,
                scheme.linkTypeConcurrency
            ).coerceAtMost(
                if (scheme.linkTypeConcurrencyMode == SpeedTestSelectionMode.COUNT) {
                    SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
                } else {
                    100
                }
            ),
            unlockTestConcurrency = normalizeSelectionValue(
                scheme.unlockTestConcurrencyMode,
                scheme.unlockTestConcurrency
            ).coerceAtMost(
                if (scheme.unlockTestConcurrencyMode == SpeedTestSelectionMode.COUNT) {
                    SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
                } else {
                    100
                }
            ),
            unlockTestRequestConcurrency = scheme.unlockTestRequestConcurrency.coerceIn(
                1,
                SpeedTestSettings.MAX_PRE_VPN_TEST_CONCURRENCY
            ),
            bandwidthAutoDeleteThresholdMbps = scheme.bandwidthAutoDeleteThresholdMbps.coerceIn(0, 100_000),
            tcpingTimeoutMs = scheme.tcpingTimeoutMs.coerceAtLeast(500L),
            urlTestWarmupTimeoutMs = scheme.urlTestWarmupTimeoutMs.coerceIn(500L, 60_000L),
            urlTestMeasurementTimeoutMs = scheme.urlTestMeasurementTimeoutMs.coerceIn(500L, 60_000L),
            urlTestUrl = UrlTestTargets.normalize(scheme.urlTestUrl) ?: UrlTestTargets.DEFAULT_URL,
            urlTestBackupUrl = UrlTestTargets.normalizeOptional(scheme.urlTestBackupUrl) ?: "",
            bandwidthTestDurationMs = scheme.bandwidthTestDurationMs.coerceAtLeast(1_000L),
            bandwidthTestUrl = BandwidthTestTargets.normalize(scheme.bandwidthTestUrl)
                ?: io.github.HanJason233.chimera.AppConfig.SPEED_TEST_DOWNLOAD_URL,
            tcpingConcurrency = normalizeSelectionValue(
                scheme.tcpingConcurrencyMode,
                scheme.tcpingConcurrency
            ).coerceAtMost(if (scheme.tcpingConcurrencyMode == SpeedTestSelectionMode.COUNT) 512 else 100),
            urlTestConcurrency = normalizeSelectionValue(
                scheme.urlTestConcurrencyMode,
                scheme.urlTestConcurrency
            ).coerceAtMost(if (scheme.urlTestConcurrencyMode == SpeedTestSelectionMode.COUNT) 128 else 100),
            bandwidthTestConcurrency = normalizeSelectionValue(
                scheme.bandwidthTestConcurrencyMode,
                scheme.bandwidthTestConcurrency
            ).coerceAtMost(if (scheme.bandwidthTestConcurrencyMode == SpeedTestSelectionMode.COUNT) 3 else 100)
        )
    }

    private fun encodeLegacySpeedTestSettingsRecords(schemes: List<LegacySpeedTestSettings>): String {
        val array = JSONArray()
        schemes.distinctBy(LegacySpeedTestSettings::id).forEach { scheme ->
            val normalized = normalizeLegacySpeedTestSettings(scheme)
            array.put(
                JSONObject().apply {
                    put("id", normalized.id)
                    if (normalized.id == LegacySpeedTestSettings.DEFAULT_ID) {
                        put("defaultsVersion", SettingsRepository.DEFAULT_SPEED_TEST_PRESET_REVISION)
                    }
                    put("name", normalized.name)
                    put("builtIn", normalized.builtIn)
                    put("tcpingEnabled", normalized.tcpingEnabled)
                    put("urlTestEnabled", normalized.urlTestEnabled)
                    put("bandwidthEnabled", normalized.bandwidthEnabled)
                    put("linkTypeTestEnabled", normalized.linkTypeTestEnabled)
                    put("linkTypeAcceptDirect", normalized.linkTypeAcceptDirect)
                    put("linkTypeAcceptRelay", normalized.linkTypeAcceptRelay)
                    put("linkTypeAcceptCdnRelay", normalized.linkTypeAcceptCdnRelay)
                    put("unlockTestsEnabled", normalized.unlockTestsEnabled)
                    put("unlockTestIds", JSONArray().apply {
                        normalized.unlockTestIds.forEach(::put)
                    })
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
                    // Keep the legacy percentage keys so older builds fall back to a safe full test.
                    put(
                        "tcpingPercent",
                        normalized.tcpingSelectionValue.takeIf {
                            normalized.tcpingSelectionMode == SpeedTestSelectionMode.PERCENT
                        } ?: 100
                    )
                    put(
                        "urlTestPercent",
                        normalized.urlTestSelectionValue.takeIf {
                            normalized.urlTestSelectionMode == SpeedTestSelectionMode.PERCENT
                        } ?: 100
                    )
                    put(
                        "bandwidthPercent",
                        normalized.bandwidthSelectionValue.takeIf {
                            normalized.bandwidthSelectionMode == SpeedTestSelectionMode.PERCENT
                        } ?: 100
                    )
                    put("tcpingTimeoutMs", normalized.tcpingTimeoutMs)
                    // Keep the old key for downgrade compatibility.
                    put("urlTestTimeoutMs", normalized.urlTestWarmupTimeoutMs)
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
                }
            )
        }
        return array.toString()
    }
}
