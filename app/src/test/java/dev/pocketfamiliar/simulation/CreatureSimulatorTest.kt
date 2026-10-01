package dev.pocketfamiliar.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatureSimulatorTest {
    private val simulator = CreatureSimulator()
    private val hour = SimulationRules.MILLIS_PER_HOUR
    private val initial = CreatureState.initial(0)

    @Test
    fun `zero elapsed preserves initial state`() {
        assertEquals(SimulationUpdate(initial, 0), simulator.advance(initial, 0))
    }

    @Test
    fun `awake state loses energy and stimulation with real elapsed time`() {
        val update = simulator.advance(initial, 2 * hour)

        assertEquals(74f, update.state.energy, 0.0001f)
        assertEquals(47f, update.state.stimulation, 0.0001f)
        assertEquals(CreatureMode.AWAKE, update.state.mode)
        assertEquals(2 * hour, update.elapsedMillis)
        assertEquals(2 * hour, update.state.lastUpdatedAt)
        assertEquals(initial.createdAt, update.state.createdAt)
        assertEquals(initial.curiosity, update.state.curiosity)
    }

    @Test
    fun `energy threshold enters resting at the exact boundary`() {
        val state = simulator.advance(initial, 20 * hour).state

        assertEquals(20f, state.energy, 0f)
        assertEquals(20f, state.stimulation, 0f)
        assertEquals(CreatureMode.RESTING, state.mode)
    }

    @Test
    fun `resting recovers energy and holds stimulation`() {
        val resting = initial.copy(energy = 20f, stimulation = 40f, mode = CreatureMode.RESTING)
        val state = simulator.advance(resting, 3 * hour).state

        assertEquals(50f, state.energy, 0f)
        assertEquals(40f, state.stimulation, 0f)
        assertEquals(CreatureMode.RESTING, state.mode)
    }

    @Test
    fun `recovery wakes at the exact boundary`() {
        val state = simulator.advance(initial, 26 * hour).state

        assertEquals(80f, state.energy, 0f)
        assertEquals(20f, state.stimulation, 0f)
        assertEquals(CreatureMode.AWAKE, state.mode)
    }

    @Test
    fun `one update processes resting and waking before remaining awake time`() {
        val state = simulator.advance(initial.copy(energy = 25f), 8 * hour).state

        assertEquals(79f, state.energy, 0.0001f)
        assertEquals(47f, state.stimulation, 0.0001f)
        assertEquals(CreatureMode.AWAKE, state.mode)
    }

    @Test
    fun `many cycles keep state bounded and stimulation at its floor`() {
        val state = simulator.advance(initial, 26 * hour * 1_000_000 + hour).state

        assertEquals(77f, state.energy, 0f)
        assertEquals(15f, state.stimulation, 0f)
        assertEquals(CreatureMode.AWAKE, state.mode)
    }

    @Test(timeout = 1000)
    fun `enormous elapsed interval preserves the exact millisecond remainder`() {
        val elapsed = 26 * hour * 90_000_000_000L + hour + 17
        val update = simulator.advance(initial, elapsed)
        val expectedEnergy = (80.0 - (hour + 17).toDouble() / hour * 3.0).toFloat()

        assertEquals(expectedEnergy, update.state.energy, 0f)
        assertEquals(15f, update.state.stimulation, 0f)
        assertEquals(CreatureMode.AWAKE, update.state.mode)
        assertEquals(elapsed, update.elapsedMillis)
    }

    @Test
    fun `complete cycles from a partial resting phase preserve that phase`() {
        val resting = initial.copy(energy = 53f, stimulation = 90f, mode = CreatureMode.RESTING)
        val state = simulator.advance(resting, 26 * hour * 2).state

        assertEquals(53f, state.energy, 0f)
        assertEquals(30f, state.stimulation, 0f)
        assertEquals(CreatureMode.RESTING, state.mode)
    }

    @Test
    fun `segmented and single elapsed updates agree within storage precision`() {
        val source = initial.copy(energy = 63.25f, stimulation = 92f)
        val total = 113 * hour + 123_456L
        val single = simulator.advance(source, total).state
        var segmented = source
        var now = 0L
        for (segment in listOf(2 * hour + 517, 19 * hour, 36 * hour + 24_811, 7 * hour)) {
            now += segment
            segmented = simulator.advance(segmented, now).state
        }
        segmented = simulator.advance(segmented, total).state

        assertEquals(single.energy, segmented.energy, 0.0001f)
        assertEquals(single.stimulation, segmented.stimulation, 0.0001f)
        assertEquals(single.mode, segmented.mode)
        assertEquals(single.lastUpdatedAt, segmented.lastUpdatedAt)
    }

    @Test
    fun `backward clock preserves timestamp and does not replay elapsed time`() {
        val advanced = simulator.advance(initial, 4 * hour).state
        val backward = simulator.advance(advanced, hour)
        val caughtUp = simulator.advance(backward.state, 4 * hour)
        val later = simulator.advance(caughtUp.state, 5 * hour)

        assertEquals(SimulationUpdate(advanced, 0), backward)
        assertEquals(SimulationUpdate(advanced, 0), caughtUp)
        assertEquals(65f, later.state.energy, 0f)
        assertEquals(hour, later.elapsedMillis)
    }

    @Test
    fun `poke changes only interaction and stimulation state while resting`() {
        val resting = initial.copy(energy = 30f, mode = CreatureMode.RESTING, lastUpdatedAt = hour)
        val poked = simulator.poke(resting, hour)

        assertEquals(resting.copy(stimulation = 58f, lastInteractionAt = hour, interactionCount = 1), poked)
    }

    @Test
    fun `poke clamps stimulation and uses logical time during clock rollback`() {
        val state = initial.copy(stimulation = 98f, lastUpdatedAt = 3 * hour, interactionCount = 9)
        val poked = simulator.poke(state, hour)

        assertEquals(100f, poked.stimulation, 0f)
        assertEquals(3 * hour, poked.lastInteractionAt)
        assertEquals(10L, poked.interactionCount)
        assertEquals(state.energy, poked.energy, 0f)
    }

    @Test
    fun `states outside the normal cycle process their initial prefix`() {
        val aboveWakeThreshold = initial.copy(energy = 100f)
        val afterFullCycleAndPrefix = simulator.advance(aboveWakeThreshold, 26 * hour + 24_000_000).state
        assertEquals(80f, afterFullCycleAndPrefix.energy, 0.0001f)
        assertEquals(CreatureMode.AWAKE, afterFullCycleAndPrefix.mode)

        val belowSleepThreshold = initial.copy(energy = 10f)
        val afterRecoveryAndCycle = simulator.advance(belowSleepThreshold, 27 * hour).state
        assertEquals(20f, afterRecoveryAndCycle.energy, 0.0001f)
        assertEquals(CreatureMode.RESTING, afterRecoveryAndCycle.mode)
        assertTrue(afterRecoveryAndCycle.stimulation >= SimulationRules.STIMULATION_FLOOR)
    }
}
