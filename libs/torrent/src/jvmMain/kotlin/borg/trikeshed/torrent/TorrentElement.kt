package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.htx.client.ipfs.CID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * TorrentElement — BitTorrent v2 + uTP integration as an HTX reactor handler.
 *
 * Exposes torrent:// URIs as first-class protocol citizens alongside ipfs:// / ipns://.
 * Implements the CCEK (Coroutine → Context → Element → Key) framing for transport security.
 *
 * Usage:
 *   suspend fun downloadTorrent(uri: String): ByteArray {
 *       val handle = torrentElement.addTorrent(uri)
 *       return handle.downloadAll()  // downloads all pieces
 *   }
 */
class TorrentElement(
    private val blockStore: BlockStore = MemoryBlockStore(),
    private val downloadDir: String? = null,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = TorrentElementKey

    private val engine = TorrentEngine(
        blockStore = blockStore,
        scope = CoroutineScope(supervisor),
    )

    /** Active torrent handles: info-hash hex → handle. */
    val activeTorrents: Map<String, TorrentHandle>
        get() = engine.getAllTorrents().associateBy { it.infoHash.hex() }

    /** Global engine statistics. */
    val stats: StateFlow<TorrentEngineStats>
        get() = engine.stats

    /**
     * Add a torrent from a magnet URI or .torrent file path.
     *
     * Magnet URI example:
     *   magnet:?xt=urn:btih:<info-hash>&dn=<name>&tr=<tracker-url>
     */
    suspend fun addTorrent(
        magnetUri: String,
        destinationDir: String? = downloadDir,
    ): TorrentHandle {
        return when {
            magnetUri.startsWith("magnet:") -> addMagnet(magnetUri, destinationDir)
            magnetUri.startsWith("file:") -> addTorrentFile(URI(magnetUri).path, destinationDir)
            magnetUri.startsWith("/") -> addTorrentFile(magnetUri, destinationDir)
            else -> throw IllegalArgumentException("Unsupported torrent URI: $magnetUri")
        }
    }

    /**
     * Add a torrent from a magnet URI string.
     */
    suspend fun addMagnet(magnetUri: String, destinationDir: String?): TorrentHandle {
        val params = parseMagnetUri(magnetUri)
        val xt = params["xt"]?.removePrefix("urn:btih:") ?: throw IllegalArgumentException("Magnet URI missing btih")
        val infoHash = InfoHash(hexToBytes(xt))
        val name = params["dn"] ?: "unknown"

        // Synthesize a minimal TorrentInfo from magnet data
        val tf = TorrentFile(
            announce = params["tr"],
            info = TorrentInfo(name = name, pieceLength = 16384),
        )
        return engine.addTorrent(tf, destinationDir)
    }

    /**
     * Add a torrent from a .torrent file path.
     */
    suspend fun addTorrentFile(filePath: String, destinationDir: String?): TorrentHandle {
        val bytes = File(filePath).readBytes()
        return engine.addTorrent(bytes, destinationDir)
    }

    /**
     * Add a torrent from raw bencode bytes.
     */
    suspend fun addTorrentData(torrentData: ByteArray, destinationDir: String? = null): TorrentHandle {
        return engine.addTorrent(torrentData, destinationDir)
    }

    /**
     * Remove a torrent by info-hash hex.
     */
    suspend fun removeTorrent(infoHashHex: String) {
        engine.removeTorrent(InfoHash(hexToBytes(infoHashHex)))
    }

    /**
     * Get a torrent handle by info-hash hex.
     */
    fun getTorrent(infoHashHex: String): TorrentHandle? {
        return engine.getTorrent(InfoHash(hexToBytes(infoHashHex)))
    }

    /**
     * Resolve CID (for torrent, returns info-hash as CID for content-addressing).
     */
    suspend fun resolve(infoHash: InfoHash): CID {
        return CID.sha256(infoHash.bytes)
    }

    /**
     * Store raw bytes and return their content-addressable CID.
     */
    suspend fun put(data: ByteArray): CID {
        val cid = CID.sha256(data)
        blockStore.put(cid, data)
        return cid
    }

    /**
     * Retrieve data by CID.
     */
    suspend fun get(cid: CID): ByteArray? = blockStore.get(cid)

    override suspend fun open() {
        super.open()
    }

    override suspend fun close() {
        engine.close()
        super.close()
    }

    // ─── Magnet URI parser ────────────────────────────────────────────────────

    private fun parseMagnetUri(uri: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val body = uri.removePrefix("magnet:?")
        body.split("&").forEach { pair ->
            val (k, v) = pair.split("=", limit = 2)
            params[k] = java.net.URLDecoder.decode(v, Charsets.UTF_8.name())
        }
        return params
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((hex[2 * i].digitToIntOrNull(16) ?: 0) shl 4 or (hex[2 * i + 1].digitToIntOrNull(16) ?: 0)).toByte()
        }
    }
}

object TorrentElementKey : CoroutineContext.Key<TorrentElement>

/**
 * TorrentHandle extension — download all pieces and return the full file data.
 */
suspend fun TorrentHandle.downloadAll(): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    for (i in 0 until numPieces) {
        val piece = downloadPiece(i)
        buf.write(piece)
    }
    return buf.toByteArray()
}
