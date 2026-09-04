package garden.butler.app

/** The pot form is one Map<String, String> draft diffed against the stored
 * pot, keyed by the wire names, so the whole thing is a diff and a join —
 * no per-field classes, and no validation: the backend validates, and its
 * refusal is shown verbatim. `input` only picks the keyboard.
 *
 * `help` is the sentence behind the ⓘ. Every field has one, because a
 * form of seventeen boxes labelled in wire names is a form only its author
 * can fill in — and three of these fields (the kind of plant, and the two
 * limits under the dose) are worth more than the box is wide.
 */
enum class Input {
    TEXT,
    INTEGER,
    DECIMAL,
    /** One of a closed set: a dropdown, not a box. The backend refuses
     * anything outside the set, so a typed value could only ever be a
     * refusal — and, before these were sets, a silent wrong band. */
    PICK,
}

/** The values a picked field offers, or null for a field that is typed. */
fun choicesFor(key: String): List<Kind>? =
    when (key) {
        "plant_type" -> PLANT_KINDS
        "soil" -> SOIL_KINDS
        "status" -> POT_STATUSES
        else -> null
    }

/** What to show for a stored value: its label, or the value itself when
 * this build has never heard of it. A pot written by a newer backend, or
 * by free text before these were sets, must render rather than crash. */
fun labelFor(key: String, wire: String?): String =
    when {
        wire.isNullOrEmpty() -> ""
        else -> choicesFor(key)?.firstOrNull { it.wire == wire }?.label ?: wire
    }

data class Field(val key: String, val label: String, val input: Input, val help: String)

/** A closed set the backend will accept, and what to call each value on
 * screen. The wire words are its own — a label change here must never
 * become a wire change, which is why the two are separate fields. */
data class Kind(val wire: String, val label: String)

/** Driest first, so the dropdown reads as the one axis it actually is. */
val PLANT_KINDS: List<Kind> =
    listOf(
        Kind("cactus", "cactus"),
        Kind("succulent", "succulent"),
        Kind("orchid", "orchid or epiphyte"),
        Kind("mediterranean", "Mediterranean or woody shrub"),
        Kind("bulb", "bulb"),
        Kind("flower", "flowering"),
        Kind("herb", "herb"),
        Kind("palm", "palm"),
        Kind("tropical", "tropical foliage"),
        Kind("vegetable", "vegetable"),
        Kind("fern", "fern"),
        Kind("carnivorous", "carnivorous or bog"),
    )

/** The soils that MOVE the range. An ordinary potting mix is not among
 * them on purpose: it is what the plant kinds are written against, so
 * "not said" and "the bag from the shop" are the same answer. Wettest
 * first, matching the kinds' own direction. */
val SOIL_KINDS: List<Kind> =
    listOf(
        Kind("sphagnum", "sphagnum moss"),
        Kind("peat", "peat-based"),
        Kind("clay", "clay-heavy"),
        Kind("sandy", "sandy or gritty"),
        Kind("perlite", "perlite-heavy"),
        Kind("cactus", "cactus or succulent mix"),
        Kind("bark", "bark or orchid mix"),
    )

/** What a pot IS. `alive` waters; everything else is an aside. Two values
 * today and room for a third, which is why the control is a list and not
 * a switch. */
val POT_STATUSES: List<Kind> =
    listOf(
        Kind(ALIVE, "alive"),
        Kind(GRAVEYARD, "graveyard"),
    )

val POT_FIELDS: List<Field> =
    listOf(
        Field(
            "controller",
            "controller",
            Input.INTEGER,
            "Which board reports this pot, by number. One board carries several pots; this " +
                "is the number it sends as c= in every report, and there is one board, so " +
                "0 is almost certainly right.",
        ),
        Field(
            "channel",
            "channel",
            Input.INTEGER,
            "Which sensor socket on that board this pot's probe is plugged into, counting " +
                "from 0. Readings taken before you change it stay with the pot they were " +
                "taken for.",
        ),
        Field(
            "outlet",
            "outlet",
            Input.INTEGER,
            "Which outlet on the manifold this pot's hose hangs on, counting from 0. " +
                "Moving a hose is an edit here, and it takes the pot's watering history " +
                "with it rather than leaving it for whatever hangs there next.",
        ),
        Field(
            "species",
            "species",
            Input.TEXT,
            "The botanical name, if you know one. Looking it up corrects a typo, follows a " +
                "plant that was renamed, and offers a kind below. It sets no watering " +
                "number: no source carries one.",
        ),
        Field(
            "plant_type",
            "kind of plant",
            Input.PICK,
            "The biggest lever on the suggested range: an unlabelled plant starts at " +
                "35–55%, a succulent at 15–30%. Not sure is a real answer and has its own " +
                "sensible band — a wrong one here is twenty points no measurement recovers.",
        ),
        Field(
            "plant_height_cm",
            "plant height (cm)",
            Input.DECIMAL,
            "How tall the plant stands. Read against the pot rather than on its own: 40 cm " +
                "of basil is thirsty in a 10 cm pot and comfortable in a 30 cm one. It only " +
                "ever raises the bottom of the range, never the top.",
        ),
        Field(
            "pot_diameter_cm",
            "pot diameter (cm)",
            Input.DECIMAL,
            "How wide the pot is across the rim. The pot is the water store, and the store " +
                "goes as the cube of this: a small one runs dry before anybody looks again, " +
                "so its floor rises; a big one holds water around roots that rot, so its " +
                "ceiling drops.",
        ),
        Field(
            "soil",
            "soil",
            Input.PICK,
            "What it is potted in. Only the mixes that actually change how long water stays " +
                "are listed: sphagnum and peat hold it, clay holds it at the top, and sand, " +
                "perlite, cactus grit and bark let it go. Ordinary potting compost is what " +
                "everything else is measured against, so leaving this unset is the same answer.",
        ),
        Field(
            "dry_raw",
            "dry raw",
            Input.INTEGER,
            "What the sensor counts with the probe bone dry in air. Not a percentage — " +
                "every percentage you see is worked out from these two numbers when it is " +
                "shown, so recalibrating re-reads the whole history.",
        ),
        Field(
            "wet_raw",
            "wet raw",
            Input.INTEGER,
            "What the sensor counts with the probe in water. The other end of the line " +
                "every percentage is read off. Recalibrate measures both for you.",
        ),
        Field(
            "target_low_pct",
            "target low %",
            Input.INTEGER,
            "Water when moisture falls below this. A percentage on this pot's own " +
                "calibration — 0 is dry air, 100 is tap water — and nothing to do with any " +
                "figure a plant database prints.",
        ),
        Field(
            "target_high_pct",
            "target high %",
            Input.INTEGER,
            "The top of the range this pot should sit in. Nothing waters towards it: it is " +
                "the ceiling a dose is not supposed to overshoot.",
        ),
        Field(
            "dose_ml",
            "dose ml",
            Input.INTEGER,
            "How much water one dose delivers. The first of three limits, and the only one " +
                "that decides the size of a watering.",
        ),
        Field(
            "cooldown_h",
            "cooldown h",
            Input.INTEGER,
            "The least time between two doses for this pot. The second limit: it stops a " +
                "slow pot being watered again before the last dose has reached the sensor.",
        ),
        Field(
            "daily_cap_ml",
            "daily cap ml",
            Input.INTEGER,
            "The most water this pot may get in one day. The third limit, and the one that " +
                "holds when the other two turn out to be wrong.",
        ),
        Field(
            "mode",
            "mode",
            Input.TEXT,
            "manual — the butler watches and you do the watering. learning — it proposes a " +
                "dose and waits for you to approve each one. auto — it waters on its own. " +
                "Per pot, and always a human act: nothing switches itself.",
        ),
        Field(
            "status",
            "status",
            Input.PICK,
            "Graveyard is for a plant that has died: no proposals, no doses and no alerts, and " +
                "it lets go of its channel and its outlet so another pot can have them. It " +
                "keeps everything else — its readings, its watering history, its photographs. " +
                "Bringing it back leaves it unwired, so you say where the new plant went.",
        ),
    )

/** The nickname, which is not a POT_FIELDS key — it is the one thing a
 * create must have, and it is diffed separately — but wants explaining like
 * the rest, because "you may rename this freely" is not obvious of a field
 * that used to be the pot's identity. */
val NAME_FIELD =
    Field(
        "name",
        "name",
        Input.TEXT,
        "What you call this plant. Yours to change whenever: the butler keys everything on " +
            "a hidden id, so a rename keeps the pot's readings, its doses and its " +
            "photographs. It only has to be different from your other pots' names.",
    )

/** The field one ⓘ belongs to, name included. Null for a key no form has,
 * which is what a stale screen state looks like. */
fun fieldFor(key: String?): Field? =
    if (key == null) null else (POT_FIELDS + NAME_FIELD).firstOrNull { it.key == key }

/** The kind a lookup offered, if it is worth offering. Nothing is ever
 * overwritten: a kind already in the form is a human's answer and outranks
 * a guess read off a botanical family. */
fun suggestedKind(draft: Map<String, String>, kind: String?): String? {
    if (kind == null || PLANT_KINDS.none { it.wire == kind }) return null
    return if (draft["plant_type"] == kind) null else kind
}

/** What the form looks like after a lookup: the suggestion fills the field
 * only while it is still empty. When it is not, `suggestedKind` puts the
 * offer on screen as something to tap instead. */
fun withKind(draft: Map<String, String>, kind: String?): Map<String, String> =
    if (kind != null && suggestedKind(draft, kind) != null && draft["plant_type"].isNullOrBlank()) {
        draft + ("plant_type" to kind)
    } else {
        draft
    }

/** The stored pot as the wire would spell it: nulls omitted, ints plain,
 * status as its word. Never the name (the key of POST /pot) nor anything the
 * board wrote (raw, pct, read_ts) or the backend derived (proposal,
 * last_dose): those are not editable. */
fun wireFields(pot: Pot): Map<String, String> {
    val out = linkedMapOf<String, String>()
    fun put(key: String, value: Any?) {
        if (value != null) out[key] = value.toString()
    }
    put("controller", pot.controller)
    put("channel", pot.channel)
    put("outlet", pot.outlet)
    put("species", pot.species)
    put("plant_type", pot.plantType)
    put("plant_height_cm", cmText(pot.plantHeightCm))
    put("pot_diameter_cm", cmText(pot.potDiameterCm))
    put("soil", pot.soil)
    put("dry_raw", pot.dryRaw)
    put("wet_raw", pot.wetRaw)
    put("target_low_pct", pot.targetLowPct)
    put("target_high_pct", pot.targetHighPct)
    put("dose_ml", pot.doseMl)
    put("cooldown_h", pot.cooldownH)
    put("daily_cap_ml", pot.dailyCapMl)
    put("mode", pot.mode)
    put("status", pot.status)
    return out
}

/** A measurement as the wire spells it: 14.0 is 14, 14.5 stays 14.5. A
 * trailing zero in a box the user is about to edit reads as precision
 * nobody measured, and it would also make the form dirty on open. */
fun cmText(value: Double?): String? =
    when {
        value == null -> null
        value == Math.floor(value) && !value.isInfinite() -> value.toLong().toString()
        else -> value.toString()
    }

/** Values are single words on the wire: "Monstera deliciosa" travels as
 * Monstera_deliciosa. Every Unicode space counts — a phone keyboard's NBSP
 * would otherwise split a token the backend then refuses. */
fun tokenize(value: String): String =
    buildString {
        var gap = false
        for (ch in value.trim()) {
            if (ch.isWhitespace()) {
                gap = true
            } else {
                if (gap) append('_')
                gap = false
                append(ch)
            }
        }
    }

/** One field's value as the wire spells it. A decimal COMMA becomes a
 * point: a phone keyboard set to a locale that writes 14,5 offers a comma
 * and often no point at all, and the backend refuses anything that is not
 * ASCII digits and one point — so the field would be untypable on half the
 * phones in Europe, this one included. */
fun wireValue(field: Field, value: String): String =
    tokenize(value).let { if (field.input == Input.DECIMAL) it.replace(',', '.') else it }

/** The keys worth sending: a non-empty draft value that differs from what
 * is stored. An emptied field is dropped, not nulled — the wire has no way
 * to null a column. Keys outside POT_FIELDS are ignored. */
fun changedFields(original: Map<String, String>, draft: Map<String, String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (field in POT_FIELDS) {
        val value = draft[field.key]?.let { wireValue(field, it) } ?: continue
        if (value.isNotEmpty() && value != original[field.key]) out[field.key] = value
    }
    return out
}

/** The stored fields the draft has blanked: the wire cannot clear them, so
 * the form has to say the edit will not land rather than drop it silently. */
fun emptiedFields(original: Map<String, String>, draft: Map<String, String>): List<Field> =
    POT_FIELDS.filter { field ->
        original[field.key] != null && draft[field.key]?.let { wireValue(field, it).isEmpty() } == true
    }

/** A name that would land on another pot. The backend keeps nicknames
 * unique, so a "new" pot spelled like a stored one would be refused —
 * and renaming a pot to the name it already has is not a clash, which is
 * why the caller passes the pot's own id. A create has none. */
fun nameTaken(garden: Garden, name: String, selfId: String?): Boolean {
    val other = garden.potNamed(tokenize(name)) ?: return false
    return other.id != selfId
}

/** The nickname the form is editing, as the wire would spell it. */
fun draftName(draft: Map<String, String>): String = tokenize(draft["name"].orEmpty())

/** The draft renames the pot: a non-empty nickname that differs from the
 * stored one. A blanked name is not a rename — the wire cannot clear it. */
fun renamed(original: Map<String, String>, draft: Map<String, String>): Boolean {
    val name = draftName(draft)
    return name.isNotEmpty() && name != original["name"]
}

/** Everything the form would post, in one question: is there anything to
 * save? A rename is not a POT_FIELDS key, so it needs asking separately. */
fun formDirty(original: Map<String, String>, draft: Map<String, String>): Boolean =
    changedFields(original, draft).isNotEmpty() ||
        emptiedFields(original, draft).isNotEmpty() ||
        renamed(original, draft)

/** The POST /pot body. An id makes it an edit; `name` is sent only when it
 * is the edit — a nickname resent out of habit would travel as an
 * instruction and overwrite a rename made from another phone while this
 * form sat open. No id makes it a create, the name is then required, and
 * the backend mints the id. The changed keys follow in POT_FIELDS order. */
fun potBody(id: String?, name: String?, changed: Map<String, String>): String =
    buildList {
        if (!id.isNullOrEmpty()) add("id=$id")
        if (name != null) add("name=" + tokenize(name))
        for (field in POT_FIELDS) changed[field.key]?.let { add("${field.key}=$it") }
    }.joinToString(" ")

/** The form opens pre-filled with the stored values: 30 -> 35 is a
 * two-character edit, not a retype. The nickname rides in the same map so
 * the form can diff a rename the way it diffs everything else; it is not
 * a POT_FIELDS key, so changedFields never sends it twice. */
fun draftOf(pot: Pot): Map<String, String> = wireFields(pot) + ("name" to pot.name)
