package keymux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxKeyEntry
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.withLock

/** Lazy iterable — delegates to Series.view, no eager size-N List. */
fun <T> Series<T>.iterable(): Iterable<T> = view

// ═══════════════════════════════════════════
// Type algebra
// ═══════════════════════════════════════════

typealias KeyId = String

data class LeaseMetadata(val leasedTo: String, val leaseExpiresAt: Long)

/** Dotted-path key: "llm.openai.key" → ["llm","openai","key"] */
typealias KeyPath = Series<String>

/** A resolved value: the value + which source produced it */
typealias KeyResult = Join<String?, String>   // value j sourceName

/** A binding: path joined to a lazy source */
typealias KeyBinding = Join<KeyPath, KeySource>

/** The mux itself: ordered bindings + resolver strategy */
typealias KeyMuxCore = Join<Series<KeyBinding>, KeyResolver>

fun KeyPath.asString(): String = view.joinToString(".")
fun String.toKeyPath(): KeyPath = split(".").toSeries()

// ═══════════════════════════════════════════
// Source algebra — sealed, each wraps platform/reactor effects
// ═══════════════════════════════════════════

sealed class KeySource {
    abstract val name: String
    abstract suspend fun read(path: KeyPath): String?
    abstract suspend fun write(path: KeyPath, value: String)
    open suspend fun invalidate() {}
}

// ── ENV source ──

class EnvSource(private val prefix: String = "") : KeySource() {
    override val name = "env"
    override suspend fun read(path: KeyPath): String? {
        val key = (if (prefix.isNotEmpty()) "$prefix." else "") + path.asString()
        return SystemOperations.default.getenv(key.uppercase().replace('.', '_'))
    }
    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("env source is read-only")
    }
}

/** Resolve one exact environment variable for an explicitly bound key path. */
class EnvVarSource(private val variable: String) : KeySource() {
    override val name = "env:$variable"
    override suspend fun read(path: KeyPath): String? = SystemOperations.default.getenv(variable)
    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("env source is read-only")
    }
}

// ── PERSIST source ──

class PersistSource(
    val root: String,
    val explicitFileOps: FileOperations? = null,
    private val codec: (ByteArray) -> Map<String, String> = { bytes ->
        val text = bytes.decodeToString()
        (borg.trikeshed.parse.json.JsonSupport.parse(text) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
    },
    private val encode: (Map<String, String>) -> ByteArray = { m ->
        borg.trikeshed.parse.json.JsonSupport.stringify(m).encodeToByteArray()
    }
) : KeySource() {
    override val name = "persist"

    private var cache: Map<String, String>? = null

    private suspend fun getFileOps(): FileOperations {
        return explicitFileOps
            ?: currentCoroutineContext()[FileOperations.Key]
            ?: error("No FileOperations found in coroutine context for PersistSource")
    }

    private suspend fun load(ops: FileOperations): Map<String, String> {
        val cached = cache
        if (cached != null) return cached
        val file = ops.resolvePath(root, "keymux.conf")
        val loaded = if (ops.exists(file)) {
            codec(ops.readAllBytes(file))
        } else {
            emptyMap()
        }
        cache = loaded
        return loaded
    }

    private suspend fun flush(ops: FileOperations, m: Map<String, String>) {
        cache = null
        ops.mkdirs(root)
        val file = ops.resolvePath(root, "keymux.conf")
        ops.write(file, encode(m))
    }

    override suspend fun read(path: KeyPath): String? {
        val ops = getFileOps()
        return load(ops)[path.asString()]
    }

    override suspend fun write(path: KeyPath, value: String) {
        val ops = getFileOps()
        flush(ops, load(ops) + (path.asString() to value))
    }

    override suspend fun invalidate() {
        cache = null
    }

    /**
     * Read the whole persisted map WITH THIS SOURCE'S CODEC. KeyMux.rotate() must not
     * use the legacy k=v line reader — write() persists JSON, so a line-codec read
     * sees zero candidates and rotation silently no-ops (found by the K7 gate).
     */
    internal suspend fun readAll(ops: FileOperations): Map<String, String> = load(ops)
}

private fun defaultCodecRead(bytes: ByteArray): Map<String, String> {
    val text = bytes.decodeToString()
    return text.lineSequence()
        .filter { it.contains('=') && !it.startsWith('#') }
        .associate { val (k, v) = it.split('=', limit = 2); k.trim() to v.trim() }
}

private fun defaultCodecWrite(m: Map<String, String>): ByteArray {
    val text = m.entries.joinToString("\n") { "${it.key}=${it.value}" }
    return text.encodeToByteArray()
}

// ── API source ──

class ApiSource(
    private val baseUrl: String,
    private val headers: Series<Twin<String>> = 0 j { throw IndexOutOfBoundsException() },
    private val explicitHtx: HtxElement? = null
) : KeySource() {
    override val name = "api"

    private suspend fun getHtx(): HtxElement {
        return explicitHtx
            ?: currentCoroutineContext()[HtxKey]
            ?: error("No HtxKey found in coroutine context for ApiSource")
    }

    override suspend fun read(path: KeyPath): String? {
        val htx = getHtx()
        val url = "$baseUrl/${path.asString()}"
        val htxHeaders = htxHeaders(*headers.toArray())
        val req = parseHtxRequest(url = url, method = HtxMethod.GET).copy(headers = htxHeaders)
        val resp = htx.request(req)
        if (resp.status != 200) return null
        return resp.body.toArray().decodeToString().trim().ifEmpty { null }
    }

    override suspend fun write(path: KeyPath, value: String) {
        val htx = getHtx()
        val url = "$baseUrl/${path.asString()}"
        val htxHeaders = htxHeaders(*headers.toArray())
        val req = parseHtxRequest(
            url = url,
            method = HtxMethod.PUT,
            body = ByteSeries(value.encodeToByteArray())
        ).copy(headers = htxHeaders)
        htx.request(req)
    }
}

// ── REACTOR source ──

class ReactorSource(
    private val explicitReactor: MuxReactorElement? = null
) : KeySource() {
    override val name = "reactor"

    private suspend fun getReactor(): MuxReactorElement {
        return explicitReactor
            ?: currentCoroutineContext()[MuxReactorElement.Key]
            ?: error("No MuxReactorElement found in coroutine context for ReactorSource")
    }

    override suspend fun read(path: KeyPath): String? {
        val r = getReactor()
        val keyStr = path.asString()
        if (!keyStr.startsWith("llm.") || !keyStr.endsWith(".key")) return null
        val identifier = keyStr.removePrefix("llm.").removeSuffix(".key")
        val keys = r.flowState.value.keys
        if (identifier == "default") {
            return keys.firstOrNull { it.status == MuxKeyStatus.ACTIVE }?.keyId
        }
        val match = keys.firstOrNull { it.status == MuxKeyStatus.ACTIVE && (it.lastModel == identifier || it.provider == identifier) }
            ?: keys.firstOrNull { it.status == MuxKeyStatus.ACTIVE }
        return match?.keyId
    }

    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("reactor source is read-only")
    }
}

// ── FIXED source ──

class FixedKeySource(
    private val fixedValue: String,
    override val name: String = "fixed",
) : KeySource() {
    override suspend fun read(path: KeyPath): String? = fixedValue
    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("fixed source is read-only")
    }
}

// ── CACHED source — TTL-based cache wrapping any other source ──

/**
 * Wraps any [KeySource] with TTL-based in-memory caching.
 * Model listings and provider keys change infrequently — caching for [ttlMs]
 * (default 24 hours) avoids re-resolving env/persist/API on every chat call.
 *
 * Thread-safe via [Mutex] (every method here is already `suspend`), not JVM
 * `synchronized`/`ConcurrentHashMap` — this stays a legal commonMain
 * `KeySource`, compiling for JVM, JS, and Wasm from one source.
 */
class CachedKeySource(
    private val delegate: KeySource,
    override val name: String = "cached:${delegate.name}",
    private val ttlMs: Long = 24 * 60 * 60 * 1000L, // 24 hours
) : KeySource() {
    private data class Entry(val value: String?, val resolvedAt: Long)

    private val lock = kotlinx.coroutines.sync.Mutex()
    private val cache = mutableMapOf<String, Entry>()

    private fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    override suspend fun read(path: KeyPath): String? {
        val key = path.asString()
        val now = nowMs()
        lock.withLock { cache[key] }?.let { entry ->
            if (now - entry.resolvedAt < ttlMs) return entry.value
        }
        val value = delegate.read(path)
        lock.withLock { cache[key] = Entry(value, now) }
        return value
    }

    override suspend fun write(path: KeyPath, value: String) {
        delegate.write(path, value)
        lock.withLock { cache.remove(path.asString()) }
    }

    override suspend fun invalidate() {
        lock.withLock { cache.clear() }
        delegate.invalidate()
    }

    /** Evict entries older than [ageMs]. Useful for selective refresh. */
    suspend fun evictStale(ageMs: Long = ttlMs) {
        val now = nowMs()
        lock.withLock { cache.entries.retainAll { now - it.value.resolvedAt < ageMs } }
    }
}

// ── TEST source ──

class TestKeySource(
    override val name: String = "test",
    var value: String? = "sk-test"
) : KeySource() {
    override suspend fun read(path: KeyPath): String? = value
    override suspend fun write(path: KeyPath, value: String) {
        this.value = value
    }
}

// ═══════════════════════════════════════════
// Utilities
// ═══════════════════════════════════════════

/**
 * Determines whether a [path] matches a [pattern] containing variables.
 *
 * Paths are matched segment by segment, separated by `/`. A pattern segment starting
 * with `:` acts as a variable and matches any corresponding segment in the path.
 * Exact equality is required for all other segments. The paths must have the same number of segments.
 *
 * **Examples:**
 * - `pathMatch("api/users", "api/users")` returns `true`
 * - `pathMatch("api/users", "api/users/")` returns `false` (length mismatch due to trailing slash)
 * - `pathMatch("api/users", "API/users")` returns `false` (case-sensitive)
 * - `pathMatch("users/:id/profile", "users/123/profile")` returns `true`
 * - `pathMatch("users/:id/profile", "users/123/settings")` returns `false`
 * - `pathMatch("api//users", "api/users")` returns `false` (malformed consecutive slashes not normalized)
 */
fun pathMatch(pattern: String, path: String): Boolean {
    val patternParts = pattern.split("/")
    val pathParts = path.split("/")
    if (patternParts.size != pathParts.size) return false
    for (i in patternParts.indices) {
        if (patternParts[i].startsWith(":")) continue
        if (patternParts[i] != pathParts[i]) return false
    }
    return true
}

// ═══════════════════════════════════════════
// Free-tier Projection
// ═══════════════════════════════════════════

val MuxKeyEntry.isFreeTier: Boolean
    get() = provider.contains("free", ignoreCase = true) || label.contains("free", ignoreCase = true)

class FreeTierKeyProjection(private val base: Series<MuxKeyEntry>) : Sequence<MuxKeyEntry> {
    private var cursor: Int = 0

    fun nextFreeKey(excluding: Series<String> = emptySeries()): MuxKeyEntry? {
        while (cursor < base.size) {
            val key = base[cursor]
            cursor++
            if (key.isFreeTier && key.status == MuxKeyStatus.ACTIVE) {
                var isExcluded = false
                for (ex in excluding.iterable()) {
                    if (ex == key.keyId) {
                        isExcluded = true
                        break
                    }
                }
                if (!isExcluded) return key
            }
        }
        return null
    }

    override fun iterator(): Iterator<MuxKeyEntry> = object : Iterator<MuxKeyEntry> {
        private var nextElement: MuxKeyEntry? = null

        private fun advance() {
            if (nextElement == null) {
                nextElement = nextFreeKey()
            }
        }

        override fun hasNext(): Boolean {
            advance()
            return nextElement != null
        }

        override fun next(): MuxKeyEntry {
            if (!hasNext()) throw NoSuchElementException()
            val result = nextElement!!
            nextElement = null
            return result
        }
    }
}

fun Series<MuxKeyEntry>.freeTierProjection(): FreeTierKeyProjection = FreeTierKeyProjection(this)

// ═══════════════════════════════════════════
// Resolver — first-wins precedence across sources
// ═══════════════════════════════════════════

interface KeyResolver {
    suspend fun resolve(bindings: Series<KeyBinding>, path: KeyPath): KeyResult
}

object FirstWinsResolver : KeyResolver {
    override suspend fun resolve(bindings: Series<KeyBinding>, path: KeyPath): KeyResult =
        (0 until bindings.size).firstNotNullOfOrNull { i ->
            val (p, src) = bindings[i]
            if (pathMatch(p, path)) src.read(path)?.let { it j src.name } else null
        } ?: (null j "none")

    /**
     * Determines whether a [query] path matches a [binding] path containing wildcards.
     *
     * Paths are matched segment by segment. A binding segment of `*` acts as a single-segment wildcard.
     * Exact equality is required for all other segments. The paths must have the same number of segments.
     *
     * **Examples:**
     * - `pathMatch("api.*".toKeyPath(), "api.users".toKeyPath())` returns `true`
     * - `pathMatch("api.users.*".toKeyPath(), "api.users".toKeyPath())` returns `false` (length mismatch)
     * - `pathMatch("api.users".toKeyPath(), "API.users".toKeyPath())` returns `false` (case-sensitive)
     * - `pathMatch("users.*.profile".toKeyPath(), "users.123.profile".toKeyPath())` returns `true`
     */
    private fun pathMatch(binding: KeyPath, query: KeyPath): Boolean {
        // A bare "*" binding is the global fallback — env()/persist()/api()/reactor()/
        // harness() all bind it. Before this arm, ["*"] only matched single-segment
        // queries, so `KeyMux { env() }` never answered "llm.<provider>.key" and the
        // daemon's env lane was dead for every modelmux path.
        if (binding.size == 1 && binding[0] == "*") return true
        if (binding.size != query.size) return false
        return (0 until binding.size).all { i ->
            binding[i] == "*" || binding[i] == query[i]
        }
    }
}

// ═══════════════════════════════════════════
// KeyMux — the KMP surface
// ═══════════════════════════════════════════

class KeyMux constructor(
    private val core: KeyMuxCore
) {
    private val bindings: Series<KeyBinding> get() = core.a
    private val resolver: KeyResolver get() = core.b

    // Single-writer invariant: mutable map stays as private backing, modified by reactor/test
    private val leaseBacking = mutableMapOf<KeyId, LeaseMetadata>()
    
    // Visit counter to verify laziness in tests
    internal var leaseVisits = 0

    // Test-only setter since reactor mutation is not fully mocked here
    internal fun setLeaseForTest(keyId: KeyId, metadata: LeaseMetadata) {
        leaseBacking[keyId] = metadata
    }

    /** Lease-view API returning Series2<KeyId, LeaseMetadata> — lazy, no copying, Join not Pair. */
    val activeLeases: Series2<KeyId, LeaseMetadata> get() = leaseBacking.size j { i ->
        leaseVisits++
        val entry = leaseBacking.entries.elementAt(i)
        entry.key j entry.value
    }

    companion object {
        operator fun invoke(block: KeyMuxBuilder.() -> Unit): KeyMux =
            KeyMuxBuilder().apply(block).build()
    }

    suspend fun get(key: String): String? = resolver.resolve(bindings, key.toKeyPath()).a

    suspend fun getWithSource(key: String): KeyResult = resolver.resolve(bindings, key.toKeyPath())

    suspend fun set(key: String, value: String) {
        val path = key.toKeyPath()
        for ((p, src) in bindings.view) {
            if (pathMatch(p, path)) {
                try { src.write(path, value); return } catch (_: UnsupportedOperationException) { /* skip read-only */ }
            }
        }
        error("no writable source for key: $key")
    }

    /**
     * Rotate to the next credential for [key] in persistent storage.
     *
     * Jules API calls consume quota; when a 429 fires the next key is used.
     * Rotation cycles through the `persist` source (keymux.conf): each entry
     * after the current one becomes the new value.  If no persist source holds
     * the key, this is a no-op and the current binding is retained.
     *
     * @return the new value after rotation, or null if no next value existed
     */
    suspend fun rotate(key: String): String? {
        val path = key.toKeyPath()
        val keyStr = key
        // Find all PersistSource instances that have this key
        val candidates = mutableListOf<Pair<PersistSource, String>>()
        for ((p, src) in bindings.view) {
            if (src is PersistSource && pathMatch(p, path)) {
                val ops = src.explicitFileOps
                    ?: currentCoroutineContext()[FileOperations.Key]
                    ?: continue
                val file = ops.resolvePath(src.root, "keymux.conf")
                if (!ops.exists(file)) continue
                val map = src.readAll(ops) // the source's own codec — not the legacy line reader
                map[keyStr]?.let { candidates.add(src to it) }
            }
        }
        if (candidates.isEmpty()) {
            println("[KEYMUX] rotate($keyStr): no persist source has this key; retaining current value")
            return null
        }
        // Rotate: the second candidate becomes the new value for all
        val nextValue = candidates.getOrNull(1)?.second ?: candidates[0].second
        for ((src, _) in candidates) {
            src.invalidate()
        }
        for ((p, src) in bindings.view) {
            if (src is PersistSource && pathMatch(p, path)) {
                try {
                    src.write(path, nextValue)
                    src.invalidate()
                    return nextValue
                } catch (_: UnsupportedOperationException) { /* skip */ }
            }
        }
        return null
    }

    suspend fun list(prefix: String): Series<Join<String, String>> =
        listRaw(prefix).let { s -> s.size j { i -> s[i].a j (s[i].b ?: "") } }

    fun watch(prefix: String = ""): Flow<Join<String, String>> = emptyFlow()

    suspend fun invalidate() {
        for ((_, src) in bindings.view) {
            src.invalidate()
        }
    }

    /**
     * Determines whether a [query] path matches a [binding] path prefix containing wildcards.
     *
     * Matches segment by segment. A binding segment of `*` acts as a single-segment wildcard.
     * The [query] path must have at least as many segments as the [binding] path. Any trailing
     * segments in the [query] beyond the length of the [binding] are ignored.
     *
     * **Examples:**
     * - `pathMatch("api.*".toKeyPath(), "api.users".toKeyPath())` returns `true`
     * - `pathMatch("api.users".toKeyPath(), "api.users.list".toKeyPath())` returns `true`
     * - `pathMatch("api.users.*".toKeyPath(), "api.users".toKeyPath())` returns `false` (binding is longer)
     * - `pathMatch("api".toKeyPath(), "api".toKeyPath())` returns `true`
     */
    private fun pathMatch(binding: KeyPath, query: KeyPath): Boolean {
        if (binding.size > query.size) return false
        return binding.view.withIndex().all { (i, seg) -> seg == "*" || seg == query[i] }
    }

    private suspend fun listRaw(prefix: String): Series<Join<String, String?>> {
        val results = mutableListOf<Join<String, String?>>()
        for ((_, src) in bindings.view) {
            if (src is PersistSource) {
                val fileOps = src.explicitFileOps
                    ?: currentCoroutineContext()[FileOperations.Key]
                    ?: continue
                val file = fileOps.resolvePath(src.root, "keymux.conf")
                if (!fileOps.exists(file)) continue
                val map = src.readAll(fileOps) // source's own codec (JSON), matching write()
                map.filter { it.key.startsWith(prefix) }.forEach { (k, v) ->
                    results.add(k j v)
                }
            }
        }
        return results.toSeries()
    }
}

class KeyMuxBuilder {
    private val sources = mutableListOf<Join<KeyPath, KeySource>>()

    fun env(prefix: String = ""): KeyMuxBuilder = apply {
        sources.add("*".toKeyPath() j EnvSource(prefix))
    }

    fun persist(root: String): KeyMuxBuilder = apply {
        sources.add("*".toKeyPath() j PersistSource(root))
    }

    fun api(baseUrl: String, vararg hdrs: Pair<String, String>): KeyMuxBuilder = apply {
        // Bolt: avoid intermediate list allocations from .toList().map
        val h = hdrs.map { it.first j it.second }.toSeries()
        sources.add("*".toKeyPath() j ApiSource(baseUrl, h))
    }

    fun reactor(): KeyMuxBuilder = apply {
        sources.add("*".toKeyPath() j ReactorSource())
    }

    fun bind(prefix: String, source: KeySource): KeyMuxBuilder = apply {
        sources.add(prefix.toKeyPath() j source)
    }

    /** Wrap the next-added source with [CachedKeySource] for24-hour TTL caching. */
    fun cached(prefix: String, source: KeySource, ttlMs: Long = 24 * 60 * 60 * 1000L): KeyMuxBuilder = apply {
        sources.add(prefix.toKeyPath() j CachedKeySource(source, ttlMs = ttlMs))
    }

    fun build(): KeyMux {
        val bindings: Series<KeyBinding> = sources.toSeries()
        return KeyMux(bindings j FirstWinsResolver)
    }
}
