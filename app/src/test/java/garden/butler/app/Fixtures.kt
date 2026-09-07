package garden.butler.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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

// ---------------------------------------------------------------------- //
// The wire shapes, with a default for everything a test is not about.

fun pot(
    name: String = "basil",
    id: String = "pot-$name",
    status: String = ALIVE,
    controller: Int? = null,
    channel: Int? = null,
    outlet: Int? = null,
    mode: String = "manual",
    doseMl: Int? = null,
    pct: Int? = null,
    raw: Long? = null,
    readTs: Long? = null,
) = Pot(
    id = id,
    name = name,
    status = status,
    controller = controller,
    channel = channel,
    outlet = outlet,
    mode = mode,
    doseMl = doseMl,
    pct = pct,
    raw = raw,
    readTs = readTs,
)

fun controller(
    name: Int = 0,
    lastSeen: Long = 990,
    nextS: Int? = null,
    float: Int? = null,
    pos: String? = null,
    command: InFlight? = null,
    latched: Latch? = null,
    lastRefill: Long? = null,
    retired: Int = 0,
    posOkSeen: Long? = null,
    tankMl: Int? = null,
    tankSamples: Int? = null,
    pumpedMl: Int = 0,
    over: Int = 0,
    flap: Int = 0,
) = ControllerHealth(
    name, lastSeen, nextS, float, pos, command,
    latched = latched, lastRefill = lastRefill, retired = retired, posOkSeen = posOkSeen,
    tankMl = tankMl, tankSamples = tankSamples, pumpedMl = pumpedMl, over = over, flap = flap,
)

/** The dose hanging off a pot in `/pots`. */
fun lastDose(
    id: Long = 16,
    state: String = "acked",
    ml: Int? = 100,
    flowMl: Int? = null,
    source: String? = null,
    sentTs: Long? = null,
    ackedTs: Long? = null,
    verdict: String? = null,
) = LastDose(id, ml, flowMl, state, source, sentTs, ackedTs, verdict)

/** A row of `/doses`, which carries the pot it was attributed to. */
fun dose(
    id: Long = 1,
    ml: Int? = 100,
    flowMl: Int? = null,
    state: String = "acked",
    source: String? = "manual",
    sentTs: Long? = 1000,
    ackedTs: Long? = 1010,
    createdTs: Long? = 990,
    verdict: String? = null,
    potName: String? = "basil",
    kind: String = "water",
) = Dose(id, kind, ml, flowMl, state, source, createdTs, sentTs, ackedTs, verdict, potName)

// ---------------------------------------------------------------------- //
// The fake butler, and the scaffolding every test that drives one needs.

/** A MockWebServer answering from a queue, with a Backend pointed at it. */
fun withServer(block: (MockWebServer, Backend) -> Unit) {
    MockWebServer().use { server ->
        server.start()
        block(server, Backend(server.url("/").toString(), token = "s3cret"))
    }
}

/** Routes by path, so a refresh the model fires after a write always
 * finds an answer; the knobs stand in for what the backend would have
 * changed underneath. */
class Butler : Dispatcher() {
    val nowS = System.currentTimeMillis() / 1000
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val deleted = CopyOnWriteArrayList<String>()
    @Volatile var nextS: Int? = null
    @Volatile var failPots = false
    @Volatile var potAnswer = MockResponse().setBody("pot=pot-1 name=basil\n")
    @Volatile var potsGate: CountDownLatch? = null
    @Volatile var potGate: CountDownLatch? = null
    @Volatile var failHistory = false
    @Volatile var failDoses = false
    @Volatile var dosesSayNow = true
    /** A first page as full as the app asks for, so there is a second. */
    @Volatile var dosesPageFull = false
    @Volatile var dosesGate: CountDownLatch? = null
    @Volatile var proposal = false
    @Volatile var lastDose: String? = null
    @Volatile var commandAnswer = MockResponse().setBody("cmd=17\n")
    @Volatile var resumeAnswer = MockResponse().setBody("resumed=0\n")
    /** What board 0's one slot holds, as /health shows it. */
    @Volatile var slot: String? = null
    /** The band the backend would offer pot-1, as /pots carries it. */
    @Volatile var advice: String? = null
    /** The pot's photographs, as the strip would be sent them. */
    val photos = CopyOnWriteArrayList<String>()
    @Volatile var failPhotos = false
    @Volatile var photoAnswer = MockResponse().setBody("photo=photo-new ts=1757000000\n")
    @Volatile var photoGate: CountDownLatch? = null
    @Volatile var speciesAnswer =
        MockResponse().setBody(
            """{"query": "basil", "matched": "common", "accepted": "Ocimum basilicum",
                "care": {"found": true, "light": 7, "common_name": "Basil"},
                "candidates": [], "note": "Trefle: Ocimum basilicum"}""",
        )

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
                             {"id": "pot-1", "name": "basil", "controller": 0, "channel": 0, "outlet": 3,
                              "mode": "manual", "target_low_pct": 30, "dose_ml": 100,
                              "raw": 9000, "read_ts": $nowS
                              ${if (proposal) ", \"proposal\": {\"id\": 9, \"ml\": 100}" else ""}
                              ${lastDose?.let { ", \"last_dose\": $it" } ?: ""}
                              ${advice?.let { ", \"advice\": $it" } ?: ""}},
                             {"id": "pot-2", "name": "mint", "controller": 0, "channel": 1, "mode": "learning"},
                             {"id": "pot-3", "name": "fern", "status": "graveyard",
                              "photo": "photo-abc123"}
                           ]}""",
                    )
                }
            }
            "/health" ->
                MockResponse().setBody(
                    """{"ok": true, "next_default": 60, "last_ts": $nowS,
                       "controllers": [{"controller": 0, "last_seen": $nowS,
                                        "next_s": ${nextS ?: "null"}, "float": 1, "pos": "ok"
                                        ${slot?.let { ", \"command\": $it" } ?: ""}}]}""",
                )
            "/pot" -> {
                potGate?.await(5, TimeUnit.SECONDS)
                potAnswer
            }
            "/command" -> commandAnswer
            "/refill" -> MockResponse().setBody("refill=1757000000\n")
            "/resume" -> resumeAnswer
            "/photo/delete" -> {
                photos.remove(
                    request.body.copy().readUtf8().trim().removePrefix("photo="),
                )
                MockResponse().setBody("ok\n")
            }
            "/advice" -> MockResponse().setBody("ok\n")
            "/pot/delete" -> {
                deleted += request.body.copy().readUtf8().trim().removePrefix("id=")
                MockResponse().setBody("ok\n")
            }
            "/interval" -> {
                // As the backend answers: the effective pace, and next=0
                // clears the override back to next_default.
                val asked = request.body.copy().readUtf8().substringAfter("next=").trim().toInt()
                MockResponse().setBody("next=${if (asked == 0) 60 else asked}\n")
            }
            else ->
                if (request.path?.startsWith("/photos") == true) {
                    if (failPhotos) {
                        MockResponse().setResponseCode(503).setBody("try again: x\n")
                    } else {
                        val rows =
                            photos.mapIndexed { i, id ->
                                """{"id": "$id", "ts": ${nowS - 1000 + i}, "bytes": 400000,
                                    "species": "Ocimum_basilicum"}"""
                            }
                        MockResponse().setBody(
                            """{"pot": "pot-1", "more": false, "now": $nowS,
                                "photos": [${rows.joinToString(",")}]}""",
                        )
                    }
                } else if (request.path?.startsWith("/photo?") == true) {
                    photoGate?.await(5, TimeUnit.SECONDS)
                    photos += "photo-new"
                    photoAnswer
                } else if (request.path?.startsWith("/species") == true) {
                    speciesAnswer
                } else if (request.path?.startsWith("/history") == true) {
                    if (failHistory) {
                        MockResponse().setResponseCode(503).setBody("try again: x\n")
                    } else {
                        // Answers whatever window was asked for, so a test
                        // can tell one from another by what comes back.
                        val q = request.path!!.substringAfter("?").split("&").associate {
                            it.substringBefore("=") to it.substringAfter("=")
                        }
                        val hours = q["hours"]!!.toLong()
                        val bucket = q["bucket_s"]!!.toInt()
                        MockResponse().setBody(
                            """{"controller": 0, "channel": 0, "since": ${nowS - hours * 3600},
                                "to": $nowS, "bucket_s": $bucket,
                                "points": [{"ts": ${nowS - 600}, "raw": 9010, "lo": 9000, "hi": 9020, "n": 5},
                                           {"ts": ${nowS - 300}, "raw": 8990, "n": 4}]}""",
                        )
                    }
                } else if (request.path?.startsWith("/doses") == true) {
                    dosesFor(request.path!!)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
    }

    /** Two rows without a cursor, one older row behind it with one —
     * unless dosesPageFull, when the first page is as long as the app
     * asked for and the second one ends it. */
    private fun dosesFor(path: String): MockResponse {
        dosesGate?.takeIf { "before=" in path }?.await(5, TimeUnit.SECONDS)
        if (failDoses) return MockResponse().setResponseCode(503).setBody("try again: x\n")
        val now = if (dosesSayNow) "\"now\": $nowS," else ""
        if (dosesPageFull) {
            val ids = if ("before=" in path) listOf(1L) else (1..DOSES_LIMIT).map { 1000L - it }
            val rows = ids.joinToString(",") { id ->
                """{"id": $id, "ml": 100, "state": "acked", "flow_ml": 100,
                     "sent_ts": ${nowS - id}, "acked_ts": ${nowS - id},
                     "pot": "pot-1", "pot_name": "basil"}"""
            }
            return MockResponse().setBody("""{$now "doses": [$rows]}""")
        }
        return MockResponse().setBody(
            if ("before=" in path) {
                """{$now "doses": [
                     {"id": 3, "ml": 50, "state": "acked", "flow_ml": 50,
                      "sent_ts": ${nowS - 90000}, "acked_ts": ${nowS - 89990},
                      "pot": "pot-1", "pot_name": "basil"}
                   ]}"""
            } else {
                """{$now "doses": [
                     {"id": 7, "ml": 100, "cap_s": 30, "flow_ml": 96,
                      "state": "acked", "source": "manual",
                      "sent_ts": ${nowS - 600}, "acked_ts": ${nowS - 590},
                      "pot": "pot-1", "pot_name": "basil"},
                     {"id": 6, "ml": 100, "state": "expired",
                      "sent_ts": ${nowS - 4000}, "pot": null, "pot_name": null}
                   ]}"""
            },
        )
    }

    fun sent(path: String) = requests.filter { it.path == path }

    fun histories() = requests.filter { it.path?.startsWith("/history") == true }

    fun lookups() = requests.filter { it.path?.startsWith("/species") == true }

    fun strips() = requests.filter { it.path?.startsWith("/photos") == true }

    fun uploads() = requests.filter { it.path?.startsWith("/photo?") == true }

    fun posts() = requests.filter { it.method == "POST" }
}

/** A cache in memory: the file one has its own test, and this keeps the
 * view model's tests about what it does with a hit, not about disk. */
class FakeCache(var held: CachedGarden? = null) : GardenCache {
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

/** The view model on a real Main thread. Main is one real thread (the model
 * hops to Dispatchers.IO, which virtual time cannot drive), so the tests
 * wait for state rather than advance it. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
abstract class ModelTest {
    private val main = newSingleThreadContext("main")
    lateinit var model: GardenViewModel

    @BeforeTest
    fun setUpMain() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDownMain() {
        Dispatchers.resetMain()
        main.close()
    }

    fun onMain(block: GardenViewModel.() -> Unit) = runBlocking(Dispatchers.Main) { model.block() }

    fun <T> onMainGet(block: GardenViewModel.() -> T): T = runBlocking(Dispatchers.Main) { model.block() }

    fun <T : Any> waitFor(what: String, get: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            get()?.let { return it }
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    fun settled(): UiState.Ready =
        waitFor("a settled garden") { (model.state.value as? UiState.Ready)?.takeIf { !it.refreshing } }
}

/** The view model against a fake butler on a real socket. */
abstract class ButlerTest : ModelTest() {
    val server = MockWebServer()
    val butler = Butler()

    @BeforeTest
    fun startButler() {
        server.dispatcher = butler
        server.start()
        model = GardenViewModel(Backend(server.url("/").toString(), token = "s3cret"))
    }

    @AfterTest
    fun stopButler() {
        server.shutdown()
    }

    fun potForm(): Screen.Pot = waitFor("a pot form") { model.screen.value as? Screen.Pot }

    fun ready() {
        onMain { refresh() }
        settled()
    }

    /** Stamped with the butler it came from, as every real write is: a
     * cache is opened only by the address that wrote it. */
    fun cached(pots: List<Pot>, health: Health, atS: Long) =
        CachedGarden(pots, health, atS = atS, url = server.url("/").toString())

    fun withCache(cache: FakeCache): GardenViewModel =
        GardenViewModel(Backend(server.url("/").toString(), token = "s3cret"), cache = cache)
}
