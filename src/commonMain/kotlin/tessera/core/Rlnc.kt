/*
 * Ported from Tessera — https://github.com/3x3xX3N0N/tessera (via jnorthrup/tessera @78bc042).
 * Copyright 2026 3x3xX3N0N. Licensed under the Apache License, Version 2.0; see tessera/NOTICE.md.
 *
 * Changes in TrikeShed: the off-heap window mirror / accumulator paths (Panama FFM) are removed —
 * every kernel here works on heap ByteArrays; `Frame.Repair(ByteBuffer)` is [RepairSymbol] with a
 * `ByteArray`; `java.util.Arrays.fill` is Kotlin's `fill`. The algebra, invariants and comments
 * are the upstream author's.
 */
package tessera.core

import kotlin.random.Random

/**
 * An RLNC repair symbol over the window `[windowBase, windowBase + windowLen)`. Coefficients are
 * regenerated from [seed] on both sides. [symbol] may be shorter than the coder's symbol size (the
 * transport trims trailing zeros); the decoder zero-extends it.
 *
 * Upstream this is `Frame.Repair`, written to the wire as `0x04 windowBase(8) windowLen(2) seed(4) len(2) symbol`;
 * the wire form follows with the frame codec in a later stage.
 */
class RepairSymbol(val windowBase: Long, val windowLen: Int, val seed: Int, val symbol: ByteArray) {
    init { require(windowLen in 0..0xFFFF) { "windowLen $windowLen exceeds the 16-bit wire field" } }
}

/**
 * The sender's sliding window: the last [maxWindow] symbols pushed, by contiguous `seq`.
 *
 * `repair(seed)` combines the whole window with coefficients `1 + Random(seed).nextInt(255)` in window order, and
 * describes the window as `[windowBase, windowBase + windowLen)`; [RlncDecoder.onRepair] regenerates the same
 * coefficients from the seed, so the window must be contiguous in `seq` — [push] checks it. The window slides inside
 * [push]; a repair always describes the window as it is at `repair()` time.
 *
 * Thread-safety: none — one thread at a time (the transport holds its connection lock around every call; its sender
 * thread pushes, its timer thread repairs). [push] keeps a reference to `data` (no copy), so `data` must not be
 * modified after the call.
 */
class RlncEncoder(private val symbolSize: Int, private val maxWindow: Int = 64) {
    private class Sym(val seq: Long, val data: ByteArray)
    private val window = ArrayDeque<Sym>()
    private var base = 0L

    init { require(symbolSize > 0 && maxWindow > 0) }

    fun push(seq: Long, data: ByteArray) {
        require(data.size == symbolSize) { "symbol of ${data.size} bytes, expected $symbolSize" }
        require(window.isEmpty() || seq == window.last().seq + 1) { "seq $seq does not follow ${window.last().seq}: the window must be contiguous" }
        if (window.isEmpty()) base = seq
        window.addLast(Sym(seq, data))
        while (window.size > maxWindow) { window.removeFirst(); base = window.first().seq }
    }

    fun repair(seed: Int): RepairSymbol {
        val rnd = Random(seed)
        val out = ByteArray(symbolSize)
        val k = GF256.kernel
        for (s in window) { val c = 1 + rnd.nextInt(255); k.mulAddInto(out, s.data, c) }
        return RepairSymbol(base, window.size, seed, out)
    }
}

/**
 * The receiver: incremental Gaussian elimination over whatever repairs and sources arrive, in any order.
 *
 * State is `known` (seq -> symbol; never evicted, the transport rotates decoders) and `pivots`: the open rows in
 * reduced row-echelon form, keyed by pivot seq. Invariants between calls — (I1) a row has coefficient 1 at its key,
 * (I2) no row has a non-zero coefficient at another row's key, (I3) no row references a known seq, (I4) every row has
 * at least two unknowns (a row down to one unknown is a solution and is learned at once). A repair is reduced against
 * the pivots, normalized on its lowest unknown and back-substituted into the others; a source is substituted into
 * every row that references it. When the source arrives for a seq that is already a pivot key — the transport's
 * residual ARQ re-sends lost sources verbatim, and reordering does the same — that row loses its pivot and is
 * re-inserted under a new one so that (I1)/(I2) hold again. (Before this was done, the row stayed under its old key
 * with no unit coefficient; a later repair's back-substitution could reduce it to one unknown, and its payload — a
 * GF(256) multiple of the true symbol — was learned unnormalized: a wrong solve that then spread into every row it
 * touched. The transport saw it as a recovered symbol with an out-of-range length prefix, ~1 in 5000 repair-decoded
 * symbols under loss + ARQ; the harness in the tests reproduces it at 2% of decoded symbols.)
 *
 * Integrity, at no wire cost: the optional [validator] vets every solved symbol before it is learned ([rejected]
 * counts refusals; a refused symbol never reaches another row), and a repair that reduces to no unknowns must leave a
 * zero remainder ([inconsistent] counts contradictions between a repair and the known symbols — corrupt input, or an
 * encoder/decoder window or seed mismatch). The validator is a few byte compares per solve; the zero check one pass
 * over the remainder of a redundant repair. Both counters are per instance: read them before dropping a decoder.
 *
 * Thread-safety: none — one thread at a time (the transport's rx thread, under the connection lock).
 */
class RlncDecoder(private val symbolSize: Int, private val validator: SymbolValidator? = null) {
    /**
     * Accepts or rejects a symbol the decoder solved (sources are trusted; they come authenticated). The transport can
     * check the length prefix and the FEC extension frame `0x80 0x02 fecSeq16` at the start of the body against `seq`:
     * no wrong solve that is a GF(256) multiple of the true symbol passes, since `c * 0x80 == 0x80` only for `c == 1`.
     */
    fun interface SymbolValidator { fun isValid(seq: Long, symbol: ByteArray): Boolean }

    /** Solved symbols the [validator] refused; they were not learned, so they cannot reach other rows. */
    var rejected = 0L; private set
    /** Repairs that reduced to no unknowns with a non-zero remainder: the known symbols and the repair contradict each other. */
    var inconsistent = 0L; private set

    private val known = HashMap<Long, ByteArray>()
    /** An open row: coefficient per unknown seq (never a known one), and the payload it equals. */
    private class Row(val coeffs: HashMap<Long, Int>, val payload: ByteArray)
    private val pivots = HashMap<Long, Row>()

    init { require(symbolSize > 0) }

    fun onSource(seq: Long, data: ByteArray) {
        require(data.size == symbolSize) { "symbol of ${data.size} bytes, expected $symbolSize" }
        learn(seq, data)
    }

    fun onRepair(r: RepairSymbol) {
        val rnd = Random(r.seed)
        val coeffs = HashMap<Long, Int>()
        val payload = ByteArray(symbolSize)
        r.symbol.copyInto(payload, 0, 0, minOf(symbolSize, r.symbol.size))
        val k = GF256.kernel
        for (i in 0 until r.windowLen) {
            val c = 1 + rnd.nextInt(255); val seq = r.windowBase + i
            val s = known[seq]
            if (s != null) k.mulAddInto(payload, s, c) else coeffs[seq] = c
        }
        insert(Row(coeffs, payload))
    }

    /** Reduces `row` (no known seqs in it) against the pivots and makes it one; then takes out whatever got solved. */
    private fun insert(row: Row) {
        // forward-eliminate against existing pivots; by (I2) this introduces no pivot seq, so the order is irrelevant
        for ((pseq, prow) in pivots) {
            val c = row.coeffs[pseq] ?: continue
            eliminate(row, prow, c)
        }
        if (row.coeffs.isEmpty()) { if (!isZero(row.payload)) inconsistent++; return }   // redundant: must reduce to nothing
        // normalize on lowest seq, make pivot, back-substitute into others
        val pseq = row.coeffs.keys.min()
        scale(row, GF256.inv(row.coeffs[pseq]!!))
        for (other in pivots.values) other.coeffs[pseq]?.let { eliminate(other, row, it) }
        pivots[pseq] = row
        solveSingles()
    }

    /** A pivot row down to one unknown is a solution: take it out (by its own key) and learn it; repeat while that completes others. */
    private fun solveSingles() {
        while (true) {
            val e = pivots.entries.firstOrNull { it.value.coeffs.size == 1 } ?: return
            pivots.remove(e.key)
            solved(e.value)
        }
    }

    /** `row` (out of the pivot set) has one unknown left: normalize it and learn it — unless the [validator] refuses it. */
    private fun solved(row: Row) {
        val s = row.coeffs.keys.first()
        val c = row.coeffs[s]!!
        if (c != 1) scale(row, GF256.inv(c))
        val have = known[s]
        when {
            have != null -> if (!have.contentEquals(row.payload)) inconsistent++   // solved twice: both ways must agree
            validator != null && !validator.isValid(s, row.payload) -> rejected++
            else -> learn(s, row.payload)
        }
    }

    /** `seq` is known: substitute it into every row that references it, then learn what that solved and re-pivot what lost its pivot. */
    private fun learn(seq: Long, data: ByteArray) {
        known[seq] = data
        val it = pivots.entries.iterator()
        var ready: ArrayList<Row>? = null     // down to one unknown: solutions
        var rePivot: ArrayList<Row>? = null   // `seq` was their pivot: they need a new one
        while (it.hasNext()) {
            val e = it.next()
            val row = e.value
            val c = row.coeffs.remove(seq) ?: continue
            GF256.mulAddInto(row.payload, data, c)
            when {
                row.coeffs.isEmpty() -> { it.remove(); if (!isZero(row.payload)) inconsistent++ }
                row.coeffs.size == 1 -> { it.remove(); ready = (ready ?: ArrayList()).also { l -> l += row } }
                e.key == seq -> { it.remove(); rePivot = (rePivot ?: ArrayList()).also { l -> l += row } }
            }
        }
        ready?.forEach { solved(it) }
        rePivot?.forEach { row -> substituteKnown(row); insert(row) }
    }

    /** Folds in whatever became known while `row` was out of the pivot set (I3 before it goes back in). */
    private fun substituteKnown(row: Row) {
        val it = row.coeffs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val d = known[e.key] ?: continue
            GF256.mulAddInto(row.payload, d, e.value)
            it.remove()
        }
    }

    /** target -= c * source  (pivot row is normalized, so this zeroes target's coefficient at source pivot) */
    private fun eliminate(target: Row, source: Row, c: Int) {
        for ((seq, sc) in source.coeffs) {
            val v = (target.coeffs[seq] ?: 0) xor GF256.mul(sc, c)
            if (v == 0) target.coeffs.remove(seq) else target.coeffs[seq] = v
        }
        GF256.mulAddInto(target.payload, source.payload, c)
    }
    private fun scale(row: Row, c: Int) {
        for (k in row.coeffs.keys.toList()) row.coeffs[k] = GF256.mul(row.coeffs[k]!!, c)
        val tmp = row.payload.copyOf(); row.payload.fill(0); GF256.mulAddInto(row.payload, tmp, c)
    }
    private fun isZero(a: ByteArray): Boolean { for (b in a) if (b != 0.toByte()) return false; return true }

    fun get(seq: Long): ByteArray? = known[seq]
}
