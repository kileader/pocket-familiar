package dev.pocketfamiliar.perception

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TimePerceptionSourceTest {
    @Test
    fun `coarse time changes at the intended boundaries`() {
        val cases = listOf(
            "00:00" to TimeOfDay.NIGHT,
            "05:59" to TimeOfDay.NIGHT,
            "06:00" to TimeOfDay.MORNING,
            "11:59" to TimeOfDay.MORNING,
            "12:00" to TimeOfDay.AFTERNOON,
            "17:59" to TimeOfDay.AFTERNOON,
            "18:00" to TimeOfDay.EVENING,
            "21:59" to TimeOfDay.EVENING,
            "22:00" to TimeOfDay.NIGHT,
            "23:59" to TimeOfDay.NIGHT,
        )
        cases.forEach { (time, expected) ->
            assertEquals(time, expected, timeOfDayAt(LocalTime.parse(time)))
        }
    }

    @Test
    fun `observation uses local clock zone while retaining epoch timestamp`() {
        val instant = Instant.parse("2026-10-01T01:30:00Z")
        val source = TimePerceptionSource(Clock.fixed(instant, ZoneId.of("America/New_York")))
        val observation = source.observe()

        assertEquals(instant.toEpochMilli(), observation.observedAtMillis)
        assertEquals(LocalTime.of(21, 30), observation.localTime)
        assertEquals(TimeOfDay.EVENING, observation.timeOfDay)
    }

    @Test
    fun `default source follows timezone changes between observations`() {
        val originalZone = TimeZone.getDefault()
        val source = TimePerceptionSource()
        try {
            listOf("Asia/Tokyo", "America/New_York").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val observation = source.observe()
                val expectedLocalTime = Instant.ofEpochMilli(observation.observedAtMillis)
                    .atZone(ZoneId.of(zone))
                    .toLocalTime()
                // Epoch milliseconds intentionally omit any sub-millisecond clock precision.
                assertEquals(expectedLocalTime, observation.localTime.withNano(observation.localTime.nano / 1_000_000 * 1_000_000))
            }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }
}
