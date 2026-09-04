package garden.butler.app

import java.util.Locale

/** A pot's own photographs, as words and as decisions. Pure: the bitmap
 * work is in PhotoFile.kt, and everything here has a test. */

/** How many the strip asks for. A photograph a week for four years, which
 * is longer than any of these pots will live in one place. */
const val PHOTOS_LIMIT = 200

/** What the long edge is shrunk to before anything is uploaded.
 *
 * The pitch's first rabbit hole: a phone photo is several megabytes and the
 * NAS volume and its backup were never sized for hundreds of them. 1600 is
 * more than a phone screen shows and about 300-500 KB as a JPEG, so a
 * decade of weekly pictures is a couple of hundred megabytes rather than a
 * couple of dozen gigabytes. */
const val PHOTO_LONG_EDGE = 1600

/** JPEG quality on the way out. High enough that nobody can see the
 * difference in a leaf, low enough that the size claim above holds. */
const val PHOTO_QUALITY = 85

/** The power-of-two subsample that gets a (w, h) under `cap` on its long
 * edge without going under it — decoding straight to roughly the right size
 * rather than loading twelve megapixels into memory and scaling after.
 *
 * Never less than 1, and never so aggressive that the result is smaller
 * than the cap: 2 on a 2000px edge would give 1000, so it stays at 1 and
 * the exact scaling is done afterwards. */
fun sampleSize(w: Int, h: Int, cap: Int = PHOTO_LONG_EDGE): Int {
    val long = maxOf(w, h)
    if (long <= 0 || cap <= 0) return 1
    var sample = 1
    while (long / (sample * 2) >= cap) sample *= 2
    return sample
}

/** The size to decode to, keeping the aspect ratio, capped on the long
 * edge. A picture already smaller than the cap is left alone: upscaling a
 * photograph to meet a number would be inventing pixels. */
fun fitted(w: Int, h: Int, cap: Int = PHOTO_LONG_EDGE): Pair<Int, Int> {
    val long = maxOf(w, h)
    if (long <= cap || long <= 0) return w to h
    val scale = cap.toDouble() / long
    return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
}

/** The strip's order: oldest first, so it reads left to right as the plant
 * grew. The wire is newest first, like every other list here. */
fun strip(photos: List<Photo>): List<Photo> = photos.sortedWith(compareBy({ it.ts }, { it.id }))

/** Where one plant ended and the next began, as the ids the strip should
 * put a mark before.
 *
 * A pot outlives its plant. Nothing records a replant, so this reads the
 * species each picture was taken under and marks the changes — which is
 * honest about what it can and cannot see: replanting basil with basil
 * leaves no trace, and neither does a plant that was never named. The first
 * photograph is never a break; it is where the strip starts. */
fun speciesBreaks(photos: List<Photo>): Set<String> {
    val ordered = strip(photos)
    val breaks = mutableSetOf<String>()
    var previous: String? = null
    ordered.forEachIndexed { i, photo ->
        val species = photo.species?.takeIf { it.isNotBlank() }
        if (i > 0 && species != null && previous != null && species != previous) {
            breaks += photo.id
        }
        if (species != null) previous = species
    }
    return breaks
}

/** The date under a thumbnail: the day, and the year only when it is not
 * this one — a strip is read as a sequence of days, and four identical
 * years in every caption is noise. */
fun photoDay(ts: Long, nowS: Long): String {
    val when_ = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowS * 1000 }
    val pattern =
        if (when_.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
            "d MMM"
        } else {
            "d MMM yyyy"
        }
    return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(when_.time)
}

/** The line in the full-size view: when it was taken, what the plant was
 * called then, and how big the file is. */
fun photoLine(photo: Photo, nowS: Long): String =
    buildList {
        add(photoDay(photo.ts, nowS))
        photo.species?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (photo.missing) {
            add("the file is gone from the butler")
        } else if (photo.bytes > 0) {
            add(fileSize(photo.bytes))
        }
    }.joinToString(" · ")

fun fileSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 ->
            String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

/** What the strip says when it has nothing to show. A pot with no pictures
 * is the ordinary case, not a fault. */
fun stripEmptyLine(saved: Boolean): String =
    if (saved) {
        "No pictures yet — take one and this pot starts keeping its own history."
    } else {
        "Save the pot first; photographs hang off its id."
    }
