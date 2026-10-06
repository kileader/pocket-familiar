package dev.pocketfamiliar.ui

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketfamiliar.brain.BrainResponse
import dev.pocketfamiliar.brain.BrainSettingsStore
import dev.pocketfamiliar.brain.CreatureBrain
import dev.pocketfamiliar.brain.CreatureContext
import dev.pocketfamiliar.brain.DiscoveryHistoryStore
import dev.pocketfamiliar.perception.PhonePerception
import dev.pocketfamiliar.persistence.CreatureEntity
import dev.pocketfamiliar.persistence.CreatureRepository
import dev.pocketfamiliar.persistence.FamiliarDatabase
import dev.pocketfamiliar.simulation.Behavior
import dev.pocketfamiliar.simulation.CreatureMode
import dev.pocketfamiliar.simulation.CreatureState
import dev.pocketfamiliar.simulation.deriveBehavior
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Changes emulator battery service readings, exercising Android's protected broadcasts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PhoneReactionIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var database: FamiliarDatabase
    private val viewModels = ViewModelStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        assumeTrue("Battery overrides are restricted to emulators", Build.MODEL.contains("sdk", ignoreCase = true))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(context, FamiliarDatabase::class.java).build()
        shell("dumpsys battery set level 50")
        shell("dumpsys battery set status 3")
    }

    @After fun tearDown() {
        if (!::database.isInitialized) return
        viewModels.clear()
        database.close()
        shell("dumpsys battery reset")
        Dispatchers.resetMain()
    }

    private fun model(brain: CreatureBrain) = FamiliarViewModel(
        CreatureRepository(database, clock), PhonePerception(context, clock), brain,
        BrainSettingsStore(context), DiscoveryHistoryStore(context, "phone-reaction-test-history"),
    ).also { viewModels.put("test", it) }

    @Test fun liveBatteryChangesPreserveCanonAndAnOngoingListen() = runBlocking {
        val creature = CreatureState.initial(clock.millis()).copy(mode = CreatureMode.RESTING, energy = 45f)
        database.creatureDao().save(CreatureEntity.fromState(creature))
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<BrainResponse>()
        var requests = 0
        val vm = model(object : CreatureBrain {
            override suspend fun think(context: CreatureContext): BrainResponse {
                requests++
                started.complete(Unit)
                return answer.await()
            }
        })
        val settings = BrainSettingsStore(context)
        val originalSettings = settings.read()
        vm.saveBrainSettings("https://example.com", "test-token-" + "a".repeat(32))
        val observation = launch { vm.observePhoneStateChanges() }
        try {
            vm.listen()
            withTimeout(5000) { started.await() }
            val before = vm.uiState.value
            shell("dumpsys battery set level 15")
            val low = withTimeout(5000) { vm.uiState.first { phoneReaction(it.environment) == PhoneReaction.LOW_BATTERY } }
            assertEquals(before.update, low.update)
            assertEquals(before.pokeReaction, low.pokeReaction)
            assertEquals(Behavior.RESTING, deriveBehavior(low.update!!.state))
            shell("dumpsys battery set status 2")
            withTimeout(5000) { vm.uiState.first { phoneReaction(it.environment) == PhoneReaction.CHARGING } }
            answer.complete(BrainResponse("A thought from before the charger arrived."))
            val spoken = withTimeout(5000) { vm.uiState.first { it.thought != null && !it.isThinking } }
            assertEquals("A thought from before the charger arrived.", spoken.thought)
            shell("dumpsys battery set level 80")
            shell("dumpsys battery set status 3")
            val unplugged = withTimeout(5000) { vm.uiState.first { it.environment?.batteryPercent == 80 && it.environment.isCharging == false } }
            assertNull(phoneReaction(unplugged.environment))
            assertEquals(spoken.thought, unplugged.thought)
            assertEquals(creature, database.creatureDao().get()!!.toState())
            assertEquals(1, requests)
        } finally {
            observation.cancelAndJoin()
            if (originalSettings.configured) settings.save(originalSettings)
            else context.getSharedPreferences("brain-settings", 0).edit().clear().commit()
        }
    }

    @Test fun cancelledObservationStopsUpdatesAndRestartReadsCurrentBattery() = runBlocking {
        val vm = model(object : CreatureBrain {
            override suspend fun think(context: CreatureContext): BrainResponse = error("No voice request expected")
        })
        var observation = launch { vm.observePhoneStateChanges() }
        try {
            withTimeout(5000) { vm.uiState.first { it.environment?.batteryPercent == 50 } }
            observation.cancelAndJoin()
            shell("dumpsys battery set level 10")
            delay(300)
            assertEquals(50, vm.uiState.value.environment!!.batteryPercent)
            observation = launch { vm.observePhoneStateChanges() }
            withTimeout(5000) { vm.uiState.first { it.environment?.batteryPercent == 10 } }
            assertNull(vm.uiState.value.update)
            assertNull(database.creatureDao().get())
            assertEquals(0L, vm.uiState.value.pokeReaction)
        } finally {
            observation.cancelAndJoin()
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
