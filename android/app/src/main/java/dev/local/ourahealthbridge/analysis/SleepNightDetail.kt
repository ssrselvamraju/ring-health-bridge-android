package dev.local.ourahealthbridge.analysis

import android.content.Context
import dev.local.ourahealthbridge.protocol.DecodedRingEvent
import dev.local.ourahealthbridge.protocol.OuraEventDecoder
import dev.local.ourahealthbridge.protocol.RawRingEvent
import java.util.Locale
import kotlin.math.roundToInt

data class SleepTrendPoint(val unixMillis: Long, val value: Double)

data class SleepNightDetail(
    val startUnixMillis: Long,
    val endUnixMillis: Long,
    val heartRate: List<SleepTrendPoint>,
    val hrv: List<SleepTrendPoint>,
    val fingerTemperature: List<SleepTrendPoint>,
    val movementSignal: List<SleepTrendPoint>,
    val motionEvents: Int,
    val sleepAcmEvents: Int,
    val stageEvents: Int,
    val stageEpochs: Int,
    val spo2Events: Int,
    val spo2Samples: Int,
) {
    val durationMinutes: Long get() = (endUnixMillis - startUnixMillis).coerceAtLeast(0L) / 60_000L

    fun statusText(): String =
        "Sleep Detail v0 - sleep window ${durationMinutes / 60}h ${durationMinutes % 60}m; " +
            "HR ${heartRate.summary("bpm")}, RMSSD ${hrv.summary("ms")}, " +
            "finger-sensor temperature ${fingerTemperature.summary("°C")}; " +
            "movement signal ${movementSignal.size} five-minute bins from $sleepAcmEvents sleep-ACM and " +
            "$motionEvents compact-motion events; stage events $stageEvents ($stageEpochs decoded epochs), " +
            "finished SpO2 events $spo2Events ($spo2Samples decoded samples). " +
            "Sleep window is not proven total sleep; movement is not labeled restlessness or stages; " +
            "temperature is not body temperature or a Health Connect delta. Read only; nothing written."

    private fun List<SleepTrendPoint>.summary(unit: String): String {
        val median = map(SleepTrendPoint::value).median() ?: return "unavailable"
        return "median ${"%.1f".format(Locale.US, median)} $unit across $size bins"
    }
}

data class SleepInventoryRow(
    val tag: Int,
    val totalCount: Int,
    val sleepCount: Int,
    val bodyLengths: Map<Int, Int>,
    val mappedCount: Int,
    val decodedCount: Int,
    val sleepMedianCadenceSeconds: Double?,
) {
    fun display(): String {
        val lengths = bodyLengths.entries.sortedBy { it.key }.joinToString(",") { "${it.key}B:${it.value}" }
        val cadence = sleepMedianCadenceSeconds?.let { "${"%.1f".format(Locale.US, it)}s" } ?: "n/a"
        return "0x${tag.toString(16)} total $totalCount, sleep $sleepCount, lengths $lengths, " +
            "UTC $mappedCount, decoded $decodedCount, sleep cadence $cadence"
    }
}

data class SleepStructuralInventory(val rows: List<SleepInventoryRow>) {
    fun statusText(): String = "Private sleep structural inventory - " +
        rows.joinToString("; ") { it.display() } +
        ". Aggregates only; raw bodies and individual values stayed app-private; nothing written."
}

data class SleepDetailBuildResult(
    val detail: SleepNightDetail?,
    val inventory: SleepStructuralInventory,
)

object SleepNightDetailBuilder {
    val INVENTORY_TAGS: Set<Int> = setOf(
        0x49, 0x4b, 0x4c, 0x4e, 0x4f, 0x58, 0x5a, 0x6a, 0x6d,
        0x6e, 0x6f, 0x70, 0x72, 0x75, 0x76, 0x77, 0x8b,
    )

    fun build(events: List<RawRingEvent>, candidates: HealthConnectCandidateSet): SleepDetailBuildResult {
        val mapper = RingTimeMapper.fromEvents(events)
        val sleep = candidates.sleepRecords.maxByOrNull { it.endUnixMillis }
        val detail = sleep?.let { window ->
            val start = window.startUnixMillis
            val end = window.endUnixMillis
            val hr = candidates.heartRateRecords.flatMap { it.samples }
                .filter { it.unixMillis in start until end }
                .map { SleepTrendPoint(it.unixMillis, it.bpm) }
            val hrv = candidates.hrvRecords
                .filter { it.unixMillis in start until end }
                .map { SleepTrendPoint(it.unixMillis, it.rmssdMillis) }
            val decodedInWindow = events.mapNotNull { event ->
                val time = mapper.unixMillis(event.ringTimestampDeciseconds) ?: return@mapNotNull null
                if (time !in start until end) return@mapNotNull null
                Triple(time, event, OuraEventDecoder.decode(event))
            }
            val temperatures = decodedInWindow.flatMap { (time, _, decoded) ->
                (decoded as? DecodedRingEvent.Temperatures)?.celsius.orEmpty().map { time to it }
            }
            val sleepAcm = decodedInWindow.mapNotNull { (time, _, decoded) ->
                (decoded as? DecodedRingEvent.SleepAccelerometer)?.let { time to it.mad.average() }
            }
            val motion = decodedInWindow.mapNotNull { (time, _, decoded) ->
                (decoded as? DecodedRingEvent.Motion)?.let { time to it.motionSeconds.toDouble() }
            }
            val movementSource = if (sleepAcm.isNotEmpty()) sleepAcm else motion
            val stage = decodedInWindow.mapNotNull { it.third as? DecodedRingEvent.SleepPhases }
            val spo2 = decodedInWindow.mapNotNull { it.third as? DecodedRingEvent.Spo2 }
            SleepNightDetail(
                startUnixMillis = start,
                endUnixMillis = end,
                heartRate = hr.sortedBy(SleepTrendPoint::unixMillis),
                hrv = hrv.sortedBy(SleepTrendPoint::unixMillis),
                fingerTemperature = bucketMedian(temperatures),
                movementSignal = bucketMedian(movementSource),
                motionEvents = motion.size,
                sleepAcmEvents = sleepAcm.size,
                stageEvents = stage.size,
                stageEpochs = stage.sumOf { it.phases.size },
                spo2Events = spo2.size,
                spo2Samples = spo2.sumOf { it.percentages.size },
            )
        }
        return SleepDetailBuildResult(detail, buildInventory(events, mapper, detail))
    }

    private fun buildInventory(
        events: List<RawRingEvent>,
        mapper: RingTimeMapper,
        detail: SleepNightDetail?,
    ): SleepStructuralInventory {
        val rows = INVENTORY_TAGS.sorted().map { tag ->
            val tagged = events.filter { it.tag == tag }
            val mapped = tagged.mapNotNull { event ->
                mapper.unixMillis(event.ringTimestampDeciseconds)?.let { Triple(it, event, OuraEventDecoder.decode(event)) }
            }
            val inSleep = if (detail == null) emptyList() else mapped.filter { (time, _, _) ->
                time in detail.startUnixMillis until detail.endUnixMillis
            }
            val cadence = inSleep.map { it.first }.sorted().zipWithNext()
                .map { (first, second) -> (second - first) / 1_000.0 }.median()
            SleepInventoryRow(
                tag = tag,
                totalCount = tagged.size,
                sleepCount = inSleep.size,
                bodyLengths = tagged.groupingBy { it.body.size }.eachCount(),
                mappedCount = mapped.size,
                decodedCount = mapped.count { it.third != null },
                sleepMedianCadenceSeconds = cadence,
            )
        }
        return SleepStructuralInventory(rows)
    }

    private fun bucketMedian(values: List<Pair<Long, Double>>): List<SleepTrendPoint> = values
        .groupBy { Math.floorDiv(it.first, FIVE_MINUTES_MILLIS) }
        .toSortedMap()
        .mapNotNull { (bucket, entries) ->
            entries.map { it.second }.median()?.let {
                SleepTrendPoint(bucket * FIVE_MINUTES_MILLIS + FIVE_MINUTES_MILLIS / 2, it)
            }
        }

    private const val FIVE_MINUTES_MILLIS = 300_000L
}

class SleepNightDetailStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(detail: SleepNightDetail?) {
        preferences.edit().apply {
            if (detail == null) {
                clear()
            } else {
                putLong(START, detail.startUnixMillis)
                putLong(END, detail.endUnixMillis)
                putString(HR, encode(detail.heartRate))
                putString(HRV, encode(detail.hrv))
                putString(TEMP, encode(detail.fingerTemperature))
                putString(MOVEMENT, encode(detail.movementSignal))
                putInt(MOTION_EVENTS, detail.motionEvents)
                putInt(SLEEP_ACM_EVENTS, detail.sleepAcmEvents)
                putInt(STAGE_EVENTS, detail.stageEvents)
                putInt(STAGE_EPOCHS, detail.stageEpochs)
                putInt(SPO2_EVENTS, detail.spo2Events)
                putInt(SPO2_SAMPLES, detail.spo2Samples)
            }
        }.apply()
    }

    fun load(): SleepNightDetail? {
        if (!preferences.contains(START) || !preferences.contains(END)) return null
        return SleepNightDetail(
            startUnixMillis = preferences.getLong(START, 0L),
            endUnixMillis = preferences.getLong(END, 0L),
            heartRate = decode(preferences.getString(HR, null)),
            hrv = decode(preferences.getString(HRV, null)),
            fingerTemperature = decode(preferences.getString(TEMP, null)),
            movementSignal = decode(preferences.getString(MOVEMENT, null)),
            motionEvents = preferences.getInt(MOTION_EVENTS, 0),
            sleepAcmEvents = preferences.getInt(SLEEP_ACM_EVENTS, 0),
            stageEvents = preferences.getInt(STAGE_EVENTS, 0),
            stageEpochs = preferences.getInt(STAGE_EPOCHS, 0),
            spo2Events = preferences.getInt(SPO2_EVENTS, 0),
            spo2Samples = preferences.getInt(SPO2_SAMPLES, 0),
        )
    }

    private fun encode(points: List<SleepTrendPoint>): String = points.joinToString(";") {
        "${it.unixMillis},${it.value}"
    }

    private fun decode(text: String?): List<SleepTrendPoint> = text.orEmpty().split(';').mapNotNull { row ->
        val parts = row.split(',', limit = 2)
        val time = parts.getOrNull(0)?.toLongOrNull()
        val value = parts.getOrNull(1)?.toDoubleOrNull()
        if (time != null && value != null) SleepTrendPoint(time, value) else null
    }

    private companion object {
        const val PREFERENCES = "sleep-night-detail-v1"
        const val START = "start"
        const val END = "end"
        const val HR = "hr"
        const val HRV = "hrv"
        const val TEMP = "temp"
        const val MOVEMENT = "movement"
        const val MOTION_EVENTS = "motion-events"
        const val SLEEP_ACM_EVENTS = "sleep-acm-events"
        const val STAGE_EVENTS = "stage-events"
        const val STAGE_EPOCHS = "stage-epochs"
        const val SPO2_EVENTS = "spo2-events"
        const val SPO2_SAMPLES = "spo2-samples"
    }
}

fun sleepSparkline(points: List<SleepTrendPoint>): String {
    if (points.isEmpty()) return ""
    val values = points.map(SleepTrendPoint::value)
    val min = values.min()
    val max = values.max()
    val glyphs = "▁▂▃▄▅▆▇█"
    if (max == min) return glyphs[3].toString().repeat(values.size.coerceAtMost(48))
    val sampled = if (values.size <= 48) values else values.chunked((values.size + 47) / 48).map { it.average() }
    return sampled.joinToString("") { value ->
        val index = (((value - min) / (max - min)) * (glyphs.length - 1)).roundToInt()
            .coerceIn(0, glyphs.length - 1)
        glyphs[index].toString()
    }
}
