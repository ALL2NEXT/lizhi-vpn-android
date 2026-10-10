package io.github.HanJason233.chimera.network.subscription
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.HanJason233.chimera.data.model.ACCOUNT_TYPE_FORMAL
import io.github.HanJason233.chimera.data.model.ACCOUNT_TYPE_GUEST

class SubscriptionListParserTest {

    private val parser = SubscriptionListParser()

    @Test
    fun parseIgnoresLegacyEncryptionFlags() {
        val result = parser.parse(
            """
            {"subscriptions":[{"name":"A","url":"https://example.com/a","encrypted":true},{"name":"B","url":"https://example.com/b","isEncrypted":"1"},{"name":"C","url":"https://example.com/c"}]}
            """.trimIndent()
        ).getOrThrow()

        assertEquals(listOf("https://example.com/a", "https://example.com/b", "https://example.com/c"), result.sources.map { it.url })
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
        assertEquals("https://worker.example/api/subscription/1", result.sources.single().url)
    }

    @Test
    fun parseReadsMinimumAccountType() {
        val result = parser.parse(
            """
            {
              "subscriptions": [
                {"name":"A","url":"https://example.com/a","minAccountType":"formal"},
                {"name":"B","url":"https://example.com/b"},
                {"name":"C","url":"https://example.com/c","requiredAccountType":"invalid"}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals(listOf(ACCOUNT_TYPE_FORMAL, ACCOUNT_TYPE_GUEST, ACCOUNT_TYPE_GUEST), result.sources.map { it.minAccountType })
    }

}
