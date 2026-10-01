package dev.pocketfamiliar.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.PhonePerception
import dev.pocketfamiliar.persistence.CreatureRepository
import dev.pocketfamiliar.simulation.SimulationUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FamiliarUiState(
    val update: SimulationUpdate? = null,
    val environment: EnvironmentSnapshot? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val pokeReaction: Long = 0,
)

class FamiliarViewModel(
    private val repository: CreatureRepository,
    private val perception: PhonePerception,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(FamiliarUiState())
    val uiState: StateFlow<FamiliarUiState> = mutableUiState.asStateFlow()
    private val operationMutex = Mutex()

    fun refresh() = update(poke = false)

    fun poke() = update(poke = true)

    private fun update(poke: Boolean) {
        viewModelScope.launch {
            operationMutex.withLock {
                mutableUiState.value = mutableUiState.value.copy(isLoading = true, error = null)
                try {
                    val update = if (poke) repository.poke() else repository.refresh()
                    val environment = try {
                        perception.observe()
                    } catch (error: Exception) {
                        Log.w("PocketFamiliar", "Environmental observations unavailable", error)
                        null
                    }
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
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FamiliarViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return FamiliarViewModel(repository, perception) as T
        }
    }
}
