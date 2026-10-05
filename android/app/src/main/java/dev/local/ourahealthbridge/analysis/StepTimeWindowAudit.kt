package dev.local.ourahealthbridge.analysis

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.local.ourahealthbridge.protocol.RawRingEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class StepResearchWindow(
    val walkStart: Instant,
    val walkDuration: Duration = Duration.ofMinutes(6),
) {
    val auditStart: Instant get() = walkStart.minus(Duration.ofMinutes(15))
    val walkEnd: Instant get() = walkStart.plus(walkDuration)
    val auditEnd: Instant get() = walkEnd.plus(Duration.ofMinutes(30))
}

data class StepWindowSegment(
    val label: String,
    val start: Instant,
    val end: Instant,
)

data class StepWindowFieldCandidate(
    val tag: Int,
    val byteOffset: Int,
    val endian: String,
    val monotonicPercent: Int,
    val segmentDeltas: List<Long?>,
    val segmentSums: List<Long>,
) {
    fun display(): String {
        val deltas = segmentDeltas.joinToString("/") { it?.toString() ?: "nonmono" }
        val sums = segmentSums.joinToString("/")
        return "0x${tag.toString(16)} $endian-u16@$byteOffset " +
            "monotonic $monotonicPercent%, deltas $deltas, sums $sums"
    }
}

data class StepWindowHealthSource(
    val packageName: String,
    val segmentRecordCounts: List<Int>,
    val segmentProratedStepTotals: List<Double>,
) {
    fun display(): String =
        "${sourceLabel(packageName)} records ${segmentRecordCounts.joinToString("/")}, " +
            "time-apportioned totals ${segmentProratedStepTotals.joinToString("/") { "%.1f".format(it) }}"
}

data class StepTimeWindowReport(
    val window: StepResearchWindow,
    val zoneId: ZoneId,
    val segments: List<StepWindowSegment>,
    val pairedCounts: List<Int>,
    val unpaired7eCounts: List<Int>,
    val unpaired7fCounts: List<Int>,
    val counterCandidates: List<StepWindowFieldCandidate>,
    val intervalCandidates: List<StepWindowFieldCandidate>,
    val packedAnalysis: PackedStepAnalysis = PackedStepAnalysis(emptyList(), emptyList()),
    val motionFeasibility: MotionFeasibilitySummary? = null,
    val healthSources: List<StepWindowHealthSource>,
) {
    fun statusText(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zoneId)
        val labels = segments.joinToString("/") { it.label }
        val counters = counterCandidates.joinToString("; ") { it.display() }.ifEmpty { "none" }
        val intervals = intervalCandidates.joinToString("; ") { it.display() }.ifEmpty { "none" }
        val packedCounters = packedAnalysis.cumulative.joinToString("; ") { it.display() }.ifEmpty { "none" }
        val packedIntervals = packedAnalysis.interval.joinToString("; ") { it.display() }.ifEmpty { "none" }
        val health = healthSources.joinToString("; ") { it.display() }
            .ifEmpty { "no overlapping Health Connect step records" }
        return "Private step time-window audit - walk ${formatter.format(window.walkStart)} to " +
            "${formatter.format(window.walkEnd)} $zoneId; segments $labels; paired 0x7e/0x7f " +
            "${pairedCounts.joinToString("/")}, unpaired 0x7e ${unpaired7eCounts.joinToString("/")}, " +
            "unpaired 0x7f ${unpaired7fCounts.joinToString("/")}. " +
            "Ranked cumulative candidates (segment order $labels): $counters. " +
            "Ranked interval-value candidates: $intervals. Packed cumulative candidates: $packedCounters. " +
            "Packed interval candidates: $packedIntervals. " +
            "${motionFeasibility?.display(labels).orEmpty()} Health Connect: $health. " +
            "Deltas, sums, bit fields, MET values, and time-apportioned source totals are exploratory, " +
            "not validated step counts. " +
            "Read only; raw bodies and individual records stayed app-private; nothing exported or written."
    }
}

class StepTimeWindowAudit(private val client: HealthConnectClient) {
    suspend fun read(
        allEvents: List<RawRingEvent>,
        window: StepResearchWindow,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StepTimeWindowReport {
        val mapper = RingTimeMapper.fromEvents(allEvents)
        val segments = segments(window)
        val mapped = allEvents.asSequence()
            .filter { it.tag == 0x7e || it.tag == 0x7f }
            .mapNotNull { event -> mapper.unixMillis(event.ringTimestampDeciseconds)?.let { it to event } }
            .filter { (time, _) -> time >= window.auditStart.toEpochMilli() && time < window.auditEnd.toEpochMilli() }
            .sortedBy { it.first }
            .toList()
        val paired = pair(mapped.filter { it.second.tag == 0x7e }, mapped.filter { it.second.tag == 0x7f })
        val packedAnalysis = PackedStepAnalyzer.analyze(
            paired.map { TimedStepBody(it.timeMillis, it.firstBody + it.secondBody) },
            segments,
        )
        val motionFeasibility = MotionFeasibilityBuilder.build(allEvents, mapper, segments)

        val fields = buildFieldCandidates(paired, segments)
        val counterCandidates = fields.asSequence()
            .filter { candidate ->
                candidate.segmentDeltas[1]?.let { it in 1..5_000 } == true &&
                    candidate.monotonicPercent >= 90
            }
            .sortedWith(
                compareBy<StepWindowFieldCandidate> { abs((it.segmentDeltas[1] ?: 0L) - 500L) }
                    .thenByDescending { it.monotonicPercent },
            )
            .take(MAX_CANDIDATES)
            .toList()
        val intervalCandidates = fields.asSequence()
            .filter { it.segmentSums[1] in 1..5_000 }
            .sortedBy { abs(it.segmentSums[1] - 500L) }
            .take(MAX_CANDIDATES)
            .toList()

        val records = readHealthRecords(window.auditStart, window.auditEnd)
        val sources = records.groupBy { it.metadata.dataOrigin.packageName }
            .map { (packageName, sourceRecords) ->
                StepWindowHealthSource(
                    packageName = packageName,
                    segmentRecordCounts = segments.map { segment ->
                        sourceRecords.count { overlaps(it, segment) }
                    },
                    segmentProratedStepTotals = segments.map { segment ->
                        sourceRecords.sumOf { record -> proratedSteps(record, segment) }
                    },
                )
            }
            .sortedByDescending { it.segmentProratedStepTotals.sum() }

        return StepTimeWindowReport(
            window = window,
            zoneId = zoneId,
            segments = segments,
            pairedCounts = segments.map { segment -> paired.count { it.timeMillis in segment.range() } },
            unpaired7eCounts = segments.map { segment ->
                mapped.count { (time, event) -> event.tag == 0x7e && time in segment.range() } -
                    paired.count { it.timeMillis in segment.range() }
            },
            unpaired7fCounts = segments.map { segment ->
                mapped.count { (time, event) -> event.tag == 0x7f && time in segment.range() } -
                    paired.count { it.timeMillis in segment.range() }
            },
            counterCandidates = counterCandidates,
            intervalCandidates = intervalCandidates,
            packedAnalysis = packedAnalysis,
            motionFeasibility = motionFeasibility,
            healthSources = sources,
        )
    }

    private suspend fun readHealthRecords(start: Instant, end: Instant): List<StepsRecord> {
        val records = mutableListOf<StepsRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private fun overlaps(record: StepsRecord, segment: StepWindowSegment): Boolean =
        record.startTime < segment.end && record.endTime > segment.start

    private fun proratedSteps(record: StepsRecord, segment: StepWindowSegment): Double {
        if (!overlaps(record, segment)) return 0.0
        val recordStart = record.startTime.toEpochMilli()
        val recordEnd = record.endTime.toEpochMilli()
        val duration = (recordEnd - recordStart).coerceAtLeast(1L)
        val overlapStart = maxOf(recordStart, segment.start.toEpochMilli())
        val overlapEnd = minOf(recordEnd, segment.end.toEpochMilli())
        return record.count.toDouble() * (overlapEnd - overlapStart).coerceAtLeast(0L) / duration
    }

    private fun buildFieldCandidates(
        pairs: List<StepPair>,
        segments: List<StepWindowSegment>,
    ): List<StepWindowFieldCandidate> = buildList {
        listOf(0x7e, 0x7f).forEach { tag ->
            val bodySize = pairs.map { it.body(tag).size }.minOrNull() ?: return@forEach
            for (offset in 0 until bodySize - 1) {
                listOf(false, true).forEach { bigEndian ->
                    val values = pairs.map { pair -> pair.timeMillis to u16(pair.body(tag), offset, bigEndian) }
                    val transitions = values.zipWithNext()
                    val nonDecreasing = transitions.count { (first, second) -> second.second >= first.second }
                    val monotonicPercent = if (transitions.isEmpty()) 0 else nonDecreasing * 100 / transitions.size
                    val segmentValues = segments.map { segment ->
                        values.filter { (time, _) -> time in segment.range() }.map { it.second }
                    }
                    add(
                        StepWindowFieldCandidate(
                            tag = tag,
                            byteOffset = offset,
                            endian = if (bigEndian) "BE" else "LE",
                            monotonicPercent = monotonicPercent,
                            segmentDeltas = segmentValues.map { selected ->
                                if (selected.size < 2 || selected.zipWithNext().any { it.second < it.first }) {
                                    null
                                } else {
                                    selected.last().toLong() - selected.first().toLong()
                                }
                            },
                            segmentSums = segmentValues.map { selected -> selected.sumOf(Int::toLong) },
                        ),
                    )
                }
            }
        }
    }

    private fun u16(body: ByteArray, offset: Int, bigEndian: Boolean): Int {
        val first = body[offset].toInt() and 0xff
        val second = body[offset + 1].toInt() and 0xff
        return if (bigEndian) (first shl 8) or second else first or (second shl 8)
    }

    private fun pair(
        first: List<Pair<Long, RawRingEvent>>,
        second: List<Pair<Long, RawRingEvent>>,
    ): List<StepPair> {
        var left = 0
        var right = 0
        return buildList {
            while (left < first.size && right < second.size) {
                val delta = first[left].first - second[right].first
                when {
                    abs(delta) <= PAIR_TOLERANCE_MILLIS -> {
                        add(StepPair(minOf(first[left].first, second[right].first), first[left].second.body, second[right].second.body))
                        left++
                        right++
                    }
                    delta < 0 -> left++
                    else -> right++
                }
            }
        }
    }

    private fun segments(window: StepResearchWindow): List<StepWindowSegment> = listOf(
        StepWindowSegment("pre15", window.auditStart, window.walkStart),
        StepWindowSegment("walk6", window.walkStart, window.walkEnd),
        StepWindowSegment("post15", window.walkEnd, window.walkEnd.plus(Duration.ofMinutes(15))),
        StepWindowSegment("post30", window.walkEnd.plus(Duration.ofMinutes(15)), window.auditEnd),
    )

    private fun StepWindowSegment.range(): LongRange = start.toEpochMilli() until end.toEpochMilli()

    private data class StepPair(val timeMillis: Long, val firstBody: ByteArray, val secondBody: ByteArray) {
        fun body(tag: Int): ByteArray = if (tag == 0x7e) firstBody else secondBody
    }

    private companion object {
        const val PAGE_SIZE = 1_000
        const val PAIR_TOLERANCE_MILLIS = 1_000L
        const val MAX_CANDIDATES = 6
    }
}

private fun sourceLabel(packageName: String): String = when (packageName) {
    "com.sec.android.app.shealth" -> "Samsung Health"
    "com.ouraring.oura" -> "Oura"
    "com.google.android.apps.fitness" -> "Google Fit"
    "com.fitbit.FitbitMobile" -> "Fitbit"
    "android" -> "Pixel on-device (legacy)"
    else -> if (packageName.startsWith("com.android.healthconnect.phone.")) {
        "Pixel on-device"
    } else {
        packageName
    }
}
