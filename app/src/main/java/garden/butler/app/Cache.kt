// The last good answer from the butler, kept on disk, and the file it lives in.
package garden.butler.app

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Something to show with the butler out of reach. A cache, not a database:
 * one file, written whole, read once at launch, and dropped the moment a
 * live answer arrives.
 *
 * Whole `Pot`s, never anything derived from them: a cached percentage would
 * be read back through whatever calibration the pot has when the cache is
 * opened, and after a recalibration that is a different scale.
 */
@Serializable
data class CachedGarden(
    val pots: List<Pot> = emptyList(),
    val health: Health = Health(),
    /** The phone's clock when the answer arrived, not the backend's: this
     * answers how long since we last heard anything, which is a question
     * about this phone. */
    @SerialName("at_s") val atS: Long = 0,
    /** Which butler these plants came from. Pointing the app elsewhere
     * clears the cache; this is what makes that safe rather than merely
     * likely, since a delete that failed, or a kill between storing the new
     * address and clearing the file, would otherwise show one server's
     * garden under another's name. A file with no address is discarded. */
    val url: String = "",
)

interface GardenCache {
    fun read(): CachedGarden?

    fun write(cached: CachedGarden)

    /** Forget everything. A cache belongs to one butler: the old server's
     * plants must not fill the new one's garden while it loads, under a
     * banner calling them five minutes old — which they are, on a machine
     * nobody is talking to any more. */
    fun clear()
}

/** One JSON file in the app's own storage. Every failure is a miss: a cache
 * that throws on a half-written file would take the whole app down for a
 * convenience. */
class FileGardenCache(private val file: File) : GardenCache {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): CachedGarden? =
        try {
            file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<CachedGarden>(it) }
        } catch (why: Exception) {
            null
        }

    override fun write(cached: CachedGarden) {
        try {
            // Whole-file replace through a temp: a kill mid-write leaves the
            // previous good cache, not half of this one.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedGarden.serializer(), cached))
            // renameTo returns false rather than throwing on some
            // filesystems, and the cache would then sit frozen at whatever
            // it last managed to rename. Writing in place is a smaller
            // window, not a lost one.
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (why: Exception) {
            // A cache that cannot be written is a cache miss next launch.
        }
    }

    override fun clear() {
        try {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        } catch (why: Exception) {
            // The worst case is a stale file, which read() throws away the
            // moment it will not decode. What must not happen is the app
            // failing to change butler because a file would not delete.
        }
    }
}
