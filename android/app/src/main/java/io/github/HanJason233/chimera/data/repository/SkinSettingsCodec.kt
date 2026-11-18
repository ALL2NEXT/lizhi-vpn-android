package io.github.HanJason233.chimera.data.repository

import androidx.datastore.preferences.core.*
import org.json.JSONArray
import org.json.JSONObject
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle
import io.github.HanJason233.chimera.data.model.HomeIconEffect
import io.github.HanJason233.chimera.data.model.HomeIconMotionEffect
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

        return SkinSettings()
    }

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

}
