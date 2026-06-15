package borg.trikeshed.lcnc.reduction

import borg.trikeshed.lib.*

/**
 * Key extraction from a carrier element. `fun interface` allows lambda construction.
 */
fun interface KeyExtractor<T, K> {
    fun extract(input: T): K
}

/**
 * Key hierarchy for multi-level reduction (Forge keyHierarchy, Confix depth, CRMS callsiteHash).
 */
interface KeyHierarchy<K> {
    val levels: List<KeyExtractor<Any, K>>  // ordered: outermost → innermost (accept Any, cast internally)
    fun compositeKey(input: Any): List<K>   // [level0(key), level1(key), ...]
    fun prefix(key: List<K>, depth: Int): List<K>  // for rereduce grouping
}

/**
 * Key ordering for sort/group operations.
 */
interface KeyOrder<K> {
    fun compare(a: K, b: K): Int
    fun equiv(a: K, b: K): Boolean = compare(a, b) == 0
}

/**
 * Combined key algebra interface.
 */
interface KeyAlg<K> {
    val extractor: KeyExtractor<Any, K>
    val hierarchy: KeyHierarchy<K>
    val order: KeyOrder<K>
}

/**
 * Confix structural key = (depth, open, close). Top-level so [LcncReductions] and
 * [ConfixReducers] can reference it unqualified.
 */
data class ConfixStructuralKey(val depth: Int, val open: Int, val close: Int)

/**
 * Default implementations and factories.
 */
object LcncKeyAlg {

    /** Universal key type — can represent Forge composite, Confix structural, CRMS hash. */
    sealed class LcncKey {
        data class Composite(val parts: List<String>) : LcncKey()      // Forge
        data class Structural(val depth: Int, val open: Int, val close: Int) : LcncKey()  // Confix
        data class Hashed(val hash: Int) : LcncKey()                   // CRMS
        data class Custom(val value: Any) : LcncKey()                  // Extensibility
    }

    /** Key codec for wire proto / serialization. */
    interface LcncKeyCodec<K : LcncKey> {
        fun encode(key: K): ByteArray
        fun decode(bytes: ByteArray): K
    }

    // ── Forge: composite string key from keyHierarchy columns ─────

    fun forgeKeyHierarchy(columns: List<String>): KeyHierarchy<String> = object : KeyHierarchy<String> {
        override val levels: List<KeyExtractor<Any, String>> = columns.map { col ->
            KeyExtractor<Any, String> { input ->
                (input as? Map<String, Any>)?.get(col) as? String ?: ""
            }
        }

        override fun compositeKey(input: Any): List<String> {
            val map = input as? Map<String, Any> ?: return emptyList()
            return levels.map { it.extract(map) }
        }

        override fun prefix(key: List<String>, depth: Int): List<String> =
            key.take(minOf(depth, key.size))
    }

    // ── Confix: structural key = (depth, span.open, span.close) ──

    fun confixStructuralKey(): KeyHierarchy<ConfixStructuralKey> = object : KeyHierarchy<ConfixStructuralKey> {
        override val levels: List<KeyExtractor<Any, ConfixStructuralKey>> = listOf(
            KeyExtractor<Any, ConfixStructuralKey> { input ->
                val event = input as? SpanEvent
                    ?: return@KeyExtractor ConfixStructuralKey(0, 0, 0)
                ConfixStructuralKey(event.depth, event.span.start, event.span.endInclusive)
            }
        )

        override fun compositeKey(input: Any): List<ConfixStructuralKey> {
            val event = input as? SpanEvent ?: return emptyList()
            return listOf(levels[0].extract(event))
        }

        override fun prefix(key: List<ConfixStructuralKey>, depth: Int): List<ConfixStructuralKey> =
            key.take(minOf(depth, key.size))
    }

    // ── CRMS: 32-bit FNV-1a hash of (opcode, methodIdx, siteIdx) ──

    fun crmsCallsiteHash(): KeyExtractor<TraceEvent, Int> = KeyExtractor { input ->
        // FNV-1a 32-bit
        var hash = FNV_OFFSET
        hash = (hash xor input.opcode) * FNV_PRIME
        hash = (hash xor input.methodIdx) * FNV_PRIME
        hash = (hash xor input.siteIdx) * FNV_PRIME
        hash
    }

    private const val FNV_OFFSET: Int = -2128831035  // 0x811c9dc5 as signed Int
    private const val FNV_PRIME: Int = 0x01000193

    /** Standard natural ordering for Comparable keys. */
    fun <K : Comparable<K>> naturalKeyOrder(): KeyOrder<K> = object : KeyOrder<K> {
        override fun compare(a: K, b: K): Int = a.compareTo(b)
    }

    /** Reverse ordering. */
    fun <K : Comparable<K>> reverseKeyOrder(): KeyOrder<K> = object : KeyOrder<K> {
        override fun compare(a: K, b: K): Int = b.compareTo(a)
    }
}

/** Placeholder for SpanEvent — actual type from Confix parser. */
data class SpanEvent(val depth: Int, val span: Span) {
    data class Span(val start: Int, val endInclusive: Int)
}

/** Placeholder for TraceEvent — actual type from CRMS. Defaults keep the 3-arg ctor
 *  used by tests while allowing latency/timestamp access in CRMS reducers. */
data class TraceEvent(
    val opcode: Int,
    val methodIdx: Int,
    val siteIdx: Int,
    val latencyNanos: Long = 0L,
    val timestampNanos: Long = 0L
)
