package garden.butler.app

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse

/** The pot form on the wire: what a save posts, what a rename posts, and
 * burying, reviving and erasing a pot. */
class SavePotTest : ButlerTest() {
    @Test
    fun `a new pot form opens with the controller filled in`() {
        ready()
        onMain { newPot() }
        val form = model.screen.value as Screen.Pot
        assertNull(form.id)
        // The DRAFT only: prefilling `original` too would make it look
        // unchanged and it would never be sent.
        assertEquals(DEFAULT_CONTROLLER, form.draft["controller"])
        assertEquals("0", DEFAULT_CONTROLLER, "board 0 is the one board there is")
        assertEquals(emptyMap(), form.original)
    }

    @Test
    fun `save posts only the changed fields and pops to the list`() {
        ready()
        onMain {
            open("pot-1")
            edit("target_low_pct", "35")
            save()
        }
        waitFor("the list") { model.screen.value.takeIf { it == Screen.Garden } }
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
        waitFor("the list") { model.screen.value.takeIf { it == Screen.Garden } }
        val post = butler.posts().single()
        assertEquals("/pot", post.path)
        assertEquals("id=pot-1 name=genovese", post.body.readUtf8())
        // The pot is still reachable under the id it was renamed through.
        onMain { open("pot-1") }
        assertEquals("pot-1", potForm().id)
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
        assertEquals(false, form.busy)
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
        assertEquals(false, form.busy)
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
        waitFor("the list") { model.screen.value.takeIf { it == Screen.Garden } }
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
        // busy = true for good.
        onMain { edit("name", "yet_another_name") }
        gate.countDown()
        waitFor("the list") { model.screen.value.takeIf { it == Screen.Garden } }
        assertEquals("id=pot-1 name=genovese", butler.posts().single().body.readUtf8())
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
        assertEquals(false, form.busy)
        assertEquals("35", form.draft["target_low_pct"])
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
        assertEquals(Screen.Garden, model.screen.value)
    }
}
