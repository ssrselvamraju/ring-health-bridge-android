package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.DecodedRingEvent
import dev.local.ourahealthbridge.protocol.OuraEventDecoder
import dev.local.ourahealthbridge.protocol.RawRingEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class BeatSample(
    val unixMillis: Long,
    val bpm: Double,
    val sourceTag: Int,
    val ibiMillis: Int = (60_000.0 / bpm).toInt(),
)
data class SummaryHeartRateSample(val unixMillis: Long, val bpm: Double)

enum class SummaryTimingRule { FORWARD_FROM_EVENT, ENDING_AT_EVENT, UNRESOLVED }

data class SummaryTimingComparison(
    val selectedRule: SummaryTimingRule,
    val forwardMedianErrorBpm: Double?,
    val endingMedianErrorBpm: Double?,
    val matchedForwardSamples: Int,
    val matchedEndingSamples: Int,
)

data class SleepSessionDryRun(
    val startUnixMillis: Long,
    val endUnixMillis: Long,
    val beatCount: Int,
    val medianHeartRateBpm: Double?,
)

data class RecordDryRunPreview(
    val packedBeatCandidates: Int,
    val qualityMarkedBeatCandidates: Int,
    val medianBeatHeartRateBpm: Double?,
    val hrvSummarySamples: Int,
    val medianRmssdMillis: Double?,
    val timingComparison: SummaryTimingComparison,
    val sleepSession: SleepSessionDryRun?,
) {
    fun statusText(): String {
        val timing = "summary timing ${timingComparison.selectedRule.displayName}, " +
            "forward error ${timingComparison.forwardMedianErrorBpm.display()} bpm " +
            "(${timingComparison.matchedForwardSamples} matched), ending error " +
            "${timingComparison.endingMedianErrorBpm.display()} bpm " +
            "(${timingComparison.matchedEndingSamples} matched)"
        val sleep = sleepSession?.let {
            "sleep ${TIME_FORMATTER.format(Instant.ofEpochMilli(it.startUnixMillis))} to " +
                "${TIME_FORMATTER.format(Instant.ofEpochMilli(it.endUnixMillis))}, " +
                "${(it.endUnixMillis - it.startUnixMillis) / 3_600_000.0} h, " +
                "${it.beatCount} beat candidates, sleep median HR ${it.medianHeartRateBpm.display()} bpm"
        } ?: "sleep session unavailable"
        return "Record dry run only — packed beats $packedBeatCandidates, quality-marked beats " +
            "$qualityMarkedBeatCandidates, overall median beat HR ${medianBeatHeartRateBpm.display()} bpm; " +
            "HRV summaries $hrvSummarySamples, median RMSSD ${medianRmssdMillis.display()} ms; " +
            "$timing; $sleep. Nothing written to Health Connect."
    }

    private val SummaryTimingRule.displayName: String
        get() = when (this) {
            SummaryTimingRule.FORWARD_FROM_EVENT -> "forward-from-event"
            SummaryTimingRule.ENDING_AT_EVENT -> "ending-at-event"
            SummaryTimingRule.UNRESOLVED -> "unresolved"
        }

    private fun Double?.display(): String = this?.let { "%.1f".format(it) } ?: "n/a"

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}

object RecordDryRunPreviewBuilder {
    fun build(events: List<RawRingEvent>): RecordDryRunPreview {
        val mapper = RingTimeMapper.fromEvents(events)
        val beats = mutableListOf<BeatSample>()
        val hrvEvents = mutableListOf<Pair<RawRingEvent, DecodedRingEvent.Hrv>>()
        var packedBeats = 0
        var qualityBeats = 0
        var latestSleep: DecodedRingEvent.BedtimePeriod? = null
        var latestSleepEventTimestamp = 0u

        events.forEach { event ->
            when (val decoded = OuraEventDecoder.decode(event)) {
                is DecodedRingEvent.IbiAmplitude -> {
                    val added = BeatReconstructor.reconstruct(event, decoded.ibiMillis, null, mapper)
                    beats += added
                    packedBeats += added.size
                }
                is DecodedRingEvent.QualityMarkedIbi -> {
                    val added = BeatReconstructor.reconstruct(event, decoded.ibiMillis, decoded.quality, mapper)
                    beats += added
                    qualityBeats += added.size
                }
                is DecodedRingEvent.Hrv -> hrvEvents += event to decoded
                is DecodedRingEvent.BedtimePeriod -> if (event.ringTimestampDeciseconds >= latestSleepEventTimestamp) {
                    latestSleepEventTimestamp = event.ringTimestampDeciseconds
                    latestSleep = decoded
                }
                else -> Unit
            }
        }
        beats.sortBy(BeatSample::unixMillis)

        val forward = summarySamples(hrvEvents, mapper, SummaryTimingRule.FORWARD_FROM_EVENT)
        val ending = summarySamples(hrvEvents, mapper, SummaryTimingRule.ENDING_AT_EVENT)
        val timing = SummaryTimingAnalyzer.compare(beats, forward, ending)
        val rmssd = hrvEvents.flatMap { it.second.rmssdMillis }.filter { it in 1..300 }
        val sleep = latestSleep?.let { period ->
            val start = mapper.unixMillis(period.startDeciseconds) ?: return@let null
            val end = mapper.unixMillis(period.endDeciseconds) ?: return@let null
            if (end <= start || end - start !in MIN_SLEEP_MILLIS..MAX_SLEEP_MILLIS) return@let null
            val sleepBeats = beats.filter { it.unixMillis in start..end }
            SleepSessionDryRun(start, end, sleepBeats.size, sleepBeats.map(BeatSample::bpm).median())
        }

        return RecordDryRunPreview(
            packedBeatCandidates = packedBeats,
            qualityMarkedBeatCandidates = qualityBeats,
            medianBeatHeartRateBpm = beats.map(BeatSample::bpm).median(),
            hrvSummarySamples = forward.size,
            medianRmssdMillis = rmssd.map(Int::toDouble).median(),
            timingComparison = timing,
            sleepSession = sleep,
        )
    }

    private fun summarySamples(
        events: List<Pair<RawRingEvent, DecodedRingEvent.Hrv>>,
        mapper: RingTimeMapper,
        rule: SummaryTimingRule,
    ): List<SummaryHeartRateSample> = buildList {
        events.forEach { (event, decoded) ->
            val base = mapper.unixMillis(event.ringTimestampDeciseconds) ?: return@forEach
            decoded.heartRateBpm.forEachIndexed { index, bpm ->
                if (bpm !in MIN_SUMMARY_BPM..MAX_SUMMARY_BPM) return@forEachIndexed
                val offsetIndex = when (rule) {
                    SummaryTimingRule.FORWARD_FROM_EVENT -> index
                    SummaryTimingRule.ENDING_AT_EVENT -> index - (decoded.heartRateBpm.size - 1)
                    SummaryTimingRule.UNRESOLVED -> return@forEachIndexed
                }
                add(SummaryHeartRateSample(base + offsetIndex * HRV_INTERVAL_MILLIS, bpm.toDouble()))
            }
        }
    }

    private const val MIN_SUMMARY_BPM = 25
    private const val MAX_SUMMARY_BPM = 240
    private const val HRV_INTERVAL_MILLIS = 5L * 60L * 1_000L
    private const val MIN_SLEEP_MILLIS = 30L * 60L * 1_000L
    private const val MAX_SLEEP_MILLIS = 24L * 60L * 60L * 1_000L
}

internal object BeatReconstructor {
    fun reconstruct(
        event: RawRingEvent,
        ibiMillis: List<Int>,
        quality: List<Int>?,
        mapper: RingTimeMapper,
    ): List<BeatSample> {
        val start = mapper.unixMillis(event.ringTimestampDeciseconds) ?: return emptyList()
        var elapsed = 0L
        return buildList {
            ibiMillis.forEachIndexed { index, ibi ->
                if (ibi <= 0) return@forEachIndexed
                elapsed += ibi
                val acceptedQuality = quality == null || quality.getOrNull(index) == GOOD_QUALITY
                if (acceptedQuality && ibi in MIN_IBI_MILLIS..MAX_IBI_MILLIS) {
                    add(BeatSample(start + elapsed, 60_000.0 / ibi, event.tag, ibi))
                }
            }
        }
    }

    private const val GOOD_QUALITY = 1
    private const val MIN_IBI_MILLIS = 300
    private const val MAX_IBI_MILLIS = 2_000
}

object SummaryTimingAnalyzer {
    fun compare(
        beats: List<BeatSample>,
        forward: List<SummaryHeartRateSample>,
        ending: List<SummaryHeartRateSample>,
    ): SummaryTimingComparison {
        val forwardErrors = errors(beats, forward)
        val endingErrors = errors(beats, ending)
        val forwardMedian = forwardErrors.median()
        val endingMedian = endingErrors.median()
        val selected = select(forwardMedian, endingMedian, forwardErrors.size, endingErrors.size)
        return SummaryTimingComparison(
            selectedRule = selected,
            forwardMedianErrorBpm = forwardMedian,
            endingMedianErrorBpm = endingMedian,
            matchedForwardSamples = forwardErrors.size,
            matchedEndingSamples = endingErrors.size,
        )
    }

    private fun errors(beats: List<BeatSample>, summaries: List<SummaryHeartRateSample>): List<Double> =
        summaries.mapNotNull { summary ->
            val nearbyMedian = beats.asSequence()
                .filter { abs(it.unixMillis - summary.unixMillis) <= COMPARISON_WINDOW_MILLIS }
                .map(BeatSample::bpm)
                .toList()
                .median() ?: return@mapNotNull null
            abs(nearbyMedian - summary.bpm)
        }

    private fun select(forward: Double?, ending: Double?, forwardN: Int, endingN: Int): SummaryTimingRule {
        if (forwardN < MIN_MATCHES && endingN < MIN_MATCHES) return SummaryTimingRule.UNRESOLVED
        val candidates = listOfNotNull(
            forward?.takeIf { forwardN >= MIN_MATCHES }?.let { SummaryTimingRule.FORWARD_FROM_EVENT to it },
            ending?.takeIf { endingN >= MIN_MATCHES }?.let { SummaryTimingRule.ENDING_AT_EVENT to it },
        ).sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return SummaryTimingRule.UNRESOLVED
        if (best.second > MAX_ACCEPTABLE_MEDIAN_ERROR_BPM) return SummaryTimingRule.UNRESOLVED
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && runnerUp.second - best.second < MIN_WINNING_MARGIN_BPM) {
            return SummaryTimingRule.UNRESOLVED
        }
        return best.first
    }

    private const val COMPARISON_WINDOW_MILLIS = 150_000L
    private const val MIN_MATCHES = 5
    private const val MAX_ACCEPTABLE_MEDIAN_ERROR_BPM = 15.0
    private const val MIN_WINNING_MARGIN_BPM = 1.0
}

internal fun List<Double>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}
