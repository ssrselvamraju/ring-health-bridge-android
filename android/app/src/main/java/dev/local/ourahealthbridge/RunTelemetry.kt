package dev.local.ourahealthbridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class PhoneBatterySnapshot(
    val percent: Int?,
    val charging: Boolean?,
)

object PhoneBatteryReader {
    fun read(context: Context): PhoneBatterySnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return PhoneBatterySnapshot(null, null)
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) ((level * 100.0) / scale).toInt() else null
        val charging = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> false
            else -> null
        }
        return PhoneBatterySnapshot(percent, charging)
    }
}
