package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The address, the token, and telling the two mistakes apart. */
class SettingsTest {
    @Test
    fun `an address typed without a scheme gets http, never https`() {
        // The pitch's rabbit hole: the laptop backend is plain HTTP on a LAN
        // address and the NAS is plain HTTP on the tailnet, so cleartext has
        // to keep working and the field cannot insist on https.
        assertEquals("http://192.168.1.42:9380", normaliseUrl("192.168.1.42:9380"))
        assertEquals("http://100.64.0.1:9380", normaliseUrl("  100.64.0.1:9380  "))
        assertEquals("http://ciccia:9380", normaliseUrl("ciccia:9380"))
    }

    @Test
    fun `a scheme that was typed is kept`() {
        assertEquals("https://butler.example", normaliseUrl("https://butler.example"))
        assertEquals("http://butler.example", normaliseUrl("http://butler.example"))
    }

    @Test
    fun `a trailing slash goes, because every path is appended to this`() {
        assertEquals("http://x:9380", normaliseUrl("http://x:9380/"))
        assertEquals("http://x:9380", normaliseUrl("x:9380//"))
        // A base path is not a trailing slash and stays: a reverse proxy may
        // well put the butler under one.
        assertEquals("http://x/butler", normaliseUrl("http://x/butler/"))
    }

    @Test
    fun `an empty address is asked for rather than guessed at`() {
        assertEquals("", normaliseUrl("   "))
        assertNotNull(urlProblem(""))
        assertTrue(urlProblem("  ")!!.contains("type the butler's address"))
    }

    @Test
    fun `a scheme this app cannot speak is named in the refusal`() {
        val why = urlProblem("ftp://butler")
        assertNotNull(why)
        assertTrue(why.contains("ftp://"), why)
    }

    @Test
    fun `an address okhttp cannot dial is refused here, not later`() {
        // Refusing what the client would refuse anyway is the point: an
        // address this screen accepts and the app then cannot use looks
        // like the butler's fault.
        assertNotNull(urlProblem("http://"))
        assertNotNull(urlProblem("http://:9380"))
    }

    @Test
    fun `an ordinary address has no problem`() {
        assertNull(urlProblem("192.168.1.42:9380"))
        assertNull(urlProblem("http://100.64.0.1:9380"))
        assertNull(urlProblem("https://butler.example/butler"))
    }

    @Test
    fun `a token is compared byte for byte, so whitespace is a wrong token`() {
        assertNull(tokenProblem("s3cret"))
        assertNull(tokenProblem("  s3cret  ")) // trimmed, which is the fix
        assertNotNull(tokenProblem(""))
        val why = tokenProblem("s3 cret")
        assertNotNull(why)
        assertTrue(why.contains("space"), why)
    }

    @Test
    fun `a butler that answers is a butler`() {
        val probe = readHello(200, "butler=0.14.0\n")
        assertEquals(Probe.Butler("0.14.0"), probe)
        assertTrue(probeLine(probe, "ciccia:9380").contains("0.14.0"))
    }

    @Test
    fun `a 401 is the token and says so`() {
        val probe = readHello(401, "bad token\n")
        assertEquals(Probe.WrongToken, probe)
        val line = probeLine(probe, "ciccia:9380")
        // The whole value of this screen: which of the two it was, and that
        // the other one is fine.
        assertTrue(line.contains("refused that token"), line)
        assertTrue(line.contains("The address is right"), line)
    }

    @Test
    fun `a 404 could be an old butler or another service, and says both`() {
        val probe = readHello(404, "Not Found")
        assertIs<Probe.NotTheButler>(probe)
        assertTrue(probe.why.contains("0.13.0"), probe.why)
        assertTrue(probe.why.contains("another service"), probe.why)
    }

    @Test
    fun `a 200 that is not a butler is not taken for one`() {
        // A router's admin page, a printer, anything on that port.
        val probe = readHello(200, "<html><body>Login</body></html>")
        assertIs<Probe.NotTheButler>(probe)
        assertTrue(probeLine(probe, "x").contains("not your butler"))
    }

    @Test
    fun `another status quotes what came back, short`() {
        val probe = readHello(503, "try again: database is locked")
        assertIs<Probe.NotTheButler>(probe)
        assertTrue(probe.why.contains("503"), probe.why)
        assertTrue(probe.why.contains("database is locked"), probe.why)
        assertTrue(readHello(500, "x".repeat(500)).let { it as Probe.NotTheButler }.why.length < 200)
    }

    @Test
    fun `nothing answering points at the address and the tailnet`() {
        val line = probeLine(Probe.NoAnswer("connect timed out"), "100.64.0.1:9380")
        assertTrue(line.contains("100.64.0.1:9380"), line)
        assertTrue(line.contains("tailnet"), line)
        assertTrue(line.contains("connect timed out"), line)
    }

    @Test
    fun `the host is what goes in a sentence, without the scheme`() {
        assertEquals("100.64.0.1:9380", hostOf("http://100.64.0.1:9380"))
        assertEquals("butler.example", hostOf("https://butler.example"))
    }

    @Test
    fun `a config never prints its token`() {
        // A data class in a state flow, one crash report away from a log.
        val shown = ButlerConfig("http://x:9380", "s3cret").toString()
        assertTrue(shown.contains("http://x:9380"), shown)
        assertTrue(!shown.contains("s3cret"), shown)
        val screen = Screen.Setup("http://x:9380", "s3cret", first = true).toString()
        assertTrue(!screen.contains("s3cret"), screen)
    }
}
