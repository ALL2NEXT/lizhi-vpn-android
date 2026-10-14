package io.github.HanJason233.chimera.core.xray

import io.github.HanJason233.chimera.platform.isRunningCompat
import io.github.HanJason233.chimera.platform.awaitExit
import io.github.HanJason233.chimera.platform.terminateCompat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.core.*
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.core.route.CoreRuleRepository
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.platform.UnderlayController
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class XrayBackend : CoreBackend {
    override val id = ProxyCore.XRAY
    override val capabilities = CoreCapabilities(ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet())

    override fun supports(node: Node): Boolean =
        node.type in capabilities.protocols && CoreCompatibility.xraySupports(node)

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val smartRulesEnabled = request.settings?.let {
            (it.proxyMode == ProxyMode.SMART ||
                (it.lanInbound.enabled && it.lanInbound.proxyMode == ProxyMode.SMART)) && (
                it.smartRoutingSettings.rules.any { rule ->
                    rule.enabled && (
                        rule.type == SmartRoutingRuleType.DOMESTIC_DOMAIN ||
                            rule.type == SmartRoutingRuleType.DOMESTIC_IP
                    )
                }
                )
        } == true
        val rules = if (smartRulesEnabled) {
            CoreRuleRepository.load(context)
        } else null
        val config = XrayVpnConfig.generate(request, rules)
        val temporary = File(context.cacheDir, "xray-check-${UUID.randomUUID()}.json")
        var process: Process? = null
        try {
            temporary.writeText(config)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            process = ProcessBuilder(executable.absolutePath, "--check", "--config", temporary.absolutePath)
                .redirectErrorStream(true).start()
            check(process.awaitExit(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Xray 配置校验失败，请检查节点协议与传输参数"
            }
        } finally {
            process?.let { if (it.isRunningCompat) { it.terminateCompat(force = true); it.awaitExit(3, TimeUnit.SECONDS) } }
            temporary.delete()
        }
        PreparedCore(id, request, config)
    }

    override suspend fun start(context: Context, scope: CoroutineScope, prepared: PreparedCore,
        underlay: UnderlayController?, onFailure: (String) -> Unit): CoreSession {
        require(prepared.core == id)
        return XrayNativeSession(context, prepared.request.endpoint, onFailure).start(prepared.config)
    }
}
