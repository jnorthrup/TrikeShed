package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Seekable media streaming client over BitTorrent.
 *
 * Implements piece prioritization by playback cursor — pieces at the current
 * position get highest priority, followed by a lookahead buffer, enabling
 * real-time playback without full download.
 *
 * Integration:
 *   ffplay -i http://localhost:6800/stream  → TorrentMediaServer → StreamingTorrent
 *
 * The ffmpeg/ffplay client sends HTTP Range requests; StreamingTorrent reprioritizes
 * pieces around the requested range, blocking until data is available.
 */
class StreamingTorrent(
    val torrent: TorrentElement,
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(parentJob = parentJob) {
    companion object Key : AsyncContextKey<StreamingTorrent>()
    override val key: AsyncContextKey<StreamingTorrent> get() = Key

    /** Priority levels for piece requests. */
    enum class Priority(val weight: Int) {
        CRITICAL(0),  // cursor position — must have now
        HIGH(1),      // lookahead buffer (next few seconds)
        MEDIUM(2),    // remaining pieces in current file
        LOW(3),       // other files / far ahead
    }

    /** Current playback position in bytes (0-based from torrent start). */
    @Volatile var cursorPosition: Long = 0
        private set

    /** Number of bytes to buffer ahead of cursor (default ~5s at 1 Mbps). */
    var lookaheadBytes: Long = 640 * 1024  // 640 KiB ≈ 5s at 1 Mbps

    private val piecePriority = Mutex()
    private val pendingReads = Mutex()
    private val readWaiters = LinkedHashMap<Long, CompletableDeferred<ByteArray>>()  // pieceIndex → waiter

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        torrent.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            torrent.close()
            supervisor.cancel()
            super.close()
        }
    }

    /**
     * Seek to [byteOffset] and reprioritize pieces around the new cursor.
     * Called by the media server when ffplay issues a Range request.
     */
    suspend fun seek(byteOffset: Long) {
        cursorPosition = byteOffset.coerceIn(0, torrent.metainfo.totalLength)
        reprioritize()
    }

    /**
     * Read [length] bytes starting at [offset]. Blocks until data is available.
     * This is the primary read interface — ffplay calls this via the media server.
     *
     * Returns the bytes actually read (may be less than [length] if EOF).
     */
    suspend fun read(offset: Long, length: Int): ByteArray {
        requireState(ElementState.ACTIVE)
        val metainfo = torrent.metainfo
        val clampedLength = minOf(length.toLong(), metainfo.totalLength - offset).toInt()
        if (clampedLength <= 0) return ByteArray(0)

        val result = ByteArray(clampedLength)
        val startPiece = (offset / metainfo.pieceLength).toInt()
        val endPiece = ((offset + clampedLength - 1) / metainfo.pieceLength).toInt()

        for (pi in startPiece..endPiece) {
            val piece = torrent.pieces[pi]
            val pieceStart = pi.toLong() * metainfo.pieceLength
            val readStart = maxOf(offset, pieceStart)
            val readEnd = minOf(offset + clampedLength, pieceStart + piece.length)
            if (readStart >= readEnd) continue

            // Wait for piece to be complete
            if (!piece.isComplete) {
                awaitPiece(pi)
            }

            val pieceData = piece.data()
            val srcOffset = (readStart - pieceStart).toInt()
            val dstOffset = (readStart - offset).toInt()
            val copyLen = (readEnd - readStart).toInt()
            pieceData.copyInto(result, dstOffset, srcOffset, srcOffset + copyLen)
        }
        return result
    }

    /**
     * Get the priority for a piece based on its distance from the cursor.
     */
    fun priorityForPiece(pieceIndex: Int): Priority {
        val pieceStart = pieceIndex.toLong() * torrent.metainfo.pieceLength
        val pieceEnd = pieceStart + torrent.metainfo.pieceLength
        val cursor = cursorPosition
        return when {
            cursor in pieceStart..<pieceEnd -> Priority.CRITICAL
            pieceStart in (cursor - lookaheadBytes / 2)..(cursor + lookaheadBytes) -> Priority.HIGH
            pieceStart < cursor + torrent.metainfo.totalLength / 2 -> Priority.MEDIUM
            else -> Priority.LOW
        }
    }

    /**
     * The total byte length of all torrent data.
     */
    val totalLength: Long get() = torrent.metainfo.totalLength

    // ── Internal ──────────────────────────────────────────────────

    private suspend fun reprioritize() {
        piecePriority.withLock {
            // Piece request ordering is handled externally by the swarm supervisor
            // The priorityForPiece() method informs peer request scheduling
        }
    }

    private suspend fun awaitPiece(pieceIndex: Int) {
        // Block until the piece is downloaded by the swarm supervisor
        val deferred = CompletableDeferred<ByteArray>()
        pendingReads.withLock {
            readWaiters[pieceIndex.toLong()] = deferred
        }
        withTimeout(120_000) { // 2-minute timeout per piece
            deferred.await()
        }
    }

    /** Called by the swarm supervisor when a piece completes. */
    fun onPieceComplete(pieceIndex: Int, data: ByteArray) {
        CoroutineScope(Dispatchers.Default).launch {
            pendingReads.withLock {
                readWaiters.remove(pieceIndex.toLong())?.complete(data)
            }
        }
    }
}
