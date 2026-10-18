package dev.local.ourahealthbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraEventDecoderTest {
    @Test
    fun syncTimeRequestMatchesUpstreamLayout() {
        assertEquals("1209010203040506070809", OuraRequests.syncTime(0x0807060504030201uL, 9u).hex())
    }

    @Test
    fun decodesCapturedTimeSyncAnchor() {
        val decoded = decode(0x42, "4fd2376a0000000000") as DecodedRingEvent.TimeSync
        assertEquals(1_782_043_215L, decoded.unixSeconds)
    }

    @Test
    fun decodesCapturedTemperatureFixtures() {
        val probes = decode(0x46, "1c0dec0b8d0aa90e1f0dae0c9c0c") as DecodedRingEvent.Temperatures
        assertEquals(7, probes.celsius.size)
        assertEquals(33.56, probes.celsius[0], 0.001)
        assertEquals(37.53, probes.celsius[3], 0.001)
        assertNull(decode(0x46, "ff7f"))
    }

    @Test
    fun decodesHrvPairs() {
        val event = RawRingEvent(0x5d, 100u, byteArrayOf(60, 40, 62, 45, 58, 50))
        val decoded = OuraEventDecoder.decode(event) as DecodedRingEvent.Hrv
        assertEquals(listOf(60, 62, 58), decoded.heartRateBpm)
        assertEquals(listOf(40, 45, 50), decoded.rmssdMillis)
    }

    @Test
    fun decodesCapturedBedtimeAndMotionFixtures() {
        val bedtime = decode(0x76, "74376100e6366500") as DecodedRingEvent.BedtimePeriod
        assertEquals(6_371_188u, bedtime.startDeciseconds)
        assertEquals(6_633_190u, bedtime.endDeciseconds)

        val motion = decode(0x47, "6f0c1d070c07") as DecodedRingEvent.Motion
        assertEquals(3, motion.orientation)
        assertEquals(listOf(96, 232, 56), motion.averageAxes)
        assertEquals(7, motion.highIntensity)
    }

    @Test
    fun decodesPackedIbiVector() {
        val body = "7d7d7d7d7d7d020406080a0c0007".hex()
        val decoded = OuraEventDecoder.decode(RawRingEvent(0x60, 1u, body)) as DecodedRingEvent.IbiAmplitude

        assertEquals(listOf(1_000, 1_000, 1_000, 1_000, 1_000, 1_000), decoded.ibiMillis)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), decoded.amplitude)
    }

    @Test
    fun decodesCapturedSleepAccelerometerFixture() {
        val decoded = decode(0x72, "b1004601f0001e003e000200") as DecodedRingEvent.SleepAccelerometer
        assertEquals(6, decoded.mad.size)
        assertEquals(0.6941, decoded.mad[0], 0.0001)
        assertEquals(1.2745, decoded.mad[1], 0.0001)
    }

    @Test
    fun decodesCapturedMotionPeriodFixture() {
        val decoded = decode(0x6b, "30abefaa596ea89669197afffffb") as DecodedRingEvent.MotionPeriod
        assertEquals(0, decoded.periodType)
        assertEquals(51, decoded.levels.size)
        assertTrue(decoded.levels.all { it in 0..3 })
    }

    @Test
    fun motionPeriodFinalCountZeroMeansFourValidPackedStates() {
        val decoded = decode(0x6b, "001b") as DecodedRingEvent.MotionPeriod
        assertEquals(listOf(0, 1, 2, 3), decoded.levels)
    }

    @Test
    fun decodesCapturedQualityMarkedIbiFixture() {
        val decoded = decode(0x80, "9d09940b9d0d9a099a09a62e946e") as DecodedRingEvent.QualityMarkedIbi
        assertEquals(7, decoded.ibiMillis.size)
        assertEquals(1_257, decoded.ibiMillis[0])
        decoded.ibiMillis.zip(decoded.quality).filter { it.second == 1 }.forEach { (ibi, _) ->
            assertTrue(ibi in 1_000..1_500)
        }
    }

    @Test
    fun decodesStructuralSleepPhasePacket() {
        val decoded = decode(0x4b, "011b") as DecodedRingEvent.SleepPhases
        assertEquals(1, decoded.header)
        assertEquals(listOf(0, 1, 2, 3), decoded.phases)
    }

    @Test
    fun decodesFinishedSpo2PacketAndSentinel() {
        val decoded = decode(0x6f, "006162ff") as DecodedRingEvent.Spo2
        assertEquals(listOf(97, 98), decoded.percentages)
        assertNull(decode(0x6f, "0065ff"))
    }

    private fun decode(tag: Int, bodyHex: String): DecodedRingEvent? =
        OuraEventDecoder.decode(RawRingEvent(tag, 1u, bodyHex.hex()))

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
