package garden.butler.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/** The view model against a fake butler on a real socket. Main is one real
 * thread (the model hops to Dispatchers.IO, which virtual time cannot
 * drive), so the tests wait for state rather than advance it. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class GardenViewModelTest {
    /** Routes by path, so a refresh the model fires after a write always
     * finds an answer; the knobs stand in for what the backend would have
     * changed underneath. */
    private class Butler : Dispatcher() {
        val nowS = System.currentTimeMillis() / 1000
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var nextS: Int? = null
        @Volatile var failPots = false
        @Volatile var potAnswer = MockResponse().setBody("pot=pot-1 name=basil\n")
        @Volatile var potsGate: CountDownLatch? = null
        @Volatile var failHistory = false
        @Volatile var proposal = false
        @Volatile var lastDose: String? = null
        @Volatile var commandAnswer = MockResponse().setBody("cmd=17\n")
        /** What b1's one slot holds, as /health shows it. */
        @Volatile var slot: String? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            return when (request.path) {
                "/pots" -> {
                    potsGate?.await(5, TimeUnit.SECONDS)
                    if (failPots) {
                        MockResponse().setResponseCode(503).setBody("try again: x\n")
                    } else {
                        MockResponse().setBody(
                            """{"pots": [
                                 {"id": "pot-1", "name": "basil", "controller": "b1", "channel": 0, "outlet": 3,
                                  "mode": "manual", "target_low_pct": 30, "dose_ml": 100,
                                  "raw": 9000, "read_ts": $nowS
                                  ${if (proposal) ", \"proposal\": {\"id\": 9, \"ml\": 100}" else ""}
                                  ${lastDose?.let { ", \"last_dose\": $it" } ?: ""}},
                                 {"id": "pot-2", "name": "mint", "controller": "b1", "channel": 1, "mode": "learning"}
                               ]}""",
                        )
                    }
                }
                "/health" ->
                    MockResponse().setBody(
                        """{"ok": true, "next_default": 60, "last_ts": $nowS,
                           "controllers": [{"controller": "b1", "last_seen": $nowS,
                                            "next_s": ${nextS ?: "null"}, "float": 1, "pos": "ok"
                                            ${slot?.let { ", \"command\": $it" } ?: ""}}]}""",
                    )
                "/pot" -> potAnswer
                "/command" -> commandAnswer
                "/history?c=b1&ch=0&hours=24&bucket_s=300" ->
                    if (failHistory) {
                        MockResponse().setResponseCode(503).setBody("try again: x\n")
                    } else {
                        MockResponse().setBody(
                            """{"controller": "b1", "channel": 0, "since": ${nowS - 86400}, "to": $nowS,
                                "bucket_s": 300,
                                "points": [{"ts": ${nowS - 600}, "raw": 9010, "lo": 9000, "hi": 9020, "n": 5},
                                           {"ts": ${nowS - 300}, "raw": 8990, "n": 4}]}""",
                        )
                    }
                "/interval" -> MockResponse().setBody("next=${request.body.copy().readUtf8().substringAfter("next=")}\n")
                else -> MockResponse().setResponseCode(404)
            }
        }

        fun sent(path: String) = requests.filter { it.path == path }

        fun histories() = requests.filter { it.path?.startsWith("/history") == true }

        fun posts() = requests.filter { it.method == "POST" }
    }

    private val main = newSingleThreadContext("main")
    private val server = MockWebServer()
    private val butler = Butler()
    private lateinit var model: GardenViewModel

    @BeforeTest
    fun start() {
        Dispatchers.setMain(main)
        server.dispatcher = butler
        server.start()
        model = GardenViewModel(Backend(server.url("/").toString(), token = "s3cret"))
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun onMain(block: GardenViewModel.() -> Unit) = runBlocking(Dispatchers.Main) { model.block() }

    private fun <T> onMainGet(block: GardenViewModel.() -> T): T = runBlocking(Dispatchers.Main) { model.block() }

    private fun <T : Any> waitFor(what: String, get: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            get()?.let { return it }
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    private fun settled(): UiState.Ready =
        waitFor("a settled garden") { (model.state.value as? UiState.Ready)?.takeIf { !it.refreshing } }

    private fun pot(): Screen.Pot = waitFor("a pot form") { model.screen.value as? Screen.Pot }

    private fun ready() {
        onMain { refresh() }
        settled()
    }

    @Test
    fun `a refresh asked for mid-fetch runs one more fetch afterwards`() {
        val gate = CountDownLatch(1)
        butler.potsGate = gate
        onMain { refresh() }
        waitFor("the first GET /pots") { butler.sent("/pots").firstOrNull() }
        onMain { refresh() }
        onMain { refresh() }
        gate.countDown()
        waitFor("the second fetch") { butler.sent("/pots").getOrNull(1) }
        settled()
        Thread.sleep(200)
        assertEquals(2, butler.sent("/pots").size)
        assertEquals(2, butler.sent("/health").size)
    }

    @Test
    fun `save posts only the changed fields and pops to the list`() {
        ready()
        onMain {
            open("pot-1")
            edit("target_low_pct", "35")
            save()
        }
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
        val post = butler.posts().single()
        assertEquals("/pot", post.path)
        assertEquals("s3cret", post.getHeader("X-Token"))
        assertEquals("id=pot-1 name=basil target_low_pct=35", post.body.readUtf8())
    }

    @Test
    fun `a rename posts the id with the new name and keeps the form on the same pot`() {
        ready()
        onMain {
            open("pot-1")
            edit("name", "genovese")
            save()
        }
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
        val post = butler.posts().single()
        assertEquals("/pot", post.path)
        assertEquals("id=pot-1 name=genovese", post.body.readUtf8())
        // The pot is still reachable under the id it was renamed through.
        onMain { open("pot-1") }
        assertEquals("pot-1", pot().id)
    }

    @Test
    fun `a rename onto another pot's name is refused before any POST`() {
        ready()
        onMain {
            open("pot-1")
            edit("name", "mint")
            save()
        }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.refused != null } }
        assertEquals("mint is another pot's name", form.refused)
        assertEquals(false, form.saving)
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `saving a pot under its own unchanged name is not a clash`() {
        ready()
        onMain {
            open("pot-1")
            edit("target_low_pct", "35")
            save()
        }
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
        assertEquals("id=pot-1 name=basil target_low_pct=35", butler.posts().single().body.readUtf8())
    }

    @Test
    fun `the wizard refuses to arm over an unsaved rename`() {
        ready()
        onMain {
            open("pot-1")
            edit("name", "genovese")
            startCalibration()
        }
        val form = waitFor("the note") { (model.screen.value as? Screen.Pot)?.takeIf { it.note != null } }
        assertEquals(
            "save or discard your changes first — the wizard calibrates the stored controller and channel",
            form.note,
        )
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `a new pot spelt like a stored one is refused before any POST`() {
        ready()
        onMain {
            newPot()
            edit("name", "basil")
            save()
        }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.refused != null } }
        assertEquals("basil already exists — open it from the list", form.refused)
        assertEquals(false, form.saving)
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `calibration refuses a pot that is not in manual and posts nothing`() {
        ready()
        onMain {
            open("pot-2")
            startCalibration()
        }
        val form = waitFor("the note") { (model.screen.value as? Screen.Pot)?.takeIf { it.note != null } }
        assertEquals("set the pot to manual first — the rules would water a sensor held in the air", form.note)
        assertEquals(false, form.saving)
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `calibration speeds the board up and forgets a leftover fast pace`() {
        butler.nextS = FAST_NEXT_S
        ready()
        onMain {
            open("pot-1")
            startCalibration()
        }
        val wizard = waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        assertEquals("c=b1 next=5", butler.posts().single().body.readUtf8())
        assertEquals("/interval", butler.posts().single().path)
        assertIs<CalState.SpeedingUp>(wizard.cal)
        assertNull(wizard.cal.prevNextS)
        assertEquals("pot-1", wizard.parent.id)
        assertEquals(false, wizard.parent.saving)
    }

    @Test
    fun `calibration remembers a real override to restore later`() {
        butler.nextS = 120
        ready()
        onMain {
            open("pot-1")
            startCalibration()
        }
        val wizard = waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        assertEquals("c=b1 next=5", butler.posts().single().body.readUtf8())
        assertEquals(120, wizard.cal.prevNextS)
    }

    @Test
    fun `cancelling from air restores the previous pace and pops to the form`() {
        butler.nextS = 120
        ready()
        onMain {
            open("pot-1")
            startCalibration()
        }
        waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        onMain {
            calEvent(CalEvent.Seen(9000, butler.nowS))
            calEvent(CalEvent.Seen(9010, butler.nowS + 5))
        }
        assertIs<CalState.Air>((model.screen.value as Screen.Calibrate).cal)
        onMain { calEvent(CalEvent.Cancel) }
        val form = pot()
        assertEquals("pot-1", form.id)
        assertNull(form.note)
        val intervals = butler.sent("/interval").map { it.body.readUtf8() }
        assertEquals(listOf("c=b1 next=5", "c=b1 next=120"), intervals)
    }

    @Test
    fun `a displayed garden survives a failed refresh with the reason on it`() {
        ready()
        val before = settled()
        butler.failPots = true
        onMain { refresh() }
        val after = waitFor("the failed refresh") { (model.state.value as? UiState.Ready)?.takeIf { it.why != null } }
        assertEquals("try again: x", after.why)
        assertEquals(false, after.refreshing)
        assertEquals(before.garden.pots.map { it.name }, after.garden.pots.map { it.name })
    }

    @Test
    fun `opening a mapped pot fetches its last day onto the form`() {
        ready()
        onMain { open("pot-1") }
        val form = waitFor("the curve") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        val get = butler.requests.single { it.path?.startsWith("/history") == true }
        assertEquals("/history?c=b1&ch=0&hours=24&bucket_s=300", get.path)
        assertEquals("GET", get.method)
        val history = assertNotNull(form.history)
        assertEquals(butler.nowS - 86400, history.since)
        assertEquals(butler.nowS, history.to)
        assertEquals(listOf(9010L, 8990L), history.points.map { it.raw })
        assertEquals(9, history.points.sumOf { it.n })
        assertNull(form.historyWhy)
    }

    @Test
    fun `a failed history fetch says why on the form and keeps it up`() {
        butler.failHistory = true
        ready()
        onMain { open("pot-1") }
        val form = waitFor("the reason") { (model.screen.value as? Screen.Pot)?.takeIf { it.historyWhy != null } }
        assertEquals("chart: try again: x", form.historyWhy)
        assertNull(form.history)
        assertEquals("pot-1", form.id)
    }

    @Test
    fun `a refresh reloads the open form's curve`() {
        ready()
        onMain { open("pot-1") }
        waitFor("the curve") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        onMain { refresh() }
        settled()
        waitFor("the reload") { butler.histories().getOrNull(1) }
        Thread.sleep(200)
        assertEquals(2, butler.histories().size)
        assertEquals("/history?c=b1&ch=0&hours=24&bucket_s=300", butler.histories()[1].path)
    }

    @Test
    fun `a failed reload keeps the curve up and says why beside it`() {
        ready()
        onMain { open("pot-1") }
        val before = waitFor("the curve") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        butler.failHistory = true
        onMain { refresh() }
        val after = waitFor("the reason") { (model.screen.value as? Screen.Pot)?.takeIf { it.historyWhy != null } }
        assertEquals("chart: try again: x", after.historyWhy)
        assertEquals(before.history, after.history)
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
        assertEquals(false, form.saving)
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
        assertEquals(false, form.saving)
        assertEquals(1, butler.posts().size)
        butler.slot = """{"id": 3, "state": "sent"}"""
        gate.countDown()
        waitFor("the refresh after it") { butler.sent("/health").getOrNull(1) }
        settled()
        assertEquals("busy: cmd=3 state=sent", pot().waterRefused)
        butler.slot = null
        onMain { refresh() }
        waitFor("the line gone") { (model.screen.value as? Screen.Pot)?.takeIf { it.waterRefused == null } }
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
        assertEquals("c=b1 water=3 ml=100", post.body.readUtf8())
        val issued = assertNotNull(form.watering)
        assertEquals(17, issued.id)
        assertEquals(true, issued.ts in butler.nowS..butler.nowS + 5)
        assertEquals(false, form.saving)
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
        assertEquals(false, form.saving)
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
    fun `a refused save keeps the form up with the backend's words`() {
        butler.potAnswer = MockResponse().setResponseCode(400).setBody("refused: x\n")
        ready()
        onMain {
            open("pot-1")
            edit("target_low_pct", "35")
            save()
        }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.refused != null } }
        assertEquals("refused: x", form.refused)
        assertEquals(false, form.saving)
        assertEquals("35", form.draft["target_low_pct"])
    }
}
