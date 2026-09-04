package garden.butler.app

import kotlin.math.roundToInt

/** What the care lookup produced, as the words that go on screen.
 *
 * The backend words the outcome sentence (`SpeciesAnswer.note`), because it
 * is the side that knows which of the five endings happened and most of them
 * are unhappy ones. This file words what is *known* about the plant, which
 * is a different thing and, for houseplants, usually nothing.
 */

/** The same folding the backend's cache key uses, so "Ocimum_basilicum" and
 * "ocimum  basilicum" are one name here too. */
fun normaliseSpecies(text: String): String =
    text.replace('_', ' ').trim().split(Regex("\\s+")).joinToString(" ").lowercase()

/** 6.5 stays 6.5; 7.0 is 7. A trailing zero reads as precision nobody has. */
private fun phText(value: Double): String =
    if (value == value.roundToInt().toDouble()) "${value.roundToInt()}" else "$value"

/** Trefle's numbers as one line, or null when there are none worth showing.
 *
 * The scales are said to be the source's own. Ours is a straight line
 * between two calibration points — dry air and tap water — and a 7 here has
 * nothing to do with a 7% there; putting them on one screen without saying
 * which is which would invite exactly that arithmetic.
 */
fun careLine(care: Care?): String? {
    if (care == null || !care.found) return null
    val parts =
        buildList {
            care.light?.let { add("light $it/10") }
            care.humidity?.let { add("humidity $it/10") }
            val low = care.phMin
            val high = care.phMax
            when {
                low != null && high != null && low != high ->
                    add("pH ${phText(low)}–${phText(high)}")
                low != null -> add("pH ${phText(low)}")
                high != null -> add("pH ${phText(high)}")
            }
            care.tempMinC?.let { add("above ${it.roundToInt()}°C") }
        }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The common name, when the source has one and it is not the binomial
 * again. Shown beside the accepted name, never instead of it. */
fun commonName(care: Care?, accepted: String?): String? =
    care?.commonName?.takeIf {
        it.isNotBlank() && normaliseSpecies(it) != normaliseSpecies(accepted ?: "")
    }

/** The offer, in one line. The numbers first: the reason is why they are
 * what they are, not a second opinion about them. */
fun adviceLine(advice: Advice): String =
    "suggested ${advice.low}–${advice.high}%" +
        if (advice.why.isBlank()) "" else " · ${advice.why}"

/** The accepted name when it is not what was typed — a synonym the plant
 * was renamed from, or a spelling GBIF corrected. The one case worth a
 * button, because the pot should hold the name the cache is keyed on. */
fun betterName(answer: SpeciesAnswer, typed: String): String? =
    answer.accepted?.takeIf { normaliseSpecies(it) != normaliseSpecies(typed) }
