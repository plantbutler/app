package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun pot(
    name: String,
    enabled: Int = 1,
    pct: Int? = null,
    raw: Long? = null,
    readTs: Long? = null,
) = Pot(name = name, enabled = enabled, pct = pct, raw = raw, readTs = readTs)

private fun controller(
    name: String = "b1",
    lastSeen: Long = 0,
    nextS: Int? = null,
    float: Int? = null,
    pos: String? = null,
) = ControllerHealth(name, lastSeen, nextS, float, pos)

class GardenTest {
    @Test
    fun `env pots split off and disabled pots vanish`() {
        val garden =
            splitGarden(
                listOf(
                    pot("basil"),
                    pot("env:temp"),
                    pot("cactus", enabled = 0),
                ),
                Health(ok = true),
                nowS = 1000,
            )
        assertEquals(listOf("basil"), garden.pots.map { it.name })
        assertEquals(listOf("env:temp"), garden.env.map { it.name })
    }

    @Test
    fun `a healthy garden has no problems`() {
        val health =
            Health(
                ok = true,
                controllers =
                    listOf(controller(lastSeen = 990, float = 1, pos = "ok")),
            )
        assertEquals(emptyList(), problems(health, nowS = 1000))
    }

    @Test
    fun `null safety fields are today's live wire shape and raise nothing`() {
        // The firmware does not send float=/pos= yet: /health answers null
        // for both on every refresh. A comparison slip (!= 1) would put a
        // permanent false strip on the phone.
        val health =
            Health(
                ok = true,
                controllers = listOf(controller(lastSeen = 990, float = null, pos = null)),
            )
        assertEquals(emptyList(), problems(health, nowS = 1000))
    }

    @Test
    fun `a controller the backend cannot page for still shows silent here`() {
        // The backend's silent: alert only exists when ntfy is configured;
        // the strip computes silence itself from last_seen.
        val health = Health(ok = true, controllers = listOf(controller(lastSeen = 300)))
        val found = problems(health, nowS = 1000)
        assertEquals(listOf("b1 last reported 11min ago"), found)
    }

    @Test
    fun `the app-side silent check respects the interval and dedups the alert`() {
        // Threshold is max(600, 3 * next_s): 700 s of silence at next=300 is fine.
        val slow =
            Health(ok = true, controllers = listOf(controller(lastSeen = 300, nextS = 300)))
        assertEquals(emptyList(), problems(slow, nowS = 1000))

        // And when the backend already raised silent:b1, no second line.
        val paged =
            Health(
                ok = true,
                controllers = listOf(controller(lastSeen = 300)),
                alerts = listOf(RaisedAlert("silent:b1", 950)),
            )
        val found = problems(paged, nowS = 1000)
        assertEquals(1, found.count { "b1" in it })
    }

    @Test
    fun `a configured but never-heard controller is called out`() {
        val health = Health(ok = true, controllers = listOf(controller(lastSeen = 0)))
        assertEquals(listOf("b1 has never reported"), problems(health, nowS = 1000))
    }

    @Test
    fun `raised alerts become readable lines`() {
        assertEquals("b1 has gone silent", describeAlert("silent:b1"))
        assertEquals("reservoir empty on b1", describeAlert("float:b1"))
        assertEquals("b1 lost its manifold position", describeAlert("pos:b1"))
        assertEquals("sensor ch0 on b1 stopped reporting", describeAlert("sensor:b1:0"))
        assertEquals("b1 stopped sending float=", describeAlert("fields:float:b1"))
        assertEquals("weird:key", describeAlert("weird:key"))
    }

    @Test
    fun `a raised alert carries how long it has stood`() {
        assertEquals(
            "reservoir empty on b1 (50s ago)",
            describeAlert("float:b1", nowS = 1000, raisedTs = 950),
        )
    }

    @Test
    fun `instant float and pos lines are deduplicated against raised alerts`() {
        val health =
            Health(
                ok = true,
                controllers =
                    listOf(controller(lastSeen = 990, float = 0, pos = "unknown")),
                alerts = listOf(RaisedAlert("float:b1", 900)),
            )
        val found = problems(health, nowS = 1000)
        // float shows once (the raised alert), pos once (instant, not yet raised)
        assertEquals(1, found.count { "reservoir" in it })
        assertEquals(1, found.count { "manifold" in it })
    }

    @Test
    fun `ago text picks sane units`() {
        assertEquals("45s ago", agoText(55, 100))
        assertEquals("10min ago", agoText(0, 600))
        assertEquals("3h ago", agoText(0, 3 * 3600 + 100))
        assertEquals("3d ago", agoText(0, 3 * 86400 + 100))
        assertEquals("0s ago", agoText(200, 100)) // clock skew never goes negative
    }

    @Test
    fun `ago text unit boundaries sit where designed`() {
        assertEquals("89s ago", agoText(0, 89))
        assertEquals("1min ago", agoText(0, 91))
        assertEquals("89min ago", agoText(0, 89 * 60))
        assertEquals("1h ago", agoText(0, 91 * 60))
        assertEquals("47h ago", agoText(0, 47 * 3600))
        assertEquals("2d ago", agoText(0, 49 * 3600))
    }

    @Test
    fun `the pot line prefers pct, falls back to raw, admits no data`() {
        assertEquals(
            "48% · 60s ago",
            potLine(pot("p", pct = 48, raw = 8123, readTs = 40), 100),
        )
        assertEquals("raw 8123 · 60s ago", potLine(pot("p", raw = 8123, readTs = 40), 100))
        assertEquals("no data yet", potLine(pot("p"), 100))
    }

    @Test
    fun `env entries drop the prefix and prefer pct`() {
        assertEquals("temp" to "231", envEntry(pot("env:temp", raw = 231)))
        assertEquals("light" to "72%", envEntry(pot("env:light", pct = 72, raw = 9)))
        assertEquals("hum" to "—", envEntry(pot("env:hum")))
    }

    @Test
    fun `a stale env reading says so instead of posing as current`() {
        val fresh = pot("env:temp", raw = 231, readTs = 1000)
        assertNull(envStale(fresh, nowS = 1000 + ENV_STALE_S))
        assertEquals("3h ago", envStale(fresh, nowS = 1000 + 3 * 3600 + 100))
        assertEquals("never read", envStale(pot("env:temp"), nowS = 1000))
    }
}
