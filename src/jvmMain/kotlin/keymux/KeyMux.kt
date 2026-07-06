package keymux

import keymux.transport.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import borg.trikeshed.lib.*
//temporary demo exception to the uring userspace nio without trekking far off the path 

fun <T> Series<T>.iterable(): Iterable<T> = Iterable { (0 until size).map { this[it] }.iterator() }

// ═══════════════════════════════════════════
// Type algebra
// ═══════════════════════════════════════════

/** Dotted-path key: "llm.openai.key" → ["llm","openai","key"] */
typealias KeyPath = Series<String>

/** A resolved value: the value + which source produced it */
typealias KeyResult = Join<String?, String>   // value j sourceName

/** A binding: path joined to a lazy source */
typealias KeyBinding = Join<KeyPath, KeySource>

/** The mux itself: ordered bindings + resolver strategy */
typealias KeyMuxCore = Join<Series<KeyBinding>, KeyResolver>

fun KeyPath.asString(): String = (0 until size).joinToString(".") { this[it] }
fun String.toKeyPath(): KeyPath = split(".").toSeries()

// ═══════════════════════════════════════════
// Source algebra — sealed, each owns its NIO channel
// ═══════════════════════════════════════════

sealed class KeySource {
    abstract val name: String
    abstract suspend fun read(path: KeyPath): String?
    abstract suspend fun write(path: KeyPath, value: String)
}

// ── ENV source ──

class EnvSource(private val prefix: String = "") : KeySource() {
    override val name = "env"
    override suspend fun read(path: KeyPath): String? {
        val key = (if (prefix.isNotEmpty()) "$prefix." else "") + path.asString()
        return System.getenv(key.uppercase().replace('.', '_'))
    }
    override suspend fun write(path: KeyPath, value: String) {
        error("env source is read-only")
    }
}

// ── PERSIST source — NIO file-backed JSON-ish key store ──

class PersistSource(
    val root: Path,
    private val codec: (ByteBuffer) -> Map<String, String> = ::defaultCodecRead,
    private val encode: (Map<String, String>) -> ByteBuffer = ::defaultCodecWrite
) : KeySource() {
    override val name = "persist"

    private var cache: Map<String, String>? = null

    private suspend fun load(): Map<String, String> = cache ?: (
        NioStore.read(root.resolve("keymux.conf"))?.let { codec(it) } ?: emptyMap()
    ).also { cache = it }

    private suspend fun flush(m: Map<String, String>) {
        cache = m
        NioStore.write(root.resolve("keymux.conf"), encode(m))
    }

    override suspend fun read(path: KeyPath): String? = load()[path.asString()]
    override suspend fun write(path: KeyPath, value: String) = flush(load() + (path.asString() to value))
}

private fun defaultCodecRead(buf: ByteBuffer): Map<String, String> {
    val text = Charsets.UTF_8.decode(buf.duplicate()).toString()
    return text.lineSequence()
        .filter { it.contains('=') && !it.startsWith('#') }
        .associate { val (k, v) = it.split('=', limit = 2); k.trim() to v.trim() }
}

private fun defaultCodecWrite(m: Map<String, String>): ByteBuffer {
    val text = m.entries.joinToString("\n") { "${it.key}=${it.value}" }
    return ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
}

// ── API source — remote config via NIO HTTP ──

class ApiSource(
    private val baseUrl: String,
    private val headers: Series<Join<String, String>> = 0 j { throw IndexOutOfBoundsException() }
) : KeySource() {
    override val name = "api"

    override suspend fun read(path: KeyPath): String? {
        val uri = URI.create("$baseUrl/${path.asString()}")
        val resp = NioHttp.request(HttpRequest("GET", uri, headers))
        if (resp.status != 200) return null
        return Charsets.UTF_8.decode(resp.body.duplicate()).toString().trim().ifEmpty { null }
    }

    override suspend fun write(path: KeyPath, value: String) {
        val uri = URI.create("$baseUrl/${path.asString()}")
        val body = ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8))
        NioHttp.request(HttpRequest("PUT", uri, headers, body))
    }
}

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

    private fun pathMatch(binding: KeyPath, query: KeyPath): Boolean {
        if (binding.size != query.size) return false
        return (0 until binding.size).all { i ->
            binding[i] == "*" || binding[i] == query[i]
        }
    }
}

// ═══════════════════════════════════════════
// KeyMux — the public surface
// ═══════════════════════════════════════════

class KeyMux internal constructor(
    private val core: KeyMuxCore
) {
    private val bindings: Series<KeyBinding> get() = core.a
    private val resolver: KeyResolver get() = core.b

    companion object {
        operator fun invoke(block: KeyMuxBuilder.() -> Unit): KeyMux =
            KeyMuxBuilder().apply(block).build()
    }

    /** Resolve a key through the source stack */
    suspend fun get(key: String): String? = resolver.resolve(bindings, key.toKeyPath()).a

    /** Resolve with provenance */
    suspend fun getWithSource(key: String): KeyResult = resolver.resolve(bindings, key.toKeyPath())

    /** Write to the first writable source matching the key prefix */
    suspend fun set(key: String, value: String) {
        val path = key.toKeyPath()
        for (i in 0 until bindings.size) {
            val (p, src) = bindings[i]
            if (pathMatch(p, path)) {
                try { src.write(path, value); return } catch (_: UnsupportedOperationException) { /* skip read-only */ }
            }
        }
        error("no writable source for key: $key")
    }

    /** Project all keys matching a prefix — returns Series of results */
    suspend fun list(prefix: String): Series<Join<String, String>> =
        listRaw(prefix).let { s -> s.size j { i -> s[i].a j (s[i].b ?: "") } }

    /** Watch for changes via NIO WatchService on persist roots */
    fun watch(prefix: String = ""): Flow<Join<String, String>> = flow {
        val persistRoots = (0 until bindings.size)
            .map { bindings[it] }
            .filter { it.b is PersistSource }
            .map { (it.b as PersistSource).root }
        for (root in persistRoots) {
            NioStore.watch(root).collect { (path, kind) ->
                val rel = root.relativize(path).toString().replace('/', '.').removeSuffix(".conf")
                if (rel.startsWith(prefix)) emit(rel j kind.name())
            }
        }
    }

    private fun pathMatch(binding: KeyPath, query: KeyPath): Boolean {
        if (binding.size > query.size) return false
        return (0 until binding.size).all { i -> binding[i] == "*" || binding[i] == query[i] }
    }

    /** Internal listRaw — enumerates all key→value from persist sources */
    private suspend fun listRaw(prefix: String): Series<Join<String, String?>> {
        val results = mutableListOf<Join<String, String?>>()
        for (i in 0 until bindings.size) {
            val src = bindings[i].b
            if (src is PersistSource) {
                val data = NioStore.read((src as PersistSource).let { it.root.resolve("keymux.conf") })
                    ?: continue
                val map = defaultCodecRead(data)
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

    /** Bind env vars with a prefix (e.g. "APP_" → APP_LLMS__OPENAI__KEY) */
    fun env(prefix: String = ""): KeyMuxBuilder = apply {
        sources.add("*".toKeyPath() j EnvSource(prefix))
    }

    /** Bind a persistence directory */
    fun persist(root: Path): KeyMuxBuilder = apply {
        sources.add("*".toKeyPath() j PersistSource(root))
    }

    /** Bind a remote config API */
    fun api(baseUrl: String, vararg hdrs: Pair<String, String>): KeyMuxBuilder = apply {
        val h = hdrs.toList().map { it.first j it.second }.toSeries()
        sources.add("*".toKeyPath() j ApiSource(baseUrl, h))
    }

    /** Bind a source under a specific key prefix */
    fun bind(prefix: String, source: KeySource): KeyMuxBuilder = apply {
        sources.add(prefix.toKeyPath() j source)
    }

    fun build(): KeyMux {
        val bindings: Series<KeyBinding> = sources.toSeries()
        return KeyMux(bindings j FirstWinsResolver)
    }
}
