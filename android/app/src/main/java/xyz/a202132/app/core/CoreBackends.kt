package xyz.a202132.app.core

import xyz.a202132.app.core.singbox.SingBoxBackend
import xyz.a202132.app.core.xray.XrayBackend

internal object CoreBackends {
    // Mihomo is added here once its native adapter and capabilities are implemented.
    val registry = CoreRegistry(listOf(SingBoxBackend(), XrayBackend()))
}
