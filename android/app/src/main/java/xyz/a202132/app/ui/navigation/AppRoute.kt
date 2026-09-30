package xyz.a202132.app.ui.navigation

object AppRoute {
    const val MAIN = "main"
    const val OTHER_CONFIG = "other_config"
    const val PER_APP_INBOUND = "per_app_inbound"
    const val INBOUND = "inbound"
    const val CORE_ROUTING = "core_routing"
    const val OUTBOUND = "outbound"
    const val SPEED_TEST_SCHEMES = "speed_test_schemes"
    const val SPEED_TEST_SCHEME_EDITOR = "speed_test_scheme_editor/{schemeId}"
    const val ACCOUNT_SETTINGS = "account_settings"
    const val SKIN = "skin"
    const val SKIN_EDITOR = "skin_editor/{schemeId}"
    const val QR_SCANNER = "qr_scanner"

    fun skinEditor(schemeId: String): String = "skin_editor/$schemeId"

    fun speedTestSchemeEditor(schemeId: String): String = "speed_test_scheme_editor/$schemeId"
}
