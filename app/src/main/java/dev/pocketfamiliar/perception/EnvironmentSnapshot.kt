package dev.pocketfamiliar.perception

import java.time.LocalTime

/** Observations of the phone, kept separate from the creature's canonical state. */
data class EnvironmentSnapshot(
    val observedAtMillis: Long,
    val localTime: LocalTime,
    val timeOfDay: TimeOfDay,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val appUsage: AppUsageObservation? = null,
)

enum class TimeOfDay {
    NIGHT,
    MORNING,
    AFTERNOON,
    EVENING,
}
