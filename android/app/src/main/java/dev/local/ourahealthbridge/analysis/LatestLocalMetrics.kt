package dev.local.ourahealthbridge.analysis

import android.content.Context

data class LatestLocalMetrics(
    val heartRateBpm: Double?,
    val heartRateUnixMillis: Long?,
    val hrvRmssdMillis: Double?,
    val hrvUnixMillis: Long?,
    val sleepStartUnixMillis: Long?,
    val sleepEndUnixMillis: Long?,
)

object LatestLocalMetricsSelector {
    fun select(candidates: HealthConnectCandidateSet): LatestLocalMetrics {
        val heartRate = candidates.heartRateRecords
            .flatMap { it.samples }
            .maxByOrNull { it.unixMillis }
        val hrv = candidates.hrvRecords.maxByOrNull { it.unixMillis }
        val sleep = candidates.sleepRecords.maxByOrNull { it.endUnixMillis }
        return LatestLocalMetrics(
            heartRateBpm = heartRate?.bpm,
            heartRateUnixMillis = heartRate?.unixMillis,
            hrvRmssdMillis = hrv?.rmssdMillis,
            hrvUnixMillis = hrv?.unixMillis,
            sleepStartUnixMillis = sleep?.startUnixMillis,
            sleepEndUnixMillis = sleep?.endUnixMillis,
        )
    }
}

/** Private cache for the UI; values are never logged or included in diagnostics. */
class LatestLocalMetricsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(metrics: LatestLocalMetrics) {
        preferences.edit().apply {
            putNullableDouble(HR_BPM, metrics.heartRateBpm)
            putNullableLong(HR_TIME, metrics.heartRateUnixMillis)
            putNullableDouble(HRV_RMSSD, metrics.hrvRmssdMillis)
            putNullableLong(HRV_TIME, metrics.hrvUnixMillis)
            putNullableLong(SLEEP_START, metrics.sleepStartUnixMillis)
            putNullableLong(SLEEP_END, metrics.sleepEndUnixMillis)
        }.apply()
    }

    fun load(): LatestLocalMetrics = LatestLocalMetrics(
        heartRateBpm = preferences.getNullableDouble(HR_BPM),
        heartRateUnixMillis = preferences.getNullableLong(HR_TIME),
        hrvRmssdMillis = preferences.getNullableDouble(HRV_RMSSD),
        hrvUnixMillis = preferences.getNullableLong(HRV_TIME),
        sleepStartUnixMillis = preferences.getNullableLong(SLEEP_START),
        sleepEndUnixMillis = preferences.getNullableLong(SLEEP_END),
    )

    private fun android.content.SharedPreferences.Editor.putNullableLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun android.content.SharedPreferences.Editor.putNullableDouble(key: String, value: Double?) {
        if (value == null) remove(key) else putLong(key, value.toBits())
    }

    private fun android.content.SharedPreferences.getNullableLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.getNullableDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

    private companion object {
        const val PREFERENCES = "latest-local-metrics-v1"
        const val HR_BPM = "hr-bpm"
        const val HR_TIME = "hr-time"
        const val HRV_RMSSD = "hrv-rmssd"
        const val HRV_TIME = "hrv-time"
        const val SLEEP_START = "sleep-start"
        const val SLEEP_END = "sleep-end"
    }
}
