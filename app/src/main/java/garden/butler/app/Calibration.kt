package garden.butler.app

import kotlin.math.abs
import kotlin.math.ceil

/** The recalibration wizard as a pure reducer: sensor in the air, tap; in a
 * glass of water, tap. The screen owns the clock, the polling and the
 * network; this file only decides what the next state is, so the JVM tests
 * can walk every path without a board.
 */
const val FAST_NEXT_S = 5
const val FRESH_FAST_S = 15L
const val FRESH_SLOW_S = 150L
const val POLL_MS = 2000L
const val SETTLE_SHOWN = 3
const val CLOSE_COUNTS = 800L

/** `seenS` is the phone clock when the reading was first polled: freshness
 * compares phone to phone, so a phone clock off the backend's by minutes
 * neither blocks every tap nor lets a stale report through. `readTs` stays
 * the backend's, for the newer-than-since check. */
data class Reading(val raw: Long, val readTs: Long, val seenS: Long)

/** `prevNextS` is the override that stood before the wizard armed (null =
 * default): the driver restores it on the way out, whichever way out. */
sealed interface CalState {
    val prevNextS: Int?

    data class SpeedingUp(
        override val prevNextS: Int?,
        val startTs: Long,
        val timeoutS: Long,
        val seen: List<Reading> = emptyList(),
    ) : CalState

    /** Carries timeoutS so a retry waits as long as the first attempt did. */
    data class Stalled(
        override val prevNextS: Int?,
        val lastReadTs: Long?,
        val timeoutS: Long,
    ) : CalState

    data class Air(
        override val prevNextS: Int?,
        val freshS: Long,
        val since: Long,
        val seen: List<Reading> = emptyList(),
    ) : CalState

    data class Water(
        override val prevNextS: Int?,
        val freshS: Long,
        val since: Long,
        val dry: Long,
        val seen: List<Reading> = emptyList(),
    ) : CalState

    /** Carries freshS so "back to air" keeps the slow or fast mode. */
    data class Review(
        override val prevNextS: Int?,
        val freshS: Long,
        val dry: Long,
        val wet: Long,
        val refused: String? = null,
    ) : CalState

    data class Saving(
        override val prevNextS: Int?,
        val freshS: Long,
        val dry: Long,
        val wet: Long,
    ) : CalState

    data class Finished(override val prevNextS: Int?, val dry: Long, val wet: Long) : CalState

    data class Cancelled(override val prevNextS: Int?) : CalState
}

sealed interface CalEvent {
    data class Seen(val raw: Long, val readTs: Long) : CalEvent

    data object Tick : CalEvent

    data object Tap : CalEvent

    data object Save : CalEvent

    data object Saved : CalEvent

    data class Refused(val text: String) : CalEvent

    data object Retry : CalEvent

    data object ContinueSlow : CalEvent

    data object BackToAir : CalEvent

    data object Cancel : CalEvent
}

/** A board on a 300 s override needs longer than a default one to even hear
 * the knob: it only reads next= with its next report. */
fun calStart(prevNextS: Int?, nowS: Long, defaultNextS: Int): CalState.SpeedingUp =
    CalState.SpeedingUp(
        prevNextS = prevNextS,
        startTs = nowS,
        timeoutS = maxOf(150L, ceil(2.5 * (prevNextS ?: defaultNextS)).toLong()),
    )

/** Total: every (state, event) pair answers a state; the pairs that mean
 * nothing answer `s` unchanged. Terminal states ignore everything. */
fun calStep(s: CalState, e: CalEvent, nowS: Long): CalState =
    when (s) {
        is CalState.SpeedingUp ->
            when (e) {
                is CalEvent.Seen -> {
                    val seen = remember(s.seen, e, nowS)
                    if (obeyed(seen)) {
                        // Air starts empty, and starts from the newest report
                        // that proved the board sped up. Those reports are
                        // evidence about the board's pace, not about air: the
                        // sensor was wherever it was, and "hold it in the AIR"
                        // has not been shown yet. Folding them into the median
                        // would calibrate dry against soil, and a pot whose
                        // scale says it is dry gets watered.
                        CalState.Air(s.prevNextS, FRESH_FAST_S, seen.first().readTs)
                    } else {
                        s.copy(seen = seen)
                    }
                }
                CalEvent.Tick ->
                    if (nowS - s.startTs > s.timeoutS) {
                        CalState.Stalled(s.prevNextS, s.seen.firstOrNull()?.readTs, s.timeoutS)
                    } else {
                        s
                    }
                CalEvent.Cancel -> CalState.Cancelled(s.prevNextS)
                else -> s
            }
        is CalState.Stalled ->
            when (e) {
                CalEvent.Retry -> CalState.SpeedingUp(s.prevNextS, nowS, s.timeoutS)
                CalEvent.ContinueSlow -> CalState.Air(s.prevNextS, FRESH_SLOW_S, nowS)
                CalEvent.Cancel -> CalState.Cancelled(s.prevNextS)
                else -> s
            }
        is CalState.Air ->
            when (e) {
                is CalEvent.Seen -> s.copy(seen = remember(s.seen, e, nowS))
                CalEvent.Tap ->
                    if (canTap(s, nowS)) {
                        // The newest report is what the next step must be
                        // newer than; the value is the median of the few
                        // this step has actually seen.
                        val dry = medianRaw(tapSamples(s))!!
                        CalState.Water(s.prevNextS, s.freshS, s.seen.first().readTs, dry)
                    } else {
                        s
                    }
                CalEvent.Cancel -> CalState.Cancelled(s.prevNextS)
                else -> s
            }
        is CalState.Water ->
            when (e) {
                is CalEvent.Seen -> s.copy(seen = remember(s.seen, e, nowS))
                CalEvent.Tap ->
                    if (canTap(s, nowS)) {
                        CalState.Review(s.prevNextS, s.freshS, s.dry, medianRaw(tapSamples(s))!!)
                    } else {
                        s
                    }
                CalEvent.BackToAir -> CalState.Air(s.prevNextS, s.freshS, nowS)
                CalEvent.Cancel -> CalState.Cancelled(s.prevNextS)
                else -> s
            }
        is CalState.Review ->
            when (e) {
                CalEvent.Save -> CalState.Saving(s.prevNextS, s.freshS, s.dry, s.wet)
                CalEvent.BackToAir -> CalState.Air(s.prevNextS, s.freshS, nowS)
                is CalEvent.Refused -> s.copy(refused = e.text)
                CalEvent.Cancel -> CalState.Cancelled(s.prevNextS)
                else -> s
            }
        is CalState.Saving ->
            when (e) {
                CalEvent.Saved -> CalState.Finished(s.prevNextS, s.dry, s.wet)
                is CalEvent.Refused ->
                    CalState.Review(s.prevNextS, s.freshS, s.dry, s.wet, refused = e.text)
                else -> s // Cancel included: the POST is on the wire
            }
        is CalState.Finished, is CalState.Cancelled -> s
    }

/** What a tap would capture: the distinct reports this step has seen, newest
 * first, at most three. Reports from before the step began are excluded —
 * that is what stops the one that served the dry end serving the wet end
 * too. `remember` already keeps these distinct by `readTs`, so three samples
 * are three reports and never one report counted three times. */
fun tapSamples(s: CalState): List<Reading> =
    when (s) {
        is CalState.Air -> s.seen.filter { it.readTs > s.since }
        is CalState.Water -> s.seen.filter { it.readTs > s.since }
        else -> emptyList()
    }.take(SETTLE_SHOWN)

/** The median of what a tap captures, or null with nothing to capture.
 *
 * Median, not mean of everything: one noisy sample used to set a pot's whole
 * scale until somebody recalibrated, and a median throws the outlier away
 * where a mean would fold it in. Two samples have no middle one, so they
 * average — which is what the median of an even set is. */
fun medianRaw(samples: List<Reading>): Long? {
    if (samples.isEmpty()) return null
    val sorted = samples.map { it.raw }.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid] + 1) / 2
}

/** The same report polled again is not a new reading. */
private fun remember(seen: List<Reading>, e: CalEvent.Seen, nowS: Long): List<Reading> =
    if (seen.firstOrNull()?.readTs == e.readTs) {
        seen
    } else {
        (listOf(Reading(e.raw, e.readTs, nowS)) + seen).take(SETTLE_SHOWN)
    }

/** Two reports that close prove the board took the knob; the first report
 * after it still arrives at the old pace. */
private fun obeyed(seen: List<Reading>): Boolean =
    seen.size >= 2 && abs(seen[0].readTs - seen[1].readTs) <= FRESH_FAST_S

/** A tap needs a reading that is live and newer than the step began: the
 * report that served the dry end must never serve the wet one too. */
fun canTap(s: CalState, nowS: Long): Boolean {
    val (freshS, since, seen) =
        when (s) {
            is CalState.Air -> Triple(s.freshS, s.since, s.seen)
            is CalState.Water -> Triple(s.freshS, s.since, s.seen)
            else -> return false
        }
    val newest = seen.firstOrNull() ?: return false
    return newest.readTs > since && nowS - newest.seenS <= freshS
}

/** How many reports this endpoint has, and what a tap would do with them.
 * Says it rather than hiding the tap: one reading is a usable calibration,
 * it is just a worse one than three. */
fun settleLine(samples: Int): String =
    when (samples) {
        0 -> "no reading yet"
        SETTLE_SHOWN -> "$SETTLE_SHOWN of $SETTLE_SHOWN readings — tapping takes their median"
        1 -> "1 of $SETTLE_SHOWN readings — tapping now takes just this one"
        else -> "$samples of $SETTLE_SHOWN readings — tapping now takes the median of these"
    }

/** A hint, never a refusal: the backend still decides what it accepts. */
fun calHint(dry: Long, wet: Long): String? =
    when {
        abs(dry - wet) < CLOSE_COUNTS ->
            "dry and wet are very close — is the sensor plugged in?"
        dry < wet -> "dry reads lower than wet — that is unusual for this probe"
        else -> null
    }

/** Null when the wizard may start; else the reason, most fundamental first.
 * The rules run on every report for learning/auto pots, so a sensor held
 * in the air would get its pot watered. */
fun canCalibrate(
    pot: Pot,
    controller: ControllerHealth?,
    nowS: Long,
    defaultNextS: Int,
): String? {
    val c = pot.controller
    if (c == null || pot.channel == null) return "map a controller and a channel first"
    if (pot.mode != "manual") {
        return "set the pot to manual first — the rules would water a sensor held in the air"
    }
    if (controller == null || controller.lastSeen == 0L) return "$c has never reported"
    val threshold = maxOf(600L, 3L * (controller.nextS ?: defaultNextS))
    if (nowS - controller.lastSeen > threshold) {
        return "$c is silent (last reported ${agoText(controller.lastSeen, nowS)})"
    }
    return null
}
