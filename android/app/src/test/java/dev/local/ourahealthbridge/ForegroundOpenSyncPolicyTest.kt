package dev.local.ourahealthbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundOpenSyncPolicyTest {
    private val now = 10L * 60L * 60L * 1_000L

    @Test
    fun startsWhenNoPreviousRunExists() {
        assertTrue(ForegroundOpenSyncPolicy.shouldStart(now, null, null, null))
    }

    @Test
    fun doesNotStartWhileTheLastSuccessIsFresh() {
        assertFalse(
            ForegroundOpenSyncPolicy.shouldStart(
                now,
                now - ForegroundOpenSyncPolicy.FRESHNESS_MILLIS + 1L,
                null,
                null,
            ),
        )
    }

    @Test
    fun startsAtTheFreshnessBoundary() {
        assertTrue(
            ForegroundOpenSyncPolicy.shouldStart(
                now,
                now - ForegroundOpenSyncPolicy.FRESHNESS_MILLIS,
                null,
                null,
            ),
        )
    }

    @Test
    fun recentAttemptOrForegroundDispatchPreventsDuplicateWork() {
        assertFalse(
            ForegroundOpenSyncPolicy.shouldStart(
                now,
                null,
                now - ForegroundOpenSyncPolicy.ATTEMPT_COOLDOWN_MILLIS + 1L,
                null,
            ),
        )
        assertFalse(
            ForegroundOpenSyncPolicy.shouldStart(
                now,
                null,
                null,
                now - ForegroundOpenSyncPolicy.ATTEMPT_COOLDOWN_MILLIS + 1L,
            ),
        )
    }
}
