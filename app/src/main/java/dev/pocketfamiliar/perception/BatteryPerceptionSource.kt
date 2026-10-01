package dev.pocketfamiliar.perception

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlin.math.roundToInt

data class BatteryObservation(val percent: Int?, val isCharging: Boolean?)

/** A snapshot from Android's sticky battery broadcast; no listener or service is kept alive. */
class BatteryPerceptionSource(context: Context) {
    private val applicationContext = context.applicationContext

    fun observe(): BatteryObservation {
        val intent = try {
            applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (error: Exception) {
            Log.w("PocketFamiliar", "Battery observation unavailable", error)
            null
        } ?: return BatteryObservation(null, null)

        return batteryObservation(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
        )
    }
}

internal fun batteryObservation(level: Int, scale: Int, status: Int): BatteryObservation {
    val percent = if (scale > 0 && level in 0..scale) {
        (level.toDouble() / scale * 100).roundToInt().coerceIn(0, 100)
    } else {
        null
    }
    val isCharging = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
        else -> null
    }
    return BatteryObservation(percent, isCharging)
}
