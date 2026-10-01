package dev.pocketfamiliar.simulation

enum class CreatureMode {
    AWAKE,
    RESTING,
}

/** The creature's model-independent, canonical state. Timestamps are epoch milliseconds. */
data class CreatureState(
    val energy: Float,
    val stimulation: Float,
    val mode: CreatureMode,
    val curiosity: Float,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val lastInteractionAt: Long?,
    val interactionCount: Long,
) {
    init {
        require(energy.isFinite() && energy in 0f..100f) { "Energy must be finite and within 0..100" }
        require(stimulation.isFinite() && stimulation in 0f..100f) {
            "Stimulation must be finite and within 0..100"
        }
        require(curiosity.isFinite() && curiosity in 0f..1f) { "Curiosity must be finite and within 0..1" }
        require(createdAt >= 0 && lastUpdatedAt >= createdAt) { "Creature timestamps must be ordered and nonnegative" }
        require(lastInteractionAt == null || lastInteractionAt in createdAt..lastUpdatedAt) {
            "Interaction time must fall between creation and the latest update"
        }
        require(interactionCount >= 0) { "Interaction count must be nonnegative" }
    }

    companion object {
        fun initial(nowMillis: Long): CreatureState = CreatureState(
            energy = SimulationRules.INITIAL_ENERGY,
            stimulation = SimulationRules.INITIAL_STIMULATION,
            mode = CreatureMode.AWAKE,
            curiosity = SimulationRules.INITIAL_CURIOSITY,
            createdAt = nowMillis,
            lastUpdatedAt = nowMillis,
            lastInteractionAt = null,
            interactionCount = 0,
        )
    }
}
