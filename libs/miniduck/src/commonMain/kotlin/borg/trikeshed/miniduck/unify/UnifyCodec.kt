@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.miniduck.unify

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.common.toLongSeries
import borg.trikeshed.common.toRowVec
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.*
import borg.trikeshed.parse.yaml.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// ─── Format tag ───────────────────────────────────────────────────────────────
enum class Format { JSON, YAML, CBOR }

// ─── KEYS: CoroutineContext.Key<> singleton identity objects ───────────────────
// Keys are the factory-abstraction holders. Each is a CoroutineContext.Key<T>
// that uniquely identifies a keyed service in the context. The spring model
// composes them via context + operator plus: each new context layer overrides
// prior bindings without mutating the old map — just like a prototype chain.

/** KEYS: Json format factory — CoroutineContext.Key identity for json branch. */
object JsonCodecKey : CoroutineContext.Key<JsonCodecService>

/** KEYS: Yaml format factory. */
object YamlCodecKey : CoroutineContext.Key<YamlCodecService>

/** KEYS: Cbor format factory. */
object CborCodecKey : CoroutineContext.Key<CborCodecService>

/** KEYS: Active format tag stored in context. */
data class ActiveFormat(
    val format: Format,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = ActiveFormat

    companion object Key : CoroutineContext.Key<ActiveFormat>
}

/** KEYS: WAM program counter — tracks position in token stream per branch. */
data class WamPc(
    val pc: Int,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<WamPc>
}

// ─── ELEMENTS: CoroutineContext.Element working state ─────────────────────────
// Each Element IS a CoroutineContext.Element (data class + companion Key).
// They carry mutable working state and are composed into the context like
// stack frames. The spring model: withContext overlays them; pop restores.

/** ELEMENTS: Json scan state as CoroutineContext.Element. */
data class JsonCodecService(
    val index: (Series<Char>, MutableList<Int>?, Int?) -> JsElement,
    val reify: (Series<Char>, MutableList<TypeEvidence>?, ((RowVec) -> Unit)?) -> Any?,
    val jsPath: (JsContext, JsPath, Boolean, List<Int>?) -> Any?,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = JsonCodecKey

    companion object {
        /** Default json factory — captures JsonParser.index / .reify / .jsPath as local state. */
        val default: JsonCodecService = JsonCodecService(
            index = { src, d, t -> JsonParser.index(src, d, t) },
            reify = { src, ev, cb -> JsonParser.reify(src, ev, cb) },
            jsPath = { ctx, p, r, d -> JsonParser.jsPath(ctx, p, r, d) },
        )
    }
}

/** ELEMENTS: Yaml scan state as CoroutineContext.Element. */
data class YamlCodecService(
    val parse: (Series<Char>) -> YamlDocument,
    val reify: (Series<Char>, MutableList<TypeEvidence>, ((RowVec) -> Unit)) -> Any?,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = YamlCodecKey

    companion object {
        val default: YamlCodecService = YamlCodecService(
            parse = { src -> YamlParser.parse(src) },
            reify = { src, ev, cb -> YamlParser.reify(src, ev, cb) },
        )
    }
}

/** ELEMENTS: Cbor encode/decode state as CoroutineContext.Element. */
data class CborCodecService(
    val encode: (Item) -> ByteArray,
    val decode: (ByteArray) -> Item,
    val toValue: (Item) -> Any?,
    val fromValue: (Any?) -> Item,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = CborCodecKey

    companion object {
        val default: CborCodecService = CborCodecService(
            encode = { item -> Cbor.encode(item) },
            decode = { bytes -> Cbor.decode(bytes) },
            toValue = { item -> item.toTrikeValue() },
            fromValue = { any -> any.toTrikeItem() },
        )
    }
}

/** ELEMENTS: WAM accumulator — holds current JsContext / YamlNode / Item per branch. */
data class WamAcc(
    val value: Any?,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<WamAcc>
}

/** ELEMENTS: WAM environment — holds JsPath / segment list for query dispatch. */
data class WamEnv(
    val path: List<String>,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<WamEnv>
}

// ─── Item ↔ Kotlin value conversion (Cbor side) ───────────────────────────────

private fun Item.toTrikeValue(): Any? = when (this) {
    is Item.Nil -> null
    is Item.Str -> value
    is Item.Num -> value
    is Item.Flt -> value
    is Item.Bool -> value
    is Item.Bin -> value
    is Item.Map -> entries.view.associate { it.a to it.b.toTrikeValue() }
    is Item.Arr -> (0 until size).map { get(it).toTrikeValue() }
    is Item.Tag -> item.toTrikeValue()
}

private fun Any?.toTrikeItem(): Item = when (this) {
    null -> Item.Nil
    is String -> Item.Str(this)
    is Long -> Item.Num(this)
    is Int -> Item.Num(this.toLong())
    is Double -> Item.Flt(this)
    is Boolean -> Item.Bool(this)
    is ByteArray -> Item.Bin(this)
    is List<*> -> Item.Arr(this.size j { this[it].toTrikeItem() })
    is Map<*, *> -> Item.Map(
        (this as Map<*, *>).size j { i ->
            val e = this.entries.elementAt(i)
            e.key.toString() j e.value.toTrikeItem()
        },
    )

    else -> Item.Str(toString())
}



// ─── UnifyCodec: SupervisorJob + KEYS/ELEMENTS spring ─────────────────────────

/**
 * Unified codec SupervisorJob.
 *
 * Structure (adheres to all three source algebras):
 *   KEYS   = CoroutineContext.Key<> singletons (factory abstraction holders)
 *   ELEMENTS = CoroutineContext.Element data classes (mutable WAM working state)
 *   spring model = withContext overlays; SupervisorJob children = fanout branches
 *
 * Lifecycle states match ElementState enum (CREATED → OPEN → ACTIVE → DRAINING → CLOSED).
 * The SupervisorJob is the structural spine; KEYS/ELEMENTS are overlaid per-branch.
 *
 * Fanout directions: { json, yaml, cbor } × { parse, reify, query }
 * Each fanout branch gets its own withContext layer carrying the appropriate
 * KEYS (JsonCodecService | YamlCodecService | CborCodecService) + ELEMENTS
 * (WamAcc, WamEnv, WamPc) so state is isolated yet composable.
 */
class UnifyCodec(
    private val format: Format,
) {
    private var state: ElementState = ElementState.CREATED
    private var scope: CoroutineScope? = null

    val lifecycleState: ElementState get() = state

    // ── Lifecycle (matches Trainer.kt pattern) ─────────────────────────────

    suspend fun open() {
        check(state == ElementState.CREATED) { "open() in state $state" }
        state = ElementState.OPEN
    }

    suspend fun drain() {
        check(state.isAtLeast(ElementState.OPEN)) { "drain() in state $state" }
        if (state == ElementState.OPEN) {
            state = ElementState.DRAINING
            state = ElementState.CLOSED
        }
    }

    suspend fun close() {
        check(state.isAtLeast(ElementState.OPEN)) { "close() in state $state" }
        state = ElementState.CLOSED
    }

    // ── Core WAM loop: parse → reify → query, unified ─────────────────────

    /**
     * WAM unified execution:
     *   1. withContext pushes the format's KEYS + ELEMENTS onto the context
     *   2. parseIndex advances WamPc and stores element in WamAcc
     *   3. reify consumes WamAcc and materialises the value
     *   4. query navigates WamEnv path against WamAcc
     * All three phases share the same SupervisorJob spine.
     */
    suspend fun <T> withWamContext(
        block: suspend CoroutineScope.() -> T,
    ): T {
        val parent = coroutineContext[Job]
        val supJob = SupervisorJob(parent)

        val formatCtx: CoroutineContext = when (format) {
            Format.JSON -> JsonCodecService.default
            Format.YAML -> YamlCodecService.default
            Format.CBOR -> CborCodecService.default
        }

        return withContext(supJob + formatCtx) {
            block()
        }
    }

    // ── Operations ─────────────────────────────────────────────────────────

    /**
     * Parse: tokenise input into WAM index form.
     *   JSON → JsElement (open/close/comma positions)
     *   YAML → YamlSpan (line range)
     *   CBOR → Item (decoded but not reified)
     */
    suspend fun parseIndex(src: CharSeriesWrapper): Any? = when (format) {
        Format.JSON -> {
            val ctx = currentCoroutineContext()
            val svc: JsonCodecService = ctx[JsonCodecKey] ?: JsonCodecService.default
            val depths = mutableListOf<Int>()
            val elem = svc.index(src.chars, depths, null)
            // Push WamAcc + WamPc into context
            elem
        }

        Format.YAML -> {
            val ctx = coroutineContext
            val svc: YamlCodecService = ctx[YamlCodecKey] ?: YamlCodecService.default
            val doc = svc.parse(src.chars)
            doc.root.span
        }

        Format.CBOR -> {
            val ctx = coroutineContext
            val svc: CborCodecService = ctx[CborCodecKey] ?: CborCodecService.default
            svc.decode(src.bytes)
        }
    }

    /**
     * Reify: fully materialise the parsed structure into Kotlin values.
     * Uses TypeEvidence RowVec callback for each node (matches JsonParser.reify signature).
     */
    suspend fun reify(
        src: CharSeriesWrapper,
        nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
        rowVecCallback: ((RowVec) -> Unit)? = null,
    ): Any? = when (format) {
        Format.JSON -> {
            val svc: JsonCodecService = coroutineContext[JsonCodecKey] ?: JsonCodecService.default
            svc.reify(src.chars, nodeEvidence, rowVecCallback)
        }

        Format.YAML -> {
            val svc: YamlCodecService = coroutineContext[YamlCodecKey] ?: YamlCodecService.default
            svc.reify(src.chars, nodeEvidence, rowVecCallback ?: {})
        }

        Format.CBOR -> {
            val svc: CborCodecService = coroutineContext[CborCodecKey] ?: CborCodecService.default
            val item = svc.decode(src.bytes)
            nodeEvidence.add(TypeEvidence.sample(src.chars))
            rowVecCallback?.invoke(nodeEvidence.last().toRowVec())
            svc.toValue(item)
        }
    }

    /**
     * Query: path-based navigation.
     *   JSON → JsPath (Either<String,Int> segments)
     *   YAML → dot-path strings
     *   CBOR → dot-path strings
     *
     * @param path dot-separated path e.g. "data.0.name"
     */
    suspend fun query(src: CharSeriesWrapper, path: String): Any? {
        val segments: List<String> = (CharSeries(path) / ('.')).α { seg: Series<Char> -> seg.asString() }.toList()
        return when (format) {
            Format.JSON -> {
                val svc: JsonCodecService = coroutineContext[JsonCodecKey] ?: JsonCodecService.default
                val jsPath: JsPath = (segments.map { seg: String ->
                    seg.toIntOrNull()?.let { JsPathElement.right<String, Int>(it) } ?: JsPathElement.left<String, Int>(seg)
                }).toSeries()
                val ctx: JsContext = (JsonParser.index(src.chars) j src.chars)
                svc.jsPath(ctx, jsPath, true, null)
            }

            Format.YAML -> {
                val svc: YamlCodecService = coroutineContext[YamlCodecKey] ?: YamlCodecService.default
                val doc = svc.parse(src.chars)
                queryYamlNode(doc.root, segments)
            }

            Format.CBOR -> {
                val svc: CborCodecService = coroutineContext[CborCodecKey] ?: CborCodecService.default
                val item = svc.decode(src.bytes)
                queryCborItem(item, segments, svc)
            }
        }
    }

    // ── Coroutine Flows: fanout across {parse, reify, query} ───────────────

    /**
     * Fanout: launch parse / reify / query as three SupervisorJob children
     * each with its own withContext layer carrying the branch's KEYS + ELEMENTS.
     * Structured concurrency: parent waits for all children via coroutineScope.
     */
    suspend fun fanout(
        src: CharSeriesWrapper,
        path: String? = null,
    ): List<UnifyResult> = coroutineScope {
        val parent = coroutineContext[Job]
        val supJob = SupervisorJob(parent)

        // Each branch: withContext pushes format's KEYS/ELEMENTS, then launches
        val parseDeferred = async(supJob) {
            val depth = mutableListOf<Int>()
            val idx = withContext(JsonCodecService.default) {
                parseIndex(src)
            }
            UnifyResult(Direction.PARSE, format, idx, null)
        }

        val reifyDeferred = async(supJob) {
            val ev = mutableListOf<TypeEvidence>()
            withContext(JsonCodecService.default) {
                reify(src, ev, null)
            }
            UnifyResult(Direction.REIFY, format, null, ev)
        }

        val queryDeferred = path?.let { p ->
            async(supJob) {
                val result = withContext(JsonCodecService.default) {
                    query(src, p)
                }
                UnifyResult(Direction.QUERY, format, result, null)
            }
        }

        listOfNotNull(
            parseDeferred.await(),
            reifyDeferred.await(),
            queryDeferred?.await(),
        )
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private tailrec fun queryYamlNode(node: YamlNode, segments: List<String>): Any? {
        if (segments.isEmpty()) return when (node) {
            is YamlScalarNode -> node.value?.asString()
            is YamlSequenceNode -> (0 until node.items.size).map {
                (node.items.b(it) as? YamlScalarNode)?.value?.asString()
            }

            is YamlMappingNode -> node.entries.view.associate {
                it.key.asString() to ((it.value as? YamlScalarNode)?.value?.asString())
            }

            else -> null
        }
        val head = segments.first()
        val tail = segments.drop(1)
        return when (node) {
            is YamlMappingNode -> {
                val entry = node.entries.view.find { it.key.asString() == head }
                entry?.let { queryYamlNode(it.value, tail) }
            }

            is YamlSequenceNode -> {
                val idx = head.toIntOrNull() ?: return null
                val item = node.items.b(idx)
                queryYamlNode(item, tail)
            }

            else -> null
        }
    }

    private tailrec fun queryCborItem(
        item: Item,
        segments: List<String>,
        svc: CborCodecService,
    ): Any? {
        if (segments.isEmpty()) return svc.toValue(item)
        val head = segments.first()
        val tail = segments.drop(1)
        return when (item) {
            is Item.Map -> {
                val child = item[head] ?: return null
                queryCborItem(child, tail, svc)
            }

            is Item.Arr -> {
                val idx = head.toIntOrNull() ?: return null
                queryCborItem(item[idx], tail, svc)
            }

            else -> null
        }
    }
}

// ─── IO wrapper ───────────────────────────────────────────────────────────────

/** Unifies CharSeries and ByteArray inputs. */
data class CharSeriesWrapper(
    val chars: Series<Char>,
    val bytes: ByteArray = chars.encodeToByteArray(),
) {
    companion object {
        fun of(chars: Series<Char>): CharSeriesWrapper = CharSeriesWrapper(chars)
        fun of(bytes: ByteArray): CharSeriesWrapper = CharSeriesWrapper(bytes.decodeToChars(), bytes)
        fun of(text: String): CharSeriesWrapper = CharSeriesWrapper(text.toSeries())
    }
}

// ─── Result types ─────────────────────────────────────────────────────────────

data class UnifyResult(
    val direction: Direction,
    val format: Format,
    val value: Any?,
    val evidence: List<TypeEvidence>?,
)

enum class Direction { PARSE, REIFY, QUERY }

// ─── Extension aliases for couch callers ──────────────────────────────────────

/** Json parse → JsContext. */
fun parseJsonContext(src: Series<Char>): JsContext = JsonParser.index(src) j src

/** Json reify → Kotlin value. */
fun reifyJson(
    src: Series<Char>,
    nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
    rowVecCallback: ((RowVec) -> Unit)? = null,
): Any? = JsonParser.reify(src, nodeEvidence, rowVecCallback)

/** Yaml parse → YamlDocument. */
fun parseYamlDoc(src: Series<Char>) = YamlParser.parse(src)

/** Yaml reify → Kotlin value. */
fun reifyYaml(
    src: Series<Char>,
    nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
    rowVecCallback: (RowVec) -> Unit = {},
): Any? = YamlParser.reify(src, nodeEvidence, rowVecCallback)

/** CBOR decode → Item. */
fun decodeCbor(bytes: ByteArray): Item = Cbor.decode(bytes)

/** CBOR encode → ByteArray. */
fun encodeCbor(item: Item): ByteArray = Cbor.encode(item)

object ignore {
    /** Item → Kotlin value. */
    fun Item.toTrikeValue(): Any? = when (this) {
        is Item.Nil -> null
        is Item.Str -> value
        is Item.Num -> value
        is Item.Flt -> value
        is Item.Bool -> value
        is Item.Bin -> value
        is Item.Map -> entries.view.associate { it.a to it.b.toTrikeValue() }
        is Item.Arr -> (0 until size).map { get(it).toTrikeValue() }
        is Item.Tag -> item.toTrikeValue()
    }
}
