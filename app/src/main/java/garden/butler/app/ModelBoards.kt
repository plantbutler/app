// The one-shot taps a board answers to, from the garden list.
package garden.butler.app

/** The board actions the garden list offers all refuse the same way:
 * nothing goes on the wire from a cached garden, and the reason lands
 * where the tap was. True once it has been refused. */
private fun GardenViewModel.refusedOnList(): Boolean = staleRefusal()?.also { noteOnList.value = it } != null

internal fun GardenViewModel.resetInterval(controller: Int) {
    if (refusedOnList()) return
    resetIntervalNow(controller)
}

private fun GardenViewModel.resetIntervalNow(controller: Int) =
    act({
        val next = backend.interval(controller, 0)
        "${boardName(controller)} reports every ${next ?: "default"}s again"
    }) { noteOnList.value = it }

/** The human refilled the tank: the butler records when, and the
 * stuck-float rule has something to measure against. */
internal fun GardenViewModel.refill(controller: Int) {
    if (refusedOnList()) return
    act({
        backend.refill(controller)
        "${boardName(controller)}: refill noted"
    }) { noteOnList.value = it }
}

/** The human checked the tank (and typed the board's clearing word,
 * `latchSteps`): the butler queues water for this board again. */
internal fun GardenViewModel.resume(controller: Int) {
    if (refusedOnList()) return
    act({
        backend.resume(controller)
        "${boardName(controller)} waters again"
    }) { noteOnList.value = it }
}
