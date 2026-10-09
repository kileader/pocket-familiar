package dev.pocketfamiliar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pocketfamiliar.brain.BrainSettings
import dev.pocketfamiliar.brain.BrainSettingsStore
import dev.pocketfamiliar.roam.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class FreeRoamUiState(
    val configured: Boolean = false,
    val snapshot: RoamSnapshot? = null,
    val entries: List<RoamJournalEntry> = emptyList(),
    val nextCursor: Long? = null,
    val connected: Boolean = false,
    val pairingBlocked: Boolean = false,
    val busy: Boolean = false,
    val fresh: Boolean = false,
    val loadedAt: Long? = null,
    val error: String? = null,
    val message: String? = null,
)

/** No CreatureRepository, simulator, PhonePerception, or Android background worker. */
class FreeRoamViewModel(
    private val settings: BrainSettingsStore,
    private val bindings: RoamBindingStore,
    private val client: FreeRoamClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FreeRoamUiState(configured = settings.read().configured))
    val uiState = mutableState.asStateFlow()
    private var identity: RoamIdentity? = null
    private var generation = 0L
    private var visible = false

    fun open(identity: RoamIdentity) {
        if (this.identity != identity) {
            generation++
            mutableState.value = FreeRoamUiState(configured = settings.read().configured)
        }
        this.identity = identity
        visible = true
        refresh()
    }

    fun close() { visible = false }
    fun refreshIfOpen() { if (visible) refresh() }

    fun settingsChanged() {
        generation++
        mutableState.value = FreeRoamUiState(configured = settings.read().configured)
        if (visible) refresh()
    }

    private fun current(config: BrainSettings, stamp: Long) = generation == stamp && settings.read() == config

    private fun checkCurrent(config: BrainSettings, stamp: Long) {
        if (!current(config, stamp)) throw CancellationException("Backend settings changed")
    }

    private suspend fun binding(config: BrainSettings, local: RoamIdentity) =
        withContext(Dispatchers.IO) { bindings.read(config.endpoint, local.createdAt) }

    private fun pairingProblem(profile: RoamProfile?, local: RoamIdentity, known: RoamBinding?): String? = when {
        profile == null && known?.familiarId != null -> "The server pairing is missing. Restore its backup; this app will not create a replacement familiar."
        profile != null && !profile.matches(local) -> "This backend is paired with a different familiar. Check the backend URL; its identity will not be replaced."
        profile != null && known != null && (known.pairingId != profile.pairingId ||
            (known.familiarId != null && known.familiarId != profile.familiarId)) ->
            "The server identity differs from this phone’s saved pairing. Check the backend and its backup."
        else -> null
    }

    private suspend fun fetchLatest(config: BrainSettings, local: RoamIdentity, stamp: Long) {
        var snapshot = client.snapshot(config)
        checkCurrent(config, stamp)
        val known = binding(config, local)
        val problem = pairingProblem(snapshot.profile, local, known)
        val connected = problem == null && known != null && snapshot.profile != null
        val verified = known?.copy(familiarId = snapshot.profile?.familiarId ?: known.familiarId)
        var page: RoamJournalPage? = null
        if (connected) {
            if (known!!.familiarId == null) {
                withContext(Dispatchers.IO) {
                    bindings.save(config.endpoint, local.createdAt, known.copy(familiarId = snapshot.profile!!.familiarId))
                }
            }
            page = client.journal(config)
            checkCurrent(config, stamp)
            if (page.revision != snapshot.revision) {
                snapshot = client.snapshot(config)
                page = client.journal(config)
                checkCurrent(config, stamp)
                if (page.revision != snapshot.revision || pairingProblem(snapshot.profile, local, verified) != null)
                    throw RoamApiException("The journal changed while loading. Refresh again.", conflict = true)
            }
        }
        checkCurrent(config, stamp)
        mutableState.value = FreeRoamUiState(configured = true, snapshot = snapshot,
            entries = page?.items ?: emptyList(), nextCursor = page?.nextCursor,
            connected = connected, pairingBlocked = problem != null, busy = true,
            fresh = true, loadedAt = System.currentTimeMillis(), error = problem)
    }

    private fun operate(work: suspend (BrainSettings, RoamIdentity, Long) -> Unit) {
        if (mutableState.value.busy || identity == null) return
        val config = settings.read()
        if (!config.configured) {
            mutableState.value = FreeRoamUiState(error = "Set up the voice service in developer details first.")
            return
        }
        val local = identity!!
        val stamp = generation
        mutableState.value = mutableState.value.copy(busy = true, fresh = false, error = null, message = null)
        viewModelScope.launch {
            try {
                work(config.validated(), local, stamp)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current(config, stamp)) {
                    if (error is RoamApiException && error.conflict) {
                        mutableState.value = mutableState.value.copy(snapshot = null, entries = emptyList(), connected = false)
                        // Read back, never retry a mutation against a newer revision.
                        try { fetchLatest(config, local, stamp) } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { /* Keep controls unavailable until a successful read. */ }
                    }
                    mutableState.value = mutableState.value.copy(fresh = false,
                        error = if (error is RoamApiException) error.message else "Free Roam returned an invalid response. Refresh or check the backend.")
                }
            } finally {
                if (current(config, stamp)) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun refresh() = operate(::fetchLatest)

    fun connect() = operate { config, local, stamp ->
        val remote = client.snapshot(config)
        checkCurrent(config, stamp)
        val known = binding(config, local)
        pairingProblem(remote.profile, local, known)?.let { throw RoamApiException(it) }
        val candidate = known ?: RoamBinding(remote.profile?.pairingId ?: UUID.randomUUID().toString())
        // Commit the retry UUID before the first POST, even if the response is lost.
        withContext(Dispatchers.IO) { bindings.save(config.endpoint, local.createdAt, candidate) }
        checkCurrent(config, stamp)
        val paired = if (remote.profile != null) remote else client.pair(config, local, candidate.pairingId)
        checkCurrent(config, stamp)
        val profile = paired.profile ?: throw RoamApiException("Pairing did not complete. Refresh and retry.")
        pairingProblem(profile, local, candidate)?.let { throw RoamApiException(it) }
        withContext(Dispatchers.IO) { bindings.save(config.endpoint, local.createdAt, candidate.copy(familiarId = profile.familiarId)) }
        fetchLatest(config, local, stamp)
    }

    private fun writableSnapshot(): RoamSnapshot? =
        mutableState.value.takeIf { it.connected && it.fresh && !it.busy }?.snapshot

    fun saveControls(controls: RoamControls) {
        val snapshot = writableSnapshot() ?: return
        if (controls.enabled && !snapshot.executionAvailable) return
        if (controls.webEnabled && !snapshot.webAvailable) return
        operate { config, local, stamp ->
            client.controls(config, snapshot.revision, controls.validated())
            checkCurrent(config, stamp)
            fetchLatest(config, local, stamp)
        }
    }

    fun forget() {
        val snapshot = writableSnapshot() ?: return
        operate { config, local, stamp ->
            client.forget(config, snapshot.revision)
            checkCurrent(config, stamp)
            mutableState.value = mutableState.value.copy(snapshot = null, entries = emptyList(), connected = false)
            fetchLatest(config, local, stamp)
            mutableState.value = mutableState.value.copy(message = "Free Roam memory forgotten. The familiar’s identity and local state are preserved.")
        }
    }

    fun deleteEntry(id: String) {
        val snapshot = writableSnapshot() ?: return
        operate { config, local, stamp ->
            client.deleteEntry(config, snapshot.revision, id)
            checkCurrent(config, stamp)
            mutableState.value = mutableState.value.copy(entries = mutableState.value.entries.filterNot { it.id == id })
            fetchLatest(config, local, stamp)
        }
    }

    fun loadOlder() {
        val state = mutableState.value
        val before = state.nextCursor ?: return
        val snapshot = writableSnapshot() ?: return
        operate { config, local, stamp ->
            val page = client.journal(config, before)
            checkCurrent(config, stamp)
            if (page.revision != snapshot.revision) {
                fetchLatest(config, local, stamp)
                mutableState.value = mutableState.value.copy(message = "The journal changed; showing the latest activity.")
            } else {
                mutableState.value = state.copy(entries = (state.entries + page.items).distinctBy { it.id },
                    nextCursor = page.nextCursor, busy = true, fresh = true, error = null)
            }
        }
    }

    class Factory(private val settings: BrainSettingsStore, private val bindings: RoamBindingStore,
                  private val client: FreeRoamClient) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FreeRoamViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return FreeRoamViewModel(settings, bindings, client) as T
        }
    }
}
