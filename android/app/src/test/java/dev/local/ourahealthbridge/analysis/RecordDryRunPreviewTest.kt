package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.RawRingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDryRunPreviewTest {
    @Test
    fun reconstructsPackedAndQualityMarkedBeatCandidates() {
        val events = listOf(
            RawRingEvent(0x42, 1_000u, uintLittleEndian(1_800_000_000u)),
            RawRingEvent(0x60, 2_000u, "7d7d7d7d7d7d020406080a0c0007".hex()),
            RawRingEvent(0x80, 2_100u, "7d087d087d08".hex()),
            RawRingEvent(0x5d, 2_000u, byteArrayOf(60, 40)),
            RawRingEvent(0x76, 40_100u, uintLittleEndian(2_000u) + uintLittleEndian(40_000u)),
        )
        val preview = RecordDryRunPreviewBuilder.build(events)

        assertEquals(6, preview.packedBeatCandidates)
        assertEquals(3, preview.qualityMarkedBeatCandidates)
        assertEquals(60.0, preview.medianBeatHeartRateBpm!!, 0.001)
        assertEquals(1, preview.hrvSummarySamples)
        assertEquals(40.0, preview.medianRmssdMillis!!, 0.001)
        assertNotNull(preview.sleepSession)
        assertTrue(preview.statusText().contains("Nothing written to Health Connect"))
    }

    @Test
    fun timingAnalyzerSelectsClearlyBetterForwardHypothesis() {
        val beats = listOf(60.0, 65.0, 70.0, 75.0, 80.0).mapIndexed { index, bpm ->
            BeatSample(index * 300_000L, bpm, 0x60)
        }
        val forward = beats.map { SummaryHeartRateSample(it.unixMillis, it.bpm) }
        val ending = beats.map { SummaryHeartRateSample(it.unixMillis, it.bpm + 25.0) }

        val comparison = SummaryTimingAnalyzer.compare(beats, forward, ending)

        assertEquals(SummaryTimingRule.FORWARD_FROM_EVENT, comparison.selectedRule)
        assertEquals(0.0, comparison.forwardMedianErrorBpm!!, 0.001)
        assertEquals(25.0, comparison.endingMedianErrorBpm!!, 0.001)
    }

    private fun uintLittleEndian(value: UInt): ByteArray = byteArrayOf(
        (value and 0xffu).toByte(),
        ((value shr 8) and 0xffu).toByte(),
        ((value shr 16) and 0xffu).toByte(),
        ((value shr 24) and 0xffu).toByte(),
    )

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
