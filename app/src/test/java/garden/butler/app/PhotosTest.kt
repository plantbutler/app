package garden.butler.app

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotosTest {
    private fun photo(id: String, ts: Long, species: String? = null, missing: Boolean = false) =
        Photo(id = id, ts = ts, bytes = 400_000, species = species, missing = missing)

    // ------------------------------------------------------------------ //
    // Shrinking, which is where the pitch's first rabbit hole lives

    @Test
    fun `a twelve megapixel picture is subsampled, not loaded whole`() {
        // 4032x3024, the ordinary phone camera. /4 is 1008 on the long
        // edge, which is under 1600, so /2 (2016) is as far as it may go.
        assertEquals(2, sampleSize(4032, 3024))
        assertEquals(2, sampleSize(3024, 4032)) // portrait is the same picture
    }

    @Test
    fun `a picture already small enough is not subsampled`() {
        assertEquals(1, sampleSize(1600, 1200))
        assertEquals(1, sampleSize(800, 600))
        assertEquals(1, sampleSize(2000, 1500)) // /2 would be 1000, under the cap
    }

    @Test
    fun `subsampling never goes below the cap`() {
        // The invariant that matters: the decoded picture is always at
        // least as big as what we are about to scale it to, or the exact
        // scaling afterwards would be upscaling.
        for (w in listOf(640, 1600, 2000, 3000, 4032, 8000, 12000)) {
            for (h in listOf(480, 1200, 3024, 6000)) {
                val sample = sampleSize(w, h)
                assertTrue(maxOf(w, h) / sample >= PHOTO_LONG_EDGE || sample == 1, "$w x $h")
            }
        }
    }

    @Test
    fun `nonsense sizes do not divide by zero`() {
        assertEquals(1, sampleSize(0, 0))
        assertEquals(1, sampleSize(-1, -1))
        assertEquals(1, sampleSize(4032, 3024, cap = 0))
    }

    @Test
    fun `the long edge is capped and the shape is kept`() {
        assertEquals(1600 to 1200, fitted(4000, 3000))
        assertEquals(1200 to 1600, fitted(3000, 4000))
        assertEquals(1600 to 1600, fitted(2400, 2400))
    }

    @Test
    fun `a picture smaller than the cap is left alone`() {
        // Upscaling a photograph to meet a number would be inventing pixels.
        assertEquals(800 to 600, fitted(800, 600))
        assertEquals(1600 to 1200, fitted(1600, 1200))
    }

    @Test
    fun `a very thin picture keeps at least one pixel`() {
        val (w, h) = fitted(8000, 1)
        assertEquals(1600, w)
        assertTrue(h >= 1, "h=$h")
    }

    // ------------------------------------------------------------------ //
    // The strip

    @Test
    fun `the strip runs oldest first, the wire newest first`() {
        val wire = listOf(photo("c", 300), photo("b", 200), photo("a", 100))
        assertEquals(listOf("a", "b", "c"), strip(wire).map { it.id })
    }

    @Test
    fun `two pictures in the same second still have an order`() {
        // Otherwise the strip shuffles itself between refreshes.
        val wire = listOf(photo("b", 100), photo("a", 100))
        assertEquals(listOf("a", "b"), strip(wire).map { it.id })
        assertEquals(listOf("a", "b"), strip(wire.reversed()).map { it.id })
    }

    @Test
    fun `the break is where the plant changed`() {
        val wire =
            listOf(
                photo("d", 400, "Monstera_deliciosa"),
                photo("c", 300, "Monstera_deliciosa"),
                photo("b", 200, "Ocimum_basilicum"),
                photo("a", 100, "Ocimum_basilicum"),
            )
        assertEquals(setOf("c"), speciesBreaks(wire))
    }

    @Test
    fun `the first picture is where the strip starts, not a break`() {
        assertEquals(emptySet(), speciesBreaks(listOf(photo("a", 100, "Ocimum_basilicum"))))
    }

    @Test
    fun `replanting the same species leaves no trace, and nothing is invented`() {
        // Honest about what it cannot see: this reads the species each
        // picture was taken under, and basil after basil looks like basil.
        val wire = listOf(photo("b", 200, "Ocimum_basilicum"), photo("a", 100, "Ocimum_basilicum"))
        assertEquals(emptySet(), speciesBreaks(wire))
    }

    @Test
    fun `a picture taken before the pot was named is not a break either way`() {
        // A null species is "nobody said", not "a different plant": it must
        // neither open a break nor close the one before it.
        val wire =
            listOf(
                photo("c", 300, "Ocimum_basilicum"),
                photo("b", 200, null),
                photo("a", 100, "Ocimum_basilicum"),
            )
        assertEquals(emptySet(), speciesBreaks(wire))
    }

    @Test
    fun `three plants in one pot give two breaks`() {
        val wire =
            listOf(
                photo("c", 300, "Chlorophytum_comosum"),
                photo("b", 200, "Monstera_deliciosa"),
                photo("a", 100, "Ocimum_basilicum"),
            )
        assertEquals(setOf("b", "c"), speciesBreaks(wire))
    }

    // ------------------------------------------------------------------ //
    // Words

    @Test
    fun `this year's pictures are dated without the year`() {
        val now = Calendar.getInstance()
        val nowS = now.timeInMillis / 1000
        val sameYear = Calendar.getInstance().apply { set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 9) }
        assertTrue(!photoDay(sameYear.timeInMillis / 1000, nowS).contains("${now.get(Calendar.YEAR)}"))
    }

    @Test
    fun `an older picture carries its year, or a strip is a lie`() {
        val now = Calendar.getInstance()
        val older = Calendar.getInstance().apply { add(Calendar.YEAR, -2) }
        val line = photoDay(older.timeInMillis / 1000, now.timeInMillis / 1000)
        assertTrue(line.contains("${older.get(Calendar.YEAR)}"), line)
    }

    @Test
    fun `the full-size line says when, what, and how big`() {
        val nowS = System.currentTimeMillis() / 1000
        val line = photoLine(photo("a", nowS, "Ocimum_basilicum"), nowS)
        assertTrue(line.contains("Ocimum_basilicum"), line)
        assertTrue(line.contains("390 KB"), line)
    }

    @Test
    fun `a picture whose file has gone says so instead of its size`() {
        val nowS = System.currentTimeMillis() / 1000
        val line = photoLine(photo("a", nowS, missing = true), nowS)
        assertTrue(line.contains("gone from the butler"), line)
        assertTrue(!line.contains("KB"), line)
    }

    @Test
    fun `sizes read the way a person reads them`() {
        assertEquals("512 B", fileSize(512))
        assertEquals("400 KB", fileSize(409_600))
        assertTrue(fileSize(2_500_000).endsWith("MB"))
    }

    @Test
    fun `an empty strip says which kind of empty it is`() {
        assertTrue(stripEmptyLine(saved = true).contains("No pictures yet"))
        assertTrue(stripEmptyLine(saved = false).contains("Save the pot first"))
    }

    // ------------------------------------------------------------------ //
    // The wire

    @Test
    fun `the listing parses, missing and all`() {
        val answer =
            parsePhotos(
                """{"pot": "pot-1", "more": false, "now": 1757000000,
                    "photos": [{"id": "photo-9c1f0ab2", "ts": 1756999000, "bytes": 412000,
                                "w": 1600, "h": 1200, "species": "Ocimum_basilicum",
                                "missing": false},
                               {"id": "photo-deadbeef", "ts": 1756000000, "bytes": 1,
                                "missing": true}]}""",
            )
        assertEquals("pot-1", answer.pot)
        assertEquals(2, answer.photos.size)
        assertEquals(1600, answer.photos[0].w)
        assertEquals("Ocimum_basilicum", answer.photos[0].species)
        assertTrue(answer.photos[1].missing)
        assertNull(answer.photos[1].species)
    }

    @Test
    fun `a listing from a backend with new fields still parses`() {
        val answer = parsePhotos("""{"photos": [{"id": "photo-1", "hue": "green"}], "who": "?"}""")
        assertEquals("photo-1", answer.photos.single().id)
    }

    @Test
    fun `the upload answer gives up its id`() {
        assertEquals("photo-9c1f0ab2", parsePhotoAnswer("photo=photo-9c1f0ab2 ts=1757000000\n"))
        assertNull(parsePhotoAnswer("refused: no such pot: pot-x"))
        assertNull(parsePhotoAnswer("photo="))
        assertNull(parsePhotoAnswer(""))
    }
}
