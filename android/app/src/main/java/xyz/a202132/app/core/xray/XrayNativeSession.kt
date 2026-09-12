package xyz.a202132.app.core.xray

import android.content.Context
import xyz.a202132.app.core.CoreSession
import xyz.a202132.app.core.LocalSocksEndpoint
import xyz.a202132.app.core.NativeCoreSession
import xyz.a202132.app.data.model.ProxyCore

/** Xray runtime hosted by the standalone Android PIE runner. */
internal class XrayNativeSession(
    context: Context,
    override val endpoint: LocalSocksEndpoint,
    onFailure: (String) -> Unit
) : CoreSession {
    private val delegate = NativeCoreSession(
        context = context,
        core = ProxyCore.XRAY,
        endpoint = endpoint,
        executableName = "libxray.so",
        processLabel = "Xray",
        onFailure = onFailure
    )

    override val core = ProxyCore.XRAY

    suspend fun start(config: String): XrayNativeSession {
        delegate.start(config)
        return this
    }

    override fun close() = delegate.close()
}
