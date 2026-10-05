package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.RawRingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDryRunPreviewTest {
    @Test
    fun countsOnlyPlausibleUtcMappedCandidates() {
        val events = listOf(
            RawRingEvent(0x42, 1_000u, uintLittleEndian(1_800_000_000u)),
            RawRingEvent(0x5d, 2_000u, byteArrayOf(60, 40, 0, 0, 250.toByte(), 0)),
            RawRingEvent(0x46, 3_000u, byteArrayOf(0x1c, 0x0d)),
            RawRingEvent(0x76, 4_000u, uintLittleEndian(4_000u) + uintLittleEndian(40_000u)),
            RawRingEvent(0x43, 5_000u, byteArrayOf(1, 2, 3)),
        )
        val preview = HistoryDryRunPreviewBuilder.build(events)

        assertEquals(5, preview.rawEvents)
        assertEquals(4, preview.decodedEvents)
        assertEquals(1, preview.timeAnchors)
        assertEquals(1, preview.plausibleHeartRateSamples)
        assertEquals(1, preview.plausibleHrvSamples)
        assertEquals(1, preview.temperatureEvents)
        assertEquals(1, preview.plausibleSleepWindows)
        assertTrue(preview.statusText().contains("Nothing written to Health Connect"))
    }

    @Test
    fun reportsExpandedSleepInputsAndTagHistogram() {
        val events = listOf(
            RawRingEvent(0x42, 1_000u, uintLittleEndian(1_800_000_000u)),
            RawRingEvent(0x60, 2_000u, "7d7d7d7d7d7d020406080a0c0007".hex()),
            RawRingEvent(0x72, 3_000u, "b1004601f0001e003e000200".hex()),
            RawRingEvent(0x6b, 4_000u, "30abefaa596ea89669197afffffb".hex()),
        )
        val preview = HistoryDryRunPreviewBuilder.build(events)

        assertEquals(6, preview.plausibleIbiBeats)
        assertEquals(1, preview.sleepAccelerometerEvents)
        assertEquals(1, preview.motionPeriodEvents)
        assertEquals(4, preview.utcMappedEvents)
        assertEquals(4, preview.topTags.size)
        assertEquals(1_800_000_000_000L, preview.mappedStartUnixMillis)
        assertTrue(preview.statusText().contains("packed IBI beats 6"))
    }

    private fun uintLittleEndian(value: UInt): ByteArray = byteArrayOf(
        (value and 0xffu).toByte(),
        ((value shr 8) and 0xffu).toByte(),
        ((value shr 16) and 0xffu).toByte(),
        ((value shr 24) and 0xffu).toByte(),
    )

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
