package garden.butler.app

/** The pot form is one Map<String, String> draft diffed against the stored
 * pot, keyed by the wire names, so the whole thing is a diff and a join —
 * no per-field classes, and no validation: the backend validates, and its
 * refusal is shown verbatim. `numeric` only picks the keyboard.
 */
data class Field(val key: String, val label: String, val numeric: Boolean)

val POT_FIELDS: List<Field> =
    listOf(
        Field("controller", "controller", numeric = false),
        Field("channel", "channel", numeric = true),
        Field("outlet", "outlet", numeric = true),
        Field("species", "species", numeric = false),
        Field("plant_type", "plant type", numeric = false),
        Field("plant_size", "plant size", numeric = false),
        Field("pot_size", "pot size", numeric = false),
        Field("soil", "soil", numeric = false),
        Field("dry_raw", "dry raw", numeric = true),
        Field("wet_raw", "wet raw", numeric = true),
        Field("target_low_pct", "target low %", numeric = true),
        Field("target_high_pct", "target high %", numeric = true),
        Field("dose_ml", "dose ml", numeric = true),
        Field("cooldown_h", "cooldown h", numeric = true),
        Field("daily_cap_ml", "daily cap ml", numeric = true),
        Field("mode", "mode", numeric = false),
        Field("enabled", "enabled", numeric = true),
    )

/** The stored pot as the wire would spell it: nulls omitted, ints plain,
 * enabled as 0|1. Never the name (the key of POST /pot) nor anything the
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
    put("plant_size", pot.plantSize)
    put("pot_size", pot.potSize)
    put("soil", pot.soil)
    put("dry_raw", pot.dryRaw)
    put("wet_raw", pot.wetRaw)
    put("target_low_pct", pot.targetLowPct)
    put("target_high_pct", pot.targetHighPct)
    put("dose_ml", pot.doseMl)
    put("cooldown_h", pot.cooldownH)
    put("daily_cap_ml", pot.dailyCapMl)
    put("mode", pot.mode)
    put("enabled", pot.enabled)
    return out
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

/** The keys worth sending: a non-empty tokenized draft value that differs
 * from what is stored. An emptied field is dropped, not nulled — the wire
 * has no way to null a column. Keys outside POT_FIELDS are ignored. */
fun changedFields(original: Map<String, String>, draft: Map<String, String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (field in POT_FIELDS) {
        val value = draft[field.key]?.let(::tokenize) ?: continue
        if (value.isNotEmpty() && value != original[field.key]) out[field.key] = value
    }
    return out
}

/** The stored fields the draft has blanked: the wire cannot clear them, so
 * the form has to say the edit will not land rather than drop it silently. */
fun emptiedFields(original: Map<String, String>, draft: Map<String, String>): List<Field> =
    POT_FIELDS.filter { field ->
        original[field.key] != null && draft[field.key]?.let { tokenize(it).isEmpty() } == true
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
