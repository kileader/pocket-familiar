package dev.pocketfamiliar.brain

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketfamiliar.perception.PhonePerception
import dev.pocketfamiliar.persistence.CreatureRepository
import dev.pocketfamiliar.persistence.FamiliarDatabase
import dev.pocketfamiliar.ui.FamiliarViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VoiceIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: FamiliarDatabase
    private lateinit var repository: CreatureRepository
    private lateinit var settings: BrainSettingsStore
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(context, FamiliarDatabase::class.java).build()
        repository = CreatureRepository(database, Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000), ZoneOffset.UTC))
        settings = BrainSettingsStore(context)
        settings.save(BrainSettings("https://example.com", "test-token-" + "a".repeat(32)))
    }

    @After fun tearDown() {
        viewModels.clear()
        database.close()
        context.getSharedPreferences("brain-settings", 0).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun model(brain: CreatureBrain) = FamiliarViewModel(repository, PhonePerception(context), brain, settings)
        .also { viewModels.put("test", it) }

    @Test fun listenDoesNotChangeCanonicalState() = runBlocking {
        val before = repository.refresh().state
        val vm = model(object : CreatureBrain {
            override suspend fun think(context: CreatureContext): BrainResponse {
                assertEquals(before, context.creature)
                return BrainResponse("I’m watching the evening settle.")
            }
        })
        vm.listen()
        val result = withTimeout(5000) { vm.uiState.first { !it.isThinking && it.thought != null } }
        assertNotNull(result.thought)
        assertEquals(before, repository.refresh().state)
    }

    @Test fun pokePersistsDuringSlowThinkAndDiscardsStaleWords() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<BrainResponse>()
        val vm = model(object : CreatureBrain {
            override suspend fun think(context: CreatureContext): BrainResponse {
                started.complete(Unit)
                return answer.await()
            }
        })
        vm.listen()
        withTimeout(5000) { started.await() }
        vm.poke()
        val poked = withTimeout(5000) { vm.uiState.first { it.update?.state?.interactionCount == 1L } }
        assertTrue(poked.isThinking)
        assertEquals(80f, poked.update!!.state.energy, 0f)
        answer.complete(BrainResponse("An old observation."))
        val result = withTimeout(5000) { vm.uiState.first { !it.isThinking } }
        assertNull(result.thought)
        assertEquals(1L, repository.refresh().state.interactionCount)
    }

    @Test fun voiceFailureLeavesCreatureUsable() = runBlocking {
        val vm = model(object : CreatureBrain {
            override suspend fun think(context: CreatureContext): BrainResponse = error("Unavailable")
        })
        vm.listen()
        val failed = withTimeout(5000) { vm.uiState.first { !it.isThinking && it.voiceError != null } }
        assertNotNull(failed.update)
        vm.poke()
        withTimeout(5000) { vm.uiState.first { it.update?.state?.interactionCount == 1L } }
        assertEquals(1L, repository.refresh().state.interactionCount)
    }
}
