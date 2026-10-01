package dev.pocketfamiliar.persistence

import androidx.room.withTransaction
import dev.pocketfamiliar.simulation.CreatureSimulator
import dev.pocketfamiliar.simulation.CreatureState
import dev.pocketfamiliar.simulation.SimulationUpdate
import java.time.Clock

/** Every canonical read catches up and every write commits before reaching the UI. */
class CreatureRepository(
    private val database: FamiliarDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val simulator: CreatureSimulator = CreatureSimulator(),
) {
    suspend fun refresh(): SimulationUpdate = update(poke = false)

    suspend fun poke(): SimulationUpdate = update(poke = true)

    private suspend fun update(poke: Boolean): SimulationUpdate = database.withTransaction {
        val now = clock.millis()
        val stored = database.creatureDao().get()?.toState() ?: CreatureState.initial(now)
        val advanced = simulator.advance(stored, now)
        val result = if (poke) {
            advanced.copy(state = simulator.poke(advanced.state, now))
        } else {
            advanced
        }
        database.creatureDao().save(CreatureEntity.fromState(result.state))
        result
    }
}
