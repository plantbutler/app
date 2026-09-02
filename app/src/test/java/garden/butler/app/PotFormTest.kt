package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val basil =
    Pot(
        name = "basil",
        controller = "b1",
        channel = 0,
        outlet = 3,
        plantType = "basil",
        dryRaw = 12000,
        wetRaw = 4000,
        targetLowPct = 30,
        mode = "learning",
        enabled = 1,
        raw = 8123,
        pct = 48,
        readTs = 1788291874,
        proposal = Proposal(17, 100),
        lastDose = LastDose(16, 100, state = "acked"),
    )

class PotFormTest {
    @Test
    fun `wire fields render ints and enabled, omit nulls, never leak the rest`() {
        val fields = wireFields(basil)
        assertEquals("b1", fields["controller"])
        assertEquals("0", fields["channel"])
        assertEquals("12000", fields["dry_raw"])
        assertEquals("30", fields["target_low_pct"])
        assertEquals("learning", fields["mode"])
        assertEquals("1", fields["enabled"])
        assertFalse("target_high_pct" in fields)
        assertFalse("soil" in fields)
        for (key in listOf("name", "raw", "pct", "read_ts", "proposal", "last_dose")) {
            assertNull(fields[key], key)
        }
        assertEquals(emptyList(), fields.keys.filter { key -> POT_FIELDS.none { it.key == key } })
    }

    @Test
    fun `a bare pot still says its mode and that it is enabled`() {
        assertEquals(mapOf("mode" to "manual", "enabled" to "1"), wireFields(Pot("new")))
        assertEquals("0", wireFields(Pot("off", enabled = 0))["enabled"])
    }

    @Test
    fun `an unchanged draft changes nothing`() {
        assertEquals(emptyMap(), changedFields(wireFields(basil), draftOf(basil)))
    }

    @Test
    fun `changed fields keep edits, drop emptied and unchanged, tokenize`() {
        val original = wireFields(basil)
        val draft =
            draftOf(basil) +
                mapOf(
                    "target_low_pct" to "35",
                    "controller" to "", // emptied: the wire cannot null it
                    "plant_type" to "  Thai basil ", // stays a single word
                    "mode" to "learning",
                    "bogus" to "x",
                )
        assertEquals(
            mapOf("plant_type" to "Thai_basil", "target_low_pct" to "35"),
            changedFields(original, draft),
        )
    }

    @Test
    fun `create mode is a diff against nothing`() {
        val changed =
            changedFields(emptyMap(), mapOf("controller" to "b1", "channel" to "2", "soil" to ""))
        assertEquals(mapOf("controller" to "b1", "channel" to "2"), changed)
    }

    @Test
    fun `the body puts the name first and the fields in wire order`() {
        val changed = mapOf("mode" to "learning", "target_low_pct" to "30", "controller" to "b1")
        assertEquals(
            "name=basil controller=b1 target_low_pct=30 mode=learning",
            potBody("basil", changed),
        )
        assertEquals("name=Monstera_deliciosa", potBody(" Monstera  deliciosa\n", emptyMap()))
    }

    @Test
    fun `tokenize trims and joins whitespace runs`() {
        assertEquals("Monstera_deliciosa", tokenize("Monstera deliciosa"))
        assertEquals("a_b", tokenize("  a \t\n b  "))
        assertEquals("", tokenize("   "))
    }

    @Test
    fun `tokenize folds every Unicode space, not only ASCII`() {
        assertEquals("Thai_basil", tokenize("Thai\u00A0basil"))
        assertEquals("Thai_basil", tokenize("Thai\u3000basil"))
        assertEquals("Thai_basil", tokenize("\u00A0Thai \u00A0\tbasil\u3000"))
        assertEquals("a_b", tokenize("a\t\n\r b"))
        assertEquals("", tokenize("\u00A0\u3000\t"))
        assertEquals(emptyMap(), changedFields(emptyMap(), mapOf("soil" to "\u00A0\u3000")))
    }

    @Test
    fun `tokenize leaves an equals sign for the backend to refuse`() {
        assertEquals("a=b", tokenize("a=b"))
        assertEquals("name=a=b", potBody("a=b", emptyMap()))
    }

    @Test
    fun `emptied fields are the stored values the draft blanked`() {
        val original = wireFields(basil)
        val draft =
            draftOf(basil) +
                mapOf(
                    "controller" to "",
                    "plant_type" to " \u00A0",
                    "target_low_pct" to "35",
                    "soil" to "", // was never stored: nothing to clear
                )
        assertEquals(listOf("controller", "plant_type"), emptiedFields(original, draft).map { it.key })
        assertEquals(emptyList(), emptiedFields(original, draftOf(basil)))
        assertEquals(emptyList(), emptiedFields(emptyMap(), mapOf("controller" to "")))
    }

    @Test
    fun `a name is taken as it would travel, across enabled, disabled and env pots`() {
        val garden =
            Garden(
                pots = listOf(basil),
                env = listOf(Pot("env:temp")),
                disabled = listOf(Pot("Thai_basil", enabled = 0)),
                problems = emptyList(),
                health = Health(),
            )
        assertTrue(nameTaken(garden, "basil"))
        assertTrue(nameTaken(garden, "Thai basil"))
        assertTrue(nameTaken(garden, " Thai\u00A0basil "))
        assertTrue(nameTaken(garden, "env:temp"))
        assertFalse(nameTaken(garden, "mint"))
        assertFalse(nameTaken(garden, "Basil"))
    }

    @Test
    fun `the field list is the wire in order`() {
        assertEquals(
            listOf(
                "controller", "channel", "outlet", "plant_type", "plant_size", "pot_size",
                "soil", "dry_raw", "wet_raw", "target_low_pct", "target_high_pct", "dose_ml",
                "cooldown_h", "daily_cap_ml", "mode", "enabled",
            ),
            POT_FIELDS.map { it.key },
        )
        assertEquals("target low %", POT_FIELDS.first { it.key == "target_low_pct" }.label)
        assertEquals(true, POT_FIELDS.first { it.key == "dose_ml" }.numeric)
        assertEquals(false, POT_FIELDS.first { it.key == "mode" }.numeric)
    }
}
