package dev.pocketfamiliar.perception

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlin.math.roundToInt

/** Reads a bounded event window on demand. It never keeps a listener or raw event history. */
class AppUsagePerceptionSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val settings = AppUsageSettings(applicationContext)

    fun observe(nowMillis: Long): AppUsageObservation? {
        val selected = settings.selectedPackages
        if (!settings.enabled || selected.isEmpty() || !hasAppUsageAccess(applicationContext)) return null
        return try {
            val manager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val windowStart = (nowMillis - WINDOW_MILLIS).coerceAtLeast(0)
            // Yesterday's events infer an app already open at the start of this hour.
            val lookbackStart = (windowStart - LOOKBACK_MILLIS).coerceAtLeast(0)
            val usageEvents = manager.queryEvents(lookbackStart, nowMillis) ?: return null
            val events = sequence {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val kind = eventKind(event.eventType) ?: continue
                    yield(AppUsageEvent(event.timeStamp, kind, event.packageName, event.className))
                }
            }
            val durations = reduceAppUsage(events, windowStart, nowMillis, selected)
            val apps = listObservableApps(applicationContext).associateBy { it.packageName }
            AppUsageObservation(
                windowMinutes = 60,
                apps = durations.asSequence()
                    .filter { it.durationMillis >= 30_000L }
                    .mapNotNull { duration ->
                        apps[duration.packageName]?.let {
                            ObservedAppUsage(it.displayName, (duration.durationMillis / 60_000.0).roundToInt().coerceIn(1, 60))
                        }
                    }
                    .take(MAX_REPORTED_APPS)
                    .toList(),
            )
        } catch (_: Exception) {
            // Do not log app names or events; a missing observation must not break the creature.
            Log.w("PocketFamiliar", "App usage observation unavailable")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun eventKind(type: Int): UsageEventKind? = when (type) {
        UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageEventKind.FOREGROUND
        UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.BACKGROUND
        UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventKind.SCREEN_OFF
        UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventKind.SCREEN_ON
        UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.DEVICE_SHUTDOWN
        UsageEvents.Event.DEVICE_STARTUP -> UsageEventKind.DEVICE_STARTUP
        else -> null
    }

    companion object {
        private const val WINDOW_MILLIS = 3_600_000L
        private const val LOOKBACK_MILLIS = 86_400_000L
        private const val MAX_REPORTED_APPS = 5
    }
}
