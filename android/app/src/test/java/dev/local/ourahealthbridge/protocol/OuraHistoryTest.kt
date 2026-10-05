package dev.local.ourahealthbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraHistoryTest {
    @Test
    fun parsesUpstreamBatchSummaryFixture() {
        val packet = OuraPacket.parse("110808009e0e00000300".hex())!!
        val summary = EventBatchAccumulator.parseSummary(packet)!!

        assertEquals(8, summary.eventsReceived)
        assertEquals(0, summary.sleepAnalysisProgress)
        assertEquals(3742u, summary.bytesLeft)
    }

    @Test
    fun retainsRawBodyAndAdvancesPastNewestEvent() {
        val accumulator = EventBatchAccumulator(99u)
        accumulator.accept(OuraPacket(0x43, uintLittleEndian(100u) + "git;abc".encodeToByteArray()))
        accumulator.accept(OuraPacket(0x46, uintLittleEndian(105u) + byteArrayOf(0x01, 0x02)))
        val completed = accumulator.complete(EventBatchSummary(2, 0, 10u))

        assertTrue(completed.progressed)
        assertEquals(106u, completed.nextCursor)
        assertEquals(2, completed.events.size)
        assertTrue(completed.events[0].body.contentEquals("git;abc".encodeToByteArray()))
    }

    @Test
    fun emptyBatchDoesNotAdvanceCursor() {
        val completed = EventBatchAccumulator(123u)
            .complete(EventBatchSummary(0, 0, 50u))

        assertFalse(completed.progressed)
        assertEquals(123u, completed.nextCursor)
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun uintLittleEndian(value: UInt): ByteArray = byteArrayOf(
        (value and 0xffu).toByte(),
        ((value shr 8) and 0xffu).toByte(),
        ((value shr 16) and 0xffu).toByte(),
        ((value shr 24) and 0xffu).toByte(),
    )
}
