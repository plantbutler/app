package garden.butler.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheTest {
    private fun tempFile(): File = File(Files.createTempDirectory("cache").toFile(), "garden.json")

    private val basil =
        Pot(
            id = "pot-1",
            name = "basil",
            species = "Ocimum_basilicum",
            controller = 0,
            channel = 0,
            outlet = 3,
            dryRaw = 12000,
            wetRaw = 4000,
            raw = 8000,
            pct = 50,
            readTs = 1000,
        )

    @Test
    fun `a garden written is the garden read`() {
        val cache = FileGardenCache(tempFile())
        val health = Health(ok = true, nextDefault = 60, controllers = listOf(ControllerHealth(0, 900)))
        cache.write(CachedGarden(listOf(basil), health, atS = 1234))
        val back = cache.read()!!
        assertEquals(1234, back.atS)
        assertEquals(listOf("pot-1"), back.pots.map { it.id })
        assertEquals("Ocimum_basilicum", back.pots[0].species)
        assertEquals(60, back.health.nextDefault)
        assertEquals(0, back.health.controllers.single().controller)
    }

    @Test
    fun `the cached pot carries its own calibration, so the numbers cannot drift`() {
        // The pitch's rabbit hole: a cached percentage would be re-read
        // through whatever calibration the pot has when the cache is
        // opened. Storing whole pots means the raw and the two calibration
        // points travel together and the derived % is the one it was.
        val cache = FileGardenCache(tempFile())
        cache.write(CachedGarden(listOf(basil), Health(), atS = 1234))
        val back = cache.read()!!.pots.single()
        assertEquals(12000, back.dryRaw)
        assertEquals(4000, back.wetRaw)
        assertEquals(8000, back.raw)
        assertEquals(50, moisturePct(back.raw!!, back.dryRaw, back.wetRaw))
        // Recalibrated since: the same cached raw now reads differently,
        // which is the point — nothing derived was stored, so the number
        // follows whichever calibration it is read through.
        assertEquals(100, moisturePct(back.raw!!, 16000, 8000))
        assertEquals(0, moisturePct(back.raw!!, 8000, 4000))
    }

    @Test
    fun `no file, a directory, and rubbish are all just a miss`() {
        assertNull(FileGardenCache(tempFile()).read())
        val rubbish = tempFile()
        rubbish.parentFile.mkdirs()
        rubbish.writeText("{ this is not json")
        assertNull(FileGardenCache(rubbish).read())
        // A cache that cannot be written must not throw either.
        val unwritable = File("/no/such/place/garden.json")
        FileGardenCache(unwritable).write(CachedGarden(listOf(basil), Health(), 1))
        assertNull(FileGardenCache(unwritable).read())
    }

    @Test
    fun `a write replaces the whole file rather than appending to it`() {
        val file = tempFile()
        val cache = FileGardenCache(file)
        cache.write(CachedGarden(listOf(basil, basil.copy(id = "pot-2", name = "mint")), Health(), 1))
        cache.write(CachedGarden(listOf(basil), Health(), 2))
        val back = cache.read()!!
        assertEquals(listOf("pot-1"), back.pots.map { it.id })
        assertEquals(2, back.atS)
        assertTrue(file.readText().startsWith("{"))
    }
}
