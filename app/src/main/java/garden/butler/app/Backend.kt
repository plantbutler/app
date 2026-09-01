package garden.butler.app

import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the backend's GET /pots and GET /health answer, no more.
 *
 * Unknown keys are ignored on purpose, mirroring the wire rule the backend
 * itself follows: either side must be allowed to grow fields first.
 */
@Serializable
data class Proposal(
    val id: Long,
    val ml: Int? = null,
)

@Serializable
data class Pot(
    val name: String,
    val controller: String? = null,
    val channel: Int? = null,
    val outlet: Int? = null,
    val mode: String = "manual",
    val enabled: Int = 1,
    val raw: Long? = null,
    val pct: Int? = null,
    @SerialName("read_ts") val readTs: Long? = null,
    val proposal: Proposal? = null,
)

@Serializable
data class PotsAnswer(
    val pots: List<Pot> = emptyList(),
)

@Serializable
data class ControllerHealth(
    val controller: String,
    @SerialName("last_seen") val lastSeen: Long = 0,
    @SerialName("next_s") val nextS: Int? = null,
    val float: Int? = null,
    val pos: String? = null,
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
    val controllers: List<ControllerHealth> = emptyList(),
    val alerts: List<RaisedAlert> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

fun parsePots(body: String): List<Pot> = json.decodeFromString<PotsAnswer>(body).pots

fun parseHealth(body: String): Health = json.decodeFromString(body)

/** The one place that touches the network. Blocking calls: the view model
 * runs them on Dispatchers.IO. */
class Backend(private val baseUrl: String) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private fun get(path: String): String {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).build()
        client.newCall(request).execute().use { answer ->
            check(answer.code == 200) { "$path answered ${answer.code}" }
            return answer.body?.string() ?: error("$path answered an empty body")
        }
    }

    fun pots(): List<Pot> = parsePots(get("/pots"))

    fun health(): Health = parseHealth(get("/health"))
}
