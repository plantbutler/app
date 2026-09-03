package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun seen(raw: Long, readTs: Long) = CalEvent.Seen(raw, readTs)

private fun air(since: Long = 1000, freshS: Long = FRESH_FAST_S, vararg seen: Reading) =
    CalState.Air(prevNextS = null, freshS = freshS, since = since, seen = seen.toList())

private fun water(since: Long = 1000, freshS: Long = FRESH_FAST_S, vararg seen: Reading) =
    CalState.Water(prevNextS = null, freshS = freshS, since = since, dry = 12000, seen = seen.toList())

private fun review(refused: String? = null) =
    CalState.Review(prevNextS = null, freshS = FRESH_FAST_S, dry = 12000, wet = 4000, refused = refused)

private fun saving() = CalState.Saving(prevNextS = null, freshS = FRESH_FAST_S, dry = 12000, wet = 4000)

private fun stalled() = CalState.Stalled(prevNextS = null, lastReadTs = 990, timeoutS = 150)

private fun pot(
    controller: String? = "b1",
    channel: Int? = 0,
    mode: String = "manual",
) = Pot(name = "basil", controller = controller, channel = channel, mode = mode)

private fun health(lastSeen: Long = 990, nextS: Int? = null) =
    ControllerHealth("b1", lastSeen = lastSeen, nextS = nextS)

class CalibrationTest {
    @Test
    fun `the start timeout scales with the interval the board was on`() {
        assertEquals(150, calStart(null, 1000, 60).timeoutS)
        assertEquals(750, calStart(300, 1000, 60).timeoutS)
        assertEquals(150, calStart(5, 1000, 60).timeoutS)
        assertEquals(300, calStart(null, 1000, 120).timeoutS)
        assertEquals(153, calStart(61, 1000, 60).timeoutS) // 152.5 rounds up
        assertEquals(1000, calStart(null, 1000, 60).startTs)
    }

    @Test
    fun `a single fresh reading does not prove the board sped up`() {
        val s = calStep(calStart(null, 1000, 60), seen(8000, 1001), 1001)
        assertIs<CalState.SpeedingUp>(s)
        assertEquals(listOf(Reading(8000, 1001, 1001)), s.seen)
    }

    @Test
    fun `two readings an old interval apart are not enough`() {
        var s: CalState = calStart(null, 1000, 60)
        s = calStep(s, seen(8000, 1001), 1001)
        s = calStep(s, seen(8000, 1061), 1061)
        assertIs<CalState.SpeedingUp>(s)
    }

    @Test
    fun `two reports close together move the wizard to air`() {
        var s: CalState = calStart(7, 1000, 60)
        s = calStep(s, seen(8000, 1001), 1001)
        s = calStep(s, seen(8100, 1061), 1061)
        s = calStep(s, seen(8200, 1066), 1066)
        assertEquals(
            CalState.Air(
                prevNextS = 7,
                freshS = FRESH_FAST_S,
                since = 1000,
                seen = listOf(Reading(8200, 1066, 1066), Reading(8100, 1061, 1061), Reading(8000, 1001, 1001)),
            ),
            s,
        )
    }

    @Test
    fun `the same report polled again does not count as a second reading`() {
        var s: CalState = calStart(null, 1000, 60)
        s = calStep(s, seen(8000, 1001), 1001)
        s = calStep(s, seen(8000, 1001), 1003)
        assertIs<CalState.SpeedingUp>(s)
        assertEquals(1, s.seen.size)
    }

    @Test
    fun `seen readings keep the newest first and only the last few`() {
        var s: CalState = air()
        for (ts in 1001L..1010L) s = calStep(s, seen(ts, ts), ts)
        assertIs<CalState.Air>(s)
        assertEquals(listOf(1010L, 1009L, 1008L), s.seen.map { it.readTs })
        assertEquals(SETTLE_SHOWN, s.seen.size)
    }

    @Test
    fun `a tick past the timeout stalls with the last reading seen`() {
        val start = calStart(null, 1000, 60)
        assertSame(start, calStep(start, CalEvent.Tick, 1150))
        assertEquals(
            CalState.Stalled(null, lastReadTs = null, timeoutS = 150),
            calStep(start, CalEvent.Tick, 1151),
        )
        val heard = calStep(start, seen(8000, 1061), 1061)
        assertEquals(
            CalState.Stalled(null, lastReadTs = 1061, timeoutS = 150),
            calStep(heard, CalEvent.Tick, 1151),
        )
    }

    @Test
    fun `a stalled wizard can retry, continue slowly or give up`() {
        assertEquals(
            CalState.SpeedingUp(null, startTs = 2000, timeoutS = 150),
            calStep(stalled(), CalEvent.Retry, 2000),
        )
        assertEquals(
            CalState.Air(null, freshS = FRESH_SLOW_S, since = 2000),
            calStep(stalled(), CalEvent.ContinueSlow, 2000),
        )
        assertEquals(CalState.Cancelled(null), calStep(stalled(), CalEvent.Cancel, 2000))
        val s = stalled()
        assertSame(s, calStep(s, CalEvent.Tap, 2000))
    }

    @Test
    fun `a tap needs a reading newer than the step and still live`() {
        assertFalse(canTap(air(), 1010))
        assertFalse(canTap(air(since = 1000, seen = arrayOf(Reading(8000, 1000, 1000))), 1010))
        assertFalse(canTap(air(since = 1000, seen = arrayOf(Reading(8000, 990, 990))), 1010))
        assertFalse(canTap(air(since = 1000, seen = arrayOf(Reading(8000, 1001, 1001))), 1017))
        assertTrue(canTap(air(since = 1000, seen = arrayOf(Reading(8000, 1001, 1001))), 1016))
        assertTrue(
            canTap(air(since = 1000, freshS = FRESH_SLOW_S, seen = arrayOf(Reading(8000, 1001, 1001))), 1100),
        )
        assertFalse(canTap(stalled(), 1000))
        assertFalse(canTap(review(), 1000))
    }

    @Test
    fun `a reading exactly freshS old is live, one second older is not`() {
        val fast = air(since = 1000, seen = arrayOf(Reading(8000, 1001, 1001)))
        assertTrue(canTap(fast, 1001 + FRESH_FAST_S))
        assertFalse(canTap(fast, 1001 + FRESH_FAST_S + 1))
        val slow = water(since = 1000, freshS = FRESH_SLOW_S, seen = arrayOf(Reading(8000, 1001, 1001)))
        assertTrue(canTap(slow, 1001 + FRESH_SLOW_S))
        assertFalse(canTap(slow, 1001 + FRESH_SLOW_S + 1))
    }

    @Test
    fun `freshness is measured on the phone clock, so skew from the backend is harmless`() {
        // The phone runs 100 s ahead of the backend: a report stamped 1001
        // there is polled at 1101 here, and must still be tappable.
        var s: CalState = air(since = 1000)
        s = calStep(s, seen(8000, 1001), 1101)
        assertIs<CalState.Air>(s)
        assertEquals(listOf(Reading(8000, 1001, 1101)), s.seen)
        assertTrue(canTap(s, 1102))
        assertIs<CalState.Water>(calStep(s, CalEvent.Tap, 1102))
    }

    @Test
    fun `two reports exactly the fast interval apart prove the board sped up`() {
        var s: CalState = calStart(null, 1000, 60)
        s = calStep(s, seen(8000, 1001), 1001)
        assertIs<CalState.Air>(calStep(s, seen(8100, 1001 + FRESH_FAST_S), 1001 + FRESH_FAST_S))
        assertIs<CalState.SpeedingUp>(
            calStep(s, seen(8100, 1001 + FRESH_FAST_S + 1), 1001 + FRESH_FAST_S + 1),
        )
    }

    @Test
    fun `tapping in air on a live reading fixes dry and starts water`() {
        val s = air(since = 1000, seen = arrayOf(Reading(12000, 1005, 1005), Reading(11900, 1001, 1001)))
        assertEquals(
            // Two reports have no middle one, so they average: 11950, not
            // whichever happened to arrive last.
            CalState.Water(null, freshS = FRESH_FAST_S, since = 1005, dry = 11950),
            calStep(s, CalEvent.Tap, 1010),
        )
    }

    @Test
    fun `the endpoint is the median of the reports this step has seen`() {
        // One noisy sample used to set a pot's whole scale until somebody
        // recalibrated. The median throws it away.
        val noisy =
            air(
                since = 1000,
                seen = arrayOf(Reading(12000, 1011, 1011), Reading(400, 1006, 1006), Reading(11900, 1001, 1001)),
            )
        val water = calStep(noisy, CalEvent.Tap, 1015) as CalState.Water
        assertEquals(11900, water.dry) // not 12000, and emphatically not 400
        // And the step that follows must be newer than the newest report
        // used, so none of them can serve the wet end too.
        assertEquals(1011, water.since)
    }

    @Test
    fun `one report is still enough to tap, and is its own median`() {
        val one = air(since = 1000, seen = arrayOf(Reading(12000, 1005, 1005)))
        val water = calStep(one, CalEvent.Tap, 1010) as CalState.Water
        assertEquals(12000, water.dry)
    }

    @Test
    fun `three samples are three reports, never one report counted three times`() {
        // The pitch's rabbit hole. `remember` keeps readings distinct by
        // readTs, so polling the same report over and over leaves one.
        var s: CalState = air(since = 1000)
        repeat(3) { s = calStep(s, CalEvent.Seen(12000, 1005), 1005 + it.toLong()) }
        assertEquals(1, tapSamples(s).size)
        s = calStep(s, CalEvent.Seen(11900, 1010), 1010)
        assertEquals(2, tapSamples(s).size)
    }

    @Test
    fun `a report from before the step began is not part of its median`() {
        val s =
            air(
                since = 1000,
                seen = arrayOf(Reading(12000, 1005, 1005), Reading(9999, 999, 999)),
            )
        assertEquals(listOf(12000L), tapSamples(s).map { it.raw })
        assertEquals(12000, medianRaw(tapSamples(s)))
    }

    @Test
    fun `the wizard says how many readings it has and what a tap would take`() {
        assertEquals("no reading yet", settleLine(0))
        assertEquals("1 of 3 readings — tapping now takes just this one", settleLine(1))
        assertEquals("2 of 3 readings — tapping now takes the median of these", settleLine(2))
        assertEquals("3 of 3 readings — tapping takes their median", settleLine(3))
    }

    @Test
    fun `the median of nothing is nothing, and of an even pair is their average`() {
        assertNull(medianRaw(emptyList()))
        assertEquals(11950, medianRaw(listOf(Reading(12000, 2, 2), Reading(11900, 1, 1))))
        // Rounds up rather than truncating: a raw count is not a half.
        assertEquals(12000, medianRaw(listOf(Reading(12001, 2, 2), Reading(11999, 1, 1))))
        assertEquals(5, medianRaw(listOf(Reading(4, 2, 2), Reading(5, 1, 1))))
    }

    @Test
    fun `tapping in air is ignored when the reading is stale or missing`() {
        val stale = air(since = 1000, seen = arrayOf(Reading(12000, 1005, 1005)))
        assertSame(stale, calStep(stale, CalEvent.Tap, 1030))
        val empty = air()
        assertSame(empty, calStep(empty, CalEvent.Tap, 1010))
        val old = air(since = 1000, seen = arrayOf(Reading(12000, 999, 999)))
        assertSame(old, calStep(old, CalEvent.Tap, 1005))
    }

    @Test
    fun `water refuses the reading that already served as dry`() {
        var s: CalState = air(since = 1000, seen = arrayOf(Reading(12000, 1005, 1005)))
        s = calStep(s, CalEvent.Tap, 1006)
        assertIs<CalState.Water>(s)
        s = calStep(s, seen(12000, 1005), 1007)
        assertSame(s, calStep(s, CalEvent.Tap, 1008))
        s = calStep(s, seen(4000, 1010), 1011)
        assertEquals(
            CalState.Review(null, freshS = FRESH_FAST_S, dry = 12000, wet = 4000),
            calStep(s, CalEvent.Tap, 1012),
        )
    }

    @Test
    fun `water can go back to air without forgetting the mode`() {
        val slow = water(freshS = FRESH_SLOW_S, seen = arrayOf(Reading(5000, 1005, 1005)))
        assertEquals(
            CalState.Air(null, freshS = FRESH_SLOW_S, since = 1200),
            calStep(slow, CalEvent.BackToAir, 1200),
        )
    }

    @Test
    fun `review saves, gets refused, saves again and finishes`() {
        var s: CalState = review()
        s = calStep(s, CalEvent.Save, 1000)
        assertEquals(saving(), s)
        s = calStep(s, CalEvent.Refused("refused: dry_raw must exceed wet_raw"), 1001)
        assertEquals(review(refused = "refused: dry_raw must exceed wet_raw"), s)
        s = calStep(s, CalEvent.Save, 1002)
        assertEquals(saving(), s)
        s = calStep(s, CalEvent.Saved, 1003)
        assertEquals(CalState.Finished(null, dry = 12000, wet = 4000), s)
    }

    @Test
    fun `review keeps the refusal text and can go back to air`() {
        assertEquals(review(refused = "bad token"), calStep(review(), CalEvent.Refused("bad token"), 1000))
        assertEquals(
            CalState.Air(null, freshS = FRESH_FAST_S, since = 1500),
            calStep(review(refused = "bad token"), CalEvent.BackToAir, 1500),
        )
    }

    @Test
    fun `cancel works from every state before the save is on the wire`() {
        val states = listOf(calStart(9, 1000, 60), stalled(), air(), water(), review())
        for (s in states) {
            assertEquals(CalState.Cancelled(s.prevNextS), calStep(s, CalEvent.Cancel, 2000), "$s")
        }
    }

    @Test
    fun `saving ignores cancel, the POST is already out`() {
        val s = saving()
        assertSame(s, calStep(s, CalEvent.Cancel, 2000))
    }

    @Test
    fun `finished and cancelled ignore everything`() {
        val events =
            listOf(
                seen(1, 1),
                CalEvent.Tick,
                CalEvent.Tap,
                CalEvent.Save,
                CalEvent.Saved,
                CalEvent.Refused("x"),
                CalEvent.Retry,
                CalEvent.ContinueSlow,
                CalEvent.BackToAir,
                CalEvent.Cancel,
            )
        for (s in listOf(CalState.Finished(3, 12000, 4000), CalState.Cancelled(3))) {
            for (e in events) assertSame(s, calStep(s, e, 5000), "$s $e")
        }
    }

    @Test
    fun `ticks and foreign events leave the other states alone`() {
        for (s in listOf(stalled(), air(), water(), review(), saving())) {
            assertSame(s, calStep(s, CalEvent.Tick, 9000), "$s")
        }
        val a = air()
        assertSame(a, calStep(a, CalEvent.Save, 1000))
        val r = review()
        assertSame(r, calStep(r, CalEvent.Tap, 1000))
        val v = saving()
        assertSame(v, calStep(v, CalEvent.Save, 1000))
    }

    @Test
    fun `the hint flags close and inverted endpoints, never a good pair`() {
        assertNull(calHint(12000, 4000))
        assertEquals("dry and wet are very close — is the sensor plugged in?", calHint(4500, 4000))
        assertEquals("dry and wet are very close — is the sensor plugged in?", calHint(4000, 4000))
        assertEquals("dry reads lower than wet — that is unusual for this probe", calHint(4000, 12000))
    }

    @Test
    fun `calibration is refused for the right reason in priority order`() {
        assertEquals(
            "map a controller and a channel first",
            canCalibrate(pot(controller = null, mode = "auto"), null, 1000, 60),
        )
        assertEquals(
            "map a controller and a channel first",
            canCalibrate(pot(channel = null), health(), 1000, 60),
        )
        assertEquals(
            "set the pot to manual first — the rules would water a sensor held in the air",
            canCalibrate(pot(mode = "learning"), null, 1000, 60),
        )
        assertEquals("b1 has never reported", canCalibrate(pot(), null, 1000, 60))
        assertEquals("b1 has never reported", canCalibrate(pot(), health(lastSeen = 0), 1000, 60))
        assertEquals(
            "b1 is silent (last reported 11min ago)",
            canCalibrate(pot(), health(lastSeen = 300), 1000, 60),
        )
    }

    @Test
    fun `the silence threshold follows the interval the controller is on`() {
        assertNull(canCalibrate(pot(), health(lastSeen = 300, nextS = 300), 1000, 60))
        assertNull(canCalibrate(pot(), health(lastSeen = 300), 1000, 300))
        assertEquals(
            "b1 is silent (last reported 15min ago)",
            canCalibrate(pot(), health(lastSeen = 50, nextS = 300), 1000, 60),
        )
    }

    @Test
    fun `a manual, mapped, reporting pot may start`() {
        assertNull(canCalibrate(pot(), health(), 1000, 60))
    }
}
