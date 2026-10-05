package dev.local.ourahealthbridge.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.local.ourahealthbridge.storage.HistoryStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class StepTrialBaseline(
    val startedUnixMillis: Long,
    val healthWindowStartUnixMillis: Long,
    val ringTagCounts: Map<Int, Long>,
    val healthSourceTotals: Map<String, Long>,
)

data class StepTrialSnapshot(
    val ringTagCounts: Map<Int, Long>,
    val healthSourceTotals: Map<String, Long>,
)

data class CompletedStepTrial(
    val startedUnixMillis: Long,
    val finishedUnixMillis: Long,
    val manualSteps: Int,
)

data class MarkedStepTrialEnd(
    val finishedUnixMillis: Long,
    val manualSteps: Int,
)

object StepTrialSummarizer {
    fun startedText(baseline: StepTrialBaseline): String =
        "Step trial started ${TIME_FORMATTER.format(Instant.ofEpochMilli(baseline.startedUnixMillis))}. " +
            "Baseline captured for ${baseline.healthSourceTotals.size} Health Connect sources. " +
            "Walk with the ring, manually count steps, then tap Mark walk finished immediately after the last step. " +
            "Only aggregate counters were retained app-privately."

    fun markedText(baseline: StepTrialBaseline, marker: MarkedStepTrialEnd): String {
        val durationMinutes = (marker.finishedUnixMillis - baseline.startedUnixMillis)
            .coerceAtLeast(0L) / 60_000.0
        return "Walk end marked - start ${TIME_FORMATTER.format(Instant.ofEpochMilli(baseline.startedUnixMillis))}, " +
            "end ${TIME_FORMATTER.format(Instant.ofEpochMilli(marker.finishedUnixMillis))}, " +
            "manual ${marker.manualSteps} steps over ${"%.1f".format(durationMinutes)} min. " +
            "The exact interval is saved. Remain still, run Sync and publish now, then finalize the trial."
    }

    fun finishedText(
        baseline: StepTrialBaseline,
        finishedUnixMillis: Long,
        manualSteps: Int,
        snapshot: StepTrialSnapshot,
    ): String {
        val durationMinutes = (finishedUnixMillis - baseline.startedUnixMillis).coerceAtLeast(0L) / 60_000.0
        val ringDeltas = STEP_TAGS.joinToString(", ") { tag ->
            val delta = (snapshot.ringTagCounts[tag] ?: 0L) - (baseline.ringTagCounts[tag] ?: 0L)
            "0x${tag.toString(16)} ${delta.signed()}"
        }
        val packages = baseline.healthSourceTotals.keys + snapshot.healthSourceTotals.keys
        val healthDeltas = packages.sorted().joinToString("; ") { packageName ->
            val delta = (snapshot.healthSourceTotals[packageName] ?: 0L) -
                (baseline.healthSourceTotals[packageName] ?: 0L)
            "${sourceLabel(packageName)} ${delta.signed()}"
        }.ifEmpty { "no sources" }
        return "Step trial finished - start ${TIME_FORMATTER.format(Instant.ofEpochMilli(baseline.startedUnixMillis))}, " +
            "end ${TIME_FORMATTER.format(Instant.ofEpochMilli(finishedUnixMillis))}, " +
            "manual $manualSteps steps over ${"%.1f".format(durationMinutes)} min; " +
            "new ring events: $ringDeltas; Health Connect step changes: $healthDeltas. " +
            "Source changes are comparisons, not ground truth; nothing written to Health Connect or the ring."
    }

    private fun Long.signed(): String = if (this >= 0) "+$this" else toString()

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

    val STEP_TAGS: Set<Int> = setOf(0x50, 0x51, 0x52, 0x7e, 0x7f)
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
}

class StepTrialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun active(): StepTrialBaseline? {
        if (!preferences.contains(STARTED)) return null
        return StepTrialBaseline(
            startedUnixMillis = preferences.getLong(STARTED, 0L),
            healthWindowStartUnixMillis = preferences.getLong(WINDOW_START, 0L),
            ringTagCounts = decodeMap(preferences.getString(RING_COUNTS, null)),
            healthSourceTotals = decodeMap(preferences.getString(HEALTH_TOTALS, null)),
        )
    }

    fun save(baseline: StepTrialBaseline, statusText: String) {
        preferences.edit()
            .putLong(STARTED, baseline.startedUnixMillis)
            .putLong(WINDOW_START, baseline.healthWindowStartUnixMillis)
            .putString(RING_COUNTS, encodeMap(baseline.ringTagCounts))
            .putString(HEALTH_TOTALS, encodeMap(baseline.healthSourceTotals))
            .remove(MARKED_FINISHED)
            .remove(MARKED_MANUAL_STEPS)
            .putString(LAST_STATUS, statusText)
            .apply()
    }

    fun markWalkFinished(marker: MarkedStepTrialEnd, statusText: String) {
        preferences.edit()
            .putLong(MARKED_FINISHED, marker.finishedUnixMillis)
            .putInt(MARKED_MANUAL_STEPS, marker.manualSteps)
            .putString(LAST_STATUS, statusText)
            .apply()
    }

    fun markedEnd(): MarkedStepTrialEnd? {
        if (!preferences.contains(MARKED_FINISHED)) return null
        val finished = preferences.getLong(MARKED_FINISHED, 0L)
        val manual = preferences.getInt(MARKED_MANUAL_STEPS, 0)
        if (finished <= 0L || manual <= 0) return null
        return MarkedStepTrialEnd(finished, manual)
    }

    fun finish(
        statusText: String,
        baseline: StepTrialBaseline,
        finishedUnixMillis: Long,
        manualSteps: Int,
    ) {
        preferences.edit()
            .remove(STARTED)
            .remove(WINDOW_START)
            .remove(RING_COUNTS)
            .remove(HEALTH_TOTALS)
            .remove(MARKED_FINISHED)
            .remove(MARKED_MANUAL_STEPS)
            .putLong(LAST_STARTED, baseline.startedUnixMillis)
            .putLong(LAST_FINISHED, finishedUnixMillis)
            .putInt(LAST_MANUAL_STEPS, manualSteps)
            .putString(LAST_STATUS, statusText)
            .apply()
    }

    fun lastCompleted(): CompletedStepTrial? {
        if (!preferences.contains(LAST_STARTED) || !preferences.contains(LAST_FINISHED)) return null
        val started = preferences.getLong(LAST_STARTED, 0L)
        val finished = preferences.getLong(LAST_FINISHED, 0L)
        val manual = preferences.getInt(LAST_MANUAL_STEPS, 0)
        if (started <= 0L || finished <= started || manual <= 0) return null
        return CompletedStepTrial(started, finished, manual)
    }

    fun lastStatus(): String = preferences.getString(LAST_STATUS, "No controlled trial started")!!

    private fun encodeMap(values: Map<*, Long>): String = values.entries.joinToString("\n") {
        "${it.key}\t${it.value}"
    }

    private inline fun <reified K> decodeMap(encoded: String?): Map<K, Long> = encoded.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val split = line.split('\t', limit = 2)
            val key: Any? = when (K::class) {
                Int::class -> split.getOrNull(0)?.toIntOrNull()
                String::class -> split.getOrNull(0)
                else -> null
            }
            val value = split.getOrNull(1)?.toLongOrNull()
            if (key != null && value != null) @Suppress("UNCHECKED_CAST") ((key as K) to value) else null
        }.toMap()

    private companion object {
        const val PREFERENCES = "step-trial-v1"
        const val STARTED = "started"
        const val WINDOW_START = "window-start"
        const val RING_COUNTS = "ring-counts"
        const val HEALTH_TOTALS = "health-totals"
        const val MARKED_FINISHED = "marked-finished"
        const val MARKED_MANUAL_STEPS = "marked-manual-steps"
        const val LAST_STATUS = "last-status"
        const val LAST_STARTED = "last-completed-started"
        const val LAST_FINISHED = "last-completed-finished"
        const val LAST_MANUAL_STEPS = "last-completed-manual-steps"
    }
}

class StepTrialReader(
    private val context: Context,
    private val client: HealthConnectClient,
) {
    suspend fun start(now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): StepTrialBaseline {
        val windowStart = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
        val snapshot = snapshot(windowStart, now)
        return StepTrialBaseline(
            startedUnixMillis = now.toEpochMilli(),
            healthWindowStartUnixMillis = windowStart.toEpochMilli(),
            ringTagCounts = snapshot.ringTagCounts,
            healthSourceTotals = snapshot.healthSourceTotals,
        )
    }

    suspend fun snapshot(windowStart: Instant, now: Instant = Instant.now()): StepTrialSnapshot {
        val ringCounts = HistoryStore(context).use {
            it.countRawEventsByTags(StepTrialSummarizer.STEP_TAGS)
        }
        val records = mutableListOf<StepsRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(windowStart, now),
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return StepTrialSnapshot(
            ringTagCounts = ringCounts,
            healthSourceTotals = records.groupBy { it.metadata.dataOrigin.packageName }
                .mapValues { (_, values) -> values.sumOf { it.count } },
        )
    }

    private companion object {
        const val PAGE_SIZE = 1_000
    }
}
