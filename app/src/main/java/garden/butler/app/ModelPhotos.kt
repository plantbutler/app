// A pot's own photographs: reading the strip, adding to it, forgetting one.
package garden.butler.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pot's own growth history. Single-flight like the chart's loader:
 * an upload refreshes it, and two answers racing would put the older
 * strip back over the newer one. A failed load keeps whatever strip is
 * already up and says why beside it. */
internal fun GardenViewModel.loadPhotos(form: Screen.Pot) {
    val id = form.id ?: return
    photosFlight =
        latestOnly(
            photosFlight,
            { backend.photos(id) },
            { answer -> onPot(form) { it.copy(photos = answer.photos, photosWhy = null) } },
            { why -> onPot(form) { it.copy(photosWhy = "pictures: $why") } },
        )
}

internal fun GardenViewModel.reloadPhotos() = (shown.value as? Screen.Pot)?.let { loadPhotos(it) }

/** Re-read one form's strip, and only if that form is still up. Async
 * outcomes land only on the form they came from, and a reload is an
 * outcome like any other. */
private fun GardenViewModel.reloadPhotosOf(of: Screen.Pot) =
    (shown.value as? Screen.Pot)?.takeIf { it.isForm(of) }?.let { loadPhotos(it) }

/** Where a picture is and what it takes to read it: the photo routes
 * are the only gated reads, so the image loader needs the header. */
internal fun GardenViewModel.photoSource(photoId: String): PhotoSource = backend.photoSource(photoId)

/** One picture, already downscaled by the screen that took it. The
 * bytes go up; what comes back is the strip, re-read. */
internal fun GardenViewModel.addPhoto(jpeg: ByteArray, w: Int, h: Int) {
    val form = shown.value as? Screen.Pot ?: return
    val id = form.id ?: return noteOnPot(form, "save the pot first")
    staleRefusal()?.let { why -> return noteOnPot(form, why) }
    onPot(form) { it.copy(uploading = true, note = null, photosWhy = null) }
    background {
        val why =
            try {
                withContext(Dispatchers.IO) { backend.addPhoto(id, jpeg, w, h) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                refused.reason()
            }
        onPot(form) { it.copy(uploading = false, note = why) }
        // Whether it landed or not: a POST that timed out client-side
        // may still have stored the picture, and the strip is what says
        // which happened. Only this form's, though — the user may have
        // moved on to another pot by now, and reloading whatever is on
        // screen would cancel that pot's own fetch to re-ask a question
        // nobody asked.
        reloadPhotosOf(form)
    }
}

internal fun GardenViewModel.viewPhoto(photoId: String?) = onPot { it.copy(viewing = photoId) }

/** Forget one picture. The row goes first on the backend, so this is
 * gone from the strip even if the volume will not give up the bytes. */
internal fun GardenViewModel.deletePhoto(photoId: String) {
    val form = shown.value as? Screen.Pot ?: return
    staleRefusal()?.let { why -> return noteOnPot(form, why) }
    onPot(form) { it.copy(viewing = null) }
    background {
        val note =
            try {
                withContext(Dispatchers.IO) { backend.deletePhoto(photoId) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                refused.reason()
            }
        onPot(form) { it.copy(note = note) }
        reloadPhotosOf(form)
    }
}
