package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** The HTTP side of Backend against a real socket: headers, bodies, and the
 * backend's refusals arriving verbatim. */
class BackendWireTest {
    private fun withServer(block: (MockWebServer, Backend) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server, Backend(server.url("/").toString(), token = "s3cret"))
        }
    }

    @Test
    fun `postPot sends the token and a plain-text body, answers the name`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setBody("pot=basil\n"))
            assertEquals("pot=basil", backend.postPot("name=basil target_low_pct=30"))

            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/pot", sent.path)
            assertEquals("s3cret", sent.getHeader("X-Token"))
            assertEquals("text/plain; charset=utf-8", sent.getHeader("Content-Type"))
            assertEquals("name=basil target_low_pct=30", sent.body.readUtf8())
        }

    @Test
    fun `a refusal carries the code and the backend's own words`() =
        withServer { server, backend ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("refused: dry_raw and wet_raw must differ\n"),
            )
            val refused = assertFailsWith<Refused> { backend.postPot("name=basil dry_raw=1 wet_raw=1") }
            assertEquals(400, refused.code)
            assertEquals("refused: dry_raw and wet_raw must differ", refused.text)
            assertEquals(refused.text, refused.message)
        }

    @Test
    fun `busy, try again and bad token are refusals too`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("busy: cmd=3 state=sent"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("try again: x"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("bad token"))

            val busy = assertFailsWith<Refused> { backend.approve(17) }
            assertEquals(409 to "busy: cmd=3 state=sent", busy.code to busy.text)
            val later = assertFailsWith<Refused> { backend.verdict(16, "ok") }
            assertEquals(503 to "try again: x", later.code to later.text)
            val token = assertFailsWith<Refused> { backend.interval("b1", 5) }
            assertEquals(401 to "bad token", token.code to token.text)
        }

    @Test
    fun `an empty error body still says something`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setResponseCode(500))
            val refused = assertFailsWith<Refused> { backend.postPot("name=basil") }
            assertEquals(500, refused.code)
            assertEquals(true, refused.text.isNotEmpty())
        }

    @Test
    fun `interval sends c and next and parses the effective interval`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setBody("next=5\n"))
            assertEquals(5, backend.interval("b1", 5))
            assertEquals("c=b1 next=5", server.takeRequest().body.readUtf8())
        }

    @Test
    fun `pots come back through a real GET`() =
        withServer { server, backend ->
            server.enqueue(
                MockResponse().setBody(
                    """{"pots": [{"name": "basil", "pct": 48, "read_ts": 10}]}""",
                ),
            )
            val pots = backend.pots()
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("/pots", sent.path)
            assertEquals(listOf("basil"), pots.map { it.name })
            assertEquals(48, pots[0].pct)
        }

    @Test
    fun `doses come back through a real GET, per pot and for the garden`() =
        withServer { server, backend ->
            server.enqueue(
                MockResponse().setBody(
                    """{"now": 500, "doses": [
                         {"id": 7, "kind": "water", "ml": 100, "cap_s": 30, "flow_ml": 12,
                          "state": "acked", "source": "manual", "created_ts": 90,
                          "sent_ts": 100, "acked_ts": 110, "verdict": "too_little",
                          "pot": "pot-1", "pot_name": "basil"},
                         {"id": 6, "state": "expired", "pot": null, "pot_name": null}
                       ]}""",
                ),
            )
            val answer = backend.doses("pot-1", 50)
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("/doses?pot=pot-1&limit=50", sent.path)
            assertEquals(500, answer.now)
            assertEquals(listOf(7L, 6L), answer.doses.map { it.id })
            assertEquals(12, answer.doses[0].flowMl)
            assertEquals("too_little", answer.doses[0].verdict)
            assertEquals("basil", answer.doses[0].potName)
            // The unattributable row decodes as itself, not as a failure.
            assertNull(answer.doses[1].pot)
            assertEquals("expired", answer.doses[1].state)
        }

    @Test
    fun `the garden's history asks for no pot at all`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setBody("""{"now": 1, "doses": []}"""))
            backend.doses(null, 25)
            assertEquals("/doses?limit=25", server.takeRequest().path)
        }

    @Test
    fun `health comes back through a real GET`() =
        withServer { server, backend ->
            server.enqueue(
                MockResponse().setBody(
                    """{"ok": true, "next_default": 30, "last_ts": 1788291874,
                       "controllers": [{"controller": "b1", "last_seen": 1788291870}]}""",
                ),
            )
            val health = backend.health()
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("/health", sent.path)
            assertEquals(true, health.ok)
            assertEquals(30, health.nextDefault)
            assertEquals(1788291874, health.lastTs)
            assertEquals("b1", health.controllers.single().controller)
        }

    @Test
    fun `a GET the backend refuses is a Refused with its words, like a POST`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("try again: x\n"))
            val later = assertFailsWith<Refused> { backend.pots() }
            assertEquals(503, later.code)
            assertEquals("try again: x", later.text)
            assertEquals("try again: x", later.message)
        }

    @Test
    fun `each command hits its path with the token and a k=v body`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setBody("cmd=17 state=sent\n"))
            server.enqueue(MockResponse().setBody("cmd=16 verdict=ok\n"))
            server.enqueue(MockResponse().setBody("next=5\n"))

            assertEquals("cmd=17 state=sent", backend.approve(17))
            assertEquals("cmd=16 verdict=ok", backend.verdict(16, "ok"))
            assertEquals(5, backend.interval("b1", 5))

            val expected =
                listOf("/approve" to "cmd=17", "/verdict" to "cmd=16 verdict=ok", "/interval" to "c=b1 next=5")
            for ((path, body) in expected) {
                val sent = server.takeRequest()
                assertEquals("POST", sent.method)
                assertEquals(path, sent.path)
                assertEquals("s3cret", sent.getHeader("X-Token"))
                assertEquals(body, sent.body.readUtf8())
            }
        }

    @Test
    fun `history asks for one sensor's window and parses the buckets`() =
        withServer { server, backend ->
            server.enqueue(
                MockResponse().setBody(
                    """{"pot": "pot-3f9a21", "since": 1788205474, "to": 1788291874,
                       "bucket_s": 300, "points": [{"ts": 1788205500, "raw": 8123, "n": 5}]}""",
                ),
            )
            val history = backend.history("pot-3f9a21", 24, 300)
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("/history?pot=pot-3f9a21&hours=24&bucket_s=300", sent.path)
            assertEquals(1788291874, history.to)
            assertEquals(listOf(HistoryPoint(1788205500, 8123, n = 5)), history.points)
        }

    @Test
    fun `history percent-encodes the pot id, as doses does`() =
        withServer { server, backend ->
            // A minted `pot-xxxxxx` carries nothing to encode, so the encoder
            // is here for the same reason doses' is: the two readers must
            // agree about what an id looks like, and neither gets to assume.
            server.enqueue(MockResponse().setBody("""{"pot": "a+b", "points": []}"""))
            backend.history("a+b", 24, 300)
            assertEquals("/history?pot=a%2Bb&hours=24&bucket_s=300", server.takeRequest().path)
        }

    @Test
    fun `water posts the controller, outlet and dose with the token and answers the id`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setBody("cmd=17\n"))
            assertEquals(17, backend.water("b1", 3, 100))

            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/command", sent.path)
            assertEquals("s3cret", sent.getHeader("X-Token"))
            assertEquals("c=b1 water=3 ml=100", sent.body.readUtf8())
        }

    @Test
    fun `a taken slot refuses water with the backend's busy line`() =
        withServer { server, backend ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("busy: cmd=3 state=sent\n"))
            val busy = assertFailsWith<Refused> { backend.water("b1", 3, 100) }
            assertEquals(409, busy.code)
            assertEquals("busy: cmd=3 state=sent", busy.text)
        }
}
