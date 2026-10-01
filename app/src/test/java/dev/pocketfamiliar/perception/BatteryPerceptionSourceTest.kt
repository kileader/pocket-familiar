package dev.pocketfamiliar.perception

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPerceptionSourceTest {
    @Test
    fun `percentage respects battery scale rather than assuming 100`() {
        assertEquals(50, batteryObservation(1, 2, BatteryManager.BATTERY_STATUS_DISCHARGING).percent)
        assertEquals(67, batteryObservation(2, 3, BatteryManager.BATTERY_STATUS_DISCHARGING).percent)
    }

    @Test
    fun `missing or invalid battery levels stay unknown`() {
        listOf(-1 to 100, 50 to 0, 101 to 100).forEach { (level, scale) ->
            assertNull(batteryObservation(level, scale, -1).percent)
        }
    }

    @Test
    fun `charging and full statuses both count as charging`() {
        assertEquals(true, batteryObservation(50, 100, BatteryManager.BATTERY_STATUS_CHARGING).isCharging)
        assertEquals(true, batteryObservation(100, 100, BatteryManager.BATTERY_STATUS_FULL).isCharging)
        assertEquals(false, batteryObservation(50, 100, BatteryManager.BATTERY_STATUS_DISCHARGING).isCharging)
        assertEquals(false, batteryObservation(50, 100, BatteryManager.BATTERY_STATUS_NOT_CHARGING).isCharging)
        assertNull(batteryObservation(50, 100, BatteryManager.BATTERY_STATUS_UNKNOWN).isCharging)
    }
}
