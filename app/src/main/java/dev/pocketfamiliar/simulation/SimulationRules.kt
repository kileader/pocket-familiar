package dev.pocketfamiliar.simulation

/** All v0.1a tuning values live here; phone observations do not affect these rules. */
object SimulationRules {
    const val INITIAL_ENERGY = 80f
    const val INITIAL_STIMULATION = 50f
    const val INITIAL_CURIOSITY = 0.65f
    const val MILLIS_PER_HOUR = 3_600_000L
    const val AWAKE_ENERGY_LOSS_PER_HOUR = 3.0
    const val AWAKE_STIMULATION_LOSS_PER_HOUR = 1.5
    const val STIMULATION_FLOOR = 15f
    const val REST_AT_ENERGY = 20f
    const val RESTING_ENERGY_GAIN_PER_HOUR = 10.0
    const val WAKE_AT_ENERGY = 80f
    const val POKE_STIMULATION_GAIN = 8f

    const val DROWSY_MAX_ENERGY = 35f
    const val RESTLESS_BELOW_STIMULATION = 30f
    const val LIVELY_MIN_ENERGY = 60f
    const val LIVELY_MIN_STIMULATION = 60f
}
