package borg.trikeshed.reactor.xet

import borg.trikeshed.job.sha256

/** One Xet chunk and the raw CIDv1 (sha2-256) of the same bytes. */
data class XetChunkCid(
    val offset: Long,
    val length: Int,
    /** Xet chunk hash in API string form. Keyed BLAKE3, so it never equals the CID digest. */
    val xetChunkHash: String,
    val cid: String,
)

data class XetFileBridge(
    val length: Long,
    /** Xet file hash in API string form (the `file_id` of the reconstruction endpoint). */
    val xetFileHash: String,
    val chunks: List<XetChunkCid>,
)

/**
 * Bridge between Xet chunk identity and IPFS block identity. The two systems never share a chunk hash, so
 * the bridge cuts with Xet's chunker (every chunk is at most 128 KiB, under IPFS's 1 MiB block limit),
 * stores each chunk as a raw block, and records `xet chunk hash <-> CID <-> (offset, length)`.
 */
object XetIpfsBridge {
    fun bridge(data: ByteArray): XetFileBridge {
        val spans = XetChunker.chunk(data)
        val nodes = XetHashing.chunkNodes(data, spans)
        val rows = spans.mapIndexed { i, s ->
            XetChunkCid(
                offset = s.offset.toLong(),
                length = s.length,
                xetChunkHash = XetHashing.toApiHex(nodes[i].hash),
                cid = rawCidV1Sha256(data.copyOfRange(s.offset, s.offset + s.length)),
            )
        }
        return XetFileBridge(data.size.toLong(), XetHashing.toApiHex(XetHashing.fileHash(nodes)), rows)
    }

    /** CIDv1, multibase base32 lower (`b` prefix), codec raw (0x55), multihash sha2-256 (0x12, 32 bytes). */
    fun rawCidV1Sha256(block: ByteArray): String {
        val digest = sha256(block)
        val bytes = ByteArray(4 + digest.size)
        bytes[0] = 0x01; bytes[1] = 0x55; bytes[2] = 0x12; bytes[3] = 0x20
        digest.copyInto(bytes, 4)
        return "b" + base32Lower(bytes)
    }

    private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"

    private fun base32Lower(b: ByteArray): String {
        val sb = StringBuilder((b.size * 8 + 4) / 5)
        var buf = 0
        var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buf ushr (bits - 5)) and 31])
                bits -= 5
            }
            buf = buf and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
}
