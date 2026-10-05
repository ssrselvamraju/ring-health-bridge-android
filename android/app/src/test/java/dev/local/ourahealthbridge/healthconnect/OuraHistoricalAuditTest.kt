package dev.local.ourahealthbridge.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraHistoricalAuditTest {
    @Test
    fun summarizesRecordStructureWithoutHealthValues() {
        val shapes = listOf(
            AuditRecordShape(
                type = AuditRecordType.HEART_RATE,
                startUnixMillis = 1_800_000_000_000,
                endUnixMillis = 1_800_000_120_000,
                childUnixMillis = listOf(1_800_000_000_000, 1_800_000_060_000, 1_800_000_120_000),
                hasZoneOffset = true,
                hasClientRecordId = true,
            ),
            AuditRecordShape(
                type = AuditRecordType.HRV_RMSSD,
                startUnixMillis = 1_800_000_000_000,
                endUnixMillis = 1_800_000_000_000,
                hasZoneOffset = true,
                hasClientRecordId = false,
            ),
            AuditRecordShape(
                type = AuditRecordType.HRV_RMSSD,
                startUnixMillis = 1_800_000_300_000,
                endUnixMillis = 1_800_000_300_000,
                hasZoneOffset = true,
                hasClientRecordId = false,
            ),
            AuditRecordShape(
                type = AuditRecordType.SLEEP,
                startUnixMillis = 1_800_000_000_000,
                endUnixMillis = 1_800_028_800_000,
                sleepStageTypes = listOf(
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                    SleepSessionRecord.STAGE_TYPE_DEEP,
                    SleepSessionRecord.STAGE_TYPE_REM,
                ),
                hasZoneOffset = true,
                hasClientRecordId = true,
            ),
        )

        val report = OuraAuditSummarizer.summarize(shapes)

        assertEquals(4, report.records)
        assertEquals(3, report.heartRateSamples)
        assertEquals(3.0, report.medianHeartRateSamplesPerRecord!!, 0.001)
        assertEquals(120.0, report.medianHeartRateRecordDurationSeconds!!, 0.001)
        assertEquals(60.0, report.medianHeartRateSampleSpacingSeconds!!, 0.001)
        assertEquals(5.0, report.medianHrvCadenceMinutes!!, 0.001)
        assertEquals(8.0, report.medianSleepDurationHours!!, 0.001)
        assertEquals(4, report.sleepStages)
        assertEquals(4, report.zoneOffsetRecords)
        assertEquals(2, report.clientIdRecords)
        assertTrue(report.statusText().contains("Read only; nothing exported or written"))
    }

    @Test
    fun reportsMissingOuraOriginWithoutDetails() {
        val report = OuraAuditSummarizer.summarize(emptyList())

        assertEquals(0, report.records)
        assertTrue(report.statusText().contains("no records found"))
    }

    @Test
    fun labelsSamsungHealthWithoutChangingPrivacyBoundary() {
        val report = OuraAuditSummarizer.summarize(
            emptyList(),
            HistoricalDataSource.SAMSUNG_HEALTH,
        )

        assertTrue(report.statusText().contains("Historical Samsung Health audit"))
        assertTrue(report.statusText().contains("nothing exported or written"))
    }

    @Test
    fun summarizesWhetherSleepAndOverlappingHrWereModifiedDuringOrAfterSleep() {
        val start = 1_800_000_000_000
        val end = start + 8 * 3_600_000
        val shapes = listOf(
            AuditRecordShape(
                type = AuditRecordType.SLEEP,
                startUnixMillis = start,
                endUnixMillis = end,
                hasZoneOffset = true,
                hasClientRecordId = true,
                lastModifiedUnixMillis = end + 30 * 60_000,
            ),
            AuditRecordShape(
                type = AuditRecordType.HEART_RATE,
                startUnixMillis = start + 3_600_000,
                endUnixMillis = start + 2 * 3_600_000,
                hasZoneOffset = true,
                hasClientRecordId = true,
                lastModifiedUnixMillis = start + 3 * 3_600_000,
            ),
        )

        val report = OuraAuditSummarizer.summarize(shapes)

        assertEquals(30.0, report.medianSleepPublicationDelayMinutes!!, 0.001)
        assertEquals(1, report.sleepModifiedWithinSixHoursAfter)
        assertEquals(1, report.sleepOverlapHeartRateModifiedDuringSleep)
        assertTrue(report.statusText().contains("publication timing"))
    }
}
