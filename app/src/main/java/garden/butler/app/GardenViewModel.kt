package garden.butler.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Loading : UiState

    data class Trouble(val why: String, val retrying: Boolean = false) : UiState

    data class Ready(
        val garden: Garden,
        val refreshing: Boolean = false,
        val why: String? = null, // the last refresh failed; the list stays up
    ) : UiState
}

/** One screen, one state flow, no ceremony (the pitch's own words). */
class GardenViewModel(
    private val backend: Backend = Backend(BuildConfig.BUTLER_URL),
) : ViewModel() {
    private val current = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = current
    private var fetching: Job? = null

    fun refresh() {
        // Single-flight: resume + pull + retry taps must not stack fetches,
        // and a slow loser must never overwrite a fresh success with its
        // stale failure.
        if (fetching?.isActive == true) return
        current.value =
            when (val before = current.value) {
                is UiState.Ready -> before.copy(refreshing = true, why = null)
                is UiState.Trouble -> before.copy(retrying = true)
                else -> before
            }
        fetching =
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
                    } catch (why: CancellationException) {
                        throw why // cancellation is not a backend problem
                    } catch (why: Exception) {
                        when (val before = current.value) {
                            // A displayed garden survives a failed refresh: a
                            // busy-database 503 must not blank the sofa view.
                            is UiState.Ready ->
                                before.copy(
                                    refreshing = false,
                                    why = why.message ?: why.toString(),
                                )
                            else -> UiState.Trouble(why.message ?: why.toString())
                        }
                    }
            }
    }
}
