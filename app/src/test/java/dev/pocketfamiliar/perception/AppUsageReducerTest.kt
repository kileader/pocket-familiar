package dev.pocketfamiliar.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageReducerTest {
    private fun foreground(at: Long, packageName: String = "chosen", className: String? = null) =
        AppUsageEvent(at, UsageEventKind.FOREGROUND, packageName, className)

    private fun background(at: Long, packageName: String = "chosen", className: String? = null) =
        AppUsageEvent(at, UsageEventKind.BACKGROUND, packageName, className)

    private fun reduce(vararg events: AppUsageEvent, start: Long = 100, end: Long = 200, selected: Set<String> = setOf("chosen")) =
        reduceAppUsage(events.asSequence(), start, end, selected)

    @Test
    fun `lookback establishes app already foreground at window start`() {
        assertEquals(50L, reduce(foreground(20), background(150)).single().durationMillis)
    }

    @Test
    fun `ongoing foreground use closes at observation end`() {
        val result = reduce(foreground(125)).single()
        assertEquals(75L, result.durationMillis)
        assertEquals(200L, result.lastUsedAtMillis)
    }

    @Test
    fun `events before window contribute no duration`() {
        assertTrue(reduce(foreground(20), background(50)).isEmpty())
    }

    @Test
    fun `unselected foreground replaces chosen app without exposing its identity`() {
        val results = reduce(foreground(110), foreground(130, "private"), background(150, "private"))
        assertEquals(listOf(AppForegroundDuration("chosen", 20, 130)), results)
    }

    @Test
    fun `screen off closes activity and no time is invented on screen on`() {
        assertEquals(20L, reduce(foreground(110),
            AppUsageEvent(130, UsageEventKind.SCREEN_OFF),
            AppUsageEvent(170, UsageEventKind.SCREEN_ON)).single().durationMillis)
    }

    @Test
    fun `shutdown prevents overnight foreground carryover`() {
        assertTrue(reduce(foreground(20), AppUsageEvent(50, UsageEventKind.DEVICE_SHUTDOWN),
            AppUsageEvent(120, UsageEventKind.SCREEN_ON)).isEmpty())
    }

    @Test
    fun `startup after same timestamp screen on allows later real foreground use`() {
        val result = reduce(AppUsageEvent(20, UsageEventKind.SCREEN_ON),
            AppUsageEvent(20, UsageEventKind.DEVICE_STARTUP), foreground(110), background(180))
        assertEquals(listOf(AppForegroundDuration("chosen", 70, 180)), result)
    }

    @Test
    fun `startup discards unmatched preboot activity before counting fresh foreground`() {
        val result = reduce(foreground(20, "old-app"), AppUsageEvent(120, UsageEventKind.DEVICE_STARTUP),
            foreground(150), background(170), selected = setOf("chosen", "old-app"))
        assertEquals(listOf(AppForegroundDuration("chosen", 20, 170)), result)
    }

    @Test
    fun `startup without new foreground invents no use`() {
        assertTrue(reduce(foreground(20), AppUsageEvent(120, UsageEventKind.DEVICE_STARTUP),
            AppUsageEvent(150, UsageEventKind.SCREEN_ON)).isEmpty())
    }

    @Test
    fun `shutdown still closes known activity and startup permits fresh use`() {
        val result = reduce(foreground(110), AppUsageEvent(130, UsageEventKind.DEVICE_SHUTDOWN),
            AppUsageEvent(150, UsageEventKind.DEVICE_STARTUP), foreground(170), background(190))
        assertEquals(listOf(AppForegroundDuration("chosen", 40, 190)), result)
    }

    @Test
    fun `new resume after unlock starts a new interval`() {
        assertEquals(50L, reduce(foreground(110), AppUsageEvent(130, UsageEventKind.SCREEN_OFF),
            AppUsageEvent(160, UsageEventKind.SCREEN_ON), foreground(170)).single().durationMillis)
    }

    @Test
    fun `late pause of previous activity cannot close its replacement`() {
        assertEquals(60L, reduce(foreground(110, className = "First"), foreground(130, className = "Second"),
            background(140, className = "First"), background(170, className = "Second")).single().durationMillis)
    }

    @Test
    fun `duplicate resume does not count overlapping time twice`() {
        assertEquals(60L, reduce(foreground(110), foreground(130), background(170)).single().durationMillis)
    }

    @Test
    fun `no chosen apps means no observation entries`() {
        assertTrue(reduce(foreground(110), selected = emptySet()).isEmpty())
    }

    @Test
    fun `multiple sessions aggregate and sort by duration then recency`() {
        val result = reduce(foreground(110, "other"), background(140, "other"), foreground(150), background(170),
            foreground(180), background(190), selected = setOf("chosen", "other"))
        assertEquals(listOf(AppForegroundDuration("chosen", 30, 190), AppForegroundDuration("other", 30, 140)), result)
    }

    @Test
    fun `unknown pause without earlier foreground does not invent use`() {
        assertTrue(reduce(background(150)).isEmpty())
    }
}
