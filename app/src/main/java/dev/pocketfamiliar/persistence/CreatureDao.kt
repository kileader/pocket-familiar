package dev.pocketfamiliar.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface CreatureDao {
    @Query("SELECT * FROM creature WHERE id = 1")
    suspend fun get(): CreatureEntity?

    @Upsert
    suspend fun save(creature: CreatureEntity)
}
