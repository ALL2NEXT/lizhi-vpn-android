package io.github.HanJason233.chimera.core.xray

import android.content.Context
import io.github.HanJason233.chimera.core.CoreSession
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.NativeCoreSession
import io.github.HanJason233.chimera.data.model.ProxyCore

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

    override fun lanTraffic() = delegate.lanTraffic()

    suspend fun start(config: String): XrayNativeSession {
        delegate.start(config)
        return this
    }

    override fun close() = delegate.close()
}
