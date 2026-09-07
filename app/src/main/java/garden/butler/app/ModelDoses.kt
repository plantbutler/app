// The watering history: one pot's or the whole garden's, a page at a time.
package garden.butler.app

import kotlinx.coroutines.flow.update

/** The watering history: one pot's, or the whole garden's from the
 * list. The rows come from the backend already attributed, so nothing
 * here has to guess whose dose was whose. */
internal fun GardenViewModel.openDoses(potId: String?, title: String) {
    if (!addressed) return
    val parent = shown.value as? Screen.Pot
    // Not while the form has something on the wire. Back restores this
    // very snapshot, so leaving mid-save brings back a form stuck on
    // busy = true, its Save and Water greyed out for good: the
    // outcome lands on the form that is shown, and this one is not.
    // Worse over the wizard's arming POST, which would leave the board
    // reporting every 5 s with no wizard on screen to restore it.
    if (parent?.busy == true) return
    val screen = Screen.Doses(parent, potId, title)
    noteOnList.value = null
    shown.value = screen
    loadDoses(screen)
}

internal fun GardenViewModel.reloadDoses() = (shown.value as? Screen.Doses)?.let { loadDoses(it.copy(loading = true, why = null)) }

/** Single-flight, like the chart's loader: two quick pulls must not
 * race, or the slower answer lands last and quietly replaces the
 * fresher list with an older one. The job is shared with the pager,
 * so a reload also cancels a page that is on its way. */
private fun GardenViewModel.loadDoses(screen: Screen.Doses) {
    val fresh = screen.copy(loadingMore = false)
    shown.value = fresh
    dosesFlight =
        latestOnly(
            dosesFlight,
            { backend.doses(fresh.potId, DOSES_LIMIT) },
            { answer ->
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
            },
            // The list already up stays up: a failed reload is weather.
            { why -> onDoses(fresh) { it.copy(loading = false, why = why) } },
        )
}

/** The page before the oldest row on screen. Appended, never replacing:
 * the rows already read do not move under the finger. */
internal fun GardenViewModel.loadOlderDoses() {
    val screen = shown.value as? Screen.Doses ?: return
    // Not over a reload: its cursor would be anchored to a list that is
    // about to be replaced, and the row on the old boundary would be
    // stepped over and never asked for again.
    if (screen.loading || screen.loadingMore || !screen.more) return
    val cursor = screen.doses?.lastOrNull()?.let(::doseCursor) ?: return
    val asking = screen.copy(loadingMore = true, why = null)
    shown.value = asking
    dosesFlight =
        latestOnly(
            dosesFlight,
            { backend.doses(asking.potId, DOSES_LIMIT, cursor) },
            { answer ->
                onDoses(asking) {
                    it.copy(
                        doses = (it.doses ?: emptyList()) + answer.doses,
                        more = answer.doses.size >= DOSES_LIMIT,
                        loadingMore = false,
                    )
                }
            },
            { why -> onDoses(asking) { it.copy(loadingMore = false, why = why) } },
        )
}

/** An answer lands only on the history it was asked for: the user may
 * have moved on to another pot's, or back to the garden's. */
private inline fun GardenViewModel.onDoses(of: Screen.Doses, change: (Screen.Doses) -> Screen.Doses) {
    shown.update { if (it is Screen.Doses && it.potId == of.potId && it.parent?.id == of.parent?.id) change(it) else it }
}
