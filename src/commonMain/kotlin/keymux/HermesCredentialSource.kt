package keymux

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxKeyEntry
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import kotlinx.coroutines.currentCoroutineContext

/**
 * HermesCredentialSource — resolves `llm.<provider>.key` / `llm.<provider>.base_url`
 * against Hermes' own credential pool at `<hermesHome>/auth.json`.
 *
 * Hermes' pool is keyed by PROVIDER (`credential_pool.<provider>[]`, each entry a
 * `PooledCredential` — provider, id, label, priority, status, access_token,
 * base_url, request_count, cooldown fields), not by model. [ModelMux] resolves
 * keys by MODEL id (`llm.${'$'}{card.id}.key`) — [modelmux.acp.AcpModelCard.providerTag]
 * is the bridge: a model card tagged with its provider makes `ModelMux.session()`
 * try `llm.<provider>.key` first, landing here.
 *
 * **Env indirection** — Hermes externalizes most pool secrets: an entry's
 * `source` field is either `"env:<VAR>"` (the credential lives in the process
 * env or a hermes `.env` file) or an inline mechanism (`device_code`,
 * `manual`, …) with the token in `access_token`. This source follows the same
 * indirection: `env:<VAR>` answers from the process env first, then the hermes
 * dotenv chain ($HERMES_HOME/.env, ~/.hermes/.env, ~/.hermes/profiles/<name>/.env) —
 * so a key the operator already gave Hermes answers here with no second copy,
 * which is the whole point of borrowing hermes' key access for proto testing.
 * A pool entry whose key cannot be resolved does not win — selection falls
 * through to the next-priority entry that can.
 *
 * **Both pools** — the active profile's pool (`<hermesHome>/auth.json`) and the
 * default pool (`~/.hermes/auth.json`) are merged, profile first (its entries
 * win per provider; a provider present in both carries rotation depth). Each
 * carries a different provider set; merging is what makes "everything hermes
 * can reach" one source.
 *
 * Follows [PersistSource]'s pattern — file IO through the [FileOperations] SPI
 * from the coroutine context, so this stays a legal commonMain [KeySource]
 * subclass (a jvmMain subclass of a commonMain `sealed class` does not compile:
 * "Extending sealed classes or interfaces from a different module is prohibited").
 *
 * Read-only: Hermes owns the pool file (auth flows, rotation, cooldown timers all
 * live in the Python side). This source never writes.
 */
class HermesCredentialSource(
    private val hermesHome: String = "~/.hermes",
    private val explicitFileOps: FileOperations? = null,
    /** Test seam: pin env lookup instead of the real process env. */
    private val getenv: (String) -> String? = { SystemOperations.default.getenv(it) },
) : KeySource() {
    override val name = "hermes-credential-pool"

    private var cachedPool: Map<String, List<Map<String, Any?>>>? = null
    private val dotenvMemo = mutableMapOf<String, Map<String, String>>()

    private suspend fun fileOps(): FileOperations =
        explicitFileOps
            ?: currentCoroutineContext()[FileOperations.Key]
            ?: error("No FileOperations found in coroutine context for HermesCredentialSource")

    private suspend fun fileOpsOrNull(): FileOperations? =
        explicitFileOps ?: currentCoroutineContext()[FileOperations.Key]

    override suspend fun read(path: KeyPath): String? {
        if (path.size != 3 || path[0] != "llm") return null
        val provider = path[1]
        val field = path[2]
        // base_url and key resolve INDEPENDENTLY: an entry that cannot answer
        // a key (env:VAR nobody set) still carries the provider's base_url —
        // the key just falls through the KeyMux chain, the endpoint does not.
        val entry = bestEntry(provider) ?: return null
        return when (field) {
            "key" -> keyFor(entry)
            "base_url" -> str(entry["base_url"]) ?: str(entry["inference_base_url"])
            else -> null
        }
    }

    /**
     * The key an entry actually answers with: `env:<VAR>` follows the hermes
     * indirection (process env, then the dotenv chain); anything else is the
     * inline `access_token`. Null when neither yields a value.
     */
    private suspend fun keyFor(entry: Map<String, Any?>): String? {
        val source = str(entry["source"])
        if (source != null && source.startsWith("env:")) {
            return envOrDotenv(source.substring("env:".length))
        }
        return str(entry["access_token"])?.takeIf { it.isNotBlank() }
    }

    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("hermes credential pool is read-only from this source")
    }

    override suspend fun invalidate() {
        cachedPool = null
        dotenvMemo.clear()
    }

    /**
     * Quota-facing projection: every pool entry across every provider, as
     * [MuxKeyEntry] rows for [borg.trikeshed.userspace.reactor.MuxReactorElement]
     * / [modelmux.QuotaLegion]. Unlike [read], this surfaces the whole pool —
     * QuotaLegion needs every key's standing, not just the one that would win.
     */
    suspend fun keyEntries(): Series<MuxKeyEntry> {
        val pool = loadPool()
        val out = ArrayList<MuxKeyEntry>()
        for ((provider, entries) in pool) {
            for (entry in entries) out.add(toMuxKeyEntry(provider, entry))
        }
        return out.size j { out[it] }
    }

    /**
     * The entry a call would actually use for [provider] right now, or null if
     * none is usable. "Usable" = not dead, not still cooling down, AND able to
     * answer a key (its `env:<VAR>` indirection resolves, or it carries an
     * inline token) — an unresolvable pool row must not win.
     */
    suspend fun bestEntry(provider: String): Map<String, Any?>? {
        val entries = loadPool()[provider] ?: return null
        val alive = entries.filterNot { str(it["last_status"]) == "dead" }
        if (alive.isEmpty()) return null
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val usable = alive.filter { isUsableNow(it, now) && keyFor(it) != null }
        return usable.minByOrNull { int(it["priority"]) }
            ?: alive.minByOrNull { int(it["priority"]) }
    }

    private fun isUsableNow(entry: Map<String, Any?>, nowMs: Long): Boolean {
        if (str(entry["last_status"]) != "exhausted") return true
        // Exhausted is only a hard block until its own cooldown clears — Hermes
        // clears exhaustion on the same wall-clock signal (last_error_reset_at).
        val resetAtMs = epochMs(entry["last_error_reset_at"]) ?: return false
        return nowMs >= resetAtMs
    }

    private fun toMuxKeyEntry(provider: String, entry: Map<String, Any?>): MuxKeyEntry {
        val status = when (str(entry["last_status"])) {
            "dead" -> MuxKeyStatus.BENCHED
            "exhausted" -> MuxKeyStatus.BACKOFF
            else -> MuxKeyStatus.ACTIVE
        }
        val id = str(entry["id"]) ?: "$provider:${entry.hashCode()}"
        return MuxKeyEntry(
            keyId = "$provider:$id",
            provider = provider,
            label = str(entry["label"]) ?: provider,
            modelUrl = str(entry["base_url"]) ?: str(entry["inference_base_url"]) ?: "",
            lastModel = null,
            lastUsedMs = epochMs(entry["last_status_at"]) ?: 0L,
            accessCount = long(entry["request_count"]),
            status = status,
            leasedTo = null,
            leaseExpiresAt = epochMs(entry["last_error_reset_at"]) ?: 0L,
        )
    }

    private suspend fun loadPool(): Map<String, List<Map<String, Any?>>> {
        // No memo: the pool is Hermes' live store (rotation, cooldowns, new
        // logins). auth.json is small; reading it per call is the price of
        // answering with the key Hermes would use right now.
        val ops = fileOps()
        // The ACTIVE profile's pool first (profile entries win per provider),
        // then the default ~/.hermes pool as a union — each carries a different
        // provider set (copilot/custom:* live only in the default pool today).
        val files = mutableListOf<String>()
        val active = ops.resolvePath(hermesHome, "auth.json")
        files += active
        val default = ops.resolvePath("~", ".hermes", "auth.json")
        if (default != active) files += default
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (file in files) {
            val pool = loadPoolFile(ops, file) ?: continue
            for ((provider, entries) in pool) {
                val existing = out[provider]
                if (existing != null) {
                    // Provider in both pools: profile rows first, default rows
                    // appended (a second pool entry = rotation depth, not a
                    // conflict).
                    out[provider] = existing + entries
                } else {
                    out[provider] = entries
                }
            }
        }
        return out
    }

    private suspend fun loadPoolFile(ops: FileOperations, file: String): Map<String, List<Map<String, Any?>>>? {
        if (!ops.exists(file)) return null
        val parsed = runCatching { JsonSupport.parse(ops.readString(file)) }.getOrNull() as? Map<*, *>
            ?: return null
        val pool = (parsed["credential_pool"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for ((k, v) in pool) {
            val provider = k as? String ?: continue
            val entries = (v as? List<*>)?.mapNotNull { row ->
                @Suppress("UNCHECKED_CAST")
                (row as? Map<*, *>)?.let { it as Map<String, Any?> }
            } ?: continue
            out[provider] = entries
        }
        return out
    }

    // ── env:<VAR> indirection (mirrors HarnessSource's dotenv chain) ──────────

    private suspend fun envOrDotenv(name: String): String? {
        getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
        val ops = fileOpsOrNull() ?: return null
        val files = dotenvFiles(ops)
        for (i in 0 until files.size) {
            dotenv(ops, files[i])?.get(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun dotenvFiles(ops: FileOperations): Series<String> {
        val home = getenv("HOME") ?: return emptySeriesOf()
        val defaultHermes = "$home/.hermes"
        val hh = getenv("HERMES_HOME")
        val out = mutableListOf<String>()
        if (hh != null) out += "$hh/.env"
        if (hh != defaultHermes) out += "$defaultHermes/.env"
        val profilesDir = "$defaultHermes/profiles"
        if (runCatching { ops.isDir(profilesDir) }.getOrDefault(false)) {
            for (p in runCatching { ops.listDir(profilesDir) }.getOrDefault(emptyList()).sorted()) {
                val f = "$profilesDir/$p/.env"
                if (f !in out) out += f
            }
        }
        return out.toSeries()
    }

    private suspend fun dotenv(ops: FileOperations, file: String): Map<String, String>? {
        if (!runCatching { ops.isFile(file) }.getOrDefault(false)) return null
        return parseDotenv(runCatching { ops.readString(file) }.getOrNull() ?: return null)
    }

    // ── boundary coercions — JSON minting is never trusted to be one type ──

    private fun str(v: Any?): String? = v as? String

    private fun int(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun long(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    /**
     * Hermes stores status timestamps as epoch SECONDS (Python `time.time()`),
     * float-precision. `null` means the field was absent, distinct from `0`.
     */
    private fun epochMs(v: Any?): Long? = when (v) {
        is Number -> (v.toDouble() * 1000.0).toLong()
        is String -> v.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        else -> null
    }
}

/** `KeyMux { hermes() }` — bind Hermes' credential pool under `llm.*.*`.
 *  [fileOps] explicit: coroutine contexts don't reliably carry FileOperations
 *  (the daemon's KeyMux reads from non-coroutine boot paths), so the daemon
 *  passes its JvmFileOperations — without it the pool silently degrades to
 *  nothing. */
fun KeyMuxBuilder.hermes(
    hermesHome: String = "~/.hermes",
    fileOps: FileOperations? = null,
): KeyMuxBuilder =
    bind("llm.*.*", HermesCredentialSource(hermesHome, fileOps))
