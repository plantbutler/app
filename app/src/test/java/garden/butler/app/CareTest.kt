package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CareTest {
    private fun care(
        found: Boolean = true,
        light: Int? = null,
        humidity: Int? = null,
        phMin: Double? = null,
        phMax: Double? = null,
        tempMinC: Double? = null,
        commonName: String? = null,
    ) = Care(
        found = found,
        light = light,
        humidity = humidity,
        phMin = phMin,
        phMax = phMax,
        tempMinC = tempMinC,
        commonName = commonName,
    )

    @Test
    fun `a plant the source has never heard of has no line`() {
        assertNull(careLine(care(found = false, light = 7)))
        assertNull(careLine(null))
    }

    @Test
    fun `a plant the source knows but has no numbers for has no line either`() {
        // Dracaena trifasciata: resolves, and every growth field null. The
        // ordinary houseplant answer, and it must not render as an empty row.
        assertNull(careLine(care()))
    }

    @Test
    fun `the numbers say whose scale they are on`() {
        val line = careLine(care(light = 7, humidity = 5, phMin = 6.5, phMax = 7.0))
        assertEquals("light 7/10 · humidity 5/10 · pH 6.5–7", line)
    }

    @Test
    fun `a light level of zero is a number and not an absence`() {
        assertEquals("light 0/10", careLine(care(light = 0)))
    }

    @Test
    fun `one end of a pH range still says something`() {
        assertEquals("pH 6.5", careLine(care(phMin = 6.5)))
        assertEquals("pH 7", careLine(care(phMin = 7.0, phMax = 7.0)))
    }

    @Test
    fun `a minimum temperature is rounded, not printed raw`() {
        assertEquals("above 15°C", careLine(care(tempMinC = 15.0)))
    }

    @Test
    fun `the common name is dropped when it only repeats the binomial`() {
        assertEquals("Basil", commonName(care(commonName = "Basil"), "Ocimum basilicum"))
        assertNull(commonName(care(commonName = "ocimum  basilicum"), "Ocimum_basilicum"))
        assertNull(commonName(care(commonName = "  "), "Ocimum basilicum"))
    }

    @Test
    fun `the offer leads with the numbers`() {
        val advice = Advice(low = 30, high = 50, why = "culinary herb, sandy loam soil")
        assertEquals("suggested 30–50% · culinary herb, sandy loam soil", adviceLine(advice))
        assertEquals("suggested 30–50%", adviceLine(advice.copy(why = "")))
    }

    @Test
    fun `a renamed plant is worth a button and a matching one is not`() {
        val renamed = SpeciesAnswer(accepted = "Dracaena trifasciata", matched = "exact")
        assertEquals("Dracaena trifasciata", betterName(renamed, "Sansevieria_trifasciata"))
        val same = SpeciesAnswer(accepted = "Ocimum basilicum", matched = "exact")
        assertNull(betterName(same, "ocimum_basilicum"))
        assertNull(betterName(SpeciesAnswer(matched = "none"), "zzqq"))
    }
}
