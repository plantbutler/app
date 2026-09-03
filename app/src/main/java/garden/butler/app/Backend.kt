package garden.butler.app

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the backend's GET /pots and GET /health answer, no more.
 *
 * Unknown keys are ignored on purpose, mirroring the wire rule the backend
 * itself follows: either side must be allowed to grow fields first. Every
 * field the app may not find (a backend older than the app) defaults.
 */
@Serializable
data class Proposal(
    val id: Long,
    val ml: Int? = null,
    @SerialName("cap_s") val capS: Int? = null,
    @SerialName("created_ts") val createdTs: Long? = null,
)

/** The newest dose handed to the board on this pot's hose, with the human
 * verdict so far. `state` is sent | acked | expired; `verdict` is
 * ok | too_much | too_little or null while unjudged. */
@Serializable
data class LastDose(
    val id: Long,
    val ml: Int? = null,
    @SerialName("cap_s") val capS: Int? = null,
    @SerialName("flow_ml") val flowMl: Int? = null,
    val state: String = "sent",
    val source: String? = null,
    @SerialName("sent_ts") val sentTs: Long? = null,
    @SerialName("acked_ts") val ackedTs: Long? = null,
    val verdict: String? = null,
)

@Serializable
data class Pot(
    /** The backend's own key, `pot-3f9a21`. Defaults to empty so a backend
     * older than the app still decodes; nothing may use "" as a key. */
    val id: String = "",
    /** A nickname, editable, unique among pots — not the identity. */
    val name: String,
    val species: String? = null,
    val controller: String? = null,
    val channel: Int? = null,
    val outlet: Int? = null,
    @SerialName("plant_type") val plantType: String? = null,
    @SerialName("plant_size") val plantSize: String? = null,
    @SerialName("pot_size") val potSize: String? = null,
    val soil: String? = null,
    @SerialName("dry_raw") val dryRaw: Long? = null,
    @SerialName("wet_raw") val wetRaw: Long? = null,
    @SerialName("target_low_pct") val targetLowPct: Int? = null,
    @SerialName("target_high_pct") val targetHighPct: Int? = null,
    @SerialName("dose_ml") val doseMl: Int? = null,
    val mode: String = "manual",
    @SerialName("cooldown_h") val cooldownH: Int? = null,
    @SerialName("daily_cap_ml") val dailyCapMl: Int? = null,
    val enabled: Int = 1,
    val raw: Long? = null,
    val pct: Int? = null,
    @SerialName("read_ts") val readTs: Long? = null,
    val proposal: Proposal? = null,
    @SerialName("last_dose") val lastDose: LastDose? = null,
)

/** One row of the watering history: what was asked, what the meter
 * counted, how it ended, and whose it was. `pot` is null for a dose no
 * mapping window claims — handed out on a hose no pot held, or never
 * handed out at all. */
@Serializable
data class Dose(
    val id: Long,
    val kind: String = "water",
    val ml: Int? = null,
    @SerialName("cap_s") val capS: Int? = null,
    @SerialName("flow_ml") val flowMl: Int? = null,
    val state: String = "sent",
    val source: String? = null,
    @SerialName("created_ts") val createdTs: Long? = null,
    @SerialName("sent_ts") val sentTs: Long? = null,
    @SerialName("acked_ts") val ackedTs: Long? = null,
    val verdict: String? = null,
    val pot: String? = null,
    @SerialName("pot_name") val potName: String? = null,
)

@Serializable
data class DosesAnswer(
    val doses: List<Dose> = emptyList(),
    /** The server's clock when it answered, so "3h ago" is not the phone's
     * opinion of a backend timestamp. */
    val now: Long = 0,
)

@Serializable
data class PotsAnswer(
    val pots: List<Pot> = emptyList(),
)

/** The one command in flight on a controller: queued or sent. */
@Serializable
data class InFlight(
    val id: Long,
    val kind: String = "water",
    val state: String = "queued",
)

@Serializable
data class ControllerHealth(
    val controller: String,
    @SerialName("last_seen") val lastSeen: Long = 0,
    @SerialName("next_s") val nextS: Int? = null,
    val float: Int? = null,
    val pos: String? = null,
    val command: InFlight? = null,
)

@Serializable
data class RaisedAlert(
    val key: String,
    @SerialName("raised_ts") val raisedTs: Long = 0,
)

@Serializable
data class Health(
    val ok: Boolean = false,
    val readings: Long = 0,
    @SerialName("last_ts") val lastTs: Long? = null,
    /** BUTLER_NEXT_S on the backend; 60 only until a backend that says so. */
    @SerialName("next_default") val nextDefault: Int = 60,
    val controllers: List<ControllerHealth> = emptyList(),
    val alerts: List<RaisedAlert> = emptyList(),
)

/** One bucket of GET /history: the mean raw count over `bucket_s` seconds
 * from `ts`, its extremes, and how many readings went in. Raw only: the
 * app derives % from the pot's current calibration, so a recalibration
 * re-reads the whole curve. */
@Serializable
data class HistoryPoint(
    val ts: Long,
    val raw: Long,
    val lo: Long? = null,
    val hi: Long? = null,
    val n: Int = 1,
)

@Serializable
data class History(
    val controller: String = "",
    val channel: Int = 0,
    val since: Long = 0,
    /** The server's clock when it answered: the chart's right edge. */
    val to: Long = 0,
    @SerialName("bucket_s") val bucketS: Int = 300,
    val points: List<HistoryPoint> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

fun parseHistory(body: String): History = json.decodeFromString(body)

/** `cmd=17` as POST /command answers it; null when it is not that. */
fun parseCmdAnswer(answer: String): Long? =
    answer.trim().removePrefix("cmd=").takeIf { it != answer.trim() }?.toLongOrNull()

fun parsePots(body: String): List<Pot> = json.decodeFromString<PotsAnswer>(body).pots

fun parseDoses(body: String): DosesAnswer = json.decodeFromString(body)

fun parseHealth(body: String): Health = json.decodeFromString(body)

/** The backend said no, in its own words: "refused: …", "busy: cmd=3
 * state=sent", "try again: …", "bad token". Shown verbatim; the backend's
 * merged-row checks are the validation. */
class Refused(val code: Int, val text: String) : Exception(text)

/** `next=120` as POST /interval answers it; null when it is not that. */
fun parseNextAnswer(answer: String): Int? =
    answer.trim().removePrefix("next=").takeIf { it != answer.trim() }?.toIntOrNull()

/** The one place that touches the network. Blocking calls: the view model
 * runs them on Dispatchers.IO. */
class Backend(private val baseUrl: String, private val token: String = "") {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private fun get(path: String): String {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).build()
        return answerOf(path, request)
    }

    /** A k=v body with the token. */
    private fun post(path: String, body: String): String {
        val request =
            Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("X-Token", token)
                .post(body.toRequestBody(TEXT))
                .build()
        return answerOf(path, request)
    }

    /** Any non-200 becomes a Refused carrying the backend's text verbatim
     * (trimmed of its trailing newline): a "try again: …" on GET /pots reads
     * the same on the phone as one on POST. */
    private fun answerOf(path: String, request: Request): String {
        client.newCall(request).execute().use { answer ->
            val text = answer.body?.string().orEmpty().trim()
            if (answer.code != 200) {
                throw Refused(answer.code, text.ifEmpty { "$path answered ${answer.code}" })
            }
            return text
        }
    }

    fun pots(): List<Pot> = parsePots(get("/pots"))

    fun health(): Health = parseHealth(get("/health"))

    /** `id=<pot> name=<nickname> k=v…`, built by potBody(); answers
     * `pot=<id> name=<name>`, which nothing reads: the refresh after it is
     * what the screen believes. */
    fun postPot(body: String): String = post("/pot", body)

    fun approve(cmdId: Long): String = post("/approve", "cmd=$cmdId")

    fun verdict(cmdId: Long, verdict: String): String =
        post("/verdict", "cmd=$cmdId verdict=$verdict")

    /** The watering history, newest first: one pot's, or the whole
     * garden's when `potId` is null. */
    fun doses(potId: String?, limit: Int, after: Cursor? = null): DosesAnswer {
        val query =
            buildList {
                potId?.let { add("pot=" + URLEncoder.encode(it, "UTF-8")) }
                add("limit=$limit")
                // Both halves, or the backend refuses: doses share a second,
                // and a timestamp alone would skip or repeat them.
                after?.let {
                    add("before=${it.ts}")
                    add("before_id=${it.id}")
                }
            }.joinToString("&")
        return parseDoses(get("/doses?$query"))
    }

    /** Bucketed raw counts for one sensor over the last `hours`. The name is
     * a single token on the wire but not necessarily a URL-safe one. */
    fun history(controller: String, channel: Int, hours: Int, bucketS: Int): History {
        val c = URLEncoder.encode(controller, "UTF-8")
        return parseHistory(get("/history?c=$c&ch=$channel&hours=$hours&bucket_s=$bucketS"))
    }

    /** Queues one dose through the hand-off; the backend sizes the cap
     * from the dose itself. Answers the command id, or throws Refused —
     * "busy: cmd=N state=S" while the controller's one slot is taken. */
    fun water(controller: String, outlet: Int, ml: Int): Long? =
        parseCmdAnswer(post("/command", "c=$controller water=$outlet ml=$ml"))

    /** Sets (or with 0 clears) the controller's report interval; returns the
     * effective interval the backend answered with. */
    fun interval(controller: String, nextS: Int): Int? =
        parseNextAnswer(post("/interval", "c=$controller next=$nextS"))

    private companion object {
        val TEXT = "text/plain; charset=utf-8".toMediaType()
    }
}
