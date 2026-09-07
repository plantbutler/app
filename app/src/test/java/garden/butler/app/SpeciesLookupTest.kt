package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse

/** Asking what the plant is, and the watering band that comes back as an
 * offer rather than as a number written behind your back. */
class SpeciesLookupTest : ButlerTest() {
    @Test
    fun `a lookup asks about the typed species and lands on the form`() {
        ready()
        onMain {
            open("pot-1")
            edit("species", "basil")
            lookUpSpecies()
        }
        val answer = waitFor("the lookup") { potForm().lookup }
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
        waitFor("the lookup") { potForm().lookup }
        // The one route by which a species has ever reached the band: a
        // dropdown a human can see and change, not a number in the math.
        assertEquals("herb", potForm().draft["plant_type"])
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
        waitFor("the lookup") { potForm().lookup }
        assertEquals("succulent", potForm().draft["plant_type"])
        // It is offered rather than applied, and taking it is a tap.
        assertEquals("herb", suggestedKind(potForm().draft, potForm().lookup?.kind))
        onMain { useKind("herb") }
        assertEquals("herb", potForm().draft["plant_type"])
    }

    @Test
    fun `a lookup with nothing typed asks nobody`() {
        ready()
        onMain {
            open("pot-1")
            lookUpSpecies()
        }
        assertEquals("type a species first", waitFor("the refusal") { potForm().lookup }.note)
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
        waitFor("the shortlist") { potForm().lookup?.candidates?.firstOrNull() }
        onMain { pickCandidate("Solanum lycopersicum") }
        // The name lands as a single wire token, and the second question is
        // asked about it rather than about what was typed.
        assertEquals("Solanum_lycopersicum", waitFor("the filled field") { potForm().draft["species"] })
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
            waitFor("the note") { potForm().note },
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
}
