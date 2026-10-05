package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.DecodedRingEvent
import dev.local.ourahealthbridge.protocol.OuraEventDecoder
import dev.local.ourahealthbridge.protocol.RawRingEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class EventTagCount(val tag: Int, val name: String, val count: Int)

data class HistoryDryRunPreview(
    val rawEvents: Int,
    val decodedEvents: Int,
    val timeAnchors: Int,
    val utcMappedEvents: Int,
    val plausibleHeartRateSamples: Int,
    val plausibleIbiBeats: Int,
    val qualityMarkedIbiBeats: Int,
    val plausibleHrvSamples: Int,
    val temperatureEvents: Int,
    val motionEvents: Int,
    val motionPeriodEvents: Int,
    val sleepAccelerometerEvents: Int,
    val plausibleSleepWindows: Int,
    val maximumAnchorResidualMillis: Long?,
    val mappedStartUnixMillis: Long?,
    val mappedEndUnixMillis: Long?,
    val topTags: List<EventTagCount>,
) {
    fun statusText(): String {
        val range = if (mappedStartUnixMillis != null && mappedEndUnixMillis != null) {
            "UTC range ${mappedStartUnixMillis.utcDate()} to ${mappedEndUnixMillis.utcDate()}"
        } else {
            "UTC range unavailable"
        }
        val residual = maximumAnchorResidualMillis?.let {
            "anchor residual ${it}ms${if (it > MAX_GOOD_ANCHOR_RESIDUAL_MS) " (check clock)" else ""}"
        } ?: "anchor residual unavailable"
        val tags = topTags.joinToString { "${it.name}(0x${it.tag.toString(16)})=${it.count}" }
        return "Dry run only — raw $rawEvents, decoded $decodedEvents, UTC anchors $timeAnchors, " +
            "UTC-mapped $utcMappedEvents; $range, $residual; summary HR $plausibleHeartRateSamples, " +
            "packed IBI beats $plausibleIbiBeats, quality IBI beats $qualityMarkedIbiBeats, " +
            "HRV $plausibleHrvSamples, temperature $temperatureEvents, " +
            "motion $motionEvents, motion-period $motionPeriodEvents, sleep-ACM $sleepAccelerometerEvents, " +
            "sleep windows $plausibleSleepWindows; top tags: $tags. Nothing written to Health Connect."
    }

    private fun Long.utcDate(): String = DATE_FORMATTER.format(Instant.ofEpochMilli(this))

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        const val MAX_GOOD_ANCHOR_RESIDUAL_MS = 60_000L
    }
}

object HistoryDryRunPreviewBuilder {
    fun build(events: List<RawRingEvent>): HistoryDryRunPreview {
        val mapper = RingTimeMapper.fromEvents(events)
        var decodedCount = 0
        var mappedCount = 0
        var heartRateSamples = 0
        var ibiBeats = 0
        var qualityIbiBeats = 0
        var hrvSamples = 0
        var temperatureEvents = 0
        var motionEvents = 0
        var motionPeriodEvents = 0
        var sleepAccelerometerEvents = 0
        var sleepWindows = 0
        val mappedTimes = mutableListOf<Long>()

        events.forEach { event ->
            val decoded = OuraEventDecoder.decode(event) ?: return@forEach
            decodedCount++
            val mapped = mapper.unixMillis(event.ringTimestampDeciseconds) != null
            if (mapped) {
                mappedCount++
                mapper.unixMillis(event.ringTimestampDeciseconds)?.let(mappedTimes::add)
            }
            when (decoded) {
                is DecodedRingEvent.Hrv -> if (mapped) {
                    heartRateSamples += decoded.heartRateBpm.count { it in 25..240 }
                    hrvSamples += decoded.rmssdMillis.count { it in 1..300 }
                }
                is DecodedRingEvent.Temperatures -> temperatureEvents++
                is DecodedRingEvent.Motion -> motionEvents++
                is DecodedRingEvent.IbiAmplitude -> if (mapped) {
                    ibiBeats += decoded.ibiMillis.count { it in 300..2_000 }
                }
                is DecodedRingEvent.QualityMarkedIbi -> if (mapped) {
                    qualityIbiBeats += decoded.ibiMillis.zip(decoded.quality)
                        .count { (ibi, quality) -> quality == 1 && ibi in 300..2_000 }
                }
                is DecodedRingEvent.MotionPeriod -> motionPeriodEvents++
                is DecodedRingEvent.SleepAccelerometer -> sleepAccelerometerEvents++
                is DecodedRingEvent.BedtimePeriod -> if (
                    mapper.unixMillis(decoded.startDeciseconds) != null &&
                    mapper.unixMillis(decoded.endDeciseconds) != null &&
                    decoded.endDeciseconds.toLong() - decoded.startDeciseconds.toLong() in
                    MIN_SLEEP_DECISECONDS..MAX_SLEEP_DECISECONDS
                ) {
                    sleepWindows++
                }
                is DecodedRingEvent.TimeSync -> Unit
            }
        }

        return HistoryDryRunPreview(
            rawEvents = events.size,
            decodedEvents = decodedCount,
            timeAnchors = mapper.anchorCount,
            utcMappedEvents = mappedCount,
            plausibleHeartRateSamples = heartRateSamples,
            plausibleIbiBeats = ibiBeats,
            qualityMarkedIbiBeats = qualityIbiBeats,
            plausibleHrvSamples = hrvSamples,
            temperatureEvents = temperatureEvents,
            motionEvents = motionEvents,
            motionPeriodEvents = motionPeriodEvents,
            sleepAccelerometerEvents = sleepAccelerometerEvents,
            plausibleSleepWindows = sleepWindows,
            maximumAnchorResidualMillis = mapper.maximumAnchorResidualMillis(),
            mappedStartUnixMillis = mappedTimes.minOrNull(),
            mappedEndUnixMillis = mappedTimes.maxOrNull(),
            topTags = events.groupingBy(RawRingEvent::tag).eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .take(MAX_TOP_TAGS)
                .map { EventTagCount(it.key, eventName(it.key), it.value) },
        )
    }

    private const val MIN_SLEEP_DECISECONDS = 30L * 60L * 10L
    private const val MAX_SLEEP_DECISECONDS = 24L * 60L * 60L * 10L
    private const val MAX_TOP_TAGS = 8

    private fun eventName(tag: Int): String = when (tag) {
        0x42 -> "time-sync"
        0x46 -> "temperature"
        0x47 -> "motion"
        0x50 -> "activity"
        0x55 -> "sleep-HR"
        0x5d -> "HRV"
        0x60 -> "IBI"
        0x61 -> "debug-data"
        0x69 -> "temperature-period"
        0x6b -> "motion-period"
        0x6f -> "SpO2"
        0x72 -> "sleep-ACM"
        0x75 -> "sleep-temperature"
        0x76 -> "bedtime"
        else -> "tag"
    }
}
