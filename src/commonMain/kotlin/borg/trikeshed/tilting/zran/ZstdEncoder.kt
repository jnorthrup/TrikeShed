package borg.trikeshed.tilting.zran

/**
 * RFC 8878 frame encoder: hash-chain LZ77 with lazy matching and repeat offsets, Huffman
 * literals, FSE sequences. Matches stay inside their block, so every block is self-contained.
 * Reusable: one instance keeps its match tables and entropy scratch.
 */
class ZstdEncoder(val depth: Int = 16) {
    var head = IntArray(0)
    var chain = IntArray(0)
    var hashLog = 0
    var nbSeq = 0
    var seqLL = IntArray(256)
    var seqML = IntArray(256)
    var seqOV = IntArray(256)
    val lits = ByteArray(ZSTD_BLOCK_MAX)
    var litN = 0
    val rep = IntArray(3)
    val saved = IntArray(3)
    /** Worst case: literals (n) plus n / 4 sequences of at most 89 bits each, below 4n. */
    val body = ByteArray(4 * ZSTD_BLOCK_MAX + 1024)
    var codes = IntArray(0)
    val hist = IntArray(256)
    val norm = IntArray(256)
    val huf = HufCodes()
    val hufBuf = ByteArray(2 * ZSTD_BLOCK_MAX + 1024)
    val llEnc = FseEncoder(9)
    val ofEnc = FseEncoder(8)
    val mlEnc = FseEncoder(9)
    val wEnc = FseEncoder(6)

    /** One frame holding src[off, off + len); Single_Segment when it fits one block. */
    fun compress(src: ByteArray, off: Int = 0, len: Int = src.size - off, checksum: Boolean = true): ByteArray {
        var dst = ByteArray(len + (len ushr 7) + 64)
        var p = 0
        dst.put32(p, ZSTD_MAGIC); p += 4
        val single = len <= ZSTD_BLOCK_MAX
        val fcsFlag = when {
            single && len < 256 -> 0
            len < 65536 + 256 -> 1
            else -> 2
        }
        dst[p++] = ((fcsFlag shl 6) or ((if (single) 1 else 0) shl 5) or ((if (checksum) 1 else 0) shl 2)).toByte()
        if (!single) dst[p++] = (7 shl 3).toByte() // window 128 KiB
        when (fcsFlag) {
            0 -> dst[p++] = len.toByte()
            1 -> { dst.put16(p, len - 256); p += 2 }
            else -> { dst.put32(p, len); p += 4 }
        }
        rep[0] = 1; rep[1] = 4; rep[2] = 8
        var at = off
        val end = off + len
        do {
            val n = minOf(ZSTD_BLOCK_MAX, end - at)
            val last = at + n == end
            if (dst.size < p + n + 8 + 2 * 3) dst = dst.copyOf(maxOf(dst.size * 2, p + n + 64))
            p = block(src, at, n, last, dst, p)
            at += n
        } while (at < end)
        if (checksum) {
            if (dst.size < p + 4) dst = dst.copyOf(p + 4)
            dst.put32(p, xxh64(src, off, len).toInt()); p += 4
        }
        return dst.copyOf(p)
    }

    /** Emits one block at dst[p]: compressed when smaller, else RLE or raw. */
    fun block(src: ByteArray, start: Int, n: Int, last: Boolean, dst: ByteArray, p: Int): Int {
        val lastBit = if (last) 1 else 0
        if (n >= 32) {
            rep.copyInto(saved)
            sequences(src, start, n)
            val size = entropy()
            if (size in 1 until n) {
                dst.put24(p, lastBit or (2 shl 1) or (size shl 3))
                body.copyInto(dst, p + 3, 0, size)
                return p + 3 + size
            }
            saved.copyInto(rep)
        }
        val b = if (n > 0) src[start] else 0
        var same = n > 1
        for (i in start until start + n) if (src[i] != b) { same = false; break }
        if (same) {
            dst.put24(p, lastBit or (1 shl 1) or (n shl 3))
            dst[p + 3] = b
            return p + 4
        }
        dst.put24(p, lastBit or (n shl 3))
        src.copyInto(dst, p + 3, start, start + n)
        return p + 3 + n
    }

    // ---- match finding ----

    fun hash(src: ByteArray, i: Int): Int = (src.le32(i) * -1640531535) ushr (32 - hashLog)

    fun matchLength(src: ByteArray, a: Int, b: Int, end: Int): Int {
        var l = 0
        while (b + l + 8 <= end && src.le64(a + l) == src.le64(b + l)) l += 8
        while (b + l < end && src[a + l] == src[b + l]) l++
        return l
    }

    /** Offset_Value [off] would code as after [litLength] literals, without moving the repeat offsets. */
    fun offsetValue(off: Int, litLength: Int): Int = if (litLength > 0) when (off) {
        rep[0] -> 1
        rep[1] -> 2
        rep[2] -> 3
        else -> off + 3
    } else when (off) {
        rep[1] -> 1
        rep[2] -> 2
        rep[0] - 1 -> 3
        else -> off + 3
    }

    /** Codes [off] and moves the repeat offsets exactly as [ZstdDecoder.offset] will. */
    fun codeOffset(off: Int, litLength: Int): Int {
        val ov = offsetValue(off, litLength)
        val idx = if (ov > 3) 4 else ov - 1 + (if (litLength == 0) 1 else 0)
        if (idx != 0) {
            if (idx != 1) rep[2] = rep[1]
            rep[1] = rep[0]
            rep[0] = off
        }
        return ov
    }

    var bestLen = 0
    var bestOff = 0
    var bestGain = 0

    /** Longest match at [i] over the repeat offsets and the hash chain; fills best*. */
    fun search(src: ByteArray, start: Int, end: Int, i: Int, litLength: Int) {
        bestLen = 0
        bestOff = 0
        bestGain = Int.MIN_VALUE
        val window = i - start
        for (r in 0 until 3) {
            val o = if (litLength == 0) (if (r == 2) rep[0] - 1 else rep[r + 1]) else rep[r]
            if (o < 1 || o > window) continue
            val l = matchLength(src, i - o, i, end)
            if (l >= 4) consider(l, o, litLength)
        }
        var c = head[hash(src, i)]
        var d = depth
        while (c >= start && d-- > 0) {
            val o = i - c
            if (o > 0 && (bestLen == 0 || (i + bestLen < end && src[c + bestLen] == src[i + bestLen]))) {
                val l = matchLength(src, c, i, end)
                if (l >= 4) consider(l, o, litLength)
            }
            c = chain[c - start]
        }
    }

    fun consider(l: Int, o: Int, litLength: Int) {
        val gain = l * 4 - highbit(offsetValue(o, litLength))
        if (gain > bestGain) { bestGain = gain; bestLen = l; bestOff = o }
    }

    /** Parses src[start, start + n) into sequences and literals. */
    fun sequences(src: ByteArray, start: Int, n: Int) {
        val end = start + n
        hashLog = (highbit(n) + 1).coerceIn(10, 17)
        if (head.size != 1 shl hashLog) head = IntArray(1 shl hashLog)
        head.fill(-1)
        if (chain.size < n) chain = IntArray(n)
        nbSeq = 0
        litN = 0
        var anchor = start
        var i = start
        var inserted = start
        val last = end - 4
        while (i <= last) {
            while (inserted < i) { val h = hash(src, inserted); chain[inserted - start] = head[h]; head[h] = inserted; inserted++ }
            search(src, start, end, i, i - anchor)
            if (bestLen < 4) { i++; continue }
            var len = bestLen
            var off = bestOff
            var gain = bestGain
            while (i + 1 <= last) {
                val h = hash(src, i); if (inserted == i) { chain[i - start] = head[h]; head[h] = i; inserted++ }
                search(src, start, end, i + 1, i + 1 - anchor)
                if (bestLen >= 4 && bestGain > gain + 4) { i++; len = bestLen; off = bestOff; gain = bestGain } else break
            }
            emit(src, anchor, i, len, off)
            i += len
            anchor = i
        }
        val tail = end - anchor
        src.copyInto(lits, litN, anchor, end)
        litN += tail
    }

    fun emit(src: ByteArray, anchor: Int, i: Int, len: Int, off: Int) {
        val ll = i - anchor
        src.copyInto(lits, litN, anchor, i)
        litN += ll
        if (nbSeq == seqLL.size) {
            seqLL = seqLL.copyOf(nbSeq * 2); seqML = seqML.copyOf(nbSeq * 2); seqOV = seqOV.copyOf(nbSeq * 2)
        }
        seqLL[nbSeq] = ll
        seqML[nbSeq] = len
        seqOV[nbSeq] = codeOffset(off, ll)
        nbSeq++
    }

    // ---- entropy stage ----

    /** Literals and sequences sections into [body]; returns their size. */
    fun entropy(): Int {
        var p = literals(body, 0)
        p = sequencesSection(body, p)
        return p
    }

    fun litHeader(dst: ByteArray, p: Int, type: Int, size: Int): Int = when {
        size < 32 -> { dst[p] = (type or (size shl 3)).toByte(); p + 1 }
        size < 4096 -> { dst[p] = (type or (1 shl 2) or ((size and 15) shl 4)).toByte(); dst[p + 1] = (size ushr 4).toByte(); p + 2 }
        else -> {
            dst[p] = (type or (3 shl 2) or ((size and 15) shl 4)).toByte()
            dst[p + 1] = (size ushr 4).toByte(); dst[p + 2] = (size ushr 12).toByte(); p + 3
        }
    }

    fun literals(dst: ByteArray, p: Int): Int {
        val n = litN
        if (n == 0) { dst[p] = 0; return p + 1 }
        val b = lits[0]
        var same = true
        for (i in 1 until n) if (lits[i] != b) { same = false; break }
        if (same) { val q = litHeader(dst, p, 1, n); dst[q] = b; return q + 1 }
        if (n >= 64) { val q = huffmanLiterals(dst, p); if (q > 0) return q }
        val q = litHeader(dst, p, 0, n)
        lits.copyInto(dst, q, 0, n)
        return q + n
    }

    /** Huffman-coded literals at dst[p]; returns the end, or -1 when raw is no larger. */
    fun huffmanLiterals(dst: ByteArray, p: Int): Int {
        val n = litN
        hist.fill(0)
        for (i in 0 until n) hist[lits.u8(i)]++
        var maxSym = 0
        for (s in 0..255) if (hist[s] > 0) maxSym = s
        huf.build(hist, maxSym, 11)
        var q = tree(hufBuf, 0)
        if (q < 0) return -1
        val sink = BitSink(hufBuf, q)
        val four = n >= 256
        if (!four) q = huf.encode(sink, lits, 0, n)
        else {
            val seg = (n + 3) / 4
            val jump = q
            sink.pos = q + 6
            val e1 = huf.encode(sink, lits, 0, seg)
            val e2 = huf.encode(sink, lits, seg, 2 * seg)
            val e3 = huf.encode(sink, lits, 2 * seg, 3 * seg)
            q = huf.encode(sink, lits, 3 * seg, n)
            hufBuf.put16(jump, e1 - jump - 6); hufBuf.put16(jump + 2, e2 - e1); hufBuf.put16(jump + 4, e3 - e2)
        }
        val csize = q
        val sf = when {
            !four -> 0
            n < 1024 && csize < 1024 -> 1
            n < 16384 && csize < 16384 -> 2
            else -> 3
        }
        val header = when (sf) { 0, 1 -> 3; 2 -> 4; else -> 5 }
        if (header + csize >= n + (if (n < 32) 1 else if (n < 4096) 2 else 3)) return -1
        when (sf) {
            0, 1 -> dst.put24(p, 2 or (sf shl 2) or (n shl 4) or (csize shl 14))
            2 -> dst.put32(p, 2 or (2 shl 2) or (n shl 4) or (csize shl 18))
            else -> { dst.put32(p, 2 or (3 shl 2) or (n shl 4) or (csize shl 22)); dst[p + 4] = (csize ushr 10).toByte() }
        }
        hufBuf.copyInto(dst, p + header, 0, csize)
        return p + header + csize
    }

    /** Huffman tree description of [huf] at dst[p]; returns its end or -1 when unrepresentable. */
    fun tree(dst: ByteArray, p: Int): Int {
        val nw = huf.maxSymbol
        val w = huf.weight
        val fse = fseWeights(dst, p + 1, nw)
        if (fse in 2 until 128 && fse < nw / 2) { dst[p] = fse.toByte(); return p + 1 + fse }
        if (nw > 128) return -1
        dst[p] = (127 + nw).toByte()
        var k = 0
        while (k < nw) {
            dst[p + 1 + k / 2] = ((w[k] shl 4) or (if (k + 1 < nw) w[k + 1] else 0)).toByte()
            k += 2
        }
        return p + 1 + (nw + 1) / 2
    }

    /** FSE-compressed Huffman weights (two interleaved states); 0/1 when not compressible. */
    fun fseWeights(dst: ByteArray, p: Int, nw: Int): Int {
        if (nw < 2) return 0
        val w = huf.weight
        val count = IntArray(HUF_LOG_MAX + 1)
        var maxW = 0
        for (k in 0 until nw) { count[w[k]]++; if (w[k] > maxW) maxW = w[k] }
        var maxCount = 0
        for (c in count) if (c > maxCount) maxCount = c
        if (maxCount == nw) return 1
        if (maxCount == 1) return 0
        val log = fseLog(6, nw, maxW)
        fseNormalize(count, maxW, nw, log, norm)
        val sink = BitSink(dst, p)
        writeFseCounts(sink, norm, maxW, log)
        val h = sink.flush()
        wEnc.build(norm, maxW, log)
        var i = nw
        var s1: Int
        var s2: Int
        if (nw and 1 == 1) {
            s1 = wEnc.init(w[--i]); s2 = wEnc.init(w[--i]); s1 = wEnc.encode(sink, s1, w[--i])
        } else {
            s2 = wEnc.init(w[--i]); s1 = wEnc.init(w[--i])
        }
        while (i > 0) {
            s2 = wEnc.encode(sink, s2, w[--i])
            s1 = wEnc.encode(sink, s1, w[--i])
        }
        wEnc.flush(sink, s2)
        wEnc.flush(sink, s1)
        check(sink.dst === dst) { "zstd: weight buffer grew" }
        return sink.close() - p
    }

    /**
     * Chooses predefined, RLE or FSE-compressed coding for one code stream, writing an FSE table
     * description at dst[p]; returns mode shl 24 or the description end.
     */
    fun codeTable(dst: ByteArray, p: Int, base: Int, maxSymbol: Int, maxLog: Int, predefined: IntArray, predefinedLog: Int, enc: FseEncoder): Int {
        hist.fill(0, 0, maxSymbol + 1)
        var maxCode = 0
        for (k in 0 until nbSeq) { val c = codes[base + k]; hist[c]++; if (c > maxCode) maxCode = c }
        var distinct = 0
        for (c in 0..maxCode) if (hist[c] > 0) distinct++
        val predefinedBits = fseCost(hist, maxCode, predefined, predefined.size - 1, predefinedLog)
        if (distinct == 1) {
            if (nbSeq <= 2 && predefinedBits != Double.MAX_VALUE) return p
            dst[p] = maxCode.toByte()
            return (1 shl 24) or (p + 1)
        }
        val log = fseLog(maxLog, nbSeq, maxCode)
        fseNormalize(hist, maxCode, nbSeq, log, norm)
        val sink = BitSink(dst, p)
        writeFseCounts(sink, norm, maxCode, log)
        val q = sink.flush()
        val bits = fseCost(hist, maxCode, norm, maxCode, log) + 8.0 * (q - p)
        if (predefinedBits <= bits) return p
        enc.build(norm, maxCode, log)
        return (2 shl 24) or q
    }

    fun sequencesSection(dst: ByteArray, start: Int): Int {
        var p = start
        val n = nbSeq
        when {
            n < 128 -> dst[p++] = n.toByte()
            n < 0x7F00 -> { dst[p++] = ((n ushr 8) + 128).toByte(); dst[p++] = n.toByte() }
            else -> { dst[p++] = 255.toByte(); dst.put16(p, n - 0x7F00); p += 2 }
        }
        if (n == 0) return p
        if (codes.size < 3 * n) codes = IntArray(3 * n + 64)
        for (k in 0 until n) {
            codes[k] = llCode(seqLL[k])
            codes[n + k] = highbit(seqOV[k])
            codes[2 * n + k] = mlCode(seqML[k])
        }
        val modesAt = p++
        val llR = codeTable(dst, p, 0, 35, 9, LL_PREDEFINED, 6, llEnc); p = llR and 0xFFFFFF
        val ofR = codeTable(dst, p, n, 31, 8, OF_PREDEFINED, 5, ofEnc); p = ofR and 0xFFFFFF
        val mlR = codeTable(dst, p, 2 * n, 52, 9, ML_PREDEFINED, 6, mlEnc); p = mlR and 0xFFFFFF
        val llMode = llR ushr 24
        val ofMode = ofR ushr 24
        val mlMode = mlR ushr 24
        dst[modesAt] = ((llMode shl 6) or (ofMode shl 4) or (mlMode shl 2)).toByte()
        val llE = if (llMode == 2) llEnc else LL_PREDEFINED_ENCODER
        val ofE = if (ofMode == 2) ofEnc else OF_PREDEFINED_ENCODER
        val mlE = if (mlMode == 2) mlEnc else ML_PREDEFINED_ENCODER
        val sink = BitSink(dst, p)
        val k0 = n - 1
        var sLL = if (llMode == 1) 0 else llE.init(codes[k0])
        var sOF = if (ofMode == 1) 0 else ofE.init(codes[n + k0])
        var sML = if (mlMode == 1) 0 else mlE.init(codes[2 * n + k0])
        extras(sink, k0, n)
        for (k in n - 2 downTo 0) {
            if (ofMode != 1) sOF = ofE.encode(sink, sOF, codes[n + k])
            if (mlMode != 1) sML = mlE.encode(sink, sML, codes[2 * n + k])
            if (llMode != 1) sLL = llE.encode(sink, sLL, codes[k])
            extras(sink, k, n)
        }
        if (mlMode != 1) mlE.flush(sink, sML)
        if (ofMode != 1) ofE.flush(sink, sOF)
        if (llMode != 1) llE.flush(sink, sLL)
        check(sink.dst === dst) { "zstd: block buffer grew" }
        return sink.close()
    }

    fun extras(sink: BitSink, k: Int, n: Int) {
        val llc = codes[k]
        val mlc = codes[2 * n + k]
        val ofc = codes[n + k]
        sink.add(seqLL[k] - LL_BASE[llc], LL_BITS[llc])
        sink.add(seqML[k] - ML_BASE[mlc], ML_BITS[mlc])
        sink.add(seqOV[k] - (1 shl ofc), ofc)
    }
}

fun zstdCompress(src: ByteArray, off: Int = 0, len: Int = src.size - off): ByteArray = ZstdEncoder().compress(src, off, len)
