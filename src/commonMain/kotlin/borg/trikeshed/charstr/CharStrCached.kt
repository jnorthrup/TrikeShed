@file:Suppress("INLINE_CLASS_DEPRECATED")

package borg.trikeshed.charstr

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.IOMemento
import kotlin.concurrent.Volatile

/**
 * Configurable hot-op set for CharStr memoization.
 *
 * Determines which TextK ops get explicit fields (scalarized by C2)
 * vs identity-map probe vs cold recompute.
 * Configured per Corpus, not globally — delays the canonical choice
 * until the workload characteristics are known.
 */
 inline  class HotTextKSet(val ops: Set<TextK<*>>) {
    companion object {
        /** Default hot set: NFC + XXH3 + Bytes — the canonical equality ops. */
        val DEFAULT = HotTextKSet(setOf(TextK.SizeK.Bytes, TextK.HashK.XXH3, TextK.NormK.NFC))
    }

    operator fun contains(op: TextK<*>): Boolean = op in ops
}

/**
 * CharStrCached — the dispatcher.
 *
 * Hot ops as explicit fields (scalarized by C2, ~1 load).
 * Warm ops via IdentityHashMap (1 volatile read + 1 probe).
 * Cold ops recompute (no cache, pure function).
 *
 * The dispatcher lambda is non-capturing: dispatch(seq, op) is a static function
 * to keep escape analysis happy and avoid closure allocation.
 */
class CharStrCached(
    internal val witness: CharSequence,
    private val hotSet: HotTextKSet = HotTextKSet.DEFAULT,
) : Join<TextK<*>, (TextK<*>) -> Any?> {

    // ── hot fields — explicit, scalarized by C2 ─────────────────
    // Racy-but-safe: same pattern as String.hash — one lazy field, computed once.
    @Volatile
    private var _sizeBytes: Int = -1
    @Volatile private var _sizeCp: Int = -1
    @Volatile private var _xxh3: Long = Long.MIN_VALUE

    // ── warm cache — identity-keyed map, 1 volatile read + 1 probe ──
    private val warmCache: MutableMap<TextK<*>, Any> = mutableMapOf()

    // ── evidence counts — allocated lazily, computed once ──
    // null = not yet allocated; sentinel -1 in slot 0 = allocated but not computed
    @Volatile
    private var _evidenceCounts: ShortArray? = null
    internal val evidenceCounts: ShortArray
        get() {
            val existing = _evidenceCounts
            if (existing != null) return existing
            val arr = ShortArray(16) { -1 }
            _evidenceCounts = arr
            return arr
        }

    override val a: TextK<*> get() = TextK.Raw
    override val b: (TextK<*>) -> Any? = { op -> resolve(op) }

    private fun resolve(op: TextK<*>): Any? = when (op) {
        TextK.Raw              -> witness
        TextK.SizeK.Bytes      -> sizeBytes()
        TextK.SizeK.Codepoints -> sizeCp()
        TextK.SizeK.UTF16Units -> witness.length
        TextK.SizeK.Graphemes  -> computeGraphemes()
        TextK.HashK.XXH3       -> xxh3()
        TextK.HashK.FNV1a      -> computeHash(op as TextK.HashK)
        TextK.HashK.SipHash13  -> computeHash(op as TextK.HashK)
        TextK.HashK.CRC32C     -> computeHash(op as TextK.HashK)
        is TextK.NormK         -> warmCache.getOrPut(op) { computeNorm(op) }
        TextK.CaseFold         -> warmCache.getOrPut(op) { computeCaseFold() }
        is TextK.RopeK         -> warmCache.getOrPut(op) { computeRope(op) }
        is TextK.NgramK<*>     -> computeNgram(op)
        is TextK.FingerprintK  -> computeFingerprint(op)
        is TextK.EvidenceK    -> computeEvidence(op as TextK.EvidenceK)
        TextK.MaxColumnLength  -> warmCache.getOrPut(op) { computeEvidenceMaxLen() }
        TextK.MinColumnLength  -> warmCache.getOrPut(op) { computeEvidenceMinLen() }
        TextK.DeducedIOMemento -> warmCache.getOrPut(op) { deduceIOMemento() }
    }

    // ── hot-path implementations ────────────────────────────────

    private fun sizeBytes(): Int {
        var s = _sizeBytes
        if (s == -1) {
            s = computeSizeBytes(witness)
            _sizeBytes = s
        }
        return s
    }

    private fun sizeCp(): Int {
        var s = _sizeCp
        if (s == -1) {
            s = computeCodepoints(witness)
            _sizeCp = s
        }
        return s
    }

    private fun xxh3(): Long {
        var h = _xxh3
        if (h == Long.MIN_VALUE) {
            h = computeXXH3(witness)
            _xxh3 = h
        }
        return h
    }

    // ── equality: canonical NFC witness + XXH3 hash ─────────────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharStrCached) return false
        // Fast path: hash compare
        if (xxh3() != other.xxh3()) return false
        // Slow path: NFC-normalized content compare
        return normalizedContent() == other.normalizedContent()
    }

    override fun hashCode(): Int = xxh3().toInt()

    private fun normalizedContent(): CharSequence {
        val norm = resolve(TextK.NormK.NFC)
        return if (norm is CharStrCached) norm.witness else witness
    }

    override fun toString(): String = witness.toString()
}

// ── Platform-hookable compute functions ─────────────────────────
// These are expect/actual candidates for KMP. Stubs for now.

internal fun computeSizeBytes(seq: CharSequence): Int =
    seq.toString().encodeToByteArray().size

internal fun computeCodepoints(seq: CharSequence): Int {
    var count = 0
    var i = 0
    val s = seq.toString()
    while (i < s.length) {
        val c = s[i]
        count++
        i += if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
    }
    return count
}

internal fun computeGraphemes(): Int {
    // Full grapheme cluster segmentation requires ICU or platform-specific impl.
    // Placeholder — returns codepoint count.
    return -1
}

internal fun computeXXH3(seq: CharSequence): Long {
    // XXH3 stub — proper impl is a platform expect/actual.
    // Uses FNV1a-64 as interim stand-in.
    var hash = -3750763034362895579L // FNV offset basis
    val bytes = seq.toString().encodeToByteArray()
    for (byte in bytes) {
        hash = hash xor byte.toLong()
        hash *= 1099511628211L // FNV prime
    }
    return hash
}

internal fun computeHash(op: TextK.HashK): Long {
    // Dispatch to the right hash family — platform expect/actual.
    return 0L
}

internal fun CharStrCached.computeNorm(op: TextK.NormK): CharStr {
    // Unicode normalization — platform expect/actual.
    // Returns self as identity for now.
    return this
}

internal fun CharStrCached.computeCaseFold(): CharStr {
    // Case fold depends on NFC — platform expect/actual.
    return CharStr(this.b(TextK.Raw).toString().lowercase())
}

internal fun computeRope(op: TextK.RopeK): RopeView {
    return object : RopeView {
        override val chunkCount: Int get() = 1
        override fun get(chunkIndex: Int): CharSequence = TODO("rope chunking")
        override fun iterator(): Iterator<CharSequence> = TODO("rope iteration")
    }
}

internal fun computeNgram(op: TextK.NgramK<*>): Any? {
    // N-gram computation — cold path, no cache.
    return null
}

internal fun computeFingerprint(op: TextK.FingerprintK): Long {
    // SimHash / MinHash — cold path, no cache.
    return 0L
}

// ── Evidence — single-pass character-class scan ─────────────────

/** Character-class categories, one bit per category. Ordinal determines bit position. */
enum class CharCategory : BitMasked<Short> {
    DIGIT,
    PERIOD,
    EXPONENT,
    SIGN,
    TRUEFALSE,
    ALPHA,
    DQUOTE,
    QUOTE,
    BACKSLASH,
    WHITESPACE,
    SPECIAL;

    override val mask: Short get() = (1 shl ordinal).toShort()

    companion object {
        /** Pre-computed lookup: each ASCII char → exactly one category mask. */
        val CHAR_CAT: ShortArray = ShortArray(128) { c ->
            when (c.toChar()) {
                in '0'..'9' -> DIGIT.mask
                '.' -> PERIOD.mask
                'e', 'E' -> EXPONENT.mask
                '+', '-' -> SIGN.mask
                't', 'r', 'u', 'f', 'a', 'l', 's',
                'T', 'R', 'U', 'F', 'A', 'L', 'S' -> TRUEFALSE.mask
                in 'a'..'z', in 'A'..'Z' -> ALPHA.mask
                '"' -> DQUOTE.mask
                '\'' -> QUOTE.mask
                '\\' -> BACKSLASH.mask
                ' ', '\t' -> WHITESPACE.mask
                else -> SPECIAL.mask
            }
        }
    }
}

/** Run evidence scan once, cache all counts. */
internal fun CharStrCached.computeEvidence(op: TextK.EvidenceK): UShort {
    val w = witness
    if (evidenceCounts[0] < 0) runEvidenceScan(w)
    return evidenceCounts[evidenceIndex(op)].toUShort()
}

private fun CharStrCached.runEvidenceScan(w: CharSequence) {
    val n = w.length
    val counts = IntArray(CharCategory.entries.size)
    val catTable = CharCategory.CHAR_CAT
    for (i in 0 until n) {
        val code = w[i].code
        val mask = if (code < 128) catTable[code] else CharCategory.SPECIAL.mask
        // mask = 1 shl ordinal → ordinal = trailingZeroBits(mask)
        val catIdx = mask.toInt().countTrailingZeroBits()
        if (catIdx < counts.size) counts[catIdx]++
    }
    // Store: evidence slots 0-10 = category counts, 11=empty, 12=linefeed
    evidenceCounts[0]  = counts[0].toShort()   // DIGIT
    evidenceCounts[1]  = counts[1].toShort()   // PERIOD
    evidenceCounts[2]  = counts[2].toShort()   // EXPONENT
    evidenceCounts[3]  = counts[3].toShort()   // SIGN
    evidenceCounts[4]  = counts[10].toShort()  // SPECIAL
    evidenceCounts[5]  = counts[5].toShort()   // ALPHA
    evidenceCounts[6]  = counts[4].toShort()   // TRUEFALSE
    evidenceCounts[7]  = 0                     // empty
    evidenceCounts[8]  = counts[7].toShort()   // QUOTE
    evidenceCounts[9]  = counts[6].toShort()   // DQUOTE
    evidenceCounts[10] = counts[9].toShort()   // WHITESPACE
    evidenceCounts[11] = counts[8].toShort()   // BACKSLASH
    evidenceCounts[12] = 0                     // linefeed
    evidenceCounts[13] = n.toShort()
    evidenceCounts[14] = n.toShort()
}

private fun evidenceIndex(op: TextK.EvidenceK): Int = when (op) {
    TextK.EvidenceK.Digits      -> 0
    TextK.EvidenceK.Periods     -> 1
    TextK.EvidenceK.Exponent    -> 2
    TextK.EvidenceK.Signs       -> 3
    TextK.EvidenceK.Special     -> 4
    TextK.EvidenceK.Alpha       -> 5
    TextK.EvidenceK.TrueFalse   -> 6
    TextK.EvidenceK.Empty       -> 7
    TextK.EvidenceK.Quotes      -> 8
    TextK.EvidenceK.DQuotes     -> 9
    TextK.EvidenceK.Whitespaces -> 10
    TextK.EvidenceK.Backslashes -> 11
    TextK.EvidenceK.Linefeed    -> 12
}

internal fun CharStrCached.computeEvidenceMaxLen(): UShort {
    if (evidenceCounts[0] < 0) runEvidenceScan(witness)
    return evidenceCounts[13].toUShort()
}

internal fun CharStrCached.computeEvidenceMinLen(): UShort {
    if (evidenceCounts[0] < 0) runEvidenceScan(witness)
    return evidenceCounts[14].toUShort()
}

internal fun CharStrCached.deduceIOMemento(): IOMemento {
    if (evidenceCounts[0] < 0) runEvidenceScan(witness)
    val dquotes = evidenceCounts[9].toInt()
    val quotes  = evidenceCounts[8].toInt()
    val empty   = evidenceCounts[7].toInt()
    val alpha   = evidenceCounts[5].toInt()
    val tf      = evidenceCounts[6].toInt()
    val digits  = evidenceCounts[0].toInt()
    val periods = evidenceCounts[1].toInt()
    val exp     = evidenceCounts[2].toInt()
    val signs   = evidenceCounts[3].toInt()
    val special = evidenceCounts[4].toInt()
    val maxLen  = evidenceCounts[13].toInt()

    return when {
        dquotes > 0 || quotes > 0 -> IOMemento.IoString
        empty > 0 || alpha > 0 -> IOMemento.IoString
        tf > 0 -> IOMemento.IoBoolean
        digits == 0 -> IOMemento.IoString
        periods == 0 && exp == 0 && signs <= 1 && special == 0 && maxLen <= 3 + signs -> IOMemento.IoDouble  // IoByte not in IOMemento
        periods == 0 && exp == 0 && signs <= 1 && special == 0 && maxLen <= 10 + signs -> IOMemento.IoInt
        periods == 0 && exp == 0 && signs <= 1 && special == 0 && maxLen <= 19 + signs -> IOMemento.IoLong
        periods == 1 && exp == 0 && signs <= 1 && special == 0 && maxLen <= 34 + signs -> IOMemento.IoDouble
        periods <= 1 && exp <= 1 && signs <= 1 && special == 0 && maxLen <= 66 + signs + exp -> IOMemento.IoDouble
        else -> IOMemento.IoString
    }
}
