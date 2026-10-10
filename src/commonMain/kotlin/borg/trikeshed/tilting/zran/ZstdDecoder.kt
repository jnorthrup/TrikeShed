package borg.trikeshed.tilting.zran

/** RFC 8878 frame decoder. Reusable: one instance keeps its literal buffer and tables. */
class ZstdDecoder {
    var out: ByteArray = ByteArray(0)
    var op = 0
    var growable = true
    val lit = ByteArray(ZSTD_BLOCK_MAX)
    var litLen = 0
    val huf = HufTable()
    var hufReady = false
    val llOwn = FseTable(9)
    val ofOwn = FseTable(8)
    val mlOwn = FseTable(9)
    var ll: FseTable? = null
    var of: FseTable? = null
    var ml: FseTable? = null
    val rep = IntArray(3)
    val br = ReverseBits()

    /** Decodes every frame in src[off, end), skipping skippable frames. */
    fun decompress(src: ByteArray, off: Int = 0, end: Int = src.size): ByteArray {
        out = ByteArray(maxOf(64, minOf(end - off, 1 shl 20) * 4))
        op = 0
        growable = true
        var p = off
        while (p < end) p = frame(src, p, end)
        return out.copyOf(op)
    }

    /** Decodes the single frame at src[off, end) into exactly dst[dOff, dOff + size); returns the frame end. */
    fun decodeInto(src: ByteArray, off: Int, end: Int, dst: ByteArray, dOff: Int, size: Int): Int {
        out = dst
        op = dOff
        growable = false
        val p = frame(src, off, end, dOff + size)
        check(op == dOff + size) { "zstd: frame content ${op - dOff} differs from expected $size" }
        return p
    }

    fun ensure(n: Int, limit: Int) {
        if (op + n <= out.size && op + n <= limit) return
        check(growable && op + n <= limit) { "zstd: content exceeds its destination" }
        out = out.copyOf(maxOf(out.size * 2, op + n))
    }

    /** Decodes one frame starting at its magic; returns the position after it. */
    fun frame(src: ByteArray, start: Int, end: Int, limit: Int = Int.MAX_VALUE): Int {
        var p = start
        check(end - p >= 4) { "zstd: truncated frame magic" }
        val magic = src.le32(p)
        if (magic and -16 == ZSTD_SKIPPABLE) {
            check(end - p >= 8) { "zstd: truncated skippable frame" }
            val size = src.le32(p + 4).toLong() and 0xFFFFFFFFL
            check(size <= end - p - 8) { "zstd: skippable frame overruns input" }
            return p + 8 + size.toInt()
        }
        check(magic == ZSTD_MAGIC) { "zstd: bad frame magic" }
        p += 4
        check(p < end) { "zstd: truncated frame header" }
        val fhd = src.u8(p++)
        val fcsFlag = fhd ushr 6
        val single = (fhd ushr 5) and 1 == 1
        check(fhd and 0x08 == 0) { "zstd: reserved frame header bit" }
        val checksum = (fhd ushr 2) and 1 == 1
        val dictBytes = when (fhd and 3) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
        val fcsBytes = when (fcsFlag) { 0 -> if (single) 1 else 0; 1 -> 2; 2 -> 4; else -> 8 }
        check(end - p >= (if (single) 0 else 1) + dictBytes + fcsBytes) { "zstd: truncated frame header" }
        var window = 0L
        if (!single) {
            val wd = src.u8(p++)
            val base = 1L shl (10 + (wd ushr 3))
            window = base + (base / 8) * (wd and 7)
        }
        var dict = 0L
        for (k in 0 until dictBytes) dict = dict or (src.u8(p + k).toLong() shl (8 * k))
        check(dict == 0L) { "zstd: dictionary frames are unsupported" }
        p += dictBytes
        var fcs = -1L
        if (fcsBytes > 0) {
            fcs = 0L
            for (k in 0 until fcsBytes) fcs = fcs or (src.u8(p + k).toLong() shl (8 * k))
            if (fcsBytes == 2) fcs += 256
            p += fcsBytes
        }
        if (single) window = fcs
        val blockMax = minOf(window, ZSTD_BLOCK_MAX.toLong()).toInt()
        rep[0] = 1; rep[1] = 4; rep[2] = 8
        hufReady = false
        ll = null; of = null; ml = null
        val frameStart = op
        if (fcs in 0..Int.MAX_VALUE.toLong()) {
            check(op + fcs <= limit) { "zstd: frame content exceeds its destination" }
            if (growable && op + fcs > out.size) out = out.copyOf((op + fcs).toInt())
        }
        while (true) {
            check(end - p >= 3) { "zstd: truncated block header" }
            val bh = src.le24(p)
            p += 3
            val size = bh ushr 3
            when ((bh ushr 1) and 3) {
                0 -> {
                    check(size <= blockMax && size <= end - p) { "zstd: raw block size" }
                    ensure(size, limit)
                    src.copyInto(out, op, p, p + size)
                    op += size
                    p += size
                }
                1 -> {
                    check(size <= blockMax && p < end) { "zstd: RLE block size" }
                    ensure(size, limit)
                    out.fill(src[p], op, op + size)
                    op += size
                    p += 1
                }
                2 -> {
                    check(size <= blockMax && size <= end - p) { "zstd: compressed block size" }
                    val blockStart = op
                    block(src, p, p + size, frameStart, limit)
                    check(op - blockStart <= blockMax) { "zstd: block content exceeds the block maximum" }
                    p += size
                }
                else -> error("zstd: reserved block type")
            }
            if (bh and 1 == 1) break
        }
        if (fcs >= 0) check(op - frameStart.toLong() == fcs) { "zstd: frame content size mismatch" }
        if (checksum) {
            check(end - p >= 4) { "zstd: truncated content checksum" }
            check(xxh64(out, frameStart, op - frameStart).toInt() == src.le32(p)) { "zstd: content checksum mismatch" }
            p += 4
        }
        return p
    }

    fun block(src: ByteArray, start: Int, end: Int, frameStart: Int, limit: Int) {
        val p = literals(src, start, end)
        sequences(src, p, end, frameStart, limit)
    }

    /** Decodes the literals section into [lit]; returns the position after it. */
    fun literals(src: ByteArray, start: Int, end: Int): Int {
        var p = start
        check(p < end) { "zstd: missing literals section" }
        val b0 = src.u8(p)
        val type = b0 and 3
        val sf = (b0 ushr 2) and 3
        if (type < 2) {
            val size: Int
            when (sf) {
                0, 2 -> { size = b0 ushr 3; p += 1 }
                1 -> { check(end - p >= 2); size = (b0 ushr 4) or (src.u8(p + 1) shl 4); p += 2 }
                else -> { check(end - p >= 3); size = (b0 ushr 4) or (src.u8(p + 1) shl 4) or (src.u8(p + 2) shl 12); p += 3 }
            }
            check(size <= ZSTD_BLOCK_MAX) { "zstd: literals size" }
            if (type == 0) {
                check(end - p >= size) { "zstd: raw literals overrun" }
                src.copyInto(lit, 0, p, p + size)
                p += size
            } else {
                check(p < end) { "zstd: RLE literals overrun" }
                lit.fill(src[p], 0, size)
                p += 1
            }
            litLen = size
            return p
        }
        val regen: Int
        val csize: Int
        when (sf) {
            0, 1 -> { check(end - p >= 3); val h = src.le24(p); regen = (h ushr 4) and 0x3FF; csize = (h ushr 14) and 0x3FF; p += 3 }
            2 -> { check(end - p >= 4); val h = src.le32(p); regen = (h ushr 4) and 0x3FFF; csize = (h ushr 18) and 0x3FFF; p += 4 }
            else -> { check(end - p >= 5); val h = src.le32(p); regen = (h ushr 4) and 0x3FFFF; csize = (h ushr 22) or (src.u8(p + 4) shl 10); p += 5 }
        }
        check(regen <= ZSTD_BLOCK_MAX && csize <= end - p) { "zstd: compressed literals size" }
        val cend = p + csize
        if (type == 2) { p += huf.readTree(src, p, cend); hufReady = true } else check(hufReady) { "zstd: treeless literals without a previous tree" }
        if (sf == 0) huf.decode(src, p, cend, lit, 0, regen)
        else {
            check(cend - p >= 10 && regen >= 6) { "zstd: 4-stream literals too small" }
            val e1 = p + 6 + src.le16(p)
            val e2 = e1 + src.le16(p + 2)
            val e3 = e2 + src.le16(p + 4)
            check(e3 < cend) { "zstd: Huffman jump table overruns" }
            val seg = (regen + 3) / 4
            huf.decode(src, p + 6, e1, lit, 0, seg)
            huf.decode(src, e1, e2, lit, seg, seg)
            huf.decode(src, e2, e3, lit, 2 * seg, seg)
            huf.decode(src, e3, cend, lit, 3 * seg, regen - 3 * seg)
        }
        litLen = regen
        return cend
    }

    fun table(own: FseTable, previous: FseTable?, predefined: FseTable, mode: Int, src: ByteArray, p: Int, end: Int,
              maxSymbol: Int, maxLog: Int, set: (FseTable) -> Unit): Int = when (mode) {
        0 -> { set(predefined); p }
        1 -> {
            check(p < end) { "zstd: truncated RLE sequence table" }
            val s = src.u8(p)
            check(s <= maxSymbol) { "zstd: RLE sequence symbol $s" }
            own.rle(s)
            set(own)
            p + 1
        }
        2 -> { val n = own.read(src, p, end, maxSymbol, maxLog); set(own); p + n }
        else -> { set(checkNotNull(previous) { "zstd: repeat mode without a previous table" }); p }
    }

    fun sequences(src: ByteArray, start: Int, end: Int, frameStart: Int, limit: Int) {
        var p = start
        check(p < end) { "zstd: missing sequences section" }
        val b0 = src.u8(p++)
        val nbSeq = when {
            b0 < 128 -> b0
            b0 < 255 -> { check(p < end); ((b0 - 128) shl 8) + src.u8(p++) }
            else -> { check(end - p >= 2); val v = src.le16(p) + 0x7F00; p += 2; v }
        }
        var lp = 0
        if (nbSeq > 0) {
            check(p < end) { "zstd: missing sequence modes" }
            val modes = src.u8(p++)
            check(modes and 3 == 0) { "zstd: reserved sequence mode bits" }
            p = table(llOwn, ll, LL_PREDEFINED_TABLE, (modes ushr 6) and 3, src, p, end, 35, 9) { ll = it }
            p = table(ofOwn, of, OF_PREDEFINED_TABLE, (modes ushr 4) and 3, src, p, end, 31, 8) { of = it }
            p = table(mlOwn, ml, ML_PREDEFINED_TABLE, (modes ushr 2) and 3, src, p, end, 52, 9) { ml = it }
            val llT = ll!!
            val ofT = of!!
            val mlT = ml!!
            val br = br.init(src, p, end)
            val mlBase = ML_BASE
            val mlBits = ML_BITS
            val llBase = LL_BASE
            val llBits = LL_BITS
            var llS = br.read(llT.log)
            var ofS = br.read(ofT.log)
            var mlS = br.read(mlT.log)
            br.reload()
            for (k in 0 until nbSeq) {
                val ofCode = ofT.symbol(ofS)
                val mlC = mlT.symbol(mlS)
                val llC = llT.symbol(llS)
                check(ofCode <= 31) { "zstd: offset code $ofCode" }
                // Each group below reads at most 32 bits; refill only when fewer remain.
                val ov = (1L shl ofCode) + (br.read(ofCode).toLong() and 0xFFFFFFFFL)
                if (br.consumed > 32) br.reload()
                val mlen = mlBase[mlC] + br.read(mlBits[mlC])
                val llen = llBase[llC] + br.read(llBits[llC])
                if (br.consumed > 32) br.reload()
                if (k < nbSeq - 1) {
                    llS = llT.update(llS, br)
                    mlS = mlT.update(mlS, br)
                    ofS = ofT.update(ofS, br)
                    if (br.consumed > 32) br.reload()
                }
                val off = offset(ov, llen)
                check(lp + llen <= litLen) { "zstd: sequence literals overrun" }
                ensure(llen + mlen, limit)
                // Short copies move 16 bytes in two 8-byte words while that stays inside the destination.
                val wideEnd = minOf(limit, out.size) - 16
                if (llen <= 16 && op <= wideEnd && lp + 16 <= lit.size) {
                    out.put64(op, lit.le64(lp))
                    out.put64(op + 8, lit.le64(lp + 8))
                } else lit.copyInto(out, op, lp, lp + llen)
                op += llen
                lp += llen
                check(off <= op - frameStart) { "zstd: match offset $off beyond the window" }
                var from = op - off
                if (mlen <= 16 && off >= 8 && op <= wideEnd) {
                    out.put64(op, out.le64(from))
                    out.put64(op + 8, out.le64(from + 8))
                    op += mlen
                } else if (off >= mlen) { out.copyInto(out, op, from, from + mlen); op += mlen }
                else repeat(mlen) { out[op++] = out[from++] }
            }
            check(br.finished) { "zstd: sequence bitstream not fully consumed" }
        }
        val rest = litLen - lp
        ensure(rest, limit)
        lit.copyInto(out, op, lp, litLen)
        op += rest
    }

    /** Resolves an Offset_Value against the repeat offsets (RFC 8878 §3.1.2.5). */
    fun offset(ov: Long, litLength: Int): Int {
        if (ov > 3) {
            val o = ov - 3
            check(o <= Int.MAX_VALUE) { "zstd: offset $o" }
            rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = o.toInt()
            return rep[0]
        }
        val idx = ov.toInt() - 1 + (if (litLength == 0) 1 else 0)
        if (idx == 0) return rep[0]
        val o = if (idx == 3) rep[0] - 1 else rep[idx]
        check(o > 0) { "zstd: zero repeat offset" }
        if (idx != 1) rep[2] = rep[1]
        rep[1] = rep[0]
        rep[0] = o
        return o
    }
}

fun zstdDecompress(src: ByteArray, off: Int = 0, end: Int = src.size): ByteArray = ZstdDecoder().decompress(src, off, end)
