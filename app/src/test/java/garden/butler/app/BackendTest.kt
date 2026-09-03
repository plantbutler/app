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
              {"id": "pot-3f9a21", "name": "basil", "species": "Ocimum_basilicum",
               "controller": "b1", "channel": 0,
               "outlet": 3, "plant_type": "basil", "dry_raw": 12000,
               "wet_raw": 4000, "mode": "auto", "enabled": 1,
               "raw": 8123, "pct": 48, "read_ts": 1788291874,
               "proposal": {"id": 17, "ml": 100, "cap_s": 10, "created_ts": 1},
               "brand_new_key": true},
              {"id": "pot-7c1b04", "name": "env:temp", "controller": "b1", "channel": 5,
               "mode": "manual", "enabled": 1, "raw": 231, "pct": null,
               "read_ts": 1788291874, "proposal": null}
            ]}
            """.trimIndent()

        val pots = parsePots(body)
        assertEquals(2, pots.size)
        assertEquals("pot-3f9a21", pots[0].id)
        assertEquals("Ocimum_basilicum", pots[0].species)
        assertEquals("basil", pots[0].name)
        assertEquals(48, pots[0].pct)
        assertEquals(100, pots[0].proposal?.ml)
        assertEquals("pot-7c1b04", pots[1].id)
        assertNull(pots[1].species)
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

    @Test
    fun `a pot with every column, a full proposal and a judged dose parses`() {
        val body =
            """
            {"pots": [{"id": "pot-3f9a21", "name": "basil", "species": "Ocimum_basilicum",
              "controller": "b1", "channel": 0,
              "outlet": 3, "plant_type": "basil", "plant_size": "small",
              "pot_size": "12cm", "soil": "loam", "dry_raw": 12000, "wet_raw": 4000,
              "target_low_pct": 30, "target_high_pct": 60, "dose_ml": 100,
              "mode": "learning", "cooldown_h": 12, "daily_cap_ml": 300,
              "enabled": 1, "raw": 8123, "pct": 48, "read_ts": 1788291874,
              "proposal": {"id": 17, "ml": 100, "cap_s": 10, "created_ts": 1788291000},
              "last_dose": {"id": 16, "ml": 100, "cap_s": 10, "flow_ml": 96,
                "state": "acked", "source": "manual", "sent_ts": 1788200000,
                "acked_ts": 1788200100, "verdict": "too_much"}}]}
            """.trimIndent()

        val pot = parsePots(body).single()
        assertEquals("pot-3f9a21", pot.id)
        assertEquals("Ocimum_basilicum", pot.species)
        assertEquals("small", pot.plantSize)
        assertEquals("12cm", pot.potSize)
        assertEquals("loam", pot.soil)
        assertEquals(60, pot.targetHighPct)
        assertEquals(100, pot.doseMl)
        assertEquals(12, pot.cooldownH)
        assertEquals(300, pot.dailyCapMl)
        assertEquals("learning", pot.mode)
        assertEquals(Proposal(17, 100, 10, 1788291000), pot.proposal)
        assertEquals(
            LastDose(16, 100, 10, 96, "acked", "manual", 1788200000, 1788200100, "too_much"),
            pot.lastDose,
        )
    }

    @Test
    fun `last_dose null and absent both mean no dose`() {
        val pots =
            parsePots(
                """{"pots": [{"name": "a", "last_dose": null}, {"name": "b"}]}""",
            )
        assertNull(pots[0].lastDose)
        assertNull(pots[1].lastDose)
    }

    @Test
    fun `health parses the command in flight and the default interval`() {
        val body =
            """
            {"ok": true, "next_default": 30,
             "controllers": [{"controller": "b1", "last_seen": 5,
               "command": {"id": 17, "kind": "stop", "state": "queued"}}]}
            """.trimIndent()

        val health = parseHealth(body)
        assertEquals(30, health.nextDefault)
        assertEquals(InFlight(17, "stop", "queued"), health.controllers[0].command)
    }

    @Test
    fun `an older backend without next_default means 60`() {
        assertEquals(60, parseHealth("""{"ok": true}""").nextDefault)
    }

    @Test
    fun `the interval answer is next= or nothing`() {
        assertEquals(120, parseNextAnswer("next=120\n"))
        assertNull(parseNextAnswer("pot=basil"))
        assertNull(parseNextAnswer(""))
    }

    @Test
    fun `history parses its window, bucket and points`() {
        val body =
            """
            {"controller": "b1", "channel": 0, "since": 1788205474, "to": 1788291874,
             "bucket_s": 300, "points": [
               {"ts": 1788205500, "raw": 8123, "lo": 8100, "hi": 8150, "n": 5},
               {"ts": 1788205800, "raw": 8130, "lo": null, "hi": null, "n": 1, "new_key": 1}]}
            """.trimIndent()

        val history = parseHistory(body)
        assertEquals("b1", history.controller)
        assertEquals(0, history.channel)
        assertEquals(1788205474, history.since)
        assertEquals(1788291874, history.to)
        assertEquals(300, history.bucketS)
        assertEquals(HistoryPoint(1788205500, 8123, 8100, 8150, 5), history.points[0])
        assertEquals(HistoryPoint(1788205800, 8130, null, null, 1), history.points[1])
    }

    @Test
    fun `a sensor without readings parses to no points`() {
        val history =
            parseHistory("""{"controller": "b1", "channel": 4, "since": 1, "to": 2, "bucket_s": 300, "points": []}""")
        assertEquals(emptyList(), history.points)
        assertEquals(4, history.channel)
    }

    @Test
    fun `the command answer is cmd= or nothing`() {
        assertEquals(17, parseCmdAnswer("cmd=17\n"))
        assertNull(parseCmdAnswer("busy: cmd=3 state=sent"))
        assertNull(parseCmdAnswer(""))
    }
}
