package garden.butler.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

/** First start, and changing butler afterwards.
 *
 * Two fake butlers on two real sockets, because half of what this pitch is
 * about only exists when there are two: the cache belongs to one of them,
 * and an answer from the old one must never land on the new one's screen.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class SetupFlowTest {
    /** A butler with one pot, whose name says which butler it is. */
    private class Fake(val plant: String, val token: String = "s3cret") : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var potsGate: CountDownLatch? = null
        @Volatile var helloAnswer: MockResponse? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val given = request.getHeader("X-Token").orEmpty()
            return when (request.path) {
                "/hello" ->
                    helloAnswer
                        ?: if (given == token) {
                            MockResponse().setBody("butler=0.14.0\n")
                        } else {
                            MockResponse().setResponseCode(401).setBody("bad token\n")
                        }
                "/pots" -> {
                    potsGate?.await(5, TimeUnit.SECONDS)
                    MockResponse().setBody(
                        """{"pots": [{"id": "pot-1", "name": "$plant", "mode": "manual"}]}""",
                    )
                }
                "/health" ->
                    MockResponse().setBody("""{"ok": true, "next_default": 60, "controllers": []}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        fun sent(path: String) = requests.filter { it.path == path }
    }

    /** What the phone holds, in memory. */
    private class FakeSettings(var held: ButlerConfig? = null) : ConfigStore {
        val writes = CopyOnWriteArrayList<ButlerConfig>()
        @Volatile var refuse: String? = null

        override fun read(): ButlerConfig? = held

        override fun write(config: ButlerConfig) {
            refuse?.let { throw IllegalStateException(it) }
            writes += config
            held = config
        }
    }

    private class FakeCache(var held: CachedGarden? = null) : GardenCache {
        val writes = CopyOnWriteArrayList<CachedGarden>()
        @Volatile var cleared = 0

        override fun read(): CachedGarden? = held

        override fun write(cached: CachedGarden) {
            writes += cached
            held = cached
        }

        override fun clear() {
            cleared++
            held = null
        }
    }

    private val main = newSingleThreadContext("main")
    private val one = MockWebServer()
    private val two = MockWebServer()
    private val here = Fake("basil")
    private val there = Fake("mint", token = "other")
    private val settings = FakeSettings()
    private val cache = FakeCache()
    private lateinit var model: GardenViewModel

    @BeforeTest
    fun start() {
        Dispatchers.setMain(main)
        one.dispatcher = here
        two.dispatcher = there
        one.start()
        two.start()
    }

    @AfterTest
    fun stop() {
        one.shutdown()
        two.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun url(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    /** Built here rather than in @BeforeTest: what is already stored is
     * what each test is about, and the view model reads it on construction. */
    private fun launch() {
        model =
            GardenViewModel(
                Backend(),
                cache = cache,
                settings = settings,
                defaults = ButlerConfig("", ""),
            )
    }

    private fun onMain(block: GardenViewModel.() -> Unit) =
        runBlocking(Dispatchers.Main) { model.block() }

    private fun <T : Any> waitFor(what: String, get: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            get()?.let { return it }
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    private fun setup(): Screen.Setup = waitFor("the setup screen") { model.screen.value as? Screen.Setup }

    private fun settled(): UiState.Ready =
        waitFor("a settled garden") { (model.state.value as? UiState.Ready)?.takeIf { !it.refreshing } }

    private fun plants() = settled().garden.all().map { it.name }

    /** The repoint is asynchronous and the old garden is still on screen
     * while it runs, so a bare settled() would read the butler this test is
     * moving away from. */
    private fun plantsBecome(expected: List<String>) {
        waitFor("the garden to become $expected") {
            (model.state.value as? UiState.Ready)?.takeIf {
                !it.refreshing && it.garden.all().map { pot -> pot.name } == expected
            }
        }
    }

    private fun typeAndSave(server: MockWebServer, token: String) {
        setup()
        onMain {
            editSetup(url = url(server), token = token)
            saveSetup()
        }
    }

    // ----------------------------------------------------------------- //
    // First start

    @Test
    fun `a phone that has never been told asks, and talks to nobody`() {
        launch()
        val screen = setup()
        assertTrue(screen.first)
        // The minute loop and a pull both fire regardless of what is on
        // screen. Neither may reach a butler whose address is a guess.
        onMain { refresh() }
        onMain { openCache() }
        Thread.sleep(200)
        assertTrue(here.requests.isEmpty())
        assertTrue(there.requests.isEmpty())
    }

    @Test
    fun `an address and a token that work are stored and the garden loads`() {
        launch()
        typeAndSave(one, "s3cret")
        plantsBecome(listOf("basil"))
        assertEquals(ButlerConfig(url(one), "s3cret"), settings.writes.single())
        assertEquals(Screen.List, model.screen.value)
        assertEquals(1, here.sent("/hello").size)
    }

    @Test
    fun `a stored address starts straight in the garden`() {
        settings.held = ButlerConfig(url(one), "s3cret")
        launch()
        assertEquals(listOf("basil"), plants())
        // Nothing is proved on a start that already knows: the first real
        // read is the proof, and it says so in its own words when it fails.
        assertTrue(here.sent("/hello").isEmpty())
    }

    @Test
    fun `a half-stored config is no config`() {
        // A stored address with no token would start into a garden that
        // refuses every write, which reads as the butler being broken
        // rather than as a question nobody finished answering.
        settings.held = ButlerConfig(url(one), "")
        launch()
        assertTrue(setup().first)
        assertTrue(here.requests.isEmpty())
    }

    // ----------------------------------------------------------------- //
    // The two mistakes

    @Test
    fun `a wrong token says the address is right`() {
        launch()
        typeAndSave(one, "wrong")
        val screen = waitFor("the refusal") { setup().takeIf { !it.checking && it.why != null } }
        assertTrue(screen.why!!.contains("refused that token"), screen.why!!)
        assertTrue(screen.why!!.contains("The address is right"), screen.why!!)
        // Nothing stored, and no garden loaded behind the screen.
        assertTrue(settings.writes.isEmpty())
        assertTrue(here.sent("/pots").isEmpty())
    }

    @Test
    fun `a dead address says so, and names the tailnet`() {
        launch()
        val dead = MockWebServer()
        dead.start()
        val address = url(dead)
        dead.shutdown()
        setup()
        onMain {
            editSetup(url = address, token = "s3cret")
            saveSetup()
        }
        val screen = waitFor("the refusal") { setup().takeIf { !it.checking && it.why != null } }
        assertTrue(screen.why!!.contains("nothing answered"), screen.why!!)
        assertTrue(screen.why!!.contains("tailnet"), screen.why!!)
    }

    @Test
    fun `something that is not a butler is not taken for one`() {
        here.helloAnswer = MockResponse().setBody("<html>router</html>")
        launch()
        typeAndSave(one, "s3cret")
        val screen = waitFor("the refusal") { setup().takeIf { !it.checking && it.why != null } }
        assertTrue(screen.why!!.contains("not your butler"), screen.why!!)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `a nonsense address is refused without a socket`() {
        launch()
        setup()
        onMain {
            editSetup(url = "ftp://butler", token = "s3cret")
            saveSetup()
        }
        val screen = waitFor("the refusal") { setup().takeIf { it.why != null } }
        assertTrue(screen.why!!.contains("ftp://"), screen.why!!)
        assertTrue(here.requests.isEmpty())
    }

    @Test
    fun `an empty token is asked for before anything is dialled`() {
        launch()
        setup()
        onMain {
            editSetup(url = url(one), token = "  ")
            saveSetup()
        }
        assertNotNull(waitFor("the refusal") { setup().why })
        assertTrue(here.requests.isEmpty())
    }

    @Test
    fun `a butler that answers but a phone that cannot store it says which`() {
        settings.refuse = "keystore gone"
        launch()
        typeAndSave(one, "s3cret")
        val screen = waitFor("the refusal") { setup().takeIf { !it.checking && it.why != null } }
        assertTrue(screen.why!!.contains("could not store"), screen.why!!)
        // And the app did not repoint itself at a butler it cannot remember.
        assertTrue(here.sent("/pots").isEmpty())
    }

    // ----------------------------------------------------------------- //
    // Changing butler

    @Test
    fun `pointing the app elsewhere forgets the old garden`() {
        settings.held = ButlerConfig(url(one), "s3cret")
        launch()
        assertEquals(listOf("basil"), plants())
        assertTrue(cache.writes.isNotEmpty())
        onMain { openSettings() }
        val screen = setup()
        assertTrue(!screen.first)
        // The stored token is in the field: moving the NAS must not mean
        // typing a secret again. Dots on screen, and this is the value.
        assertEquals("s3cret", screen.token)
        onMain {
            editSetup(url = url(two), token = "other")
            saveSetup()
        }
        plantsBecome(listOf("mint"))
        assertEquals(1, cache.cleared)
        assertEquals(ButlerConfig(url(two), "other"), settings.held)
    }

    @Test
    fun `the old butler's cache is not the new one's garden`() {
        // The pitch's rabbit hole, from the other end: the cache survived
        // the repoint somehow — a delete that failed, or a kill in between.
        settings.held = ButlerConfig(url(two), "other")
        cache.held =
            CachedGarden(
                listOf(Pot(id = "pot-9", name = "someone else's fern")),
                Health(),
                atS = 1,
                url = url(one),
            )
        launch()
        assertEquals(listOf("mint"), plants())
        assertTrue(model.state.value.let { it as UiState.Ready }.cachedAtS == null)
    }

    @Test
    fun `a cache from this butler is still opened`() {
        settings.held = ButlerConfig(url(one), "s3cret")
        here.potsGate = CountDownLatch(1)
        cache.held =
            CachedGarden(
                listOf(Pot(id = "pot-1", name = "basil")),
                Health(),
                atS = 1,
                url = url(one),
            )
        launch()
        val cached = waitFor("the cached garden") { (model.state.value as? UiState.Ready)?.cachedAtS }
        assertEquals(1, cached)
        here.potsGate!!.countDown()
        assertEquals(listOf("basil"), plants())
    }

    @Test
    fun `an answer from the old butler never lands on the new one's screen`() {
        // The rabbit hole about the address no longer being a build
        // constant, in its sharpest form: a slow /pots issued against the
        // old address, still in the air when the app is pointed elsewhere.
        settings.held = ButlerConfig(url(one), "s3cret")
        val gate = CountDownLatch(1)
        launch()
        assertEquals(listOf("basil"), plants())
        here.potsGate = gate
        onMain { refresh() }
        waitFor("the slow GET /pots") { here.sent("/pots").getOrNull(1) }
        onMain { openSettings() }
        setup()
        onMain {
            editSetup(url = url(two), token = "other")
            saveSetup()
        }
        plantsBecome(listOf("mint"))
        gate.countDown() // the old butler finally answers
        Thread.sleep(300)
        assertEquals(listOf("mint"), plants())
        assertNull((model.state.value as UiState.Ready).cachedAtS)
    }

    @Test
    fun `back from settings leaves the garden alone`() {
        settings.held = ButlerConfig(url(one), "s3cret")
        launch()
        assertEquals(listOf("basil"), plants())
        onMain { openSettings() }
        setup()
        onMain {
            editSetup(url = url(two), token = "other")
            back()
        }
        assertEquals(Screen.List, model.screen.value)
        assertEquals(listOf("basil"), plants())
        assertEquals(ButlerConfig(url(one), "s3cret"), settings.held)
        assertEquals(0, cache.cleared)
    }

    @Test
    fun `back on the first screen is not a way past it`() {
        launch()
        setup()
        onMain { back() }
        assertIs<Screen.Setup>(model.screen.value)
    }
}
