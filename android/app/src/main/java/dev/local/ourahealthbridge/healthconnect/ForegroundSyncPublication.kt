package dev.local.ourahealthbridge.healthconnect

import android.content.Context
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.PhoneBatterySnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

data class AffectedDatePublication(
    val previous: DailyPublicationSelection,
    val current: DailyPublicationSelection,
    val publishHrHrv: Boolean,
    val publishSleep: Boolean,
)

data class ForegroundPublicationPlan(
    val dates: List<AffectedDatePublication>,
    val deferredRecentSleepRecords: Int,
) {
    val affectedDateCount: Int get() = dates.size
}

/** Pure before/after planner used only after a successful, fully drained ring sync. */
object ForegroundPublicationPlanner {
    /**
     * Plans from the already-persisted publication fingerprints. This avoids decoding
     * the complete raw history before BLE connection and again after a successful drain.
     */
    fun planAgainstPublicationState(
        currentCandidates: HealthConnectCandidateSet,
        zoneId: ZoneId,
        nowUnixMillis: Long,
        state: (DailyPublicationSelection) -> DailyPublicationState,
    ): ForegroundPublicationPlan {
        val unchanged = plan(currentCandidates, currentCandidates, zoneId, nowUnixMillis)
        return includePendingPublication(
            unchanged,
            currentCandidates,
            zoneId,
            nowUnixMillis,
            state,
        )
    }

    fun plan(
        before: HealthConnectCandidateSet,
        after: HealthConnectCandidateSet,
        zoneId: ZoneId,
        nowUnixMillis: Long,
    ): ForegroundPublicationPlan {
        val cutoff = nowUnixMillis - SLEEP_COMPLETION_GRACE_MILLIS
        val beforeSelections = selections(before, zoneId, cutoff)
        val afterSelections = selections(after, zoneId, cutoff)
        val deferredSleep = after.sleepRecords.count { it.endUnixMillis > cutoff }
        val affected = afterSelections.mapNotNull { (date, current) ->
            val previous = beforeSelections[date] ?: emptySelection(date, zoneId)
            val hrChanged = current.hrHrvRecordCount > 0 &&
                previous.hrHrvFingerprint() != current.hrHrvFingerprint()
            val sleepChanged = current.sleep.isNotEmpty() &&
                previous.sleepFingerprint() != current.sleepFingerprint()
            if (!hrChanged && !sleepChanged) null else AffectedDatePublication(
                previous = previous,
                current = current,
                publishHrHrv = hrChanged,
                publishSleep = sleepChanged,
            )
        }
        return ForegroundPublicationPlan(affected.sortedBy { it.current.localDate }, deferredSleep)
    }

    /** Adds candidate work left unverified by an interrupted or failed publication run. */
    fun includePendingPublication(
        base: ForegroundPublicationPlan,
        currentCandidates: HealthConnectCandidateSet,
        zoneId: ZoneId,
        nowUnixMillis: Long,
        state: (DailyPublicationSelection) -> DailyPublicationState,
    ): ForegroundPublicationPlan {
        val cutoff = nowUnixMillis - SLEEP_COMPLETION_GRACE_MILLIS
        val currentSelections = selections(currentCandidates, zoneId, cutoff)
        val byDate = base.dates.associateBy { it.current.localDate }.toMutableMap()
        currentSelections.forEach { (date, current) ->
            val publicationState = state(current)
            val pendingHr = current.hrHrvRecordCount > 0 && !publicationState.hrHrvVerified
            val pendingSleep = current.sleep.isNotEmpty() && !publicationState.sleepVerified
            val existing = byDate[date]
            if (existing != null) {
                byDate[date] = existing.copy(
                    publishHrHrv = existing.publishHrHrv || pendingHr,
                    publishSleep = existing.publishSleep || pendingSleep,
                )
            } else if (pendingHr || pendingSleep) {
                byDate[date] = AffectedDatePublication(
                    previous = emptySelection(date, zoneId),
                    current = current,
                    publishHrHrv = pendingHr,
                    publishSleep = pendingSleep,
                )
            }
        }
        return ForegroundPublicationPlan(
            dates = byDate.values.sortedBy { it.current.localDate },
            deferredRecentSleepRecords = base.deferredRecentSleepRecords,
        )
    }

    private fun selections(
        candidates: HealthConnectCandidateSet,
        zoneId: ZoneId,
        sleepCutoff: Long,
    ): Map<LocalDate, DailyPublicationSelection> =
        DailyPublicationSelector.availableDates(candidates, zoneId).associateWith { date ->
            DailyPublicationSelector.select(candidates, date, zoneId).let { selection ->
                selection.copy(sleep = selection.sleep.filter { it.endUnixMillis <= sleepCutoff })
            }
        }

    private fun emptySelection(date: LocalDate, zoneId: ZoneId) = DailyPublicationSelection(
        localDate = date,
        zoneId = zoneId,
        heartRate = emptyList(),
        hrv = emptyList(),
        sleep = emptyList(),
    )

    const val SLEEP_COMPLETION_GRACE_MILLIS = 30L * 60L * 1_000L
}

data class ForegroundRunReport(
    val passed: Boolean,
    val syncSessions: Int,
    val receivedEvents: Int,
    val addedEvents: Int,
    val storedEvents: Long?,
    val affectedDates: Int,
    val heartRateRecords: Int,
    val heartRateSamples: Int,
    val hrvRecords: Int,
    val sleepRecords: Int,
    val obsoleteRecordsDeleted: Int,
    val deferredRecentSleepRecords: Int,
    val connectionRetries: Int = 0,
    val startingBatteryPercent: Int? = null,
    val skippedLowBattery: Boolean = false,
    val deferredUnavailable: Boolean = false,
    val detail: String? = null,
) {
    fun statusText(): String {
        val outcome = when {
            skippedLowBattery -> "skipped"
            deferredUnavailable -> "deferred"
            passed -> "passed"
            else -> "failed"
        }
        val stored = storedEvents?.let { ", stored $it total" }.orEmpty()
        val publication = if (affectedDates == 0) {
            if (passed) "no candidate dates changed; nothing written" else "publication not completed"
        } else {
            "affected dates $affectedDates; verified HR records $heartRateRecords with " +
                "$heartRateSamples samples, HRV $hrvRecords, completed sleep $sleepRecords; " +
                "obsolete app records deleted $obsoleteRecordsDeleted"
        }
        val deferred = if (deferredRecentSleepRecords > 0) {
            "; recent sleep deferred $deferredRecentSleepRecords"
        } else ""
        val suffix = detail?.let { "; $it" }.orEmpty()
        val battery = startingBatteryPercent?.let { ", starting ring battery $it%" }.orEmpty()
        return "Foreground sync/publish $outcome - sync sessions $syncSessions, connection retries " +
            "$connectionRetries$battery, received " +
            "$receivedEvents, added $addedEvents$stored; $publication$deferred$suffix."
    }
}

class ForegroundRunStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun markAttempt(
        nowUnixMillis: Long,
        source: String = "manual",
        phoneBattery: PhoneBatterySnapshot = PhoneBatterySnapshot(null, null),
    ) {
        recordInterruptedAttemptIfNeeded()
        preferences.edit()
            .putLong(LAST_ATTEMPT, nowUnixMillis)
            .putString(PENDING_SOURCE, source)
            .putString(UI_OUTCOME, ForegroundRunOutcome.RUNNING.name)
            .putNullableInt(PENDING_PHONE_PERCENT, phoneBattery.percent)
            .putNullableBoolean(PENDING_PHONE_CHARGING, phoneBattery.charging)
            .putString(CURRENT_STATUS, "Preparing foreground sync/publish...")
            .apply()
    }

    fun markProgress(status: String) {
        preferences.edit().putString(CURRENT_STATUS, status).apply()
    }

    fun markFinished(
        nowUnixMillis: Long,
        report: ForegroundRunReport,
        phoneBattery: PhoneBatterySnapshot = PhoneBatterySnapshot(null, null),
    ) {
        val attempt = preferences.getLong(LAST_ATTEMPT, nowUnixMillis)
        appendJournal(
            ForegroundRunJournalEntry(
                attemptUnixMillis = attempt,
                finishedUnixMillis = nowUnixMillis,
                source = preferences.getString(PENDING_SOURCE, null) ?: "unknown",
                startingPhoneBatteryPercent = preferences.getNullableInt(PENDING_PHONE_PERCENT),
                startingPhoneCharging = preferences.getNullableBoolean(PENDING_PHONE_CHARGING),
                endingPhoneBatteryPercent = phoneBattery.percent,
                endingPhoneCharging = phoneBattery.charging,
                reportText = report.statusText(),
            ),
        )
        preferences.edit().apply {
            val source = preferences.getString(PENDING_SOURCE, null) ?: "unknown"
            putString(LAST_RESULT, report.statusText())
            putString(CURRENT_STATUS, report.statusText())
            putLong(LAST_FINISHED, nowUnixMillis)
            putString(LAST_SOURCE, source)
            putString(UI_OUTCOME, report.outcome().name)
            putInt(UI_AFFECTED_DATES, report.affectedDates)
            putInt(UI_HR_SAMPLES, report.heartRateSamples)
            putInt(UI_HRV_RECORDS, report.hrvRecords)
            putInt(UI_SLEEP_RECORDS, report.sleepRecords)
            putString(UI_DETAIL, report.detail)
            report.startingBatteryPercent?.let {
                putInt(LAST_RING_BATTERY, it)
                putLong(LAST_RING_BATTERY_TIME, nowUnixMillis)
            }
            if (report.passed) putLong(LAST_SUCCESS, nowUnixMillis)
            remove(PENDING_SOURCE)
            remove(PENDING_PHONE_PERCENT)
            remove(PENDING_PHONE_CHARGING)
        }.apply()
    }

    fun statusText(): String {
        val attempt = preferences.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0 }
        val finished = preferences.getLong(LAST_FINISHED, 0L).takeIf { it > 0 }
        val success = preferences.getLong(LAST_SUCCESS, 0L).takeIf { it > 0 }
        val result = preferences.getString(LAST_RESULT, null)
        val outcome = if (attempt != null && (finished == null || attempt > finished)) {
            "The last attempt did not finish; no automatic publication was recorded for it."
        } else {
            result ?: "No foreground sync/publish result yet."
        }
        val journal = journalEntries()
        val recent = if (journal.isEmpty()) "" else journal.joinToString(
            separator = "\n",
            prefix = "\nRecent runs (newest first):\n",
        ) { "- ${it.summaryText()}" }
        return "Last attempt ${attempt.display()}, last success ${success.display()}. $outcome$recent"
    }

    fun currentStatusText(): String = preferences.getString(CURRENT_STATUS, null)
        ?: preferences.getString(LAST_RESULT, null)
        ?: "Not run"

    fun freshnessState() = ForegroundRunFreshnessState(
        lastAttemptMillis = preferences.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0 },
        lastFinishedMillis = preferences.getLong(LAST_FINISHED, 0L).takeIf { it > 0 },
        lastSuccessMillis = preferences.getLong(LAST_SUCCESS, 0L).takeIf { it > 0 },
    )

    fun uiSnapshot(nowUnixMillis: Long = System.currentTimeMillis()): ForegroundRunUiSnapshot {
        val freshness = freshnessState()
        val active = freshness.lastAttemptMillis != null &&
            (freshness.lastFinishedMillis == null || freshness.lastAttemptMillis > freshness.lastFinishedMillis) &&
            nowUnixMillis - freshness.lastAttemptMillis in 0 until UI_ACTIVE_ATTEMPT_MAX_AGE_MILLIS
        val storedOutcome = preferences.getString(UI_OUTCOME, null)
            ?.let { runCatching { ForegroundRunOutcome.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == ForegroundRunOutcome.RUNNING && !active }
        val migratedOutcome = when {
            active -> ForegroundRunOutcome.RUNNING
            freshness.lastAttemptMillis != null &&
                (freshness.lastFinishedMillis == null || freshness.lastAttemptMillis > freshness.lastFinishedMillis) ->
                ForegroundRunOutcome.UNKNOWN
            freshness.lastFinishedMillis == null -> ForegroundRunOutcome.NEVER
            freshness.lastSuccessMillis == freshness.lastFinishedMillis -> ForegroundRunOutcome.PASSED
            preferences.contains(LAST_RESULT) -> ForegroundRunOutcome.UNKNOWN
            else -> ForegroundRunOutcome.NEVER
        }
        return ForegroundRunUiSnapshot(
            hasTypedSummary = preferences.contains(UI_OUTCOME),
            outcome = if (active) ForegroundRunOutcome.RUNNING else storedOutcome ?: migratedOutcome,
            source = preferences.getString(
                if (active) PENDING_SOURCE else LAST_SOURCE,
                null,
            ),
            currentStage = preferences.getString(CURRENT_STATUS, null),
            lastAttemptMillis = freshness.lastAttemptMillis,
            lastFinishedMillis = freshness.lastFinishedMillis,
            lastSuccessMillis = freshness.lastSuccessMillis,
            affectedDates = preferences.getInt(UI_AFFECTED_DATES, 0),
            heartRateSamples = preferences.getInt(UI_HR_SAMPLES, 0),
            hrvRecords = preferences.getInt(UI_HRV_RECORDS, 0),
            sleepRecords = preferences.getInt(UI_SLEEP_RECORDS, 0),
            detail = preferences.getString(UI_DETAIL, null),
            ringBatteryPercent = preferences.getNullableInt(LAST_RING_BATTERY),
            ringBatteryMeasuredMillis = preferences.getLong(LAST_RING_BATTERY_TIME, 0L).takeIf { it > 0 },
        )
    }

    internal fun journalEntries(): List<ForegroundRunJournalEntry> = preferences
        .getString(JOURNAL, null)
        .orEmpty()
        .lineSequence()
        .mapNotNull(ForegroundRunJournalCodec::decode)
        .toList()

    private fun recordInterruptedAttemptIfNeeded() {
        val attempt = preferences.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0 } ?: return
        val finished = preferences.getLong(LAST_FINISHED, 0L).takeIf { it > 0 }
        if (finished != null && attempt <= finished) return
        appendJournal(
            ForegroundRunJournalEntry(
                attemptUnixMillis = attempt,
                finishedUnixMillis = null,
                source = preferences.getString(PENDING_SOURCE, null) ?: "unknown",
                startingPhoneBatteryPercent = preferences.getNullableInt(PENDING_PHONE_PERCENT),
                startingPhoneCharging = preferences.getNullableBoolean(PENDING_PHONE_CHARGING),
                endingPhoneBatteryPercent = null,
                endingPhoneCharging = null,
                reportText = "Foreground sync/publish unfinished - no completion was recorded.",
            ),
        )
    }

    private fun appendJournal(entry: ForegroundRunJournalEntry) {
        val entries = (listOf(entry) + journalEntries()).take(MAX_JOURNAL_ENTRIES)
        preferences.edit().putString(
            JOURNAL,
            entries.joinToString("\n", transform = ForegroundRunJournalCodec::encode),
        ).apply()
    }

    private fun android.content.SharedPreferences.Editor.putNullableInt(key: String, value: Int?) =
        if (value == null) remove(key) else putInt(key, value)

    private fun android.content.SharedPreferences.Editor.putNullableBoolean(key: String, value: Boolean?) =
        if (value == null) remove(key) else putBoolean(key, value)

    private fun android.content.SharedPreferences.getNullableInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.getNullableBoolean(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun Long?.display(): String = this?.let { Instant.ofEpochMilli(it).toString() } ?: "never"

    private companion object {
        const val PREFERENCES = "foreground-sync-publication-v1"
        const val LAST_ATTEMPT = "last-attempt"
        const val LAST_FINISHED = "last-finished"
        const val LAST_SUCCESS = "last-success"
        const val LAST_RESULT = "last-result"
        const val CURRENT_STATUS = "current-status"
        const val PENDING_SOURCE = "pending-source"
        const val PENDING_PHONE_PERCENT = "pending-phone-percent"
        const val PENDING_PHONE_CHARGING = "pending-phone-charging"
        const val JOURNAL = "run-journal-v1"
        const val LAST_SOURCE = "last-source"
        const val UI_OUTCOME = "ui-outcome-v1"
        const val UI_AFFECTED_DATES = "ui-affected-dates-v1"
        const val UI_HR_SAMPLES = "ui-hr-samples-v1"
        const val UI_HRV_RECORDS = "ui-hrv-records-v1"
        const val UI_SLEEP_RECORDS = "ui-sleep-records-v1"
        const val UI_DETAIL = "ui-detail-v1"
        const val LAST_RING_BATTERY = "last-ring-battery-v1"
        const val LAST_RING_BATTERY_TIME = "last-ring-battery-time-v1"
        const val UI_ACTIVE_ATTEMPT_MAX_AGE_MILLIS = 45L * 60L * 1_000L
        const val MAX_JOURNAL_ENTRIES = 30
    }
}

private fun ForegroundRunReport.outcome(): ForegroundRunOutcome = when {
    skippedLowBattery -> ForegroundRunOutcome.SKIPPED
    deferredUnavailable -> ForegroundRunOutcome.DEFERRED
    passed -> ForegroundRunOutcome.PASSED
    else -> ForegroundRunOutcome.FAILED
}

enum class ForegroundRunOutcome { NEVER, RUNNING, PASSED, DEFERRED, SKIPPED, FAILED, UNKNOWN }

data class ForegroundRunUiSnapshot(
    val hasTypedSummary: Boolean,
    val outcome: ForegroundRunOutcome,
    val source: String?,
    val currentStage: String?,
    val lastAttemptMillis: Long?,
    val lastFinishedMillis: Long?,
    val lastSuccessMillis: Long?,
    val affectedDates: Int,
    val heartRateSamples: Int,
    val hrvRecords: Int,
    val sleepRecords: Int,
    val detail: String?,
    val ringBatteryPercent: Int?,
    val ringBatteryMeasuredMillis: Long?,
)

data class ForegroundRunFreshnessState(
    val lastAttemptMillis: Long?,
    val lastFinishedMillis: Long?,
    val lastSuccessMillis: Long?,
)

internal data class ForegroundRunJournalEntry(
    val attemptUnixMillis: Long,
    val finishedUnixMillis: Long?,
    val source: String,
    val startingPhoneBatteryPercent: Int?,
    val startingPhoneCharging: Boolean?,
    val endingPhoneBatteryPercent: Int?,
    val endingPhoneCharging: Boolean?,
    val reportText: String,
) {
    fun summaryText(): String {
        val timestamp = Instant.ofEpochMilli(finishedUnixMillis ?: attemptUnixMillis)
        val duration = finishedUnixMillis?.let {
            ", duration ${((it - attemptUnixMillis).coerceAtLeast(0L) + 999L) / 1_000L}s"
        }.orEmpty()
        val phone = if (startingPhoneBatteryPercent == null && endingPhoneBatteryPercent == null) {
            ""
        } else {
            val start = startingPhoneBatteryPercent?.let { "$it%" } ?: "unknown"
            val end = endingPhoneBatteryPercent?.let { "$it%" } ?: "unknown"
            val charging = chargingTransition(startingPhoneCharging, endingPhoneCharging)
            ", phone $start->$end$charging"
        }
        return "$timestamp, $source$duration$phone. $reportText"
    }

    private fun chargingTransition(start: Boolean?, end: Boolean?): String {
        fun Boolean?.label() = when (this) {
            true -> "charging"
            false -> "not charging"
            null -> "charging unknown"
        }
        return " (${start.label()} -> ${end.label()})"
    }
}

internal object ForegroundRunJournalCodec {
    fun encode(entry: ForegroundRunJournalEntry): String = listOf(
        entry.attemptUnixMillis.toString(),
        entry.finishedUnixMillis?.toString().orEmpty(),
        entry.source,
        entry.startingPhoneBatteryPercent?.toString().orEmpty(),
        entry.startingPhoneCharging.flag(),
        entry.endingPhoneBatteryPercent?.toString().orEmpty(),
        entry.endingPhoneCharging.flag(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(entry.reportText.toByteArray(Charsets.UTF_8)),
    ).joinToString("|")

    fun decode(encoded: String): ForegroundRunJournalEntry? = runCatching {
        val fields = encoded.split('|', limit = 8)
        require(fields.size == 8)
        ForegroundRunJournalEntry(
            attemptUnixMillis = fields[0].toLong(),
            finishedUnixMillis = fields[1].toLongOrNull(),
            source = fields[2],
            startingPhoneBatteryPercent = fields[3].toIntOrNull(),
            startingPhoneCharging = fields[4].booleanFlag(),
            endingPhoneBatteryPercent = fields[5].toIntOrNull(),
            endingPhoneCharging = fields[6].booleanFlag(),
            reportText = String(Base64.getUrlDecoder().decode(fields[7]), Charsets.UTF_8),
        )
    }.getOrNull()

    private fun Boolean?.flag(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    private fun String.booleanFlag(): Boolean? = when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
