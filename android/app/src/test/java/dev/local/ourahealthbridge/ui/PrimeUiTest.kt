package dev.local.ourahealthbridge.ui

import dev.local.ourahealthbridge.analysis.LatestLocalMetrics
import dev.local.ourahealthbridge.healthconnect.ForegroundRunOutcome
import dev.local.ourahealthbridge.healthconnect.ForegroundRunUiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimeUiTest {
    @Test
    fun backNavigationReturnsThroughSettingsBeforeLeavingHome() {
        assertEquals(PrimeDestination.SETTINGS, backDestination(PrimeDestination.ADVANCED_LEGACY))
        assertEquals(PrimeDestination.HOME, backDestination(PrimeDestination.SETTINGS))
        assertEquals(PrimeDestination.HOME, backDestination(PrimeDestination.SETUP))
        assertEquals(null, backDestination(PrimeDestination.HOME))
    }

    @Test
    fun guidedSetupDerivesProgressFromVerifiedReality() {
        assertEquals(GuidedSetupStage.CREDENTIAL, guidedSetupStage(PrimeUiState()))
        assertEquals(
            GuidedSetupStage.ASSOCIATION,
            guidedSetupStage(PrimeUiState(keyPresent = true)),
        )
        assertEquals(
            GuidedSetupStage.AUTHENTICATION,
            guidedSetupStage(PrimeUiState(keyPresent = true, associated = true, bonded = true)),
        )
        assertEquals(
            GuidedSetupStage.HEALTH_CONNECT,
            guidedSetupStage(
                PrimeUiState(keyPresent = true, associated = true, bonded = true, authenticationVerified = true),
            ),
        )
        assertEquals(
            GuidedSetupStage.FIRST_SYNC,
            guidedSetupStage(
                PrimeUiState(
                    keyPresent = true,
                    associated = true,
                    bonded = true,
                    authenticationVerified = true,
                    healthConnect = HealthConnectState.READY,
                ),
            ),
        )
        assertEquals(
            GuidedSetupStage.COMPLETE,
            guidedSetupStage(
                PrimeUiState(
                    keyPresent = true,
                    associated = true,
                    bonded = true,
                    authenticationVerified = true,
                    healthConnect = HealthConnectState.READY,
                    firstSyncVerified = true,
                    run = ForegroundRunUiSnapshot(
                        hasTypedSummary = true,
                        outcome = ForegroundRunOutcome.PASSED,
                        source = "test",
                        currentStage = null,
                        lastAttemptMillis = 1L,
                        lastFinishedMillis = 1L,
                        lastSuccessMillis = 1L,
                        affectedDates = 1,
                        heartRateSamples = 1,
                        hrvRecords = 1,
                        sleepRecords = 1,
                        detail = "passed",
                        ringBatteryPercent = null,
                        ringBatteryMeasuredMillis = null,
                    ),
                ),
            ),
        )
    }

    private val now = 10L * 60L * 60L * 1_000L

    @Test
    fun heartRateIsRecentThroughThirtyMinutes() {
        val atBoundary = heartRatePresentation(metrics(now - 30L * 60L * 1_000L), now)
        val afterBoundary = heartRatePresentation(metrics(now - 30L * 60L * 1_000L - 1L), now)

        assertEquals(HeartRateFreshness.RECENT, atBoundary.freshness)
        assertEquals(HeartRateFreshness.STALE, afterBoundary.freshness)
        assertEquals(58, atBoundary.bpm)
    }

    @Test
    fun missingHeartRateIsUnavailableRatherThanWearInference() {
        val result = heartRatePresentation(
            LatestLocalMetrics(null, null, 40.0, now, null, null),
            now,
        )

        assertEquals(HeartRateFreshness.UNAVAILABLE, result.freshness)
        assertNull(result.bpm)
    }

    @Test
    fun futureTimestampIsStaleInsteadOfClaimingARecentMeasurement() {
        assertEquals(
            HeartRateFreshness.STALE,
            heartRatePresentation(metrics(now + 1L), now).freshness,
        )
    }

    private fun metrics(time: Long) = LatestLocalMetrics(
        heartRateBpm = 57.6,
        heartRateUnixMillis = time,
        hrvRmssdMillis = null,
        hrvUnixMillis = null,
        sleepStartUnixMillis = null,
        sleepEndUnixMillis = null,
    )
}
