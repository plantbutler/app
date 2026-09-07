// What the app is showing, and where in it the user is.
package garden.butler.app

sealed interface UiState {
    data object Loading : UiState

    data class Trouble(val why: String, val retrying: Boolean = false) : UiState

    data class Ready(
        val garden: Garden,
        val refreshing: Boolean = false,
        val why: String? = null, // the last refresh failed; the list stays up
        /** Non-null means every number here came off the disk at that
         * moment and nothing has been heard from the butler since. */
        val cachedAtS: Long? = null,
    ) : UiState
}

/** Where the app is. A flow of its own beside the garden, so a refresh
 * never knocks the user out of a half-edited form. */
sealed interface Screen {
    data object Garden : Screen

    /** `id == null` is create mode: the backend mints the id on save. The
     * nickname lives in `draft["name"]` either way, which is what makes a
     * rename an ordinary edit. `original` is what the backend stores, so
     * the form is a diff against it. */
    data class Pot(
        val id: String?,
        val original: Map<String, String>,
        val draft: Map<String, String>,
        /** Something of this form's is on the wire: a save, a water, a
         * delete, or the wizard arming a board. Every one of them greys the
         * form and stops it being left, so they are one flag. */
        val busy: Boolean = false,
        val refused: String? = null,
        val note: String? = null,
        /** The stored sensor's curve over `window`; stays up when a reload
         * fails. */
        val history: History? = null,
        val historyWhy: String? = null,
        val window: ChartWindow = ChartWindow.DAY,
        /** The water command this form queued, followed until its fate is known. */
        val watering: QueuedDose? = null,
        val waterRefused: String? = null,
        /** The last species lookup made from this form. Stored nowhere: the
         * pot keeps the name in its draft, and the garden carries the
         * cached care beside it afterwards. */
        val lookup: SpeciesAnswer? = null,
        val lookingUp: Boolean = false,
        /** This pot's photographs, newest first as the wire sends them; the
         * strip turns them round. Null means not asked for yet. */
        val photos: List<Photo>? = null,
        val photosWhy: String? = null,
        val uploading: Boolean = false,
        /** The photograph shown full size over the form, by id. */
        val viewing: String? = null,
        /** The field whose ⓘ is open, by wire key. */
        val explaining: String? = null,
    ) : Screen

    data class Calibrate(val parent: Pot, val cal: CalState) : Screen

    /** The address and the token: on first start, and from the garden's
     * settings after that. `first` is true when there is nothing behind
     * this screen — no garden to go back to, and Back exits the app, which
     * is what Back on the first screen of an app does. */
    data class Setup(
        val url: String,
        val token: String,
        val first: Boolean,
        val checking: Boolean = false,
        val why: String? = null,
        /** The token is dots until this says otherwise. */
        val show: Boolean = false,
    ) : Screen {
        /** Never the token: a data class's generated toString is the
         * shortest path from a secret to a log line or a crash report. */
        override fun toString(): String =
            "Setup(url=$url, first=$first, checking=$checking, why=$why)"
    }

    /** The watering history, over the form it was opened from — `parent`
     * null means it was opened from the list and covers the whole garden.
     * `nowS` is the server's own clock from the answer, so "3h ago" is not
     * the phone's opinion of a backend timestamp. */
    data class Doses(
        val parent: Pot?,
        val potId: String?,
        val title: String,
        val doses: List<Dose>? = null,
        val nowS: Long = 0,
        val loading: Boolean = true,
        val why: String? = null,
        /** A full page came back, so there may be another behind it. The
         * table is never pruned; this is how the older rows are reachable. */
        val more: Boolean = false,
        val loadingMore: Boolean = false,
    ) : Screen
}
