package borg.trikeshed.charstr

import borg.trikeshed.lib.*

// ── CharStr ─────────────────────────────────────────────────────
//
// A string is a point in TextK-space, and TextK-space is itself a Join algebra.
// The payload (CharSequence) is the witness; TextK is the question space;
// the lambda is the answer oracle.
//
// CharStr participates in Join algebra as a 1-row, infinitely-wide row
// keyed by computed properties, not by ordinal columns.

/** CharStr = MetaSeries<TextK<*>, Any?> = Join<TextK<*>, (TextK<*>) -> Any?> */
typealias CharStr = MetaSeries<TextK<*>, Any?>

// ── Construction ────────────────────────────────────────────────

/** Construct a CharStr from a raw CharSequence witness. */
fun CharStr(seq: CharSequence): CharStr = CharStrCached(seq)

/** Construct a CharStr from a Series<Char> span — lazy, zero-copy.
 *  The Series<Char> is wrapped as a CharSequence view. */
fun CharStr(src: Series<Char>, open: Int, closeInclusive: Int): CharStr {
    val len = closeInclusive - open + 1
    if (len <= 0) return CharStr("")
    val witness: CharSequence = object : CharSequence {
        override val length: Int get() = len
        override fun get(index: Int): Char = src[open + index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            val newLen = endIndex - startIndex
            if (newLen <= 0) return ""
            return CharStr(src, open + startIndex, open + endIndex - 1).let { it[TextK.Raw] as CharSequence }
        }
        override fun toString(): String {
            val ca = CharArray(len)
            for (i in 0 until len) ca[i] = src[open + i]
            return ca.concatToString()
        }
    }
    return CharStrCached(witness)
}

// ── GADT-safe accessors ─────────────────────────────────────────

/** Type-safe dispatch — one unchecked cast at the boundary. */
@Suppress("UNCHECKED_CAST")
operator fun <R> CharStr.get(op: TextK<R>): R = b(op) as R

val CharStr.raw: CharSequence          get() = this[TextK.Raw]
val CharStr.sizeBytes: Int             get() = this[TextK.SizeK.Bytes]
val CharStr.sizeCp: Int                get() = this[TextK.SizeK.Codepoints]
val CharStr.sizeGraphemes: Int         get() = this[TextK.SizeK.Graphemes]
val CharStr.sizeUtf16: Int             get() = this[TextK.SizeK.UTF16Units]
val CharStr.xxh3: Long                 get() = this[TextK.HashK.XXH3]
val CharStr.fnv1a: Long                get() = this[TextK.HashK.FNV1a]
val CharStr.nfc: CharStr               get() = this[TextK.NormK.NFC]
val CharStr.nfd: CharStr               get() = this[TextK.NormK.NFD]
val CharStr.caseFolded: CharStr        get() = this[TextK.CaseFold]

// ── TextK DAG — dependency graph IS a MetaSeries ────────────────

/** TextOp dependencies are a MetaSeries: TextK -> Set<TextK> */
typealias TextKDag = MetaSeries<TextK<*>, Set<TextK<*>>>

/** The dependency DAG. Every TextK declares what must be computed first. */
val TEXT_K_DEPS: Map<TextK<*>, Set<TextK<*>>> = mapOf(
    TextK.SizeK.Bytes      to setOf(TextK.Raw),
    TextK.SizeK.Codepoints to setOf(TextK.Raw),
    TextK.SizeK.Graphemes  to setOf(TextK.SizeK.Codepoints),
    TextK.SizeK.UTF16Units to setOf(TextK.Raw),
    TextK.HashK.XXH3       to setOf(TextK.Raw),
    TextK.HashK.FNV1a      to setOf(TextK.Raw),
    TextK.HashK.SipHash13  to setOf(TextK.Raw),
    TextK.HashK.CRC32C     to setOf(TextK.Raw),
    TextK.NormK.NFC        to setOf(TextK.SizeK.Codepoints),
    TextK.NormK.NFD        to setOf(TextK.SizeK.Codepoints),
    TextK.NormK.NFKC       to setOf(TextK.SizeK.Codepoints),
    TextK.NormK.NFKD       to setOf(TextK.SizeK.Codepoints),
    TextK.CaseFold         to setOf(TextK.NormK.NFC),
    TextK.Raw              to emptySet(),
)

/** DAG as a lookup function — the dependency graph is itself a Join. */
fun textKDag(op: TextK<*>): Set<TextK<*>> = TEXT_K_DEPS[op] ?: emptySet()

// ── Corpus ──────────────────────────────────────────────────────

/**
 * A corpus is a matrix: row = CharStr, column = TextK.
 * Adding an index = adding a TextK = adding a column. No code change downstream.
 */
typealias Corpus = Series<CharStr>

// ── CharStr as 1-row Cursor ─────────────────────────────────────

/**
 * A CharStr IS a 1-row cursor keyed by TextK.
 * This projection makes CharStr composable with Cursor combinators.
 */
fun CharStr.asSingleRowCursor(ops: Series<TextK<*>>): Series<Join<Any?, () -> Any?>> =
    ops.size j { c ->
        this[ops[c]] j { ops[c] as Any? }
    }
