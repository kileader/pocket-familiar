package dev.pocketfamiliar.perception

import android.content.Context
import java.time.Clock

/** Composes independent observation sources without feeding them into the simulation. */
class PhonePerception(context: Context, clock: Clock? = null) {
    private val time = TimePerceptionSource(clock)
    private val battery = BatteryPerceptionSource(context)
    private val appUsage = AppUsagePerceptionSource(context)

    fun observe(): EnvironmentSnapshot {
        val timeObservation = time.observe()
        val batteryObservation = battery.observe()
        return EnvironmentSnapshot(
            observedAtMillis = timeObservation.observedAtMillis,
            localTime = timeObservation.localTime,
            timeOfDay = timeObservation.timeOfDay,
            batteryPercent = batteryObservation.percent,
            isCharging = batteryObservation.isCharging,
            appUsage = appUsage.observe(timeObservation.observedAtMillis),
        )
    }
}
