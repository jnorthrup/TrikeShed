package borg.trikeshed.reactor.xet

/**
 * BLAKE3 (hash and keyed_hash modes, 32-byte output) in common Kotlin, including the chunk tree for inputs
 * longer than 1024 bytes. Xet needs keyed mode; `borg.trikeshed.patch.Blake3Hash` is a single-chain sketch
 * with no key and no tree, so it is not used here.
 */
internal object Blake3 {
    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024
    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8
    private const val KEYED_HASH = 16

    private val IV = intArrayOf(
        0x6A09E667, -0x4498517B, 0x3C6EF372, -0x5AB00AC6,
        0x510E527F, -0x64FA9774, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    fun hash(input: ByteArray): ByteArray = run(input, IV, 0)

    fun keyedHash(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "BLAKE3 key must be 32 bytes" }
        return run(input, wordsOf(key, 0, 32).copyOfRange(0, 8), KEYED_HASH)
    }

    private class Output(val cv: IntArray, val block: IntArray, val counter: Long, val blockLen: Int, val flags: Int) {
        fun chainingValue(): IntArray = compress(cv, block, counter, blockLen, flags).copyOfRange(0, 8)
        fun root(): ByteArray {
            val w = compress(cv, block, 0L, blockLen, flags or ROOT)
            val out = ByteArray(32)
            for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (w[i] ushr (8 * j)).toByte()
            return out
        }
    }

    private fun run(input: ByteArray, key: IntArray, baseFlags: Int): ByteArray {
        val chunks = if (input.isEmpty()) 1 else (input.size + CHUNK_LEN - 1) / CHUNK_LEN
        val stack = ArrayList<IntArray>()
        var total = 0L
        for (c in 0 until chunks - 1) {
            var cv = chunkOutput(input, c * CHUNK_LEN, CHUNK_LEN, key, c.toLong(), baseFlags).chainingValue()
            total++
            var t = total
            while (t and 1L == 0L) {
                cv = parent(stack.removeAt(stack.size - 1), cv, key, baseFlags).chainingValue()
                t = t shr 1
            }
            stack.add(cv)
        }
        val last = (chunks - 1) * CHUNK_LEN
        var out = chunkOutput(input, last, input.size - last, key, (chunks - 1).toLong(), baseFlags)
        while (stack.isNotEmpty()) out = parent(stack.removeAt(stack.size - 1), out.chainingValue(), key, baseFlags)
        return out.root()
    }

    private fun parent(left: IntArray, right: IntArray, key: IntArray, baseFlags: Int): Output {
        val block = IntArray(16)
        left.copyInto(block, 0)
        right.copyInto(block, 8)
        return Output(key, block, 0L, BLOCK_LEN, PARENT or baseFlags)
    }

    private fun chunkOutput(input: ByteArray, off: Int, len: Int, key: IntArray, counter: Long, baseFlags: Int): Output {
        var cv = key
        var pos = 0
        var first = true
        while (len - pos > BLOCK_LEN) {
            val flags = baseFlags or (if (first) CHUNK_START else 0)
            cv = compress(cv, wordsOf(input, off + pos, BLOCK_LEN), counter, BLOCK_LEN, flags).copyOfRange(0, 8)
            first = false
            pos += BLOCK_LEN
        }
        val rem = len - pos
        val flags = baseFlags or CHUNK_END or (if (first) CHUNK_START else 0)
        return Output(cv, wordsOf(input, off + pos, rem), counter, rem, flags)
    }

    /** Little-endian words of up to 64 bytes starting at [off]; missing bytes are zero. */
    private fun wordsOf(b: ByteArray, off: Int, len: Int): IntArray {
        val w = IntArray(16)
        for (i in 0 until len) w[i shr 2] = w[i shr 2] or ((b[off + i].toInt() and 0xFF) shl (8 * (i and 3)))
        return w
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        s[a] = s[a] + s[b] + mx
        s[d] = rotr(s[d] xor s[a], 16)
        s[c] = s[c] + s[d]
        s[b] = rotr(s[b] xor s[c], 12)
        s[a] = s[a] + s[b] + my
        s[d] = rotr(s[d] xor s[a], 8)
        s[c] = s[c] + s[d]
        s[b] = rotr(s[b] xor s[c], 7)
    }

    private fun round(s: IntArray, m: IntArray) {
        g(s, 0, 4, 8, 12, m[0], m[1])
        g(s, 1, 5, 9, 13, m[2], m[3])
        g(s, 2, 6, 10, 14, m[4], m[5])
        g(s, 3, 7, 11, 15, m[6], m[7])
        g(s, 0, 5, 10, 15, m[8], m[9])
        g(s, 1, 6, 11, 12, m[10], m[11])
        g(s, 2, 7, 8, 13, m[12], m[13])
        g(s, 3, 4, 9, 14, m[14], m[15])
    }

    private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
        val s = IntArray(16)
        cv.copyInto(s, 0, 0, 8)
        s[8] = IV[0]; s[9] = IV[1]; s[10] = IV[2]; s[11] = IV[3]
        s[12] = counter.toInt()
        s[13] = (counter ushr 32).toInt()
        s[14] = blockLen
        s[15] = flags
        var m = block.copyOf()
        for (r in 0 until 7) {
            round(s, m)
            if (r < 6) {
                val p = IntArray(16)
                for (i in 0 until 16) p[i] = m[PERMUTATION[i]]
                m = p
            }
        }
        for (i in 0 until 8) {
            s[i] = s[i] xor s[i + 8]
            s[i + 8] = s[i + 8] xor cv[i]
        }
        return s
    }
}
