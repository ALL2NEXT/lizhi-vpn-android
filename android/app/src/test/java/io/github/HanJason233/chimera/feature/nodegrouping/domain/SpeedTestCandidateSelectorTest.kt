package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestCandidateSelectorTest {
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
