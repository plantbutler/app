package garden.butler.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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

    /** `id == null` is create mode: the backend mints the id on save. The
     * nickname lives in `draft["name"]` either way, which is what makes a
     * rename an ordinary edit. `original` is what the backend stores, so
     * the form is a diff against it. */
    data class Pot(
        val id: String?,
        val original: Map<String, String>,
        val draft: Map<String, String>,
        val saving: Boolean = false,
        val refused: String? = null,
        val note: String? = null,
        /** The last 24 h of the stored sensor; stays up when a reload fails. */
        val history: History? = null,
        val historyWhy: String? = null,
        /** The water command this form queued, followed until its fate is known. */
        val watering: Issued? = null,
        val waterRefused: String? = null,
    ) : Screen

    data class Calibrate(val parent: Pot, val cal: CalState) : Screen

    /** The watering history, over the form it was opened from — `parent`
     * null means it was opened from the list and covers the whole garden.
     * `nowS` is the server's own clock from the answer, so "3h ago" is not
     * the phone's opinion of a backend timestamp. */
    data class Doses(
        val parent: Pot?,
        val potId: String?,
        val title: String,
        val doses: kotlin.collections.List<Dose>? = null, // Screen.List shadows the plain one here
        val nowS: Long = 0,
        val loading: Boolean = true,
        val why: String? = null,
    ) : Screen
}

const val CALIBRATION_SAVED_NOTE =
    "calibration saved — keep the pot in manual for about five readings: " +
        "the rules' window still holds the air values"

private const val NO_ANSWER =
    "no answer from the butler — it may still have queued the dose; check the controllers card"
private const val NO_COMMAND_ID = "the butler answered without a command id — check the controllers card"

/** One screen, one state flow, no ceremony (the pitch's own words). */
class GardenViewModel(
    private val backend: Backend = Backend(BuildConfig.BUTLER_URL, BuildConfig.BUTLER_TOKEN),
    /** How often the pot screen asks after a queued dose; a test shortens it. */
    val followEveryMs: Long = FOLLOW_EVERY_MS,
    /** The phone's clock in seconds; a test drives it past a wait. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    private val current = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = current
    private var fetching: Job? = null
    private var refreshAgain = false
    private var historyFlight: Job? = null
    private var dosesFlight: Job? = null

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
                    val fresh =
                        try {
                            val garden =
                                withContext(Dispatchers.IO) {
                                    splitGarden(backend.pots(), backend.health(), phoneS())
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
                    current.value = fresh
                    if (fresh is UiState.Ready) rideRefresh(fresh.garden)
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

    /** The open form rides every successful refresh: its curve reloads so a
     * dose shows up on it, and a water refusal goes once the slot is free
     * and nothing is proposed — the reasons the backend gives are the
     * transient ones. */
    private fun rideRefresh(garden: Garden) {
        val form = shown.value as? Screen.Pot ?: return
        val pot = form.id?.let { garden.potById(it) } ?: return
        loadHistory(form, pot)
        if (form.waterRefused != null &&
            cannotWater(pot, controllerOf(pot.controller), nowS(), nextDefault(), emptySet()) == null
        ) {
            onPot(form) { it.copy(waterRefused = null) }
        }
    }

    private val garden: Garden?
        get() = (current.value as? UiState.Ready)?.garden

    fun currentPot(id: String): Pot? = garden?.potById(id)

    /** The phone's own clock: what a command was issued against, so a
     * backend with a clock of its own cannot stretch or cut the wait. */
    fun phoneS(): Long = clock()

    /** A phone clock behind the backend's would call every fresh reading
     * stale, so "now" is never earlier than the backend's newest report. */
    fun nowS(): Long = maxOf(phoneS(), garden?.health?.lastTs ?: 0, polledTs)

    fun open(id: String) {
        val pot = currentPot(id) ?: return
        val draft = draftOf(pot)
        noteOnList.value = null
        val form = Screen.Pot(id, draft, draft)
        shown.value = form
        loadHistory(form, pot)
    }

    /** The curve is raw counts read through the pot's current calibration,
     * so a recalibration needs no reload; a failed fetch keeps the curve
     * already up and says why beside it. Single-flight: a reload cancels
     * the one before it, and a cancelled flight lands nothing — not even
     * its failure over a newer curve. */
    private fun loadHistory(form: Screen.Pot, pot: Pot) {
        val controller = pot.controller ?: return
        val channel = pot.channel ?: return
        historyFlight?.cancel()
        historyFlight =
            viewModelScope.launch {
                try {
                    val history =
                        withContext(Dispatchers.IO) {
                            backend.history(controller, channel, HISTORY_HOURS, HISTORY_BUCKET_S)
                        }
                    onPot(form) { it.copy(history = history, historyWhy = null) }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    ensureActive()
                    onPot(form) { it.copy(historyWhy = "chart: " + (why.message ?: why.toString())) }
                }
            }
    }

    private fun controllerOf(name: String?): ControllerHealth? =
        garden?.health?.controllers?.firstOrNull { it.controller == name }

    private fun nextDefault(): Int = garden?.health?.nextDefault ?: 60

    /** One dose to the stored pot — never the draft: the backend waters what
     * it has, so an unsaved controller or dose would water the wrong thing.
     * The checks are cannotWater's; the backend repeats the ones it owns. */
    fun water() {
        val form = shown.value as? Screen.Pot ?: return
        val id = form.id ?: return
        val pot = currentPot(id) ?: return
        val dirty =
            changedFields(form.original, form.draft).keys +
                emptiedFields(form.original, form.draft).map { it.key }
        cannotWater(pot, controllerOf(pot.controller), nowS(), nextDefault(), dirty)?.let { reason ->
            return onPot(form) { it.copy(waterRefused = reason) }
        }
        val controller = pot.controller ?: return
        val outlet = pot.outlet ?: return
        val ml = pot.doseMl ?: return
        onPot(form) { it.copy(saving = true, waterRefused = null) }
        viewModelScope.launch {
            // A POST that timed out client-side may still have queued the
            // dose: the refresh after it shows the slot either way.
            try {
                val id = withContext(Dispatchers.IO) { backend.water(controller, outlet, ml) }
                onPot(form) {
                    if (id == null) {
                        it.copy(saving = false, waterRefused = NO_COMMAND_ID)
                    } else {
                        it.copy(saving = false, watering = Issued(id, phoneS()), waterRefused = null)
                    }
                }
            } catch (why: CancellationException) {
                throw why
            } catch (why: IOException) {
                onPot(form) { it.copy(saving = false, waterRefused = NO_ANSWER) }
            } catch (why: Exception) {
                onPot(form) { it.copy(saving = false, waterRefused = why.message ?: why.toString()) }
            }
            refresh()
        }
    }

    /** One more look at the slot and the log; the pot screen calls it every
     * followEveryMs while stillFollowing, so nothing here loops. The curve
     * rides the refresh, so the dose shows up on it. */
    fun followWater() = refresh()

    /** Where the form's queued dose is, read from the last good garden. A
     * failed refresh leaves the previous garden up and must not read as
     * "the command is gone". The wait is measured on the clock that
     * stamped the command. */
    fun currentWaterStatus(form: Screen.Pot): WaterStatus? {
        val issued = form.watering ?: return null
        val pot = form.id?.let { currentPot(it) }
        val stale = (current.value as? UiState.Ready)?.why != null
        return waterStatus(issued, pot, controllerOf(pot?.controller), phoneS(), stale)
    }

    fun newPot() {
        noteOnList.value = null
        shown.value = Screen.Pot(null, emptyMap(), emptyMap())
    }

    fun edit(key: String, value: String) =
        onPot { it.copy(draft = it.draft + (key to value), refused = null, waterRefused = null) }

    fun back() {
        when (val here = shown.value) {
            is Screen.Calibrate -> calEvent(CalEvent.Cancel)
            // Back to the form it was opened over, with its draft intact:
            // reading the history is not a reason to lose an edit.
            is Screen.Doses -> shown.value = here.parent ?: Screen.List
            is Screen.Pot -> shown.value = Screen.List
            Screen.List -> Unit
        }
    }

    /** The watering history: one pot's, or the whole garden's from the
     * list. The rows come from the backend already attributed, so nothing
     * here has to guess whose dose was whose. */
    fun openDoses(potId: String?, title: String) {
        val parent = shown.value as? Screen.Pot
        // Not while the form has something on the wire. Back restores this
        // very snapshot, so leaving mid-save would bring back a form stuck
        // on saving = true — its Save and Water greyed out for good, since
        // the outcome lands on whatever form is shown and this one is not.
        // Worse over the wizard's arming POST: the board would be left
        // reporting every 5 s with no wizard on screen to restore it.
        if (parent?.saving == true) return
        val screen = Screen.Doses(parent, potId, title)
        noteOnList.value = null
        shown.value = screen
        loadDoses(screen)
    }

    fun reloadDoses() = (shown.value as? Screen.Doses)?.let { loadDoses(it.copy(loading = true, why = null)) }

    /** Single-flight, like the chart's loader: two quick pulls must not
     * race, or the slower answer lands last and quietly replaces the
     * fresher list with an older one. */
    private fun loadDoses(screen: Screen.Doses) {
        shown.value = screen
        dosesFlight?.cancel()
        dosesFlight =
            viewModelScope.launch {
                try {
                    val answer = withContext(Dispatchers.IO) { backend.doses(screen.potId, DOSES_LIMIT) }
                    onDoses(screen) {
                        it.copy(
                            doses = answer.doses,
                            // A backend that sends no clock would otherwise
                            // date every row to the epoch and render the lot
                            // as "0s ago" — a confident wrong answer. The
                            // phone's own clock is the honest fallback.
                            nowS = if (answer.now > 0) answer.now else phoneS(),
                            loading = false,
                            why = null,
                        )
                    }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    ensureActive()
                    // The list already up stays up: a failed reload is weather.
                    onDoses(screen) { it.copy(loading = false, why = why.message ?: why.toString()) }
                }
            }
    }

    /** An answer lands only on the history it was asked for: the user may
     * have moved on to another pot's, or back to the garden's. */
    private inline fun onDoses(of: Screen.Doses, change: (Screen.Doses) -> Screen.Doses) {
        shown.update { if (it is Screen.Doses && it.potId == of.potId && it.parent?.id == of.parent?.id) change(it) else it }
    }

    fun save() {
        val form = shown.value as? Screen.Pot ?: return
        val name = form.draft["name"].orEmpty()
        if (name.isBlank()) return onPot(form) { it.copy(refused = "give the pot a name") }
        // Nicknames stay unique: a create spelt like a stored pot, or a
        // rename onto another pot's name, is caught here as well as by the
        // backend. The pot's own name is not a clash with itself.
        if (garden?.let { nameTaken(it, name, form.id) } == true) {
            val why =
                if (form.id == null) {
                    "${tokenize(name)} already exists — open it from the list"
                } else {
                    "${tokenize(name)} is another pot's name"
                }
            return onPot(form) { it.copy(refused = why) }
        }
        onPot(form) { it.copy(saving = true, refused = null) }
        // A create must name the pot; an edit names it only to rename it,
        // so an unrelated field change cannot carry a stale nickname back
        // over a rename that landed from another phone meanwhile.
        val naming = if (form.id == null || renamed(form.original, form.draft)) name else null
        val body = potBody(form.id, naming, changedFields(form.original, form.draft))
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
        val id = parent.id ?: return
        val name = currentPot(id)?.name ?: parent.original["name"] ?: return
        if (parent.saving) return
        // A rename is a change like any other here: the wizard posts the
        // stored name, so an unsaved one would be silently dropped.
        if (formDirty(parent.original, parent.draft)) {
            return noteOnPot(
                parent,
                "save or discard your changes first — the wizard calibrates the stored controller and channel",
            )
        }
        onPot(parent) { it.copy(saving = true, note = null) }
        viewModelScope.launch {
            val refusal =
                try {
                    arm(parent, id, name)
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
    private suspend fun arm(parent: Screen.Pot, id: String, name: String): String? {
        val (pots, health) =
            try {
                withContext(Dispatchers.IO) { backend.pots() to backend.health() }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                return "could not reach the butler: ${why.message ?: why}"
            }
        current.value = UiState.Ready(splitGarden(pots, health, phoneS()))
        // By id: the fresh fetch is here to catch drift, and a rename is
        // exactly the drift a name lookup would misread as a missing pot.
        val pot = pots.firstOrNull { it.id == id } ?: return "$name is no longer on the backend"
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
            if (it is Screen.Pot && it.id == parent.id) Screen.Calibrate(it.copy(saving = false), start) else it
        }
        return null
    }

    /** One poll of the wizard: the pot's newest reading, then a tick. A
     * failed fetch is weather, not a verdict — it only ticks. */
    fun calPoll() {
        val id = (shown.value as? Screen.Calibrate)?.parent?.id ?: return
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
                    val pot = pots.firstOrNull { it.id == id }
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
        // No name at all: a wizard can stand open for minutes, and the two
        // numbers are the whole edit. Resending the nickname it opened with
        // would undo a rename made anywhere else in that time.
        val body = potBody(parent.id, null, mapOf("dry_raw" to "${s.dry}", "wet_raw" to "${s.wet}"))
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

    /** An id identifies a form on its own — a rename must not orphan the
     * outcome of the save that renamed it. Two create forms have no id, so
     * only there does the typed name tell them apart. */
    private fun Screen.Pot.isForm(other: Screen.Pot): Boolean =
        if (id != null) id == other.id else other.id == null && draft["name"] == other.draft["name"]
}
