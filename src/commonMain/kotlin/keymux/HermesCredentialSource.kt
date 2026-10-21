package keymux

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.yaml.YamlParser
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxKeyEntry
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public Hermes facts; neither projection contains credential material. */
data class HermesProviderInfo(val provider: String, val baseUrl: String?, val credentials: Int, val available: Int)

data class HermesModelConfig(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val apiMode: String = "chat_completions",
    val contextWindow: Int = 0,
)

/** A single resolved pool row. Deliberately has no generated secret-bearing toString. */
internal class HermesCredential(val keyId: String, val key: String, val baseUrl: String?)

internal class HermesCredentialSnapshot(
    val credentials: Map<String, HermesCredential>,
    override val name: String,
    val exhausted: Set<String> = emptySet(),
) : KeySource() {
    override suspend fun read(path: KeyPath): String? {
        if (path.size != 3 || path[0] != "llm") return null
        check(path[2] != "key" || path[1] !in exhausted) { "Hermes provider quota exhausted: ${path[1]}" }
        val entry = credentials[path[1]] ?: return null
        return when (path[2]) {
            "key" -> entry.key
            "base_url" -> entry.baseUrl
            else -> null
        }
    }
    override suspend fun write(path: KeyPath, value: String): Unit =
        throw UnsupportedOperationException("Hermes snapshot is read-only")
}

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
    private val hermesHome: String = defaultHermesHome(),
    private val explicitFileOps: FileOperations? = null,
    /** Test seam: pin env lookup instead of the real process env. */
    private val getenv: (String) -> String? = { SystemOperations.default.getenv(it) },
) : KeySource() {
    override val name = "hermes-credential-pool"

    private val cooldownLock = Mutex()
    private val cooldowns = mutableMapOf<String, Long>()

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
        return when (field) {
            "key" -> bestEntry(provider)?.let { keyFor(it) }
            "base_url" -> {
                val entries = loadPool()[provider].orEmpty()
                val entry = bestEntry(provider, entries)
                    ?: entries.filterNot { str(it["last_status"]) == "dead" }.minByOrNull { int(it["priority"]) }
                entry?.let { baseUrl(it) }
            }
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
        if (source == "model_config" || source?.startsWith("config:") == true) {
            val endpoint = baseUrl(entry)?.trimEnd('/')
            val root = loadConfigRoot()
            val candidates = mutableListOf<Map<*, *>>()
            (root?.get("model") as? Map<*, *>)?.let { candidates += it }
            (root?.get("custom_providers") as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { candidates += it }
            for (config in candidates) {
                if (str(config["base_url"])?.trimEnd('/') != endpoint) continue
                val key = str(config["api_key"])?.let { expandEnv(it) }
                    ?: str(config["key_env"])?.let { envOrDotenv(it) }
                if (key != null) return key
            }
            val host = endpoint?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')
            if (!host.isNullOrBlank()) {
                val variable = "HERMES_CUSTOM_" + host.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_API_KEY"
                envOrDotenv(variable)?.let { return it }
            }
        }
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val expiry = long(entry["expires_at_ms"]).takeIf { it > 0L } ?: epochMs(entry["expires_at"])
        if (expiry != null && expiry <= now) return null // Hermes owns OAuth refresh.
        return str(entry["access_token"])?.takeIf { it.isNotBlank() }
    }

    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("hermes credential pool is read-only from this source")
    }

    override suspend fun invalidate() {} // Files are read on each resolution; local backoff survives refresh.

    internal suspend fun snapshot(): HermesCredentialSnapshot {
        val resolved = linkedMapOf<String, HermesCredential>()
        val exhausted = mutableSetOf<String>()
        for ((provider, entries) in loadPool()) {
            val entry = bestEntry(provider, entries)
            if (entry == null) {
                if (entries.any { keyFor(it) != null }) exhausted += provider
                continue
            }
            val key = keyFor(entry) ?: continue
            resolved[provider] = HermesCredential(keyId(provider, entry), key, baseUrl(entry))
        }
        return HermesCredentialSnapshot(resolved, name, exhausted)
    }

    /** A failed call excludes its actual pool row until the provider's reset. */
    suspend fun exhaust(keyId: String, resetAtMs: Long): Boolean {
        if (loadPool().none { (provider, rows) -> rows.any { keyId(provider, it) == keyId } }) return false
        cooldownLock.withLock { cooldowns[keyId] = resetAtMs }
        return true
    }

    /** Resolve an already selected pool identity without choosing another row. */
    internal suspend fun credential(keyId: String): String? {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        for ((provider, rows) in loadPool()) {
            val row = rows.firstOrNull { keyId(provider, it) == keyId } ?: continue
            return if (isUsableNow(provider, row, now, rows.size == 1)) keyFor(row) else null
        }
        return null
    }

    suspend fun providers(): Series<HermesProviderInfo> {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val out = mutableListOf<HermesProviderInfo>()
        for ((provider, rows) in loadPool()) {
            val available = rows.count { isUsableNow(provider, it, now, rows.size == 1) && keyFor(it) != null }
            out += HermesProviderInfo(provider, bestEntry(provider, rows)?.let { baseUrl(it) }
                ?: rows.firstNotNullOfOrNull { baseUrl(it) }, rows.size, available)
        }
        return out.toSeries()
    }

    /** Hermes' selected model, read through the same commonMain file boundary as its pool. */
    suspend fun modelConfig(): HermesModelConfig? {
        val config = loadConfig() ?: return null
        val model = str(config["default"]) ?: str(config["model"]) ?: return null
        val provider = str(config["provider"])?.takeUnless { it == "auto" } ?: return null
        val base = str(config["base_url"])?.let { expandEnv(it) }
            ?: read("llm.$provider.base_url".toKeyPath())
            ?: HarnessRegistry.byId(provider)?.defaultBaseUrl ?: ""
        return HermesModelConfig(provider, model, base, str(config["api_mode"]) ?:
            if (provider == "anthropic") "anthropic_messages" else "chat_completions",
            int(config["context_length"]).takeIf { it > 0 } ?: int(config["context_window"]))
    }

    /** Selected model followed by Hermes' configured custom endpoints. Unsupported modes remain explicit. */
    suspend fun configuredModels(): Series<HermesModelConfig> {
        val out = mutableListOf<HermesModelConfig>()
        modelConfig()?.let { out += it }
        val pool = loadPool()
        val configured = loadConfigRoot()?.get("custom_providers") as? List<*> ?: return out.toSeries()
        for (item in configured) {
            val config = item as? Map<*, *> ?: continue
            val name = str(config["name"]) ?: continue
            val base = str(config["base_url"])?.let { expandEnv(it) } ?: continue
            val selected = str(config["model"]) ?: str(config["default"])
            val models = config["models"] as? Map<*, *>
            val names = (listOfNotNull(selected) + models?.keys.orEmpty().mapNotNull(::str)).distinct()
            val provider = pool.entries.firstOrNull { (_, rows) -> rows.any { baseUrl(it)?.trimEnd('/') == base.trimEnd('/') } }?.key
                ?: "custom:${name.trim().lowercase().replace(' ', '-')}"
            for (model in names) {
                val metadata = models?.get(model) as? Map<*, *>
                val window = int(metadata?.get("context_length")).takeIf { it > 0 }
                    ?: int(metadata?.get("context_window")).takeIf { it > 0 }
                    ?: int(config["context_length"]).takeIf { it > 0 } ?: int(config["context_window"])
                val row = HermesModelConfig(provider, model, base, str(config["api_mode"]) ?: "chat_completions", window)
                if (out.none { it.provider == provider && it.model == model }) out += row
            }
        }
        return out.toSeries()
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
            for (entry in entries) out.add(toMuxKeyEntry(provider, entry, entries.size == 1))
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
        return bestEntry(provider, loadPool()[provider].orEmpty())
    }

    private suspend fun bestEntry(provider: String, entries: List<Map<String, Any?>>): Map<String, Any?>? {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val usable = entries.filter { isUsableNow(provider, it, now, entries.size == 1) && keyFor(it) != null }
        return usable.minByOrNull { int(it["priority"]) }
    }

    private suspend fun isUsableNow(provider: String, entry: Map<String, Any?>, nowMs: Long, soleCredential: Boolean): Boolean {
        if (str(entry["last_status"]) == "dead") return false
        return nowMs >= resetAtMs(provider, entry, soleCredential)
    }

    private suspend fun resetAtMs(provider: String, entry: Map<String, Any?>, soleCredential: Boolean): Long {
        val local = cooldownLock.withLock { cooldowns[keyId(provider, entry)] ?: 0L }
        if (str(entry["last_status"]) != "exhausted") return local
        // Exhausted is only a hard block until its own cooldown clears — Hermes
        // clears exhaustion on the same wall-clock signal (last_error_reset_at).
        val reported = epochMs(entry["last_error_reset_at"]) ?: run {
            // Match Hermes' bounded cooldown when the provider supplied no reset.
            // A status without a timestamp cannot establish a current denial.
            val statusAt = epochMs(entry["last_status_at"]) ?: return local
            val code = int(entry["last_error_code"])
            val reason = str(entry["failure_reason"]) ?: str((entry["extra"] as? Map<*, *>)?.get("failure_reason"))
            statusAt + when {
                code == 401 -> 300_000L
                reason == "billing_unverified" && code != 402 -> 60_000L
                soleCredential && code != 402 && reason != "billing" -> 60_000L
                else -> 3_600_000L
            }
        }
        return maxOf(local, reported)
    }

    private suspend fun toMuxKeyEntry(provider: String, entry: Map<String, Any?>, soleCredential: Boolean): MuxKeyEntry {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val status = when {
            str(entry["last_status"]) == "dead" || keyFor(entry) == null -> MuxKeyStatus.BENCHED
            !isUsableNow(provider, entry, now, soleCredential) -> MuxKeyStatus.BACKOFF
            else -> MuxKeyStatus.ACTIVE
        }
        return MuxKeyEntry(
            keyId = keyId(provider, entry),
            provider = provider,
            label = str(entry["label"]) ?: provider,
            modelUrl = baseUrl(entry) ?: "",
            lastModel = null,
            lastUsedMs = epochMs(entry["last_status_at"]) ?: 0L,
            accessCount = long(entry["request_count"]),
            status = status,
            leasedTo = null,
            leaseExpiresAt = resetAtMs(provider, entry, soleCredential),
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
                    out[provider] = (existing + entries).distinctBy { keyId(provider, it) }
                } else {
                    out[provider] = entries
                }
            }
        }
        val config = loadConfig()
        val provider = config?.let { str(it["provider"]) }?.takeUnless { it == "auto" }
        if (provider != null && config != null && out[provider].isNullOrEmpty()) {
            val key = str(config["api_key"])?.let { expandEnv(it) }
                ?: str(config["key_env"])?.let { envOrDotenv(it) }
            if (!key.isNullOrBlank()) out[provider] = listOf(mapOf(
                "provider" to provider, "id" to "config", "access_token" to key,
                "base_url" to str(config["base_url"])?.let { expandEnv(it) },
            ))
        }
        return out
    }

    private suspend fun loadConfig(): Map<*, *>? {
        return loadConfigRoot()?.get("model") as? Map<*, *>
    }

    private suspend fun loadConfigRoot(): Map<*, *>? {
        val ops = fileOps()
        val file = ops.resolvePath(hermesHome, "config.yaml")
        if (!ops.isFile(file)) return null
        return YamlParser.reify(ops.readString(file)) as? Map<*, *>
    }

    private suspend fun expandEnv(value: String): String? {
        var result = value
        for (match in Regex("""\$\{([^}]+)}""").findAll(value)) {
            val name = match.groupValues[1].removePrefix("env:").trim()
            result = result.replace(match.value, envOrDotenv(name) ?: return null)
        }
        return result.takeIf { it.isNotBlank() }
    }

    private fun keyId(provider: String, entry: Map<String, Any?>): String =
        "$provider:${str(entry["id"]) ?: str(entry["source"]) ?: "default"}"

    private fun baseUrl(entry: Map<String, Any?>): String? =
        (if (str(entry["provider"]) == "nous") str(entry["inference_base_url"]) else null)
            ?: str(entry["base_url"]) ?: str(entry["inference_base_url"])

    private suspend fun loadPoolFile(ops: FileOperations, file: String): Map<String, List<Map<String, Any?>>>? {
        if (!ops.exists(file)) return null
        val parsed = runCatching { reify(ops.readString(file)) }.getOrNull() as? Map<*, *>
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
        val out = mutableListOf(ops.resolvePath(hermesHome, ".env"))
        val home = getenv("HOME") ?: return out.toSeries()
        val defaultHermes = "$home/.hermes"
        val hh = getenv("HERMES_HOME")
        if (hh != null && "$hh/.env" !in out) out += "$hh/.env"
        if ("$defaultHermes/.env" !in out) out += "$defaultHermes/.env"
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
    private fun epochMs(v: Any?): Long? {
        val numeric = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
        if (numeric != null) return numeric.takeIf { it > 0 && it.isFinite() }
            ?.let { (if (it > 1_000_000_000_000.0) it else it * 1000.0).toLong() }
        return (v as? String)?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
    }
}

/** `KeyMux { hermes() }` — bind Hermes' credential pool under `llm.*.*`.
 *  [fileOps] explicit: coroutine contexts don't reliably carry FileOperations
 *  (the daemon's KeyMux reads from non-coroutine boot paths), so the daemon
 *  passes its JvmFileOperations — without it the pool silently degrades to
 *  nothing. */
fun KeyMuxBuilder.hermes(
    hermesHome: String = defaultHermesHome(),
    fileOps: FileOperations? = null,
): KeyMuxBuilder =
    bind("llm.*.*", HermesCredentialSource(hermesHome, fileOps))
