package borg.trikeshed.tilting.zran

import kotlin.math.log2

/*
 * Zstandard (RFC 8878) primitives shared by ZstdDecoder and ZstdEncoder: little-endian
 * access, XXH64, both bitstream directions, FSE tables and Huffman tables.
 */

const val ZSTD_MAGIC: Int = -47205080 // 0xFD2FB528
const val ZSTD_SKIPPABLE: Int = 0x184D2A50 // low nibble is free
const val ZSTD_BLOCK_MAX: Int = 1 shl 17

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.le24(i: Int): Int = le16(i) or (u8(i + 2) shl 16)
fun ByteArray.le32(i: Int): Int = le16(i) or (le16(i + 2) shl 16)
fun ByteArray.le64(i: Int): Long = (le32(i).toLong() and 0xFFFFFFFFL) or (le32(i + 4).toLong() shl 32)
fun ByteArray.put16(i: Int, v: Int) { this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte() }
fun ByteArray.put24(i: Int, v: Int) { put16(i, v); this[i + 2] = (v ushr 16).toByte() }
fun ByteArray.put32(i: Int, v: Int) { put16(i, v); put16(i + 2, v ushr 16) }
fun ByteArray.put64(i: Int, v: Long) { put32(i, v.toInt()); put32(i + 4, (v ushr 32).toInt()) }

/** Index of the highest set bit; -1 for 0. */
fun highbit(v: Int): Int = 31 - v.countLeadingZeroBits()

// ---- XXH64 (frame Content_Checksum is its low 32 bits, seed 0) ----

const val XXH_P1: Long = -7046029288634856825L // 0x9E3779B185EBCA87
const val XXH_P2: Long = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
const val XXH_P3: Long = 1609587929392839161L // 0x165667B19E3779F9
const val XXH_P4: Long = -8796714831421723037L // 0x85EBCA77C2B2AE63
const val XXH_P5: Long = 2870177450012600261L // 0x27D4EB2F165667C5

fun xxhRound(acc: Long, input: Long): Long = (acc + input * XXH_P2).rotateLeft(31) * XXH_P1
fun xxhMerge(acc: Long, v: Long): Long = (acc xor xxhRound(0L, v)) * XXH_P1 + XXH_P4

fun xxh64(src: ByteArray, off: Int, len: Int, seed: Long = 0L): Long {
    var i = off
    val end = off + len
    var h: Long
    if (len >= 32) {
        var v1 = seed + XXH_P1 + XXH_P2
        var v2 = seed + XXH_P2
        var v3 = seed
        var v4 = seed - XXH_P1
        while (i <= end - 32) {
            v1 = xxhRound(v1, src.le64(i)); v2 = xxhRound(v2, src.le64(i + 8))
            v3 = xxhRound(v3, src.le64(i + 16)); v4 = xxhRound(v4, src.le64(i + 24))
            i += 32
        }
        h = v1.rotateLeft(1) + v2.rotateLeft(7) + v3.rotateLeft(12) + v4.rotateLeft(18)
        h = xxhMerge(h, v1); h = xxhMerge(h, v2); h = xxhMerge(h, v3); h = xxhMerge(h, v4)
    } else h = seed + XXH_P5
    h += len.toLong()
    while (i + 8 <= end) { h = (h xor xxhRound(0L, src.le64(i))).rotateLeft(27) * XXH_P1 + XXH_P4; i += 8 }
    if (i + 4 <= end) { h = (h xor ((src.le32(i).toLong() and 0xFFFFFFFFL) * XXH_P1)).rotateLeft(23) * XXH_P2 + XXH_P3; i += 4 }
    while (i < end) { h = (h xor (src.u8(i).toLong() * XXH_P5)).rotateLeft(11) * XXH_P1; i++ }
    h = (h xor (h ushr 33)) * XXH_P2
    h = (h xor (h ushr 29)) * XXH_P3
    return h xor (h ushr 32)
}

// ---- bitstreams (RFC 8878 §4.1) ----

/** Backward bitstream: read from the end toward [start]; the last byte's highest set bit is the end mark. */
class ReverseBits {
    var src: ByteArray = ByteArray(0)
    var start = 0
    var ptr = 0
    var bits = 0L
    var consumed = 0

    fun init(src: ByteArray, start: Int, end: Int): ReverseBits {
        check(end > start) { "zstd: empty bitstream" }
        val last = src.u8(end - 1)
        check(last != 0) { "zstd: bitstream end mark missing" }
        this.src = src
        this.start = start
        val mark = last.countLeadingZeroBits() - 23
        if (end - start >= 8) {
            ptr = end - 8
            bits = src.le64(ptr)
            consumed = mark
        } else {
            ptr = start
            var v = 0L
            for (k in 0 until end - start) v = v or (src.u8(start + k).toLong() shl (8 * k))
            bits = v
            consumed = mark + (8 - (end - start)) * 8
        }
        return this
    }

    /** Next [n] bits (1..31) without consuming; bits past [start] read as zero. */
    fun peek(n: Int): Int = if (consumed >= 64) 0 else ((bits shl consumed) ushr (64 - n)).toInt()

    fun read(n: Int): Int {
        if (n == 0) return 0
        val v = peek(n)
        consumed += n
        return v
    }

    fun reload() {
        if (consumed > 64) return
        if (ptr - start >= 8) {
            ptr -= consumed ushr 3
            consumed = consumed and 7
            bits = src.le64(ptr)
        } else if (ptr != start) {
            var nb = consumed ushr 3
            if (ptr - nb < start) nb = ptr - start
            ptr -= nb
            consumed -= nb shl 3
            bits = src.le64(ptr)
        }
    }

    val overflowed: Boolean get() = consumed > 64
    val finished: Boolean get() = ptr == start && consumed == 64
}

/** Forward bit accumulator writing little-endian bytes into [dst] from [pos]. */
class BitSink(var dst: ByteArray, var pos: Int) {
    var acc = 0L
    var n = 0

    fun add(value: Int, bits: Int) {
        acc = acc or ((value.toLong() and ((1L shl bits) - 1)) shl n)
        n += bits
        if (n >= 32) {
            ensure(4)
            dst.put32(pos, acc.toInt())
            pos += 4
            acc = acc ushr 32
            n -= 32
        }
    }

    fun ensure(k: Int) { if (pos + k > dst.size) dst = dst.copyOf(maxOf(dst.size * 2, pos + k + 64)) }

    /** Flushes partial bits to whole bytes; returns the end position. */
    fun flush(): Int {
        ensure(8)
        while (n > 0) { dst[pos++] = acc.toByte(); acc = acc ushr 8; n -= 8 }
        n = 0
        acc = 0
        return pos
    }

    /** Appends the end mark read first by [ReverseBits], then flushes. */
    fun close(): Int { add(1, 1); return flush() }
}

/** [n] (≤24) bits at bit offset [bit] of src[off, end), little-endian, zero past [end]. */
fun ByteArray.bitsAt(off: Int, end: Int, bit: Int, n: Int): Int {
    val b = off + (bit ushr 3)
    var w = 0
    for (k in 0 until 4) if (b + k < end) w = w or (u8(b + k) shl (8 * k))
    return (w ushr (bit and 7)) and ((1 shl n) - 1)
}

// ---- FSE ----

fun fseStep(size: Int): Int = (size ushr 1) + (size ushr 3) + 3

/** FSE decoding table; cell = symbol | nbBits shl 8 | baseline shl 16. */
class FseTable(maxLog: Int) {
    val cell = IntArray(1 shl maxLog)
    var log = 0
    val norm = IntArray(256)
    val next = IntArray(256)

    fun symbol(state: Int): Int = cell[state] and 0xFF
    fun update(state: Int, br: ReverseBits): Int { val c = cell[state]; return (c ushr 16) + br.read((c ushr 8) and 0xFF) }

    fun rle(symbol: Int) { log = 0; cell[0] = symbol }

    fun build(norm: IntArray, maxSymbol: Int, log: Int) {
        val size = 1 shl log
        var high = size - 1
        for (s in 0..maxSymbol) {
            val n = norm[s]
            if (n == -1) { cell[high--] = s; next[s] = 1 } else next[s] = n
        }
        val step = fseStep(size)
        val mask = size - 1
        var pos = 0
        for (s in 0..maxSymbol) repeat(norm[s]) {
            cell[pos] = s
            do pos = (pos + step) and mask while (pos > high)
        }
        check(pos == 0) { "zstd: FSE distribution does not fill its table" }
        for (u in 0 until size) {
            val s = cell[u] and 0xFF
            val ns = next[s]++
            val nb = log - highbit(ns)
            cell[u] = s or (nb shl 8) or (((ns shl nb) - size) shl 16)
        }
        this.log = log
    }

    /** Reads an FSE table description at src[off, end), builds the table, returns bytes read. */
    fun read(src: ByteArray, off: Int, end: Int, maxSymbol: Int, maxLog: Int): Int {
        var bit = 0
        val log = src.bitsAt(off, end, 0, 4) + 5
        bit += 4
        check(log <= maxLog) { "zstd: FSE accuracy log $log exceeds $maxLog" }
        var remaining = (1 shl log) + 1
        var threshold = 1 shl log
        var nbBits = log + 1
        var s = 0
        norm.fill(0, 0, maxSymbol + 1)
        while (remaining > 1 && s <= maxSymbol) {
            val max = 2 * threshold - 1 - remaining
            var count = src.bitsAt(off, end, bit, nbBits - 1)
            if (count < max) bit += nbBits - 1
            else {
                count = src.bitsAt(off, end, bit, nbBits)
                if (count >= threshold) count -= max
                bit += nbBits
            }
            count--
            remaining -= if (count < 0) -count else count
            norm[s++] = count
            if (count == 0) {
                while (true) {
                    val r = src.bitsAt(off, end, bit, 2)
                    bit += 2
                    s += r
                    if (r != 3) break
                }
                check(s <= maxSymbol + 1) { "zstd: FSE zero run past the alphabet" }
            }
            if (remaining < threshold) {
                if (remaining <= 1) break
                nbBits = highbit(remaining) + 1
                threshold = 1 shl (nbBits - 1)
            }
        }
        check(remaining == 1) { "zstd: FSE probabilities do not sum to the table" }
        val read = (bit + 7) ushr 3
        check(off + read <= end) { "zstd: FSE table description overruns its section" }
        build(norm, s - 1, log)
        return read
    }
}

/** FSE encoding table (tANS): state values live in [size, 2 * size). */
class FseEncoder(maxLog: Int) {
    val state = IntArray(1 shl maxLog)
    val deltaBits = IntArray(256)
    val deltaFind = IntArray(256)
    val spread = IntArray(1 shl maxLog)
    val cumul = IntArray(257)
    var log = 0

    fun build(norm: IntArray, maxSymbol: Int, log: Int) {
        this.log = log
        val size = 1 shl log
        val mask = size - 1
        var high = size - 1
        cumul[0] = 0
        for (u in 1..maxSymbol + 1) {
            val n = norm[u - 1]
            if (n == -1) { cumul[u] = cumul[u - 1] + 1; spread[high--] = u - 1 } else cumul[u] = cumul[u - 1] + n
        }
        val step = fseStep(size)
        var pos = 0
        for (s in 0..maxSymbol) repeat(norm[s]) {
            spread[pos] = s
            do pos = (pos + step) and mask while (pos > high)
        }
        for (u in 0 until size) state[cumul[spread[u]]++] = size + u
        var total = 0
        for (s in 0..maxSymbol) when (val n = norm[s]) {
            0 -> deltaBits[s] = ((log + 1) shl 16) - size
            -1, 1 -> { deltaBits[s] = (log shl 16) - size; deltaFind[s] = total - 1; total++ }
            else -> {
                val maxOut = log - highbit(n - 1)
                deltaBits[s] = (maxOut shl 16) - (n shl maxOut)
                deltaFind[s] = total - n
                total += n
            }
        }
    }

    /** Initial state for the last symbol in decode order; emits no bits. */
    fun init(symbol: Int): Int {
        val nbOut = (deltaBits[symbol] + (1 shl 15)) ushr 16
        val v = (nbOut shl 16) - deltaBits[symbol]
        return state[(v ushr nbOut) + deltaFind[symbol]]
    }

    fun encode(sink: BitSink, st: Int, symbol: Int): Int {
        val nbOut = (st + deltaBits[symbol]) ushr 16
        sink.add(st, nbOut)
        return state[(st ushr nbOut) + deltaFind[symbol]]
    }

    fun flush(sink: BitSink, st: Int) = sink.add(st, log)
}

/** Accuracy log for [total] samples over symbols 0..[maxSymbol] (FSE_optimalTableLog). */
fun fseLog(maxLog: Int, total: Int, maxSymbol: Int): Int {
    val srcBits = highbit(total - 1)
    var log = maxLog
    if (srcBits - 2 < log) log = srcBits - 2
    val minBits = minOf(srcBits + 1, highbit(maxSymbol) + 2)
    if (minBits > log) log = minBits
    return log.coerceIn(5, maxLog)
}

/** Normalized counts summing to 1 shl [log]; every present symbol keeps at least 1. */
fun fseNormalize(count: IntArray, maxSymbol: Int, total: Int, log: Int, norm: IntArray) {
    val size = 1 shl log
    var sum = 0
    var big = 0
    for (s in 0..maxSymbol) {
        val c = count[s]
        if (c == 0) { norm[s] = 0; continue }
        val n = ((c.toLong() * size + total / 2) / total).toInt().coerceAtLeast(1)
        norm[s] = n
        sum += n
        if (n > norm[big] || count[big] == 0) big = s
    }
    norm[big] += size - sum
    while (norm[big] < 1) {
        var donor = -1
        for (s in 0..maxSymbol) if (s != big && norm[s] > 1 && (donor < 0 || norm[s] > norm[donor])) donor = s
        check(donor >= 0) { "zstd: FSE normalization has no donor" }
        norm[donor]--
        norm[big]++
    }
}

/** Writes an FSE table description (inverse of [FseTable.read]); caller flushes to bytes. */
fun writeFseCounts(sink: BitSink, norm: IntArray, maxSymbol: Int, log: Int) {
    val size = 1 shl log
    var remaining = size + 1
    var threshold = size
    var nbBits = log + 1
    var s = 0
    var prev0 = false
    sink.add(log - 5, 4)
    while (s <= maxSymbol && remaining > 1) {
        if (prev0) {
            var start = s
            while (s <= maxSymbol && norm[s] == 0) s++
            check(s <= maxSymbol) { "zstd: FSE distribution ends in zeros" }
            while (s >= start + 24) { start += 24; sink.add(0xFFFF, 16) }
            while (s >= start + 3) { start += 3; sink.add(3, 2) }
            sink.add(s - start, 2)
        }
        var count = norm[s++]
        val max = 2 * threshold - 1 - remaining
        remaining -= if (count < 0) -count else count
        count++
        if (count >= threshold) count += max
        sink.add(count, if (count < max) nbBits - 1 else nbBits)
        prev0 = count == 1
        check(remaining >= 1) { "zstd: FSE distribution exceeds its table" }
        while (remaining < threshold) { nbBits--; threshold = threshold ushr 1 }
    }
    check(remaining == 1) { "zstd: FSE distribution does not sum to its table" }
}

/** Estimated bits to code [count] under [norm] at [log]; MAX_VALUE when a symbol is uncovered. */
fun fseCost(count: IntArray, maxSymbol: Int, norm: IntArray, normMax: Int, log: Int): Double {
    var bits = 0.0
    for (s in 0..maxSymbol) {
        val c = count[s]
        if (c == 0) continue
        if (s > normMax || norm[s] == 0) return Double.MAX_VALUE
        val n = if (norm[s] < 0) 1 else norm[s]
        bits += c * (log - log2(n.toDouble()))
    }
    return bits
}

// ---- sequence code tables (RFC 8878 §3.1.1.3.2.1) ----

val LL_BASE = intArrayOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536,
)
val LL_BITS = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
)
val ML_BASE = IntArray(53) { if (it < 32) it + 3 else 0 }.also {
    intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
        .copyInto(it, 32)
}
val ML_BITS = IntArray(53).also {
    intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16).copyInto(it, 32)
}
val LL_PREDEFINED = intArrayOf(
    4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
    -1, -1, -1, -1,
)
val ML_PREDEFINED = intArrayOf(
    1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
    -1, -1, -1, -1, -1,
)
val OF_PREDEFINED = intArrayOf(
    1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1,
)

val LL_PREDEFINED_TABLE: FseTable by lazy { FseTable(6).apply { build(LL_PREDEFINED, 35, 6) } }
val ML_PREDEFINED_TABLE: FseTable by lazy { FseTable(6).apply { build(ML_PREDEFINED, 52, 6) } }
val OF_PREDEFINED_TABLE: FseTable by lazy { FseTable(5).apply { build(OF_PREDEFINED, 28, 5) } }
val LL_PREDEFINED_ENCODER: FseEncoder by lazy { FseEncoder(6).apply { build(LL_PREDEFINED, 35, 6) } }
val ML_PREDEFINED_ENCODER: FseEncoder by lazy { FseEncoder(6).apply { build(ML_PREDEFINED, 52, 6) } }
val OF_PREDEFINED_ENCODER: FseEncoder by lazy { FseEncoder(5).apply { build(OF_PREDEFINED, 28, 5) } }

val LL_CODE = IntArray(64).also { t ->
    for (c in 0..35) for (v in LL_BASE[c] until LL_BASE[c] + (1 shl LL_BITS[c])) if (v < 64) t[v] = c
}
val ML_CODE = IntArray(128).also { t ->
    for (c in 0..52) for (v in ML_BASE[c] - 3 until ML_BASE[c] - 3 + (1 shl ML_BITS[c])) if (v < 128) t[v] = c
}

fun llCode(litLength: Int): Int = if (litLength > 63) highbit(litLength) + 19 else LL_CODE[litLength]
fun mlCode(matchLength: Int): Int { val m = matchLength - 3; return if (m > 127) highbit(m) + 36 else ML_CODE[m] }

// ---- Huffman (RFC 8878 §4.2) ----

const val HUF_LOG_MAX = 12

/** Huffman decoding table; cell = symbol | nbBits shl 8. */
class HufTable {
    val cell = IntArray(1 shl HUF_LOG_MAX)
    var log = 0
    val weights = IntArray(256)
    val rank = IntArray(HUF_LOG_MAX + 2)
    val fse = FseTable(6)
    val br = ReverseBits()

    /** Reads a Huffman tree description at src[off, end); returns bytes read. */
    fun readTree(src: ByteArray, off: Int, end: Int): Int {
        check(off < end) { "zstd: missing Huffman tree" }
        val header = src.u8(off)
        val count: Int
        val read: Int
        if (header >= 128) {
            count = header - 127
            read = 1 + (count + 1) / 2
            check(off + read <= end) { "zstd: Huffman weights overrun" }
            for (i in 0 until count) {
                val b = src.u8(off + 1 + i / 2)
                weights[i] = if (i and 1 == 0) b ushr 4 else b and 15
            }
        } else {
            read = 1 + header
            check(off + read <= end) { "zstd: Huffman weights overrun" }
            val h = fse.read(src, off + 1, off + read, 255, 6)
            count = decodeWeights(src, off + 1 + h, off + read)
        }
        var total = 0
        for (i in 0 until count) {
            val w = weights[i]
            check(w <= HUF_LOG_MAX) { "zstd: Huffman weight $w" }
            if (w > 0) total += 1 shl (w - 1)
        }
        check(total > 0) { "zstd: Huffman weights are all zero" }
        val log = highbit(total) + 1
        check(log <= HUF_LOG_MAX) { "zstd: Huffman depth $log" }
        val rest = (1 shl log) - total
        check(rest and (rest - 1) == 0) { "zstd: Huffman weights are not a complete tree" }
        weights[count] = highbit(rest) + 1
        val symbols = count + 1
        rank.fill(0)
        for (s in 0 until symbols) rank[weights[s]]++
        var next = 0
        for (w in 1..log) { val current = next; next += rank[w] shl (w - 1); rank[w] = current }
        for (s in 0 until symbols) {
            val w = weights[s]
            if (w == 0) continue
            val len = 1 shl (w - 1)
            val start = rank[w]
            cell.fill(s or ((log + 1 - w) shl 8), start, start + len)
            rank[w] = start + len
        }
        this.log = log
        return read
    }

    /** Two interleaved FSE states over one table; ends on bitstream overflow (§4.2.1.2). */
    fun decodeWeights(src: ByteArray, start: Int, end: Int): Int {
        val br = br.init(src, start, end)
        var s1 = br.read(fse.log)
        var s2 = br.read(fse.log)
        br.reload()
        var n = 0
        while (true) {
            check(n < 255) { "zstd: too many Huffman weights" }
            weights[n++] = fse.symbol(s1)
            s1 = fse.update(s1, br)
            br.reload()
            if (br.overflowed) { check(n < 255); weights[n++] = fse.symbol(s2); break }
            check(n < 255) { "zstd: too many Huffman weights" }
            weights[n++] = fse.symbol(s2)
            s2 = fse.update(s2, br)
            br.reload()
            if (br.overflowed) { check(n < 255); weights[n++] = fse.symbol(s1); break }
        }
        return n
    }

    /** Decodes [n] literals from one Huffman stream src[start, end) into dst[dOff..]. */
    fun decode(src: ByteArray, start: Int, end: Int, dst: ByteArray, dOff: Int, n: Int) {
        val br = br.init(src, start, end)
        val log = log
        val cell = cell
        val low = 64 - log
        for (i in dOff until dOff + n) {
            if (br.consumed > low) br.reload()
            val e = cell[br.peek(log)]
            dst[i] = e.toByte()
            br.consumed += e ushr 8
        }
        check(br.finished) { "zstd: Huffman stream not fully consumed" }
    }
}

/** Length-limited Huffman codes (package-merge) in the decoder's canonical order. */
class HufCodes {
    val code = IntArray(256)
    val len = IntArray(256)
    val weight = IntArray(256)
    var log = 0
    var maxSymbol = 0
    val order = IntArray(256)
    val rank = IntArray(HUF_LOG_MAX + 2)

    /** Builds codes for the symbols present in [count] (at least two); depth ≤ [maxBits]. */
    fun build(count: IntArray, maxSymbol: Int, maxBits: Int) {
        this.maxSymbol = maxSymbol
        var n = 0
        for (s in 0..maxSymbol) { len[s] = 0; if (count[s] > 0) order[n++] = s }
        check(n >= 2 && n <= 1 shl maxBits) { "zstd: Huffman needs 2..${1 shl maxBits} symbols" }
        // insertion sort by count: n ≤ 256
        for (i in 1 until n) {
            val s = order[i]
            var j = i - 1
            while (j >= 0 && count[order[j]] > count[s]) { order[j + 1] = order[j]; j-- }
            order[j + 1] = s
        }
        val leaf = LongArray(n) { count[order[it]].toLong() }
        val levels = arrayOfNulls<IntArray>(maxBits)
        var weights = leaf
        levels[0] = IntArray(n) { it }
        for (lv in 1 until maxBits) {
            val pc = weights.size / 2
            val m = n + pc
            val w = LongArray(m)
            val who = IntArray(m)
            var a = 0
            var b = 0
            for (k in 0 until m) {
                val pkg = if (b < pc) weights[2 * b] + weights[2 * b + 1] else Long.MAX_VALUE
                if (a < n && leaf[a] <= pkg) { w[k] = leaf[a]; who[k] = a; a++ } else { w[k] = pkg; who[k] = -1; b++ }
            }
            weights = w
            levels[lv] = who
        }
        var take = 2 * n - 2
        for (lv in maxBits - 1 downTo 0) {
            val who = levels[lv]!!
            var packages = 0
            for (k in 0 until take) { val li = who[k]; if (li >= 0) len[order[li]]++ else packages++ }
            take = 2 * packages
        }
        var log = 0
        for (s in 0..maxSymbol) if (len[s] > log) log = len[s]
        this.log = log
        rank.fill(0)
        for (s in 0..maxSymbol) { weight[s] = if (len[s] > 0) log + 1 - len[s] else 0; rank[weight[s]]++ }
        var next = 0
        for (w in 1..log) { val current = next; next += rank[w] shl (w - 1); rank[w] = current }
        check(next == 1 shl log) { "zstd: Huffman lengths are not a complete tree" }
        for (s in 0..maxSymbol) {
            val w = weight[s]
            if (w == 0) continue
            code[s] = rank[w] ushr (w - 1)
            rank[w] += 1 shl (w - 1)
        }
    }

    /** Encodes src[from, to) as one backward-readable Huffman stream; returns its end. */
    fun encode(sink: BitSink, src: ByteArray, from: Int, to: Int): Int {
        var i = to
        while (i > from) { val s = src.u8(--i); sink.add(code[s], len[s]) }
        return sink.close()
    }
}
