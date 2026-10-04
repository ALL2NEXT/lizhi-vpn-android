package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestCandidateSelectorTest {
    @Test
    fun `percentage uses the previous available results as its denominator`() {
        val initial = (1..100).toList()
        val tcpingResults = (81..100).toList()
        val result = SpeedTestCandidateSelector.selectForStage(
            SpeedTestScheme.default().copy(
                urlTestSelectionMode = SpeedTestSelectionMode.PERCENT, urlTestSelectionValue = 50
            ), SpeedTestStage.URL_TEST, initial,
            mapOf(SpeedTestStage.TCPING to tcpingResults)
        )
        assertEquals((81..90).toList(), result)
    }

    @Test
    fun `skipped URL stage lets bandwidth consume TCPing results`() {
        val scheme = SpeedTestScheme.default().copy(
            urlTestEnabled = false, bandwidthEnabled = true,
            bandwidthSelectionMode = SpeedTestSelectionMode.COUNT, bandwidthSelectionValue = 2
        )
        val result = SpeedTestCandidateSelector.selectForStage(
            scheme, SpeedTestStage.BANDWIDTH, (1..100).toList(),
            mapOf(SpeedTestStage.TCPING to listOf(90, 30, 10))
        )
        assertEquals(listOf(90, 30), result)
    }

    @Test
    fun `URL as first stage uses the initial candidates`() {
        val scheme = SpeedTestScheme.default().copy(
            tcpingEnabled = false, urlTestSelectionMode = SpeedTestSelectionMode.PERCENT, urlTestSelectionValue = 50
        )
        assertEquals(listOf(1, 2, 3), SpeedTestCandidateSelector.selectForStage(
            scheme, SpeedTestStage.URL_TEST, listOf(1, 2, 3, 4, 5), emptyMap()
        ))
    }

    @Test(expected = IllegalStateException::class)
    fun `missing predecessor results never silently expand to the initial scope`() {
        SpeedTestCandidateSelector.selectForStage(
            SpeedTestScheme.default(), SpeedTestStage.URL_TEST, listOf(1, 2, 3), emptyMap()
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `connected node tests cannot be treated as candidate group tests`() {
        SpeedTestCandidateSelector.selectForStage(
            SpeedTestScheme.default().copy(linkTypeTestEnabled = true),
            SpeedTestStage.LINK_TYPE, listOf(1, 2, 3), emptyMap()
        )
    }

    @Test
    fun `percentage selects the first rounded up portion`() {
        val result = SpeedTestCandidateSelector.takeTop(
            items = listOf(1, 2, 3, 4, 5),
            mode = SpeedTestSelectionMode.PERCENT,
            value = 50
        )

        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `count selects the first requested number`() {
        val result = SpeedTestCandidateSelector.takeTop(
            items = listOf(1, 2, 3, 4, 5),
            mode = SpeedTestSelectionMode.COUNT,
            value = 2
        )

        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `count never exceeds the available nodes`() {
        val result = SpeedTestCandidateSelector.takeTop(
            items = listOf(1, 2),
            mode = SpeedTestSelectionMode.COUNT,
            value = 10
        )

        assertEquals(listOf(1, 2), result)
    }
}
