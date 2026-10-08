package dev.local.ourahealthbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticVisualStateTest {
    @Test
    fun distinguishesIdleRunningCompleteAndFailureWithoutOpeningDetails() {
        assertEquals(DiagnosticVisualState.IDLE, diagnosticVisualState("Not run"))
        assertEquals(
            DiagnosticVisualState.RUNNING,
            diagnosticVisualState("Reading the private ring timeline around the walk..."),
        )
        assertEquals(
            DiagnosticVisualState.COMPLETE,
            diagnosticVisualState("Private step time-window audit - paired 12/30; nothing written."),
        )
        assertEquals(
            DiagnosticVisualState.ATTENTION,
            diagnosticVisualState("Foreground sync/publish failed; waiting for delayed retry."),
        )
        assertEquals(
            DiagnosticVisualState.WAITING,
            diagnosticVisualState(
                "Foreground sync/publish deferred; publication not completed; ring unavailable; " +
                    "waiting for return-to-range or periodic retry.",
            ),
        )
        assertEquals(
            DiagnosticVisualState.ACTIVE,
            diagnosticVisualState("Step trial started 2026-09-29T20:15:00Z"),
        )
        assertEquals(
            DiagnosticVisualState.ACTIVE,
            diagnosticVisualState("Walk end marked - exact interval is saved"),
        )
    }

    @Test
    fun waitingAndAttentionExplainWhatTheyMean() {
        assertEquals(
            "Ring not reachable; no action is required unless it stays nearby without reconnecting.",
            diagnosticVisualSummary("ring unavailable; waiting for periodic retry", DiagnosticVisualState.WAITING),
        )
        assertEquals(
            "ring sync failed (status 147)",
            diagnosticVisualSummary(
                "Foreground sync/publish failed; ring sync failed (status 147); retry is safe.",
                DiagnosticVisualState.ATTENTION,
            ),
        )
    }
}
