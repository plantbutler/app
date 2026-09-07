package garden.butler.app

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The model itself: one refresh at a time, the field help, what a garden
 * row carries, and a failed refresh leaving the last good answer up. */
class GardenViewModelTest : ButlerTest() {
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
    fun `one field explains itself at a time`() {
        ready()
        onMain { open("pot-1") }
        assertNull(potForm().explaining)
        onMain { explain("cooldown_h") }
        assertEquals("cooldown_h", potForm().explaining)
        onMain { explain("mode") }
        assertEquals("mode", potForm().explaining)
        onMain { stopExplaining() }
        assertNull(potForm().explaining)
    }

    @Test
    fun `the newest picture rides along for the row thumbnail`() {
        // The id only: the bytes come from GET /photo/<id>, which the app
        // already caches, so a list of twenty plants is still one fetch of
        // text plus whatever the image cache has not seen.
        ready()
        val garden = settled().garden
        assertEquals("photo-abc123", garden.graveyard.single().photo)
        assertNull(garden.pots.first { it.id == "pot-1" }.photo)
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
}
