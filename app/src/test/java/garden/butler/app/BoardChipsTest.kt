package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.mockwebserver.MockResponse

/** The controllers card's chips: reset the pace, note a refill, resume a
 * stopped board. */
class BoardChipsTest : ButlerTest() {
    @Test
    fun `the reset chip names the board and the pace it is back to`() {
        ready()
        onMain { resetInterval(0) }
        assertEquals("board 0 reports every 60s again", waitFor("the note") { model.listNote.value })
        assertEquals("c=0 next=0", butler.sent("/interval").single().body.readUtf8())
    }

    @Test
    fun `refill and resume post the board and land a note on the list`() {
        ready()
        onMain { refill(0) }
        var note = waitFor("the refill note") { model.listNote.value }
        assertEquals("board 0: refill noted", note)
        val refill = waitFor("the post") { butler.posts().firstOrNull { it.path == "/refill" } }
        assertEquals("c=0", refill.body.copy().readUtf8())
        onMain { resume(0) }
        note = waitFor("the resume note") { model.listNote.value?.takeIf { it != "board 0: refill noted" } }
        assertEquals("board 0 waters again", note)
        val resume = waitFor("the post") { butler.posts().firstOrNull { it.path == "/resume" } }
        assertEquals("c=0", resume.body.copy().readUtf8())
        waitFor("the refresh after it") { butler.sent("/pots").getOrNull(2) }
    }

    @Test
    fun `a refusal from resume lands verbatim`() {
        butler.resumeAnswer = MockResponse().setResponseCode(503).setBody("try again: locked\n")
        ready()
        onMain { resume(0) }
        assertEquals("try again: locked", waitFor("the note") { model.listNote.value })
    }
}
