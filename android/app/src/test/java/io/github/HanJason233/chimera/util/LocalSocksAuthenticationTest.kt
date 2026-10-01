package io.github.HanJason233.chimera.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.SocksCredentials

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
}
