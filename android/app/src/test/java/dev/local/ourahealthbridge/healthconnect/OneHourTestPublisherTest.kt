package dev.local.ourahealthbridge.healthconnect

import androidx.health.connect.client.records.metadata.Device
import dev.local.ourahealthbridge.analysis.HealthConnectCandidatePreview
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.analysis.HeartRateRecordCandidate
import dev.local.ourahealthbridge.analysis.HeartRateSampleCandidate
import dev.local.ourahealthbridge.analysis.HrvRecordCandidate
import dev.local.ourahealthbridge.analysis.SummaryTimingRule
import dev.local.ourahealthbridge.analysis.BeatSourceCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneHourTestPublisherTest {
    @Test
    fun selectsLatestCompletedHourWithHrvAndExactIds() {
        val first = hour(1_000_000L, "first")
        val preferred = hour(5_000_000L, "preferred")
        val laterWithoutHrv = hour(9_000_000L, "later")
        val hrv = HrvRecordCandidate("hrv-id", 5_100_000L, 42.0, -28_800)
        val candidates = setOfCandidates(listOf(first, preferred, laterWithoutHrv), listOf(hrv))

        val selected = OneHourTestSelector.select(candidates, 20_000_000L)!!

        assertEquals(preferred.clientRecordId, selected.heartRate.clientRecordId)
        assertEquals(listOf(preferred.clientRecordId, "hrv-id"), selected.allClientRecordIds)
        assertTrue(selected.previewText().contains("Nothing written yet"))
    }

    @Test
    fun refusesIncompleteHour() {
        val candidates = setOfCandidates(listOf(hour(9_000_000L, "future")), emptyList())

        assertNull(OneHourTestSelector.select(candidates, 10_000_000L))
    }

    @Test
    fun readBackComparatorVerifiesExactStoredRecords() {
        val hr = hour(5_000_000L, "hr-id")
        val hrv = HrvRecordCandidate("hrv-id", 5_100_000L, 42.0, -28_800)
        val selection = OneHourTestSelection(hr, listOf(hrv))
        val result = OneHourReadBackComparator.compare(
            selection = selection,
            storedHeartRate = listOf(
                StoredHeartRateSnapshot(
                    clientRecordId = "hr-id",
                    startUnixMillis = hr.startUnixMillis,
                    endUnixMillis = hr.endUnixMillis,
                    startZoneOffsetSeconds = -28_800,
                    endZoneOffsetSeconds = -28_800,
                    samples = listOf(hr.samples.single().unixMillis to 60L),
                    deviceType = Device.TYPE_RING,
                    deviceManufacturer = "Oura",
                    deviceModel = "Gen 3 Horizon",
                ),
            ),
            storedHrv = listOf(
                StoredHrvSnapshot(
                    clientRecordId = "hrv-id",
                    unixMillis = hrv.unixMillis,
                    zoneOffsetSeconds = -28_800,
                    rmssdMillis = 42.0,
                    deviceType = Device.TYPE_RING,
                    deviceManufacturer = "Oura",
                    deviceModel = "Gen 3 Horizon",
                ),
            ),
        )

        assertTrue(result.passed)
        assertEquals(1, result.heartRateSamplesMatched)
        assertEquals(1, result.hrvRecordsMatched)
        assertTrue(result.statusText().contains("source, client IDs, timestamps"))
    }

    @Test
    fun readBackComparatorReportsMismatchCategoriesWithoutValues() {
        val selection = OneHourTestSelection(hour(5_000_000L, "hr-id"), emptyList())
        val result = OneHourReadBackComparator.compare(selection, emptyList(), emptyList())

        assertTrue(!result.passed)
        assertTrue(result.mismatchCategories.contains("HR record count/ID"))
        assertTrue(result.statusText().contains("mismatch categories"))
    }

    private fun hour(start: Long, id: String) = HeartRateRecordCandidate(
        clientRecordId = id,
        startUnixMillis = start,
        endUnixMillis = start + 3_600_000L,
        samples = listOf(HeartRateSampleCandidate(start + 150_000L, 60.0)),
        startZoneOffsetSeconds = -28_800,
        endZoneOffsetSeconds = -28_800,
    )

    private fun setOfCandidates(
        heartRate: List<HeartRateRecordCandidate>,
        hrv: List<HrvRecordCandidate>,
    ) = HealthConnectCandidateSet(
        preview = HealthConnectCandidatePreview(
            packedInputBeats = 0,
            qualityInputBeats = 0,
            crossStreamMatches = 0,
            sharedMinutePackedSuppressed = 0,
            deduplicatedBeats = 0,
            heartRateSamples = heartRate.sumOf { it.samples.size },
            heartRateRecords = heartRate.size,
            hrvRecords = hrv.size,
            sleepRecords = 0,
            distinctClientRecordIds = heartRate.size + hrv.size,
            totalRecordCandidates = heartRate.size + hrv.size,
            timingRule = SummaryTimingRule.ENDING_AT_EVENT,
            sourceCoverage = BeatSourceCoverage(0, 0, 0, 0, 0),
        ),
        heartRateRecords = heartRate,
        hrvRecords = hrv,
        sleepRecords = emptyList(),
    )
}
