package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The wire as the backend actually speaks it, unknown keys included. */
class BackendTest {
    @Test
    fun `pots parse with nulls, proposals and unknown keys`() {
        val body =
            """
            {"pots": [
              {"id": 1, "name": "basil", "controller": "b1", "channel": 0,
               "outlet": 3, "plant_type": "basil", "dry_raw": 12000,
               "wet_raw": 4000, "mode": "auto", "enabled": 1,
               "raw": 8123, "pct": 48, "read_ts": 1788291874,
               "proposal": {"id": 17, "ml": 100, "cap_s": 10, "created_ts": 1},
               "brand_new_key": true},
              {"id": 2, "name": "env:temp", "controller": "b1", "channel": 5,
               "mode": "manual", "enabled": 1, "raw": 231, "pct": null,
               "read_ts": 1788291874, "proposal": null}
            ]}
            """.trimIndent()

        val pots = parsePots(body)
        assertEquals(2, pots.size)
        assertEquals("basil", pots[0].name)
        assertEquals(48, pots[0].pct)
        assertEquals(100, pots[0].proposal?.ml)
        assertEquals("env:temp", pots[1].name)
        assertNull(pots[1].pct)
        assertEquals(231, pots[1].raw)
        assertNull(pots[1].proposal)
    }

    @Test
    fun `health parses controllers, safety fields and raised alerts`() {
        val body =
            """
            {"ok": true, "readings": 42, "last_ts": 1788291874,
             "controllers": [{"controller": "b1", "last_seen": 1788291874,
               "next_s": null, "command": {"id": 3, "kind": "water",
               "state": "sent"}, "float": 1, "pos": "ok"}],
             "alerts": [{"key": "silent:b2", "raised_ts": 1788290000}]}
            """.trimIndent()

        val health = parseHealth(body)
        assertEquals(true, health.ok)
        assertEquals(1, health.controllers.size)
        assertEquals(1, health.controllers[0].float)
        assertEquals("ok", health.controllers[0].pos)
        assertEquals("silent:b2", health.alerts[0].key)
    }

    @Test
    fun `an empty garden parses to an empty list`() {
        assertEquals(emptyList(), parsePots("""{"pots": []}"""))
    }
}
