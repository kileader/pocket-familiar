package dev.pocketfamiliar.ui

import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.TimeOfDay
import dev.pocketfamiliar.simulation.Behavior

/** Local presentation cues only; these never become creature behavior or stored state. */
internal enum class PhoneReaction(val caption: String, val description: String) {
    CHARGING("Keeping your phone company while it charges.", "Charger sparkles"),
    LOW_BATTERY("Your phone is running low. A little glance toward its battery.", "Low-battery glance"),
    NIGHT("The phone says it’s late. Swaying quietly in the moonlight.", "Quiet nighttime sway"),
}

internal fun PhoneReaction.captionFor(behavior: Behavior?): String =
    if (this == PhoneReaction.LOW_BATTERY && behavior == Behavior.RESTING) {
        "Your phone is running low. A small reminder beside a sleeping familiar."
    } else caption

internal fun phoneReaction(environment: EnvironmentSnapshot?): PhoneReaction? = when {
    environment == null -> null
    environment.isCharging == true -> PhoneReaction.CHARGING
    environment.isCharging == false && environment.batteryPercent in 0..20 -> PhoneReaction.LOW_BATTERY
    environment.timeOfDay == TimeOfDay.NIGHT -> PhoneReaction.NIGHT
    else -> null
}
