package borg.trikeshed.lcnc.reduction

import borg.trikeshed.lib.*

/**
 * Key extraction from a carrier element.
 */
@FunctionalInterface
interface KeyExtractor<T, K> {
    fun extract(input: T): K
}

/**
 * Key hierarchy for multi-level reduction (Forge keyHierarchy, Confix depth, CRMS callsiteHash).
 */
interface KeyHierarchy<K> {
    val levels: List<KeyExtractor<*, K>>  // ordered: outermost → innermost
    fun compositeKey(input: Any): List<K>  // [level0(key), level1(key), ...]
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
        override val levels: List<KeyExtractor<*, String>> = columns.map { col ->
            object : KeyExtractor<Map<String, Any>, String> {
                override fun extract(input: Map<String, Any>): String = (input[col] as? String) ?: ""
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

    data class ConfixStructuralKey(val depth: Int, val open: Int, val close: Int)

    fun confixStructuralKey(): KeyHierarchy<ConfixStructuralKey> = object : KeyHierarchy<ConfixStructuralKey> {
        override val levels: List<KeyExtractor<*, ConfixStructuralKey>> = listOf(
            object : KeyExtractor<SpanEvent, ConfixStructuralKey> {
                override fun extract(input: SpanEvent): ConfixStructuralKey =
                    ConfixStructuralKey(input.depth, input.span.start, input.span.endInclusive)
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

    fun crmsCallsiteHash(): KeyExtractor<TraceEvent, Int> = object : KeyExtractor<TraceEvent, Int> {
        override fun extract(input: TraceEvent): Int {
            // FNV-1a 32-bit
            var hash = 0x811c9dc5
            hash = (hash xor input.opcode) * 0x01000193
            hash = (hash xor input.methodIdx) * 0x01000193
            hash = (hash xor input.siteIdx) * 0x01000193
            return hash
        }
    }

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

/** Placeholder for TraceEvent — actual type from CRMS. */
data class TraceEvent(val opcode: Int, val methodIdx: Int, val siteIdx: Int)