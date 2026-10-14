package io.github.HanJason233.chimera.core

import io.github.HanJason233.chimera.core.singbox.SingBoxBackend
import io.github.HanJason233.chimera.core.singbox.SingBoxLxBackend
import io.github.HanJason233.chimera.core.xray.XrayBackend

internal object CoreBackends {
    val registry = CoreRegistry(listOf(SingBoxBackend(), SingBoxLxBackend(), XrayBackend()))
}
