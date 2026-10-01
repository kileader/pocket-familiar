package dev.pocketfamiliar.simulation

import org.junit.Assert.assertThrows
import org.junit.Test

class CreatureStateTest {
    private val initial = CreatureState.initial(100)

    @Test
    fun `nonfinite and out of range canonical values are rejected`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 101f)) {
            assertThrows(IllegalArgumentException::class.java) { initial.copy(energy = value) }
            assertThrows(IllegalArgumentException::class.java) { initial.copy(stimulation = value) }
        }
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -0.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { initial.copy(curiosity = value) }
        }
    }

    @Test
    fun `invalid timestamps and interaction counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CreatureState.initial(-1) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(lastUpdatedAt = 99) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(lastInteractionAt = 99) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(lastInteractionAt = 101) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(interactionCount = -1) }
    }
}
