package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.DecodedRingEvent
import dev.local.ourahealthbridge.protocol.OuraEventDecoder
import dev.local.ourahealthbridge.protocol.RawRingEvent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

data class BeatDeduplicationResult(
    val packedInput: Int,
    val qualityInput: Int,
    val crossStreamMatches: Int,
    val sharedMinutePackedSuppressed: Int,
    val output: List<BeatSample>,
)

data class HeartRateSampleCandidate(val unixMillis: Long, val bpm: Double)

data class HeartRateRecordCandidate(
    val clientRecordId: String,
    val startUnixMillis: Long,
    val endUnixMillis: Long,
    val samples: List<HeartRateSampleCandidate>,
    val startZoneOffsetSeconds: Int,
    val endZoneOffsetSeconds: Int,
)

data class HrvRecordCandidate(
    val clientRecordId: String,
    val unixMillis: Long,
    val rmssdMillis: Double,
    val zoneOffsetSeconds: Int,
)

data class SleepRecordCandidate(
    val clientRecordId: String,
    val startUnixMillis: Long,
    val endUnixMillis: Long,
    val startZoneOffsetSeconds: Int,
    val endZoneOffsetSeconds: Int,
)

data class BeatSourceCoverage(
    val packedMinutes: Int,
    val qualityMinutes: Int,
    val sharedMinutes: Int,
    val packedOnlyMinutes: Int,
    val qualityOnlyMinutes: Int,
)

data class HealthConnectCandidatePreview(
    val packedInputBeats: Int,
    val qualityInputBeats: Int,
    val crossStreamMatches: Int,
    val sharedMinutePackedSuppressed: Int,
    val deduplicatedBeats: Int,
    val heartRateSamples: Int,
    val heartRateRecords: Int,
    val hrvRecords: Int,
    val sleepRecords: Int,
    val distinctClientRecordIds: Int,
    val totalRecordCandidates: Int,
    val timingRule: SummaryTimingRule,
    val sourceCoverage: BeatSourceCoverage,
) {
    fun statusText(): String =
        "Candidate dry run only - input beats ${packedInputBeats + qualityInputBeats}, " +
            "cross-stream matches $crossStreamMatches, deduplicated beats $deduplicatedBeats; " +
            "shared-minute packed suppressed $sharedMinutePackedSuppressed; " +
            "five-minute HR samples $heartRateSamples in $heartRateRecords hourly records; " +
            "HRV records $hrvRecords, sleep records $sleepRecords; " +
            "source coverage minutes packed ${sourceCoverage.packedMinutes}, quality " +
            "${sourceCoverage.qualityMinutes}, shared ${sourceCoverage.sharedMinutes}, " +
            "packed-only ${sourceCoverage.packedOnlyMinutes}, quality-only " +
            "${sourceCoverage.qualityOnlyMinutes}; " +
            "deterministic IDs $distinctClientRecordIds/$totalRecordCandidates unique; " +
            "timing ${timingRule.displayName}. Nothing written to Health Connect."

    private val SummaryTimingRule.displayName: String
        get() = when (this) {
            SummaryTimingRule.FORWARD_FROM_EVENT -> "forward-from-event"
            SummaryTimingRule.ENDING_AT_EVENT -> "ending-at-event"
            SummaryTimingRule.UNRESOLVED -> "unresolved"
        }
}

data class HealthConnectCandidateSet(
    val preview: HealthConnectCandidatePreview,
    val heartRateRecords: List<HeartRateRecordCandidate>,
    val hrvRecords: List<HrvRecordCandidate>,
    val sleepRecords: List<SleepRecordCandidate>,
)

object HealthConnectCandidatePreviewBuilder {
    fun build(events: List<RawRingEvent>, zoneId: ZoneId = ZoneId.systemDefault()): HealthConnectCandidateSet {
        val mapper = RingTimeMapper.fromEvents(events)
        val packed = mutableListOf<BeatSample>()
        val quality = mutableListOf<BeatSample>()
        events.forEach { event ->
            when (val decoded = OuraEventDecoder.decode(event)) {
                is DecodedRingEvent.IbiAmplitude ->
                    packed += BeatReconstructor.reconstruct(event, decoded.ibiMillis, null, mapper)
                is DecodedRingEvent.QualityMarkedIbi ->
                    quality += BeatReconstructor.reconstruct(event, decoded.ibiMillis, decoded.quality, mapper)
                else -> Unit
            }
        }

        val deduplication = BeatDeduplicator.deduplicate(packed, quality)
        val coverage = sourceCoverage(packed, quality)
        val recordPreview = RecordDryRunPreviewBuilder.build(events)
        val hrRecords = buildHeartRateRecords(deduplication.output, zoneId)
        val hrvRecords = buildHrvRecords(events, mapper, recordPreview.timingComparison.selectedRule, zoneId)
        val sleepRecords = recordPreview.sleepSession?.let { sleep ->
            listOf(
                SleepRecordCandidate(
                    clientRecordId = deterministicId("sleep", sleep.startUnixMillis, sleep.endUnixMillis),
                    startUnixMillis = sleep.startUnixMillis,
                    endUnixMillis = sleep.endUnixMillis,
                    startZoneOffsetSeconds = zoneOffsetSeconds(sleep.startUnixMillis, zoneId),
                    endZoneOffsetSeconds = zoneOffsetSeconds(sleep.endUnixMillis, zoneId),
                ),
            )
        }.orEmpty()
        val ids = hrRecords.map(HeartRateRecordCandidate::clientRecordId) +
            hrvRecords.map(HrvRecordCandidate::clientRecordId) +
            sleepRecords.map(SleepRecordCandidate::clientRecordId)

        return HealthConnectCandidateSet(
            preview = HealthConnectCandidatePreview(
                packedInputBeats = deduplication.packedInput,
                qualityInputBeats = deduplication.qualityInput,
                crossStreamMatches = deduplication.crossStreamMatches,
                sharedMinutePackedSuppressed = deduplication.sharedMinutePackedSuppressed,
                deduplicatedBeats = deduplication.output.size,
                heartRateSamples = hrRecords.sumOf { it.samples.size },
                heartRateRecords = hrRecords.size,
                hrvRecords = hrvRecords.size,
                sleepRecords = sleepRecords.size,
                distinctClientRecordIds = ids.distinct().size,
                totalRecordCandidates = ids.size,
                timingRule = recordPreview.timingComparison.selectedRule,
                sourceCoverage = coverage,
            ),
            heartRateRecords = hrRecords,
            hrvRecords = hrvRecords,
            sleepRecords = sleepRecords,
        )
    }

    private fun buildHeartRateRecords(beats: List<BeatSample>, zoneId: ZoneId): List<HeartRateRecordCandidate> {
        val fiveMinuteSamples = beats
            .filter { it.bpm in MIN_BPM..MAX_BPM }
            .groupBy { Math.floorDiv(it.unixMillis, HRV_INTERVAL_MILLIS) }
            .map { (bucket, values) ->
                HeartRateSampleCandidate(
                    unixMillis = bucket * HRV_INTERVAL_MILLIS + HRV_INTERVAL_MILLIS / 2,
                    bpm = values.map(BeatSample::bpm).median()!!,
                )
            }
            .sortedBy(HeartRateSampleCandidate::unixMillis)

        return fiveMinuteSamples.groupBy { Math.floorDiv(it.unixMillis, HOUR_MILLIS) }
            .toSortedMap()
            .map { (hour, samples) ->
                val start = hour * HOUR_MILLIS
                HeartRateRecordCandidate(
                    clientRecordId = deterministicId("heart-rate-hour", start),
                    startUnixMillis = start,
                    endUnixMillis = start + HOUR_MILLIS,
                    samples = samples,
                    startZoneOffsetSeconds = zoneOffsetSeconds(start, zoneId),
                    endZoneOffsetSeconds = zoneOffsetSeconds(start + HOUR_MILLIS, zoneId),
                )
            }
    }

    private fun buildHrvRecords(
        events: List<RawRingEvent>,
        mapper: RingTimeMapper,
        rule: SummaryTimingRule,
        zoneId: ZoneId,
    ): List<HrvRecordCandidate> {
        if (rule == SummaryTimingRule.UNRESOLVED) return emptyList()
        return buildList {
            events.forEach { event ->
                val decoded = OuraEventDecoder.decode(event) as? DecodedRingEvent.Hrv ?: return@forEach
                val base = mapper.unixMillis(event.ringTimestampDeciseconds) ?: return@forEach
                decoded.rmssdMillis.forEachIndexed { index, rmssd ->
                    if (rmssd !in MIN_RMSSD_MILLIS..MAX_RMSSD_MILLIS) return@forEachIndexed
                    val offsetIndex = when (rule) {
                        SummaryTimingRule.FORWARD_FROM_EVENT -> index
                        SummaryTimingRule.ENDING_AT_EVENT -> index - (decoded.rmssdMillis.size - 1)
                        SummaryTimingRule.UNRESOLVED -> return@forEachIndexed
                    }
                    val timestamp = base + offsetIndex * HRV_INTERVAL_MILLIS
                    add(
                        HrvRecordCandidate(
                            clientRecordId = deterministicId("hrv-rmssd", timestamp),
                            unixMillis = timestamp,
                            rmssdMillis = rmssd.toDouble(),
                            zoneOffsetSeconds = zoneOffsetSeconds(timestamp, zoneId),
                        ),
                    )
                }
            }
        }.distinctBy(HrvRecordCandidate::clientRecordId).sortedBy(HrvRecordCandidate::unixMillis)
    }

    private fun sourceCoverage(packed: List<BeatSample>, quality: List<BeatSample>): BeatSourceCoverage {
        val packedMinutes = packed.map { Math.floorDiv(it.unixMillis, MINUTE_MILLIS) }.toSet()
        val qualityMinutes = quality.map { Math.floorDiv(it.unixMillis, MINUTE_MILLIS) }.toSet()
        val shared = packedMinutes intersect qualityMinutes
        return BeatSourceCoverage(
            packedMinutes = packedMinutes.size,
            qualityMinutes = qualityMinutes.size,
            sharedMinutes = shared.size,
            packedOnlyMinutes = (packedMinutes - qualityMinutes).size,
            qualityOnlyMinutes = (qualityMinutes - packedMinutes).size,
        )
    }

    private fun zoneOffsetSeconds(unixMillis: Long, zoneId: ZoneId): Int =
        zoneId.rules.getOffset(Instant.ofEpochMilli(unixMillis)).totalSeconds

    private fun deterministicId(type: String, vararg values: Long): String {
        val canonical = buildString {
            append("oura-direct-v1|")
            append(type)
            values.forEach { append('|').append(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return "oura-direct-v1:$type:$digest"
    }

    private const val MIN_BPM = 25.0
    private const val MAX_BPM = 240.0
    private const val MIN_RMSSD_MILLIS = 1
    // Health Connect HeartRateVariabilityRmssdRecord accepts 1..200 ms.
    private const val MAX_RMSSD_MILLIS = 200
    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 60L * MINUTE_MILLIS
    private const val HRV_INTERVAL_MILLIS = 5L * MINUTE_MILLIS
}

object BeatDeduplicator {
    fun deduplicate(packedInput: List<BeatSample>, qualityInput: List<BeatSample>): BeatDeduplicationResult {
        val packed = packedInput.sortedBy(BeatSample::unixMillis)
        val quality = qualityInput.sortedBy(BeatSample::unixMillis)
        val matchedPacked = BooleanArray(packed.size)
        val qualityMinutes = quality.map { Math.floorDiv(it.unixMillis, MINUTE_MILLIS) }.toSet()
        var left = 0
        var matches = 0

        quality.forEach { qualityBeat ->
            while (left < packed.size && packed[left].unixMillis < qualityBeat.unixMillis - TIME_TOLERANCE_MILLIS) {
                left++
            }
            var index = left
            var bestIndex = -1
            var bestScore = Long.MAX_VALUE
            while (index < packed.size && packed[index].unixMillis <= qualityBeat.unixMillis + TIME_TOLERANCE_MILLIS) {
                val packedBeat = packed[index]
                if (!matchedPacked[index] && abs(packedBeat.ibiMillis - qualityBeat.ibiMillis) <= IBI_TOLERANCE_MILLIS) {
                    val score = abs(packedBeat.unixMillis - qualityBeat.unixMillis) * 100L +
                        abs(packedBeat.ibiMillis - qualityBeat.ibiMillis)
                    if (score < bestScore) {
                        bestScore = score
                        bestIndex = index
                    }
                }
                index++
            }
            if (bestIndex >= 0) {
                matchedPacked[bestIndex] = true
                matches++
            }
        }

        var sharedMinuteSuppressed = 0
        val output = buildList {
            addAll(quality)
            packed.forEachIndexed { index, beat ->
                if (matchedPacked[index]) return@forEachIndexed
                if (Math.floorDiv(beat.unixMillis, MINUTE_MILLIS) in qualityMinutes) {
                    sharedMinuteSuppressed++
                } else {
                    add(beat)
                }
            }
        }.sortedBy(BeatSample::unixMillis)
        return BeatDeduplicationResult(
            packedInput = packed.size,
            qualityInput = quality.size,
            crossStreamMatches = matches,
            sharedMinutePackedSuppressed = sharedMinuteSuppressed,
            output = output,
        )
    }

    private const val TIME_TOLERANCE_MILLIS = 250L
    private const val IBI_TOLERANCE_MILLIS = 25
    private const val MINUTE_MILLIS = 60_000L
}
