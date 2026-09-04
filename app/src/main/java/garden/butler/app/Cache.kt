package garden.butler.app

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The last good answer, on disk, so the app has something to show off the
 * tailnet or with the NAS down. A cache, not a database: one file, written
 * whole, read once at launch, and thrown away the moment a live answer
 * arrives.
 *
 * Whole `Pot`s are stored, never anything derived from them. A cached
 * percentage would be read through whatever calibration the pot has when
 * the cache is opened, which after a recalibration is a different scale —
 * the curve would lie. Keeping the pots means every number is derived from
 * the calibration it was actually read with.
 */
@Serializable
data class CachedGarden(
    val pots: List<Pot> = emptyList(),
    val health: Health = Health(),
    /** The phone clock when the answer arrived, not the backend's: this is
     * "how long since I last heard anything", which is a question about
     * this phone. */
    @SerialName("at_s") val atS: Long = 0,
    /** Which butler these plants came from. The cache is cleared when the
     * app is pointed somewhere else, and this is what makes that safe
     * rather than merely likely: a delete that failed, or a kill between
     * storing the new address and clearing the file, would otherwise show
     * one server's garden under another's name. An older cache file has no
     * address and is discarded once, which costs one launch of the banner
     * and nothing else. */
    val url: String = "",
)

interface GardenCache {
    fun read(): CachedGarden?

    fun write(cached: CachedGarden)

    /** Forget everything. A cache belongs to one butler: point the app at
     * another and the old server's plants must not be what the new one's
     * garden shows while it loads — under a banner saying they are current
     * as of five minutes ago, which they are, on a machine nobody is
     * talking to any more. */
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
            // renameTo fails silently on some filesystems; a leftover .tmp
            // would then sit there forever while the cache quietly stopped
            // updating. Fall back to writing in place — worth the smaller
            // window, since the alternative is a cache frozen at whatever
            // it last managed to rename.
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
            // Same as a failed write: the worst case is a stale file, and
            // read() throws it away the moment it will not decode. What
            // must not happen is the app failing to change butler because
            // a file would not delete.
        }
    }
}
