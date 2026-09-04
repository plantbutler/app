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
        controller = 0,
        channel = 0,
        outlet = 3,
        plantType = "basil",
        dryRaw = 12000,
        wetRaw = 4000,
        targetLowPct = 30,
        mode = "learning",
        status = ALIVE,
        raw = 8123,
        pct = 48,
        readTs = 1788291874,
        proposal = Proposal(17, 100),
        lastDose = LastDose(16, 100, state = "acked"),
    )

class PotFormTest {
    @Test
    fun `wire fields render ints and the status, omit nulls, never leak the rest`() {
        val fields = wireFields(basil)
        assertEquals("0", fields["controller"])
        assertEquals("0", fields["channel"])
        assertEquals("12000", fields["dry_raw"])
        assertEquals("30", fields["target_low_pct"])
        assertEquals("learning", fields["mode"])
        assertEquals("alive", fields["status"])
        assertFalse("target_high_pct" in fields)
        assertFalse("soil" in fields)
        for (key in listOf("name", "raw", "pct", "read_ts", "proposal", "last_dose")) {
            assertNull(fields[key], key)
        }
        assertEquals(emptyList(), fields.keys.filter { key -> POT_FIELDS.none { it.key == key } })
    }

    @Test
    fun `a bare pot still says its mode and that it is alive`() {
        assertEquals(mapOf("mode" to "manual", "status" to "alive"), wireFields(Pot(name = "new")))
        assertEquals(
            "graveyard",
            wireFields(Pot(name = "gone", status = GRAVEYARD))["status"],
        )
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
        // No name at all: an edit that is not a rename. The backend's own
        // canonical recalibration body has this shape.
        assertEquals(
            "id=pot-3f9a21 dry_raw=13000 wet_raw=4200",
            potBody("pot-3f9a21", null, mapOf("dry_raw" to "13000", "wet_raw" to "4200")),
        )
        assertEquals("id=pot-3f9a21 channel=4", potBody("pot-3f9a21", null, mapOf("channel" to "4")))
    }

    @Test
    fun `formDirty sees a rename, a changed field and a blanked one, and nothing else`() {
        val stored = draftOf(basil)
        assertFalse(formDirty(stored, stored))
        assertTrue(formDirty(stored, stored + ("name" to "genovese")))
        assertTrue(formDirty(stored, stored + ("target_low_pct" to "35")))
        assertTrue(formDirty(stored, stored + ("controller" to "")))
        // Retyping the same values, however spelt, is not a change.
        assertFalse(formDirty(stored, stored + ("name" to " basil ")))
        assertFalse(formDirty(stored, stored + ("target_low_pct" to "30")))
        // A blanked name is not a change the wire could carry.
        assertFalse(formDirty(stored, stored + ("name" to "  ")))
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
    fun `a name is taken as it would travel, across living, buried and env pots`() {
        val garden =
            Garden(
                pots = listOf(basil),
                env = listOf(Pot(id = "pot-env", name = "env:temp")),
                graveyard = listOf(Pot(id = "pot-thai", name = "Thai_basil", status = GRAVEYARD)),
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
                graveyard = listOf(Pot(id = "pot-thai", name = "Thai_basil", status = GRAVEYARD)),
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
                "controller", "channel", "outlet", "species", "plant_type", "plant_height_cm",
                "pot_diameter_cm", "soil", "dry_raw", "wet_raw", "target_low_pct",
                "target_high_pct", "dose_ml", "cooldown_h", "daily_cap_ml", "mode", "status",
            ),
            POT_FIELDS.map { it.key },
        )
        assertEquals("target low %", POT_FIELDS.first { it.key == "target_low_pct" }.label)
        assertEquals(Input.INTEGER, POT_FIELDS.first { it.key == "dose_ml" }.input)
        assertEquals(Input.TEXT, POT_FIELDS.first { it.key == "mode" }.input)
        // Three closed sets, so three dropdowns rather than three boxes.
        for (key in listOf("plant_type", "soil", "status")) {
            assertEquals(Input.PICK, POT_FIELDS.first { it.key == key }.input, key)
        }
        // A measurement wants a keyboard with a point on it.
        assertEquals(Input.DECIMAL, POT_FIELDS.first { it.key == "pot_diameter_cm" }.input)
    }

    @Test
    fun `the label of a measurement says what it is measured in`() {
        // The field this replaces was called "pot size" and read the words
        // small and large, so 14cm — the README's own example, and the "3"
        // in the live garden — moved the band by nothing at all.
        assertEquals("pot diameter (cm)", POT_FIELDS.first { it.key == "pot_diameter_cm" }.label)
        assertEquals("plant height (cm)", POT_FIELDS.first { it.key == "plant_height_cm" }.label)
    }

    @Test
    fun `every field can explain itself, and the name too`() {
        // A form of seventeen boxes labelled in wire names is a form only
        // its author can fill in.
        for (field in POT_FIELDS + NAME_FIELD) {
            assertTrue(field.help.length > 40, "${field.key}: ${field.help}")
            assertTrue(field.help.trim().endsWith("."), "${field.key} is not a sentence")
        }
    }

    @Test
    fun `an info button belongs to a field, or to nothing`() {
        assertEquals("mode", fieldFor("mode")?.key)
        assertEquals("name", fieldFor("name")?.key)
        // A key no form has: a screen state left behind by an older build.
        assertNull(fieldFor("pot_size"))
        assertNull(fieldFor(null))
    }

    @Test
    fun `the kinds are the ones the backend accepts`() {
        // Twelve wire words, and they are the backend's, not the labels'. A
        // label is free to change; one of these is a 400.
        assertEquals(
            listOf(
                "cactus", "succulent", "orchid", "mediterranean", "bulb", "flower",
                "herb", "palm", "tropical", "vegetable", "fern", "carnivorous",
            ),
            PLANT_KINDS.map { it.wire },
        )
        assertTrue(PLANT_KINDS.all { it.label.isNotBlank() })
    }

    @Test
    fun `the soils are a closed set too, and only the ones that move the range`() {
        // An ordinary potting mix is deliberately absent: it is what the
        // plant kinds are written against, so "not said" is the same answer.
        assertEquals(
            listOf("sphagnum", "peat", "clay", "sandy", "perlite", "cactus", "bark"),
            SOIL_KINDS.map { it.wire },
        )
        assertTrue(SOIL_KINDS.none { it.wire == "potting" || it.wire == "loam" })
    }

    @Test
    fun `a picked field shows a label and falls back to the raw word`() {
        assertEquals("tropical foliage", labelFor("plant_type", "tropical"))
        assertEquals("bark or orchid mix", labelFor("soil", "bark"))
        assertEquals("graveyard", labelFor("status", "graveyard"))
        assertEquals("", labelFor("plant_type", null))
        // Written before these were sets, or by a backend newer than this
        // build: it renders as itself rather than crashing the form.
        assertEquals("basil", labelFor("plant_type", "basil"))
        assertEquals("hibernating", labelFor("status", "hibernating"))
        assertNull(choicesFor("species"))
    }

    @Test
    fun `a suggested kind fills an empty field and never overwrites one`() {
        val empty = mapOf("name" to "basil")
        assertEquals("herb", withKind(empty, "herb")["plant_type"])
        // Somebody picked cactus. A guess read off a botanical family
        // does not get to overrule that; it is offered instead.
        val answered = mapOf("plant_type" to "cactus")
        assertEquals("cactus", withKind(answered, "herb")["plant_type"])
        assertEquals("herb", suggestedKind(answered, "herb"))
        // Nothing to offer when the field already agrees, or when the
        // lookup had no idea, or when it named something no chip shows.
        // "moss" is not a kind and is not going to become one; "orchid"
        // was the example here until it did.
        assertNull(suggestedKind(mapOf("plant_type" to "herb"), "herb"))
        assertNull(suggestedKind(empty, null))
        assertNull(suggestedKind(empty, "moss"))
        assertEquals(empty, withKind(empty, "moss"))
    }

    @Test
    fun `a decimal comma reaches the wire as a point`() {
        // The keyboard on a phone set to Italian or German offers a comma
        // and often no point at all, and the backend refuses anything but
        // ASCII digits and one point — so without this the field is
        // untypable on the phone this app was written for.
        val original = mapOf("pot_diameter_cm" to "14")
        val draft = mapOf("pot_diameter_cm" to "14,5")
        assertEquals(mapOf("pot_diameter_cm" to "14.5"), changedFields(original, draft))
        // Only where a decimal point means something: a comma in a name or
        // a soil description is somebody's own words.
        assertEquals(
            mapOf("soil" to "loam,_gritty"),
            changedFields(mapOf("soil" to "loam"), mapOf("soil" to "loam, gritty")),
        )
    }

    @Test
    fun `a measurement loses its trailing zero on the way into the form`() {
        // 14.0 in a box the user is about to edit reads as precision nobody
        // measured — and it would make the form dirty the moment it opened.
        assertEquals("14", cmText(14.0))
        assertEquals("14.5", cmText(14.5))
        assertNull(cmText(null))
        val pot = Pot(id = "pot-1", name = "basil", potDiameterCm = 14.0, plantHeightCm = 21.5)
        assertEquals("14", wireFields(pot)["pot_diameter_cm"])
        assertEquals("21.5", wireFields(pot)["plant_height_cm"])
        assertFalse(formDirty(draftOf(pot), draftOf(pot)))
    }
}
