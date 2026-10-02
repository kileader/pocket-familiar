package dev.pocketfamiliar.perception

/** A local summary of selected apps, not screen contents or a record of individual events. */
data class AppUsageObservation(
    val windowMinutes: Int,
    val apps: List<ObservedAppUsage>,
)

data class ObservedAppUsage(
    val appName: String,
    val approximateMinutes: Int,
)
