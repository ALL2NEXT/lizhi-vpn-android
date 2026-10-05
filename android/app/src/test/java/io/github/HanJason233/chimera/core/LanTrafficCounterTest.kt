package io.github.HanJason233.chimera.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanTrafficCounterTest {
    private val counter = LanTrafficCounter()
    private fun record(json: String) = counter.record(JsonParser.parseString(json).asJsonObject)

    @Test fun cumulativeAndFinalSnapshotsAreNotAddedTogether() {
        record("""{"event":"lan-traffic","upload":100,"download":200}""")
        record("""{"event":"lan-traffic","upload":100,"download":200}""")
        record("""{"event":"lan-traffic","upload":150,"download":280}""")
        assertEquals(CoreTraffic(150, 280), counter.total)
        record("""{"event":"lan-traffic","upload":90,"download":300}""")
        assertEquals(CoreTraffic(150, 300), counter.total)
    }

    @Test fun otherSourcesAndMalformedSnapshotsAreIgnored() {
        record("""{"event":"traffic","upload":900,"download":900}""")
        record("""{"event":"lan-traffic","upload":100}""")
        record("""{"event":"lan-traffic","upload":-1,"download":100}""")
        record("""{"event":"lan-traffic","upload":"invalid","download":100}""")
        assertNull(counter.total)
    }

    @Test fun newSessionStartsWithFreshCounters() {
        record("""{"event":"lan-traffic","upload":150,"download":280}""")
        assertNull(LanTrafficCounter().total)
    }
}
