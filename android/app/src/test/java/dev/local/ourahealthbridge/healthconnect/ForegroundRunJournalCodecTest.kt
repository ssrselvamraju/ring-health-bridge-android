package dev.local.ourahealthbridge.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRunJournalCodecTest {
    @Test
    fun roundTripsStructuredRunContextAndReportText() {
        val entry = ForegroundRunJournalEntry(
            attemptUnixMillis = 1_000L,
            finishedUnixMillis = 27_000L,
            source = "periodic",
            startingPhoneBatteryPercent = 19,
            startingPhoneCharging = false,
            endingPhoneBatteryPercent = 18,
            endingPhoneCharging = false,
            reportText = "Foreground sync/publish passed - received 81; exact read-back verified.",
        )

        assertEquals(entry, ForegroundRunJournalCodec.decode(ForegroundRunJournalCodec.encode(entry)))
    }

    @Test
    fun summaryIncludesDurationSourceAndPhoneBatteryWithoutRawHealthValues() {
        val entry = ForegroundRunJournalEntry(
            attemptUnixMillis = 1_000L,
            finishedUnixMillis = 26_001L,
            source = "foreground-open",
            startingPhoneBatteryPercent = 55,
            startingPhoneCharging = true,
            endingPhoneBatteryPercent = 56,
            endingPhoneCharging = true,
            reportText = "Foreground sync/publish passed.",
        )

        val summary = entry.summaryText()

        assertTrue(summary.contains("foreground-open"))
        assertTrue(summary.contains("duration 26s"))
        assertTrue(summary.contains("phone 55%->56%"))
        assertTrue(summary.contains("charging -> charging"))
    }
}
