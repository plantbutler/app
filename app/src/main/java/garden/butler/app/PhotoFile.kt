package garden.butler.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** The bitmap half of a pot's photographs, Android-only: where the camera
 * writes, and shrinkJpeg, which decodes, turns upright, caps the long edge
 * and re-encodes. The words and decisions around it are in Photos.kt. */

/** A photograph downscaled and re-encoded, ready to go up. */
data class Shrunk(val jpeg: ByteArray, val w: Int, val h: Int) {
    // A ByteArray in a data class compares by reference, not contents.
    // Nothing compares these, so identity equality is left as is.
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

/** Where the camera writes, before anything is shrunk: the app's own cache,
 * which the system may empty whenever it likes. Nothing is kept here — the
 * only copy that matters is on the butler. */
fun cameraFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    // One name, reused. The full-size original is worth nothing once it
    // has been shrunk and sent, and a directory of them would be the
    // several megabytes each this is meant to keep off the phone.
    return File(dir, "capture.jpg")
}

/** Decode, turn the right way up, cap the long edge, re-encode.
 *
 * All of it before anything is uploaded: the NAS volume and its backup were
 * never sized for phone photographs at full size. Decoding is subsampled
 * first, so a twelve-megapixel picture never arrives whole in memory on the
 * way to being 1600 pixels wide.
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
                // The camera writes orientation into EXIF, not the pixels;
                // re-encoding drops the tag, so this must run first or every
                // portrait picture comes back on its side, for good.
                val upright = turned(context, uri, decoded)
                // Each step can hold a whole bitmap, so the previous one is
                // recycled as soon as it's unused — three alive at once is
                // the difference between fitting in a phone's heap and not.
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
        // Its own clause: OutOfMemoryError is an Error, not an Exception,
        // and a picture too big for the heap is the one failure this
        // function exists to survive — catching Exception alone would miss it.
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
