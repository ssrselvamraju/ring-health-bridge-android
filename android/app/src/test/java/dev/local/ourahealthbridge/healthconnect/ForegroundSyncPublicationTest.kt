package dev.local.ourahealthbridge.healthconnect

import dev.local.ourahealthbridge.analysis.BeatSourceCoverage
import dev.local.ourahealthbridge.analysis.HealthConnectCandidatePreview
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.analysis.HeartRateRecordCandidate
import dev.local.ourahealthbridge.analysis.HeartRateSampleCandidate
import dev.local.ourahealthbridge.analysis.HrvRecordCandidate
import dev.local.ourahealthbridge.analysis.SleepRecordCandidate
import dev.local.ourahealthbridge.analysis.SummaryTimingRule
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSyncPublicationTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 8, 11)
    private val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = dayStart + 20 * HOUR

    @Test
    fun unchangedCandidatesProduceNoPublicationWork() {
        val candidates = candidateSet(hr = listOf(heartRate(60.0)))

        val plan = ForegroundPublicationPlanner.plan(candidates, candidates, zone, now)

        assertEquals(0, plan.affectedDateCount)
    }

    @Test
    fun changedHeartRatePublishesOnlyHrHrvForItsDate() {
        val before = candidateSet(hr = listOf(heartRate(60.0)))
        val after = candidateSet(
            hr = listOf(heartRate(62.0)),
            hrv = listOf(HrvRecordCandidate("hrv", dayStart + 2 * HOUR, 40.0, -25_200)),
        )

        val plan = ForegroundPublicationPlanner.plan(before, after, zone, now)

        assertEquals(1, plan.affectedDateCount)
        assertTrue(plan.dates.single().publishHrHrv)
        assertFalse(plan.dates.single().publishSleep)
    }

    @Test
    fun unchangedButUnverifiedCandidatesAreRecoveredAfterAnInterruptedRun() {
        val candidates = candidateSet(hr = listOf(heartRate(60.0)))
        val changed = ForegroundPublicationPlanner.plan(candidates, candidates, zone, now)

        val recovered = ForegroundPublicationPlanner.includePendingPublication(
            changed,
            candidates,
            zone,
            now,
        ) { DailyPublicationState(hrHrvVerified = false, sleepVerified = false) }

        assertEquals(1, recovered.affectedDateCount)
        assertTrue(recovered.dates.single().publishHrHrv)
        assertFalse(recovered.dates.single().publishSleep)
    }

    @Test
    fun publicationStatePlanningNeedsOnlyTheCurrentCandidateSnapshot() {
        val candidates = candidateSet(hr = listOf(heartRate(60.0)))

        val verified = ForegroundPublicationPlanner.planAgainstPublicationState(
            candidates, zone, now,
        ) { DailyPublicationState(hrHrvVerified = true, sleepVerified = true) }
        val outdated = ForegroundPublicationPlanner.planAgainstPublicationState(
            candidates, zone, now,
        ) { DailyPublicationState(hrHrvVerified = false, sleepVerified = true, hrHrvOutdated = true) }

        assertEquals(0, verified.affectedDateCount)
        assertEquals(1, outdated.affectedDateCount)
        assertTrue(outdated.dates.single().publishHrHrv)
    }

    @Test
    fun completedSleepPublishesButRecentSleepIsDeferred() {
        val completed = sleep(end = now - ForegroundPublicationPlanner.SLEEP_COMPLETION_GRACE_MILLIS - 1)
        val recent = sleep(end = now - ForegroundPublicationPlanner.SLEEP_COMPLETION_GRACE_MILLIS + 1)

        val completedPlan = ForegroundPublicationPlanner.plan(
            candidateSet(), candidateSet(sleep = listOf(completed)), zone, now,
        )
        val recentPlan = ForegroundPublicationPlanner.plan(
            candidateSet(), candidateSet(sleep = listOf(recent)), zone, now,
        )

        assertTrue(completedPlan.dates.single().publishSleep)
        assertEquals(0, completedPlan.deferredRecentSleepRecords)
        assertEquals(0, recentPlan.affectedDateCount)
        assertEquals(1, recentPlan.deferredRecentSleepRecords)
    }

    @Test
    fun aNewLatestSleepDoesNotDeleteThePreviouslyCompletedDate() {
        val oldSleep = sleep(end = dayStart + 8 * HOUR, id = "old-sleep")
        val nextDate = date.plusDays(1)
        val newEnd = nextDate.atStartOfDay(zone).toInstant().toEpochMilli() + 8 * HOUR
        val newSleep = sleep(end = newEnd, id = "new-sleep")

        val plan = ForegroundPublicationPlanner.plan(
            candidateSet(sleep = listOf(oldSleep)),
            candidateSet(sleep = listOf(newSleep)),
            zone,
            newEnd + 2 * HOUR,
        )

        assertEquals(1, plan.affectedDateCount)
        assertEquals(nextDate, plan.dates.single().current.localDate)
        assertTrue(plan.dates.single().previous.sleep.isEmpty())
    }

    @Test
    fun failedRunDoesNotClaimThatNothingChanged() {
        val text = ForegroundRunReport(
            passed = false,
            syncSessions = 1,
            receivedEvents = 0,
            addedEvents = 0,
            storedEvents = null,
            affectedDates = 0,
            heartRateRecords = 0,
            heartRateSamples = 0,
            hrvRecords = 0,
            sleepRecords = 0,
            obsoleteRecordsDeleted = 0,
            deferredRecentSleepRecords = 0,
            detail = "ring unavailable",
        ).statusText()

        assertTrue(text.contains("publication not completed"))
        assertFalse(text.contains("no candidate dates changed"))
    }

    private fun heartRate(bpm: Double) = HeartRateRecordCandidate(
        clientRecordId = "hr-hour",
        startUnixMillis = dayStart + HOUR,
        endUnixMillis = dayStart + 2 * HOUR,
        samples = listOf(HeartRateSampleCandidate(dayStart + HOUR + 150_000, bpm)),
        startZoneOffsetSeconds = -25_200,
        endZoneOffsetSeconds = -25_200,
    )

    private fun sleep(end: Long, id: String = "sleep") = SleepRecordCandidate(
        clientRecordId = id,
        startUnixMillis = end - 8 * HOUR,
        endUnixMillis = end,
        startZoneOffsetSeconds = -25_200,
        endZoneOffsetSeconds = -25_200,
    )

    private fun candidateSet(
        hr: List<HeartRateRecordCandidate> = emptyList(),
        hrv: List<HrvRecordCandidate> = emptyList(),
        sleep: List<SleepRecordCandidate> = emptyList(),
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

    private companion object {
        const val HOUR = 3_600_000L
    }
}
