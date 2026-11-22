package borg.trikeshed.reactor.xet

/**
 * Xet hashes (spec: huggingface.co/docs/xet/hashing). All hashes are 32 bytes. Chunk hash = keyed BLAKE3
 * (DATA_KEY). Xorb hash = Merkle root over `(chunk hash, length)` leaves with INTERNAL_NODE_KEY. File hash
 * = keyed BLAKE3 with an all-zero key over the Merkle root of the file's chunks. Term verification hash =
 * keyed BLAKE3 (VERIFICATION_KEY) over the concatenated raw chunk hashes of the term.
 */
object XetHashing {
    private fun key(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val DATA_KEY = key(
        102, 151, 245, 119, 91, 149, 80, 222, 49, 53, 203, 172, 165, 151, 24, 28,
        157, 228, 33, 16, 155, 235, 43, 88, 180, 208, 176, 75, 147, 173, 242, 41,
    )
    private val INTERNAL_NODE_KEY = key(
        1, 126, 197, 199, 165, 71, 41, 150, 253, 148, 102, 102, 180, 138, 2, 230,
        93, 221, 83, 111, 55, 199, 109, 210, 248, 99, 82, 230, 74, 83, 113, 63,
    )
    private val VERIFICATION_KEY = key(
        127, 24, 87, 214, 206, 86, 237, 102, 18, 127, 249, 19, 231, 165, 195, 243,
        164, 205, 38, 213, 181, 219, 73, 230, 65, 36, 152, 127, 40, 251, 148, 195,
    )
    private val ZERO_KEY = ByteArray(32)

    // Merkle grouping parameters (mean 4, min 2, max 9 children). Validated by reproducing the xorb and
    // file hashes of the xet-team/xet-spec-reference-files sample (796 chunks, multi-level tree); the
    // hashing page does not state them.
    private const val MEAN_CHILDREN = 4L
    private const val MIN_CHILDREN = 2
    private const val MAX_CHILDREN = 9

    /** A Merkle leaf or node: hash plus the byte length it covers. */
    class Node(val hash: ByteArray, val length: Long)

    fun chunkHash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray =
        Blake3.keyedHash(DATA_KEY, if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length))

    fun chunkNodes(data: ByteArray, spans: List<XetChunkSpan> = XetChunker.chunk(data)): List<Node> =
        spans.map { Node(chunkHash(data, it.offset, it.length), it.length.toLong()) }

    /** Xorb hash when [leaves] are the chunks of one xorb; also the pre-image of the file hash. */
    fun merkleRoot(leaves: List<Node>): ByteArray {
        if (leaves.isEmpty()) return ByteArray(32)
        var level = leaves
        while (level.size > 1) {
            val next = ArrayList<Node>()
            var i = 0
            while (i < level.size) {
                val take = groupSize(level, i)
                next.add(internalNode(level.subList(i, i + take)))
                i += take
            }
            level = next
        }
        return level[0].hash
    }

    fun fileHash(leaves: List<Node>): ByteArray = Blake3.keyedHash(ZERO_KEY, merkleRoot(leaves))

    fun fileHash(data: ByteArray): ByteArray = fileHash(chunkNodes(data))

    /** Verification hash for one term: raw hashes of chunks `[start, end)` of the term's xorb. */
    fun verificationHash(chunkHashes: List<ByteArray>): ByteArray {
        val buf = ByteArray(chunkHashes.size * 32)
        chunkHashes.forEachIndexed { i, h -> h.copyInto(buf, i * 32) }
        return Blake3.keyedHash(VERIFICATION_KEY, buf)
    }

    private fun groupSize(nodes: List<Node>, from: Int): Int {
        val remaining = nodes.size - from
        if (remaining <= MIN_CHILDREN) return remaining
        val end = minOf(MAX_CHILDREN, remaining)
        for (i in MIN_CHILDREN until end) {
            if (lastWord(nodes[from + i].hash).mod(MEAN_CHILDREN.toULong()) == 0UL) return i + 1
        }
        return end
    }

    /** Bytes 24..31 of the hash as a little-endian u64. */
    private fun lastWord(h: ByteArray): ULong {
        var v = 0UL
        for (j in 7 downTo 0) v = (v shl 8) or (h[24 + j].toULong() and 0xFFUL)
        return v
    }

    private fun internalNode(children: List<Node>): Node {
        val sb = StringBuilder()
        var total = 0L
        for (c in children) {
            sb.append(toApiHex(c.hash)).append(" : ").append(c.length).append('\n')
            total += c.length
        }
        return Node(Blake3.keyedHash(INTERNAL_NODE_KEY, sb.toString().encodeToByteArray()), total)
    }

    private const val HEX = "0123456789abcdef"

    /**
     * Hash to the string form used in file names and API paths: each 8-byte group is reversed (read as a
     * little-endian u64) before hex encoding. Plain per-byte hex is wrong (spec: CAS API, "Converting
     * Hashes to Strings").
     */
    fun toApiHex(hash: ByteArray): String {
        require(hash.size == 32)
        val sb = StringBuilder(64)
        for (g in 0 until 4) for (j in 7 downTo 0) {
            val b = hash[g * 8 + j].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 15])
        }
        return sb.toString()
    }

    fun fromApiHex(s: String): ByteArray {
        require(s.length == 64 && s.all { it in HEX }) { "Xet hash string must be 64 lowercase hex characters" }
        val out = ByteArray(32)
        for (g in 0 until 4) for (j in 0 until 8) {
            val pos = g * 16 + j * 2
            val b = (HEX.indexOf(s[pos]) shl 4) or HEX.indexOf(s[pos + 1])
            out[g * 8 + 7 - j] = b.toByte()
        }
        return out
    }
}
