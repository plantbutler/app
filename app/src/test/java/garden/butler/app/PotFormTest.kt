package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val basil =
    Pot(
        id = "pot-3f9a21",
        name = "basil",
        species = "Ocimum_basilicum",
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
        assertEquals(mapOf("mode" to "manual", "enabled" to "1"), wireFields(Pot(name = "new")))
        assertEquals("0", wireFields(Pot(name = "off", enabled = 0))["enabled"])
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
    fun `the body puts the id and name first and the fields in wire order`() {
        val changed = mapOf("mode" to "learning", "target_low_pct" to "30", "controller" to "b1")
        assertEquals(
            "id=pot-3f9a21 name=basil controller=b1 target_low_pct=30 mode=learning",
            potBody("pot-3f9a21", "basil", changed),
        )
        assertEquals("name=Monstera_deliciosa", potBody(null, " Monstera  deliciosa\n", emptyMap()))
    }

    @Test
    fun `an id makes it an edit, no id makes it a create, and a rename is neither new nor empty`() {
        assertEquals("id=pot-3f9a21 name=basil channel=4", potBody("pot-3f9a21", "basil", mapOf("channel" to "4")))
        assertEquals("name=basil soil=peat", potBody(null, "basil", mapOf("soil" to "peat")))
        // A rename posts no changed field at all: the name is the edit.
        assertEquals("id=pot-3f9a21 name=genovese", potBody("pot-3f9a21", "genovese", emptyMap()))
        // An empty id is not an id: it would key the upsert on nothing.
        assertEquals("name=basil", potBody("", "basil", emptyMap()))
    }

    @Test
    fun `the draft carries the nickname, and changedFields never sends it twice`() {
        assertEquals("basil", draftOf(basil)["name"])
        assertFalse("name" in wireFields(basil))
        val renaming = draftOf(basil) + ("name" to "genovese")
        assertEquals(emptyMap(), changedFields(draftOf(basil), renaming))
        assertTrue(renamed(draftOf(basil), renaming))
        assertFalse(renamed(draftOf(basil), draftOf(basil)))
        // A blanked name is not a rename: the wire cannot clear it.
        assertFalse(renamed(draftOf(basil), draftOf(basil) + ("name" to "  ")))
        // A nickname is compared as it would travel, not as it was typed.
        assertFalse(renamed(draftOf(basil), draftOf(basil) + ("name" to " basil ")))
    }

    @Test
    fun `species is an editable field that round-trips through the draft`() {
        assertTrue(POT_FIELDS.any { it.key == "species" })
        assertEquals("Ocimum_basilicum", wireFields(basil)["species"])
        assertEquals("Ocimum_basilicum", draftOf(basil)["species"])
        assertEquals(
            mapOf("species" to "Ocimum_basilicum_var_genovese"),
            changedFields(draftOf(basil), draftOf(basil) + ("species" to "Ocimum basilicum var genovese")),
        )
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
        assertEquals("name=a=b", potBody(null, "a=b", emptyMap()))
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
                env = listOf(Pot(id = "pot-env", name = "env:temp")),
                disabled = listOf(Pot(id = "pot-thai", name = "Thai_basil", enabled = 0)),
                problems = emptyList(),
                health = Health(),
            )
        assertTrue(nameTaken(garden, "basil", selfId = null))
        assertTrue(nameTaken(garden, "Thai basil", selfId = null))
        assertTrue(nameTaken(garden, " Thai\u00A0basil ", selfId = null))
        assertTrue(nameTaken(garden, "env:temp", selfId = null))
        assertFalse(nameTaken(garden, "mint", selfId = null))
        assertFalse(nameTaken(garden, "Basil", selfId = null))
    }

    @Test
    fun `a pot may keep its own name, but may not take another pot's`() {
        val garden =
            Garden(
                pots = listOf(basil),
                env = emptyList(),
                disabled = listOf(Pot(id = "pot-thai", name = "Thai_basil", enabled = 0)),
                problems = emptyList(),
                health = Health(),
            )
        assertFalse(nameTaken(garden, "basil", selfId = "pot-3f9a21"))
        assertTrue(nameTaken(garden, "basil", selfId = "pot-other"))
        assertTrue(nameTaken(garden, "Thai basil", selfId = "pot-3f9a21"))
        assertFalse(nameTaken(garden, "genovese", selfId = "pot-3f9a21"))
    }

    @Test
    fun `the field list is the wire in order`() {
        assertEquals(
            listOf(
                "controller", "channel", "outlet", "species", "plant_type", "plant_size", "pot_size",
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
