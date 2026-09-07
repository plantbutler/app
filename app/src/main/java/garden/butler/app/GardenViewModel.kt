// Every action the screens can take, and the flows that answer them.
package garden.butler.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NO_ANSWER =
    "no answer from the butler — it may still have queued the dose; check the controllers card"
private const val NO_COMMAND_ID = "the butler answered without a command id — check the controllers card"

class GardenViewModel(
    internal val backend: Backend = Backend(BuildConfig.BUTLER_URL, BuildConfig.BUTLER_TOKEN),
    /** How often the pot screen asks after a queued dose; a test shortens it. */
    val followEveryMs: Long = FOLLOW_EVERY_MS,
    /** The phone's clock in seconds; a test drives it past a wait. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** The last good answer on disk, so there is something to look at off
     * the tailnet. Null means no cache at all, which is what most of the
     * JVM tests take. */
    internal val cache: GardenCache? = null,
    /** Where the butler is, as this device holds it. Null means the address
     * is whatever `backend` was built with and cannot be changed, which is
     * what most of the JVM tests take. */
    internal val settings: ConfigStore? = null,
    /** What the setup screen starts filled in with when nothing is stored.
     * A development build bakes them from butler.properties; a build made
     * without that file prefills nothing and carries no token. */
    internal val defaults: ButlerConfig =
        ButlerConfig(BuildConfig.BUTLER_URL, BuildConfig.BUTLER_TOKEN),
) : ViewModel() {
    internal val current = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = current
    internal var fetching: Job? = null
    internal var refreshAgain = false
    internal var historyFlight: Job? = null
    internal var dosesFlight: Job? = null
    internal var photosFlight: Job? = null

    internal val shown = MutableStateFlow<Screen>(Screen.Garden)
    val screen: StateFlow<Screen> = shown

    internal val noteOnList = MutableStateFlow<String?>(null)
    val listNote: StateFlow<String?> = noteOnList

    /** Everything in the air, as one job it can drop. Pointing the app at
     * another butler cancels the lot: an answer from the old address
     * landing on the new one's screen is the same mistake as keeping its
     * cache, and a slow /pots is the shape that would do it. A supervisor,
     * so one job failing does not take its siblings with it; a child of
     * viewModelScope's job, so clearing the view model cancels everything. */
    internal var work = SupervisorJob(viewModelScope.coroutineContext[Job])

    internal fun background(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(work, block = block)

    /** One kind of load, of which only the newest may land. It cancels the
     * job before it and answers the new one for the caller to keep: two
     * answers racing would otherwise let the slower put an older curve,
     * strip or page back over a newer one. A cancelled job lands nothing —
     * not even its failure, which is what ensureActive() is there for. */
    internal fun <T> latestOnly(
        previous: Job?,
        fetch: suspend () -> T,
        land: (T) -> Unit,
        failed: (String) -> Unit,
    ): Job {
        previous?.cancel()
        return background {
            try {
                land(withContext(Dispatchers.IO) { fetch() })
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                ensureActive()
                failed(why.reason())
            }
        }
    }

    /** True once the app knows which butler it is talking to. Nothing goes
     * on the wire before that, and nothing comes off the disk either. */
    internal var addressed = settings == null

    /** Which attempt to point the app at a butler is the current one.
     *
     * A probe takes five seconds to time out, and in five seconds somebody
     * can go back, come in again and connect somewhere else. The pointing
     * coroutine is the one thing here that is not one of them — it is
     * what cancels them — so nothing can cancel it, and it has to know
     * for itself when it has been superseded. Without this a slow first
     * Connect finishes last and moves the app back to the butler the user
     * just left. */
    internal var attempt = 0

    init {
        val store = settings
        if (store != null) {
            // Not one of them: this is what decides where they all go.
            viewModelScope.launch {
                val stored = withContext(Dispatchers.IO) { store.read() }?.takeIf { it.complete }
                if (stored == null) {
                    shown.value = Screen.Setup(defaults.url, defaults.token, first = true)
                } else {
                    backend.point(stored)
                    addressed = true
                    openCache()
                    refresh()
                }
            }
        }
    }

    /** The cache is opened once, and fills any screen a live answer has not
     * already filled — including a Trouble screen, which is the whole point:
     * off the tailnet the network fails fast and usually beats the disk, and
     * refusing to load then would blank the app in exactly the case this
     * exists for. Only a Ready is left alone, cached or live: it is either
     * the butler's own answer or this same cache already. */
    fun openCache() {
        val store = cache ?: return
        if (!addressed) return
        background {
            val cached = withContext(Dispatchers.IO) { store.read() } ?: return@background
            if (current.value is UiState.Ready) return@background
            // Whose plants these are. Pointing the app elsewhere clears the
            // cache, but a delete that failed, or a kill in between, would
            // leave one butler's garden to be shown under another's name;
            // this is what makes that impossible rather than unlikely.
            if (cached.url != backend.address) return@background
            current.value =
                UiState.Ready(
                    splitGarden(cached.pots, cached.health, phoneS()),
                    cachedAtS = cached.atS,
                )
        }
    }

    internal var polling: Job? = null
    internal var polledTs = 0L
    internal var calController: Int? = null

    fun refresh() {
        // Nothing is asked of a butler whose address is not known yet. The
        // minute loop and the pull-to-refresh both fire regardless of what
        // is on screen, so this is where that is stopped rather than at
        // every caller.
        if (!addressed) return
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
            background {
                val fresh =
                    try {
                        val garden =
                            withContext(Dispatchers.IO) {
                                splitGarden(backend.pots(), backend.health(), phoneS())
                            }
                        withContext(Dispatchers.IO) {
                            // Nothing derived goes to disk: a stored
                            // percentage would be read back through
                            // whatever calibration the pot has when the
                            // cache is opened. potLine derives it from the
                            // cached raw instead.
                            cache?.write(
                                CachedGarden(
                                    garden.everyPot().map { it.copy(pct = null) },
                                    garden.health,
                                    phoneS(),
                                    backend.address,
                                ),
                            )
                        }
                        UiState.Ready(garden)
                    } catch (why: CancellationException) {
                        throw why // cancellation is not a backend problem
                    } catch (why: Exception) {
                        when (val before = current.value) {
                            // A displayed garden survives a failed refresh:
                            // a busy-database 503 must not blank the screen.
                            is UiState.Ready -> before.copy(refreshing = false, why = why.reason())
                            else -> UiState.Trouble(why.reason())
                        }
                    }
                current.value = fresh
                if (fresh is UiState.Ready) rideRefresh(fresh.garden)
            }.also { job ->
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
        loadHistory(form)
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
        loadHistory(form)
        loadPhotos(form)
    }

    /** The curve is raw counts read through the pot's current calibration,
     * so a recalibration needs no reload; a failed fetch keeps the curve
     * already up and says why beside it. Single-flight: a reload cancels
     * the one before it, and a cancelled job lands nothing — not even
     * its failure over a newer curve. */
    private fun loadHistory(form: Screen.Pot) {
        // By pot, and gated on nothing else: the readings carry the pot they
        // were taken for, so a pot that is currently unwired — brought back
        // from the graveyard, or waiting to be replugged — still has a curve.
        val id = form.id ?: return
        historyFlight =
            latestOnly(
                historyFlight,
                { backend.history(id, form.window.hours, form.window.bucketS) },
                { history -> onPot(form) { it.copy(history = history, historyWhy = null) } },
                { why -> onPot(form) { it.copy(historyWhy = "chart: $why") } },
            )
    }

    /** Day, week or month on the open form's chart. The curve is dropped
     * rather than kept while the new one loads: a month's shape drawn under
     * a "day" chip is a lie, and the spinner is one refresh long. */
    fun setChartWindow(window: ChartWindow) {
        val form = shown.value as? Screen.Pot ?: return
        if (form.window == window) return
        val next = form.copy(window = window, history = null, historyWhy = null)
        shown.value = next
        loadHistory(next)
    }

    private fun controllerOf(number: Int?): ControllerHealth? =
        garden?.health?.controllers?.firstOrNull { it.controller == number }

    private fun nextDefault(): Int = garden?.health?.nextDefault ?: 60

    /** When the screen is showing disk rather than the butler. */
    private fun cachedAtS(): Long? = (current.value as? UiState.Ready)?.cachedAtS

    /** Why a write must not go out, or null. Nothing is queued for later:
     * this is a cache, not offline editing, and a dose queued now and
     * poured whenever the tailnet comes back is a dose nobody asked for
     * then. */
    internal fun staleRefusal(): String? = cachedAtS()?.let { staleLine(it, nowS()) }

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
        cannotWater(pot, controllerOf(pot.controller), nowS(), nextDefault(), dirty, cachedAtS())?.let { reason ->
            return onPot(form) { it.copy(waterRefused = reason) }
        }
        val controller = pot.controller ?: return
        val outlet = pot.outlet ?: return
        val ml = pot.doseMl ?: return
        onPot(form) { it.copy(busy = true, waterRefused = null) }
        background {
            // A POST that timed out client-side may still have queued the
            // dose: the refresh after it shows the slot either way.
            try {
                val id = withContext(Dispatchers.IO) { backend.water(controller, outlet, ml) }
                onPot(form) {
                    if (id == null) {
                        it.copy(busy = false, waterRefused = NO_COMMAND_ID)
                    } else {
                        it.copy(busy = false, watering = QueuedDose(id, phoneS()), waterRefused = null)
                    }
                }
            } catch (why: CancellationException) {
                throw why
            } catch (why: IOException) {
                onPot(form) { it.copy(busy = false, waterRefused = NO_ANSWER) }
            } catch (why: Exception) {
                onPot(form) { it.copy(busy = false, waterRefused = why.reason()) }
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
        if (!addressed) return
        noteOnList.value = null
        // The draft only, never `original`: prefilling both would make the
        // controller look unchanged and never be sent. The cost is that the
        // form opens dirty, so Back asks before dropping it.
        shown.value = Screen.Pot(null, emptyMap(), mapOf("controller" to DEFAULT_CONTROLLER))
    }

    /** Open one field's explanation, and close it again. State on the
     * screen rather than in the composable, so it survives a rotation and a
     * test can ask what is open without touching the view layer. */
    fun explain(key: String) = onPot { it.copy(explaining = key) }

    fun stopExplaining() = onPot { it.copy(explaining = null) }

    fun edit(key: String, value: String) =
        onPot { it.copy(draft = it.draft + (key to value), refused = null, waterRefused = null) }

    fun back() {
        when (val here = shown.value) {
            is Screen.Calibrate -> calEvent(CalEvent.Cancel)
            // Back to the form it was opened over, with its draft intact:
            // reading the history is not a reason to lose an edit.
            is Screen.Doses -> shown.value = here.parent ?: Screen.Garden
            is Screen.Pot -> shown.value = Screen.Garden
            // On first start there is no garden behind this, so Back is
            // what Back on an app's first screen is: leaving the app.
            is Screen.Setup -> if (!here.first) shown.value = Screen.Garden
            Screen.Garden -> Unit
        }
    }

    fun save() {
        val form = shown.value as? Screen.Pot ?: return
        staleRefusal()?.let { why -> return onPot(form) { it.copy(refused = why) } }
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
        onPot(form) { it.copy(busy = true, refused = null) }
        // A create must name the pot; an edit names it only to rename it,
        // so an unrelated field change cannot carry a stale nickname back
        // over a rename that landed from another phone meanwhile.
        val naming = if (form.id == null || renamed(form.original, form.draft)) name else null
        val body = potBody(form.id, naming, changedFields(form.original, form.draft))
        background {
            // A save that timed out client-side may still have committed:
            // the refresh after it, either way, shows what the backend has.
            try {
                withContext(Dispatchers.IO) { backend.postPot(body) }
                shown.update { if (it is Screen.Pot && it.isForm(form)) Screen.Garden else it }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                onPot(form) { it.copy(busy = false, refused = why.reason()) }
            }
            refresh()
        }
    }

    /** Burying a pot and bringing it back, from the list's long-press sheet.
     * One field, sent on its own: `status=graveyard` together with any
     * wiring key is refused, and burying is what unwires. */
    fun bury(id: String) = setStatus(id, GRAVEYARD)

    fun revive(id: String) = setStatus(id, ALIVE)

    private fun setStatus(id: String, status: String) {
        if (!addressed) return
        staleRefusal()?.let { why -> return noteOnList.update { why } }
        background {
            try {
                withContext(Dispatchers.IO) { backend.postPot("id=$id status=$status") }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                noteOnList.value = why.reason()
            }
            refresh()
        }
    }

    /** Erase the open pot. The screen asks first — this is only the send.
     *
     * Refused while the garden is a memory: the one irreversible thing here
     * must not be the one thing allowed against numbers nobody confirmed.
     * And the form is popped whatever happens next, because PotScreen keeps
     * rendering from its own snapshot when the pot vanishes, so staying
     * would leave a working form whose Save posts an id that is gone. */
    fun deletePot() {
        val form = shown.value as? Screen.Pot ?: return
        val id = form.id ?: return
        staleRefusal()?.let { why -> return onPot(form) { it.copy(refused = why) } }
        onPot(form) { it.copy(busy = true, refused = null) }
        background {
            try {
                withContext(Dispatchers.IO) { backend.deletePot(id) }
                shown.update { if (it is Screen.Pot && it.isForm(form)) Screen.Garden else it }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                onPot(form) { it.copy(busy = false, refused = why.reason()) }
            }
            refresh()
        }
    }

    fun approve(cmdId: Long) {
        val from = shown.value as? Screen.Pot ?: return
        staleRefusal()?.let { why -> return noteOnPot(from, why) }
        act({ "approved: " + backend.approve(cmdId) }) { noteOnPot(from, it) }
    }

    fun verdict(cmdId: Long, value: String) {
        val from = shown.value as? Screen.Pot ?: return
        staleRefusal()?.let { why -> return noteOnPot(from, why) }
        act({ backend.verdict(cmdId, value) }) { noteOnPot(from, it) }
    }

    /** The backend's answer, or its refusal, lands where the user is looking. */
    internal fun act(call: () -> String, land: (String) -> Unit) {
        background {
            val text =
                try {
                    withContext(Dispatchers.IO) { call() }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    why.reason()
                }
            land(text)
            refresh()
        }
    }

    internal fun noteOnPot(of: Screen.Pot, text: String) = onPot(of) { it.copy(note = text) }

    /** Typing lands on whatever form is up; the current one is by definition
     * the one being typed into. */
    internal inline fun onPot(change: (Screen.Pot) -> Screen.Pot) {
        shown.update { if (it is Screen.Pot) change(it) else it }
    }

    /** An async outcome lands only on the form it came from: the user may
     * have moved on to another pot, or to a second new-pot form. */
    internal inline fun onPot(of: Screen.Pot, change: (Screen.Pot) -> Screen.Pot) {
        shown.update { if (it is Screen.Pot && it.isForm(of)) change(it) else it }
    }

    companion object {
        /** The only thing the Android side has to build: everything else
         * about this view model is defaulted, so the JVM tests keep using
         * the plain constructor. */
        fun factory(cache: GardenCache, settings: ConfigStore) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    // No address yet: the stored one is read first, and the
                    // backend is pointed at it (or the setup screen asks)
                    // before anything goes on the wire. A build constant
                    // here would be a request to whatever the APK was built
                    // against, ahead of the user's own answer.
                    GardenViewModel(Backend(), cache = cache, settings = settings) as T
            }
    }
}
