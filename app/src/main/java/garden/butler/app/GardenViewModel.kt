package garden.butler.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

/** Where the app is: the list, one pot's form, or the wizard over it. A
 * second flow beside the garden, so a refresh never knocks the user out
 * of a half-edited form. */
sealed interface Screen {
    data object List : Screen

    /** `name == null` is create mode; the typed name then lives in
     * `draft["name"]`. `original` is what the backend stores, so the form
     * is a diff against it. */
    data class Pot(
        val name: String?,
        val original: Map<String, String>,
        val draft: Map<String, String>,
        val saving: Boolean = false,
        val refused: String? = null,
        val note: String? = null,
    ) : Screen

    data class Calibrate(val parent: Pot, val cal: CalState) : Screen
}

const val CALIBRATION_SAVED_NOTE =
    "calibration saved — keep the pot in manual for about five readings: " +
        "the rules' window still holds the air values"

/** One screen, one state flow, no ceremony (the pitch's own words). */
class GardenViewModel(
    private val backend: Backend = Backend(BuildConfig.BUTLER_URL, BuildConfig.BUTLER_TOKEN),
) : ViewModel() {
    private val current = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = current
    private var fetching: Job? = null
    private var refreshAgain = false

    private val shown = MutableStateFlow<Screen>(Screen.List)
    val screen: StateFlow<Screen> = shown

    private val noteOnList = MutableStateFlow<String?>(null)
    val listNote: StateFlow<String?> = noteOnList

    private var polling: Job? = null
    private var polledTs = 0L
    private var calController: String? = null

    fun refresh() {
        // Single-flight: resume + pull + retry taps must not stack fetches,
        // and a slow loser must never overwrite a fresh success with its
        // stale failure. A request that lands mid-fetch is not dropped: it
        // runs once more when this one ends, since it may follow a write.
        if (fetching?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshAgain = false
        current.value =
            when (val before = current.value) {
                is UiState.Ready -> before.copy(refreshing = true, why = null)
                is UiState.Trouble -> before.copy(retrying = true)
                else -> before
            }
        fetching =
            viewModelScope
                .launch {
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
                .also { job ->
                    job.invokeOnCompletion {
                        if (refreshAgain) {
                            refreshAgain = false
                            refresh()
                        }
                    }
                }
    }

    private val garden: Garden?
        get() = (current.value as? UiState.Ready)?.garden

    fun currentPot(name: String): Pot? = garden?.potNamed(name)

    /** A phone clock behind the backend's would call every fresh reading
     * stale, so "now" is never earlier than the backend's newest report. */
    fun nowS(): Long =
        maxOf(System.currentTimeMillis() / 1000, garden?.health?.lastTs ?: 0, polledTs)

    fun open(name: String) {
        val draft = draftOf(currentPot(name) ?: return)
        noteOnList.value = null
        shown.value = Screen.Pot(name, draft, draft)
    }

    fun newPot() {
        noteOnList.value = null
        shown.value = Screen.Pot(null, emptyMap(), emptyMap())
    }

    fun edit(key: String, value: String) = onPot { it.copy(draft = it.draft + (key to value), refused = null) }

    fun back() {
        when (shown.value) {
            is Screen.Calibrate -> calEvent(CalEvent.Cancel)
            is Screen.Pot -> shown.value = Screen.List
            Screen.List -> Unit
        }
    }

    fun save() {
        val form = shown.value as? Screen.Pot ?: return
        val name = form.name ?: form.draft["name"].orEmpty()
        if (name.isBlank()) return onPot(form) { it.copy(refused = "give the pot a name") }
        // POST /pot upserts: a new pot spelt like a stored one would silently
        // edit that one instead.
        if (form.name == null && garden?.let { nameTaken(it, name) } == true) {
            return onPot(form) { it.copy(refused = "${tokenize(name)} already exists — open it from the list") }
        }
        onPot(form) { it.copy(saving = true, refused = null) }
        val body = potBody(name, changedFields(form.original, form.draft))
        viewModelScope.launch {
            // A save that timed out client-side may still have committed:
            // the refresh after it, either way, shows what the backend has.
            try {
                withContext(Dispatchers.IO) { backend.postPot(body) }
                shown.update { if (it is Screen.Pot && it.isForm(form)) Screen.List else it }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                onPot(form) { it.copy(saving = false, refused = why.message ?: why.toString()) }
            }
            refresh()
        }
    }

    fun approve(cmdId: Long) {
        val from = shown.value as? Screen.Pot ?: return
        act({ "approved: " + backend.approve(cmdId) }) { noteOnPot(from, it) }
    }

    fun verdict(cmdId: Long, value: String) {
        val from = shown.value as? Screen.Pot ?: return
        act({ backend.verdict(cmdId, value) }) { noteOnPot(from, it) }
    }

    fun resetInterval(controller: String) =
        act({
            val next = backend.interval(controller, 0)
            "$controller reports every ${next ?: "default"}s again"
        }) { noteOnList.value = it }

    /** The wizard calibrates what the backend stores, so an unsaved draft
     * refuses; and it decides on a fresh read, since the list may be a
     * minute old — a pot flipped to auto or a board gone silent since. */
    fun startCalibration() {
        val parent = shown.value as? Screen.Pot ?: return
        val name = parent.name ?: return
        if (parent.saving) return
        if (changedFields(parent.original, parent.draft).isNotEmpty() ||
            emptiedFields(parent.original, parent.draft).isNotEmpty()
        ) {
            return noteOnPot(
                parent,
                "save or discard your changes first — the wizard calibrates the stored controller and channel",
            )
        }
        onPot(parent) { it.copy(saving = true, note = null) }
        viewModelScope.launch {
            val refusal =
                try {
                    arm(parent, name)
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    why.message ?: why.toString()
                }
            if (refusal != null) onPot(parent) { it.copy(saving = false, note = refusal) }
            refresh()
        }
    }

    /** Null once the wizard is up over `parent`; else why it is not. */
    private suspend fun arm(parent: Screen.Pot, name: String): String? {
        val (pots, health) =
            try {
                withContext(Dispatchers.IO) { backend.pots() to backend.health() }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                return "could not reach the butler: ${why.message ?: why}"
            }
        current.value = UiState.Ready(splitGarden(pots, health, System.currentTimeMillis() / 1000))
        val pot = pots.firstOrNull { it.name == name } ?: return "$name is no longer on the backend"
        val on = health.controllers.firstOrNull { it.controller == pot.controller }
        canCalibrate(pot, on, nowS(), health.nextDefault)?.let { return it }
        val controller = pot.controller ?: return "map a controller and a channel first"
        try {
            withContext(Dispatchers.IO) { backend.interval(controller, FAST_NEXT_S) }
        } catch (why: CancellationException) {
            throw why
        } catch (why: Exception) {
            return "could not speed up $controller: ${why.message ?: why}"
        }
        calController = controller
        // A leftover FAST_NEXT_S from a wizard that never restored is not a
        // pace to go back to.
        val prevNextS = on?.nextS?.takeUnless { it == FAST_NEXT_S }
        val start = calStart(prevNextS, nowS(), health.nextDefault)
        shown.update {
            if (it is Screen.Pot && it.name == parent.name) Screen.Calibrate(it.copy(saving = false), start) else it
        }
        return null
    }

    /** One poll of the wizard: the pot's newest reading, then a tick. A
     * failed fetch is weather, not a verdict — it only ticks. */
    fun calPoll() {
        val name = (shown.value as? Screen.Calibrate)?.parent?.name ?: return
        if (polling?.isActive == true) return
        polling =
            viewModelScope.launch {
                val fetched =
                    try {
                        withContext(Dispatchers.IO) { backend.pots() to backend.health() }
                    } catch (why: CancellationException) {
                        throw why
                    } catch (why: Exception) {
                        null
                    }
                if (fetched != null) {
                    val (pots, health) = fetched
                    polledTs = maxOf(polledTs, health.lastTs ?: 0)
                    val pot = pots.firstOrNull { it.name == name }
                    if (pot?.raw != null && pot.readTs != null) {
                        calEvent(CalEvent.Seen(pot.raw, pot.readTs))
                    }
                }
                calEvent(CalEvent.Tick)
            }
    }

    fun calEvent(e: CalEvent) {
        val wizard = shown.value as? Screen.Calibrate ?: return
        val next = calStep(wizard.cal, e, nowS())
        if (next == wizard.cal) return
        val stepped = wizard.copy(cal = next)
        shown.value = stepped
        when (next) {
            is CalState.Saving -> saveCalibration(wizard.parent, next)
            is CalState.Finished -> leaveWizard(stepped, next)
            is CalState.Cancelled -> leaveWizard(stepped, null)
            else -> Unit
        }
    }

    private fun saveCalibration(parent: Screen.Pot, s: CalState.Saving) {
        val body = potBody(parent.name ?: return, mapOf("dry_raw" to "${s.dry}", "wet_raw" to "${s.wet}"))
        viewModelScope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.IO) { backend.postPot(body) }
                    CalEvent.Saved
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    CalEvent.Refused(why.message ?: why.toString())
                }
            calEvent(outcome)
            if (outcome is CalEvent.Refused) refresh()
        }
    }

    /** Whichever way out, the board goes back to the pace it was on; when
     * that fails the list's reset chip is the fallback, and the note says so.
     * The pop lands only on the wizard it belongs to: a slow restore must
     * not yank the user out of wherever they are by then. */
    private fun leaveWizard(wizard: Screen.Calibrate, done: CalState.Finished?) {
        val parent = wizard.parent
        val controller = calController
        val prevNextS = wizard.cal.prevNextS
        viewModelScope.launch {
            val failure =
                try {
                    if (controller != null) {
                        withContext(Dispatchers.IO) { backend.interval(controller, prevNextS ?: 0) }
                    }
                    null
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    "the interval restore failed: ${why.message ?: why} — reset it from the list"
                }
            val note =
                if (done == null) failure else listOfNotNull(CALIBRATION_SAVED_NOTE, failure).joinToString(", and ")
            val cal = done?.let { mapOf("dry_raw" to "${it.dry}", "wet_raw" to "${it.wet}") }.orEmpty()
            val form = parent.copy(original = parent.original + cal, draft = parent.draft + cal, note = note)
            shown.update { if (it === wizard) form else it }
            refresh()
        }
    }

    /** The backend's answer, or its refusal, lands where the user is looking. */
    private fun act(call: () -> String, land: (String) -> Unit) {
        viewModelScope.launch {
            val text =
                try {
                    withContext(Dispatchers.IO) { call() }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    why.message ?: why.toString()
                }
            land(text)
            refresh()
        }
    }

    private fun noteOnPot(of: Screen.Pot, text: String) = onPot(of) { it.copy(note = text) }

    /** Typing lands on whatever form is up; the current one is by definition
     * the one being typed into. */
    private inline fun onPot(change: (Screen.Pot) -> Screen.Pot) {
        shown.update { if (it is Screen.Pot) change(it) else it }
    }

    /** An async outcome lands only on the form it came from: the user may
     * have moved on to another pot, or to a second new-pot form. */
    private inline fun onPot(of: Screen.Pot, change: (Screen.Pot) -> Screen.Pot) {
        shown.update { if (it is Screen.Pot && it.isForm(of)) change(it) else it }
    }

    private fun Screen.Pot.isForm(other: Screen.Pot): Boolean =
        name == other.name && draft["name"] == other.draft["name"]
}
