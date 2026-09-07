package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The last good answer on screen, stamped with its age, and every write
 * refused while it is. */
class OfflineCacheTest : ButlerTest() {
    private fun cachedPot(name: String = "basil") =
        pot(name = name, id = "pot-1", controller = 0, channel = 0, outlet = 3, doseMl = 100, raw = 9000)

    /** A two-hour-old garden on screen, which is what every refusal below
     * needs before it can be refused. */
    private fun openMemory(name: String = "basil", health: Health = Health(ok = true)): FakeCache {
        val cache = FakeCache(cached(listOf(cachedPot(name)), health, butler.nowS - 7200))
        model = withCache(cache)
        onMain { openCache() }
        waitFor("the cached garden") { model.state.value as? UiState.Ready }
        return cache
    }

    @Test
    fun `the cache fills the screen at launch, stamped with its age`() {
        openMemory()
        val shown = model.state.value as UiState.Ready
        assertEquals(butler.nowS - 7200, shown.cachedAtS)
        assertEquals(listOf("basil"), shown.garden.pots.map { it.name })
        // Nothing was asked of the butler to get this on screen.
        assertEquals(emptyList(), butler.requests.toList())
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
    fun `a live answer clears the stamp and is written back to the cache`() {
        val cache = openMemory("stale", Health())
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
    fun `nothing is written to the butler while the screen is a memory`() {
        openMemory()
        onMain { open("pot-1") }
        val form = potForm()

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
    fun `a delete is refused while the garden is a memory`() {
        // The one irreversible thing here must not be the one thing allowed
        // against numbers nobody has confirmed.
        openMemory("stale", Health())
        onMain { open("pot-1") }
        waitFor("the form") { model.screen.value as? Screen.Pot }
        onMain { deletePot() }
        settled()
        assertEquals(emptyList(), butler.deleted.toList())
        assertNotNull((model.screen.value as Screen.Pot).refused)
    }

    /** A write from a cached garden is refused before anything is posted. */
    private fun refusedAsMemory(action: GardenViewModel.() -> Unit) {
        openMemory()
        onMain(action)
        val note = waitFor("the note") { model.listNote.value }
        assertEquals(true, note.startsWith("the butler is not answering"))
        assertEquals(emptyList(), butler.posts().toList())
    }

    @Test
    fun `the reset chip is refused while the screen is a memory`() = refusedAsMemory { resetInterval(0) }

    @Test
    fun `the refilled chip is refused while the screen is a memory`() = refusedAsMemory { refill(0) }

    @Test
    fun `resume is refused while the screen is a memory`() = refusedAsMemory { resume(0) }
}
