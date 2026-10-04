package io.github.HanJason233.chimera.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestStageScopeTest {
    @Test
    fun `all quality toggle combinations use the nearest enabled predecessor`() {
        for (mask in 0..7) {
            val scheme = SpeedTestScheme.default().copy(
                scope = SpeedTestScope.ALL_GROUPS,
                tcpingEnabled = mask and 1 != 0,
                urlTestEnabled = mask and 2 != 0,
                bandwidthEnabled = mask and 4 != 0
            )
            assertEquals(SpeedTestStageScope.Initial(SpeedTestScope.ALL_GROUPS),
                scheme.inputScopeFor(SpeedTestStage.TCPING))
            assertEquals(
                if (scheme.tcpingEnabled) SpeedTestStageScope.PreviousResult(SpeedTestStage.TCPING)
                else SpeedTestStageScope.Initial(SpeedTestScope.ALL_GROUPS),
                scheme.inputScopeFor(SpeedTestStage.URL_TEST)
            )
            assertEquals(
                when {
                    scheme.urlTestEnabled -> SpeedTestStageScope.PreviousResult(SpeedTestStage.URL_TEST)
                    scheme.tcpingEnabled -> SpeedTestStageScope.PreviousResult(SpeedTestStage.TCPING)
                    else -> SpeedTestStageScope.Initial(SpeedTestScope.ALL_GROUPS)
                },
                scheme.inputScopeFor(SpeedTestStage.BANDWIDTH)
            )
            assertEquals(SpeedTestStageScope.ConnectedNode, scheme.inputScopeFor(SpeedTestStage.LINK_TYPE))
            assertEquals(SpeedTestStageScope.ConnectedNode, scheme.inputScopeFor(SpeedTestStage.IP_QUALITY))
        }
    }

    @Test
    fun `first enabled stage respects every initial scope`() {
        for (scope in SpeedTestScope.entries) {
            val scheme = SpeedTestScheme.default().copy(
                scope = scope, tcpingEnabled = false, urlTestEnabled = false, bandwidthEnabled = true
            )
            assertEquals(listOf(SpeedTestStage.BANDWIDTH), scheme.enabledQualityStages)
            assertEquals(SpeedTestStageScope.Initial(scope), scheme.inputScopeFor(SpeedTestStage.BANDWIDTH))
        }
    }

    @Test
    fun `categories preserve the five stage execution order`() {
        assertEquals(listOf(1, 2, 3, 4, 5), SpeedTestStage.entries.map { it.number })
        assertEquals(listOf(SpeedTestStage.TCPING, SpeedTestStage.URL_TEST, SpeedTestStage.BANDWIDTH),
            SpeedTestStage.entries.filter { it.category == SpeedTestCategory.LINK_QUALITY })
        assertEquals(SpeedTestCategory.LINK_TYPE, SpeedTestStage.LINK_TYPE.category)
        assertEquals(SpeedTestCategory.IP_QUALITY, SpeedTestStage.IP_QUALITY.category)
    }
}
