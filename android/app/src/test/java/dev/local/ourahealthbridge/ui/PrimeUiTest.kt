package dev.local.ourahealthbridge.ui

import dev.local.ourahealthbridge.analysis.LatestLocalMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimeUiTest {
    @Test
    fun backNavigationReturnsThroughSettingsBeforeLeavingHome() {
        assertEquals(PrimeDestination.SETTINGS, backDestination(PrimeDestination.ADVANCED_LEGACY))
        assertEquals(PrimeDestination.HOME, backDestination(PrimeDestination.SETTINGS))
        assertEquals(null, backDestination(PrimeDestination.HOME))
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
