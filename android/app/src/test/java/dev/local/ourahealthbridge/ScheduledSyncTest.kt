package dev.local.ourahealthbridge

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledSyncTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun alignsFirstPeriodicRunToTheNextThreeHourLocalSlot() {
        val now = LocalDateTime.of(2026, 8, 13, 7, 10).atZone(zone).toInstant().toEpochMilli()

        val delay = BackgroundScheduleTiming.delayToNextSlotMillis(now, zone)

        assertEquals(110L * 60L * 1_000L, delay)
    }

    @Test
    fun rollsTheLateEveningSlotToLocalMidnight() {
        val now = LocalDateTime.of(2026, 8, 13, 23, 30).atZone(zone).toInstant().toEpochMilli()

        val delay = BackgroundScheduleTiming.delayToNextSlotMillis(now, zone)

        assertEquals(30L * 60L * 1_000L, delay)
    }

    @Test
    fun nominalPeriodicWindowIsThreeHoursAfterTheLastDispatch() {
        val last = 1_000_000L

        assertEquals(last + 3L * 60L * 60L * 1_000L, BackgroundScheduleTiming.nominalNextDispatchMillis(last))
    }

    @Test
    fun flagsOnlyMateriallyLatePeriodicDispatches() {
        val last = 1_000_000L

        assertFalse(BackgroundScheduleTiming.appearsLate(last + 5L * 60L * 60L * 1_000L, last))
        assertTrue(BackgroundScheduleTiming.appearsLate(last + 5L * 60L * 60L * 1_000L + 1L, last))
    }
}
