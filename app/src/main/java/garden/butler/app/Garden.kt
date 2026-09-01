package garden.butler.app

/** Pure screen logic: what the garden list shows, split from how. All of it
 * is plain functions of (backend answers, now) so the JVM tests cover it
 * without an emulator.
 */
const val ENV_PREFIX = "env:"

data class Garden(
    val pots: List<Pot>,
    val env: List<Pot>,
    val problems: List<String>,
)

fun splitGarden(all: List<Pot>, health: Health, nowS: Long): Garden {
    val enabled = all.filter { it.enabled == 1 }
    return Garden(
        pots = enabled.filterNot { it.name.startsWith(ENV_PREFIX) },
        env = enabled.filter { it.name.startsWith(ENV_PREFIX) },
        problems = problems(health, nowS),
    )
}

/** The health strip: the backend's own raised alerts (debounced, the source
 * of truth) plus the two instant states worth showing before the ticker's
 * debounce catches up — deduplicated so one empty reservoir is one line. */
fun problems(health: Health, nowS: Long): List<String> {
    val found = mutableListOf<String>()
    if (!health.ok) found += "the backend is not ok"
    val raised = health.alerts.map { it.key }.toSet()
    health.alerts.mapTo(found) { describeAlert(it.key, nowS, it.raisedTs) }
    for (c in health.controllers) {
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
