package garden.butler.app

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse

/** A pot's own strip: reading it, adding to it, forgetting from it, and
 * the address the image loader is handed. */
class PhotoFlowTest : ButlerTest() {
    @Test
    fun `opening a pot reads its strip`() {
        butler.photos += "photo-a"
        ready()
        onMain { open("pot-1") }
        val photos = waitFor("the strip") { potForm().photos }
        assertEquals(listOf("photo-a"), photos.map { it.id })
        assertEquals("pot-1", butler.strips().single().path?.substringAfter("pot=")?.substringBefore("&"))
    }

    @Test
    fun `a picture goes up and the strip is re-read`() {
        ready()
        onMain { open("pot-1") }
        waitFor("the first strip") { potForm().photos }
        onMain { addPhoto(byteArrayOf(1, 2, 3), 1600, 1200) }
        val photos = waitFor("the picture") { potForm().photos?.takeIf { it.isNotEmpty() } }
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
        waitFor("the first strip") { potForm().photos }
        onMain { addPhoto(byteArrayOf(1), 800, 600) }
        val note = waitFor("the refusal") { potForm().note }
        assertTrue(note.contains("no space left"), note)
        assertEquals(2, butler.strips().size)
        assertTrue(!potForm().uploading)
    }

    @Test
    fun `a picture cannot be taken of a pot that has not been saved`() {
        ready()
        onMain { newPot() }
        onMain { addPhoto(byteArrayOf(1), 800, 600) }
        assertEquals("save the pot first", waitFor("the refusal") { potForm().note })
        assertTrue(butler.uploads().isEmpty())
    }

    @Test
    fun `forgetting a picture takes it off the strip`() {
        butler.photos += "photo-a"
        butler.photos += "photo-b"
        ready()
        onMain { open("pot-1") }
        waitFor("the strip") { potForm().photos?.takeIf { it.size == 2 } }
        onMain { viewPhoto("photo-a") }
        assertEquals("photo-a", potForm().viewing)
        onMain { deletePhoto("photo-a") }
        val left = waitFor("the shorter strip") { potForm().photos?.takeIf { it.size == 1 } }
        assertEquals(listOf("photo-b"), left.map { it.id })
        assertNull(potForm().viewing)
    }

    @Test
    fun `a strip that will not load keeps the form and says why`() {
        butler.failPhotos = true
        ready()
        onMain { open("pot-1") }
        val why = waitFor("the reason") { potForm().photosWhy }
        assertTrue(why.startsWith("pictures:"), why)
        assertNull(potForm().photos)
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
        waitFor("pot-1's strip") { potForm().photos }
        onMain { addPhoto(byteArrayOf(1), 800, 600) }
        waitFor("the upload") { butler.uploads().firstOrNull() }
        onMain { back() }
        onMain { open("pot-2") }
        waitFor("pot-2's strip") { potForm().photos }
        val before = butler.strips().size
        gate.countDown()
        Thread.sleep(300)
        assertEquals(before, butler.strips().size)
        assertEquals("pot-2", potForm().id)
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
}
