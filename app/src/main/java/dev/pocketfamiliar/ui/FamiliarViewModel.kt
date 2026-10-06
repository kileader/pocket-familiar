package dev.pocketfamiliar.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.PhonePerception
import dev.pocketfamiliar.persistence.CreatureRepository
import dev.pocketfamiliar.brain.BrainSettings
import dev.pocketfamiliar.brain.BrainSettingsStore
import dev.pocketfamiliar.brain.CreatureBrain
import dev.pocketfamiliar.brain.CreatureContext
import dev.pocketfamiliar.brain.DiscoveryHistoryStore
import dev.pocketfamiliar.brain.DiscoverySource
import dev.pocketfamiliar.simulation.SimulationUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FamiliarUiState(
    val update: SimulationUpdate? = null,
    val environment: EnvironmentSnapshot? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val pokeReaction: Long = 0,
    val thought: String? = null,
    val thoughtSource: DiscoverySource? = null,
    val isThinking: Boolean = false,
    val voiceError: String? = null,
    val brainConfigured: Boolean = false,
)

class FamiliarViewModel(
    private val repository: CreatureRepository,
    private val perception: PhonePerception,
    private val brain: CreatureBrain,
    private val brainSettings: BrainSettingsStore,
    private val discoveryHistory: DiscoveryHistoryStore,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(FamiliarUiState(brainConfigured = brainSettings.read().configured))
    val uiState: StateFlow<FamiliarUiState> = mutableUiState.asStateFlow()
    private val operationMutex = Mutex()
    private var revision = 0L

    fun saveBrainSettings(endpoint: String, token: String) {
        try {
            brainSettings.save(BrainSettings(endpoint, token))
            revision++
            mutableUiState.value = mutableUiState.value.copy(brainConfigured = true, voiceError = null, thought = null, thoughtSource = null)
        } catch (error: IllegalArgumentException) {
            mutableUiState.value = mutableUiState.value.copy(voiceError = error.message ?: "Check the backend settings.")
        }
    }

    fun listen() {
        if (mutableUiState.value.isThinking) return
        if (!brainSettings.read().configured) {
            mutableUiState.value = mutableUiState.value.copy(voiceError = "Set up the voice service in developer details first.")
            return
        }
        mutableUiState.value = mutableUiState.value.copy(isThinking = true, voiceError = null, thought = null, thoughtSource = null)
        viewModelScope.launch {
            var requestRevision = revision
            try {
                val context = operationMutex.withLock {
                    val update = repository.refresh()
                    val environment = observeEnvironment()
                    revision++
                    requestRevision = revision
                    mutableUiState.value = mutableUiState.value.copy(update = update, environment = environment, isLoading = false)
                    CreatureContext(update.state, environment, discoveryHistory.read())
                }
                // Network work never holds the simulation mutex. A poke can commit while waiting.
                val response = brain.think(context)
                if (revision == requestRevision) {
                    response.discoveryId?.let(discoveryHistory::remember)
                    mutableUiState.value = mutableUiState.value.copy(thought = response.text, thoughtSource = response.source)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == requestRevision) {
                    // Avoid logging credentials or provider response bodies.
                    mutableUiState.value = mutableUiState.value.copy(voiceError =
                        if (error is java.io.IOException) error.message else "The familiar’s voice is unavailable. Try again later.")
                }
            } finally {
                mutableUiState.value = mutableUiState.value.copy(isThinking = false)
            }
        }
    }

    private suspend fun observeEnvironment(includeAppUsage: Boolean = true) = try {
        withContext(Dispatchers.IO) { perception.observe(includeAppUsage) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("PocketFamiliar", "Environmental observations unavailable", error)
        null
    }

    /** Called by the Activity's resumed lifecycle; cancellation also releases the receiver. */
    suspend fun observePhoneStateChanges() {
        try {
            perception.phoneStateChanges().collect {
                operationMutex.withLock {
                    val environment = observeEnvironment(includeAppUsage = false)
                    mutableUiState.value = mutableUiState.value.copy(
                        environment = environment?.copy(appUsage = mutableUiState.value.environment?.appUsage),
                    )
                    // No repository access, voice request, revision change, or poke animation.
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("PocketFamiliar", "Live phone observations unavailable", error)
        }
    }

    fun refresh() = update(poke = false)

    fun poke() = update(poke = true)

    private fun update(poke: Boolean) {
        viewModelScope.launch {
            operationMutex.withLock {
                revision++
                mutableUiState.value = mutableUiState.value.copy(
                    isLoading = true, error = null,
                    thought = if (poke) null else mutableUiState.value.thought,
                    thoughtSource = if (poke) null else mutableUiState.value.thoughtSource,
                    voiceError = null,
                )
                try {
                    val update = if (poke) repository.poke() else repository.refresh()
                    val environment = observeEnvironment()
                    mutableUiState.value = mutableUiState.value.copy(
                        update = update,
                        environment = environment,
                        isLoading = false,
                        error = null,
                        pokeReaction = mutableUiState.value.pokeReaction + if (poke) 1 else 0,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("PocketFamiliar", if (poke) "Could not persist poke" else "Could not load creature", error)
                    mutableUiState.value = mutableUiState.value.copy(
                        isLoading = false,
                        error = if (poke) {
                            "The poke could not be saved. Please try again."
                        } else {
                            "The creature could not be loaded. Please try again."
                        },
                    )
                }
            }
        }
    }

    class Factory(
        private val repository: CreatureRepository,
        private val perception: PhonePerception,
        private val brain: CreatureBrain,
        private val brainSettings: BrainSettingsStore,
        private val discoveryHistory: DiscoveryHistoryStore,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FamiliarViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return FamiliarViewModel(repository, perception, brain, brainSettings, discoveryHistory) as T
        }
    }
}
