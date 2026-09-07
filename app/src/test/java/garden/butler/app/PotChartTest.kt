package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The moisture curve on the pot form: the window it asks for, the
 * reloads, and what it does when a fetch fails. */
class PotChartTest : ButlerTest() {
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
    fun `an unwired pot still fetches its chart`() {
        // The readings carry the pot they were taken for, so a pot with no
        // controller and no channel — just back from the graveyard, say —
        // still has a curve.
        ready()
        onMain { open("pot-3") }
        waitFor("the curve") { butler.histories().firstOrNull() }
        assertEquals("/history?pot=pot-3&hours=24&bucket_s=300", butler.histories().last().path)
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
}
