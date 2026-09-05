package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pot(
    name: String,
    status: String = ALIVE,
    pct: Int? = null,
    raw: Long? = null,
    readTs: Long? = null,
    id: String = "pot-$name",
) = Pot(id = id, name = name, status = status, pct = pct, raw = raw, readTs = readTs)

private fun controller(
    name: Int = 0,
    lastSeen: Long = 0,
    nextS: Int? = null,
    float: Int? = null,
    pos: String? = null,
    command: InFlight? = null,
    latched: Latch? = null,
    retired: Int = 0,
    posOkSeen: Long? = null,
) = ControllerHealth(
    name, lastSeen, nextS, float, pos, command,
    latched = latched, retired = retired, posOkSeen = posOkSeen,
)

private fun dose(
    state: String = "acked",
    verdict: String? = null,
    sentTs: Long? = null,
    ackedTs: Long? = null,
    ml: Int? = 100,
    flowMl: Int? = null,
    source: String? = null,
) = LastDose(16, ml, 10, flowMl, state, source, sentTs, ackedTs, verdict)

private val complete =
    Pot(
        name = "basil",
        controller = 0,
        channel = 0,
        outlet = 3,
        dryRaw = 12000,
        wetRaw = 4000,
        targetLowPct = 30,
        doseMl = 100,
    )

class GardenTest {
    @Test
    fun `potById finds a pot whatever its nickname, and never on an empty id`() {
        val garden =
            splitGarden(
                listOf(pot("basil", id = "pot-1"), pot("cactus", status = GRAVEYARD, id = "pot-2")),
                Health(ok = true),
                nowS = 1000,
            )
        assertEquals("basil", garden.potById("pot-1")?.name)
        assertEquals("cactus", garden.potById("pot-2")?.name) // buried pots stay reachable
        assertNull(garden.potById("pot-nope"))
        // A backend too old to send ids leaves them empty: "" is not a key.
        val old = splitGarden(listOf(pot("basil", id = "")), Health(ok = true), nowS = 1000)
        assertNull(old.potById(""))
    }

    @Test
    fun `the list keys on the id, and falls back to the name when there is none`() {
        assertEquals("pot-1", potKey(pot("basil", id = "pot-1")))
        // A backend too old to send ids would key every row the same and a
        // LazyColumn throws on a duplicate: the names still differ.
        assertEquals("name:basil", potKey(pot("basil", id = "")))
        assertEquals("name:mint", potKey(pot("mint", id = "")))
    }

    @Test
    fun `env pots split off and buried pots go to the graveyard`() {
        val garden =
            splitGarden(
                listOf(
                    pot("basil"),
                    pot("env:temp"),
                    pot("cactus", status = GRAVEYARD),
                ),
                Health(ok = true),
                nowS = 1000,
            )
        assertEquals(listOf("basil"), garden.pots.map { it.name })
        assertEquals(listOf("env:temp"), garden.env.map { it.name })
        assertEquals(listOf("cactus"), garden.graveyard.map { it.name })
    }

    @Test
    fun `buried pots of any name are kept aside and the health travels along`() {
        val health = Health(ok = true, nextDefault = 30)
        val garden =
            splitGarden(listOf(pot("env:hum", status = GRAVEYARD), pot("fern", status = GRAVEYARD)), health, 1000)
        assertEquals(listOf("env:hum", "fern"), garden.graveyard.map { it.name })
        assertEquals(emptyList(), garden.pots)
        assertEquals(emptyList(), garden.env)
        assertEquals(health, garden.health)
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
        assertEquals(listOf("board 0 last reported 11min ago"), found)
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
                alerts = listOf(RaisedAlert("silent:0", 950)),
            )
        val found = problems(paged, nowS = 1000)
        assertEquals(1, found.count { "board 0" in it })
    }

    @Test
    fun `the default interval comes from the backend, not a baked-in 60`() {
        val health = Health(ok = true, nextDefault = 300, controllers = listOf(controller(lastSeen = 300)))
        assertEquals(emptyList(), problems(health, nowS = 1000)) // 700 s < 3 * 300
        assertEquals(listOf("board 0 last reported 16min ago"), problems(health, nowS = 1300))
    }

    @Test
    fun `the silence floor is 600 s exactly`() {
        val quiet = Health(ok = true, controllers = listOf(controller(lastSeen = 400)))
        assertEquals(emptyList(), problems(quiet, nowS = 1000))
        val silent = Health(ok = true, controllers = listOf(controller(lastSeen = 399)))
        assertEquals(listOf("board 0 last reported 10min ago"), problems(silent, nowS = 1000))
    }

    @Test
    fun `a configured but never-heard controller is called out`() {
        val health = Health(ok = true, controllers = listOf(controller(lastSeen = 0)))
        assertEquals(listOf("board 0 has never reported"), problems(health, nowS = 1000))
    }

    @Test
    fun `raised alerts become readable lines`() {
        assertEquals("board 0 has gone silent", describeAlert("silent:0"))
        assertEquals("reservoir empty on board 0", describeAlert("float:0"))
        assertEquals("board 0 lost its manifold position", describeAlert("pos:0"))
        assertEquals("sensor ch0 on board 0 stopped reporting", describeAlert("sensor:0:0"))
        assertEquals("board 0 stopped sending float=", describeAlert("fields:float:0"))
        assertEquals("weird:key", describeAlert("weird:key"))
    }

    @Test
    fun `a raised alert carries how long it has stood`() {
        assertEquals(
            "reservoir empty on board 0 (50s ago)",
            describeAlert("float:0", nowS = 1000, raisedTs = 950),
        )
    }

    @Test
    fun `instant float and pos lines are deduplicated against raised alerts`() {
        val health =
            Health(
                ok = true,
                controllers =
                    // posOkSeen: a board that once knew its position, so the
                    // pos line is a problem at all (see the dark board below).
                    listOf(controller(lastSeen = 990, float = 0, pos = "unknown", posOkSeen = 100)),
                alerts = listOf(RaisedAlert("float:0", 900)),
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

    @Test
    fun `the controller line reads seen, interval, float and pos`() {
        assertEquals(
            "board 0 · seen 40s ago · every 60s · float ok · pos ok",
            controllerLine(controller(lastSeen = 960, float = 1, pos = "ok"), 1000, 60),
        )
        assertEquals(
            "board 0 · never reported · every 5s (override) · float EMPTY · pos unknown",
            controllerLine(controller(nextS = 5, float = 0, pos = "unknown"), 1000, 60),
        )
        assertEquals(
            "board 0 · seen 10s ago · every 30s · float ? · pos ?",
            controllerLine(controller(lastSeen = 990), 1000, 30),
        )
        assertEquals(
            "board 0 · seen 10s ago · every 60s · float ok · pos 3",
            controllerLine(controller(lastSeen = 990, float = 1, pos = "3"), 1000, 60),
        )
    }

    @Test
    fun `the controller line appends the command in flight`() {
        val water = controller(lastSeen = 990, command = InFlight(17, "water", "sent"))
        assertTrue(controllerLine(water, 1000, 60).endsWith(" · cmd 17 sent"))
        val stop = controller(lastSeen = 990, command = InFlight(17, "stop", "sent"))
        assertTrue(controllerLine(stop, 1000, 60).endsWith(" · cmd 17 stop sent"))
        assertFalse("cmd" in controllerLine(controller(lastSeen = 990), 1000, 60))
    }

    @Test
    fun `the controller line says stopped and retired`() {
        val stopped = controller(lastSeen = 990, float = 1, pos = "ok", latched = Latch(900, "contra"))
        assertEquals(
            "board 0 · seen 10s ago · every 60s · float ok · pos ok · STOPPED",
            controllerLine(stopped, 1000, 60),
        )
        val retired = controller(lastSeen = 990, retired = 1)
        assertTrue(controllerLine(retired, 1000, 60).endsWith(" · retired"))
    }

    @Test
    fun `the latch line says why, since when, and what to do`() {
        val c = controller(lastSeen = 990, latched = Latch(since = 400, reason = "contra"))
        assertEquals(
            "board 0 stopped watering 10min ago: the float said full and the meter saw nothing. " +
                "Check the tank, type clear contra on the board, then resume.",
            latchLine(c, 1000),
        )
        assertEquals("it reset with the pump running", latchReason("resetmid"))
        assertEquals("heap", latchReason("heap"))
    }

    @Test
    fun `a stopped board is a problem until the backend's own page stands`() {
        val stopped = controller(lastSeen = 990, float = 1, pos = "ok", latched = Latch(400, "contra"))
        assertEquals(
            listOf("board 0 stopped watering: the float said full and the meter saw nothing"),
            problems(Health(ok = true, controllers = listOf(stopped)), nowS = 1000),
        )
        val paged =
            Health(
                ok = true,
                controllers = listOf(stopped),
                alerts = listOf(RaisedAlert("latch:0", raisedTs = 500)),
            )
        assertEquals(listOf("board 0 stopped watering (8min ago)"), problems(paged, nowS = 1000))
        assertEquals(
            "the float on board 0 never moved across the refill (8min ago)",
            describeAlert("stale:0", nowS = 1000, raisedTs = 500),
        )
    }

    @Test
    fun `a board that never knew its position is not a problem, and a retired one is never silent`() {
        val dark = controller(lastSeen = 990, float = 1, pos = "unknown", posOkSeen = null)
        assertEquals(emptyList(), problems(Health(ok = true, controllers = listOf(dark)), nowS = 1000))
        val lost = controller(lastSeen = 990, float = 1, pos = "unknown", posOkSeen = 100)
        assertEquals(
            listOf("board 0 lost its manifold position"),
            problems(Health(ok = true, controllers = listOf(lost)), nowS = 1000),
        )
        val retired = controller(lastSeen = 10, retired = 1)
        assertEquals(emptyList(), problems(Health(ok = true, controllers = listOf(retired)), nowS = 100000))
    }

    @Test
    fun `an override is a non-null next_s`() {
        assertTrue(hasOverride(controller(nextS = 5)))
        assertFalse(hasOverride(controller()))
    }

    @Test
    fun `the proposal line says what, how capped and how old`() {
        assertEquals(
            "proposal: 100 ml, cap 10 s, made 3min ago",
            proposalLine(Proposal(17, 100, 10, 820), 1000),
        )
        assertEquals("proposal: ? ml", proposalLine(Proposal(17), 1000))
        assertEquals("proposal: 50 ml, made 5s ago", proposalLine(Proposal(17, 50, null, 995), 1000))
    }

    @Test
    fun `the dose line covers source, ago, state and meter`() {
        assertEquals(
            "manual dose 100 ml · 40min ago · confirmed, meter 96 ml",
            doseLine(dose(source = "manual", sentTs = -3000, ackedTs = -1400, flowMl = 96), 1000),
        )
        assertEquals("dose 100 ml · 10s ago · confirmed", doseLine(dose(ackedTs = 990), 1000))
        assertEquals(
            "dose 100 ml · 10s ago · handed over, waiting for the board to confirm",
            doseLine(dose(state = "sent", sentTs = 990), 1000),
        )
        assertEquals(
            "dose ? ml · expired, the board never confirmed it",
            doseLine(dose(state = "expired", ml = null), 1000),
        )
        assertFalse("too" in doseLine(dose(ackedTs = 990, verdict = "too_much"), 1000))
    }

    @Test
    fun `a verdict is wanted after the soak and within the window`() {
        val acked = 1000L
        assertFalse(needsVerdict(dose(ackedTs = acked), acked + SOAK_S))
        assertTrue(needsVerdict(dose(ackedTs = acked), acked + SOAK_S + 1))
        assertTrue(needsVerdict(dose(ackedTs = acked), acked + VERDICT_WINDOW_S))
        assertFalse(needsVerdict(dose(ackedTs = acked), acked + VERDICT_WINDOW_S + 1))
    }

    @Test
    fun `no verdict wanted without a dose, an ack, a timestamp or once judged`() {
        val nowS = 10_000L
        assertFalse(needsVerdict(null, nowS))
        assertFalse(needsVerdict(dose(state = "sent", sentTs = 1000), nowS))
        assertFalse(needsVerdict(dose(state = "expired", sentTs = 1000), nowS))
        assertFalse(needsVerdict(dose(verdict = "ok", ackedTs = 1000), nowS))
        assertFalse(needsVerdict(dose(), nowS))
        assertTrue(needsVerdict(dose(sentTs = 1000), nowS)) // acked without acked_ts
    }

    @Test
    fun `a pot with no percentage of its own gets one from its own calibration`() {
        // What a cached pot looks like: the backend's derived pct is not
        // stored, so the line derives it from the raw and the two points
        // that travelled with it — the same formula, so cached and live
        // pots read alike.
        val cached = Pot(name = "basil", raw = 8000, dryRaw = 12000, wetRaw = 4000, readTs = 900)
        assertEquals("50% · 1min ago", potLine(cached, 1000))
        // The backend's own number still wins when it sent one.
        assertEquals("48% · 1min ago", potLine(cached.copy(pct = 48), 1000))
        // Without calibration there is nothing to derive, so raw it is.
        assertEquals("raw 8000 · 1min ago", potLine(Pot(name = "b", raw = 8000, readTs = 900), 1000))
    }

    @Test
    fun `the row note nags for a verdict and otherwise stays quiet`() {
        val judged = Pot(name = "basil", lastDose = dose(ackedTs = 1000))
        assertEquals("dose 2h ago, not judged yet", rowNote(judged, 1000 + 2 * 3600))
        assertNull(rowNote(judged, 1000 + 60))
        assertNull(rowNote(Pot(name = "basil"), 5000))
    }

    @Test
    fun `a buried pot is never nagged`() {
        val off = Pot(name = "basil", status = GRAVEYARD, lastDose = dose(ackedTs = 1000))
        assertNull(rowNote(off, 1000 + 2 * 3600))
    }

    @Test
    fun `learning gaps name every missing prerequisite in order`() {
        assertEquals(
            listOf(
                "a controller", "a channel", "an outlet", "calibration (dry and wet)",
                "a target low %", "a dose",
            ),
            learningGaps(Pot(name = "new")),
        )
        val ready = controller(lastSeen = 990, float = 1, pos = "ok")
        assertEquals(emptyList(), learningGaps(complete, ready))
        assertEquals(
            listOf("calibration (dry and wet)"),
            learningGaps(complete.copy(wetRaw = null), ready),
        )
    }

    @Test
    fun `learning also needs the board's float and pos, which today's firmware omits`() {
        assertEquals(
            listOf("the board reporting float=1 and pos=ok (now float ?, pos ?)"),
            learningGaps(complete, controller(lastSeen = 990)),
        )
        assertEquals(
            listOf("the board reporting float=1 and pos=ok (now float ?, pos ?)"),
            learningGaps(complete, null),
        )
        assertEquals(
            listOf("the board reporting float=1 and pos=ok (now float 0, pos unknown)"),
            learningGaps(complete, controller(lastSeen = 990, float = 0, pos = "unknown")),
        )
        assertEquals(emptyList(), learningGaps(complete, controller(lastSeen = 990, float = 1, pos = "ok")))
        val unmapped = complete.copy(controller = null)
        assertEquals(listOf("a controller"), learningGaps(unmapped, null))
        assertEquals(
            listOf("a channel", "the board reporting float=1 and pos=ok (now float ?, pos ?)"),
            learningGaps(complete.copy(channel = null), null),
        )
    }

    @Test
    fun `verdicts read as words`() {
        assertEquals("ok", verdictLabel("ok"))
        assertEquals("too much", verdictLabel("too_much"))
        assertEquals("too little", verdictLabel("too_little"))
        assertEquals("odd", verdictLabel("odd"))
    }

    @Test
    fun `board zero is a real board, not an empty one`() {
        // The controller is an integer now, and 0 is falsy in every language
        // this passes through — it is also the number a new pot is filled in
        // with, so anything testing it for truth refuses the commonest board
        // there is.
        val zero = Pot(id = "p", name = "basil", controller = 0, channel = 0, outlet = 0)
        assertEquals("board 0", boardName(0))
        assertNull(
            cannotWater(zero.copy(doseMl = 100), controller(lastSeen = 990), 1000, 60, emptySet())
        )
        // Board 0 is not a gap; a missing board is.
        assertTrue("a controller" in learningGaps(zero.copy(controller = null)))
        assertFalse("a controller" in learningGaps(zero))
    }

    @Test
    fun `an alert key renders its board even when the shape is unfamiliar`() {
        assertEquals("board 0 has gone silent", describeAlert("silent:0"))
        // The key is whatever the backend put there. This reads keys rather
        // than parsing them, so a longer or shorter one still renders a line
        // instead of throwing: the extra half is ignored, and a missing half
        // is a question mark.
        assertEquals("board 0 has gone silent", describeAlert("silent:0:extra"))
        assertEquals("? has gone silent", describeAlert("silent"))
    }
}
