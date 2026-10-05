package dev.local.ourahealthbridge.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import dev.local.ourahealthbridge.analysis.HeartRateRecordCandidate
import dev.local.ourahealthbridge.analysis.HrvRecordCandidate
import dev.local.ourahealthbridge.analysis.SleepRecordCandidate
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

object HealthConnectRecordMapper {
    fun heartRate(candidate: HeartRateRecordCandidate): HeartRateRecord = HeartRateRecord(
        startTime = Instant.ofEpochMilli(candidate.startUnixMillis),
        startZoneOffset = ZoneOffset.ofTotalSeconds(candidate.startZoneOffsetSeconds),
        endTime = Instant.ofEpochMilli(candidate.endUnixMillis),
        endZoneOffset = ZoneOffset.ofTotalSeconds(candidate.endZoneOffsetSeconds),
        samples = candidate.samples.map {
            HeartRateRecord.Sample(
                time = Instant.ofEpochMilli(it.unixMillis),
                beatsPerMinute = it.bpm.roundToLong(),
            )
        },
        metadata = metadata(candidate.clientRecordId),
    )

    fun hrv(candidate: HrvRecordCandidate): HeartRateVariabilityRmssdRecord =
        HeartRateVariabilityRmssdRecord(
            time = Instant.ofEpochMilli(candidate.unixMillis),
            zoneOffset = ZoneOffset.ofTotalSeconds(candidate.zoneOffsetSeconds),
            heartRateVariabilityMillis = candidate.rmssdMillis,
            metadata = metadata(candidate.clientRecordId),
        )

    fun sleep(candidate: SleepRecordCandidate): SleepSessionRecord = SleepSessionRecord(
        startTime = Instant.ofEpochMilli(candidate.startUnixMillis),
        startZoneOffset = ZoneOffset.ofTotalSeconds(candidate.startZoneOffsetSeconds),
        endTime = Instant.ofEpochMilli(candidate.endUnixMillis),
        endZoneOffset = ZoneOffset.ofTotalSeconds(candidate.endZoneOffsetSeconds),
        metadata = metadata(candidate.clientRecordId),
        title = "Oura Ring sleep",
        notes = null,
        stages = emptyList(),
    )

    private fun metadata(clientRecordId: String): Metadata = Metadata.autoRecorded(
        device = DEVICE,
        clientRecordId = clientRecordId,
        clientRecordVersion = CLIENT_RECORD_VERSION,
    )

    private val DEVICE = Device(
        type = Device.TYPE_RING,
        manufacturer = "Oura",
        model = "Gen 3 Horizon",
    )

    // Version 2 introduces quality-marked source precedence in shared minutes.
    const val CLIENT_RECORD_VERSION = 2L
}
