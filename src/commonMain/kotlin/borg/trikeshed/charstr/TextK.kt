package borg.trikeshed.charstr

import borg.trikeshed.lib.*
import borg.trikeshed.isam.meta.IOMemento

/**
 * TextK — GADT-style sealed hierarchy for text facets.
 *
 * R = result type. SizeK yields Int, HashK yields Long, NormK yields CharStr, etc.
 * The sealed hierarchy is the key-space; CharStr is the MetaSeries indexed by it.
 *
 * Hot TextK set must stay ≤3 for bimorphic JIT inlining.
 * Beyond that, vtable dispatch costs ~1-2ns per call.
 */
sealed class TextK<out R> : OpK<R>() {

    sealed class SizeK : TextK<Int>() {
        data object Bytes      : SizeK()
        data object Codepoints : SizeK()
        data object Graphemes  : SizeK()
        data object UTF16Units : SizeK()
    }

    sealed class HashK : TextK<Long>() {
        data object XXH3      : HashK()
        data object FNV1a     : HashK()
        data object SipHash13 : HashK()
        data object CRC32C    : HashK()
    }

    sealed class NormK : TextK<CharStr>() {
        data object NFC  : NormK()
        data object NFD  : NormK()
        data object NFKC : NormK()
        data object NFKD : NormK()
    }

    /** Case fold — depends on NormK.NFC. */
    data object CaseFold : TextK<CharStr>()

    sealed class RopeK : TextK<RopeView>() {
        data class Chunk(val targetBytes: Int) : RopeK()
    }

    sealed class NgramK<out R> : TextK<R>() {
        data class CharNgram(val n: Int) : NgramK<Series<CharSequence>>()
        data class WordNgram(val n: Int) : NgramK<Series<CharSequence>>()
    }

    sealed class FingerprintK : TextK<Long>() {
        data object SimHash : FingerprintK()
        data object MinHash : FingerprintK()
    }

    /** The raw witness — identity TextK, returns the CharSequence itself. */
    data object Raw : TextK<CharSequence>()

    // ── Evidence — character-class counts (single-pass, memoized) ─

    /**
     * EvidenceK — character-class frequency facets.
     * Counted in one pass over the witness; all counts memoized together.
     * Used to deduce the most specific IOMemento for type inference.
     */
    sealed class EvidenceK : TextK<UShort>() {
        data object Digits      : EvidenceK()
        data object Periods     : EvidenceK()
        data object Exponent    : EvidenceK()
        data object Signs       : EvidenceK()
        data object Special     : EvidenceK()
        data object Alpha       : EvidenceK()
        data object TrueFalse   : EvidenceK()
        data object Empty       : EvidenceK()
        data object Quotes      : EvidenceK()
        data object DQuotes     : EvidenceK()
        data object Whitespaces : EvidenceK()
        data object Backslashes : EvidenceK()
        data object Linefeed    : EvidenceK()
    }

    /** Maximum column length observed — Evidence pass. */
    data object MaxColumnLength : TextK<UShort>()

    /** Minimum column length observed — Evidence pass. */
    data object MinColumnLength : TextK<UShort>()

    /**
     * Deduced IOMemento — computed from EvidenceK counts.
     * Collapses character-class evidence into the most specific numeric
     * or structural type (IoByte, IoShort, IoInt, IoLong, IoFloat,
     * IoDouble, IoBoolean, IoString).
     */
    data object DeducedIOMemento : TextK<IOMemento>()
}

/**
 * RopeView — chunked iterator view of a CharSequence.
 * The underlying CharSequence doesn't change; the rope is a reified algebra element.
 */
interface RopeView : Iterable<CharSequence> {
    val chunkCount: Int
    operator fun get(chunkIndex: Int): CharSequence
}
