// What the butler knows about the plant: the species lookup and the band it offers.
package garden.butler.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two fields the offer would write. An unsaved edit to either of them
 * would be silently overwritten by accepting it. */
private val TARGET_KEYS = setOf("target_low_pct", "target_high_pct")

/** Take the kind the lookup offered. A tap, because the field already
 * held an answer somebody typed and a guess off a botanical family does
 * not get to overwrite one. */
internal fun GardenViewModel.useKind(kind: String) = edit("plant_type", kind)

/** Ask what is known about the species in the form. Reads only — the
 * answer is words on screen, and whatever the pot ends up storing is
 * typed or tapped afterwards, so a stale cache is no reason to refuse. */
internal fun GardenViewModel.lookUpSpecies() {
    val form = shown.value as? Screen.Pot ?: return
    val typed = form.draft["species"].orEmpty().trim()
    if (typed.isBlank()) {
        return onPot(form) { it.copy(lookup = SpeciesAnswer(note = "type a species first")) }
    }
    onPot(form) { it.copy(lookingUp = true, lookup = null) }
    background {
        val answer =
            try {
                withContext(Dispatchers.IO) { backend.species(normaliseSpecies(typed)) }
            } catch (why: CancellationException) {
                throw why
            } catch (why: Exception) {
                SpeciesAnswer(query = typed, note = why.reason())
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
internal fun GardenViewModel.useName(name: String) = onPot { it.copy(draft = it.draft + ("species" to tokenize(name))) }

/** Somebody recognised their plant in the shortlist. Put its name in the
 * form and ask again: the second question resolves exactly, and both
 * halves of the answer are already cached, so it costs one round trip. */
internal fun GardenViewModel.pickCandidate(name: String) {
    useName(name)
    lookUpSpecies()
}

/** Accept the offered band: an ordinary pot edit and nothing more, the
 * same POST /pot the form makes, carrying two numbers a person has just
 * agreed to. */
internal fun GardenViewModel.applyAdvice(advice: Advice) {
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
internal fun GardenViewModel.dismissAdvice() {
    val form = shown.value as? Screen.Pot ?: return
    val id = form.id ?: return
    staleRefusal()?.let { why -> return noteOnPot(form, why) }
    act({
        backend.dismissAdvice(id)
        "not now"
    }) { noteOnPot(form, it) }
}
