package garden.butler.app

/** Pure screen logic: what the garden list shows, split from how. All of it
 * is plain functions of (backend answers, now) so the JVM tests cover it
 * without an emulator.
 */
const val ENV_PREFIX = "env:"

data class Garden(
    val pots: List<Pot>,
    val env: List<Pot>,
    /** Disabled pots stay reachable: a pot is switched off to be edited or
     * re-enabled, not forgotten. */
    val disabled: List<Pot>,
    val problems: List<String>,
    val health: Health,
)

/** The pot as the last good read has it; null once it vanished, in which
 * case the open form keeps rendering from its own snapshot. Every screen
 * keys on the id, so a rename moves a nickname and not a pot. */
fun Garden.potById(id: String): Pot? =
    if (id.isEmpty()) null else (pots + disabled + env).firstOrNull { it.id == id }

/** By nickname, for the two places that only have one: the wizard's "is
 * this pot still on the backend" check and the create-time name clash. */
fun Garden.potNamed(name: String): Pot? =
    (pots + disabled + env).firstOrNull { it.name == name }

/** A pot's key in the list. The id, which a rename does not move; a
 * backend too old to send one would give every row the same empty key, and
 * a LazyColumn throws on a duplicate, so fall back to the name there
 * rather than take the whole list down. */
fun potKey(pot: Pot): String = pot.id.ifEmpty { "name:" + pot.name }

fun splitGarden(all: List<Pot>, health: Health, nowS: Long): Garden {
    val enabled = all.filter { it.enabled == 1 }
    return Garden(
        pots = enabled.filterNot { it.name.startsWith(ENV_PREFIX) },
        env = enabled.filter { it.name.startsWith(ENV_PREFIX) },
        disabled = all.filter { it.enabled != 1 },
        problems = problems(health, nowS),
        health = health,
    )
}

/** The health strip: the backend's own raised alerts (debounced, the source
 * of truth) plus the instant states worth showing on their own —
 * deduplicated so one empty reservoir is one line. The silent check runs
 * app-side too: the backend's ticker only pages when ntfy is configured,
 * and a dark controller must not render a strip-free "healthy" screen. */
fun problems(health: Health, nowS: Long): List<String> {
    val found = mutableListOf<String>()
    val raised = health.alerts.map { it.key }.toSet()
    health.alerts.mapTo(found) { describeAlert(it.key, nowS, it.raisedTs) }
    for (c in health.controllers) {
        val threshold = silentAfterS(c.nextS, health.nextDefault)
        if (c.lastSeen == 0L) {
            found += "${c.controller} has never reported"
        } else if (
            nowS - c.lastSeen > threshold && "silent:${c.controller}" !in raised
        ) {
            found += "${c.controller} last reported ${agoText(c.lastSeen, nowS)}"
        }
        if (c.float == 0 && "float:${c.controller}" !in raised) {
            found += "reservoir empty on ${c.controller}"
        }
        if (c.pos == "unknown" && "pos:${c.controller}" !in raised) {
            found += "${c.controller} lost its manifold position"
        }
    }
    return found
}

fun describeAlert(key: String, nowS: Long = 0, raisedTs: Long = 0): String {
    val parts = key.split(":")
    val since = if (raisedTs in 1..nowS) " (${agoText(raisedTs, nowS)})" else ""
    return when (parts[0]) {
        "silent" -> "${parts.getOrElse(1) { "?" }} has gone silent$since"
        "float" -> "reservoir empty on ${parts.getOrElse(1) { "?" }}$since"
        "pos" -> "${parts.getOrElse(1) { "?" }} lost its manifold position$since"
        "sensor" ->
            "sensor ch${parts.getOrElse(2) { "?" }} on ${parts.getOrElse(1) { "?" }} " +
                "stopped reporting$since"
        "fields" ->
            "${parts.getOrElse(2) { "?" }} stopped sending ${parts.getOrElse(1) { "?" }}=$since"
        else -> key
    }
}

fun agoText(thenS: Long, nowS: Long): String {
    val s = (nowS - thenS).coerceAtLeast(0)
    return when {
        s < 90 -> "${s}s ago"
        s < 90 * 60 -> "${s / 60}min ago"
        s < 48 * 3600 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/** The one supporting line under a pot's name. */
fun potLine(pot: Pot, nowS: Long): String {
    val seen = pot.readTs?.let { agoText(it, nowS) }
    return when {
        pot.pct != null && seen != null -> "${pot.pct}% · $seen"
        pot.raw != null && seen != null -> "raw ${pot.raw} · $seen"
        else -> "no data yet"
    }
}

const val ENV_STALE_S = 7200L

/** A stale env reading must say so: a dead temperature sensor would
 * otherwise display its last number as current, forever. */
fun envStale(pot: Pot, nowS: Long): String? {
    val readTs = pot.readTs ?: return "never read"
    return if (nowS - readTs > ENV_STALE_S) agoText(readTs, nowS) else null
}

/** An env: pot's display pair: label without the prefix, value. */
fun envEntry(pot: Pot): Pair<String, String> {
    val value =
        when {
            pot.pct != null -> "${pot.pct}%"
            pot.raw != null -> "${pot.raw}"
            else -> "—"
        }
    return pot.name.removePrefix(ENV_PREFIX) to value
}

/** One line per controller on the health list: "b1 · seen 40s ago · every
 * 60s · float ok · pos ok", plus the command in flight when there is one. */
fun controllerLine(c: ControllerHealth, nowS: Long, defaultNextS: Int): String {
    val seen = if (c.lastSeen == 0L) "never reported" else "seen ${agoText(c.lastSeen, nowS)}"
    val every = c.nextS?.let { "every ${it}s (override)" } ?: "every ${defaultNextS}s"
    val float =
        when (c.float) {
            null -> "float ?"
            0 -> "float EMPTY"
            else -> "float ok"
        }
    val pos = c.pos?.let { "pos $it" } ?: "pos ?"
    val parts = mutableListOf(c.controller, seen, every, float, pos)
    c.command?.let { cmd ->
        val kind = if (cmd.kind == "water") "" else " ${cmd.kind}"
        parts += "cmd ${cmd.id}$kind ${cmd.state}"
    }
    return parts.joinToString(" · ")
}

fun hasOverride(c: ControllerHealth): Boolean = c.nextS != null

/** "proposal: 100 ml, cap 10 s, made 3min ago" — what learning mode wants to
 * pour and is waiting for a tap on. */
fun proposalLine(p: Proposal, nowS: Long): String {
    val parts = mutableListOf("proposal: ${p.ml?.toString() ?: "?"} ml")
    p.capS?.let { parts += "cap $it s" }
    p.createdTs?.let { parts += "made ${agoText(it, nowS)}" }
    return parts.joinToString(", ")
}

/** "<source> dose 100 ml · 40min ago · confirmed, meter 96 ml". The verdict is
 * not here: the chips beside it show it. */
fun doseLine(d: LastDose, nowS: Long): String {
    val ml = d.ml?.toString() ?: "?"
    val parts = mutableListOf(listOfNotNull(d.source, "dose $ml ml").joinToString(" "))
    (d.ackedTs ?: d.sentTs)?.let { parts += agoText(it, nowS) }
    parts +=
        when (d.state) {
            "sent" -> "handed over, waiting for the board to confirm"
            "expired" -> "expired, the board never confirmed it"
            "acked" -> "confirmed" + (d.flowMl?.let { ", meter $it ml" } ?: "")
            else -> d.state + (d.flowMl?.let { ", meter $it ml" } ?: "")
        }
    return parts.joinToString(" · ")
}

/** A dose is judged after the water has soaked in and before the memory of
 * the plant has faded. */
const val SOAK_S = 1800L
const val VERDICT_WINDOW_S = 48L * 3600

fun needsVerdict(d: LastDose?, nowS: Long): Boolean {
    if (d == null || d.state != "acked" || d.verdict != null) return false
    val age = nowS - (d.ackedTs ?: d.sentTs ?: return false)
    return age > SOAK_S && age <= VERDICT_WINDOW_S
}

/** The nudge under a pot's row while its last dose waits for a verdict. */
fun rowNote(pot: Pot, nowS: Long): String? {
    if (pot.enabled != 1) return null
    val dose = pot.lastDose?.takeIf { needsVerdict(it, nowS) } ?: return null
    val ts = dose.ackedTs ?: dose.sentTs ?: return null
    return "dose ${agoText(ts, nowS)}, not judged yet"
}

/** What the backend's rules need before learning or auto can do anything,
 * so the mode flip explains itself instead of silently doing nothing. The
 * rules also gate on the board's float and pos, which the firmware does
 * not send yet: that gap names itself rather than being read as "fine". */
fun learningGaps(pot: Pot, controller: ControllerHealth? = null): List<String> {
    val gaps = mutableListOf<String>()
    if (pot.controller == null) gaps += "a controller"
    if (pot.channel == null) gaps += "a channel"
    if (pot.outlet == null) gaps += "an outlet"
    if (pot.dryRaw == null || pot.wetRaw == null) gaps += "calibration (dry and wet)"
    if (pot.targetLowPct == null) gaps += "a target low %"
    if (pot.doseMl == null) gaps += "a dose"
    if (pot.controller != null && (controller?.float != 1 || controller.pos != "ok")) {
        gaps +=
            "the board reporting float=1 and pos=ok (now float ${controller?.float ?: "?"}, " +
                "pos ${controller?.pos ?: "?"})"
    }
    return gaps
}

fun verdictLabel(v: String): String =
    when (v) {
        "ok" -> "ok"
        "too_much" -> "too much"
        "too_little" -> "too little"
        else -> v
    }
