package dev.local.ourahealthbridge.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.analysis.HeartRateRecordCandidate
import dev.local.ourahealthbridge.analysis.HrvRecordCandidate
import dev.local.ourahealthbridge.analysis.SleepRecordCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.reflect.KClass

data class DailyPublicationSelection(
    val localDate: LocalDate,
    val zoneId: ZoneId,
    val heartRate: List<HeartRateRecordCandidate>,
    val hrv: List<HrvRecordCandidate>,
    val sleep: List<SleepRecordCandidate>,
) {
    val heartRateSamples: Int get() = heartRate.sumOf { it.samples.size }
    val hrHrvRecordCount: Int get() = heartRate.size + hrv.size
    val allRecordCount: Int get() = hrHrvRecordCount + sleep.size

    fun hrHrvFingerprint(): String = fingerprint(
        heartRate.flatMap { record ->
            listOf(record.clientRecordId, record.startUnixMillis, record.endUnixMillis) +
                record.samples.flatMap { listOf(it.unixMillis, it.bpm) }
        } + hrv.flatMap { listOf(it.clientRecordId, it.unixMillis, it.rmssdMillis) },
    )

    fun sleepFingerprint(): String = fingerprint(
        sleep.flatMap { listOf(it.clientRecordId, it.startUnixMillis, it.endUnixMillis) },
    )

    fun previewText(state: DailyPublicationState): String =
        "Date preview $localDate - HR records ${heartRate.size} with $heartRateSamples samples, " +
            "HRV records ${hrv.size}, completed sleep records ${sleep.size}; total $allRecordCount. " +
            "State: HR/HRV ${state.hrHrvLabel}, sleep ${state.sleepLabel}. Nothing written by preview."

    private fun fingerprint(values: List<Any>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("|").toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}

object DailyPublicationSelector {
    fun availableDates(candidates: HealthConnectCandidateSet, zoneId: ZoneId): List<LocalDate> =
        buildSet {
            candidates.heartRateRecords.forEach {
                add(Instant.ofEpochMilli(it.startUnixMillis).atZone(zoneId).toLocalDate())
            }
            candidates.hrvRecords.forEach {
                add(Instant.ofEpochMilli(it.unixMillis).atZone(zoneId).toLocalDate())
            }
            candidates.sleepRecords.forEach {
                add(Instant.ofEpochMilli(it.endUnixMillis - 1).atZone(zoneId).toLocalDate())
            }
        }.sorted()

    fun select(
        candidates: HealthConnectCandidateSet,
        localDate: LocalDate,
        zoneId: ZoneId,
    ): DailyPublicationSelection = DailyPublicationSelection(
        localDate = localDate,
        zoneId = zoneId,
        heartRate = candidates.heartRateRecords.filter {
            Instant.ofEpochMilli(it.startUnixMillis).atZone(zoneId).toLocalDate() == localDate
        },
        hrv = candidates.hrvRecords.filter {
            Instant.ofEpochMilli(it.unixMillis).atZone(zoneId).toLocalDate() == localDate
        },
        sleep = candidates.sleepRecords.filter {
            Instant.ofEpochMilli(it.endUnixMillis - 1).atZone(zoneId).toLocalDate() == localDate
        },
    )
}

/**
 * Covers both the experienced local publication date and any sleep session assigned
 * to that date by its end time. A sleep session may legitimately begin before local
 * midnight, so a date-only read range is insufficient for exact read-back.
 */
internal fun DailyPublicationSelection.readBackRange(): Pair<Instant, Instant> {
    val dayStart = localDate.atStartOfDay(zoneId).toInstant()
    val dayEnd = localDate.plusDays(1).atStartOfDay(zoneId).toInstant()
    val sleepStart = sleep.minOfOrNull(SleepRecordCandidate::startUnixMillis)
        ?.let(Instant::ofEpochMilli)
    val sleepEnd = sleep.maxOfOrNull(SleepRecordCandidate::endUnixMillis)
        ?.let(Instant::ofEpochMilli)
    return minOf(dayStart, sleepStart ?: dayStart) to maxOf(dayEnd, sleepEnd ?: dayEnd)
}

data class DailyPublicationState(
    val hrHrvVerified: Boolean,
    val sleepVerified: Boolean,
    val hrHrvOutdated: Boolean = false,
    val sleepOutdated: Boolean = false,
) {
    val hrHrvLabel: String
        get() = when {
            hrHrvVerified -> "verified"
            hrHrvOutdated -> "outdated - repair needed"
            else -> "not published"
        }
    val sleepLabel: String
        get() = when {
            sleepVerified -> "verified"
            sleepOutdated -> "outdated - repair needed"
            else -> "not published"
        }
}

data class PublishedClientIds(
    val heartRate: Set<String>,
    val hrv: Set<String>,
    val sleep: Set<String>,
)

class DailyPublicationStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun state(selection: DailyPublicationSelection): DailyPublicationState {
        val hrStored = preferences.getString("hr-hrv:${selection.localDate}", null)
        val sleepStored = preferences.getString("sleep:${selection.localDate}", null)
        val hrCurrent = selection.hrHrvFingerprint()
        val sleepCurrent = selection.sleepFingerprint()
        return DailyPublicationState(
            hrHrvVerified = hrStored != null && hrStored == hrCurrent,
            sleepVerified = sleepStored != null && sleepStored == sleepCurrent,
            hrHrvOutdated = hrStored != null && hrStored != hrCurrent,
            sleepOutdated = sleepStored != null && sleepStored != sleepCurrent,
        )
    }

    fun setHrHrvVerified(selection: DailyPublicationSelection, verified: Boolean) =
        preferences.edit().apply {
            if (verified) {
                putString("hr-hrv:${selection.localDate}", selection.hrHrvFingerprint())
                putString("hr-ids:${selection.localDate}", selection.heartRate.joinIds())
                putString("hrv-ids:${selection.localDate}", selection.hrv.joinIds())
            } else remove("hr-hrv:${selection.localDate}")
        }.apply()

    fun setSleepVerified(selection: DailyPublicationSelection, verified: Boolean) =
        preferences.edit().apply {
            if (verified) {
                putString("sleep:${selection.localDate}", selection.sleepFingerprint())
                putString("sleep-ids:${selection.localDate}", selection.sleep.joinIds())
            } else remove("sleep:${selection.localDate}")
        }.apply()

    fun clientIds(date: LocalDate): PublishedClientIds = PublishedClientIds(
        heartRate = preferences.getString("hr-ids:$date", null).splitIds(),
        hrv = preferences.getString("hrv-ids:$date", null).splitIds(),
        sleep = preferences.getString("sleep-ids:$date", null).splitIds(),
    )

    fun clear(date: LocalDate) {
        preferences.edit()
            .remove("hr-hrv:$date")
            .remove("sleep:$date")
            .remove("hr-ids:$date")
            .remove("hrv-ids:$date")
            .remove("sleep-ids:$date")
            .apply()
    }

    private fun List<*>.joinIds(): String = joinToString("\n") {
        when (it) {
            is HeartRateRecordCandidate -> it.clientRecordId
            is HrvRecordCandidate -> it.clientRecordId
            is SleepRecordCandidate -> it.clientRecordId
            else -> error("Unsupported candidate type")
        }
    }

    private fun String?.splitIds(): Set<String> = this?.lineSequence()?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private companion object {
        const val PREFERENCES = "health-connect-publication-state-v1"
    }
}

data class DailyVerificationResult(
    val passed: Boolean,
    val expectedHeartRateRecords: Int,
    val matchedHeartRateRecords: Int,
    val expectedHeartRateSamples: Int,
    val matchedHeartRateSamples: Int,
    val expectedHrvRecords: Int,
    val matchedHrvRecords: Int,
    val expectedSleepRecords: Int,
    val matchedSleepRecords: Int,
    val mismatchCategories: Set<String>,
) {
    fun hrHrvStatusText(date: LocalDate): String = if (passed && expectedSleepRecords == 0) {
        "Date HR/HRV publication passed for $date - HR records $matchedHeartRateRecords/" +
            "$expectedHeartRateRecords, samples $matchedHeartRateSamples/$expectedHeartRateSamples, " +
            "HRV $matchedHrvRecords/$expectedHrvRecords; exact read-back verified."
    } else {
        "Date HR/HRV verification for $date - HR records $matchedHeartRateRecords/" +
            "$expectedHeartRateRecords, samples $matchedHeartRateSamples/$expectedHeartRateSamples, " +
            "HRV $matchedHrvRecords/$expectedHrvRecords; mismatches ${mismatchText()}."
    }

    fun sleepStatusText(date: LocalDate): String = if (passed && expectedHeartRateRecords == 0 && expectedHrvRecords == 0) {
        "Date sleep publication passed for $date - sleep records $matchedSleepRecords/" +
            "$expectedSleepRecords; exact read-back verified."
    } else {
        "Date sleep verification for $date - sleep records $matchedSleepRecords/" +
            "$expectedSleepRecords; mismatches ${mismatchText()}."
    }

    private fun mismatchText(): String = mismatchCategories.sorted().joinToString(", ").ifEmpty { "none" }
}

class DailyHealthConnectPublisher(
    private val client: HealthConnectClient,
    private val originPackageName: String,
) {
    suspend fun publishHrHrv(selection: DailyPublicationSelection): DailyVerificationResult {
        val records: List<Record> = selection.heartRate.map(HealthConnectRecordMapper::heartRate) +
            selection.hrv.map(HealthConnectRecordMapper::hrv)
        require(records.isNotEmpty()) { "No HR or HRV candidates for selected date" }
        records.chunked(MAX_INSERT_BATCH).forEach { client.insertRecords(it) }
        return verifyHrHrv(selection)
    }

    suspend fun publishSleep(selection: DailyPublicationSelection): DailyVerificationResult {
        require(selection.sleep.isNotEmpty()) { "No completed sleep candidate for selected date" }
        selection.sleep.map(HealthConnectRecordMapper::sleep).chunked(MAX_INSERT_BATCH)
            .forEach { client.insertRecords(it) }
        return verifySleep(selection)
    }

    suspend fun verifyHrHrv(selection: DailyPublicationSelection): DailyVerificationResult {
        val (start, end) = selection.readBackRange()
        val origins = setOf(DataOrigin(originPackageName))
        val storedHr = readAll(HeartRateRecord::class, start, end, origins)
            .filter { it.metadata.clientRecordId in selection.heartRate.map { candidate -> candidate.clientRecordId }.toSet() }
        val storedHrv = readAll(HeartRateVariabilityRmssdRecord::class, start, end, origins)
            .filter { it.metadata.clientRecordId in selection.hrv.map { candidate -> candidate.clientRecordId }.toSet() }
        return DailyPublicationComparator.compareHrHrv(selection, storedHr, storedHrv)
    }

    suspend fun verifySleep(selection: DailyPublicationSelection): DailyVerificationResult {
        val (start, end) = selection.readBackRange()
        val origins = setOf(DataOrigin(originPackageName))
        val expectedIds = selection.sleep.map { it.clientRecordId }.toSet()
        val stored = readAll(SleepSessionRecord::class, start, end, origins)
            .filter { it.metadata.clientRecordId in expectedIds }
        return DailyPublicationComparator.compareSleep(selection, stored)
    }

    suspend fun deleteDate(selection: DailyPublicationSelection) {
        deleteByIds(HeartRateRecord::class, selection.heartRate.map { it.clientRecordId })
        deleteByIds(HeartRateVariabilityRmssdRecord::class, selection.hrv.map { it.clientRecordId })
        deleteByIds(SleepSessionRecord::class, selection.sleep.map { it.clientRecordId })
    }

    /** Removes only superseded deterministic IDs, after replacement records verify successfully. */
    suspend fun deleteObsolete(
        previous: DailyPublicationSelection,
        current: DailyPublicationSelection,
        includeHrHrv: Boolean,
        includeSleep: Boolean,
        previouslyPublished: PublishedClientIds = PublishedClientIds(emptySet(), emptySet(), emptySet()),
    ): Int {
        var deleted = 0
        if (includeHrHrv) {
            val currentHr = current.heartRate.map { it.clientRecordId }.toSet()
            val oldHr = (previous.heartRate.map { it.clientRecordId } + previouslyPublished.heartRate)
                .distinct().filterNot { it in currentHr }
            deleteByIds(HeartRateRecord::class, oldHr)
            deleted += oldHr.size
            val currentHrv = current.hrv.map { it.clientRecordId }.toSet()
            val oldHrv = (previous.hrv.map { it.clientRecordId } + previouslyPublished.hrv)
                .distinct().filterNot { it in currentHrv }
            deleteByIds(HeartRateVariabilityRmssdRecord::class, oldHrv)
            deleted += oldHrv.size
        }
        if (includeSleep) {
            val currentSleep = current.sleep.map { it.clientRecordId }.toSet()
            val oldSleep = (previous.sleep.map { it.clientRecordId } + previouslyPublished.sleep)
                .distinct().filterNot { it in currentSleep }
            deleteByIds(SleepSessionRecord::class, oldSleep)
            deleted += oldSleep.size
        }
        return deleted
    }

    suspend fun verifyDateAbsent(selection: DailyPublicationSelection): Boolean {
        val (start, end) = selection.readBackRange()
        val origins = setOf(DataOrigin(originPackageName))
        val expectedHr = selection.heartRate.map { it.clientRecordId }.toSet()
        val expectedHrv = selection.hrv.map { it.clientRecordId }.toSet()
        val expectedSleep = selection.sleep.map { it.clientRecordId }.toSet()
        val hrPresent = readAll(HeartRateRecord::class, start, end, origins)
            .any { it.metadata.clientRecordId in expectedHr }
        val hrvPresent = readAll(HeartRateVariabilityRmssdRecord::class, start, end, origins)
            .any { it.metadata.clientRecordId in expectedHrv }
        val sleepPresent = readAll(SleepSessionRecord::class, start, end, origins)
            .any { it.metadata.clientRecordId in expectedSleep }
        return !hrPresent && !hrvPresent && !sleepPresent
    }

    private suspend fun <T : Record> deleteByIds(type: KClass<T>, clientIds: List<String>) {
        clientIds.chunked(MAX_DELETE_BATCH).forEach { ids ->
            if (ids.isNotEmpty()) {
                client.deleteRecords(type, recordIdsList = emptyList(), clientRecordIdsList = ids)
            }
        }
    }

    private suspend fun <T : Record> readAll(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        origins: Set<DataOrigin>,
    ): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = origins,
                    pageSize = 1_000,
                    pageToken = token,
                ),
            )
            records += response.records
            token = response.pageToken
        } while (token != null)
        return records
    }

    companion object {
        val HR_HRV_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        )
        val SLEEP_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
        )
        val ALL_PERMISSIONS: Set<String> = HR_HRV_PERMISSIONS + SLEEP_PERMISSIONS

        private const val MAX_INSERT_BATCH = 50
        private const val MAX_DELETE_BATCH = 50
    }
}

object DailyPublicationComparator {
    fun compareHrHrv(
        selection: DailyPublicationSelection,
        storedHr: List<HeartRateRecord>,
        storedHrv: List<HeartRateVariabilityRmssdRecord>,
    ): DailyVerificationResult {
        val mismatches = linkedSetOf<String>()
        val hrById = storedHr.filter { it.metadata.clientRecordId != null }.associateBy { it.metadata.clientRecordId }
        var matchedHr = 0
        var matchedSamples = 0
        selection.heartRate.forEach { expected ->
            val actual = hrById[expected.clientRecordId]
            if (actual == null) {
                mismatches += "HR ID/count"
                return@forEach
            }
            val expectedSamples = expected.samples.map { it.unixMillis to it.bpm.roundToLong() }
            val actualSamples = actual.samples.map { it.time.toEpochMilli() to it.beatsPerMinute }.sortedBy { it.first }
            val valid = actual.startTime.toEpochMilli() == expected.startUnixMillis &&
                actual.endTime.toEpochMilli() == expected.endUnixMillis &&
                actual.startZoneOffset?.totalSeconds == expected.startZoneOffsetSeconds &&
                actual.endZoneOffset?.totalSeconds == expected.endZoneOffsetSeconds &&
                expectedSamples.sortedBy { it.first } == actualSamples && actual.hasExpectedRingAttribution()
            if (valid) {
                matchedHr++
                matchedSamples += expectedSamples.size
            } else {
                mismatches += "HR fields/samples"
            }
        }
        if (storedHr.size != selection.heartRate.size) mismatches += "HR ID/count"

        val hrvById = storedHrv.filter { it.metadata.clientRecordId != null }.associateBy { it.metadata.clientRecordId }
        var matchedHrv = 0
        selection.hrv.forEach { expected ->
            val actual = hrvById[expected.clientRecordId]
            val valid = actual != null && actual.time.toEpochMilli() == expected.unixMillis &&
                actual.zoneOffset?.totalSeconds == expected.zoneOffsetSeconds &&
                abs(actual.heartRateVariabilityMillis - expected.rmssdMillis) < 0.0001 &&
                actual.hasExpectedRingAttribution()
            if (valid) matchedHrv++ else mismatches += "HRV ID/fields"
        }
        if (storedHrv.size != selection.hrv.size) mismatches += "HRV ID/count"

        return DailyVerificationResult(
            passed = mismatches.isEmpty(),
            expectedHeartRateRecords = selection.heartRate.size,
            matchedHeartRateRecords = matchedHr,
            expectedHeartRateSamples = selection.heartRateSamples,
            matchedHeartRateSamples = matchedSamples,
            expectedHrvRecords = selection.hrv.size,
            matchedHrvRecords = matchedHrv,
            expectedSleepRecords = 0,
            matchedSleepRecords = 0,
            mismatchCategories = mismatches,
        )
    }

    fun compareSleep(
        selection: DailyPublicationSelection,
        stored: List<SleepSessionRecord>,
    ): DailyVerificationResult {
        val mismatches = linkedSetOf<String>()
        val byId = stored.filter { it.metadata.clientRecordId != null }.associateBy { it.metadata.clientRecordId }
        var matched = 0
        selection.sleep.forEach { expected ->
            val actual = byId[expected.clientRecordId]
            val valid = actual != null && actual.startTime.toEpochMilli() == expected.startUnixMillis &&
                actual.endTime.toEpochMilli() == expected.endUnixMillis &&
                actual.startZoneOffset?.totalSeconds == expected.startZoneOffsetSeconds &&
                actual.endZoneOffset?.totalSeconds == expected.endZoneOffsetSeconds &&
                actual.stages.isEmpty() && actual.hasExpectedRingAttribution()
            if (valid) matched++ else mismatches += "sleep ID/fields"
        }
        if (stored.size != selection.sleep.size) mismatches += "sleep ID/count"
        return DailyVerificationResult(
            passed = mismatches.isEmpty(),
            expectedHeartRateRecords = 0,
            matchedHeartRateRecords = 0,
            expectedHeartRateSamples = 0,
            matchedHeartRateSamples = 0,
            expectedHrvRecords = 0,
            matchedHrvRecords = 0,
            expectedSleepRecords = selection.sleep.size,
            matchedSleepRecords = matched,
            mismatchCategories = mismatches,
        )
    }

    private fun Record.hasExpectedRingAttribution(): Boolean =
        metadata.device?.type == Device.TYPE_RING && metadata.device?.manufacturer == "Oura" &&
            metadata.device?.model == "Gen 3 Horizon"
}
