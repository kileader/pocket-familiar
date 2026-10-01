package dev.pocketfamiliar.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketfamiliar.simulation.CreatureMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class CreatureRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "creature-repository-test.db"
    private lateinit var database: FamiliarDatabase
    private val clock = MutableClock(1_800_000_000_000)

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
        database = openDatabase()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun pokeSurvivesDatabaseCloseAndReopenThenCatchesUp() = runTest {
        val repository = CreatureRepository(database, clock)
        val initial = repository.refresh().state
        val poked = repository.poke().state
        assertEquals(initial.energy, poked.energy, 0f)
        assertEquals(initial.stimulation + 8f, poked.stimulation, 0f)
        assertEquals(1L, poked.interactionCount)
        assertEquals(clock.millis(), poked.lastInteractionAt)

        database.close()
        database = openDatabase()
        assertEquals(poked, CreatureRepository(database, clock).refresh().state)

        clock.now += 21 * HOUR
        val afterAbsence = CreatureRepository(database, clock).refresh()
        assertEquals(21 * HOUR, afterAbsence.elapsedMillis)
        assertEquals(CreatureMode.RESTING, afterAbsence.state.mode)
        assertEquals(30f, afterAbsence.state.energy, 0.0001f)
        assertEquals(initial.createdAt, afterAbsence.state.createdAt)
        assertEquals(poked.lastInteractionAt, afterAbsence.state.lastInteractionAt)
        assertEquals(1L, afterAbsence.state.interactionCount)
        assertNotNull(database.creatureDao().get())
    }

    @Test fun concurrentPokesDoNotLoseInteractions() = runTest {
        val repository = CreatureRepository(database, clock)
        repository.refresh()
        List(20) { async { repository.poke() } }.awaitAll()
        val state = repository.refresh().state
        assertEquals(20L, state.interactionCount)
        assertEquals(100f, state.stimulation, 0f)
        assertEquals(80f, state.energy, 0f)
    }

    @Test fun pokeAfterOfflineSleepTransitionDoesNotWakeCreature() = runTest {
        val repository = CreatureRepository(database, clock)
        repository.refresh()
        clock.now += 20 * HOUR
        val update = repository.poke()
        assertEquals(CreatureMode.RESTING, update.state.mode)
        assertEquals(20f, update.state.energy, 0f)
        assertEquals(28f, update.state.stimulation, 0.0001f)
        assertEquals(1L, update.state.interactionCount)
    }

    private fun openDatabase() = Room.databaseBuilder(
        context, FamiliarDatabase::class.java, databaseName,
    ).build()

    private class MutableClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
    }

    companion object { private const val HOUR = 3_600_000L }
}
