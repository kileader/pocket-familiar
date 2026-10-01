package dev.pocketfamiliar.simulation

enum class Behavior {
    RESTING,
    DROWSY,
    RESTLESS,
    WATCHFUL,
    LIVELY,
}

/** Labels are observations of canonical state, never another persisted state machine. */
fun deriveBehavior(state: CreatureState): Behavior = when {
    state.mode == CreatureMode.RESTING -> Behavior.RESTING
    state.energy <= SimulationRules.DROWSY_MAX_ENERGY -> Behavior.DROWSY
    state.stimulation < SimulationRules.RESTLESS_BELOW_STIMULATION -> Behavior.RESTLESS
    state.energy >= SimulationRules.LIVELY_MIN_ENERGY &&
        state.stimulation >= SimulationRules.LIVELY_MIN_STIMULATION -> Behavior.LIVELY
    else -> Behavior.WATCHFUL
}
