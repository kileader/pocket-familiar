package dev.pocketfamiliar.ui

import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class PhoneReactionTest {
    private fun environment(percent: Int? = 50, charging: Boolean? = false, night: Boolean = false) =
        EnvironmentSnapshot(0, if (night) LocalTime.of(23, 0) else LocalTime.NOON,
            if (night) TimeOfDay.NIGHT else TimeOfDay.AFTERNOON, percent, charging)

    @Test fun `charging takes priority even at low battery and at night`() {
        assertEquals(PhoneReaction.CHARGING, phoneReaction(environment(5, true, true)))
        assertEquals(PhoneReaction.CHARGING, phoneReaction(environment(100, true)))
        assertEquals(PhoneReaction.CHARGING, phoneReaction(environment(null, true)))
    }

    @Test fun `low battery requires a known noncharging reading at or below twenty percent`() {
        for (percent in listOf(0, 1, 19, 20)) {
            assertEquals(PhoneReaction.LOW_BATTERY, phoneReaction(environment(percent)))
        }
        assertNull(phoneReaction(environment(21)))
        assertNull(phoneReaction(environment(null)))
        assertNull(phoneReaction(environment(10, null)))
        assertNull(phoneReaction(environment(-1)))
    }

    @Test fun `low battery takes priority over nighttime`() {
        assertEquals(PhoneReaction.LOW_BATTERY, phoneReaction(environment(20, false, true)))
        assertEquals(PhoneReaction.NIGHT, phoneReaction(environment(21, false, true)))
    }

    @Test fun `night needs only known time and does not infer battery state`() {
        assertEquals(PhoneReaction.NIGHT, phoneReaction(environment(null, null, true)))
        assertNull(phoneReaction(null))
    }

    @Test fun `ordinary daytime has no extra reaction`() {
        assertNull(phoneReaction(environment()))
    }
}
