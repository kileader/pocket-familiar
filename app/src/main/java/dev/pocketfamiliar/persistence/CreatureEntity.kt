package dev.pocketfamiliar.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import dev.pocketfamiliar.simulation.CreatureMode
import dev.pocketfamiliar.simulation.CreatureState

/** The only canonical row. Behavioral labels and observations are deliberately absent. */
@Entity(tableName = "creature")
data class CreatureEntity(
    @PrimaryKey val id: Int = SINGLE_CREATURE_ID,
    val energy: Float,
    val stimulation: Float,
    val mode: CreatureMode,
    val curiosity: Float,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val lastInteractionAt: Long?,
    val interactionCount: Long,
) {
    fun toState() = CreatureState(
        energy = energy,
        stimulation = stimulation,
        mode = mode,
        curiosity = curiosity,
        createdAt = createdAt,
        lastUpdatedAt = lastUpdatedAt,
        lastInteractionAt = lastInteractionAt,
        interactionCount = interactionCount,
    )

    companion object {
        const val SINGLE_CREATURE_ID = 1

        fun fromState(state: CreatureState) = CreatureEntity(
            energy = state.energy,
            stimulation = state.stimulation,
            mode = state.mode,
            curiosity = state.curiosity,
            createdAt = state.createdAt,
            lastUpdatedAt = state.lastUpdatedAt,
            lastInteractionAt = state.lastInteractionAt,
            interactionCount = state.interactionCount,
        )
    }
}

class CreatureConverters {
    @TypeConverter fun modeToString(mode: CreatureMode): String = mode.name
    @TypeConverter fun stringToMode(value: String): CreatureMode = CreatureMode.valueOf(value)
}
