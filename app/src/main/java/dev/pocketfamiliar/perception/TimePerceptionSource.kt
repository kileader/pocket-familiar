package dev.pocketfamiliar.perception

import java.time.Clock
import java.time.LocalTime

data class TimeObservation(
    val observedAtMillis: Long,
    val localTime: LocalTime,
    val timeOfDay: TimeOfDay,
)

class TimePerceptionSource(private val clock: Clock? = null) {
    fun observe(): TimeObservation {
        // Resolve the system zone on every observation so a resumed app follows timezone changes.
        val currentClock = clock ?: Clock.systemDefaultZone()
        val instant = currentClock.instant()
        val localTime = instant.atZone(currentClock.zone).toLocalTime()
        return TimeObservation(
            observedAtMillis = instant.toEpochMilli(),
            localTime = localTime,
            timeOfDay = timeOfDayAt(localTime),
        )
    }
}

fun timeOfDayAt(time: LocalTime): TimeOfDay = when (time.hour) {
    in 6..11 -> TimeOfDay.MORNING
    in 12..17 -> TimeOfDay.AFTERNOON
    in 18..21 -> TimeOfDay.EVENING
    else -> TimeOfDay.NIGHT
}
