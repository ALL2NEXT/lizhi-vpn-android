package xyz.a202132.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.local.PendingDailyTrafficRecord

class TrafficReporterTest {
    @Test
    fun onlyAcknowledgesRecordsWithSuccessfulResponses() = runBlocking {
        val records = listOf(
            record("success"),
            record("no-response"),
        )
        val attempted = mutableListOf<String>()
        val acknowledged = mutableListOf<String>()

        processPendingTrafficRecords(
            records = records,
            report = { record ->
                attempted += record.id
                record.id == "success"
            },
            acknowledge = { record -> acknowledged += record.id },
        )

        assertEquals(listOf("success", "no-response"), attempted)
        assertEquals(listOf("success"), acknowledged)
    }

    private fun record(id: String) = PendingDailyTrafficRecord(
        id = id,
        day = "2026-08-30",
        endedAt = 1L,
        uploadBytes = 2L,
        downloadBytes = 3L,
    )
}
