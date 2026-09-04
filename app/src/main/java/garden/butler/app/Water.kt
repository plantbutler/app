package garden.butler.app

/** The water-now button as pure decisions: may this pot be watered, and
 * what became of the command once it was. The backend owns the slot and
 * the log; this file only reads them back for one issued id.
 */

/** The command this form queued, and the phone clock when. */
data class Issued(val id: Long, val ts: Long)

/** The board collects a queued command on its next report and acks on the
 * one after: two default intervals plus slack is when to stop asking. */
const val FOLLOW_EVERY_MS = 15_000L
const val FOLLOW_MAX_S = 240L

private val WATER_KEYS = setOf("controller", "outlet", "dose_ml")

/** Null when the tap may go through; else the reason, most fundamental
 * first. The cooldown, the daily cap and the float gate are not here on
 * purpose: a manual command bypasses the rules, and the firmware protects. */
fun cannotWater(
    pot: Pot,
    controller: ControllerHealth?,
    nowS: Long,
    nextDefault: Int,
    dirtyKeys: Set<String>,
    cachedAtS: Long? = null,
): String? {
    // Before anything else: these numbers came off the disk. Watering on
    // them would be watering a reading nobody has confirmed, and every
    // check below is being made against a garden that may have moved.
    if (cachedAtS != null) return staleLine(cachedAtS, nowS)
    if (pot.status != ALIVE) {
        return "this pot is in the graveyard — bring it back in the form first"
    }
    val c = pot.controller?.let { boardName(it) }
    if (c == null || pot.outlet == null) return "map a controller and an outlet first"
    if (pot.doseMl == null) return "set a dose in the form first"
    if (dirtyKeys.any { it in WATER_KEYS }) {
        return "save or discard your changes first — the water goes to the stored controller, outlet and dose"
    }
    if (controller == null || controller.lastSeen == 0L) return "$c has never reported"
    if (nowS - controller.lastSeen > silentAfterS(controller.nextS, nextDefault)) {
        return "$c is silent (last reported ${agoText(controller.lastSeen, nowS)})"
    }
    controller.command?.let { return "busy: cmd ${it.id} ${it.state} on $c" }
    if (pot.proposal != null) return "a proposal is waiting above — approve it or let it expire"
    return null
}

sealed interface WaterStatus {
    data object Queued : WaterStatus

    data object Sent : WaterStatus

    data class Done(val flowMl: Int?) : WaterStatus

    data object Expired : WaterStatus

    data object NoNews : WaterStatus
}

/** Where the issued command is, read from the two places the backend shows
 * it: the pot's last_dose once handed (the richer record: acked, expired,
 * meter), else the controller's slot while queued or sent. Acked and
 * expired are definitive whenever they show; anything still open past
 * FOLLOW_MAX_S is no news, however the slot reads — a queued command that
 * old means the board is not collecting. Null when neither knows it yet —
 * unless the garden is a stale copy from before the tap, which keeps
 * saying Queued rather than "gone". nowS is the phone clock, the same one
 * that stamped issued.ts. */
fun waterStatus(
    issued: Issued,
    pot: Pot?,
    controller: ControllerHealth?,
    nowS: Long,
    stale: Boolean,
): WaterStatus? {
    val dose = pot?.lastDose?.takeIf { it.id == issued.id }
    when (dose?.state) {
        "acked" -> return WaterStatus.Done(dose.flowMl)
        "expired" -> return WaterStatus.Expired
    }
    if (nowS - issued.ts > FOLLOW_MAX_S) return WaterStatus.NoNews
    if (dose != null) return WaterStatus.Sent
    controller?.command?.takeIf { it.id == issued.id }?.let { cmd ->
        return if (cmd.state == "sent") WaterStatus.Sent else WaterStatus.Queued
    }
    return if (stale) WaterStatus.Queued else null
}

/** Honest words under the button: the hand-off takes up to two reports,
 * and an expiry is not proof that nothing poured. */
fun waterLine(status: WaterStatus, controller: String): String =
    when (status) {
        WaterStatus.Queued ->
            "queued — $controller collects it on its next report, up to about three minutes"
        WaterStatus.Sent -> "handed to $controller — it waters, then confirms on its next report"
        is WaterStatus.Done ->
            status.flowMl?.let { "done — $controller poured $it ml (meter)" }
                ?: "done — confirmed by $controller"
        WaterStatus.Expired ->
            "expired — $controller never confirmed it: maybe nothing poured, " +
                "maybe it poured and the confirmation was lost"
        WaterStatus.NoNews -> "no news after 4 min — check the controllers card"
    }

/** Whether the screen keeps polling for the issued command's fate. */
fun stillFollowing(issued: Issued?, status: WaterStatus?, nowS: Long): Boolean {
    if (issued == null) return false
    val open = status == null || status == WaterStatus.Queued || status == WaterStatus.Sent
    return open && nowS - issued.ts <= FOLLOW_MAX_S
}

/** What is on screen is the last thing the butler said, and how long ago.
 * One wording, so the banner, the disabled button and the refused action
 * all say the same thing. */
fun staleLine(cachedAtS: Long, nowS: Long): String =
    "the butler is not answering — this is what it last said, ${agoText(cachedAtS, nowS)}"

/** The one confirmation the pitch allows: what is about to happen and what
 * the rules will make of it. Nothing about what might go wrong — a failure
 * that has not happened is noise, and the status line under the button says
 * so if and when it does. */
fun waterDialogText(pot: Pot): String =
    "Water ${pot.name} with ${pot.doseMl ?: "?"} ml on " +
        "${pot.controller?.let { boardName(it) } ?: "?"} outlet " +
        "${pot.outlet ?: "?"}? Counts as today's watering."
