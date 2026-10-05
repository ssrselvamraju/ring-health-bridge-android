package dev.local.ourahealthbridge.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

enum class AuditRecordType(val label: String) {
    HEART_RATE("HR"),
    HRV_RMSSD("HRV"),
    SLEEP("sleep"),
    RESTING_HEART_RATE("resting HR"),
    SKIN_TEMPERATURE("skin temperature"),
    OXYGEN_SATURATION("SpO2"),
    RESPIRATORY_RATE("respiratory rate"),
    STEPS("steps"),
}

enum class HistoricalDataSource(val label: String, val packageName: String) {
    OURA("Oura", "com.ouraring.oura"),
    SAMSUNG_HEALTH("Samsung Health", "com.sec.android.app.shealth"),
}

data class AuditRecordShape(
    val type: AuditRecordType,
    val startUnixMillis: Long,
    val endUnixMillis: Long,
    val childUnixMillis: List<Long> = emptyList(),
    val sleepStageTypes: List<Int> = emptyList(),
    val hasZoneOffset: Boolean,
    val hasClientRecordId: Boolean,
    val lastModifiedUnixMillis: Long = 0L,
)

data class OuraHistoricalAuditReport(
    val source: HistoricalDataSource,
    val records: Int,
    val byType: Map<AuditRecordType, Int>,
    val earliestUnixMillis: Long?,
    val latestUnixMillis: Long?,
    val heartRateSamples: Int,
    val medianHeartRateSamplesPerRecord: Double?,
    val medianHeartRateRecordDurationSeconds: Double?,
    val medianHeartRateSampleSpacingSeconds: Double?,
    val medianHrvCadenceMinutes: Double?,
    val sleepStages: Int,
    val medianSleepDurationHours: Double?,
    val medianStagesPerSleepSession: Double?,
    val sleepStageCounts: Map<Int, Int>,
    val zoneOffsetRecords: Int,
    val clientIdRecords: Int,
    val medianSleepPublicationDelayMinutes: Double?,
    val sleepModifiedDuringSession: Int,
    val sleepModifiedWithinSixHoursAfter: Int,
    val sleepModifiedLater: Int,
    val sleepOverlapHeartRateRecords: Int,
    val sleepOverlapHeartRateModifiedDuringSleep: Int,
    val sleepOverlapHeartRateModifiedWithinSixHoursAfter: Int,
    val sleepOverlapHeartRateModifiedLater: Int,
) {
    fun statusText(): String {
        if (records == 0) {
            return "Historical ${source.label} audit - no records found for the official " +
                "${source.label} data origin. " +
                "Read only; nothing exported or written."
        }
        val range = "${earliestUnixMillis.asUtcDate()} to ${latestUnixMillis.asUtcDate()}"
        val counts = AuditRecordType.entries.joinToString(", ") { "${it.label} ${byType[it] ?: 0}" }
        val hrShape = "HR samples $heartRateSamples, median ${medianHeartRateSamplesPerRecord.display()} " +
            "samples/record, ${medianHeartRateRecordDurationSeconds.display()} s record duration, " +
            "${medianHeartRateSampleSpacingSeconds.display()} s sample spacing"
        val hrvShape = "HRV median cadence ${medianHrvCadenceMinutes.display()} min"
        val stages = sleepStageCounts.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
            .ifEmpty { "none" }
        val sleepShape = "sleep median ${medianSleepDurationHours.display()} h, " +
            "${medianStagesPerSleepSession.display()} stages/session ($sleepStages total; types $stages)"
        val timing = "publication timing: sleep end-to-modified median " +
            "${medianSleepPublicationDelayMinutes.display()} min (during $sleepModifiedDuringSession, " +
            "within 6h after $sleepModifiedWithinSixHoursAfter, later $sleepModifiedLater); " +
            "sleep-overlap HR $sleepOverlapHeartRateRecords (during " +
            "$sleepOverlapHeartRateModifiedDuringSleep, within 6h after " +
            "$sleepOverlapHeartRateModifiedWithinSixHoursAfter, later " +
            "$sleepOverlapHeartRateModifiedLater)"
        return "Historical ${source.label} audit - $records records, range $range; $counts; $hrShape; " +
            "$hrvShape; $sleepShape; zone offsets $zoneOffsetRecords/$records, " +
            "client IDs $clientIdRecords/$records; $timing. Read only; nothing exported or written."
    }

    private fun Double?.display(): String = this?.let { "%.1f".format(it) } ?: "n/a"

    private fun Long?.asUtcDate(): String = this?.let {
        DATE_FORMATTER.format(Instant.ofEpochMilli(it))
    } ?: "n/a"

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}

object OuraAuditSummarizer {
    fun summarize(
        shapes: List<AuditRecordShape>,
        source: HistoricalDataSource = HistoricalDataSource.OURA,
    ): OuraHistoricalAuditReport {
        val heartRate = shapes.filter { it.type == AuditRecordType.HEART_RATE }
        val hrvTimes = shapes.filter { it.type == AuditRecordType.HRV_RMSSD }
            .map(AuditRecordShape::startUnixMillis).sorted()
        val sleep = shapes.filter { it.type == AuditRecordType.SLEEP }
        val timedSleep = sleep.filter { it.lastModifiedUnixMillis > 0 }
        val sleepOverlapHr = heartRate.mapNotNull { hr ->
            val midpoint = hr.startUnixMillis + (hr.endUnixMillis - hr.startUnixMillis) / 2
            sleep.firstOrNull { midpoint in it.startUnixMillis..it.endUnixMillis }?.let { hr to it }
        }.filter { it.first.lastModifiedUnixMillis > 0 }
        return OuraHistoricalAuditReport(
            source = source,
            records = shapes.size,
            byType = shapes.groupingBy(AuditRecordShape::type).eachCount(),
            earliestUnixMillis = shapes.minOfOrNull(AuditRecordShape::startUnixMillis),
            latestUnixMillis = shapes.maxOfOrNull(AuditRecordShape::endUnixMillis),
            heartRateSamples = heartRate.sumOf { it.childUnixMillis.size },
            medianHeartRateSamplesPerRecord = heartRate.map { it.childUnixMillis.size.toDouble() }.median(),
            medianHeartRateRecordDurationSeconds = heartRate
                .map { (it.endUnixMillis - it.startUnixMillis) / 1_000.0 }.median(),
            medianHeartRateSampleSpacingSeconds = heartRate.flatMap { record ->
                record.childUnixMillis.sorted().zipWithNext { first, second -> (second - first) / 1_000.0 }
                    .filter { it > 0.0 }
            }.median(),
            medianHrvCadenceMinutes = hrvTimes.zipWithNext { first, second -> (second - first) / 60_000.0 }
                .filter { it > 0.0 }.median(),
            sleepStages = sleep.sumOf { it.sleepStageTypes.size },
            medianSleepDurationHours = sleep.map { (it.endUnixMillis - it.startUnixMillis) / 3_600_000.0 }.median(),
            medianStagesPerSleepSession = sleep.map { it.sleepStageTypes.size.toDouble() }.median(),
            sleepStageCounts = sleep.flatMap(AuditRecordShape::sleepStageTypes).groupingBy { it }.eachCount(),
            zoneOffsetRecords = shapes.count(AuditRecordShape::hasZoneOffset),
            clientIdRecords = shapes.count(AuditRecordShape::hasClientRecordId),
            medianSleepPublicationDelayMinutes = timedSleep
                .map { (it.lastModifiedUnixMillis - it.endUnixMillis) / 60_000.0 }.median(),
            sleepModifiedDuringSession = timedSleep.count { it.lastModifiedUnixMillis < it.endUnixMillis },
            sleepModifiedWithinSixHoursAfter = timedSleep.count {
                it.lastModifiedUnixMillis in it.endUnixMillis..(it.endUnixMillis + SIX_HOURS_MILLIS)
            },
            sleepModifiedLater = timedSleep.count { it.lastModifiedUnixMillis > it.endUnixMillis + SIX_HOURS_MILLIS },
            sleepOverlapHeartRateRecords = sleepOverlapHr.size,
            sleepOverlapHeartRateModifiedDuringSleep = sleepOverlapHr.count { (hr, session) ->
                hr.lastModifiedUnixMillis < session.endUnixMillis
            },
            sleepOverlapHeartRateModifiedWithinSixHoursAfter = sleepOverlapHr.count { (hr, session) ->
                hr.lastModifiedUnixMillis in session.endUnixMillis..(session.endUnixMillis + SIX_HOURS_MILLIS)
            },
            sleepOverlapHeartRateModifiedLater = sleepOverlapHr.count { (hr, session) ->
                hr.lastModifiedUnixMillis > session.endUnixMillis + SIX_HOURS_MILLIS
            },
        )
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private const val SIX_HOURS_MILLIS = 6L * 60L * 60L * 1_000L
}

class HistoricalHealthConnectAudit(
    private val client: HealthConnectClient,
    private val source: HistoricalDataSource,
) {
    suspend fun read(): OuraHistoricalAuditReport {
        val shapes = buildList {
            addAll(readAll(HeartRateRecord::class).map(::heartRateShape))
            addAll(readAll(HeartRateVariabilityRmssdRecord::class).map(::hrvShape))
            addAll(readAll(SleepSessionRecord::class).map(::sleepShape))
            addAll(readAll(RestingHeartRateRecord::class).map(::restingHeartRateShape))
            addAll(readAll(SkinTemperatureRecord::class).map(::skinTemperatureShape))
            addAll(readAll(OxygenSaturationRecord::class).map(::oxygenSaturationShape))
            addAll(readAll(RespiratoryRateRecord::class).map(::respiratoryRateShape))
            addAll(readAll(StepsRecord::class).map(::stepsShape))
        }
        return OuraAuditSummarizer.summarize(shapes, source)
    }

    private suspend fun <T : Record> readAll(recordType: KClass<T>): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                    dataOriginFilter = setOf(DataOrigin(source.packageName)),
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

    private fun heartRateShape(record: HeartRateRecord) = AuditRecordShape(
        type = AuditRecordType.HEART_RATE,
        startUnixMillis = record.startTime.toEpochMilli(),
        endUnixMillis = record.endTime.toEpochMilli(),
        childUnixMillis = record.samples.map { it.time.toEpochMilli() },
        hasZoneOffset = record.startZoneOffset != null && record.endZoneOffset != null,
        hasClientRecordId = record.metadata.clientRecordId != null,
        lastModifiedUnixMillis = record.metadata.lastModifiedTime.toEpochMilli(),
    )

    private fun hrvShape(record: HeartRateVariabilityRmssdRecord) = instantaneousShape(
        AuditRecordType.HRV_RMSSD, record.time, record.zoneOffset, record.metadata.clientRecordId,
        record.metadata.lastModifiedTime,
    )

    private fun sleepShape(record: SleepSessionRecord) = AuditRecordShape(
        type = AuditRecordType.SLEEP,
        startUnixMillis = record.startTime.toEpochMilli(),
        endUnixMillis = record.endTime.toEpochMilli(),
        childUnixMillis = record.stages.map { it.startTime.toEpochMilli() },
        sleepStageTypes = record.stages.map { it.stage },
        hasZoneOffset = record.startZoneOffset != null && record.endZoneOffset != null,
        hasClientRecordId = record.metadata.clientRecordId != null,
        lastModifiedUnixMillis = record.metadata.lastModifiedTime.toEpochMilli(),
    )

    private fun restingHeartRateShape(record: RestingHeartRateRecord) = instantaneousShape(
        AuditRecordType.RESTING_HEART_RATE, record.time, record.zoneOffset, record.metadata.clientRecordId,
        record.metadata.lastModifiedTime,
    )

    private fun skinTemperatureShape(record: SkinTemperatureRecord) = AuditRecordShape(
        type = AuditRecordType.SKIN_TEMPERATURE,
        startUnixMillis = record.startTime.toEpochMilli(),
        endUnixMillis = record.endTime.toEpochMilli(),
        childUnixMillis = record.deltas.map { it.time.toEpochMilli() },
        hasZoneOffset = record.startZoneOffset != null && record.endZoneOffset != null,
        hasClientRecordId = record.metadata.clientRecordId != null,
        lastModifiedUnixMillis = record.metadata.lastModifiedTime.toEpochMilli(),
    )

    private fun oxygenSaturationShape(record: OxygenSaturationRecord) = instantaneousShape(
        AuditRecordType.OXYGEN_SATURATION, record.time, record.zoneOffset, record.metadata.clientRecordId,
        record.metadata.lastModifiedTime,
    )

    private fun respiratoryRateShape(record: RespiratoryRateRecord) = instantaneousShape(
        AuditRecordType.RESPIRATORY_RATE, record.time, record.zoneOffset, record.metadata.clientRecordId,
        record.metadata.lastModifiedTime,
    )

    private fun stepsShape(record: StepsRecord) = AuditRecordShape(
        type = AuditRecordType.STEPS,
        startUnixMillis = record.startTime.toEpochMilli(),
        endUnixMillis = record.endTime.toEpochMilli(),
        hasZoneOffset = record.startZoneOffset != null && record.endZoneOffset != null,
        hasClientRecordId = record.metadata.clientRecordId != null,
        lastModifiedUnixMillis = record.metadata.lastModifiedTime.toEpochMilli(),
    )

    private fun instantaneousShape(
        type: AuditRecordType,
        time: Instant,
        zoneOffset: ZoneOffset?,
        clientRecordId: String?,
        lastModifiedTime: Instant,
    ) = AuditRecordShape(
        type = type,
        startUnixMillis = time.toEpochMilli(),
        endUnixMillis = time.toEpochMilli(),
        hasZoneOffset = zoneOffset != null,
        hasClientRecordId = clientRecordId != null,
        lastModifiedUnixMillis = lastModifiedTime.toEpochMilli(),
    )

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(SkinTemperatureRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )

        private const val PAGE_SIZE = 1_000
    }
}
