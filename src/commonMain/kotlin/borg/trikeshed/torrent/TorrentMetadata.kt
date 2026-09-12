package borg.trikeshed.torrent

import borg.trikeshed.job.sha256

/** Bounded BEP 9 assembly. Complete means all blocks arrived; reads additionally authenticate the info hash. */
class TorrentMetadata(
    val expected: InfoHash,
    val totalSize: Int,
    maxBytes: Int = TorrentFile.MAX_METADATA_BYTES,
) {
    init { require(maxBytes in 1..TorrentFile.MAX_METADATA_BYTES && totalSize in 1..maxBytes) { "Metadata size limit" } }
    val pieceCount: Int = (totalSize - 1) / BLOCK_SIZE + 1
    private val bytes = ByteArray(totalSize)
    private val received = BooleanArray(pieceCount)
    val complete: Boolean get() = received.all { it }

    fun accept(piece: Int, data: ByteArray) {
        require(piece in 0 until pieceCount) { "Metadata piece out of range" }
        val offset = piece * BLOCK_SIZE
        val length = minOf(BLOCK_SIZE, totalSize - offset)
        require(data.size == length) { "Metadata piece width mismatch" }
        if (received[piece]) require(bytes.copyOfRange(offset, offset + length).contentEquals(data)) { "Conflicting metadata duplicate" }
        else { data.copyInto(bytes, offset); received[piece] = true }
    }
    fun infoBytes(): ByteArray {
        check(complete) { "Incomplete metadata" }
        val actual = if (expected.isV2) sha256(bytes) else Sha1.digest(bytes)
        require(actual.contentEquals(expected.bytes)) { "Metadata info hash mismatch" }
        TorrentBencode.decode(bytes).dictionary()
        return bytes.copyOf()
    }
    fun torrentFile(): TorrentFile = TorrentFile.fromInfoBytes(infoBytes(), expected)

    companion object { const val BLOCK_SIZE = 16_384 }
}
