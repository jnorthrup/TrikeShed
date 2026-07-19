package keymux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Lazy iterable — delegates to Series.view, no eager size-N List. */
fun <T> Series<T>.iterable(): Iterable<T> = view

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

fun KeyPath.asString(): String = view.joinToString(".")
fun String.toKeyPath(): KeyPath = split(".").toSeries()

// ═══════════════════════════════════════════
// Source algebra — sealed, each wraps platform/reactor effects
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
        return SystemOperations.default.getenv(key.uppercase().replace('.', '_'))
    }
    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("env source is read-only")
    }
}

// ── PERSIST source ──

class PersistSource(
    val root: String,
    val explicitFileOps: FileOperations? = null,
    private val codec: (ByteArray) -> Map<String, String> = ::defaultCodecRead,
    private val encode: (Map<String, String>) -> ByteArray = ::defaultCodecWrite
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
        cache = m
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
        val htxHeaders = htxHeaders(*headers.toList().map { it.a j it.b }.toTypedArray())
        val req = parseHtxRequest(url = url, method = HtxMethod.GET).copy(headers = htxHeaders)
        val resp = htx.request(req)
        if (resp.status != 200) return null
        return resp.body.toArray().decodeToString().trim().ifEmpty { null }
    }

    override suspend fun write(path: KeyPath, value: String) {
        val htx = getHtx()
        val url = "$baseUrl/${path.asString()}"
        val htxHeaders = htxHeaders(*headers.toList().map { it.a j it.b }.toTypedArray())
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
// KeyMux — the KMP surface
// ═══════════════════════════════════════════

class KeyMux constructor(
    private val core: KeyMuxCore
) {
    private val bindings: Series<KeyBinding> get() = core.a
    private val resolver: KeyResolver get() = core.b

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

    suspend fun list(prefix: String): Series<Join<String, String>> =
        listRaw(prefix).let { s -> s.size j { i -> s[i].a j (s[i].b ?: "") } }

    fun watch(prefix: String = ""): Flow<Join<String, String>> = emptyFlow()

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
                val map = defaultCodecRead(fileOps.readAllBytes(file))
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
        val h = hdrs.toList().map { it.first j it.second }.toSeries()
        sources.add("*".toKeyPath() j ApiSource(baseUrl, h))
    }

    fun reactor(): KeyMuxBuilder = apply {
        sources.add("*".toKeyPath() j ReactorSource())
    }

    fun bind(prefix: String, source: KeySource): KeyMuxBuilder = apply {
        sources.add(prefix.toKeyPath() j source)
    }

    fun build(): KeyMux {
        val bindings: Series<KeyBinding> = sources.toSeries()
        return KeyMux(bindings j FirstWinsResolver)
    }
}
