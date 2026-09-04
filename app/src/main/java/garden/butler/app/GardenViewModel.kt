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

sealed interface UiState {
    data object Loading : UiState

    data class Trouble(val why: String, val retrying: Boolean = false) : UiState

    data class Ready(
        val garden: Garden,
        val refreshing: Boolean = false,
        val why: String? = null, // the last refresh failed; the list stays up
        /** Non-null means every number here came off the disk at that
         * moment and nothing has been heard from the butler since. */
        val cachedAtS: Long? = null,
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
        /** The stored sensor's curve over `window`; stays up when a reload
         * fails. */
        val history: History? = null,
        val historyWhy: String? = null,
        val window: ChartWindow = ChartWindow.DAY,
        /** The water command this form queued, followed until its fate is known. */
        val watering: Issued? = null,
        val waterRefused: String? = null,
        /** The last species lookup made from this form. It is not stored
         * anywhere: what the pot keeps is the name in draft["species"], and
         * the garden carries the cached care beside it afterwards. */
        val lookup: SpeciesAnswer? = null,
        val lookingUp: Boolean = false,
        /** This pot's photographs, newest first as the wire sends them; the
         * strip turns them round. Null means not asked for yet. */
        val photos: kotlin.collections.List<Photo>? = null,
        val photosWhy: String? = null,
        val uploading: Boolean = false,
        /** The photograph shown full size over the form, by id. */
        val viewing: String? = null,
        /** The field whose ⓘ is open, by wire key. */
        val explaining: String? = null,
    ) : Screen

    data class Calibrate(val parent: Pot, val cal: CalState) : Screen

    /** The address and the token: on first start, and from the garden's
     * settings after that. `first` is true when there is nothing behind
     * this screen — no garden to go back to, and Back exits the app, which
     * is what Back on the first screen of an app does. */
    data class Setup(
        val url: String,
        val token: String,
        val first: Boolean,
        val checking: Boolean = false,
        val why: String? = null,
        /** The token is dots until this says otherwise. */
        val show: Boolean = false,
    ) : Screen {
        /** Never the token. This is a data class in a state flow, and the
         * generated toString is the shortest path from a secret to a log
         * line or a crash report. */
        override fun toString(): String =
            "Setup(url=$url, first=$first, checking=$checking, why=$why)"
    }

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
        /** A full page came back, so there may be another behind it. The
         * table is never pruned; this is how the older rows are reachable. */
        val more: Boolean = false,
        val loadingMore: Boolean = false,
    ) : Screen
}

const val CALIBRATION_SAVED_NOTE =
    "calibration saved — keep the pot in manual for about five readings: " +
        "the rules' window still holds the air values"

/** The two fields the offer would write. An unsaved edit to either of them
 * would be silently overwritten by accepting it. */
private val TARGET_KEYS = setOf("target_low_pct", "target_high_pct")

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
    /** The last good answer on disk, so there is something to look at off
     * the tailnet. Null means no cache at all — which is what the tests
     * that predate it get. */
    private val cache: GardenCache? = null,
    /** Where the butler is, as this device holds it. Null means the address
     * is whatever `backend` was built with and cannot be changed — which is
     * what the JVM tests that predate the setup screen get, and what the
     * app itself was until 2026-09-04. */
    private val settings: ConfigStore? = null,
    /** What the setup screen starts filled in with when nothing is stored.
     * A development build bakes them from butler.properties; a build made
     * without that file prefills nothing and carries no token. */
    private val defaults: ButlerConfig =
        ButlerConfig(BuildConfig.BUTLER_URL, BuildConfig.BUTLER_TOKEN),
) : ViewModel() {
    private val current = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = current
    private var fetching: Job? = null
    private var refreshAgain = false
    private var historyFlight: Job? = null
    private var dosesFlight: Job? = null
    private var photosFlight: Job? = null

    private val shown = MutableStateFlow<Screen>(Screen.List)
    val screen: StateFlow<Screen> = shown

    private val noteOnList = MutableStateFlow<String?>(null)
    val listNote: StateFlow<String?> = noteOnList

    /** Everything this view model has in the air, as one job it can drop.
     * Pointing the app at another butler cancels the lot: an answer from
     * the old address landing on the new one's screen is the same mistake
     * as keeping its cache, and a slow /pots is exactly the shape that
     * would do it. A supervisor, like viewModelScope's own job, so one
     * flight failing does not take its siblings with it; a child of that
     * job, so clearing the view model still cancels everything. */
    private var work = SupervisorJob(viewModelScope.coroutineContext[Job])

    private fun flight(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(work, block = block)

    /** True once the app knows which butler it is talking to. Nothing goes
     * on the wire before that, and nothing comes off the disk either. */
    private var addressed = settings == null

    /** Which attempt to point the app at a butler is the current one.
     *
     * A probe takes five seconds to time out, and in five seconds somebody
     * can go back, come in again and connect somewhere else. The pointing
     * coroutine is the one thing here that is not a flight — it is what
     * cancels the flights — so nothing can cancel it, and it has to know
     * for itself when it has been superseded. Without this a slow first
     * Connect finishes last and moves the app back to the butler the user
     * just left: the same failure this pitch is about, through another
     * door. */
    private var attempt = 0

    init {
        val store = settings
        if (store != null) {
            // Not a flight: this is what decides where the flights go.
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
        flight {
            val cached = withContext(Dispatchers.IO) { store.read() } ?: return@flight
            if (current.value is UiState.Ready) return@flight
            // Whose plants these are. clear() runs when the app is pointed
            // somewhere else, but a delete that failed, or a kill in
            // between, would leave one butler's garden to be shown under
            // another's name; this is what makes that impossible rather
            // than unlikely. A file with no address at all is discarded
            // too: it was written by a build where the address could not
            // change, and the first thing this build does on top of one is
            // ask for an address, which may well be a different one.
            if (cached.url != backend.address) return@flight
            current.value =
                UiState.Ready(
                    splitGarden(cached.pots, cached.health, phoneS()),
                    cachedAtS = cached.atS,
                )
        }
    }

    private var polling: Job? = null
    private var polledTs = 0L
    private var calController: String? = null

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
            flight {
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
                            // cache is opened, and after a recalibration
                            // that is a different scale. potLine derives
                            // it from the cached raw instead.
                            cache?.write(
                                CachedGarden(
                                    garden.all().map { it.copy(pct = null) },
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
        loadPhotos(form)
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
            flight {
                try {
                    val history =
                        withContext(Dispatchers.IO) {
                            backend.history(controller, channel, form.window.hours, form.window.bucketS)
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

    /** The pot's own growth history. Single-flight like the chart's loader:
     * an upload refreshes it, and two answers racing would put the older
     * strip back over the newer one. A failed load keeps whatever strip is
     * already up and says why beside it. */
    private fun loadPhotos(form: Screen.Pot) {
        val id = form.id ?: return
        photosFlight?.cancel()
        photosFlight =
            flight {
                try {
                    val answer = withContext(Dispatchers.IO) { backend.photos(id) }
                    onPot(form) { it.copy(photos = answer.photos, photosWhy = null) }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    ensureActive()
                    onPot(form) {
                        it.copy(photosWhy = "pictures: " + (why.message ?: why.toString()))
                    }
                }
            }
    }

    fun reloadPhotos() = (shown.value as? Screen.Pot)?.let { loadPhotos(it) }

    /** Re-read one form's strip, and only if that form is still up. Async
     * outcomes land only on the form they came from, and a reload is an
     * outcome like any other. */
    private fun reloadPhotosOf(of: Screen.Pot) =
        (shown.value as? Screen.Pot)?.takeIf { it.isForm(of) }?.let { loadPhotos(it) }

    /** Where a picture is and what it takes to read it: the photo routes
     * are the only gated reads, so the image loader needs the header. */
    fun photoSource(photoId: String): PhotoSource = backend.photoSource(photoId)

    /** One picture, already downscaled by the screen that took it. The
     * bytes go up; what comes back is the strip, re-read. */
    fun addPhoto(jpeg: ByteArray, w: Int, h: Int) {
        val form = shown.value as? Screen.Pot ?: return
        val id = form.id ?: return noteOnPot(form, "save the pot first")
        staleRefusal()?.let { why -> return noteOnPot(form, why) }
        onPot(form) { it.copy(uploading = true, note = null, photosWhy = null) }
        flight {
            val why =
                try {
                    withContext(Dispatchers.IO) { backend.addPhoto(id, jpeg, w, h) }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: Exception) {
                    refused.message ?: refused.toString()
                }
            onPot(form) { it.copy(uploading = false, note = why) }
            // Whether it landed or not: a POST that timed out client-side
            // may still have stored the picture, and the strip is what says
            // which happened. Only this form's, though — the user may have
            // moved on to another pot by now, and reloading whatever is on
            // screen would cancel that pot's own fetch to re-ask a question
            // nobody asked.
            reloadPhotosOf(form)
        }
    }

    fun viewPhoto(photoId: String?) = onPot { it.copy(viewing = photoId) }

    /** Forget one picture. The row goes first on the backend, so this is
     * gone from the strip even if the volume will not give up the bytes. */
    fun deletePhoto(photoId: String) {
        val form = shown.value as? Screen.Pot ?: return
        staleRefusal()?.let { why -> return noteOnPot(form, why) }
        onPot(form) { it.copy(viewing = null) }
        flight {
            val note =
                try {
                    withContext(Dispatchers.IO) { backend.deletePhoto(photoId) }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: Exception) {
                    refused.message ?: refused.toString()
                }
            onPot(form) { it.copy(note = note) }
            reloadPhotosOf(form)
        }
    }

    /** Day, week or month on the open form's chart. The curve is dropped
     * rather than kept while the new one loads: a month's shape drawn under
     * a "day" chip is a lie, and the spinner is one refresh long. */
    fun setChartWindow(window: ChartWindow) {
        val form = shown.value as? Screen.Pot ?: return
        if (form.window == window) return
        val pot = form.id?.let { currentPot(it) } ?: return
        val next = form.copy(window = window, history = null, historyWhy = null)
        shown.value = next
        loadHistory(next, pot)
    }

    private fun controllerOf(name: String?): ControllerHealth? =
        garden?.health?.controllers?.firstOrNull { it.controller == name }

    private fun nextDefault(): Int = garden?.health?.nextDefault ?: 60

    /** When the screen is showing disk rather than the butler. */
    private fun cachedAtS(): Long? = (current.value as? UiState.Ready)?.cachedAtS

    /** Why a write must not go out, or null. Nothing is queued for later:
     * the pitch is a cache, not offline editing, and a dose queued now and
     * poured whenever the tailnet comes back is a dose nobody asked for
     * then. */
    private fun staleRefusal(): String? = cachedAtS()?.let { staleLine(it, nowS()) }

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
        onPot(form) { it.copy(saving = true, waterRefused = null) }
        flight {
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
        if (!addressed) return
        noteOnList.value = null
        shown.value = Screen.Pot(null, emptyMap(), emptyMap())
    }

    /** Change the address or the token. The stored token goes into the
     * field rather than being blanked — moving the NAS should not mean
     * typing a secret again — and shows as dots until the eye is tapped. */
    fun openSettings() {
        val store = settings ?: return
        // Whatever was being tried is no longer what the user is doing.
        attempt++
        noteOnList.value = null
        flight {
            val stored = withContext(Dispatchers.IO) { store.read() }
            shown.value =
                Screen.Setup(
                    stored?.url ?: defaults.url,
                    stored?.token ?: defaults.token,
                    first = false,
                )
        }
    }

    fun editSetup(url: String? = null, token: String? = null) =
        onSetup { it.copy(url = url ?: it.url, token = token ?: it.token, why = null) }

    fun revealToken(show: Boolean) = onSetup { it.copy(show = show) }

    /** Prove the address and the token with a real call, then keep them.
     *
     * The proof is the point. Nothing here can be validated by looking at
     * it: an address that parses may have nothing behind it, and a token is
     * only ever right or wrong to the butler. What comes back is one of
     * three different mistakes, and the sentence says which — because only
     * one of them is fixed by retyping the token. */
    fun saveSetup() {
        val form = shown.value as? Screen.Setup ?: return
        val store = settings ?: return
        if (form.checking) return // one address is being tried already
        urlProblem(form.url)?.let { why -> return onSetup { it.copy(why = why) } }
        tokenProblem(form.token)?.let { why -> return onSetup { it.copy(why = why) } }
        val candidate = ButlerConfig(normaliseUrl(form.url), form.token.trim())
        onSetup { it.copy(checking = true, why = null) }
        val mine = ++attempt
        // Deliberately not a flight: pointing the app somewhere else cancels
        // every flight, and this is the coroutine that does the pointing.
        // Which is exactly why it needs `attempt` of its own — nothing else
        // can cancel it, so it has to know when it has been superseded.
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) { backend.probe(candidate) }
            if (mine != attempt) return@launch
            if (probe !is Probe.Butler) {
                return@launch onSetup {
                    it.copy(checking = false, why = probeLine(probe, hostOf(candidate.url)))
                }
            }
            val kept =
                try {
                    withContext(Dispatchers.IO) {
                        store.write(candidate)
                        // The cache belongs to one butler. Clear it before
                        // the app is pointed, so there is no moment where
                        // the new address could open the old one's garden.
                        cache?.clear()
                    }
                    null
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    "the butler answered, but this phone could not store the address: " +
                        (why.message ?: why.toString())
                }
            if (mine != attempt) return@launch
            if (kept != null) {
                return@launch onSetup { it.copy(checking = false, why = kept) }
            }
            pointAt(candidate)
        }
    }

    /** Talk to that butler from now on. Everything in the air is dropped
     * first: a /pots from the old address landing afterwards would put its
     * plants on the new one's screen, and a wizard's interval restore would
     * be posted to a machine that never sped up. */
    private fun pointAt(config: ButlerConfig) {
        work.cancel()
        work = SupervisorJob(viewModelScope.coroutineContext[Job])
        fetching = null
        historyFlight = null
        dosesFlight = null
        photosFlight = null
        polling = null
        refreshAgain = false
        calController = null
        backend.point(config)
        addressed = true
        current.value = UiState.Loading
        noteOnList.value = null
        shown.value = Screen.List
        refresh()
    }

    private inline fun onSetup(change: (Screen.Setup) -> Screen.Setup) {
        shown.update { if (it is Screen.Setup) change(it) else it }
    }

    /** Open one field's explanation, and close it again. State on the
     * screen rather than in the composable, so it survives a rotation and a
     * test can ask what is open without touching the view layer. */
    fun explain(key: String) = onPot { it.copy(explaining = key) }

    fun stopExplaining() = onPot { it.copy(explaining = null) }

    /** Take the kind the lookup offered. A tap, because the field already
     * held an answer somebody typed and a guess off a botanical family does
     * not get to overwrite one. */
    fun useKind(kind: String) = edit("plant_type", kind)

    fun edit(key: String, value: String) =
        onPot { it.copy(draft = it.draft + (key to value), refused = null, waterRefused = null) }

    fun back() {
        when (val here = shown.value) {
            is Screen.Calibrate -> calEvent(CalEvent.Cancel)
            // Back to the form it was opened over, with its draft intact:
            // reading the history is not a reason to lose an edit.
            is Screen.Doses -> shown.value = here.parent ?: Screen.List
            is Screen.Pot -> shown.value = Screen.List
            // On first start there is no garden behind this, so Back is
            // what Back on an app's first screen is: leaving the app.
            is Screen.Setup -> if (!here.first) shown.value = Screen.List
            Screen.List -> Unit
        }
    }

    /** The watering history: one pot's, or the whole garden's from the
     * list. The rows come from the backend already attributed, so nothing
     * here has to guess whose dose was whose. */
    fun openDoses(potId: String?, title: String) {
        if (!addressed) return
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
     * fresher list with an older one. The flight is shared with the pager,
     * so a reload also cancels a page that is on its way. */
    private fun loadDoses(screen: Screen.Doses) {
        dosesFlight?.cancel()
        val fresh = screen.copy(loadingMore = false)
        shown.value = fresh
        dosesFlight =
            flight {
                try {
                    val answer = withContext(Dispatchers.IO) { backend.doses(fresh.potId, DOSES_LIMIT) }
                    onDoses(fresh) {
                        it.copy(
                            doses = answer.doses,
                            more = answer.doses.size >= DOSES_LIMIT,
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
                    onDoses(fresh) { it.copy(loading = false, why = why.message ?: why.toString()) }
                }
            }
    }

    /** The page before the oldest row on screen. Appended, never replacing:
     * the rows already read do not move under the finger. */
    fun loadOlderDoses() {
        val screen = shown.value as? Screen.Doses ?: return
        // Not over a reload: its cursor would be anchored to a list that is
        // about to be replaced, and the row on the old boundary would be
        // stepped over and never asked for again.
        if (screen.loading || screen.loadingMore || !screen.more) return
        val cursor = screen.doses?.lastOrNull()?.let(::doseCursor) ?: return
        dosesFlight?.cancel()
        val asking = screen.copy(loadingMore = true, why = null)
        shown.value = asking
        dosesFlight =
            flight {
                try {
                    val answer = withContext(Dispatchers.IO) { backend.doses(asking.potId, DOSES_LIMIT, cursor) }
                    onDoses(asking) {
                        it.copy(
                            doses = (it.doses ?: emptyList()) + answer.doses,
                            more = answer.doses.size >= DOSES_LIMIT,
                            loadingMore = false,
                        )
                    }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    ensureActive()
                    onDoses(asking) { it.copy(loadingMore = false, why = why.message ?: why.toString()) }
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
        onPot(form) { it.copy(saving = true, refused = null) }
        // A create must name the pot; an edit names it only to rename it,
        // so an unrelated field change cannot carry a stale nickname back
        // over a rename that landed from another phone meanwhile.
        val naming = if (form.id == null || renamed(form.original, form.draft)) name else null
        val body = potBody(form.id, naming, changedFields(form.original, form.draft))
        flight {
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

    /** Ask what is known about the species in the form. Reads only — the
     * answer is words on screen, and whatever the pot ends up storing is
     * typed or tapped afterwards, so a stale cache is no reason to refuse. */
    fun lookUpSpecies() {
        val form = shown.value as? Screen.Pot ?: return
        val typed = form.draft["species"].orEmpty().trim()
        if (typed.isBlank()) {
            return onPot(form) { it.copy(lookup = SpeciesAnswer(note = "type a species first")) }
        }
        onPot(form) { it.copy(lookingUp = true, lookup = null) }
        flight {
            val answer =
                try {
                    withContext(Dispatchers.IO) { backend.species(normaliseSpecies(typed)) }
                } catch (why: CancellationException) {
                    throw why
                } catch (why: Exception) {
                    SpeciesAnswer(
                        query = typed,
                        matched = "unavailable",
                        note = why.message ?: why.toString(),
                    )
                }
            // The kind fills the dropdown only while it is empty. A form
            // that already says herb is a human's answer and outranks a
            // guess read off a family — that one arrives as a chip to tap.
            onPot(form) {
                it.copy(
                    lookingUp = false,
                    lookup = answer,
                    draft = withKind(it.draft, answer.kind),
                )
            }
        }
    }

    /** Put the accepted name in the form: a synonym the plant was renamed
     * from, or a spelling GBIF corrected. Typing, not saving — the form is
     * dirty afterwards and Save is what stores it. */
    fun useName(name: String) = onPot { it.copy(draft = it.draft + ("species" to tokenize(name))) }

    /** Somebody recognised their plant in the shortlist. Put its name in the
     * form and ask again: the second question resolves exactly, and both
     * halves of the answer are already cached, so it costs one round trip. */
    fun pickCandidate(name: String) {
        useName(name)
        lookUpSpecies()
    }

    /** Accept the offered band. This is the approval the pitch asks for, so
     * it is an ordinary pot edit and nothing more: the same POST /pot the
     * form makes, carrying the two numbers a person just agreed to. */
    fun applyAdvice(advice: Advice) {
        val form = shown.value as? Screen.Pot ?: return
        val id = form.id ?: return noteOnPot(form, "save the pot first")
        staleRefusal()?.let { why -> return noteOnPot(form, why) }
        if (TARGET_KEYS.any { it in changedFields(form.original, form.draft) }) {
            return noteOnPot(form, "save or discard your target edits first")
        }
        act({
            backend.postPot("id=$id target_low_pct=${advice.low} target_high_pct=${advice.high}")
            "target set to ${advice.low}-${advice.high}%"
        }) { noteOnPot(form, it) }
    }

    /** Refuse the offer. Remembered against these numbers, so a repot or a
     * change of season asks again rather than never asking. */
    fun dismissAdvice() {
        val form = shown.value as? Screen.Pot ?: return
        val id = form.id ?: return
        staleRefusal()?.let { why -> return noteOnPot(form, why) }
        act({
            backend.dismissAdvice(id)
            "not now"
        }) { noteOnPot(form, it) }
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

    fun resetInterval(controller: String) {
        staleRefusal()?.let { why ->
            noteOnList.value = why
            return
        }
        resetIntervalNow(controller)
    }

    private fun resetIntervalNow(controller: String) =
        act({
            val next = backend.interval(controller, 0)
            "$controller reports every ${next ?: "default"}s again"
        }) { noteOnList.value = it }

    /** The wizard calibrates what the backend stores, so an unsaved draft
     * refuses; and it decides on a fresh read, since the list may be a
     * minute old — a pot flipped to auto or a board gone silent since. */
    fun startCalibration() {
        val parent = shown.value as? Screen.Pot ?: return
        staleRefusal()?.let { why -> return noteOnPot(parent, why) }
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
        flight {
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
            flight {
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
        flight {
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
        flight {
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
        flight {
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
                    // against, before the user's own answer was even read.
                    GardenViewModel(Backend(), cache = cache, settings = settings) as T
            }
    }

    private fun Screen.Pot.isForm(other: Screen.Pot): Boolean =
        if (id != null) id == other.id else other.id == null && draft["name"] == other.draft["name"]
}
