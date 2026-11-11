package io.github.HanJason233.chimera.data.repository

import androidx.datastore.preferences.core.*
import org.json.JSONArray
import org.json.JSONObject
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle
import io.github.HanJason233.chimera.data.model.HomeIconEffect
import io.github.HanJason233.chimera.data.model.HomeIconMotionEffect
import io.github.HanJason233.chimera.data.model.LegacySkinScheme
import io.github.HanJason233.chimera.data.model.SkinSettings
import io.github.HanJason233.chimera.data.model.SkinThemeMode
import io.github.HanJason233.chimera.data.model.SpeedTestStage

internal object SkinSettingsCodec {
    internal fun normalizeSkinColor(argb: Long): Long {
        return 0xFF000000L or (argb and 0x00FFFFFFL)
    }

    internal fun decodeSkinSettings(preferences: Preferences): SkinSettings {
        val storedSettings = preferences[SettingsRepository.SKIN_SETTINGS_JSON]
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (storedSettings != null) {
            val defaults = SkinSettings()
            fun optionalPath(key: String): String? = storedSettings.optString(key)
                .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() }

            return SkinSettings(
                appDisplayName = storedSettings.optString(
                    "appDisplayName",
                    SkinSettings.DEFAULT_APP_DISPLAY_NAME
                ).trim().ifBlank { SkinSettings.DEFAULT_APP_DISPLAY_NAME }
                    .let { if (it == "Firefly") "Chimera" else it },
                appIconPresetId = storedSettings.optString(
                    "appIconPresetId",
                    SkinSettings.DEFAULT_APP_ICON_PRESET_ID
                ).trim().ifBlank { SkinSettings.DEFAULT_APP_ICON_PRESET_ID },
                baseColorArgb = normalizeSkinColor(
                    storedSettings.optLong("baseColorArgb", defaults.baseColorArgb)
                ),
                themeMode = SkinThemeMode.fromStorage(
                    storedSettings.optString("themeMode").takeIf(String::isNotBlank)
                ),
                navigationStyle = HomeNavigationStyle.fromStorage(
                    storedSettings.optString("navigationStyle").takeIf(String::isNotBlank)
                ),
                monetEnabled = storedSettings.optBoolean("monetEnabled", defaults.monetEnabled),
                customImagePath = optionalPath("customImagePath"),
                homeDisconnectedImagePath = optionalPath("homeDisconnectedImagePath"),
                homeConnectingImagePath = optionalPath("homeConnectingImagePath"),
                homeDisconnectingImagePath = optionalPath("homeDisconnectingImagePath"),
                homeConnectedImagePath = optionalPath("homeConnectedImagePath"),
                homeIconEffect = HomeIconEffect.fromStorage(
                    storedSettings.optString("homeIconEffect").takeIf(String::isNotBlank)
                ),
                homeIconEffectEnabled = storedSettings.optBoolean(
                    "homeIconEffectEnabled",
                    defaults.homeIconEffectEnabled
                ),
                homeDisconnectedIconEffect = HomeIconMotionEffect.fromStorage(
                    storedSettings.optString(
                        "homeDisconnectedIconEffect",
                        defaults.homeDisconnectedIconEffect.storageName
                    ).takeIf(String::isNotBlank)
                ),
                homeConnectingIconEffect = HomeIconMotionEffect.fromStorage(
                    storedSettings.optString(
                        "homeConnectingIconEffect",
                        defaults.homeConnectingIconEffect.storageName
                    ).takeIf(String::isNotBlank)
                ),
                homeDisconnectingIconEffect = HomeIconMotionEffect.fromStorage(
                    storedSettings.optString(
                        "homeDisconnectingIconEffect",
                        defaults.homeDisconnectingIconEffect.storageName
                    ).takeIf(String::isNotBlank)
                ),
                homeConnectedIconEffect = HomeIconMotionEffect.fromStorage(
                    storedSettings.optString(
                        "homeConnectedIconEffect",
                        defaults.homeConnectedIconEffect.storageName
                    ).takeIf(String::isNotBlank)
                ),
                homeDisconnectedParticleEffectEnabled = storedSettings.optBoolean(
                    "homeDisconnectedParticleEffectEnabled",
                    defaults.homeDisconnectedParticleEffectEnabled
                ),
                homeConnectingParticleEffectEnabled = storedSettings.optBoolean(
                    "homeConnectingParticleEffectEnabled",
                    defaults.homeConnectingParticleEffectEnabled
                ),
                homeDisconnectingParticleEffectEnabled = storedSettings.optBoolean(
                    "homeDisconnectingParticleEffectEnabled",
                    defaults.homeDisconnectingParticleEffectEnabled
                ),
                homeConnectedParticleEffectEnabled = storedSettings.optBoolean(
                    "homeConnectedParticleEffectEnabled",
                    defaults.homeConnectedParticleEffectEnabled
                ),
                showNodeProtocol = storedSettings.optBoolean(
                    "showNodeProtocol",
                    defaults.showNodeProtocol
                ),
                showNodeSecurity = storedSettings.optBoolean(
                    "showNodeSecurity",
                    defaults.showNodeSecurity
                ),
                showNodeTransport = storedSettings.optBoolean(
                    "showNodeTransport",
                    defaults.showNodeTransport
                ),
                showNodeCore = storedSettings.optBoolean("showNodeCore", defaults.showNodeCore),
                showNodeAddress = storedSettings.optBoolean(
                    "showNodeAddress",
                    defaults.showNodeAddress
                ),
                showNodePort = storedSettings.optBoolean("showNodePort", defaults.showNodePort),
                homeTestDisplayStages = storedSettings.optJSONArray("homeTestDisplayStages")?.let { array ->
                    val storedStages = (0 until array.length()).map { array.optString(it) }.toSet()
                    SpeedTestStage.entries.filter { it.name in storedStages }
                } ?: defaults.homeTestDisplayStages
            )
        }

        // 迁移时沿用旧版当前选中的主题；没有选择记录时与旧版一样优先采用首个自定义主题。
        val legacySchemes = decodeLegacySkinSchemes(preferences)
        val activeId = preferences[SettingsRepository.LEGACY_ACTIVE_SKIN_SCHEME_ID]
            ?.takeIf { id -> legacySchemes.any { it.id == id } }
            ?: legacySchemes.firstOrNull { !it.isDefault }?.id
            ?: LegacySkinScheme.DEFAULT_SCHEME_ID
        return legacySchemes.firstOrNull { it.id == activeId }
            ?.toSkinSettings()
            ?: LegacySkinScheme.default().toSkinSettings()
    }

    private fun LegacySkinScheme.toSkinSettings() = SkinSettings(
        appDisplayName = appDisplayName,
        appIconPresetId = appIconPresetId,
        baseColorArgb = baseColorArgb,
        themeMode = themeMode,
        navigationStyle = navigationStyle,
        monetEnabled = monetEnabled,
        customImagePath = customImagePath,
        homeDisconnectedImagePath = homeDisconnectedImagePath,
        homeConnectingImagePath = homeConnectingImagePath,
        homeDisconnectingImagePath = homeDisconnectingImagePath,
        homeConnectedImagePath = homeConnectedImagePath,
        homeIconEffect = homeIconEffect,
        homeIconEffectEnabled = homeIconEffectEnabled,
        homeDisconnectedIconEffect = homeDisconnectedIconEffect,
        homeConnectingIconEffect = homeConnectingIconEffect,
        homeDisconnectingIconEffect = homeDisconnectingIconEffect,
        homeConnectedIconEffect = homeConnectedIconEffect,
        homeDisconnectedParticleEffectEnabled = homeDisconnectedParticleEffectEnabled,
        homeConnectingParticleEffectEnabled = homeConnectingParticleEffectEnabled,
        homeDisconnectingParticleEffectEnabled = homeDisconnectingParticleEffectEnabled,
        homeConnectedParticleEffectEnabled = homeConnectedParticleEffectEnabled,
        showNodeProtocol = showNodeProtocol,
        showNodeSecurity = showNodeSecurity,
        showNodeTransport = showNodeTransport,
        showNodeCore = showNodeCore,
        showNodeAddress = showNodeAddress,
        showNodePort = showNodePort
    )

    internal fun encodeSkinSettings(settings: SkinSettings): String = JSONObject().apply {
        put("appDisplayName", settings.appDisplayName)
        put("appIconPresetId", settings.appIconPresetId)
        put("baseColorArgb", normalizeSkinColor(settings.baseColorArgb))
        put("themeMode", settings.themeMode.storageName)
        put("navigationStyle", settings.navigationStyle.storageName)
        put("monetEnabled", settings.monetEnabled)
        settings.customImagePath?.let { put("customImagePath", it) }
        settings.homeDisconnectedImagePath?.let { put("homeDisconnectedImagePath", it) }
        settings.homeConnectingImagePath?.let { put("homeConnectingImagePath", it) }
        settings.homeDisconnectingImagePath?.let { put("homeDisconnectingImagePath", it) }
        settings.homeConnectedImagePath?.let { put("homeConnectedImagePath", it) }
        put("homeIconEffect", settings.homeIconEffect.storageName)
        put("homeIconEffectEnabled", settings.homeIconEffectEnabled)
        put("homeDisconnectedIconEffect", settings.homeDisconnectedIconEffect.storageName)
        put("homeConnectingIconEffect", settings.homeConnectingIconEffect.storageName)
        put("homeDisconnectingIconEffect", settings.homeDisconnectingIconEffect.storageName)
        put("homeConnectedIconEffect", settings.homeConnectedIconEffect.storageName)
        put("homeDisconnectedParticleEffectEnabled", settings.homeDisconnectedParticleEffectEnabled)
        put("homeConnectingParticleEffectEnabled", settings.homeConnectingParticleEffectEnabled)
        put("homeDisconnectingParticleEffectEnabled", settings.homeDisconnectingParticleEffectEnabled)
        put("homeConnectedParticleEffectEnabled", settings.homeConnectedParticleEffectEnabled)
        put("showNodeProtocol", settings.showNodeProtocol)
        put("showNodeSecurity", settings.showNodeSecurity)
        put("showNodeTransport", settings.showNodeTransport)
        put("showNodeCore", settings.showNodeCore)
        put("showNodeAddress", settings.showNodeAddress)
        put("showNodePort", settings.showNodePort)
        put("homeTestDisplayStages", JSONArray(settings.homeTestDisplayStages.map { it.name }))
    }.toString()

    private fun decodeLegacySkinSchemes(preferences: Preferences): List<LegacySkinScheme> {
        fun defaultSchemeWithLegacyNodeDisplaySettings(): LegacySkinScheme =
            LegacySkinScheme.default().copy(
                showNodeProtocol = preferences[SettingsRepository.NODE_LABEL_SHOW_PROTOCOL] ?: false,
                showNodeSecurity = preferences[SettingsRepository.NODE_LABEL_SHOW_SECURITY] ?: false,
                showNodeTransport = preferences[SettingsRepository.NODE_LABEL_SHOW_TRANSPORT] ?: false,
                showNodeCore = preferences[SettingsRepository.NODE_LABEL_SHOW_CORE] ?: false
            )

        val storedJson = preferences[SettingsRepository.LEGACY_SKIN_SCHEMES_JSON]
        if (!storedJson.isNullOrBlank()) {
            val storedSchemes = runCatching {
                val array = JSONArray(storedJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isBlank()) continue
                        // 旧版统一旋转开关只作用于连接中和断开中，读取时映射到这两个状态。
                        val legacyHomeIconRotationEnabled = item.optBoolean(
                            "homeIconRotationEnabled",
                            false
                        )
                        val defaultsVersion = item.optInt("defaultsVersion", 0)
                        if (id == LegacySkinScheme.DEFAULT_SCHEME_ID &&
                            defaultsVersion < SettingsRepository.DEFAULT_SKIN_PARTICLE_PRESET_REVISION
                        ) {
                            item.put("homeIconEffectEnabled", LegacySkinScheme.default().homeIconEffectEnabled)
                        }
                        if (id == LegacySkinScheme.DEFAULT_SCHEME_ID &&
                            defaultsVersion < SettingsRepository.DEFAULT_SKIN_PRESET_REVISION
                        ) {
                            val defaultScheme = LegacySkinScheme.default()
                            // Revert the just introduced defaults when upgrading from revision 2.
                            if (defaultsVersion >= 2 &&
                                item.optString("homeDisconnectedIconEffect") ==
                                HomeIconMotionEffect.BREATHE.storageName
                            ) {
                                item.put(
                                    "homeDisconnectedIconEffect",
                                    HomeIconMotionEffect.NONE.storageName
                                )
                            }
                            if (defaultsVersion >= 2 &&
                                item.optString("homeConnectedIconEffect") ==
                                HomeIconMotionEffect.ROTATE.storageName
                            ) {
                                item.put(
                                    "homeConnectedIconEffect",
                                    HomeIconMotionEffect.NONE.storageName
                                )
                            }
                            if (item.optString("homeConnectingIconEffect") in setOf("", "none")) {
                                item.put(
                                    "homeConnectingIconEffect",
                                    defaultScheme.homeConnectingIconEffect.storageName
                                )
                            }
                            if (item.optString("homeDisconnectingIconEffect") in setOf("", "none") &&
                                !legacyHomeIconRotationEnabled
                            ) {
                                item.put(
                                    "homeDisconnectingIconEffect",
                                    defaultScheme.homeDisconnectingIconEffect.storageName
                                )
                            }
                        }
                        // 旧版只有统一粒子开关，新字段缺失时沿用旧设置作为四种状态的初始值。
                        val legacyHomeIconEffectEnabled = item.optBoolean(
                            "homeIconEffectEnabled",
                            id != LegacySkinScheme.DEFAULT_SCHEME_ID
                        )
                        add(
                            LegacySkinScheme(
                                id = id,
                                appDisplayName = item.optString(
                                    "appDisplayName",
                                    SkinSettings.DEFAULT_APP_DISPLAY_NAME
                                ).trim().ifBlank { SkinSettings.DEFAULT_APP_DISPLAY_NAME }
                                    .let { if (it == "Firefly") "Chimera" else it },
                                appIconPresetId = item.optString(
                                    "appIconPresetId",
                                    SkinSettings.DEFAULT_APP_ICON_PRESET_ID
                                ).trim().ifBlank { SkinSettings.DEFAULT_APP_ICON_PRESET_ID },
                                baseColorArgb = normalizeSkinColor(
                                    item.optLong(
                                        "baseColorArgb",
                                        SkinSettings.DEFAULT_BASE_COLOR_ARGB
                                    )
                                ),
                                showNodeProtocol = item.optBoolean(
                                    "showNodeProtocol",
                                    preferences[SettingsRepository.NODE_LABEL_SHOW_PROTOCOL] ?: false
                                ),
                                showNodeSecurity = item.optBoolean(
                                    "showNodeSecurity",
                                    preferences[SettingsRepository.NODE_LABEL_SHOW_SECURITY] ?: false
                                ),
                                showNodeTransport = item.optBoolean(
                                    "showNodeTransport",
                                    preferences[SettingsRepository.NODE_LABEL_SHOW_TRANSPORT] ?: false
                                ),
                                showNodeCore = item.optBoolean(
                                    "showNodeCore",
                                    preferences[SettingsRepository.NODE_LABEL_SHOW_CORE] ?: false
                                ),
                                showNodeAddress = item.optBoolean("showNodeAddress", false),
                                showNodePort = item.optBoolean("showNodePort", false),
                                themeMode = SkinThemeMode.fromStorage(
                                    item.optString("themeMode").takeIf { it.isNotBlank() }
                                ),
                                navigationStyle = HomeNavigationStyle.fromStorage(
                                    item.optString("navigationStyle").takeIf { it.isNotBlank() }
                                ),
                                monetEnabled = item.optBoolean("monetEnabled", false),
                                customImagePath = item.optString("customImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeDisconnectedImagePath = item.optString("homeDisconnectedImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeConnectingImagePath = item.optString("homeConnectingImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeDisconnectingImagePath = item.optString("homeDisconnectingImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeConnectedImagePath = item.optString("homeConnectedImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeIconEffect = HomeIconEffect.fromStorage(
                                    item.optString("homeIconEffect").takeIf { it.isNotBlank() }
                                ),
                                homeIconEffectEnabled = item.optBoolean(
                                    "homeIconEffectEnabled",
                                    id != LegacySkinScheme.DEFAULT_SCHEME_ID
                                ),
                                homeDisconnectedParticleEffectEnabled = item.optBoolean(
                                    "homeDisconnectedParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeConnectingParticleEffectEnabled = item.optBoolean(
                                    "homeConnectingParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeDisconnectingParticleEffectEnabled = item.optBoolean(
                                    "homeDisconnectingParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeConnectedParticleEffectEnabled = item.optBoolean(
                                    "homeConnectedParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeDisconnectedIconEffect = HomeIconMotionEffect.fromStorage(
                                    item.optString("homeDisconnectedIconEffect")
                                        .takeIf { it.isNotBlank() }
                                ),
                                homeConnectingIconEffect = HomeIconMotionEffect.fromStorage(
                                        item.optString("homeConnectingIconEffect")
                                            .takeIf { it.isNotBlank() }
                                        ?: if (legacyHomeIconRotationEnabled) {
                                            HomeIconMotionEffect.ROTATE.storageName
                                        } else {
                                            null
                                        }
                                ),
                                homeDisconnectingIconEffect = HomeIconMotionEffect.fromStorage(
                                        item.optString("homeDisconnectingIconEffect")
                                            .takeIf { it.isNotBlank() }
                                        ?: if (legacyHomeIconRotationEnabled) {
                                            HomeIconMotionEffect.ROTATE.storageName
                                        } else {
                                            null
                                        }
                                ),
                                homeConnectedIconEffect = HomeIconMotionEffect.fromStorage(
                                    item.optString("homeConnectedIconEffect")
                                        .takeIf { it.isNotBlank() }
                                )
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
            val defaultScheme = storedSchemes.firstOrNull { it.isDefault }
                ?: defaultSchemeWithLegacyNodeDisplaySettings()
            return listOf(defaultScheme) + storedSchemes
                .filterNot { it.isDefault }
                .distinctBy { it.id }
        }

        return listOf(defaultSchemeWithLegacyNodeDisplaySettings())
    }
}
