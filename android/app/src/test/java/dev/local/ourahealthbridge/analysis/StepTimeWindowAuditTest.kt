package dev.local.ourahealthbridge.analysis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class StepTimeWindowAuditTest {
    @Test
    fun reportKeepsCandidateInterpretationsExplicitlyExploratory() {
        val start = Instant.parse("2026-09-30T03:15:00Z")
        val window = StepResearchWindow(start, Duration.ofMinutes(6))
        val segments = listOf(
            StepWindowSegment("pre15", window.auditStart, window.walkStart),
            StepWindowSegment("walk6", window.walkStart, window.walkEnd),
            StepWindowSegment("post15", window.walkEnd, window.walkEnd.plusSeconds(900)),
            StepWindowSegment("post30", window.walkEnd.plusSeconds(900), window.auditEnd),
        )
        val candidate = StepWindowFieldCandidate(
            tag = 0x7e,
            byteOffset = 2,
            endian = "LE",
            monotonicPercent = 100,
            segmentDeltas = listOf(0, 500, 0, 0),
            segmentSums = listOf(0, 1_500, 0, 0),
        )
        val report = StepTimeWindowReport(
            window = window,
            zoneId = ZoneId.of("America/Los_Angeles"),
            segments = segments,
            pairedCounts = listOf(30, 12, 30, 30),
            unpaired7eCounts = listOf(0, 0, 0, 0),
            unpaired7fCounts = listOf(0, 0, 0, 0),
            counterCandidates = listOf(candidate),
            intervalCandidates = emptyList(),
            healthSources = emptyList(),
        )

        val text = report.statusText()

        assertTrue(text.contains("2026-09-29 20:15"))
        assertTrue(text.contains("0x7e LE-u16@2"))
        assertTrue(text.contains("not validated step counts"))
        assertTrue(text.contains("raw bodies and individual records stayed app-private"))
    }
}
