package dev.pocketfamiliar.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [CreatureEntity::class], version = 1, exportSchema = true)
@TypeConverters(CreatureConverters::class)
abstract class FamiliarDatabase : RoomDatabase() {
    internal abstract fun creatureDao(): CreatureDao
}
