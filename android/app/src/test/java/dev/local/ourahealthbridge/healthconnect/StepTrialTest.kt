package dev.local.ourahealthbridge.healthconnect

import org.junit.Assert.assertTrue
import org.junit.Test

class StepTrialTest {
    @Test
    fun marksExactWalkBoundariesBeforeSync() {
        val baseline = StepTrialBaseline(
            startedUnixMillis = 1_000L,
            healthWindowStartUnixMillis = 0L,
            ringTagCounts = emptyMap(),
            healthSourceTotals = emptyMap(),
        )

        val text = StepTrialSummarizer.markedText(
            baseline,
            MarkedStepTrialEnd(finishedUnixMillis = 301_000L, manualSteps = 500),
        )

        assertTrue(text.contains("start 1970-01-01T00:00:01Z"))
        assertTrue(text.contains("end 1970-01-01T00:05:01Z"))
        assertTrue(text.contains("manual 500 steps over 5.0 min"))
        assertTrue(text.contains("exact interval is saved"))
    }

    @Test
    fun reportsRingAndSourceDeltasWithoutCombiningSources() {
        val baseline = StepTrialBaseline(
            startedUnixMillis = 1_000L,
            healthWindowStartUnixMillis = 0L,
            ringTagCounts = mapOf(0x50 to 10L, 0x7e to 0L),
            healthSourceTotals = mapOf(
                "com.sec.android.app.shealth" to 100L,
                "android" to 50L,
            ),
        )
        val final = StepTrialSnapshot(
            ringTagCounts = mapOf(0x50 to 12L, 0x7e to 1L),
            healthSourceTotals = mapOf(
                "com.sec.android.app.shealth" to 140L,
                "android" to 55L,
            ),
        )

        val text = StepTrialSummarizer.finishedText(baseline, 61_000L, 50, final)

        assertTrue(text.contains("start 1970-01-01T00:00:01Z"))
        assertTrue(text.contains("end 1970-01-01T00:01:01Z"))
        assertTrue(text.contains("manual 50 steps"))
        assertTrue(text.contains("0x50 +2"))
        assertTrue(text.contains("0x7e +1"))
        assertTrue(text.contains("Samsung Health +40"))
        assertTrue(text.contains("Pixel on-device (legacy) +5"))
    }
}
