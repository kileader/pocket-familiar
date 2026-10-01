package dev.pocketfamiliar.simulation

import org.junit.Assert.assertEquals
import org.junit.Test

class BehaviorTest {
    private val initial = CreatureState.initial(0)

    @Test
    fun `resting has priority over all awake behaviors`() {
        assertEquals(Behavior.RESTING, deriveBehavior(initial.copy(mode = CreatureMode.RESTING)))
    }

    @Test
    fun `drowsy has priority over low stimulation`() {
        assertEquals(Behavior.DROWSY, deriveBehavior(initial.copy(energy = 35f, stimulation = 15f)))
    }

    @Test
    fun `restless uses the strict stimulation threshold`() {
        assertEquals(Behavior.RESTLESS, deriveBehavior(initial.copy(stimulation = 29.9f)))
        assertEquals(Behavior.WATCHFUL, deriveBehavior(initial.copy(stimulation = 30f)))
    }

    @Test
    fun `lively requires both energy and stimulation thresholds`() {
        assertEquals(Behavior.LIVELY, deriveBehavior(initial.copy(energy = 60f, stimulation = 60f)))
        assertEquals(Behavior.WATCHFUL, deriveBehavior(initial.copy(energy = 59.9f, stimulation = 60f)))
        assertEquals(Behavior.WATCHFUL, deriveBehavior(initial.copy(energy = 60f, stimulation = 59.9f)))
    }

    @Test
    fun `curiosity is retained without affecting v01a behavior`() {
        assertEquals(deriveBehavior(initial.copy(curiosity = 0f)), deriveBehavior(initial.copy(curiosity = 1f)))
    }
}
