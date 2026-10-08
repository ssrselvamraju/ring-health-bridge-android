package dev.local.ourahealthbridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.local.ourahealthbridge.healthconnect.ForegroundRunFreshnessState
import dev.local.ourahealthbridge.healthconnect.ForegroundRunStateStore
import dev.local.ourahealthbridge.healthconnect.StepTrialStore
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ScheduledSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(INPUT_KIND) ?: KIND_PERIODIC
        val state = BackgroundScheduleStateStore(applicationContext)
        if ((kind == KIND_PERIODIC || kind == KIND_RECOVERY) && !state.isEnabled()) return Result.success()
        RingPresenceObservation(applicationContext).ensureIfEnabled()
        if (kind == KIND_PERIODIC || kind == KIND_RECOVERY) {
            val now = System.currentTimeMillis()
            val reason = AutomaticSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunStateStore(applicationContext).freshnessState(),
                StepTrialStore(applicationContext).active() != null,
            )
            if (reason != null) {
                state.markCoalesced(now, kind, reason)
                return Result.success()
            }
        }
        return runCatching {
            state.markDispatched(System.currentTimeMillis(), kind)
            ForegroundSyncService.start(
                applicationContext,
                when (kind) {
                    KIND_TEST -> ForegroundSyncService.SOURCE_TEST
                    KIND_RECOVERY -> ForegroundSyncService.SOURCE_RECOVERY
                    else -> ForegroundSyncService.SOURCE_PERIODIC
                },
            )
            Result.success()
        }.getOrElse {
            state.markDispatchFailure(System.currentTimeMillis(), it.javaClass.simpleName)
            Result.retry()
        }
    }

    companion object {
        const val INPUT_KIND = "kind"
        const val KIND_TEST = "test"
        const val KIND_PERIODIC = "periodic"
        const val KIND_RECOVERY = "recovery"
    }
}

class BackgroundSyncScheduler(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val state = BackgroundScheduleStateStore(context)

    fun enqueueOneTimeTest() {
        val request = OneTimeWorkRequestBuilder<ScheduledSyncWorker>()
            .setInitialDelay(TEST_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(ScheduledSyncWorker.INPUT_KIND, ScheduledSyncWorker.KIND_TEST).build())
            .build()
        workManager.enqueueUniqueWork(TEST_WORK, ExistingWorkPolicy.REPLACE, request)
        state.markTestQueued(System.currentTimeMillis(), TEST_DELAY_SECONDS)
    }

    fun enablePeriodic(nowUnixMillis: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()) {
        val initialDelay = BackgroundScheduleTiming.delayToNextSlotMillis(nowUnixMillis, zoneId)
        val request = PeriodicWorkRequestBuilder<ScheduledSyncWorker>(
            PERIOD_HOURS, TimeUnit.HOURS,
            FLEX_MINUTES, TimeUnit.MINUTES,
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder().putString(ScheduledSyncWorker.INPUT_KIND, ScheduledSyncWorker.KIND_PERIODIC).build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        state.setEnabled(true, nowUnixMillis, initialDelay)
        RingPresenceObservation(context).ensureIfEnabled()
    }

    fun disablePeriodic() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
        state.setEnabled(false, System.currentTimeMillis(), null)
        RingPresenceObservation(context).stop()
        cancelUnavailableRecovery()
    }

    fun enqueueUnavailableRecovery(delayMinutes: Long = RECOVERY_DELAY_MINUTES) {
        if (!state.isEnabled()) return
        val request = OneTimeWorkRequestBuilder<ScheduledSyncWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder().putString(ScheduledSyncWorker.INPUT_KIND, ScheduledSyncWorker.KIND_RECOVERY).build(),
            )
            .build()
        workManager.enqueueUniqueWork(RECOVERY_WORK, ExistingWorkPolicy.REPLACE, request)
        state.markRecoveryQueued(System.currentTimeMillis(), delayMinutes)
    }

    fun cancelUnavailableRecovery() {
        workManager.cancelUniqueWork(RECOVERY_WORK)
    }

    private companion object {
        const val TEST_WORK = "oura-background-trigger-test-v1"
        const val PERIODIC_WORK = "oura-periodic-sync-v1"
        const val RECOVERY_WORK = "oura-unavailable-recovery-v1"
        const val TEST_DELAY_SECONDS = 15L
        const val PERIOD_HOURS = 3L
        const val FLEX_MINUTES = 30L
        const val RECOVERY_DELAY_MINUTES = 30L
    }
}

object BackgroundScheduleTiming {
    fun delayToNextSlotMillis(nowUnixMillis: Long, zoneId: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowUnixMillis).atZone(zoneId)
        val nextHour = ((now.hour / PERIOD_HOURS) + 1) * PERIOD_HOURS
        val next = if (nextHour >= 24) {
            now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
        } else {
            now.toLocalDate().atTime(nextHour, 0).atZone(zoneId)
        }
        return (next.toInstant().toEpochMilli() - nowUnixMillis).coerceAtLeast(0L)
    }

    fun nominalNextDispatchMillis(lastDispatchUnixMillis: Long): Long =
        lastDispatchUnixMillis + PERIOD_HOURS * 60L * 60L * 1_000L

    fun appearsLate(nowUnixMillis: Long, lastDispatchUnixMillis: Long): Boolean =
        nowUnixMillis > nominalNextDispatchMillis(lastDispatchUnixMillis) + LATE_TOLERANCE_MILLIS

    private const val PERIOD_HOURS = 3
    private const val LATE_TOLERANCE_MILLIS = 2L * 60L * 60L * 1_000L
}

class BackgroundScheduleStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(ENABLED, false)

    fun isPresenceObservationActive(): Boolean =
        preferences.getBoolean(PRESENCE_ACTIVE, false)

    fun setEnabled(enabled: Boolean, now: Long, initialDelay: Long?) {
        preferences.edit().apply {
            putBoolean(ENABLED, enabled)
            putLong(LAST_CHANGED, now)
            if (initialDelay != null) putLong(INITIAL_DELAY, initialDelay) else remove(INITIAL_DELAY)
        }.apply()
    }

    fun markTestQueued(now: Long, delaySeconds: Long) {
        preferences.edit()
            .putString(LAST_EVENT, "One-time background test queued at ${Instant.ofEpochMilli(now)}; " +
                "dispatch expected after about $delaySeconds seconds.")
            .apply()
    }

    fun markDispatched(now: Long, kind: String) {
        preferences.edit().apply {
            putString(LAST_EVENT_KIND, BackgroundScheduleEventKind.DISPATCHED.name)
            putString(
                LAST_EVENT,
                "${kind.replaceFirstChar { it.uppercase() }} trigger dispatched the foreground service at " +
                    "${Instant.ofEpochMilli(now)}.",
            )
            if (kind == ScheduledSyncWorker.KIND_PERIODIC) putLong(LAST_PERIODIC_DISPATCH, now)
        }.apply()
    }

    fun markDispatchFailure(now: Long, category: String) {
        preferences.edit().putString(LAST_EVENT_KIND, BackgroundScheduleEventKind.FAILED.name).putString(
            LAST_EVENT,
            "Background trigger failed at ${Instant.ofEpochMilli(now)} ($category); WorkManager will retry.",
        ).apply()
    }

    fun markCoalesced(now: Long, kind: String, reason: String) {
        preferences.edit().putString(LAST_EVENT_KIND, BackgroundScheduleEventKind.COALESCED.name).putString(
            LAST_EVENT,
            "${kind.replaceFirstChar { it.uppercase() }} trigger coalesced at ${Instant.ofEpochMilli(now)}; $reason.",
        ).apply()
    }

    fun markRecoveryQueued(now: Long, delayMinutes: Long) {
        preferences.edit().putString(LAST_EVENT_KIND, BackgroundScheduleEventKind.RECOVERY_QUEUED.name).putString(
            LAST_EVENT,
            "Ring unavailable at ${Instant.ofEpochMilli(now)}; one recovery queued in about $delayMinutes minutes.",
        ).apply()
    }

    fun markPresenceObservation(now: Long, active: Boolean, category: String? = null) {
        val text = if (active) {
            "Return-to-range observation confirmed active at ${Instant.ofEpochMilli(now)}."
        } else {
            "Return-to-range observation unavailable at ${Instant.ofEpochMilli(now)}" +
                (category?.let { " ($it)." } ?: ".")
        }
        preferences.edit()
            .putString(PRESENCE_EVENT, text)
            .putBoolean(PRESENCE_ACTIVE, active)
            .putLong(PRESENCE_CHANGED, now)
            .putString(PRESENCE_CATEGORY, category)
            .apply()
    }

    fun uiSnapshot(): BackgroundScheduleUiSnapshot {
        val lastPeriodic = preferences.getLong(LAST_PERIODIC_DISPATCH, 0L).takeIf { it > 0 }
        return BackgroundScheduleUiSnapshot(
            enabled = isEnabled(),
            presenceObservation = when {
                !preferences.contains(PRESENCE_ACTIVE) -> PresenceObservationState.UNKNOWN
                preferences.getBoolean(PRESENCE_ACTIVE, false) -> PresenceObservationState.ENABLED
                else -> PresenceObservationState.UNAVAILABLE
            },
            presenceChangedMillis = preferences.getLong(PRESENCE_CHANGED, 0L).takeIf { it > 0 },
            presenceFailureCategory = preferences.getString(PRESENCE_CATEGORY, null),
            lastPeriodicDispatchMillis = lastPeriodic,
            nextNominalDispatchMillis = lastPeriodic?.let(BackgroundScheduleTiming::nominalNextDispatchMillis),
            lastEventKind = preferences.getString(LAST_EVENT_KIND, null)
                ?.let { runCatching { BackgroundScheduleEventKind.valueOf(it) }.getOrNull() },
        )
    }

    fun statusText(nowUnixMillis: Long = System.currentTimeMillis()): String {
        val enabled = if (isEnabled()) "enabled every 3 hours (inexact, Doze-aware)" else "disabled"
        val event = preferences.getString(LAST_EVENT, null) ?: "No background trigger attempted."
        val lastPeriodic = preferences.getLong(LAST_PERIODIC_DISPATCH, 0L).takeIf { it > 0 }
        val timing = if (!isEnabled() || lastPeriodic == null) {
            ""
        } else {
            val next = Instant.ofEpochMilli(BackgroundScheduleTiming.nominalNextDispatchMillis(lastPeriodic))
            val late = if (BackgroundScheduleTiming.appearsLate(nowUnixMillis, lastPeriodic)) {
                " The next dispatch appears delayed; Android may be deferring inexact work."
            } else {
                ""
            }
            " Last periodic dispatch ${Instant.ofEpochMilli(lastPeriodic)}; next nominal window around $next.$late"
        }
        val presence = preferences.getString(PRESENCE_EVENT, null)
            ?: "Return-to-range observation has not been registered yet."
        return "Periodic background sync $enabled. $presence $event$timing"
    }

    private companion object {
        const val PREFERENCES = "background-sync-schedule-v1"
        const val ENABLED = "enabled"
        const val LAST_CHANGED = "last-changed"
        const val INITIAL_DELAY = "initial-delay"
        const val LAST_EVENT = "last-event"
        const val LAST_PERIODIC_DISPATCH = "last-periodic-dispatch"
        const val PRESENCE_EVENT = "presence-event"
        const val PRESENCE_ACTIVE = "presence-active-v1"
        const val PRESENCE_CHANGED = "presence-changed-v1"
        const val PRESENCE_CATEGORY = "presence-category-v1"
        const val LAST_EVENT_KIND = "last-event-kind-v1"
    }
}

enum class PresenceObservationState { UNKNOWN, ENABLED, UNAVAILABLE }
enum class BackgroundScheduleEventKind { DISPATCHED, FAILED, COALESCED, RECOVERY_QUEUED }

data class BackgroundScheduleUiSnapshot(
    val enabled: Boolean,
    val presenceObservation: PresenceObservationState,
    val presenceChangedMillis: Long?,
    val presenceFailureCategory: String?,
    val lastPeriodicDispatchMillis: Long?,
    val nextNominalDispatchMillis: Long?,
    val lastEventKind: BackgroundScheduleEventKind?,
)

internal object AutomaticSyncDispatchPolicy {
    const val RECENT_SUCCESS_MILLIS = 30L * 60L * 1_000L
    const val ACTIVE_ATTEMPT_MAX_AGE_MILLIS = 45L * 60L * 1_000L

    fun coalescingReason(
        now: Long,
        state: ForegroundRunFreshnessState,
        controlledTrialActive: Boolean = false,
    ): String? {
        if (controlledTrialActive) return "a controlled step trial is active"
        val attempt = state.lastAttemptMillis
        val unfinished = attempt != null &&
            (state.lastFinishedMillis == null || attempt > state.lastFinishedMillis) &&
            now - attempt in 0 until ACTIVE_ATTEMPT_MAX_AGE_MILLIS
        if (unfinished) return "a sync is already active"
        val success = state.lastSuccessMillis
        if (success != null && now - success in 0 until RECENT_SUCCESS_MILLIS) {
            return "a successful sync completed less than 30 minutes ago"
        }
        return null
    }
}
