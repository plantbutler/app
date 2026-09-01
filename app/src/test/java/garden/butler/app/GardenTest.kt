package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                controllers = listOf(controller(lastSeen = 990, float = 1, pos = "ok")),
            )
        assertEquals(emptyList(), problems(health, nowS = 1000))
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
    fun `instant float and pos lines are deduplicated against raised alerts`() {
        val health =
            Health(
                ok = true,
                controllers = listOf(controller(lastSeen = 990, float = 0, pos = "unknown")),
                alerts = listOf(RaisedAlert("float:b1", 900)),
            )
        val found = problems(health, nowS = 1000)
        // float shows once (the raised alert), pos once (instant, not yet raised)
        assertEquals(1, found.count { "reservoir" in it })
        assertEquals(1, found.count { "manifold" in it })
    }

    @Test
    fun `a backend that says not ok is a problem`() {
        assertTrue(problems(Health(ok = false), 0).any { "not ok" in it })
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
    fun `the pot line prefers pct, falls back to raw, admits no data`() {
        assertEquals("48% · 60s ago", potLine(pot("p", pct = 48, raw = 8123, readTs = 40), 100))
        assertEquals("raw 8123 · 60s ago", potLine(pot("p", raw = 8123, readTs = 40), 100))
        assertEquals("no data yet", potLine(pot("p"), 100))
    }

    @Test
    fun `env entries drop the prefix and prefer pct`() {
        assertEquals("temp" to "231", envEntry(pot("env:temp", raw = 231)))
        assertEquals("light" to "72%", envEntry(pot("env:light", pct = 72, raw = 9)))
        assertEquals("hum" to "—", envEntry(pot("env:hum")))
    }
}
