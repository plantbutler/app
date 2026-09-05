package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ready =
    Pot(name = "basil", controller = 0, channel = 0, outlet = 3, doseMl = 100)

private fun controller(
    lastSeen: Long = 990,
    nextS: Int? = null,
    command: InFlight? = null,
    latched: Latch? = null,
) = ControllerHealth(0, lastSeen = lastSeen, nextS = nextS, command = command, latched = latched)

private fun dose(id: Long, state: String, flowMl: Int? = null) =
    LastDose(id, ml = 100, flowMl = flowMl, state = state)

private val issued = Issued(17, ts = 1000)

class WaterTest {
    @Test
    fun `a mapped pot with a dose on a live idle board may be watered`() {
        assertNull(cannotWater(ready, controller(), 1000, 60, emptySet()))
        assertNull(cannotWater(ready, controller(), 1000, 60, setOf("plant_type", "target_low_pct")))
    }

    @Test
    fun `a buried pot refuses before anything else`() {
        val buried = ready.copy(status = GRAVEYARD, proposal = Proposal(9, 100))
        assertEquals(
            "this pot is in the graveyard — bring it back in the form first",
            cannotWater(buried, controller(command = InFlight(3, state = "sent")), 1000, 60, emptySet()),
        )
    }

    @Test
    fun `the mapping comes before the dose, the dose before the draft`() {
        assertEquals(
            "map a controller and an outlet first",
            cannotWater(ready.copy(controller = null, doseMl = null), null, 1000, 60, setOf("controller")),
        )
        assertEquals(
            "map a controller and an outlet first",
            cannotWater(ready.copy(outlet = null), controller(), 1000, 60, emptySet()),
        )
        assertEquals(
            "set a dose in the form first",
            cannotWater(ready.copy(doseMl = null), null, 1000, 60, setOf("dose_ml")),
        )
    }

    @Test
    fun `a dirty controller, outlet or dose refuses before the board is asked`() {
        val why = "save or discard your changes first — the water goes to the stored controller, outlet and dose"
        assertEquals(why, cannotWater(ready, null, 1000, 60, setOf("controller")))
        assertEquals(why, cannotWater(ready, null, 1000, 60, setOf("outlet")))
        assertEquals(why, cannotWater(ready, controller(command = InFlight(3)), 1000, 60, setOf("dose_ml")))
    }

    @Test
    fun `a board that never reported or went silent refuses before the slot`() {
        val busy = InFlight(3, state = "sent")
        assertEquals("board 0 has never reported", cannotWater(ready, null, 1000, 60, emptySet()))
        assertEquals("board 0 has never reported", cannotWater(ready, controller(lastSeen = 0, command = busy), 1000, 60, emptySet()))
        assertEquals(
            "board 0 is silent (last reported 11min ago)",
            cannotWater(ready, controller(lastSeen = 1000 - 661, command = busy), 1000, 60, emptySet()),
        )
        assertNull(cannotWater(ready, controller(lastSeen = 1000 - 600), 1000, 60, emptySet()))
        assertNull(cannotWater(ready, controller(lastSeen = 1000 - 899, nextS = 300), 1000, 60, emptySet()))
    }

    @Test
    fun `a taken slot refuses before a waiting proposal`() {
        val proposed = ready.copy(proposal = Proposal(9, 100))
        assertEquals(
            "busy: cmd 3 sent on board 0",
            cannotWater(proposed, controller(command = InFlight(3, state = "sent")), 1000, 60, emptySet()),
        )
        assertEquals(
            "busy: cmd 4 queued on board 0",
            cannotWater(ready, controller(command = InFlight(4)), 1000, 60, emptySet()),
        )
        assertEquals(
            "a proposal is waiting above — approve it or let it expire",
            cannotWater(proposed, controller(), 1000, 60, emptySet()),
        )
    }

    @Test
    fun `a stopped board refuses after silence and before the busy slot`() {
        val stopped = controller(command = InFlight(3, state = "sent"), latched = Latch(900, "contra"))
        assertEquals(
            "board 0 stopped watering (the float said full and the meter saw nothing) — check the tank, then resume it on the garden screen",
            cannotWater(ready, stopped, 1000, 60, emptySet()),
        )
        val silentAndStopped = controller(lastSeen = 10, latched = Latch(900, "contra"))
        assertTrue(cannotWater(ready, silentAndStopped, 1000, 60, emptySet())!!.startsWith("board 0 is silent"))
    }

    @Test
    fun `the controller's slot says queued or sent`() {
        assertEquals(WaterStatus.Queued, waterStatus(issued, ready, controller(command = InFlight(17)), 1010, false))
        assertEquals(
            WaterStatus.Sent,
            waterStatus(issued, ready, controller(command = InFlight(17, state = "sent")), 1010, false),
        )
    }

    @Test
    fun `the pot's last dose says sent, done or expired`() {
        assertEquals(WaterStatus.Sent, waterStatus(issued, ready.copy(lastDose = dose(17, "sent")), controller(), 1010, false))
        assertEquals(
            WaterStatus.Done(96),
            waterStatus(issued, ready.copy(lastDose = dose(17, "acked", flowMl = 96)), controller(), 1010, false),
        )
        assertEquals(WaterStatus.Done(null), waterStatus(issued, ready.copy(lastDose = dose(17, "acked")), null, 1010, false))
        assertEquals(WaterStatus.Expired, waterStatus(issued, ready.copy(lastDose = dose(17, "expired")), null, 1010, false))
    }

    @Test
    fun `another command's id is not this one`() {
        val other = ready.copy(lastDose = dose(16, "acked", flowMl = 96))
        assertNull(waterStatus(issued, other, controller(command = InFlight(18)), 1010, false))
        assertNull(waterStatus(issued, null, null, 1010, false))
    }

    @Test
    fun `unseen on a stale garden stays queued, unseen after the wait is no news`() {
        assertEquals(WaterStatus.Queued, waterStatus(issued, ready, controller(), 1010, stale = true))
        assertNull(waterStatus(issued, ready, controller(), 1000 + FOLLOW_MAX_S, stale = false))
        assertEquals(WaterStatus.NoNews, waterStatus(issued, ready, controller(), 1000 + FOLLOW_MAX_S + 1, stale = false))
        assertEquals(WaterStatus.NoNews, waterStatus(issued, ready, controller(), 1000 + FOLLOW_MAX_S + 1, stale = true))
    }

    @Test
    fun `a slot or a sent dose still open after the wait is no news too`() {
        val atEdge = 1000 + FOLLOW_MAX_S
        val past = atEdge + 1
        assertEquals(WaterStatus.Queued, waterStatus(issued, ready, controller(command = InFlight(17)), atEdge, false))
        assertEquals(
            WaterStatus.Sent,
            waterStatus(issued, ready, controller(command = InFlight(17, state = "sent")), atEdge, false),
        )
        assertEquals(WaterStatus.NoNews, waterStatus(issued, ready, controller(command = InFlight(17)), past, false))
        assertEquals(
            WaterStatus.NoNews,
            waterStatus(issued, ready, controller(command = InFlight(17, state = "sent")), past, false),
        )
        assertEquals(WaterStatus.Sent, waterStatus(issued, ready.copy(lastDose = dose(17, "sent")), controller(), atEdge, false))
        assertEquals(WaterStatus.NoNews, waterStatus(issued, ready.copy(lastDose = dose(17, "sent")), controller(), past, false))
    }

    @Test
    fun `acked and expired are definitive however late they show`() {
        val past = 1000 + FOLLOW_MAX_S + 1
        assertEquals(
            WaterStatus.Done(96),
            waterStatus(issued, ready.copy(lastDose = dose(17, "acked", flowMl = 96)), controller(), past, false),
        )
        assertEquals(WaterStatus.Expired, waterStatus(issued, ready.copy(lastDose = dose(17, "expired")), null, past, true))
    }

    @Test
    fun `the status line in the pitch's words`() {
        assertEquals(
            "queued — b1 collects it on its next report, up to about three minutes",
            waterLine(WaterStatus.Queued, "b1"),
        )
        assertEquals(
            "handed to b1 — it waters, then confirms on its next report",
            waterLine(WaterStatus.Sent, "b1"),
        )
        // The wire says "ack"; a person should never have to.
        for (status in listOf(WaterStatus.Queued, WaterStatus.Sent, WaterStatus.Done(null), WaterStatus.Expired)) {
            assertFalse("ack" in waterLine(status, "b1"))
        }
        assertEquals("done — b1 poured 96 ml (meter)", waterLine(WaterStatus.Done(96), "b1"))
        assertEquals("done — confirmed by b1", waterLine(WaterStatus.Done(null), "b1"))
        assertEquals(
            "expired — b1 never confirmed it: maybe nothing poured, maybe it poured and the confirmation was lost",
            waterLine(WaterStatus.Expired, "b1"),
        )
        assertEquals("no news after 4 min — check the controllers card", waterLine(WaterStatus.NoNews, "b1"))
    }

    @Test
    fun `following lasts while the command is open and the wait is not over`() {
        assertTrue(stillFollowing(issued, null, 1010))
        assertTrue(stillFollowing(issued, WaterStatus.Queued, 1010))
        assertTrue(stillFollowing(issued, WaterStatus.Sent, 1000 + FOLLOW_MAX_S))
        assertFalse(stillFollowing(issued, WaterStatus.Sent, 1000 + FOLLOW_MAX_S + 1))
        assertFalse(stillFollowing(issued, WaterStatus.Done(96), 1010))
        assertFalse(stillFollowing(issued, WaterStatus.Expired, 1010))
        assertFalse(stillFollowing(issued, WaterStatus.NoNews, 1010))
        assertFalse(stillFollowing(null, null, 1010))
    }

    @Test
    fun `the confirmation names the pot, the dose and where it goes`() {
        assertEquals(
            "Water basil with 100 ml on board 0 outlet 3? Counts as today's watering.",
            waterDialogText(ready),
        )
        // No warning about a failure that has not happened: the dose's own
        // status line says so if and when it does.
        assertFalse("NAS" in waterDialogText(ready))
    }
}
