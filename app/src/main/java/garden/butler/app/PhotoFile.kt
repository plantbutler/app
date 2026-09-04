package garden.butler.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** A photograph downscaled and re-encoded, ready to go up. */
data class Shrunk(val jpeg: ByteArray, val w: Int, val h: Int) {
    // A ByteArray in a data class has reference equality by default, which
    // is not what anybody reading `==` here would expect. Nothing compares
    // these, so they are excluded rather than deep-compared.
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

/** Where the camera writes, before anything is shrunk: the app's own cache,
 * which the system may empty whenever it likes. Nothing is kept here — the
 * only copy that matters is on the butler. */
fun cameraFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    // One name, reused. The full-size original is worth nothing the moment
    // it has been shrunk and sent, and a directory of them would be the
    // several megabytes each that the pitch says to keep off the phone.
    return File(dir, "capture.jpg")
}

/** Decode, turn the right way up, cap the long edge, re-encode.
 *
 * All of it before anything is uploaded, per the pitch: the NAS volume and
 * its backup were never sized for phone photographs at full size. Decoding
 * is subsampled first, so a twelve-megapixel picture never arrives whole in
 * memory on the way to being 1600 pixels wide.
 *
 * Null when the file cannot be read as an image at all — a camera app that
 * was cancelled, or a cache the system emptied between the two.
 */
fun shrinkJpeg(context: Context, uri: Uri, cap: Int = PHOTO_LONG_EDGE): Shrunk? =
    try {
        val bounds =
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, cap)
                }
            val decoded =
                context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            if (decoded == null) {
                null
            } else {
                // A phone camera writes the sensor's orientation into EXIF
                // rather than rotating the pixels. Re-encoding drops the
                // tag, so without this every picture taken in portrait
                // would come back on its side, for good.
                val upright = turned(context, uri, decoded)
                // Each step can hold a whole bitmap, so the one before it
                // is let go as soon as it is not the one being used. Three
                // live at once is the difference between fitting in a
                // phone's heap and not.
                if (upright !== decoded) decoded.recycle()
                val (w, h) = fitted(upright.width, upright.height, cap)
                val scaled =
                    if (w == upright.width && h == upright.height) {
                        upright
                    } else {
                        Bitmap.createScaledBitmap(upright, w, h, true).also { upright.recycle() }
                    }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)
                Shrunk(out.toByteArray(), scaled.width, scaled.height).also { scaled.recycle() }
            }
        }
    } catch (why: OutOfMemoryError) {
        // Its own clause, and not folded into the one below, because
        // OutOfMemoryError is an Error and not an Exception: a picture too
        // big for the heap is the one failure this whole function exists to
        // survive, and catching Exception alone would let it crash the app.
        null
    } catch (why: Exception) {
        // A file that vanished, a camera app that wrote nothing, anything
        // else: the screen says the picture could not be read, which is all
        // the person can act on.
        null
    }

private fun turned(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees =
        try {
            context.contentResolver.openInputStream(uri).use { stream ->
                when (
                    ExifInterface(stream!!)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (why: Exception) {
            0f
        }
    if (degrees == 0f) return bitmap
    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
