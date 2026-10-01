package dev.pocketfamiliar.simulation

import kotlin.math.max
import kotlin.math.roundToLong

data class SimulationUpdate(
    val state: CreatureState,
    val elapsedMillis: Long,
)

/** Pure elapsed-time simulation: no clock, Android dependencies, storage, or background ticking. */
class CreatureSimulator {
    fun advance(state: CreatureState, nowMillis: Long): SimulationUpdate {
        require(nowMillis >= 0 && state.lastUpdatedAt >= 0) {
            "Simulation timestamps must be nonnegative epoch milliseconds"
        }
        val effectiveNow = max(nowMillis, state.lastUpdatedAt)
        val elapsedMillis = effectiveNow - state.lastUpdatedAt
        val hourMillis = SimulationRules.MILLIS_PER_HOUR.toDouble()
        val sleepEnergy = SimulationRules.REST_AT_ENERGY.toDouble()
        val wakeEnergy = SimulationRules.WAKE_AT_ENERGY.toDouble()
        val awakeLoss = SimulationRules.AWAKE_ENERGY_LOSS_PER_HOUR
        val restingGain = SimulationRules.RESTING_ENERGY_GAIN_PER_HOUR
        val awakeCycleMillis = (wakeEnergy - sleepEnergy) / awakeLoss * hourMillis
        val restingCycleMillis = (wakeEnergy - sleepEnergy) / restingGain * hourMillis
        // Current rules give an exact integer-millisecond cycle: 20 awake hours + 6 resting hours.
        val cycleMillis = (awakeCycleMillis + restingCycleMillis).roundToLong()

        // Preserve the Long remainder before converting to Double, including very long absences.
        var wholeCycles = elapsedMillis / cycleMillis
        var remainingMillis = (elapsedMillis % cycleMillis).toDouble()
        var awakeMillis = 0.0
        var energy = state.energy.toDouble()
        var mode = when {
            state.mode == CreatureMode.AWAKE && energy <= sleepEnergy -> CreatureMode.RESTING
            state.mode == CreatureMode.RESTING && energy >= wakeEnergy -> CreatureMode.AWAKE
            else -> state.mode
        }

        // Imported states can start outside the ordinary 20..80 cycle. Handle that short prefix.
        val prefixTarget = when {
            mode == CreatureMode.AWAKE && energy > wakeEnergy -> wakeEnergy
            mode == CreatureMode.RESTING && energy < sleepEnergy -> sleepEnergy
            else -> energy
        }
        if (prefixTarget != energy) {
            val rate = if (mode == CreatureMode.AWAKE) awakeLoss else restingGain
            val prefixMillis = kotlin.math.abs(prefixTarget - energy) / rate * hourMillis
            if (wholeCycles == 0L && remainingMillis < prefixMillis) {
                if (mode == CreatureMode.AWAKE) {
                    energy -= remainingMillis / hourMillis * awakeLoss
                    awakeMillis += remainingMillis
                } else {
                    energy += remainingMillis / hourMillis * restingGain
                }
                remainingMillis = 0.0
            } else {
                if (mode == CreatureMode.AWAKE) awakeMillis += prefixMillis
                energy = prefixTarget
                remainingMillis -= prefixMillis
                if (remainingMillis < 0.0) {
                    wholeCycles--
                    remainingMillis += cycleMillis.toDouble()
                }
            }
        }

        // Any point inside the cycle returns to the same energy and mode after a full cycle.
        awakeMillis += wholeCycles.toDouble() * awakeCycleMillis
        while (remainingMillis > 0.0) {
            val isAwake = mode == CreatureMode.AWAKE
            val threshold = if (isAwake) sleepEnergy else wakeEnergy
            val rate = if (isAwake) awakeLoss else restingGain
            val untilTransition = kotlin.math.abs(energy - threshold) / rate * hourMillis
            if (remainingMillis >= untilTransition) {
                if (isAwake) awakeMillis += untilTransition
                remainingMillis = max(0.0, remainingMillis - untilTransition)
                energy = threshold
                mode = if (isAwake) CreatureMode.RESTING else CreatureMode.AWAKE
            } else {
                val change = remainingMillis / hourMillis * rate
                energy += if (isAwake) -change else change
                if (isAwake) awakeMillis += remainingMillis
                remainingMillis = 0.0
            }
        }

        val stimulation = max(
            SimulationRules.STIMULATION_FLOOR.toDouble(),
            state.stimulation.toDouble() -
                awakeMillis / hourMillis * SimulationRules.AWAKE_STIMULATION_LOSS_PER_HOUR,
        )
        return SimulationUpdate(
            state = state.copy(
                energy = energy.coerceIn(0.0, 100.0).toFloat(),
                stimulation = stimulation.coerceIn(0.0, 100.0).toFloat(),
                mode = mode,
                lastUpdatedAt = effectiveNow,
            ),
            elapsedMillis = elapsedMillis,
        )
    }

    /** Call after advancing to now, in the same persistence transaction. A poke never adds energy. */
    fun poke(state: CreatureState, nowMillis: Long): CreatureState = state.copy(
        stimulation = (state.stimulation + SimulationRules.POKE_STIMULATION_GAIN).coerceAtMost(100f),
        lastInteractionAt = max(nowMillis, state.lastUpdatedAt),
        interactionCount = state.interactionCount + 1,
    )
}
