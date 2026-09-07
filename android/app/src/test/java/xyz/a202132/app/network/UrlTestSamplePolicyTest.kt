package xyz.a202132.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlTestSamplePolicyTest {
    @Test
    fun selectsOnlyFastestAvailableFinalists() {
        val firstPass = listOf(
            result("slow", 180),
            result("failed", -2, false),
            result("fast", 40),
            result("middle", 90)
        )

        assertEquals(
            listOf("fast", "middle"),
            UrlTestSamplePolicy.selectFinalistIds(firstPass, limit = 2)
        )
    }

    @Test
    fun usesMedianOfInitialAndTwoSuccessfulRechecks() {
        val firstPass = listOf(result("node", 40))
        val stabilized = UrlTestSamplePolicy.stabilize(
            firstPass,
            mapOf("node" to listOf(result("node", 110), result("node", 80)))
        )

        assertEquals(80, stabilized.single().latency)
    }

    @Test
    fun keepsInitialSampleWhenRechecksFail() {
        val firstPass = listOf(result("node", 55))
        val stabilized = UrlTestSamplePolicy.stabilize(
            firstPass,
            mapOf("node" to listOf(result("node", -2, false), result("node", -2, false)))
        )

        assertEquals(firstPass, stabilized)
    }

    private fun result(
        nodeId: String,
        latency: Int,
        available: Boolean = true
    ) = LatencyTester.LatencyResult(nodeId, latency, available)
}
