package dev.local.ourahealthbridge

import dev.local.ourahealthbridge.healthconnect.ForegroundRunReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBatteryPolicyTest {
    @Test
    fun automaticSyncIsRefusedOnlyBelowFivePercent() {
        assertTrue(RingBatteryPolicy.blocksAutomaticSync(4))
        assertFalse(RingBatteryPolicy.blocksAutomaticSync(5))
        assertFalse(RingBatteryPolicy.blocksAutomaticSync(9))
    }

    @Test
    fun batteryAlertsHaveTwentyAndTenPercentBands() {
        assertEquals(0, RingBatteryPolicy.alertBand(20))
        assertEquals(1, RingBatteryPolicy.alertBand(19))
        assertEquals(1, RingBatteryPolicy.alertBand(10))
        assertEquals(2, RingBatteryPolicy.alertBand(9))
    }

    @Test
    fun lowBatteryRefusalIsReportedAsSkippedRatherThanFailed() {
        val text = ForegroundRunReport(
            passed = false,
            syncSessions = 1,
            receivedEvents = 0,
            addedEvents = 0,
            storedEvents = 100,
            affectedDates = 0,
            heartRateRecords = 0,
            heartRateSamples = 0,
            hrvRecords = 0,
            sleepRecords = 0,
            obsoleteRecordsDeleted = 0,
            deferredRecentSleepRecords = 0,
            startingBatteryPercent = 4,
            skippedLowBattery = true,
        ).statusText()

        assertTrue(text.startsWith("Foreground sync/publish skipped"))
    }
}
