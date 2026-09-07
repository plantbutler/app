// The address and the token: what makes each wrong, and where they are kept.
package garden.butler.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the butler is and what it accepts. Asked for on first start and
 * kept on the device rather than compiled in, so one APK installs on a
 * second phone, a moved NAS is a typed line rather than a rebuild, and the
 * artifact carries no token.
 */
data class ButlerConfig(val url: String, val token: String) {
    /** Half a config is no config: an address with no token starts the app
     * into a garden that refuses every write, which reads as a broken
     * butler rather than as a question nobody finished answering. */
    val complete: Boolean
        get() = url.isNotEmpty() && token.isNotEmpty()

    /** Never the token: a data class's generated toString is the shortest
     * path from a secret to a crash report. */
    override fun toString(): String = "ButlerConfig(url=$url, token=***)"
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

/** What somebody typed, as an address. A missing scheme becomes http and
 * never https: the backend is plain HTTP on the LAN and on the tailnet, so
 * cleartext has to keep working. Typing https is allowed; being made to is
 * not.
 */
fun normaliseUrl(typed: String): String {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return ""
    val full = if (SCHEME.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    return full.trimEnd('/')
}

/** The address without its scheme: what to put in a sentence about it. */
fun hostOf(url: String): String = url.substringAfter("://").trimEnd('/').ifEmpty { url }

/** Why that is not an address, or null. */
fun urlProblem(typed: String): String? {
    val url = normaliseUrl(typed)
    if (url.isEmpty()) {
        return "type the butler's address — the NAS on the tailnet, like 100.x.y.z:9380"
    }
    val scheme = SCHEME.find(url)?.value.orEmpty().dropLast(3).lowercase()
    if (scheme != "http" && scheme != "https") {
        return "$scheme:// is not something this app speaks — http:// or https://"
    }
    // OkHttp's own parser, because it is the one that will have to dial it:
    // an address this app accepts and then cannot use looks like the
    // butler's fault.
    val parsed =
        url.toHttpUrlOrNull()
            ?: return "that is not an address — it should look like 100.x.y.z:9380"
    // Every path is appended to this address, so a query or a fragment ends
    // up in front of the path rather than after it: http://x/?t=1 plus
    // /hello asks for / with a mangled query, succeeds as far as OkHttp is
    // concerned, and fails as "not your butler" with nothing pointing at
    // the real cause.
    if (parsed.encodedQuery != null || parsed.encodedFragment != null) {
        return "leave off anything after ? or # — this is where the butler is, not a link to a page"
    }
    return null
}

/** Why that is not a token, or null. The backend compares byte for byte, so
 * a stray space really is a wrong token — and saying so beats a 401 nobody
 * can account for. */
fun tokenProblem(typed: String): String? {
    val token = typed.trim()
    if (token.isEmpty()) return "type the butler's token too"
    if (token.any { it.isWhitespace() }) {
        return "that token has a space in it — check what was pasted"
    }
    // Anything outside printable ASCII cannot go in an HTTP header at all,
    // and OkHttp's refusal quotes the offending value back — which here
    // would put the token on the screen. The butler's token is hex, so
    // nothing real is turned away by this.
    if (token.any { it < ' ' || it > '~' }) {
        return "that token has a character an HTTP header cannot carry — a smart quote or " +
            "an accent, most likely; check what was pasted"
    }
    return null
}

/** What an address said when it was asked whether it is a butler. Four
 * answers rather than one failure: nothing listening there and a refused
 * token are different mistakes, and only one of them is fixed by retyping
 * the token.
 */
sealed interface Probe {
    data class Butler(val version: String) : Probe

    /** Nothing answered at all: the wrong address, or off the tailnet. */
    data class NoAnswer(val why: String) : Probe

    /** Something is listening there, and it is not this. */
    data class NotTheButler(val why: String) : Probe

    data object WrongToken : Probe
}

/** `GET /hello`, classified. Pure, so every branch has a test that does not
 * need a socket. */
fun classifyHello(code: Int, body: String): Probe {
    val text = body.trim()
    return when {
        code == 401 -> Probe.WrongToken
        // An old enough butler has no /hello and answers 404 here, which is
        // indistinguishable from another service on the port — so the
        // sentence has to own up to both.
        code == 404 ->
            Probe.NotTheButler(
                "it has no /hello — a butler older than 0.13.0, or another service on that port",
            )
        code != 200 -> Probe.NotTheButler("it answered $code: ${text.take(120).ifEmpty { "nothing" }}")
        text.startsWith("butler=") -> Probe.Butler(text.removePrefix("butler=").ifEmpty { "?" })
        else -> Probe.NotTheButler("it answered, but not like a butler")
    }
}

/** The sentence under the fields. */
fun probeLine(probe: Probe, host: String): String =
    when (probe) {
        is Probe.Butler -> "butler ${probe.version} at $host"
        Probe.WrongToken ->
            "$host is a butler and it refused that token. The address is right — the token is not."
        is Probe.NotTheButler -> "something answers at $host, but it is not your butler: ${probe.why}"
        is Probe.NoAnswer ->
            "nothing answered at $host (${probe.why}) — check the address, " +
                "and that this phone is on the tailnet"
    }

interface ConfigStore {
    fun read(): ButlerConfig?

    fun write(config: ButlerConfig)
}

/** The address and the token on this device. The encrypted store rather
 * than plain preferences, because plain preferences are a readable file to
 * anything with root or a backup of it, and the token is the one secret the
 * app holds. Nothing here ever logs or stringifies it.
 */
class EncryptedConfigStore(
    private val context: Context,
    private val name: String = "butler",
) : ConfigStore {
    private fun prefs() =
        EncryptedSharedPreferences.create(
            name,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override fun read(): ButlerConfig? =
        try {
            val stored = prefs()
            ButlerConfig(
                stored.getString("url", "").orEmpty(),
                stored.getString("token", "").orEmpty(),
            ).takeIf { it.complete }
        } catch (why: Exception) {
            // The keystore entry can go — a restore onto another device, a
            // wiped keystore — and then this file can never be decrypted
            // again. Throwing would be an app that never starts, so the
            // undecryptable store is dropped and the setup screen asks
            // again, which is the one thing the user can act on.
            forget()
            null
        }

    /** commit(), not apply(): the app repoints itself the moment this
     * returns, and a config still in a background queue would be lost to a
     * kill on the very launch that set it. */
    override fun write(config: ButlerConfig) {
        prefs().edit().putString("url", config.url).putString("token", config.token).commit()
    }

    private fun forget() {
        try {
            context.deleteSharedPreferences(name)
        } catch (why: Exception) {
            // Nothing left to try; read() answers null either way and the
            // setup screen is where that lands.
        }
    }
}
