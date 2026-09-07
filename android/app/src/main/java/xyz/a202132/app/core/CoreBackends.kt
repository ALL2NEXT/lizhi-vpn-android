package xyz.a202132.app.core

import xyz.a202132.app.core.singbox.SingBoxBackend
import xyz.a202132.app.core.xray.XrayBackend

internal object CoreBackends {
    val registry = CoreRegistry(listOf(SingBoxBackend(), XrayBackend()))
}
