package dev.local.ourahealthbridge.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestLocalMetricsTest {
    @Test
    fun selectsTheLatestAlreadyDerivedSimpleMetrics() {
        val candidates = HealthConnectCandidateSet(
            preview = HealthConnectCandidatePreview(
                packedInputBeats = 0,
                qualityInputBeats = 0,
                crossStreamMatches = 0,
                sharedMinutePackedSuppressed = 0,
                deduplicatedBeats = 0,
                heartRateSamples = 2,
                heartRateRecords = 1,
                hrvRecords = 2,
                sleepRecords = 2,
                distinctClientRecordIds = 5,
                totalRecordCandidates = 5,
                timingRule = SummaryTimingRule.ENDING_AT_EVENT,
                sourceCoverage = BeatSourceCoverage(0, 0, 0, 0, 0),
            ),
            heartRateRecords = listOf(
                HeartRateRecordCandidate(
                    clientRecordId = "hr",
                    startUnixMillis = 1_000L,
                    endUnixMillis = 3_000L,
                    samples = listOf(
                        HeartRateSampleCandidate(1_500L, 60.0),
                        HeartRateSampleCandidate(2_500L, 64.0),
                    ),
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                ),
            ),
            hrvRecords = listOf(
                HrvRecordCandidate("hrv-1", 1_600L, 35.0, 0),
                HrvRecordCandidate("hrv-2", 2_600L, 42.0, 0),
            ),
            sleepRecords = listOf(
                SleepRecordCandidate("sleep-1", 100L, 1_100L, 0, 0),
                SleepRecordCandidate("sleep-2", 1_200L, 2_200L, 0, 0),
            ),
        )

        val latest = LatestLocalMetricsSelector.select(candidates)

        assertEquals(64.0, latest.heartRateBpm!!, 0.0)
        assertEquals(2_500L, latest.heartRateUnixMillis)
        assertEquals(42.0, latest.hrvRmssdMillis!!, 0.0)
        assertEquals(2_600L, latest.hrvUnixMillis)
        assertEquals(1_200L, latest.sleepStartUnixMillis)
        assertEquals(2_200L, latest.sleepEndUnixMillis)
    }
}
