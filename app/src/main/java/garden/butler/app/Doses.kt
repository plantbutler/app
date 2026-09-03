package garden.butler.app

/** The watering history as lines: what a dose row says, and whether it is
 * one of the rows the list exists for. All pure functions of (dose, now),
 * so the JVM tests cover the whole screen's words without an emulator.
 */

/** How much history one look asks for. The table is never pruned, so the
 * list is bounded rather than complete; the screen says so. */
const val DOSES_LIMIT = 50

/** A dose the meter voted against: it counted less than half of what was
 * asked. The same rule the backend's dose alert uses (`2 * flow_ml < ml`),
 * deliberately — two thresholds for one symptom would disagree in public. */
fun flowedShort(dose: Dose): Boolean {
    val asked = dose.ml ?: return false
    val counted = dose.flowMl ?: return false
    return 2 * counted < asked
}

/** Why this row is worth a second look, or null when it is an ordinary
 * dose. The pitch's own rabbit hole: the interesting row is the one that
 * expired, was never acked, or flowed short, and it has to read
 * differently rather than be filtered out. */
fun doseTrouble(dose: Dose): String? =
    when {
        dose.state == "expired" ->
            "expired — the board never confirmed it, so nobody knows whether it poured"
        dose.state == "failed" -> "the board reported it failed"
        dose.state == "acked" && flowedShort(dose) ->
            "the meter counted ${dose.flowMl} of ${dose.ml} ml"
        else -> null
    }

/** "100 ml · 3h ago · confirmed, meter 96 ml" — the row's own line. Worded
 * like the pot screen's dose card, so the same dose reads the same in both
 * places, and in words rather than the wire's: "ack" is what the board
 * sends, not something a person should have to know. */
fun doseHistoryLine(dose: Dose, nowS: Long): String {
    val parts = mutableListOf<String>()
    parts += if (dose.kind != "water") dose.kind else dose.ml?.let { "$it ml" } ?: "? ml"
    (dose.ackedTs ?: dose.sentTs ?: dose.createdTs)?.let { parts += agoText(it, nowS) }
    parts +=
        when (dose.state) {
            "queued" -> "queued, never handed out"
            "sent" -> "handed to the board, waiting for it to confirm"
            "expired" -> "expired, the board never confirmed it"
            "acked" -> "confirmed" + (dose.flowMl?.let { ", meter $it ml" } ?: "")
            else -> dose.state + (dose.flowMl?.let { ", meter $it ml" } ?: "")
        }
    return parts.joinToString(" · ")
}

/** Whose dose it was, for the garden-wide list. A dose no mapping window
 * claims says so instead of borrowing the name of whoever hangs on that
 * hose now — which is the whole reason the windows exist. */
fun doseWho(dose: Dose): String =
    dose.potName
        ?: if (dose.sentTs == null) {
            // Never handed out, so there is nothing to attribute yet —
            // which is not the same as the windows saying nobody was there.
            "not handed out yet"
        } else {
            "no pot on that hose then"
        }

/** The source, spelt for a human: who asked for this water. */
fun doseSource(dose: Dose): String? =
    when (dose.source) {
        null -> null
        "manual" -> "by hand"
        "rules" -> "by the rules"
        else -> dose.source
    }
