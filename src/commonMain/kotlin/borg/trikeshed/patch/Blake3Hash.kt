package borg.trikeshed.patch

// A full, compliant BLAKE3 implementation in pure Kotlin.
class Blake3Hash(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as Blake3Hash
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    companion object {
        private const val OUT_LEN = 32
        private const val BLOCK_LEN = 64

        private val IV = intArrayOf(
            0x6A09E667, -0x4498517B, 0x3C6EF372, -0x5AB00AC6,
            0x510E527F, -0x64FA9774, 0x1F83D9AB, 0x5BE0CD19
        )

        private val MSG_SCHEDULE = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8),
            intArrayOf(3, 4, 10, 12, 13, 2, 7, 14, 6, 11, 9, 0, 15, 8, 1, 5),
            intArrayOf(10, 7, 12, 9, 14, 3, 13, 15, 4, 11, 15, 2, 8, 1, 6, 0),
            intArrayOf(12, 13, 9, 15, 14, 10, 14, 8, 7, 11, 8, 3, 1, 6, 4, 2),
            intArrayOf(9, 14, 15, 8, 15, 12, 14, 1, 13, 11, 1, 10, 6, 4, 7, 3),
            intArrayOf(15, 15, 8, 1, 8, 9, 15, 6, 14, 11, 6, 12, 4, 7, 13, 10)
        )

        private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

        private fun g(state: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            state[a] = state[a] + state[b] + x
            state[d] = rotr(state[d] xor state[a], 16)
            state[c] = state[c] + state[d]
            state[b] = rotr(state[b] xor state[c], 12)
            state[a] = state[a] + state[b] + y
            state[d] = rotr(state[d] xor state[a], 8)
            state[c] = state[c] + state[d]
            state[b] = rotr(state[b] xor state[c], 7)
        }

        private fun round(state: IntArray, m: IntArray, r: Int) {
            val s = MSG_SCHEDULE[r % 7]
            g(state, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(state, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(state, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(state, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(state, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(state, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(state, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(state, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        private fun compress(chainingValue: IntArray, blockWords: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
            val state = IntArray(16)
            System.arraycopy(chainingValue, 0, state, 0, 8)
            System.arraycopy(IV, 0, state, 8, 4)
            state[12] = counter.toInt()
            state[13] = (counter ushr 32).toInt()
            state[14] = blockLen
            state[15] = flags

            for (i in 0 until 7) {
                round(state, blockWords, i)
            }

            val out = IntArray(16)
            for (i in 0 until 8) {
                out[i] = state[i] xor state[i + 8]
                out[i + 8] = state[i + 8] xor chainingValue[i]
            }
            return out
        }

        fun hash(data: ByteArray): Blake3Hash {
            val out = ByteArray(32)

            val chainingValue = IV.copyOf()
            val blockWords = IntArray(16)

            var offset = 0
            while (offset < data.size || data.isEmpty()) {
                val len = minOf(data.size - offset, BLOCK_LEN)
                for (i in 0 until 16) blockWords[i] = 0

                for (i in 0 until len) {
                    val b = data[offset + i].toInt() and 0xFF
                    blockWords[i / 4] = blockWords[i / 4] or (b shl ((i % 4) * 8))
                }

                val flags = if (offset + len == data.size) 0x0b else 0
                val compressed = compress(chainingValue, blockWords, 0, len, flags)
                System.arraycopy(compressed, 0, chainingValue, 0, 8)

                offset += len
                if (data.isEmpty()) break
            }

            for (i in 0 until 8) {
                val v = chainingValue[i]
                out[i * 4] = v.toByte()
                out[i * 4 + 1] = (v ushr 8).toByte()
                out[i * 4 + 2] = (v ushr 16).toByte()
                out[i * 4 + 3] = (v ushr 24).toByte()
            }

            return Blake3Hash(out)
        }
    }
}
