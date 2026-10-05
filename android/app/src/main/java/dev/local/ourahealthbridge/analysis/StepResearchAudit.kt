package dev.local.ourahealthbridge.analysis

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.local.ourahealthbridge.protocol.RawRingEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class RingStepTagSummary(
    val tag: Int,
    val count: Int,
    val mappedCount: Int,
    val bodyLengths: Map<Int, Int>,
    val firstUnixMillis: Long?,
    val lastUnixMillis: Long?,
    val medianCadenceMinutes: Double?,
) {
    fun display(): String {
        val lengths = bodyLengths.toSortedMap().entries.joinToString(",") { "${it.key}B:${it.value}" }
            .ifEmpty { "none" }
        val range = if (firstUnixMillis != null && lastUnixMillis != null) {
            "${DATE_FORMATTER.format(Instant.ofEpochMilli(firstUnixMillis))}.." +
                DATE_FORMATTER.format(Instant.ofEpochMilli(lastUnixMillis))
        } else {
            "unmapped"
        }
        return "0x${tag.toString(16)}=$count (UTC $mappedCount, lengths $lengths, " +
            "range $range, median cadence ${medianCadenceMinutes.oneDecimal()} min)"
    }

    private fun Double?.oneDecimal(): String = this?.let { "%.1f".format(it) } ?: "n/a"

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}

data class RingStepInventory(
    val tags: List<RingStepTagSummary>,
    val exactStepMotionPairs: Int,
    val nearStepMotionPairs: Int,
) {
    fun statusText(): String {
        val found = tags.filter { it.count > 0 }
        val tagText = if (found.isEmpty()) {
            "none of 0x50/0x51/0x52/0x7e/0x7f found"
        } else {
            found.joinToString("; ") { it.display() }
        }
        return "$tagText; stepmotion 0x7e/0x7f exact timestamp pairs $exactStepMotionPairs, " +
            "within 1 second pairs $nearStepMotionPairs"
    }
}

object RingStepInventoryBuilder {
    private val candidateTags = listOf(0x50, 0x51, 0x52, 0x7e, 0x7f)
    val REQUIRED_TAGS: Set<Int> = (candidateTags + 0x42).toSet()

    fun build(events: List<RawRingEvent>): RingStepInventory {
        val mapper = RingTimeMapper.fromEvents(events)
        val summaries = candidateTags.map { tag ->
            val selected = events.filter { it.tag == tag }
            val mapped = selected.mapNotNull { mapper.unixMillis(it.ringTimestampDeciseconds) }.sorted()
            RingStepTagSummary(
                tag = tag,
                count = selected.size,
                mappedCount = mapped.size,
                bodyLengths = selected.groupingBy { it.body.size }.eachCount(),
                firstUnixMillis = mapped.firstOrNull(),
                lastUnixMillis = mapped.lastOrNull(),
                medianCadenceMinutes = mapped.zipWithNext { first, second ->
                    (second - first) / 60_000.0
                }.filter { it > 0.0 }.median(),
            )
        }
        val firstParts = events.filter { it.tag == 0x7e }.map { it.ringTimestampDeciseconds.toLong() }.sorted()
        val secondParts = events.filter { it.tag == 0x7f }.map { it.ringTimestampDeciseconds.toLong() }.sorted()
        return RingStepInventory(
            tags = summaries,
            exactStepMotionPairs = pairCount(firstParts, secondParts, 0L),
            nearStepMotionPairs = pairCount(firstParts, secondParts, 10L),
        )
    }

    private fun pairCount(first: List<Long>, second: List<Long>, toleranceDeciseconds: Long): Int {
        var left = 0
        var right = 0
        var pairs = 0
        while (left < first.size && right < second.size) {
            val delta = first[left] - second[right]
            when {
                kotlin.math.abs(delta) <= toleranceDeciseconds -> {
                    pairs++
                    left++
                    right++
                }
                delta < 0 -> left++
                else -> right++
            }
        }
        return pairs
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}

data class HealthConnectStepSourceSummary(
    val packageName: String,
    val recordCount: Int,
    val totalSteps: Long,
    val firstUnixMillis: Long,
    val lastUnixMillis: Long,
    val medianRecordMinutes: Double?,
) {
    fun display(): String {
        val label = when (packageName) {
            "com.sec.android.app.shealth" -> "Samsung Health"
            "com.ouraring.oura" -> "Oura"
            "dev.local.ourahealthbridge" -> "Ring Health Bridge"
            else -> packageName
        }
        val range = "${DATE_FORMATTER.format(Instant.ofEpochMilli(firstUnixMillis))}.." +
            DATE_FORMATTER.format(Instant.ofEpochMilli(lastUnixMillis))
        return "$label: $recordCount records, $totalSteps steps, $range, median " +
            "${medianRecordMinutes?.let { "%.1f".format(it) } ?: "n/a"} min/record"
    }

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}

data class PrivateStepResearchReport(
    val ring: RingStepInventory,
    val healthConnectSources: List<HealthConnectStepSourceSummary>,
) {
    fun statusText(): String {
        val sources = healthConnectSources.joinToString("; ") { it.display() }
            .ifEmpty { "no Health Connect step records found" }
        return "Private step-source audit - ring candidates: ${ring.statusText()}. " +
            "Health Connect: $sources. Event counts are not treated as step counts. " +
            "Read only; raw bodies and individual Health Connect records stayed app-private; " +
            "nothing exported or written."
    }
}

class PrivateStepResearchAudit(private val client: HealthConnectClient) {
    suspend fun read(events: List<RawRingEvent>): PrivateStepResearchReport {
        val records = mutableListOf<StepsRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)

        val sources = records.groupBy { it.metadata.dataOrigin.packageName }.map { (packageName, sourceRecords) ->
            HealthConnectStepSourceSummary(
                packageName = packageName,
                recordCount = sourceRecords.size,
                totalSteps = sourceRecords.sumOf { it.count },
                firstUnixMillis = sourceRecords.minOf { it.startTime.toEpochMilli() },
                lastUnixMillis = sourceRecords.maxOf { it.endTime.toEpochMilli() },
                medianRecordMinutes = sourceRecords.map {
                    (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0
                }.median(),
            )
        }.sortedByDescending { it.totalSteps }

        return PrivateStepResearchReport(RingStepInventoryBuilder.build(events), sources)
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )
        private const val PAGE_SIZE = 1_000
    }
}
