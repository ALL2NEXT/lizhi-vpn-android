package io.github.HanJason233.chimera.network.subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun parseIgnoresLegacyAccountTypes() {
        val result = parser.parse(
            """
            {
              "subscriptions": [
                {"name":"A","url":"https://example.com/a","minAccountType":"formal"},
                {"name":"B","url":"https://example.com/b","allowedAccountTypes":["guest"]},
                {"name":"C","url":"https://example.com/c","requiredAccountType":"invalid"}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals(listOf("A", "B", "C"), result.sources.map { it.name })
    }

    @Test
    fun parseAcceptsEmptyAvailableList() {
        assertTrue(parser.parse("""{"subscriptions":[],"settings":{}}""").getOrThrow().sources.isEmpty())
        assertTrue(parser.parse("[]").getOrThrow().sources.isEmpty())
    }

    @Test
    fun filteredListPreservesBackendIdsRegardlessOfOrder() {
        val first = parser.parse("""{"subscriptions":[
            {"id":"42","name":"A","url":"https://worker.example/api/subscription/42"},
            {"id":"43","name":"B","url":"https://worker.example/api/subscription/43"}
        ]}""").getOrThrow()
        val filtered = parser.parse("""{"subscriptions":[
            {"id":"43","name":"B","url":"https://worker.example/api/subscription/43"}
        ]}""").getOrThrow()
        assertEquals(listOf("42", "43"), first.sources.map { it.id })
        assertEquals(first.sources.last().id, filtered.sources.single().id)
    }

    @Test
    fun legacySourcesWithoutIdsUseStableUrls() {
        val first = parser.parse("""[{"url":"https://example.com/a"},{"url":"https://example.com/b"}]""").getOrThrow()
        val filtered = parser.parse("""[{"url":"https://example.com/b"}]""").getOrThrow()
        assertEquals(first.sources.last().id, filtered.sources.single().id)
    }

    @Test
    fun disabledSourcesCanProduceAnEmptyList() {
        val result = parser.parse("""{"subscriptions":[{"id":"42","url":"https://example.com/a","enabled":false}]}""")
        assertTrue(result.getOrThrow().sources.isEmpty())
    }

    @Test
    fun malformedListsStillFailInsteadOfClearingCachedNodes() {
        assertTrue(parser.parse("""{"settings":{}}""").isFailure)
        assertTrue(parser.parse("""{"subscriptions":[{"url":"javascript:invalid"}]}""").isFailure)
    }

}
