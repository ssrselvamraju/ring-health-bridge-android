package dev.local.ourahealthbridge.protocol

sealed interface DecodedRingEvent {
    data class TimeSync(val unixSeconds: Long) : DecodedRingEvent
    data class Temperatures(val celsius: List<Double>) : DecodedRingEvent
    data class Hrv(val heartRateBpm: List<Int>, val rmssdMillis: List<Int>) : DecodedRingEvent
    data class Motion(
        val orientation: Int,
        val motionSeconds: Int,
        val averageAxes: List<Int>,
        val lowIntensity: Int?,
        val highIntensity: Int?,
    ) : DecodedRingEvent
    data class BedtimePeriod(val startDeciseconds: UInt, val endDeciseconds: UInt) : DecodedRingEvent
    data class IbiAmplitude(val ibiMillis: List<Int>, val amplitude: List<Long>) : DecodedRingEvent
    data class SleepAccelerometer(val mad: List<Double>) : DecodedRingEvent
    data class MotionPeriod(val periodType: Int, val levels: List<Int>) : DecodedRingEvent
    data class QualityMarkedIbi(val ibiMillis: List<Int>, val quality: List<Int>) : DecodedRingEvent
}

/** Selected byte-exact decoders ported from the pinned open_oura implementation. */
object OuraEventDecoder {
    const val VERSION = 1

    fun decode(event: RawRingEvent): DecodedRingEvent? = when (event.tag) {
        0x42 -> decodeTimeSync(event.body)
        0x46, 0x69, 0x75 -> decodeTemperatures(event.body)
        0x5d -> decodeHrv(event.body)
        0x47 -> decodeMotion(event.body)
        0x60 -> decodeIbiAmplitude(event.body)
        0x6b -> decodeMotionPeriod(event.body)
        0x72 -> decodeSleepAccelerometer(event.body)
        0x76 -> decodeBedtime(event.body)
        0x80 -> decodeQualityMarkedIbi(event.body)
        else -> null
    }

    private fun decodeTimeSync(body: ByteArray): DecodedRingEvent.TimeSync? {
        if (body.size < 4) return null
        val unix = body.readUIntLittleEndian(0).toLong()
        return unix.takeIf { it in MIN_PLAUSIBLE_UNIX_SECONDS..MAX_PLAUSIBLE_UNIX_SECONDS }
            ?.let(DecodedRingEvent::TimeSync)
    }

    private fun decodeTemperatures(body: ByteArray): DecodedRingEvent.Temperatures? {
        if (body.isEmpty() || body.size % 2 != 0) return null
        val values = body.asList().chunked(2).map { pair ->
            val raw = ((pair[1].toInt() shl 8) or (pair[0].toInt() and 0xff)).toShort()
            raw.toDouble() / 100.0
        }
        if (values.any { it !in -40.0..85.0 }) return null
        return DecodedRingEvent.Temperatures(values)
    }

    private fun decodeHrv(body: ByteArray): DecodedRingEvent.Hrv? {
        if (body.isEmpty() || body.size % 2 != 0) return null
        return DecodedRingEvent.Hrv(
            heartRateBpm = body.filterIndexed { index, _ -> index % 2 == 0 }
                .map { it.toUByte().toInt() },
            rmssdMillis = body.filterIndexed { index, _ -> index % 2 == 1 }
                .map { it.toUByte().toInt() },
        )
    }

    private fun decodeMotion(body: ByteArray): DecodedRingEvent.Motion? {
        if (body.size < 4) return null
        val low = body.getOrNull(4)?.toUByte()?.toInt()
        val high = body.getOrNull(5)?.toUByte()?.toInt()
        if ((low != null && low.and(0x40) != 0) || (high != null && high.and(0x40) != 0)) return null
        return DecodedRingEvent.Motion(
            orientation = body[0].toUByte().toInt() shr 5,
            motionSeconds = body[0].toUByte().toInt() and 0x1f,
            averageAxes = body.slice(1..3).map { it.toInt() * 8 },
            lowIntensity = low?.and(0x3f),
            highIntensity = high?.and(0x3f),
        )
    }

    private fun decodeBedtime(body: ByteArray): DecodedRingEvent.BedtimePeriod? {
        if (body.size < 8) return null
        val start = body.readUIntLittleEndian(0)
        val end = body.readUIntLittleEndian(4)
        if (end < start) return null
        return DecodedRingEvent.BedtimePeriod(start, end)
    }

    private fun decodeIbiAmplitude(body: ByteArray): DecodedRingEvent.IbiAmplitude? {
        if (body.size != 14) return null
        val b = body.map { it.toUByte().toInt() }
        val ibi = listOf(
            (b[6] and 1) or (b[0] shl 3) or ((b[12] shr 5) and 6),
            (b[7] and 1) or (b[1] shl 3) or ((b[12] shr 3) and 6),
            (b[8] and 1) or (b[2] shl 3) or ((b[12] shr 1) and 6),
            (b[9] and 1) or (b[3] shl 3) or ((b[12] and 3) shl 1),
            (b[10] and 1) or (b[4] shl 3) or ((b[13] shr 5) and 6),
            (b[11] and 1) or (b[5] shl 3) or ((b[13] shr 3) and 6),
        )
        val shift = if ((b[13] and 0x0f) == 7) 0 else (b[13] and 0x0f) + 1
        val amplitude = (0 until 6).map { index ->
            (b[6 + index].toLong() shr 1) shl shift
        }
        return DecodedRingEvent.IbiAmplitude(ibi, amplitude)
    }

    private fun decodeSleepAccelerometer(body: ByteArray): DecodedRingEvent.SleepAccelerometer? {
        if (body.size < 12) return null
        val u = body.map { it.toUByte().toInt() }
        fun fixedPoint(frac: Int, integer: Int): Double = integer + frac / 255.0
        fun q12(low: Int, high: Int): Double =
            (low or ((high and 0x0f) shl 8)) / 4095.0 + (high shr 4)
        val values = listOf(
            fixedPoint(u[0], u[1]),
            fixedPoint(u[2], u[3]),
            fixedPoint(u[4], u[5]),
            q12(u[6], u[7]),
            q12(u[8], u[9]),
            q12(u[10], u[11]),
        ).map { kotlin.math.round(it * 10_000.0) / 10_000.0 }
        return DecodedRingEvent.SleepAccelerometer(values)
    }

    private fun decodeMotionPeriod(body: ByteArray): DecodedRingEvent.MotionPeriod? {
        if (body.size < 2) return null
        val header = body[0].toUByte().toInt()
        val encodedLastCount = (header shr 4) and 0x03
        // The two-bit count wraps: zero means all four packed states in the final byte are valid.
        val lastCount = if (encodedLastCount == 0) 4 else encodedLastCount
        val levels = buildList {
            body.drop(1).forEachIndexed { index, byte ->
                val sampleCount = if (index == body.size - 2) lastCount else 4
                val unsigned = byte.toUByte().toInt()
                repeat(sampleCount) { sample -> add((unsigned shr (6 - 2 * sample)) and 0x03) }
            }
        }
        return DecodedRingEvent.MotionPeriod(header shr 6, levels)
    }

    private fun decodeQualityMarkedIbi(body: ByteArray): DecodedRingEvent.QualityMarkedIbi? {
        if (body.size < 2) return null
        val ibi = mutableListOf<Int>()
        val quality = mutableListOf<Int>()
        body.asList().chunked(2).filter { it.size == 2 }.forEach { pair ->
            val first = pair[0].toUByte().toInt()
            val second = pair[1].toUByte().toInt()
            ibi += (second and 0x07) or (first shl 3)
            quality += (second shr 3) and 0x03
        }
        return DecodedRingEvent.QualityMarkedIbi(ibi, quality)
    }

    private const val MIN_PLAUSIBLE_UNIX_SECONDS = 1_577_836_800L // 2020-01-01 UTC
    private const val MAX_PLAUSIBLE_UNIX_SECONDS = 4_102_444_800L // 2100-01-01 UTC
}

internal fun ByteArray.readUIntLittleEndian(offset: Int): UInt =
    (this[offset].toUByte().toUInt()) or
        (this[offset + 1].toUByte().toUInt() shl 8) or
        (this[offset + 2].toUByte().toUInt() shl 16) or
        (this[offset + 3].toUByte().toUInt() shl 24)
