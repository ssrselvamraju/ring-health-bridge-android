package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.DecodedRingEvent
import dev.local.ourahealthbridge.protocol.OuraEventDecoder
import dev.local.ourahealthbridge.protocol.RawRingEvent
import kotlin.math.abs

data class TimedStepBody(val timeMillis: Long, val body: ByteArray)

data class PackedStepCandidate(
    val bitOffset: Int,
    val bitWidth: Int,
    val monotonicPercent: Int,
    val segmentDeltas: List<Long?>,
    val segmentSums: List<Long>,
) {
    fun display(): String =
        "combined LE-bits@$bitOffset:$bitWidth monotonic $monotonicPercent%, " +
            "deltas ${segmentDeltas.joinToString("/") { it?.toString() ?: "nonmono" }}, " +
            "sums ${segmentSums.joinToString("/")}"
}

data class PackedStepAnalysis(
    val cumulative: List<PackedStepCandidate>,
    val interval: List<PackedStepCandidate>,
)

object PackedStepAnalyzer {
    fun analyze(records: List<TimedStepBody>, segments: List<StepWindowSegment>): PackedStepAnalysis {
        val minimumBytes = records.minOfOrNull { it.body.size } ?: return PackedStepAnalysis(emptyList(), emptyList())
        val fields = buildList {
            for (width in FIELD_WIDTHS) {
                for (offset in 0..(minimumBytes * 8 - width)) {
                    val values = records.map { it.timeMillis to readLittleBits(it.body, offset, width) }
                    val transitions = values.zipWithNext()
                    val monotonic = if (transitions.isEmpty()) 0 else {
                        transitions.count { (first, second) -> second.second >= first.second } * 100 / transitions.size
                    }
                    val selected = segments.map { segment ->
                        values.filter { (time, _) -> time >= segment.start.toEpochMilli() && time < segment.end.toEpochMilli() }
                            .map { it.second }
                    }
                    add(
                        PackedStepCandidate(
                            bitOffset = offset,
                            bitWidth = width,
                            monotonicPercent = monotonic,
                            segmentDeltas = selected.map { segmentValues ->
                                if (segmentValues.size < 2 ||
                                    segmentValues.zipWithNext().any { it.second < it.first }
                                ) null else segmentValues.last() - segmentValues.first()
                            },
                            segmentSums = selected.map { it.sum() },
                        ),
                    )
                }
            }
        }
        val cumulative = fields.asSequence()
            .filter { it.monotonicPercent >= 90 && it.segmentDeltas.getOrNull(1) in 1L..5_000L }
            .sortedWith(
                compareBy<PackedStepCandidate> { abs((it.segmentDeltas[1] ?: 0L) - 500L) }
                    .thenByDescending { it.monotonicPercent }
                    .thenBy { it.bitWidth },
            )
            .distinctBy { it.segmentDeltas to it.segmentSums }
            .take(MAX_CANDIDATES)
            .toList()
        val interval = fields.asSequence()
            .filter { it.segmentSums.getOrElse(1) { 0L } in 1L..5_000L }
            .sortedWith(
                compareBy<PackedStepCandidate> { abs(it.segmentSums[1] - 500L) }
                    .thenBy { it.bitWidth },
            )
            .distinctBy { it.segmentDeltas to it.segmentSums }
            .take(MAX_CANDIDATES)
            .toList()
        return PackedStepAnalysis(cumulative, interval)
    }

    private fun readLittleBits(body: ByteArray, bitOffset: Int, width: Int): Long {
        var result = 0L
        repeat(width) { bit ->
            val source = bitOffset + bit
            val value = (body[source / 8].toInt() ushr (source % 8)) and 1
            result = result or (value.toLong() shl bit)
        }
        return result
    }

    private val FIELD_WIDTHS = intArrayOf(4, 6, 8, 10, 12, 14, 16, 24, 32)
    private const val MAX_CANDIDATES = 8
}

data class MotionFeasibilitySummary(
    val motionEvents: List<Int>,
    val decodedMotionEvents: List<Int>,
    val motionSeconds: List<Int>,
    val highIntensityTotals: List<Int>,
    val motionPeriodEvents: List<Int>,
    val motionPeriodLevelCounts: List<List<Int>>,
    val activityEvents: List<Int>,
    val unknownActivitySummaryEvents: List<Int>,
    val activityMedianMet: List<Double?>,
) {
    fun display(labels: String): String {
        val levels = motionPeriodLevelCounts.joinToString("/") { it.joinToString(":") }
        val met = activityMedianMet.joinToString("/") { it?.let { value -> "%.1f".format(value) } ?: "n/a" }
        return "Motion feasibility ($labels): 0x47 events ${motionEvents.joinToString("/")}, decoded " +
            "${decodedMotionEvents.joinToString("/")}, motion-seconds ${motionSeconds.joinToString("/")}, " +
            "high-intensity totals ${highIntensityTotals.joinToString("/")}; 0x6b events " +
            "${motionPeriodEvents.joinToString("/")}, level0:1:2:3 counts $levels; 0x50 events " +
            "${activityEvents.joinToString("/")}, median decoded MET $met; undecoded 0x51/0x52 events " +
            "${unknownActivitySummaryEvents.joinToString("/")}. These are aggregated motion/activity " +
            "features, not raw accelerometer samples or validated steps."
    }
}

object MotionFeasibilityBuilder {
    fun build(
        events: List<RawRingEvent>,
        mapper: RingTimeMapper,
        segments: List<StepWindowSegment>,
    ): MotionFeasibilitySummary {
        val timed = events.mapNotNull { event ->
            mapper.unixMillis(event.ringTimestampDeciseconds)?.let { Triple(it, event, OuraEventDecoder.decode(event)) }
        }
        fun <T> bySegment(selector: (List<Triple<Long, RawRingEvent, DecodedRingEvent?>>) -> T): List<T> =
            segments.map { segment ->
                selector(timed.filter { (time, _, _) ->
                    time >= segment.start.toEpochMilli() && time < segment.end.toEpochMilli()
                })
            }
        return MotionFeasibilitySummary(
            motionEvents = bySegment { rows -> rows.count { it.second.tag == 0x47 } },
            decodedMotionEvents = bySegment { rows -> rows.count { it.third is DecodedRingEvent.Motion } },
            motionSeconds = bySegment { rows ->
                rows.mapNotNull { it.third as? DecodedRingEvent.Motion }.sumOf { it.motionSeconds }
            },
            highIntensityTotals = bySegment { rows ->
                rows.mapNotNull { it.third as? DecodedRingEvent.Motion }.sumOf { it.highIntensity ?: 0 }
            },
            motionPeriodEvents = bySegment { rows -> rows.count { it.third is DecodedRingEvent.MotionPeriod } },
            motionPeriodLevelCounts = bySegment { rows ->
                val levels = rows.mapNotNull { it.third as? DecodedRingEvent.MotionPeriod }.flatMap { it.levels }
                (0..3).map { level -> levels.count { it == level } }
            },
            activityEvents = bySegment { rows -> rows.count { it.second.tag == 0x50 } },
            unknownActivitySummaryEvents = bySegment { rows ->
                rows.count { it.second.tag == 0x51 || it.second.tag == 0x52 }
            },
            activityMedianMet = bySegment { rows ->
                rows.filter { it.second.tag == 0x50 }
                    .flatMap { it.second.body.drop(1).map(::decodeMet) }
                    .median()
            },
        )
    }

    private fun decodeMet(byte: Byte): Double {
        val value = byte.toUByte().toInt()
        return if (value < 128) value * 0.1 else 12.8 + (value - 128) * 0.2
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val values = sorted()
        val middle = values.size / 2
        return if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2.0 else values[middle]
    }
}
