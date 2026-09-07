package garden.butler.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy

/** Watering by hand: what is posted, what comes back, and how long the
 * app follows the command it issued. */
class WaterFlowTest : ButlerTest() {
    @Test
    fun `water posts the stored dose with the token and follows the command`() {
        ready()
        onMain {
            open("pot-1")
            water()
        }
        val form = waitFor("the issued command") { (model.screen.value as? Screen.Pot)?.takeIf { it.watering != null } }
        val post = butler.posts().single()
        assertEquals("/command", post.path)
        assertEquals("s3cret", post.getHeader("X-Token"))
        assertEquals("c=0 water=3 ml=100", post.body.readUtf8())
        val issued = assertNotNull(form.watering)
        assertEquals(17, issued.id)
        assertEquals(true, issued.ts in butler.nowS..butler.nowS + 5)
        assertEquals(false, form.busy)
        assertNull(form.waterRefused)
        waitFor("the refresh after it") { butler.sent("/pots").getOrNull(1) }
        settled()
    }

    @Test
    fun `water with a proposal waiting posts nothing and says so`() {
        butler.proposal = true
        ready()
        onMain {
            open("pot-1")
            water()
        }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.waterRefused != null } }
        assertEquals("a proposal is waiting above — approve it or let it expire", form.waterRefused)
        assertNull(form.watering)
        assertEquals(false, form.busy)
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `the issued command reads as done once the pot's last dose acks it`() {
        ready()
        onMain {
            open("pot-1")
            water()
        }
        val form = waitFor("the issued command") { (model.screen.value as? Screen.Pot)?.takeIf { it.watering != null } }
        assertNull(model.currentWaterStatus(form).takeIf { it is WaterStatus.Done })
        butler.lastDose = """{"id": 17, "ml": 100, "cap_s": 30, "flow_ml": 96, "state": "acked",
                               "source": "manual", "sent_ts": ${butler.nowS - 60}, "acked_ts": ${butler.nowS}}"""
        onMain { followWater() }
        val done = waitFor("the ack") { model.currentWaterStatus(form) as? WaterStatus.Done }
        assertEquals(WaterStatus.Done(96), done)
        assertEquals(false, stillFollowing(form.watering, done, model.nowS()))
    }

    @Test
    fun `the wait is measured on the phone clock, whatever the backend's says`() {
        val phoneNow = AtomicLong(butler.nowS)
        model = GardenViewModel(Backend(server.url("/").toString(), token = "s3cret"), clock = { phoneNow.get() })
        ready()
        onMain {
            open("pot-1")
            water()
        }
        val form = waitFor("the issued command") { (model.screen.value as? Screen.Pot)?.takeIf { it.watering != null } }
        val issued = assertNotNull(form.watering)
        assertEquals(phoneNow.get(), issued.ts)
        butler.slot = """{"id": 17, "state": "queued"}"""
        onMain { followWater() }
        val queued = waitFor("the slot") { model.currentWaterStatus(form).takeIf { it == WaterStatus.Queued } }
        assertEquals(true, stillFollowing(issued, queued, model.phoneS()))
        // last_ts stays where it was: only the phone's clock moves on.
        phoneNow.set(issued.ts + FOLLOW_MAX_S + 1)
        val status = onMainGet { currentWaterStatus(form) }
        assertEquals(WaterStatus.NoNews, status)
        assertEquals(false, stillFollowing(issued, status, model.phoneS()))
    }

    @Test
    fun `no answer to the water POST says so and refreshes anyway`() {
        butler.commandAnswer = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        ready()
        onMain { open("pot-1") }
        val gate = CountDownLatch(1) // the refresh after it would clear the line: hold it
        butler.potsGate = gate
        onMain { water() }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.waterRefused != null } }
        assertEquals(
            "no answer from the butler — it may still have queued the dose; check the controllers card",
            form.waterRefused,
        )
        assertNull(form.watering)
        assertEquals(false, form.busy)
        assertEquals(setOf("/command"), butler.posts().map { it.path }.toSet())
        waitFor("the refresh after it") { butler.sent("/pots").getOrNull(1) }
        gate.countDown()
        settled()
    }

    @Test
    fun `a taken slot lands the backend's busy line verbatim, until the slot is free`() {
        butler.commandAnswer = MockResponse().setResponseCode(409).setBody("busy: cmd=3 state=sent\n")
        ready()
        onMain { open("pot-1") }
        // The refresh after the POST is held so the slot can turn busy underneath it.
        val gate = CountDownLatch(1)
        butler.potsGate = gate
        onMain { water() }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.waterRefused != null } }
        assertEquals("busy: cmd=3 state=sent", form.waterRefused)
        assertNull(form.watering)
        assertEquals(false, form.busy)
        assertEquals(1, butler.posts().size)
        butler.slot = """{"id": 3, "state": "sent"}"""
        gate.countDown()
        waitFor("the refresh after it") { butler.sent("/health").getOrNull(1) }
        settled()
        assertEquals("busy: cmd=3 state=sent", potForm().waterRefused)
        butler.slot = null
        onMain { refresh() }
        waitFor("the line gone") { (model.screen.value as? Screen.Pot)?.takeIf { it.waterRefused == null } }
    }
}
