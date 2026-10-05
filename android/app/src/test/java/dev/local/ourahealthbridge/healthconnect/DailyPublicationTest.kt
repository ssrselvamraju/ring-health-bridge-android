package dev.local.ourahealthbridge.healthconnect

import dev.local.ourahealthbridge.analysis.BeatSourceCoverage
import dev.local.ourahealthbridge.analysis.HealthConnectCandidatePreview
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.analysis.HeartRateRecordCandidate
import dev.local.ourahealthbridge.analysis.HeartRateSampleCandidate
import dev.local.ourahealthbridge.analysis.HrvRecordCandidate
import dev.local.ourahealthbridge.analysis.SleepRecordCandidate
import dev.local.ourahealthbridge.analysis.SummaryTimingRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPublicationTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun selectsRecordsByExperiencedLocalDateAndSleepWakeDate() {
        val date = LocalDate.of(2026, 8, 10)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val hr = heartRate(start + 3_600_000, "hr")
        val hrv = HrvRecordCandidate("hrv", start + 4_000_000, 40.0, -25_200)
        val sleep = SleepRecordCandidate("sleep", start - 7 * 3_600_000, start + 2 * 3_600_000, -25_200, -25_200)
        val selection = DailyPublicationSelector.select(candidateSet(listOf(hr), listOf(hrv), listOf(sleep)), date, zone)

        assertEquals(listOf(hr), selection.heartRate)
        assertEquals(listOf(hrv), selection.hrv)
        assertEquals(listOf(sleep), selection.sleep)
        assertEquals(listOf(date), DailyPublicationSelector.availableDates(candidateSet(listOf(hr), listOf(hrv), listOf(sleep)), zone))
    }

    @Test
    fun mapsAndExactlyVerifiesHrHrvAndSleep() {
        val date = LocalDate.of(2026, 8, 10)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val hr = heartRate(start + 3_600_000, "hr")
        val hrv = HrvRecordCandidate("hrv", start + 4_000_000, 40.0, -25_200)
        val sleep = SleepRecordCandidate("sleep", start - 7 * 3_600_000, start + 2 * 3_600_000, -25_200, -25_200)
        val selection = DailyPublicationSelection(date, zone, listOf(hr), listOf(hrv), listOf(sleep))

        val hrResult = DailyPublicationComparator.compareHrHrv(
            selection,
            listOf(HealthConnectRecordMapper.heartRate(hr)),
            listOf(HealthConnectRecordMapper.hrv(hrv)),
        )
        val sleepRecord = HealthConnectRecordMapper.sleep(sleep)
        val sleepResult = DailyPublicationComparator.compareSleep(selection, listOf(sleepRecord))

        assertTrue(hrResult.passed)
        assertEquals(1, hrResult.matchedHeartRateRecords)
        assertEquals(1, hrResult.matchedHrvRecords)
        assertTrue(sleepResult.passed)
        assertTrue(sleepRecord.stages.isEmpty())
        assertEquals(HealthConnectRecordMapper.CLIENT_RECORD_VERSION, sleepRecord.metadata.clientRecordVersion)
    }

    @Test
    fun candidateFingerprintIsStableButChangesWithValues() {
        val date = LocalDate.of(2026, 8, 10)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val original = DailyPublicationSelection(date, zone, listOf(heartRate(start, "hr")), emptyList(), emptyList())
        val retry = DailyPublicationSelection(date, zone, listOf(heartRate(start, "hr")), emptyList(), emptyList())
        val changedHr = heartRate(start, "hr").copy(
            samples = listOf(HeartRateSampleCandidate(start + 150_000, 61.0)),
        )
        val changed = DailyPublicationSelection(date, zone, listOf(changedHr), emptyList(), emptyList())

        assertEquals(original.hrHrvFingerprint(), retry.hrHrvFingerprint())
        assertTrue(original.hrHrvFingerprint() != changed.hrHrvFingerprint())
    }

    @Test
    fun readBackRangeIncludesSleepThatStartsBeforeItsWakeDate() {
        val date = LocalDate.of(2026, 8, 22)
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val sleepStart = dayStart.minusSeconds(68 * 60L)
        val sleep = SleepRecordCandidate(
            "cross-midnight-sleep",
            sleepStart.toEpochMilli(),
            dayStart.plusSeconds(14 * 60L).toEpochMilli(),
            -25_200,
            -25_200,
        )

        val range = DailyPublicationSelection(date, zone, emptyList(), emptyList(), listOf(sleep)).readBackRange()

        assertEquals(sleepStart, range.first)
        assertEquals(dayEnd, range.second)
    }

    @Test
    fun readBackRangeRemainsTheLocalDayWithoutCrossMidnightSleep() {
        val date = LocalDate.of(2026, 8, 22)
        val selection = DailyPublicationSelection(date, zone, emptyList(), emptyList(), emptyList())

        assertEquals(
            date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant(),
            selection.readBackRange(),
        )
    }

    private fun heartRate(start: Long, id: String) = HeartRateRecordCandidate(
        clientRecordId = id,
        startUnixMillis = start,
        endUnixMillis = start + 3_600_000,
        samples = listOf(HeartRateSampleCandidate(start + 150_000, 60.0)),
        startZoneOffsetSeconds = -25_200,
        endZoneOffsetSeconds = -25_200,
    )

    private fun candidateSet(
        hr: List<HeartRateRecordCandidate>,
        hrv: List<HrvRecordCandidate>,
        sleep: List<SleepRecordCandidate>,
    ) = HealthConnectCandidateSet(
        preview = HealthConnectCandidatePreview(
            packedInputBeats = 0,
            qualityInputBeats = 0,
            crossStreamMatches = 0,
            sharedMinutePackedSuppressed = 0,
            deduplicatedBeats = 0,
            heartRateSamples = hr.sumOf { it.samples.size },
            heartRateRecords = hr.size,
            hrvRecords = hrv.size,
            sleepRecords = sleep.size,
            distinctClientRecordIds = hr.size + hrv.size + sleep.size,
            totalRecordCandidates = hr.size + hrv.size + sleep.size,
            timingRule = SummaryTimingRule.ENDING_AT_EVENT,
            sourceCoverage = BeatSourceCoverage(0, 0, 0, 0, 0),
        ),
        heartRateRecords = hr,
        hrvRecords = hrv,
        sleepRecords = sleep,
    )
}
