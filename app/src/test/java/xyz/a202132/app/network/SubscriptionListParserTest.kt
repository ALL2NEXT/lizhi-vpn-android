package xyz.a202132.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionListParserTest {

    private val parser = SubscriptionListParser()

    @Test
    fun parseReadsEncryptedFlagAndLegacyAlias() {
        val result = parser.parse(
            """
            {
              "subscriptions": [
                {"name":"A","url":"https://example.com/a","encrypted":true},
                {"name":"B","url":"https://example.com/b","isEncrypted":"1"},
                {"name":"C","url":"https://example.com/c"}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertTrue(result.sources[0].encrypted)
        assertTrue(result.sources[1].encrypted)
        assertFalse(result.sources[2].encrypted)
    }

    @Test
    fun parseAcceptsWorkerManagedSubscription() {
        val result = parser.parse(
            """
            {
              "subscriptions": [
                {
                  "id":"1",
                  "name":"KV nodes",
                  "url":"https://worker.example/api/subscription/1",
                  "enabled":true,
                  "encrypted":true
                }
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertTrue(result.sources.single().encrypted)
        assertTrue(result.sources.single().url.endsWith("/api/subscription/1"))
    }
}
