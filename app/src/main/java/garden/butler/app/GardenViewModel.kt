package garden.butler.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Loading : UiState

    data class Trouble(val why: String) : UiState

    data class Ready(val garden: Garden, val refreshing: Boolean = false) : UiState
}

/** One screen, one state flow, no ceremony (the pitch's own words). */
class GardenViewModel(
    private val backend: Backend = Backend(BuildConfig.BUTLER_URL),
) : ViewModel() {
    private val current = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = current

    fun refresh() {
        val before = current.value
        if (before is UiState.Ready) current.value = before.copy(refreshing = true)
        viewModelScope.launch {
            current.value =
                try {
                    val garden =
                        withContext(Dispatchers.IO) {
                            splitGarden(
                                backend.pots(),
                                backend.health(),
                                System.currentTimeMillis() / 1000,
                            )
                        }
                    UiState.Ready(garden)
                } catch (why: Exception) {
                    // The sofa answer to "backend down?": say so, offer retry.
                    UiState.Trouble(why.message ?: why.toString())
                }
        }
    }
}
