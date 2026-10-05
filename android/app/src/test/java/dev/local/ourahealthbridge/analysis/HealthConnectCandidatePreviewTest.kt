package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.RawRingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HealthConnectCandidatePreviewTest {
    @Test
    fun qualityMarkedBeatReplacesMatchingPackedBeat() {
        val packed = listOf(
            BeatSample(1_000, 60.0, 0x60, 1_000),
            BeatSample(62_000, 75.0, 0x60, 800),
            BeatSample(63_000, 50.0, 0x60, 1_200),
        )
        val quality = listOf(
            BeatSample(1_100, 60.0, 0x80, 1_000),
            BeatSample(123_500, 60.0, 0x80, 1_000),
        )

        val result = BeatDeduplicator.deduplicate(packed, quality)

        assertEquals(1, result.crossStreamMatches)
        assertEquals(4, result.output.size)
        assertEquals(0, result.sharedMinutePackedSuppressed)
        assertTrue(result.output.any { it.sourceTag == 0x80 && it.unixMillis == 1_100L })
        assertTrue(result.output.none { it.sourceTag == 0x60 && it.unixMillis == 1_000L })
    }

    @Test
    fun qualityMarkedStreamOwnsSharedMinuteAndPackedFillsOtherMinutes() {
        val packed = listOf(
            BeatSample(1_000, 60.0, 0x60, 1_000),
            BeatSample(20_000, 75.0, 0x60, 800),
            BeatSample(61_000, 50.0, 0x60, 1_200),
        )
        val quality = listOf(BeatSample(10_000, 60.0, 0x80, 1_000))

        val result = BeatDeduplicator.deduplicate(packed, quality)

        assertEquals(0, result.crossStreamMatches)
        assertEquals(2, result.sharedMinutePackedSuppressed)
        assertEquals(listOf(10_000L, 61_000L), result.output.map { it.unixMillis })
        assertEquals(listOf(0x80, 0x60), result.output.map { it.sourceTag })
    }

    @Test
    fun candidateIdsAreStableAndUniqueAcrossRetries() {
        val events = listOf(
            RawRingEvent(0x42, 1_000u, uintLittleEndian(1_800_000_000u)),
            RawRingEvent(0x60, 2_000u, "7d7d7d7d7d7d020406080a0c0007".hex()),
            RawRingEvent(0x80, 2_001u, "7d087d087d08".hex()),
            RawRingEvent(0x76, 40_100u, uintLittleEndian(2_000u) + uintLittleEndian(40_000u)),
        )

        val first = HealthConnectCandidatePreviewBuilder.build(events, ZoneId.of("America/Los_Angeles"))
        val second = HealthConnectCandidatePreviewBuilder.build(events, ZoneId.of("America/Los_Angeles"))
        val firstIds = first.heartRateRecords.map { it.clientRecordId } +
            first.hrvRecords.map { it.clientRecordId } + first.sleepRecords.map { it.clientRecordId }
        val secondIds = second.heartRateRecords.map { it.clientRecordId } +
            second.hrvRecords.map { it.clientRecordId } + second.sleepRecords.map { it.clientRecordId }

        assertEquals(firstIds, secondIds)
        assertEquals(firstIds.size, firstIds.distinct().size)
        assertEquals(3, first.preview.crossStreamMatches)
        assertEquals(3, first.preview.sharedMinutePackedSuppressed)
        assertEquals(3, first.preview.deduplicatedBeats)
        assertEquals(1, first.preview.heartRateSamples)
        assertEquals(1, first.preview.heartRateRecords)
        assertEquals(1, first.preview.sleepRecords)
        assertEquals(-28_800, first.heartRateRecords.single().startZoneOffsetSeconds)
        assertTrue(first.preview.statusText().contains("five-minute HR samples"))
        assertTrue(first.preview.statusText().contains("shared-minute packed suppressed"))
        assertTrue(first.preview.statusText().contains("Nothing written to Health Connect"))
    }

    private fun uintLittleEndian(value: UInt): ByteArray = byteArrayOf(
        (value and 0xffu).toByte(),
        ((value shr 8) and 0xffu).toByte(),
        ((value shr 16) and 0xffu).toByte(),
        ((value shr 24) and 0xffu).toByte(),
    )

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
