package garden.butler.app

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The watering history over a pot's form or over the garden, and the
 * page behind it. */
class DoseHistoryTest : ButlerTest() {
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
        val form = potForm()
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
        assertNull(history.doses?.last()?.potName)
        assertEquals("expired", history.doses?.last()?.state)
        onMain { back() }
        assertEquals(Screen.Garden, model.screen.value)
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
        // Back would restore this very snapshot, busy and all, and the
        // save's outcome lands on whatever form is shown — not this one.
        onMain { openDoses("pot-1", "basil's water") }
        assertEquals(true, model.screen.value is Screen.Pot)
        assertEquals(emptyList(), butler.requests.filter { it.path?.startsWith("/doses") == true })
        gate.countDown()
        waitFor("the list") { model.screen.value.takeIf { it == Screen.Garden } }
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
}
