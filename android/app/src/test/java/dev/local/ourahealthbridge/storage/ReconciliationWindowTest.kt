package dev.local.ourahealthbridge.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconciliationWindowTest {
    @Test
    fun ordinarySyncUsesRecentLookback() {
        assertEquals(
            7_000L,
            ReconciliationWindow.lowerBound(
                latestRingTimestamp = 10_000L,
                earliestNewTimestamp = 9_500L,
                recentLookbackDeciseconds = 3_000L,
                newEventMarginDeciseconds = 500L,
            ),
        )
    }

    @Test
    fun oldBacklogExpandsWindowAroundEarliestNewEvent() {
        assertEquals(
            1_500L,
            ReconciliationWindow.lowerBound(
                latestRingTimestamp = 10_000L,
                earliestNewTimestamp = 2_000L,
                recentLookbackDeciseconds = 3_000L,
                newEventMarginDeciseconds = 500L,
            ),
        )
    }

    @Test
    fun lowerBoundNeverBecomesNegative() {
        assertEquals(
            0L,
            ReconciliationWindow.lowerBound(200L, 100L, 3_000L, 500L),
        )
    }
}
