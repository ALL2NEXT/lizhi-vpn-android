package io.github.HanJason233.chimera.core

import android.app.Application
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.HanJason233.chimera.data.model.LanInboundSettings
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.core.singbox.SingBoxConfigGenerator
import io.github.HanJason233.chimera.core.xray.XrayConfigGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalSocksAuthenticationTest {
    private val endpoint = LocalSocksEndpoint(
        LocalSocksEndpoint.LOOPBACK,
        20_800,
        SocksCredentials("vpn", "secret")
    )

    @Test fun singBoxInboundUsesSessionCredentials() {
        val method = SingBoxConfigGenerator::class.java.getDeclaredMethod(
            "createInbounds", LocalSocksEndpoint::class.java
        ).apply { isAccessible = true }
        val inbound = (method.invoke(SingBoxConfigGenerator(), endpoint) as JsonArray)[0].asJsonObject
        val user = inbound.getAsJsonArray("users")[0].asJsonObject
        assertEquals("vpn", user.get("username").asString)
        assertEquals("secret", user.get("password").asString)
    }

    @Test fun xrayInboundUsesSessionCredentials() {
        val method = XrayConfigGenerator::class.java.getDeclaredMethod(
            "createSocksInbound", LocalSocksEndpoint::class.java
        ).apply { isAccessible = true }
        val inbound = method.invoke(XrayConfigGenerator(), endpoint) as JsonObject
        val settings = inbound.getAsJsonObject("settings")
        val account = settings.getAsJsonArray("accounts")[0].asJsonObject
        assertEquals("password", settings.get("auth").asString)
        assertEquals("vpn", account.get("user").asString)
        assertEquals("secret", account.get("pass").asString)
    }

    @Test fun xrayEnablesInboundStatisticsOnlyForLanSharing() {
        val node = Node(id = "test", name = "test", type = NodeType.SOCKS,
            server = "127.0.0.1", port = 1080, rawLink = "socks://127.0.0.1:1080")
        val generator = XrayConfigGenerator()
        val disabled = JsonParser.parseString(generator.generateCoreConfig(node, endpoint)).asJsonObject
        assertFalse(disabled.has("stats"))
        val enabled = JsonParser.parseString(generator.generateCoreConfig(
            node, endpoint, lanInbound = LanInboundSettings(enabled = true, password = "secret")
        )).asJsonObject
        assertTrue(enabled.has("stats"))
        val system = enabled.getAsJsonObject("policy").getAsJsonObject("system")
        assertTrue(system.get("statsInboundUplink").asBoolean)
        assertTrue(system.get("statsInboundDownlink").asBoolean)
    }
}
