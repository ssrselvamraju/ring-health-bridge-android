package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.RawRingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepNightDetailTest {
    @Test
    fun buildsLatestWindowFromLocalCandidatesAndAggregatePackets() {
        val anchorSeconds = 1_800_000_000L
        val anchorMillis = anchorSeconds * 1_000L
        val events = listOf(
            RawRingEvent(0x42, 1_000u, littleEndian(anchorSeconds)),
            RawRingEvent(0x46, 1_100u, byteArrayOf(0x48, 0x0d)), // 34.00 C
            RawRingEvent(0x72, 1_200u, ByteArray(12)),
            RawRingEvent(0x4b, 1_300u, byteArrayOf(1, 0x1b)),
            RawRingEvent(0x6f, 1_400u, byteArrayOf(0, 97, 98, 0xff.toByte())),
        )
        val candidates = HealthConnectCandidateSet(
            preview = HealthConnectCandidatePreview(
                0, 0, 0, 0, 0, 1, 1, 1, 1, 3, 3,
                SummaryTimingRule.ENDING_AT_EVENT,
                BeatSourceCoverage(0, 0, 0, 0, 0),
            ),
            heartRateRecords = listOf(
                HeartRateRecordCandidate(
                    "hr", anchorMillis, anchorMillis + 60_000,
                    listOf(HeartRateSampleCandidate(anchorMillis + 15_000, 60.0)), 0, 0,
                ),
            ),
            hrvRecords = listOf(HrvRecordCandidate("hrv", anchorMillis + 20_000, 42.0, 0)),
            sleepRecords = listOf(SleepRecordCandidate("sleep", anchorMillis, anchorMillis + 600_000, 0, 0)),
        )

        val built = SleepNightDetailBuilder.build(events, candidates)
        val detail = assertNotNull(built.detail).let { built.detail!! }
        assertEquals(1, detail.heartRate.size)
        assertEquals(1, detail.hrv.size)
        assertEquals(1, detail.fingerTemperature.size)
        assertEquals(1, detail.movementSignal.size)
        assertEquals(4, detail.stageEpochs)
        assertEquals(2, detail.spo2Samples)
        assertTrue(built.inventory.rows.first { it.tag == 0x4b }.decodedCount == 1)
    }

    @Test
    fun sparklineIsBoundedForLongSeries() {
        val points = (0 until 200).map { SleepTrendPoint(it.toLong(), it.toDouble()) }
        assertTrue(sleepSparkline(points).length <= 48)
    }

    private fun littleEndian(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte(),
    )
}
