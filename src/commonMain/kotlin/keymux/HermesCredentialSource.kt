package keymux

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.FileOperations
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
) : KeySource() {
    override val name = "hermes-credential-pool"

    private var cachedPool: Map<String, List<Map<String, Any?>>>? = null

    private suspend fun fileOps(): FileOperations =
        explicitFileOps
            ?: currentCoroutineContext()[FileOperations.Key]
            ?: error("No FileOperations found in coroutine context for HermesCredentialSource")

    override suspend fun read(path: KeyPath): String? {
        if (path.size != 3 || path[0] != "llm") return null
        val provider = path[1]
        val field = path[2]
        val entry = bestEntry(provider) ?: return null
        return when (field) {
            "key" -> str(entry["access_token"])
            "base_url" -> str(entry["base_url"]) ?: str(entry["inference_base_url"])
            else -> null
        }
    }

    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("hermes credential pool is read-only from this source")
    }

    override suspend fun invalidate() {
        cachedPool = null
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

    /** The entry a call would actually use for [provider] right now, or null if none is usable. */
    suspend fun bestEntry(provider: String): Map<String, Any?>? {
        val entries = loadPool()[provider] ?: return null
        val alive = entries.filterNot { str(it["last_status"]) == "dead" }
        if (alive.isEmpty()) return null
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        return alive.filter { isUsableNow(it, now) }.minByOrNull { int(it["priority"]) }
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
        cachedPool?.let { return it }
        val ops = fileOps()
        val file = ops.resolvePath(hermesHome, "auth.json")
        if (!ops.exists(file)) return emptyMap<String, List<Map<String, Any?>>>().also { cachedPool = it }
        val parsed = runCatching { JsonSupport.parse(ops.readString(file)) }.getOrNull() as? Map<*, *>
            ?: return emptyMap<String, List<Map<String, Any?>>>().also { cachedPool = it }
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
        return out.also { cachedPool = it }
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

/** `KeyMux { hermes() }` — bind Hermes' credential pool under `llm.*.*`. */
fun KeyMuxBuilder.hermes(hermesHome: String = "~/.hermes"): KeyMuxBuilder =
    bind("llm.*.*", HermesCredentialSource(hermesHome))
