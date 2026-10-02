package dev.pocketfamiliar.perception

internal enum class UsageEventKind { FOREGROUND, BACKGROUND, SCREEN_OFF, SCREEN_ON, DEVICE_SHUTDOWN, DEVICE_STARTUP }

internal data class AppUsageEvent(
    val atMillis: Long,
    val kind: UsageEventKind,
    val packageName: String? = null,
    val className: String? = null,
)

internal data class AppForegroundDuration(
    val packageName: String,
    val durationMillis: Long,
    val lastUsedAtMillis: Long,
)

/**
 * Estimates the most recently resumed app's foreground time, clipped to an exact window.
 * Events before the window establish whether an app was already open at its boundary.
 * Android/OEM event gaps and split-screen mean this is an estimate, not a screen-time ledger.
 */
internal fun reduceAppUsage(
    events: Sequence<AppUsageEvent>,
    windowStartMillis: Long,
    windowEndMillis: Long,
    selectedPackages: Set<String>,
): List<AppForegroundDuration> {
    require(windowStartMillis >= 0 && windowEndMillis >= windowStartMillis)
    if (selectedPackages.isEmpty() || windowStartMillis == windowEndMillis) return emptyList()

    var activePackage: String? = null
    var activeClass: String? = null
    var startedAt = windowStartMillis
    var screenOn = true
    var previousAt = 0L
    val durations = mutableMapOf<String, Long>()
    val lastUsed = mutableMapOf<String, Long>()

    fun finish(atMillis: Long) {
        val packageName = activePackage
        val start = maxOf(startedAt, windowStartMillis)
        val end = minOf(atMillis, windowEndMillis)
        if (packageName != null && packageName in selectedPackages && end > start) {
            durations[packageName] = (durations[packageName] ?: 0L) + (end - start)
            lastUsed[packageName] = end
        }
        activePackage = null
        activeClass = null
    }

    for (event in events) {
        // queryEvents is chronological. Ignore malformed/out-of-order events conservatively.
        if (event.atMillis < previousAt || event.atMillis < 0) continue
        if (event.atMillis > windowEndMillis) break
        previousAt = event.atMillis
        when (event.kind) {
            UsageEventKind.FOREGROUND -> {
                if (!screenOn || event.packageName == null) continue
                if (activePackage != event.packageName) {
                    finish(event.atMillis)
                    activePackage = event.packageName
                    startedAt = event.atMillis
                }
                activeClass = event.className
            }
            UsageEventKind.BACKGROUND -> {
                // A late pause from a different activity must not close its replacement.
                if (activePackage == event.packageName &&
                    (activeClass == null || event.className == null || activeClass == event.className)
                ) finish(event.atMillis)
            }
            UsageEventKind.SCREEN_OFF, UsageEventKind.DEVICE_SHUTDOWN -> {
                finish(event.atMillis)
                screenOn = false
            }
            UsageEventKind.DEVICE_STARTUP -> {
                // A missing shutdown gives no reliable end time for the old activity.
                activePackage = null
                activeClass = null
                // Android can report SCREEN_INTERACTIVE immediately before DEVICE_STARTUP.
                // A later real resume is sufficient to establish foreground use after boot.
                screenOn = true
            }
            UsageEventKind.SCREEN_ON -> {
                screenOn = true
                // Do not invent a resume when Android supplied no foreground event.
            }
        }
    }
    finish(windowEndMillis)
    return durations.map { (packageName, duration) ->
        AppForegroundDuration(packageName, duration, lastUsed.getValue(packageName))
    }.sortedWith(compareByDescending<AppForegroundDuration> { it.durationMillis }
        .thenByDescending { it.lastUsedAtMillis }.thenBy { it.packageName })
}
