package dev.pocketfamiliar.roam

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketfamiliar.brain.BrainSettings
import dev.pocketfamiliar.brain.BrainSettingsStore
import dev.pocketfamiliar.ui.FreeRoamViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real preferences/main-thread ViewModel, fake server: no model calls or body writes. */
@RunWith(AndroidJUnit4::class)
class FreeRoamIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val config = BrainSettings("https://example.com", "fake-token-" + "x".repeat(32))
    private lateinit var settings: BrainSettingsStore
    private var previousEndpoint: String? = null
    private var previousToken: String? = null
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var preferenceName: String
    private lateinit var bindings: SharedRoamBindingStore

    private class Server : FreeRoamClient {
        var state = RoamFixtures.state()
        var page = RoamFixtures.journal()
        var snapshotHook: (suspend (BrainSettings) -> RoamSnapshot)? = null
        var losePairResponse = false
        var rejectControls = false
        var rejectReads = false
        var pairs = 0
        var controlWrites = 0
        var journalReads = 0
        override suspend fun snapshot(settings: BrainSettings): RoamSnapshot {
            snapshotHook?.let { return it(settings) }
            if (rejectReads) throw RoamApiException("Offline")
            return state
        }
        override suspend fun journal(settings: BrainSettings, before: Long): RoamJournalPage {
            journalReads++
            return page.copy(items = page.items.filter { it.sequence < before })
        }
        override suspend fun pair(settings: BrainSettings, identity: RoamIdentity, pairingId: String): RoamSnapshot {
            pairs++
            state = state.copy(profile = RoamProfile(RoamFixtures.FAMILIAR, pairingId, identity.createdAt, identity.curiosity))
            if (losePairResponse) throw RoamApiException("Response lost")
            return state
        }
        override suspend fun controls(settings: BrainSettings, revision: Long, controls: RoamControls) {
            controlWrites++
            if (rejectControls) {
                state = state.copy(revision = 8, controls = state.controls.copy(dailyWakeLimit = 2))
                page = page.copy(revision = 8)
                throw RoamApiException("Server changed", conflict = true)
            }
            assertEquals(state.revision, revision)
            state = state.copy(revision = revision + 1, controls = controls)
            page = page.copy(revision = state.revision)
        }
        override suspend fun forget(settings: BrainSettings, revision: Long) {
            state = state.copy(revision = revision + 1, controls = RoamControls())
            page = RoamJournalPage(state.revision, emptyList(), null)
            rejectReads = true // Mutation succeeded, but the following GET fails.
        }
        override suspend fun deleteEntry(settings: BrainSettings, revision: Long, id: String) {
            page = page.copy(items = page.items.filterNot { it.id == id })
        }
    }

    @Before fun setUp() {
        val preferences = context.getSharedPreferences("brain-settings", Context.MODE_PRIVATE)
        previousEndpoint = preferences.getString("endpoint", null)
        previousToken = preferences.getString("access-token", null)
        settings = BrainSettingsStore(context)
        settings.save(config)
        preferenceName = "roam-test-" + UUID.randomUUID()
        bindings = SharedRoamBindingStore(context, preferenceName)
    }

    @After fun tearDown() {
        main { stores.forEach { it.clear() } }
        context.getSharedPreferences("brain-settings", Context.MODE_PRIVATE).edit()
            .putString("endpoint", previousEndpoint).putString("access-token", previousToken).commit()
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun model(server: Server): FreeRoamViewModel {
        lateinit var model: FreeRoamViewModel
        main {
            val store = ViewModelStore().also(stores::add)
            model = ViewModelProvider(store, FreeRoamViewModel.Factory(settings, bindings, server))[FreeRoamViewModel::class.java]
            model.open(RoamFixtures.identity)
        }
        await { !model.uiState.value.busy }
        return model
    }
    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; SystemClock.sleep(50) }
        error("ViewModel did not finish")
    }
    private fun connected(server: Server): FreeRoamViewModel {
        bindings.save(config.endpoint, RoamFixtures.identity.createdAt, RoamBinding(RoamFixtures.PAIR, RoamFixtures.FAMILIAR))
        return model(server).also { assertTrue(it.uiState.value.connected) }
    }

    @Test fun lostPairResponseRecoversPersistedUuidAfterViewModelRecreation() {
        val server = Server().apply { state = state.copy(profile = null); losePairResponse = true }
        val first = model(server)
        assertFalse(first.uiState.value.connected)
        main { first.connect() }
        await { !first.uiState.value.busy }
        val pending = SharedRoamBindingStore(context, preferenceName).read(config.endpoint, RoamFixtures.identity.createdAt)!!
        assertEquals(server.state.profile!!.pairingId, pending.pairingId)
        assertNull(pending.familiarId)
        val recovered = model(server)
        assertTrue(recovered.uiState.value.connected)
        assertEquals(RoamFixtures.FAMILIAR, bindings.read(config.endpoint, RoamFixtures.identity.createdAt)!!.familiarId)
        assertEquals(1, server.pairs)
        assertNull(bindings.read("https://different.example.com", RoamFixtures.identity.createdAt))
        assertNull(bindings.read(config.endpoint, RoamFixtures.identity.createdAt + 1))
    }

    @Test fun missingOrDifferentKnownIdentityCannotBeRepairedByReplacingIt() {
        val server = Server()
        val model = connected(server)
        server.state = server.state.copy(profile = null)
        main { model.refresh() }
        await { !model.uiState.value.busy }
        assertTrue(model.uiState.value.pairingBlocked)
        main { model.connect() }
        await { !model.uiState.value.busy }
        assertEquals(0, server.pairs)
        server.state = RoamFixtures.state().copy(profile = RoamFixtures.state().profile!!.copy(curiosity = 0.2f))
        main { model.refresh() }
        await { !model.uiState.value.busy }
        assertTrue(model.uiState.value.pairingBlocked)
        assertFalse(model.uiState.value.connected)
    }

    @Test fun matchingRemotePairingNeedsExplicitAdoptionBeforeReadingJournal() {
        val server = Server()
        val model = model(server)
        assertFalse(model.uiState.value.connected)
        assertEquals(0, server.journalReads)
        main { model.connect() }
        await { !model.uiState.value.busy }
        assertTrue(model.uiState.value.connected)
        assertEquals(0, server.pairs)
        assertEquals(RoamFixtures.PAIR, bindings.read(config.endpoint, RoamFixtures.identity.createdAt)!!.pairingId)
    }

    @Test fun revisionConflictReadsBackButDoesNotRetryOrEnableStaleControls() {
        val server = Server().apply { rejectControls = true }
        val model = connected(server)
        main { model.saveControls(RoamControls(enabled = true)) }
        await { !model.uiState.value.busy }
        assertEquals(1, server.controlWrites)
        assertEquals(2, model.uiState.value.snapshot!!.controls.dailyWakeLimit)
        assertFalse(model.uiState.value.fresh)
        main { model.saveControls(RoamControls(enabled = true)) }
        assertEquals(1, server.controlWrites)
        main { model.refresh() }
        await { !model.uiState.value.busy }
        assertTrue(model.uiState.value.fresh)
    }

    @Test fun backendChangeDiscardsOldInFlightDataAndCredentials() {
        val server = Server()
        val model = connected(server)
        val oldResponse = CompletableDeferred<RoamSnapshot>()
        server.snapshotHook = { request -> if (request == config) oldResponse.await() else RoamFixtures.state().copy(profile = null) }
        main { model.refresh() }
        assertTrue(model.uiState.value.busy)
        settings.save(config.copy(endpoint = "https://different.example.com"))
        main { model.settingsChanged() }
        await { !model.uiState.value.busy }
        oldResponse.complete(RoamFixtures.state())
        main {}
        SystemClock.sleep(200)
        assertNull(model.uiState.value.snapshot!!.profile)
        assertFalse(model.uiState.value.connected)
        assertTrue(model.uiState.value.entries.isEmpty())
    }

    @Test fun successfulForgetDoesNotRedisplayErasedTextWhenRefreshFails() {
        val server = Server()
        val model = connected(server)
        assertEquals(1, model.uiState.value.entries.size)
        main { model.forget() }
        await { !model.uiState.value.busy }
        assertTrue(model.uiState.value.entries.isEmpty())
        assertNull(model.uiState.value.snapshot)
        assertFalse(model.uiState.value.fresh)
        assertEquals(RoamFixtures.FAMILIAR, bindings.read(config.endpoint, RoamFixtures.identity.createdAt)!!.familiarId)
    }

    @Test fun changedRevisionDuringOlderPageReloadsLatestWithoutMergingStaleText() {
        val server = Server().apply { page = page.copy(nextCursor = 100) }
        val model = connected(server)
        val replacement = RoamFixtures.journal().items.single().copy(sequence = 101, summary = "New activity")
        server.state = server.state.copy(revision = 8)
        server.page = RoamJournalPage(8, listOf(replacement), null)
        main { model.loadOlder() }
        await { !model.uiState.value.busy }
        assertEquals(listOf(replacement), model.uiState.value.entries)
        assertTrue(model.uiState.value.fresh)
    }
}
