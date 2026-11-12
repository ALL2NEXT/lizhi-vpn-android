package io.github.HanJason233.chimera.ui.navigation

object AppRoute {
    const val MAIN = "main"
    const val OTHER_CONFIG = "other_config"
    const val DNS_SETTINGS = "dns_settings"
    const val PROTOCOL_CORE_SETTINGS = "protocol_core_settings"
    const val REGION_LOOKUP_SETTINGS = "region_lookup_settings"
    const val SPLIT_ROUTING_SETTINGS = "split_routing_settings"
    const val PER_APP_INBOUND = "per_app_inbound/{mode}"
    const val INBOUND_OUTBOUND = "inbound_outbound"
    const val CORE_ROUTING = "core_routing/{mode}"
    const val ROUTING_ASSETS = "routing_assets"
    const val SPEED_TEST_SETTINGS = "speed_test_settings"
    const val ACCOUNT_SETTINGS_DETAIL = "account_settings_detail/{page}"
    const val SKIN = "skin"
    const val QR_SCANNER = "qr_scanner"

    fun perAppInbound(mode: String): String = "per_app_inbound/$mode"

    fun coreRouting(mode: String): String = "core_routing/$mode"

    fun accountSettingsDetail(page: String): String = "account_settings_detail/$page"
}
