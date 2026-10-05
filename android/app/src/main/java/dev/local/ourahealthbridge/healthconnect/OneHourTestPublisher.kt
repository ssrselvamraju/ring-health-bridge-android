package dev.local.ourahealthbridge.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.analysis.HeartRateRecordCandidate
import dev.local.ourahealthbridge.analysis.HrvRecordCandidate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlin.reflect.KClass

data class OneHourTestSelection(
    val heartRate: HeartRateRecordCandidate,
    val hrv: List<HrvRecordCandidate>,
) {
    val allClientRecordIds: List<String>
        get() = listOf(heartRate.clientRecordId) + hrv.map(HrvRecordCandidate::clientRecordId)

    fun previewText(): String =
        "One-hour write test ready - ${FORMATTER.format(Instant.ofEpochMilli(heartRate.startUnixMillis))} " +
            "to ${FORMATTER.format(Instant.ofEpochMilli(heartRate.endUnixMillis))}, " +
            "HR samples ${heartRate.samples.size}, HRV records ${hrv.size}, " +
            "records ${allClientRecordIds.size}; exact-ID deletion available. Nothing written yet."

    companion object {
        private val FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}

object OneHourTestSelector {
    fun select(candidates: HealthConnectCandidateSet, nowMillis: Long): OneHourTestSelection? {
        val completed = candidates.heartRateRecords
            .filter { it.endUnixMillis <= nowMillis && it.samples.isNotEmpty() }
        val preferred = completed.filter { hour ->
            candidates.hrvRecords.any { it.unixMillis in hour.startUnixMillis until hour.endUnixMillis }
        }.maxByOrNull(HeartRateRecordCandidate::startUnixMillis)
            ?: completed.maxByOrNull(HeartRateRecordCandidate::startUnixMillis)
            ?: return null
        return OneHourTestSelection(
            heartRate = preferred,
            hrv = candidates.hrvRecords.filter {
                it.unixMillis in preferred.startUnixMillis until preferred.endUnixMillis
            },
        )
    }

    fun selectHour(candidates: HealthConnectCandidateSet, startUnixMillis: Long): OneHourTestSelection? {
        val heartRate = candidates.heartRateRecords.singleOrNull {
            it.startUnixMillis == startUnixMillis && it.samples.isNotEmpty()
        } ?: return null
        return OneHourTestSelection(
            heartRate = heartRate,
            hrv = candidates.hrvRecords.filter {
                it.unixMillis in heartRate.startUnixMillis until heartRate.endUnixMillis
            },
        )
    }
}

data class StoredHeartRateSnapshot(
    val clientRecordId: String?,
    val startUnixMillis: Long,
    val endUnixMillis: Long,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val samples: List<Pair<Long, Long>>,
    val deviceType: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
)

data class StoredHrvSnapshot(
    val clientRecordId: String?,
    val unixMillis: Long,
    val zoneOffsetSeconds: Int?,
    val rmssdMillis: Double,
    val deviceType: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
)

data class OneHourReadBackResult(
    val passed: Boolean,
    val heartRateRecordsFound: Int,
    val heartRateSamplesMatched: Int,
    val expectedHeartRateSamples: Int,
    val hrvRecordsMatched: Int,
    val expectedHrvRecords: Int,
    val mismatchCategories: Set<String>,
) {
    fun statusText(): String = if (passed) {
        "One-hour read-back passed - 1 HR record, $heartRateSamplesMatched/$expectedHeartRateSamples " +
            "HR samples, $hrvRecordsMatched/$expectedHrvRecords HRV records; source, client IDs, " +
            "timestamps, zone offsets, values, and ring attribution match."
    } else {
        "One-hour read-back failed - HR records $heartRateRecordsFound, HR samples " +
            "$heartRateSamplesMatched/$expectedHeartRateSamples, HRV records " +
            "$hrvRecordsMatched/$expectedHrvRecords; mismatch categories: " +
            mismatchCategories.sorted().joinToString(", ").ifEmpty { "unknown" } + "."
    }
}

object OneHourReadBackComparator {
    fun compare(
        selection: OneHourTestSelection,
        storedHeartRate: List<StoredHeartRateSnapshot>,
        storedHrv: List<StoredHrvSnapshot>,
    ): OneHourReadBackResult {
        val mismatches = linkedSetOf<String>()
        val heartRateMatches = storedHeartRate.filter { it.clientRecordId == selection.heartRate.clientRecordId }
        if (heartRateMatches.size != 1) mismatches += "HR record count/ID"
        val storedHr = heartRateMatches.singleOrNull()
        val expectedSamples = selection.heartRate.samples
            .map { it.unixMillis to it.bpm.roundToLong() }
            .sortedBy { it.first }
        var matchedSamples = 0
        if (storedHr != null) {
            if (storedHr.startUnixMillis != selection.heartRate.startUnixMillis ||
                storedHr.endUnixMillis != selection.heartRate.endUnixMillis
            ) mismatches += "HR interval"
            if (storedHr.startZoneOffsetSeconds != selection.heartRate.startZoneOffsetSeconds ||
                storedHr.endZoneOffsetSeconds != selection.heartRate.endZoneOffsetSeconds
            ) mismatches += "HR zone offset"
            val actualSamples = storedHr.samples.sortedBy { it.first }
            matchedSamples = expectedSamples.zip(actualSamples).count { (expected, actual) -> expected == actual }
            if (actualSamples.size != expectedSamples.size || matchedSamples != expectedSamples.size) {
                mismatches += "HR samples"
            }
            if (!storedHr.hasExpectedRingAttribution()) mismatches += "HR ring attribution"
        }

        val storedHrvById = storedHrv.filter { it.clientRecordId != null }.associateBy { it.clientRecordId }
        var matchedHrv = 0
        selection.hrv.forEach { expected ->
            val actual = storedHrvById[expected.clientRecordId]
            if (actual == null) {
                mismatches += "HRV ID/count"
            } else if (actual.unixMillis != expected.unixMillis ||
                actual.zoneOffsetSeconds != expected.zoneOffsetSeconds ||
                kotlin.math.abs(actual.rmssdMillis - expected.rmssdMillis) > VALUE_TOLERANCE
            ) {
                mismatches += "HRV timestamp/offset/value"
            } else if (!actual.hasExpectedRingAttribution()) {
                mismatches += "HRV ring attribution"
            } else {
                matchedHrv++
            }
        }
        if (storedHrvById.keys.intersect(selection.hrv.map { it.clientRecordId }.toSet()).size != selection.hrv.size) {
            mismatches += "HRV ID/count"
        }
        return OneHourReadBackResult(
            passed = mismatches.isEmpty(),
            heartRateRecordsFound = heartRateMatches.size,
            heartRateSamplesMatched = matchedSamples,
            expectedHeartRateSamples = expectedSamples.size,
            hrvRecordsMatched = matchedHrv,
            expectedHrvRecords = selection.hrv.size,
            mismatchCategories = mismatches,
        )
    }

    private fun StoredHeartRateSnapshot.hasExpectedRingAttribution(): Boolean =
        deviceType == Device.TYPE_RING && deviceManufacturer == "Oura" && deviceModel == "Gen 3 Horizon"

    private fun StoredHrvSnapshot.hasExpectedRingAttribution(): Boolean =
        deviceType == Device.TYPE_RING && deviceManufacturer == "Oura" && deviceModel == "Gen 3 Horizon"

    private const val VALUE_TOLERANCE = 0.0001
}

class OneHourTestReadBackVerifier(
    private val client: HealthConnectClient,
    private val originPackageName: String,
) {
    suspend fun verify(selection: OneHourTestSelection): OneHourReadBackResult {
        val range = TimeRangeFilter.between(
            Instant.ofEpochMilli(selection.heartRate.startUnixMillis),
            Instant.ofEpochMilli(selection.heartRate.endUnixMillis),
        )
        val origins = setOf(DataOrigin(originPackageName))
        val heartRate = readAll(HeartRateRecord::class, range, origins).map { record ->
            StoredHeartRateSnapshot(
                clientRecordId = record.metadata.clientRecordId,
                startUnixMillis = record.startTime.toEpochMilli(),
                endUnixMillis = record.endTime.toEpochMilli(),
                startZoneOffsetSeconds = record.startZoneOffset?.totalSeconds,
                endZoneOffsetSeconds = record.endZoneOffset?.totalSeconds,
                samples = record.samples.map { it.time.toEpochMilli() to it.beatsPerMinute },
                deviceType = record.metadata.device?.type,
                deviceManufacturer = record.metadata.device?.manufacturer,
                deviceModel = record.metadata.device?.model,
            )
        }
        val hrv = readAll(HeartRateVariabilityRmssdRecord::class, range, origins).map { record ->
            StoredHrvSnapshot(
                clientRecordId = record.metadata.clientRecordId,
                unixMillis = record.time.toEpochMilli(),
                zoneOffsetSeconds = record.zoneOffset?.totalSeconds,
                rmssdMillis = record.heartRateVariabilityMillis,
                deviceType = record.metadata.device?.type,
                deviceManufacturer = record.metadata.device?.manufacturer,
                deviceModel = record.metadata.device?.model,
            )
        }
        return OneHourReadBackComparator.compare(selection, heartRate, hrv)
    }

    private suspend fun <T : Record> readAll(
        type: KClass<T>,
        range: TimeRangeFilter,
        origins: Set<DataOrigin>,
    ): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = range,
                    dataOriginFilter = origins,
                    pageSize = 100,
                    pageToken = token,
                ),
            )
            records += response.records
            token = response.pageToken
        } while (token != null)
        return records
    }

    companion object {
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        )
    }
}

class OneHourTestPublisher(private val client: HealthConnectClient) {
    suspend fun write(selection: OneHourTestSelection): String {
        val response = client.insertRecords(selection.toHealthConnectRecords())
        return "One-hour write passed - Health Connect accepted ${response.recordIdsList.size} records " +
            "(${selection.heartRate.samples.size} HR samples, ${selection.hrv.size} HRV records). " +
            "Retry uses deterministic IDs; exact-ID deletion is available."
    }

    suspend fun delete(selection: OneHourTestSelection): String {
        client.deleteRecords(
            recordType = HeartRateRecord::class,
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(selection.heartRate.clientRecordId),
        )
        if (selection.hrv.isNotEmpty()) {
            client.deleteRecords(
                recordType = HeartRateVariabilityRmssdRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = selection.hrv.map(HrvRecordCandidate::clientRecordId),
            )
        }
        return "One-hour test deletion passed - removed the selected HR record and " +
            "${selection.hrv.size} HRV records by deterministic client ID."
    }

    private fun OneHourTestSelection.toHealthConnectRecords(): List<Record> =
        listOf(HealthConnectRecordMapper.heartRate(heartRate)) + hrv.map(HealthConnectRecordMapper::hrv)

    companion object {
        val WRITE_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        )

    }
}
