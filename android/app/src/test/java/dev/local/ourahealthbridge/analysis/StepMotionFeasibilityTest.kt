package dev.local.ourahealthbridge.analysis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StepMotionFeasibilityTest {
    @Test
    fun packedAnalyzerCanSurfaceAnIntervalCandidateWithoutCallingItSteps() {
        val origin = Instant.parse("2026-09-30T03:00:00Z")
        val segments = listOf(
            StepWindowSegment("pre15", origin, origin.plusSeconds(900)),
            StepWindowSegment("walk6", origin.plusSeconds(900), origin.plusSeconds(1_260)),
            StepWindowSegment("post15", origin.plusSeconds(1_260), origin.plusSeconds(2_160)),
            StepWindowSegment("post30", origin.plusSeconds(2_160), origin.plusSeconds(3_060)),
        )
        val records = listOf(
            TimedStepBody(origin.plusSeconds(60).toEpochMilli(), byteArrayOf(0)),
            TimedStepBody(origin.plusSeconds(930).toEpochMilli(), byteArrayOf(100)),
            TimedStepBody(origin.plusSeconds(990).toEpochMilli(), byteArrayOf(200.toByte())),
            TimedStepBody(origin.plusSeconds(1_050).toEpochMilli(), byteArrayOf(200.toByte())),
            TimedStepBody(origin.plusSeconds(1_500).toEpochMilli(), byteArrayOf(0)),
        )

        val analysis = PackedStepAnalyzer.analyze(records, segments)

        assertTrue(analysis.interval.any {
            it.bitOffset == 0 && it.bitWidth == 8 && it.segmentSums[1] == 500L
        })
    }
}
