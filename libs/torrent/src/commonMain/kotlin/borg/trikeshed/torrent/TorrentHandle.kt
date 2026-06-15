package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.htx.client.ipfs.BlockStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * BitTorrent v2 (BEP 52) Torrent Handle.
 *
 * Exposed through the HTX reactor handler interface so torrent:// URIs
 * are first-class protocol citizens alongside ipfs:// and ipns://.
 */
interface TorrentHandle {
    val infoHash: InfoHash
    val name: String
    val totalSize: Long
    val pieceSize: Int
    val numPieces: Int

    /** Piece availability bitfield across all connected peers. */
    suspend fun haveBitfield(): BitField

    /** Download a specific piece, returning its data. */
    suspend fun downloadPiece(pieceIndex: Int): ByteArray

    /** Get/set per-piece download priority (0=skip, 1=normal, 2=high). */
    suspend fun setPiecePriority(pieceIndex: Int, priority: Int)

    /** Add peers via tracker, DHT, or peer exchange. */
    suspend fun addPeers(peers: List<PeerAddress>)

    /** Permanently remove this torrent. */
    suspend fun remove()
}

/**
 * SHA-256 based 32-byte info-hash used in BitTorrent v2 (BEP 52).
 */
data class InfoHash(val bytes: ByteArray) {
    init { require(bytes.size == 32) { "InfoHash must be exactly 32 bytes (SHA-256)" } }
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }
    override fun toString(): String = "InfoHash(" + hex() + ")"
}

/**
 * Bitfield of piece availability.
 * Bit i == 1 means at least one peer has piece i.
 */
class BitField(val bits: ByteArray) {
    init { require(bits.isNotEmpty()) { "BitField requires at least 1 byte" } }
    val size: Int = bits.size * 8

    operator fun get(piece: Int): Boolean {
        val byte = piece / 8
        val bit = piece % 8
        return bits[byte].toInt() and (1 shl (7 - bit)) != 0
    }

    operator fun set(piece: Int, value: Boolean) {
        val byte = piece / 8
        val bit = piece % 8
        val mask = 1 shl (7 - bit)
        bits[byte] = if (value) (bits[byte].toInt() or mask).toByte()
                     else (bits[byte].toInt() and mask.inv()).toByte()
    }

    fun numSet(): Int = bits.sumOf { Integer.bitCount(it.toInt() and 0xFF) }

    companion object {
        fun empty(numPieces: Int): BitField {
            return BitField(ByteArray((numPieces + 7) / 8))
        }
    }
}

/**
 * Peer endpoint for BitTorrent peer wire protocol.
 */
data class PeerAddress(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$host:$port"
}

/**
 * Torrent statistics snapshot.
 */
data class TorrentStats(
    val infoHash: InfoHash,
    val state: TorrentState,
    val peersConnected: Int,
    val peersTotal: Int,
    val downloadedBytes: Long,
    val uploadedBytes: Long,
    val downloadSpeedBps: Long,
    val uploadSpeedBps: Long,
    val progressPermille: Int, // 0–1000
    val seedsConnected: Int,
)

enum class TorrentState {
    STOPPED,
    CHECKING,
    DOWNLOADING,
    SEEDING,
    FINISHED,
}

/**
 * Piece selection strategy.
 */
enum class PieceStrategy {
    /** Strict priority: requested pieces first, then rarest-first. */
    STRICT_PRIORITY,
    /** Standard rarest-first. */
    RAREST_FIRST,
    /** Sequential in-order download. */
    SEQUENTIAL,
    /** Anticipate future pieces from BitTorrent v2 file trees. */
    BITFIELD_ORDER,
}

/**
 * Piece selection state machine.
 */
class PiecePicker(
    private val numPieces: Int,
    private val strategy: PieceStrategy = PieceStrategy.RAREST_FIRST,
) {
    private val havePieces = BitField.empty(numPieces)
    private val pieceRefcounts = IntArray(numPieces) // how many peers have each piece
    private val piecePriority = IntArray(numPieces) { 1 }
    private val requestedPieces = BooleanArray(numPieces)

    fun setPeerHave(piece: Int) {
        pieceRefcounts[piece]++
    }

    fun setPeerDontHave(piece: Int) {
        pieceRefcounts[piece] = maxOf(0, pieceRefcounts[piece] - 1)
    }

    fun setHave(piece: Int) {
        havePieces[piece] = true
    }

    fun have(piece: Int): Boolean = havePieces[piece]

    fun setPriority(piece: Int, priority: Int) {
        piecePriority[piece] = priority
    }

    fun requestPiece(): Int? {
        // 1. Strict priority pieces first
        for (i in 0 until numPieces) {
            if (piecePriority[i] > 1 && !requestedPieces[i] && !havePieces[i]) {
                requestedPieces[i] = true
                return i
            }
        }

        // 2. Strategy-based selection
        return when (strategy) {
            PieceStrategy.RAREST_FIRST -> selectRarest()
            PieceStrategy.SEQUENTIAL -> selectSequential()
            PieceStrategy.BITFIELD_ORDER -> selectBitfieldOrder()
            PieceStrategy.STRICT_PRIORITY -> selectSequential()
        }
    }

    private fun selectRarest(): Int? {
        var minRef = Int.MAX_VALUE
        val candidates = mutableListOf<Int>()
        for (i in 0 until numPieces) {
            if (!requestedPieces[i] && !havePieces[i]) {
                val r = pieceRefcounts[i]
                when {
                    r < minRef -> { minRef = r; candidates.clear(); candidates.add(i) }
                    r == minRef -> candidates.add(i)
                }
            }
        }
        if (candidates.isEmpty()) return null
        val chosen = candidates.random()
        requestedPieces[chosen] = true
        return chosen
    }

    private fun selectSequential(): Int? {
        for (i in 0 until numPieces) {
            if (!requestedPieces[i] && !havePieces[i]) {
                requestedPieces[i] = true
                return i
            }
        }
        return null
    }

    private fun selectBitfieldOrder(): Int? {
        // Random by bitfield — used for v2 file tree pieces
        val candidates = (0 until numPieces).filter { !requestedPieces[it] && !havePieces[it] }
        if (candidates.isEmpty()) return null
        val chosen = candidates.random()
        requestedPieces[chosen] = true
        return chosen
    }

    fun cancelPiece(piece: Int) {
        requestedPieces[piece] = false
    }

    fun rarestFirstOrder(): List<Int> {
        return (0 until numPieces)
            .filter { !havePieces[it] }
            .sortedBy { pieceRefcounts[it] }
    }
}
