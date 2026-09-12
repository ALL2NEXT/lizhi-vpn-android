package xyz.a202132.app.core.singbox

import android.content.Context
import xyz.a202132.app.core.CoreSession
import xyz.a202132.app.core.LocalSocksEndpoint
import xyz.a202132.app.core.NativeCoreSession
import xyz.a202132.app.data.model.ProxyCore

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
