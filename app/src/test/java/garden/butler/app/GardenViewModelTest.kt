package garden.butler.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        @Volatile var potAnswer = MockResponse().setBody("pot=basil\n")
        @Volatile var potsGate: CountDownLatch? = null

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
                                 {"name": "basil", "controller": "b1", "channel": 0, "mode": "manual",
                                  "target_low_pct": 30, "raw": 9000, "read_ts": $nowS},
                                 {"name": "mint", "controller": "b1", "channel": 1, "mode": "learning"}
                               ]}""",
                        )
                    }
                }
                "/health" ->
                    MockResponse().setBody(
                        """{"ok": true, "next_default": 60, "last_ts": $nowS,
                           "controllers": [{"controller": "b1", "last_seen": $nowS,
                                            "next_s": ${nextS ?: "null"}, "float": 1, "pos": "ok"}]}""",
                    )
                "/pot" -> potAnswer
                "/interval" -> MockResponse().setBody("next=${request.body.copy().readUtf8().substringAfter("next=")}\n")
                else -> MockResponse().setResponseCode(404)
            }
        }

        fun sent(path: String) = requests.filter { it.path == path }

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
            open("basil")
            edit("target_low_pct", "35")
            save()
        }
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
        val post = butler.posts().single()
        assertEquals("/pot", post.path)
        assertEquals("s3cret", post.getHeader("X-Token"))
        assertEquals("name=basil target_low_pct=35", post.body.readUtf8())
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
            open("mint")
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
            open("basil")
            startCalibration()
        }
        val wizard = waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        assertEquals("c=b1 next=5", butler.posts().single().body.readUtf8())
        assertEquals("/interval", butler.posts().single().path)
        assertIs<CalState.SpeedingUp>(wizard.cal)
        assertNull(wizard.cal.prevNextS)
        assertEquals("basil", wizard.parent.name)
        assertEquals(false, wizard.parent.saving)
    }

    @Test
    fun `calibration remembers a real override to restore later`() {
        butler.nextS = 120
        ready()
        onMain {
            open("basil")
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
            open("basil")
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
        assertEquals("basil", form.name)
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
    fun `a refused save keeps the form up with the backend's words`() {
        butler.potAnswer = MockResponse().setResponseCode(400).setBody("refused: x\n")
        ready()
        onMain {
            open("basil")
            edit("target_low_pct", "35")
            save()
        }
        val form = waitFor("the refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.refused != null } }
        assertEquals("refused: x", form.refused)
        assertEquals(false, form.saving)
        assertEquals("35", form.draft["target_low_pct"])
    }
}
