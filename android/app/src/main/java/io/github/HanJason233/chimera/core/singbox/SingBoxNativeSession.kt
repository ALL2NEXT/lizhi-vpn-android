package io.github.HanJason233.chimera.core.singbox

import android.content.Context
import io.github.HanJason233.chimera.core.CoreSession
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.NativeCoreSession
import io.github.HanJason233.chimera.data.model.ProxyCore

/** sing-box runtime hosted by the standalone Android PIE runner. */
internal class SingBoxNativeSession(
    context: Context,
    override val endpoint: LocalSocksEndpoint,
    onFailure: (String) -> Unit
) : CoreSession {
    private val delegate = NativeCoreSession(
        context = context,
        core = ProxyCore.SING_BOX,
        endpoint = endpoint,
        executableName = "libsingbox.so",
        processLabel = "sing-box",
        onFailure = onFailure,
        extraArguments = listOf("--directory", context.filesDir.absolutePath)
    )

    override val core = ProxyCore.SING_BOX

    suspend fun start(config: String): SingBoxNativeSession {
        delegate.start(config)
        return this
    }

    override fun close() = delegate.close()
}
