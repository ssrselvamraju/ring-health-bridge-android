package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.RawRingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepResearchAuditTest {
    @Test
    fun inventoriesCandidateTagsWithoutInterpretingBodiesAsCounts() {
        val events = listOf(
            raw(0x50, 100u, 4),
            raw(0x51, 110u, 8),
            raw(0x7e, 200u, 14),
            raw(0x7f, 200u, 14),
            raw(0x7e, 300u, 14),
            raw(0x7f, 305u, 14),
        )

        val inventory = RingStepInventoryBuilder.build(events)

        assertEquals(1, inventory.tags.single { it.tag == 0x50 }.count)
        assertEquals(mapOf(14 to 2), inventory.tags.single { it.tag == 0x7e }.bodyLengths)
        assertEquals(1, inventory.exactStepMotionPairs)
        assertEquals(2, inventory.nearStepMotionPairs)
        assertTrue(inventory.statusText().contains("stepmotion"))
    }

    @Test
    fun reportsWhenNoCandidateTagsExist() {
        val inventory = RingStepInventoryBuilder.build(listOf(raw(0x47, 100u, 6)))

        assertTrue(inventory.statusText().contains("none of 0x50/0x51/0x52/0x7e/0x7f found"))
    }

    private fun raw(tag: Int, timestamp: UInt, bodyBytes: Int) = RawRingEvent(
        tag = tag,
        ringTimestampDeciseconds = timestamp,
        body = ByteArray(bodyBytes),
    )
}
