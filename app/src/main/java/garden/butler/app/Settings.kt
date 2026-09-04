package garden.butler.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the butler is and what it accepts.
 *
 * Not a build constant since 2026-09-04: it is asked for on first start and
 * kept on the device, so one APK installs on a second phone, a moved NAS is
 * a typed line rather than a rebuild, and an artifact carries no token.
 */
data class ButlerConfig(val url: String, val token: String) {
    /** Half a config is no config: an address with no token would start the
     * app into a garden that refuses every write, which reads as the butler
     * being broken rather than as a question nobody finished answering. */
    val complete: Boolean
        get() = url.isNotEmpty() && token.isNotEmpty()

    /** Never the token. The generated toString of a data class is the
     * shortest path there is from a secret to a crash report. */
    override fun toString(): String = "ButlerConfig(url=$url, token=***)"
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

/** What somebody typed, as an address.
 *
 * A LAN or tailnet address is typed without a scheme far more often than
 * with one, and cleartext has to keep working — the laptop backend is plain
 * HTTP on a LAN address and the NAS is plain HTTP on the tailnet — so a
 * missing scheme becomes http and never https. Typing https is allowed;
 * being made to is not.
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
    // an address this app accepts and cannot then use is the worst of the
    // three answers, since it looks like the butler's fault.
    if (url.toHttpUrlOrNull() == null) {
        return "that is not an address — it should look like 100.x.y.z:9380"
    }
    return null
}

/** Why that is not a token, or null. The backend compares it byte for byte,
 * so a stray space really is a wrong token — and saying so beats a 401 the
 * user cannot account for. */
fun tokenProblem(typed: String): String? {
    val token = typed.trim()
    if (token.isEmpty()) return "type the butler's token too"
    if (token.any { it.isWhitespace() }) {
        return "that token has a space in it — check what was pasted"
    }
    return null
}

/** What an address said when it was asked whether it is a butler.
 *
 * Four answers rather than one failure. "Nothing is listening there" and
 * "that is not your token" are different mistakes, the user can only have
 * made one of them, and only one of them is fixed by retyping the token —
 * telling them apart in words is most of what this screen is for.
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
fun readHello(code: Int, body: String): Probe {
    val text = body.trim()
    return when {
        code == 401 -> Probe.WrongToken
        // The route arrived in backend 0.13.0. Before that a butler answers
        // 404 here, which is indistinguishable from another service on the
        // port — so the sentence has to own up to both.
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

/** The address and the token on this device.
 *
 * The encrypted store rather than plain preferences, because the token is a
 * secret at rest on a phone: plain preferences are a world-readable file to
 * anything with root or a backup of it, and this is the one secret the app
 * holds. Nothing here ever logs or stringifies the token.
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
            // again. Throwing would be an app that never starts; the
            // undecryptable store is dropped instead and the setup screen
            // asks again, which is the one thing the user can act on.
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
