package xyz.a202132.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object LauncherIdentityManager {
    private const val TAG = "LauncherIdentity"

    fun apply(context: Context, presetId: String) {
        val selectedId = LauncherIdentityPresets.find(presetId).id
        val packageManager = context.packageManager

        val orderedPresets = buildList {
            add(LauncherIdentityPresets.find(selectedId))
            addAll(LauncherIdentityPresets.all.filterNot { it.id == selectedId })
        }
        orderedPresets.forEach { preset ->
            val component = ComponentName(
                context.packageName,
                context.packageName + "." + preset.aliasName
            )
            val state = if (preset.id == selectedId) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                packageManager.setComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure { error ->
                Log.e(TAG, "无法切换启动器入口：${preset.aliasName}", error)
            }
        }
    }
}
