// Where the butler is: asking, proving it, and pointing the app at it.
package garden.butler.app

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Change the address or the token. The stored token goes into the
 * field rather than being blanked — moving the NAS should not mean
 * typing a secret again — and shows as dots until the eye is tapped. */
internal fun GardenViewModel.openSettings() {
    val store = settings ?: return
    // Whatever was being tried is no longer what the user is doing.
    attempt++
    noteOnList.value = null
    background {
        val stored = withContext(Dispatchers.IO) { store.read() }
        shown.value =
            Screen.Setup(
                stored?.url ?: defaults.url,
                stored?.token ?: defaults.token,
                first = false,
            )
    }
}

internal fun GardenViewModel.editSetup(url: String? = null, token: String? = null) =
    onSetup { it.copy(url = url ?: it.url, token = token ?: it.token, why = null) }

internal fun GardenViewModel.revealToken(show: Boolean) = onSetup { it.copy(show = show) }

/** Prove the address and the token with a real call, then keep them.
 *
 * Nothing here can be validated by looking at it: an address that
 * parses may have nothing behind it, and a token is only ever right or
 * wrong to the butler. What comes back is one of three mistakes, and
 * only one of them is fixed by retyping the token. */
internal fun GardenViewModel.saveSetup() {
    val form = shown.value as? Screen.Setup ?: return
    val store = settings ?: return
    if (form.checking) return // one address is being tried already
    urlProblem(form.url)?.let { why -> return onSetup { it.copy(why = why) } }
    tokenProblem(form.token)?.let { why -> return onSetup { it.copy(why = why) } }
    val candidate = ButlerConfig(normaliseUrl(form.url), form.token.trim())
    onSetup { it.copy(checking = true, why = null) }
    val mine = ++attempt
    // Deliberately not one of them: pointing the app somewhere else
    // cancels every background job, and this is what does the pointing —
    // which is why it needs `attempt` of its own, nothing else being
    // able to cancel it when it is superseded.
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
                    why.reason()
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
private fun GardenViewModel.pointAt(config: ButlerConfig) {
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
    shown.value = Screen.Garden
    refresh()
}

private inline fun GardenViewModel.onSetup(change: (Screen.Setup) -> Screen.Setup) {
    shown.update { if (it is Screen.Setup) change(it) else it }
}
