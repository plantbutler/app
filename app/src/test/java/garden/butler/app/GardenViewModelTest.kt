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
        /** What b1's one slot holds, as /health shows it. */
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
                                 {"id": "pot-1", "name": "basil", "controller": "b1", "channel": 0, "outlet": 3,
                                  "mode": "manual", "target_low_pct": 30, "dose_ml": 100,
                                  "raw": 9000, "read_ts": $nowS
                                  ${if (proposal) ", \"proposal\": {\"id\": 9, \"ml\": 100}" else ""}
                                  ${lastDose?.let { ", \"last_dose\": $it" } ?: ""}
                                  ${advice?.let { ", \"advice\": $it" } ?: ""}},
                                 {"id": "pot-2", "name": "mint", "controller": "b1", "channel": 1, "mode": "learning"},
                                 {"id": "pot-3", "name": "fern", "status": "graveyard"}
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
                "/pot" -> {
                    potGate?.await(5, TimeUnit.SECONDS)
                    potAnswer
                }
                "/command" -> commandAnswer
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
                "/interval" -> MockResponse().setBody("next=${request.body.copy().readUtf8().substringAfter("next=")}\n")
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
                                """{"controller": "b1", "channel": 0, "since": ${nowS - hours * 3600},
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
    private class FakeCache(var held: CachedGarden? = null) : GardenCache {
        val writes = CopyOnWriteArrayList<CachedGarden>()

        override fun read(): CachedGarden? = held

        override fun write(cached: CachedGarden) {
            writes += cached
            held = cached
        }

        override fun clear() {
            held = null
        }
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

    // ------------------------------------------------------------------ //
    // A picture of the plant, over time

    @Test
    fun `opening a pot reads its strip`() {
        butler.photos += "photo-a"
        ready()
        onMain { open("pot-1") }
        val photos = waitFor("the strip") { pot().photos }
        assertEquals(listOf("photo-a"), photos.map { it.id })
        assertEquals("pot-1", butler.strips().single().path?.substringAfter("pot=")?.substringBefore("&"))
    }

    @Test
    fun `a picture goes up and the strip is re-read`() {
        ready()
        onMain { open("pot-1") }
        waitFor("the first strip") { pot().photos }
        onMain { addPhoto(byteArrayOf(1, 2, 3), 1600, 1200) }
        val photos = waitFor("the picture") { pot().photos?.takeIf { it.isNotEmpty() } }
        assertEquals(listOf("photo-new"), photos.map { it.id })
        val upload = butler.uploads().single()
        assertEquals(3, upload.bodySize)
        assertEquals("image/jpeg", upload.getHeader("Content-Type"))
        assertTrue(upload.path!!.contains("w=1600"), upload.path!!)
        assertTrue(upload.path!!.contains("h=1200"), upload.path!!)
        assertEquals("s3cret", upload.getHeader("X-Token"))
    }

    @Test
    fun `an upload that is refused says so and still re-reads the strip`() {
        // A POST that timed out client-side may still have stored the
        // picture: the strip is the only thing that knows which happened.
        butler.photoAnswer = MockResponse().setResponseCode(507).setBody("refused: no space left\n")
        ready()
        onMain { open("pot-1") }
        waitFor("the first strip") { pot().photos }
        onMain { addPhoto(byteArrayOf(1), 800, 600) }
        val note = waitFor("the refusal") { pot().note }
        assertTrue(note.contains("no space left"), note)
        assertEquals(2, butler.strips().size)
        assertTrue(!pot().uploading)
    }

    @Test
    fun `a picture cannot be taken of a pot that has not been saved`() {
        ready()
        onMain { newPot() }
        onMain { addPhoto(byteArrayOf(1), 800, 600) }
        assertEquals("save the pot first", waitFor("the refusal") { pot().note })
        assertTrue(butler.uploads().isEmpty())
    }

    @Test
    fun `forgetting a picture takes it off the strip`() {
        butler.photos += "photo-a"
        butler.photos += "photo-b"
        ready()
        onMain { open("pot-1") }
        waitFor("the strip") { pot().photos?.takeIf { it.size == 2 } }
        onMain { viewPhoto("photo-a") }
        assertEquals("photo-a", pot().viewing)
        onMain { deletePhoto("photo-a") }
        val left = waitFor("the shorter strip") { pot().photos?.takeIf { it.size == 1 } }
        assertEquals(listOf("photo-b"), left.map { it.id })
        assertNull(pot().viewing)
    }

    @Test
    fun `a strip that will not load keeps the form and says why`() {
        butler.failPhotos = true
        ready()
        onMain { open("pot-1") }
        val why = waitFor("the reason") { pot().photosWhy }
        assertTrue(why.startsWith("pictures:"), why)
        assertNull(pot().photos)
    }

    @Test
    fun `an upload that lands after the user moved on reloads nobody else's strip`() {
        // Async outcomes land only on the form they came from, and a reload
        // is an outcome like any other: reloading whatever is on screen
        // would cancel the other pot's own fetch to re-ask a question
        // nobody asked.
        val gate = CountDownLatch(1)
        butler.photoGate = gate
        ready()
        onMain { open("pot-1") }
        waitFor("pot-1's strip") { pot().photos }
        onMain { addPhoto(byteArrayOf(1), 800, 600) }
        waitFor("the upload") { butler.uploads().firstOrNull() }
        onMain { back() }
        onMain { open("pot-2") }
        waitFor("pot-2's strip") { pot().photos }
        val before = butler.strips().size
        gate.countDown()
        Thread.sleep(300)
        assertEquals(before, butler.strips().size)
        assertEquals("pot-2", pot().id)
    }

    @Test
    fun `a picture's address never prints its token`() {
        ready()
        val shown = onMainGet { photoSource("photo-a") }.toString()
        assertTrue(shown.contains("/photo/photo-a"), shown)
        assertTrue(!shown.contains("s3cret"), shown)
    }

    @Test
    fun `a picture's address carries the token, because these reads are gated`() {
        ready()
        val source = onMainGet { photoSource("photo-a") }
        assertTrue(source.url.endsWith("/photo/photo-a"), source.url)
        assertEquals("s3cret", source.token)
    }

    @Test
    fun `a lookup asks about the typed species and lands on the form`() {
        ready()
        onMain {
            open("pot-1")
            edit("species", "basil")
            lookUpSpecies()
        }
        val answer = waitFor("the lookup") { pot().lookup }
        assertEquals("Ocimum basilicum", answer.accepted)
        assertEquals("basil", butler.lookups().single().path?.substringAfter("q="))
    }

    @Test
    fun `a lookup pre-selects the kind while the field is empty`() {
        butler.speciesAnswer =
            MockResponse().setBody(
                """{"query": "basil", "matched": "exact", "accepted": "Ocimum basilicum",
                    "kind": "herb", "care": {"found": true}, "candidates": [],
                    "note": "Trefle: Ocimum basilicum"}""",
            )
        ready()
        onMain {
            open("pot-1")
            edit("species", "basil")
            lookUpSpecies()
        }
        waitFor("the lookup") { pot().lookup }
        // The one route by which a species has ever reached the band: a
        // dropdown a human can see and change, not a number in the math.
        assertEquals("herb", pot().draft["plant_type"])
    }

    @Test
    fun `a lookup never overwrites a kind somebody chose`() {
        butler.speciesAnswer =
            MockResponse().setBody(
                """{"query": "basil", "matched": "exact", "accepted": "Ocimum basilicum",
                    "kind": "herb", "care": {"found": true}, "candidates": [],
                    "note": "Trefle: Ocimum basilicum"}""",
            )
        ready()
        onMain {
            open("pot-1")
            edit("plant_type", "succulent")
            edit("species", "basil")
            lookUpSpecies()
        }
        waitFor("the lookup") { pot().lookup }
        assertEquals("succulent", pot().draft["plant_type"])
        // It is offered rather than applied, and taking it is a tap.
        assertEquals("herb", suggestedKind(pot().draft, pot().lookup?.kind))
        onMain { useKind("herb") }
        assertEquals("herb", pot().draft["plant_type"])
    }

    @Test
    fun `one field explains itself at a time`() {
        ready()
        onMain { open("pot-1") }
        assertNull(pot().explaining)
        onMain { explain("cooldown_h") }
        assertEquals("cooldown_h", pot().explaining)
        onMain { explain("mode") }
        assertEquals("mode", pot().explaining)
        onMain { stopExplaining() }
        assertNull(pot().explaining)
    }

    @Test
    fun `a lookup with nothing typed asks nobody`() {
        ready()
        onMain {
            open("pot-1")
            lookUpSpecies()
        }
        assertEquals("type a species first", waitFor("the refusal") { pot().lookup }.note)
        assertTrue(butler.lookups().isEmpty())
    }

    @Test
    fun `picking from the shortlist fills the field and asks again`() {
        butler.speciesAnswer =
            MockResponse().setBody(
                """{"query": "tomatoe", "matched": "none", "accepted": null,
                    "candidates": [{"name": "Solanum lycopersicum", "common": "Tomato",
                                    "image": "https://img/x", "slug": "sl"}],
                    "note": "not sure which one — pick the plant you recognise"}""",
            )
        ready()
        onMain {
            open("pot-1")
            edit("species", "tomatoe")
            lookUpSpecies()
        }
        waitFor("the shortlist") { pot().lookup?.candidates?.firstOrNull() }
        onMain { pickCandidate("Solanum lycopersicum") }
        // The name lands as a single wire token, and the second question is
        // asked about it rather than about what was typed.
        assertEquals("Solanum_lycopersicum", waitFor("the filled field") { pot().draft["species"] })
        waitFor("the second lookup") { butler.lookups().getOrNull(1) }
        // Folded and form-encoded: the backend lowercases and collapses the
        // same way, so this is one cache key with what a person typed.
        assertEquals("solanum+lycopersicum", butler.lookups()[1].path?.substringAfter("q="))
    }

    @Test
    fun `applying the offer is an ordinary pot edit`() {
        butler.advice = """{"kind": "target", "low": 30, "high": 50, "why": "herb"}"""
        ready()
        val advice = waitFor("the offer") { settled().garden.potById("pot-1")?.advice }
        onMain {
            open("pot-1")
            applyAdvice(advice)
        }
        val post = waitFor("the POST") { butler.posts().firstOrNull { it.path == "/pot" } }
        assertEquals("id=pot-1 target_low_pct=30 target_high_pct=50", post.body.readUtf8())
    }

    @Test
    fun `an unsaved target edit refuses the offer rather than overwriting it`() {
        butler.advice = """{"kind": "target", "low": 30, "high": 50, "why": "herb"}"""
        ready()
        val advice = waitFor("the offer") { settled().garden.potById("pot-1")?.advice }
        onMain {
            open("pot-1")
            edit("target_low_pct", "44")
            applyAdvice(advice)
        }
        assertEquals(
            "save or discard your target edits first",
            waitFor("the note") { pot().note },
        )
        assertTrue(butler.posts().none { it.path == "/pot" })
    }

    @Test
    fun `refusing the offer says so to the backend and writes no numbers`() {
        butler.advice = """{"kind": "target", "low": 30, "high": 50, "why": "herb"}"""
        ready()
        onMain {
            open("pot-1")
            dismissAdvice()
        }
        val post = waitFor("the POST") { butler.posts().firstOrNull { it.path == "/advice" } }
        assertEquals("pot=pot-1 dismiss=1", post.body.readUtf8())
        assertTrue(butler.posts().none { it.path == "/pot" })
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
        // No name=: this edit is not a rename, and resending the nickname
        // would overwrite one made from another phone meanwhile.
        assertEquals("id=pot-1 target_low_pct=35", post.body.readUtf8())
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
    fun `retyping a pot's own name is neither a clash nor a rename on the wire`() {
        ready()
        onMain {
            open("pot-1")
            edit("name", " basil ") // the same name, spelt with the user's spaces
            edit("target_low_pct", "35")
            save()
        }
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
        // Not refused as a duplicate of itself, and not sent as a rename.
        assertEquals("id=pot-1 target_low_pct=35", butler.posts().single().body.readUtf8())
    }

    @Test
    fun `a save's outcome lands on the pot by id, even when the draft moves on before the answer`() {
        ready()
        val gate = CountDownLatch(1)
        butler.potGate = gate
        onMain {
            open("pot-1")
            edit("name", "genovese")
            save()
        }
        waitFor("the POST in flight") { butler.sent("/pot").firstOrNull() }
        // The user keeps typing while the backend has not answered: the open
        // form's draft no longer matches what was posted. Keyed on the name,
        // the outcome would never find its form and the screen would sit on
        // saving = true for good.
        onMain { edit("name", "yet_another_name") }
        gate.countDown()
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
        assertEquals("id=pot-1 name=genovese", butler.posts().single().body.readUtf8())
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
    fun `a pot's history opens over its form and Back gives the draft back`() {
        ready()
        onMain {
            open("pot-1")
            edit("target_low_pct", "35")
            openDoses("pot-1", "basil's water")
        }
        val history = waitFor("the history") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        assertEquals("/doses?pot=pot-1&limit=50", butler.requests.last { it.path?.startsWith("/doses") == true }.path)
        assertEquals(listOf(7L, 6L), history.doses?.map { it.id })
        assertEquals(butler.nowS, history.nowS)
        assertNull(history.why)
        // Reading the history is not a reason to lose a half-typed edit.
        onMain { back() }
        val form = pot()
        assertEquals("pot-1", form.id)
        assertEquals("35", form.draft["target_low_pct"])
    }

    @Test
    fun `the garden's history asks for every pot and Back goes to the list`() {
        ready()
        onMain { openDoses(null, "Watering") }
        val history = waitFor("the history") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        assertEquals("/doses?limit=50", butler.requests.last { it.path?.startsWith("/doses") == true }.path)
        // The dose no window claims is listed, not filtered away.
        assertNull(history.doses?.last()?.pot)
        assertEquals("expired", history.doses?.last()?.state)
        onMain { back() }
        assertEquals(Screen.List, model.screen.value)
    }

    @Test
    fun `the history will not open over a form with something on the wire`() {
        ready()
        val gate = CountDownLatch(1)
        butler.potGate = gate
        onMain {
            open("pot-1")
            edit("target_low_pct", "35")
            save()
        }
        waitFor("the POST in flight") { butler.sent("/pot").firstOrNull() }
        // Back would restore this very snapshot, saving and all, and the
        // save's outcome lands on whatever form is shown — not this one.
        onMain { openDoses("pot-1", "basil's water") }
        assertEquals(true, model.screen.value is Screen.Pot)
        assertEquals(emptyList(), butler.requests.filter { it.path?.startsWith("/doses") == true })
        gate.countDown()
        waitFor("the list") { model.screen.value.takeIf { it == Screen.List } }
    }

    @Test
    fun `a backend that sends no clock does not date every dose to the epoch`() {
        butler.dosesSayNow = false
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        val history = waitFor("the history") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        // The phone's own clock, not 0 — which would render the whole list
        // as "0s ago" and look like fact.
        assertEquals(true, history.nowS >= butler.nowS)
    }

    @Test
    fun `the chart window changes what is asked for, and drops the old curve first`() {
        ready()
        onMain { open("pot-1") }
        val day = waitFor("the day") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        assertEquals(ChartWindow.DAY, day.window)
        assertEquals("/history?pot=pot-1&hours=24&bucket_s=300", butler.histories().last().path)

        onMain { setChartWindow(ChartWindow.MONTH) }
        val month = waitFor("the month") {
            (model.screen.value as? Screen.Pot)?.takeIf { it.window == ChartWindow.MONTH && it.history != null }
        }
        assertEquals("/history?pot=pot-1&hours=720&bucket_s=3600", butler.histories().last().path)
        assertEquals(3600, month.history?.bucketS)
        // A month's shape drawn under a "day" chip would be a lie, so the
        // old curve goes before the new one arrives.
        assertEquals(butler.nowS - 720 * 3600, month.history?.since)
    }

    @Test
    fun `asking for the window already shown fetches nothing`() {
        ready()
        onMain { open("pot-1") }
        waitFor("the curve") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        val before = butler.histories().size
        onMain { setChartWindow(ChartWindow.DAY) }
        Thread.sleep(200)
        assertEquals(before, butler.histories().size)
    }

    @Test
    fun `a refresh reloads the window that is up, not the day`() {
        ready()
        onMain { open("pot-1") }
        waitFor("the curve") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        onMain { setChartWindow(ChartWindow.WEEK) }
        waitFor("the week") {
            (model.screen.value as? Screen.Pot)?.takeIf { it.window == ChartWindow.WEEK && it.history != null }
        }
        onMain { refresh() }
        settled()
        waitFor("the reload") { butler.histories().lastOrNull()?.takeIf { it.path?.contains("hours=168") == true } }
        assertEquals("/history?pot=pot-1&hours=168&bucket_s=1800", butler.histories().last().path)
    }

    @Test
    fun `a short page is the whole history and offers nothing behind it`() {
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        val first = waitFor("the history") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        assertEquals(listOf(7L, 6L), first.doses?.map { it.id })
        assertEquals(false, first.more)
        // And asking anyway fetches nothing.
        val before = butler.requests.count { it.path?.startsWith("/doses") == true }
        onMain { loadOlderDoses() }
        Thread.sleep(200)
        assertEquals(before, butler.requests.count { it.path?.startsWith("/doses") == true })
    }

    @Test
    fun `older doses append behind the ones already read, on the last row's own cursor`() {
        butler.dosesPageFull = true
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        val first = waitFor("a full page") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        assertEquals(DOSES_LIMIT, first.doses?.size)
        assertEquals(true, first.more)
        val oldest = first.doses!!.last()

        onMain { loadOlderDoses() }
        val second = waitFor("the page behind it") {
            (model.screen.value as? Screen.Doses)?.takeIf { (it.doses?.size ?: 0) > DOSES_LIMIT }
        }
        // The cursor is the whole sort key of the oldest row on screen.
        val asked = butler.requests.last { it.path?.startsWith("/doses") == true }.path!!
        assertEquals(true, "before=${oldest.sentTs}" in asked)
        assertEquals(true, "before_id=${oldest.id}" in asked)
        // Appended, not replacing: what was read does not move under the finger.
        assertEquals(first.doses, second.doses?.take(DOSES_LIMIT))
        assertEquals(1L, second.doses?.last()?.id)
        assertEquals(false, second.more)
        assertEquals(false, second.loadingMore)
    }

    @Test
    fun `a page that arrives after a reload does not land on the list that replaced it`() {
        butler.dosesPageFull = true
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        val first = waitFor("a full page") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        assertEquals(DOSES_LIMIT, first.doses?.size)

        // Load older, held on the wire, then pull to refresh over it.
        val gate = CountDownLatch(1)
        butler.dosesGate = gate
        onMain { loadOlderDoses() }
        waitFor("the page in flight") { butler.requests.lastOrNull { it.path?.contains("before=") == true } }
        onMain { reloadDoses() }
        val reloaded = waitFor("the reload") {
            (model.screen.value as? Screen.Doses)?.takeIf { it.doses?.size == DOSES_LIMIT && !it.loading }
        }
        gate.countDown()
        Thread.sleep(300)

        // The held page must not append onto the list that replaced it: its
        // cursor belonged to a list that no longer exists, and appending it
        // would step over whatever arrived in between.
        val after = model.screen.value as Screen.Doses
        assertEquals(DOSES_LIMIT, after.doses?.size)
        assertEquals(reloaded.doses?.map { it.id }, after.doses?.map { it.id })
        assertEquals(false, after.loadingMore)
    }

    @Test
    fun `load older is refused while a reload is on the wire`() {
        butler.dosesPageFull = true
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        waitFor("a full page") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        butler.potsGate = CountDownLatch(1) // nothing to do with /doses; just a pause
        onMain {
            reloadDoses()
            loadOlderDoses()
        }
        Thread.sleep(200)
        butler.potsGate?.countDown()
        // Only the reload asked; the pager stood down rather than anchoring
        // a cursor to a list about to be replaced.
        assertEquals(0, butler.requests.count { it.path?.contains("before=") == true })
    }

    @Test
    fun `a failed history load says why instead of an empty list`() {
        butler.failDoses = true
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        val history = waitFor("the reason") { (model.screen.value as? Screen.Doses)?.takeIf { it.why != null } }
        assertEquals("try again: x", history.why)
        assertNull(history.doses)
        assertEquals(false, history.loading)
    }

    @Test
    fun `a failed reload keeps the history that is already up`() {
        ready()
        onMain { openDoses("pot-1", "basil's water") }
        val before = waitFor("the history") { (model.screen.value as? Screen.Doses)?.takeIf { it.doses != null } }
        butler.failDoses = true
        onMain { reloadDoses() }
        val after = waitFor("the reason") { (model.screen.value as? Screen.Doses)?.takeIf { it.why != null } }
        assertEquals("try again: x", after.why)
        assertEquals(before.doses?.map { it.id }, after.doses?.map { it.id })
    }

    private fun cachedPot(name: String = "basil") =
        Pot(id = "pot-1", name = name, controller = "b1", channel = 0, outlet = 3, doseMl = 100, raw = 9000)

    /** Stamped with the butler it came from, as every real write is: a
     * cache is opened only by the address that wrote it. */
    private fun cached(pots: kotlin.collections.List<Pot>, health: Health, atS: Long) =
        CachedGarden(pots, health, atS = atS, url = server.url("/").toString())

    private fun withCache(cache: FakeCache): GardenViewModel =
        GardenViewModel(Backend(server.url("/").toString(), token = "s3cret"), cache = cache)

    @Test
    fun `the cache fills the screen at launch, stamped with its age`() {
        val cache = FakeCache(cached(listOf(cachedPot()), Health(ok = true), butler.nowS - 7200))
        model = withCache(cache)
        onMain { openCache() }
        val shown = waitFor("the cached garden") { model.state.value as? UiState.Ready }
        assertEquals(butler.nowS - 7200, shown.cachedAtS)
        assertEquals(listOf("basil"), shown.garden.pots.map { it.name })
        // Nothing was asked of the butler to get this on screen.
        assertEquals(emptyList(), butler.requests.toList())
    }

    @Test
    fun `a new pot form opens with the controller filled in`() {
        ready()
        onMain { newPot() }
        val form = model.screen.value as Screen.Pot
        assertNull(form.id)
        // The DRAFT only: prefilling `original` too would make it look
        // unchanged and it would never be sent.
        assertEquals("0", form.draft["controller"])
        assertEquals(emptyMap(), form.original)
    }

    @Test
    fun `an unwired pot still fetches its chart`() {
        // The readings carry the pot they were taken for, so a pot with no
        // controller and no channel — just back from the graveyard, say —
        // has a curve. It used to return early and render nothing.
        ready()
        onMain { open("pot-3") }
        waitFor("the curve") { butler.histories().firstOrNull() }
        assertEquals("/history?pot=pot-3&hours=24&bucket_s=300", butler.histories().last().path)
    }

    @Test
    fun `burying a pot sends only the status, and reviving sends only the status`() {
        // Only: the backend refuses status=graveyard alongside any wiring
        // key, because burying is what unwires.
        ready()
        onMain { bury("pot-1") }
        val buried = waitFor("the burial") { butler.posts().firstOrNull() }
        assertEquals("id=pot-1 status=graveyard", buried.body.copy().readUtf8())

        onMain { revive("pot-3") }
        val revived = waitFor("the revival") { butler.posts().getOrNull(1) }
        assertEquals("id=pot-3 status=alive", revived.body.copy().readUtf8())
    }

    @Test
    fun `deleting a pot posts the erasure and leaves the form`() {
        ready()
        onMain { open("pot-1") }
        waitFor("the form") { model.screen.value as? Screen.Pot }
        onMain { deletePot() }
        waitFor("the erasure") { butler.deleted.firstOrNull() }
        settled()
        assertEquals(listOf("pot-1"), butler.deleted.toList())
        // The form MUST be popped: PotScreen keeps rendering from its own
        // snapshot when the pot vanishes, so staying would leave a working
        // form whose Save posts an id that is gone.
        assertEquals(Screen.List, model.screen.value)
    }

    @Test
    fun `a delete is refused while the garden is a memory`() {
        // The one irreversible thing here must not be the one thing allowed
        // against numbers nobody has confirmed.
        val cache = FakeCache(cached(listOf(cachedPot("stale")), Health(), butler.nowS - 7200))
        model = withCache(cache)
        onMain { openCache() }
        waitFor("the cached garden") { model.state.value as? UiState.Ready }
        onMain { open("pot-1") }
        waitFor("the form") { model.screen.value as? Screen.Pot }
        onMain { deletePot() }
        settled()
        assertEquals(emptyList(), butler.deleted.toList())
        assertNotNull((model.screen.value as Screen.Pot).refused)
    }

    @Test
    fun `a live answer clears the stamp and is written back to the cache`() {
        val cache = FakeCache(cached(listOf(cachedPot("stale")), Health(), butler.nowS - 7200))
        model = withCache(cache)
        onMain { openCache() }
        waitFor("the cached garden") { model.state.value as? UiState.Ready }
        onMain { refresh() }
        val live = waitFor("the live garden") { (model.state.value as? UiState.Ready)?.takeIf { it.cachedAtS == null } }
        assertEquals(listOf("basil", "mint"), live.garden.pots.map { it.name })
        val written = cache.writes.last()
        // Every pot the answer carried, buried ones included: splitting is
        // a screen decision, and a cache holds the answer.
        assertEquals(listOf("pot-1", "pot-2", "pot-3"), written.pots.map { it.id })
        assertEquals(true, written.atS >= butler.nowS)
    }

    @Test
    fun `off the tailnet the cache fills the Trouble screen the failed fetch left`() {
        // The case the whole feature exists for: the network fails fast and
        // usually beats the disk, so the cache has to be allowed to land on
        // a Trouble screen or the app is blank exactly when it should not be.
        butler.failPots = true
        val cache = FakeCache(cached(listOf(cachedPot()), Health(ok = true), butler.nowS - 7200))
        model = withCache(cache)
        onMain { refresh() }
        waitFor("the trouble") { model.state.value as? UiState.Trouble }
        onMain { openCache() }
        val shown = waitFor("the cached garden") { model.state.value as? UiState.Ready }
        assertEquals(butler.nowS - 7200, shown.cachedAtS)
        assertEquals(listOf("basil"), shown.garden.pots.map { it.name })
    }

    @Test
    fun `the reset chip is refused while the screen is a memory`() {
        val cache = FakeCache(cached(listOf(cachedPot()), Health(ok = true), butler.nowS - 7200))
        model = withCache(cache)
        onMain { openCache() }
        waitFor("the cached garden") { model.state.value as? UiState.Ready }
        onMain { resetInterval("b1") }
        val note = waitFor("the note") { model.listNote.value }
        assertEquals(true, note.startsWith("the butler is not answering"))
        assertEquals(emptyList(), butler.posts().toList())
    }

    @Test
    fun `what goes to disk carries no derived percentage`() {
        val cache = FakeCache()
        model = withCache(cache)
        ready()
        val written = waitFor("the write") { cache.writes.lastOrNull() }
        assertEquals(true, written.pots.isNotEmpty())
        // The backend sent no pct for these pots, but the rule is what
        // matters: nothing derived is stored, so nothing can be read back
        // through a calibration it was not taken with.
        assertEquals(emptyList(), written.pots.mapNotNull { it.pct })
    }

    @Test
    fun `a cache that arrives after a live answer does not replace it`() {
        val cache = FakeCache(cached(listOf(cachedPot("stale")), Health(), butler.nowS - 7200))
        model = withCache(cache)
        ready()
        onMain { openCache() }
        Thread.sleep(200)
        val state = model.state.value as UiState.Ready
        assertNull(state.cachedAtS)
        assertEquals(listOf("basil", "mint"), state.garden.pots.map { it.name })
    }

    @Test
    fun `nothing is written to the butler while the screen is a memory`() {
        val cache = FakeCache(cached(listOf(cachedPot()), Health(ok = true), butler.nowS - 7200))
        model = withCache(cache)
        onMain { openCache() }
        waitFor("the cached garden") { model.state.value as? UiState.Ready }
        onMain { open("pot-1") }
        val form = pot()

        onMain { water() }
        val refusedWater = waitFor("the water refusal") {
            (model.screen.value as? Screen.Pot)?.takeIf { it.waterRefused != null }
        }
        assertEquals(true, refusedWater.waterRefused!!.startsWith("the butler is not answering"))
        assertEquals(true, "2h ago" in refusedWater.waterRefused!!)

        onMain {
            edit("target_low_pct", "35")
            save()
        }
        val refusedSave = waitFor("the save refusal") {
            (model.screen.value as? Screen.Pot)?.takeIf { it.refused != null }
        }
        assertEquals(true, refusedSave.refused!!.startsWith("the butler is not answering"))

        onMain { startCalibration() }
        waitFor("the wizard refusal") { (model.screen.value as? Screen.Pot)?.takeIf { it.note != null } }

        onMain { approve(9) }
        onMain { verdict(9, "ok") }
        Thread.sleep(200)
        // Not one POST left the phone, and no dose was queued for later:
        // this is a cache, not offline editing.
        assertEquals(emptyList(), butler.posts().toList())
        assertEquals(form.id, (model.screen.value as Screen.Pot).id)
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
    fun `opening a pot fetches its last day onto the form`() {
        ready()
        onMain { open("pot-1") }
        val form = waitFor("the curve") { (model.screen.value as? Screen.Pot)?.takeIf { it.history != null } }
        val get = butler.requests.single { it.path?.startsWith("/history") == true }
        assertEquals("/history?pot=pot-1&hours=24&bucket_s=300", get.path)
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
        assertEquals("/history?pot=pot-1&hours=24&bucket_s=300", butler.histories()[1].path)
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
