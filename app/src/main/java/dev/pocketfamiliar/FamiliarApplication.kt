package dev.pocketfamiliar

import android.app.Application
import androidx.room.Room
import dev.pocketfamiliar.brain.BrainSettingsStore
import dev.pocketfamiliar.brain.HostedCreatureBrain
import dev.pocketfamiliar.perception.PhonePerception
import dev.pocketfamiliar.persistence.CreatureRepository
import dev.pocketfamiliar.persistence.FamiliarDatabase

class FamiliarApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            FamiliarDatabase::class.java,
            "pocket-familiar.db",
        ).build()
    }

    val repository by lazy { CreatureRepository(database) }
    val perception by lazy { PhonePerception(applicationContext) }
    val brainSettings by lazy { BrainSettingsStore(applicationContext) }
    val brain by lazy { HostedCreatureBrain(brainSettings::read) }
}
