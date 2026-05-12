package borg.trikeshed.torrent

/**
 * Torrent metainfo — the parsed .torrent file. Bencode format.
 */
data class TorrentMetainfo(
    val infoHash: CharSequence,         // SHA-1 of info dict (40 hex chars)
    val name: CharSequence,             // suggested save name
    val pieceLength: Long,        // bytes per piece
    val pieces: ByteArray,        // concatenated SHA-1 hashes (20 bytes each)
    val length: Long,             // single-file mode
    val files: List<TorrentFile> = emptyList(),  // multi-file mode
    val announce: CharSequence,         // tracker URL
    val announceList: List<List<CharSequence>> = emptyList(),  // multi-tracker tiers
    val creationDate: Long? = null,
    val comment: CharSequence? = null,
    val createdBy: CharSequence? = null,
) {
    val pieceCount: Int get() = pieces.size / 20
    val isMultiFile: Boolean get() = files.isNotEmpty()
    val totalLength: Long get() = if (isMultiFile) files.sumOf { it.length } else length
}

data class TorrentFile(
    val path: List<CharSequence>,  // path components within torrent
    val length: Long,
)
