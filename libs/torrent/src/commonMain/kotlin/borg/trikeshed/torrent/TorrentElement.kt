package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

/**
 * Torrent swarm supervisor (Pattern A CCEK).
 *
 * Orchestrates peer connections, piece selection, and data verification
 * for one active BitTorrent download. Multiple elements can run concurrently
 * for multi-torrent support.
 */
class TorrentElement(
    val metainfo: TorrentMetainfo,
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(parentJob = parentJob) {
    companion object Key : AsyncContextKey<TorrentElement>()
    override val key: AsyncContextKey<TorrentElement> get() = Key

    val pieces: List<TorrentPiece> = (0 until metainfo.pieceCount).map { i ->
        val start = i * metainfo.pieceLength
        val end = minOf((i + 1) * metainfo.pieceLength, metainfo.totalLength)
        TorrentPiece(
            index = i,
            hash = metainfo.pieces.copyOfRange(i * 20, minOf((i + 1) * 20, metainfo.pieces.size)),
            length = (end - start).toInt()
        )
    }

    private val peers = mutableListOf<TorrentPeer>()

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            supervisor.cancel()
            super.close()
        }
    }

    fun addPeer(peer: TorrentPeer) { peers.add(peer) }
}
