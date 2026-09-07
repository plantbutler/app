// The recalibration wizard, driven: arm the board, poll it, restore it.
package garden.butler.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

const val CALIBRATION_SAVED_NOTE =
    "calibration saved — keep the pot in manual for about five readings: " +
        "the rules' window still holds the air values"

/** The wizard calibrates what the backend stores, so an unsaved draft
 * refuses; and it decides on a fresh read, since the list may be a
 * minute old — a pot flipped to auto or a board gone silent since. */
internal fun GardenViewModel.startCalibration() {
    val parent = shown.value as? Screen.Pot ?: return
    staleRefusal()?.let { why -> return noteOnPot(parent, why) }
    val id = parent.id ?: return
    val name = currentPot(id)?.name ?: parent.original["name"] ?: return
    if (parent.busy) return
    // A rename is a change like any other here: the wizard posts the
    // stored name, so an unsaved one would be silently dropped.
    if (formDirty(parent.original, parent.draft)) {
        return noteOnPot(
            parent,
            "save or discard your changes first — the wizard calibrates the stored controller and channel",
        )
    }
    onPot(parent) { it.copy(busy = true, note = null) }
    background {
        val refusal =
            try {
                arm(parent, id, name)
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                why.reason()
            }
        if (refusal != null) onPot(parent) { it.copy(busy = false, note = refusal) }
        refresh()
    }
}

/** Null once the wizard is up over `parent`; else why it is not. */
private suspend fun GardenViewModel.arm(parent: Screen.Pot, id: String, name: String): String? {
    val (pots, health) =
        try {
            withContext(Dispatchers.IO) { backend.pots() to backend.health() }
        } catch (why: CancellationException) {
            throw why
        } catch (why: Exception) {
            return "could not reach the butler: ${why.reason()}"
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
        return "could not speed up $controller: ${why.reason()}"
    }
    calController = controller
    // A leftover FAST_NEXT_S from a wizard that never restored is not a
    // pace to go back to.
    val prevNextS = on?.nextS?.takeUnless { it == FAST_NEXT_S }
    val start = calStart(prevNextS, nowS(), health.nextDefault)
    shown.update {
        if (it is Screen.Pot && it.id == parent.id) Screen.Calibrate(it.copy(busy = false), start) else it
    }
    return null
}

/** One poll of the wizard: the pot's newest reading, then a tick. A
 * failed fetch is weather, not a verdict — it only ticks. */
internal fun GardenViewModel.calPoll() {
    val id = (shown.value as? Screen.Calibrate)?.parent?.id ?: return
    if (polling?.isActive == true) return
    polling =
        background {
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

internal fun GardenViewModel.calEvent(e: CalEvent) {
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

private fun GardenViewModel.saveCalibration(parent: Screen.Pot, s: CalState.Saving) {
    // No name at all: a wizard can stand open for minutes, and the two
    // numbers are the whole edit. Resending the nickname it opened with
    // would undo a rename made anywhere else in that time.
    val body = potBody(parent.id, null, mapOf("dry_raw" to "${s.dry}", "wet_raw" to "${s.wet}"))
    background {
        val outcome =
            try {
                withContext(Dispatchers.IO) { backend.postPot(body) }
                CalEvent.Saved
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                CalEvent.Refused(why.reason())
            }
        calEvent(outcome)
        if (outcome is CalEvent.Refused) refresh()
    }
}

/** Whichever way out, the board goes back to the pace it was on; when
 * that fails the list's reset chip is the fallback, and the note says so.
 * The pop lands only on the wizard it belongs to: a slow restore must
 * not yank the user out of wherever they are by then. */
private fun GardenViewModel.leaveWizard(wizard: Screen.Calibrate, done: CalState.Finished?) {
    val parent = wizard.parent
    val controller = calController
    val prevNextS = wizard.cal.prevNextS
    background {
        val failure =
            try {
                if (controller != null) {
                    withContext(Dispatchers.IO) { backend.interval(controller, prevNextS ?: 0) }
                }
                null
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                "the interval restore failed: ${why.reason()} — reset it from the list"
            }
        val note =
            if (done == null) failure else listOfNotNull(CALIBRATION_SAVED_NOTE, failure).joinToString(", and ")
        val cal = done?.let { mapOf("dry_raw" to "${it.dry}", "wet_raw" to "${it.wet}") }.orEmpty()
        val form = parent.copy(original = parent.original + cal, draft = parent.draft + cal, note = note)
        shown.update { if (it === wizard) form else it }
        refresh()
    }
}
