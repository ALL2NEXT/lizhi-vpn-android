package io.github.HanJason233.chimera.core.singbox

import android.content.Context
import io.github.HanJason233.chimera.core.CoreSession
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.NativeCoreSession
import io.github.HanJason233.chimera.data.model.ProxyCore

/** 使用独立 Android PIE 运行器托管 sing-box-lx 核心进程。 */
internal class SingBoxLxNativeSession(
    context: Context,
    override val endpoint: LocalSocksEndpoint,
    onFailure: (String) -> Unit
) : CoreSession {
    private val delegate = NativeCoreSession(
        context = context,
        core = ProxyCore.SING_BOX_LX,
        endpoint = endpoint,
        executableName = "libsingboxlx.so",
        processLabel = "sing-box-lx",
        onFailure = onFailure,
        extraArguments = listOf("--directory", context.filesDir.absolutePath)
    )

    override val core = ProxyCore.SING_BOX_LX

    override fun lanTraffic() = delegate.lanTraffic()

    suspend fun start(config: String): SingBoxLxNativeSession {
        delegate.start(config)
        return this
    }

    override fun close() = delegate.close()
}
