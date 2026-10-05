package dev.local.ourahealthbridge

import dev.local.ourahealthbridge.healthconnect.ForegroundRunFreshnessState
import dev.local.ourahealthbridge.healthconnect.ForegroundRunOutcome
import dev.local.ourahealthbridge.healthconnect.ForegroundRunReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRecoveryPolicyTest {
    private val now = 10L * 60L * 60L * 1_000L

    @Test
    fun automaticTimeoutIsDeferredButManualTimeoutKeepsImmediateRetryBehavior() {
        assertTrue(
            AutomaticConnectionFailurePolicy.shouldDefer(
                ForegroundSyncService.SOURCE_PERIODIC,
                AutomaticConnectionFailurePolicy.GATT_CONNECTION_TIMEOUT,
            ),
        )
        assertFalse(
            AutomaticConnectionFailurePolicy.shouldDefer(
                ForegroundSyncService.SOURCE_MANUAL,
                AutomaticConnectionFailurePolicy.GATT_CONNECTION_TIMEOUT,
            ),
        )
        assertFalse(AutomaticConnectionFailurePolicy.shouldDefer(ForegroundSyncService.SOURCE_PERIODIC, 257))
    }

    @Test
    fun aRecoveryTimeoutDoesNotQueueAnotherRecovery() {
        assertFalse(AutomaticConnectionFailurePolicy.shouldQueueFallback(
            ForegroundSyncService.SOURCE_RECOVERY, presenceObservationActive = false,
        ))
        assertTrue(AutomaticConnectionFailurePolicy.shouldQueueFallback(
            ForegroundSyncService.SOURCE_PERIODIC, presenceObservationActive = false,
        ))
        assertFalse(AutomaticConnectionFailurePolicy.shouldQueueFallback(
            ForegroundSyncService.SOURCE_PERIODIC, presenceObservationActive = true,
        ))
    }

    @Test
    fun automaticDispatchesCoalesceWithActiveRunsAndRecentSuccesses() {
        assertTrue(
            AutomaticSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunFreshnessState(now - 1_000L, null, null),
            )!!.contains("active"),
        )
        assertTrue(
            AutomaticSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunFreshnessState(now - 60_000L, now - 30_000L, now - 30_000L),
            )!!.contains("30 minutes"),
        )
        assertNull(
            AutomaticSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunFreshnessState(
                    now - AutomaticSyncDispatchPolicy.ACTIVE_ATTEMPT_MAX_AGE_MILLIS,
                    null,
                    now - AutomaticSyncDispatchPolicy.RECENT_SUCCESS_MILLIS,
                ),
            ),
        )
    }

    @Test
    fun unavailableOutcomeIsReportedAsDeferred() {
        val text = ForegroundRunReport(
            passed = false,
            syncSessions = 1,
            receivedEvents = 0,
            addedEvents = 0,
            storedEvents = 200,
            affectedDates = 0,
            heartRateRecords = 0,
            heartRateSamples = 0,
            hrvRecords = 0,
            sleepRecords = 0,
            obsoleteRecordsDeleted = 0,
            deferredRecentSleepRecords = 0,
            deferredUnavailable = true,
        ).statusText()

        assertTrue(text.startsWith("Foreground sync/publish deferred"))
        assertTrue(text.contains("publication not completed"))
    }

    @Test
    fun presenceDoesNotRedispatchForThreeHoursAfterAnyPresenceAttempt() {
        val reason = PresenceSyncDispatchPolicy.coalescingReason(
            now,
            ForegroundRunFreshnessState(now - 90_000L, now - 60_000L, null),
            ForegroundRunOutcome.FAILED,
            ForegroundSyncService.SOURCE_PRESENCE,
        )

        assertTrue(reason!!.contains("presence-triggered"))
    }

    @Test
    fun presenceCanRecoverARecentPeriodicUnavailableOutcome() {
        assertNull(
            PresenceSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunFreshnessState(now - 90_000L, now - 60_000L, null),
                ForegroundRunOutcome.DEFERRED,
                ForegroundSyncService.SOURCE_PERIODIC,
            ),
        )
    }

    @Test
    fun presenceWaitsAfterRecentPublicationFailureButNotAfterCooldown() {
        val recent = ForegroundRunFreshnessState(now - 90_000L, now - 60_000L, null)
        assertTrue(
            PresenceSyncDispatchPolicy.coalescingReason(
                now,
                recent,
                ForegroundRunOutcome.FAILED,
                ForegroundSyncService.SOURCE_PERIODIC,
            )!!.contains("periodic or manual"),
        )
        assertNull(
            PresenceSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunFreshnessState(
                    lastAttemptMillis = now - PresenceSyncDispatchPolicy.RECENT_PRESENCE_ATTEMPT_MILLIS - 30_000L,
                    lastFinishedMillis = now - PresenceSyncDispatchPolicy.RECENT_PRESENCE_ATTEMPT_MILLIS,
                    lastSuccessMillis = null,
                ),
                ForegroundRunOutcome.FAILED,
                ForegroundSyncService.SOURCE_PERIODIC,
            ),
        )
    }

    @Test
    fun manualFailureDoesNotSuppressAGenuineReturnToRangeCallback() {
        assertNull(
            PresenceSyncDispatchPolicy.coalescingReason(
                now,
                ForegroundRunFreshnessState(now - 90_000L, now - 60_000L, null),
                ForegroundRunOutcome.FAILED,
                ForegroundSyncService.SOURCE_MANUAL,
            ),
        )
    }

    @Test
    fun manualStatus147UsesOnlyOneImmediateRetry() {
        assertEquals(
            1,
            AutomaticConnectionFailurePolicy.maxImmediateRetries(
                ForegroundSyncService.SOURCE_MANUAL,
                AutomaticConnectionFailurePolicy.GATT_CONNECTION_TIMEOUT,
                3,
            ),
        )
        assertEquals(
            3,
            AutomaticConnectionFailurePolicy.maxImmediateRetries(
                ForegroundSyncService.SOURCE_MANUAL,
                257,
                3,
            ),
        )
    }

    @Test
    fun verificationFailureReportsOnlyPrivacySafeMismatchCategory() {
        val text = publicationFailureCategory(
            IllegalStateException("sleep exact read-back mismatch: sleep ID/count"),
        )

        assertTrue(text.contains("sleep ID/count"))
    }
}
