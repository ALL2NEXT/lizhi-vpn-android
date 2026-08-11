package xyz.a202132.app.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingBoxConfigParsingTest {
    @Test
    fun normalizesKnownTransportAliases() {
        assertEquals("ws", normalizeTransportType("websocket"))
        assertEquals("grpc", normalizeTransportType("gun"))
        assertEquals("httpupgrade", normalizeTransportType("http-upgrade"))
        assertEquals("unsupported", normalizeTransportType("xhttp"))
    }

    @Test
    fun parsesHysteriaPortRangesWithoutChangingSinglePorts() {
        assertEquals(
            listOf("443", "1000:2000"),
            parseHysteria2ServerPorts("hysteria2://secret@example.com:443,1000-2000?sni=x")
        )
    }

    @Test
    fun parsesTypedJsonValuesSafely() {
        val json = JsonParser.parseString(
            """{"enabled":"yes","count":"7","invalid":{}}"""
        ).asJsonObject

        assertEquals(true, parseJsonBoolean(json.get("enabled")))
        assertEquals(7, parseJsonInt(json.get("count")))
        assertNull(parseJsonString(json.get("invalid")))
    }
}
