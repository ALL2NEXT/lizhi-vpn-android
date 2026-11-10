package io.github.HanJason233.chimera.platform

import io.github.HanJason233.chimera.R

data class LauncherIdentityPreset(
    val id: String,
    val displayName: String,
    val previewIconResource: Int,
    val aliasName: String
)

object LauncherIdentityPresets {
    // 仅保留前三种名称和图标组合，供主题设置与桌面入口共用。
    val all = listOf(
        LauncherIdentityPreset("android", "荔枝VPN", R.mipmap.ic_launcher, "LauncherAliasAndroid"),
        LauncherIdentityPreset("shield", "荔枝VPN（盾牌）", R.mipmap.ic_launcher_shield, "LauncherAliasShield"),
        LauncherIdentityPreset("bolt", "荔枝VPN（闪电）", R.mipmap.ic_launcher_bolt, "LauncherAliasBolt")
    )

    fun find(id: String): LauncherIdentityPreset =
        all.firstOrNull { it.id == id } ?: all.first()
}
