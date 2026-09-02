package garden.butler.app

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
    val name: String,
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

private val json = Json { ignoreUnknownKeys = true }

fun parsePots(body: String): List<Pot> = json.decodeFromString<PotsAnswer>(body).pots

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

    /** `name=<pot> k=v…`, built by potBody(); answers `pot=<name>`. */
    fun postPot(body: String): String = post("/pot", body)

    fun approve(cmdId: Long): String = post("/approve", "cmd=$cmdId")

    fun verdict(cmdId: Long, verdict: String): String =
        post("/verdict", "cmd=$cmdId verdict=$verdict")

    /** Sets (or with 0 clears) the controller's report interval; returns the
     * effective interval the backend answered with. */
    fun interval(controller: String, nextS: Int): Int? =
        parseNextAnswer(post("/interval", "c=$controller next=$nextS"))

    private companion object {
        val TEXT = "text/plain; charset=utf-8".toMediaType()
    }
}
