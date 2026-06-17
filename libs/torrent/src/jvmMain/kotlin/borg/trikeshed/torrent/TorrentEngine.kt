package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.htx.client.ipfs.BlockStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * TorrentEngine — manages active torrents and coordinates BitTorrent v2 (BEP 52) + uTP (BEP 29).
 *
 * Each TorrentHandle is a TorrentSession. The engine owns all sessions and routes
 * incoming uTP/peer-wire messages to the correct session.
 */
class TorrentEngine(
    private val blockStore: BlockStore,
    private val scope: CoroutineScope,
) {
    private val torrents = ConcurrentHashMap<InfoHash, TorrentSession>()
    private val trackerClient = TrackerClient(scope)
    private val utpManager = UtpManager(scope)

    private val _stats = MutableStateFlow(TorrentEngineStats())
    val stats: StateFlow<TorrentEngineStats> = _stats.asStateFlow()

    /**
     * Add a torrent from raw .torrent bencode bytes.
     */
    suspend fun addTorrent(torrentData: ByteArray, destinationDir: String? = null): TorrentHandle {
        val tf = TorrentFile.parse(torrentData)
        return addTorrent(tf, destinationDir)
    }

    /**
     * Add a parsed TorrentFile.
     */
    suspend fun addTorrent(torrentFile: TorrentFile, destinationDir: String? = null): TorrentHandle {
        val infoHash = torrentFile.infoHashV2()
        val session = TorrentSession(
            torrentFile = torrentFile,
            infoHash = infoHash,
            blockStore = blockStore,
            scope = scope,
            trackerClient = trackerClient,
            utpManager = utpManager,
        )
        torrents[infoHash] = session
        session.start()
        return session
    }

    suspend fun removeTorrent(infoHash: InfoHash) {
        torrents.remove(infoHash)?.close()
    }

    fun getTorrent(infoHash: InfoHash): TorrentHandle? = torrents[infoHash]
    fun getAllTorrents(): List<TorrentHandle> = torrents.values.toList()

    fun pauseAll()  = torrents.values.forEach { it.pause() }
    fun resumeAll() = torrents.values.forEach { it.resume() }

    suspend fun close() {
        torrents.values.forEach { it.close() }
        torrents.clear()
        trackerClient.close()
        utpManager.close()
    }
}

data class TorrentEngineStats(
    val activeTorrents: Int = 0,
    val totalPeers: Int = 0,
    val totalDownloadSpeedBps: Long = 0,
    val totalUploadSpeedBps: Long = 0,
)

// ─── TorrentSession ──────────────────────────────────────────────────────────

/**
 * Active torrent session — coordinates peers and piece fetching.
 */
class TorrentSession(
    val torrentFile: TorrentFile,
    override val infoHash: InfoHash,
    private val blockStore: BlockStore,
    private val scope: CoroutineScope,
    private val trackerClient: TrackerClient,
    private val utpManager: UtpManager,
) : TorrentHandle {

    private val peers = ConcurrentHashMap<PeerAddress, PeerSession>()
    private val peerCount = torrentFile.info.length?.let { len ->
        ((len + (torrentFile.info.pieceLength ?: 16384) - 1) / (torrentFile.info.pieceLength ?: 16384)).toInt()
    } ?: 0

    private val piecePicker = PiecePicker(peerCount, PieceStrategy.RAREST_FIRST)
    private val peerWireHandler = PeerWireHandler(
        infoHashBytes = torrentFile.infoHashV1(),
        piecePicker = piecePicker,
        blockStore = blockStore,
        scope = scope,
    )

    private val _stats = MutableStateFlow(makeStats(TorrentState.CHECKING))
    private val statsMutex = Mutex()

    private var paused = false
    private var closed = false

    override val name: String get() = torrentFile.info.name
    override val totalSize: Long get() = torrentFile.info.length ?: 0L
    override val pieceSize: Int get() = (torrentFile.info.pieceLength ?: 16384).toInt()
    override val numPieces: Int get() = peerCount

    fun start() {
        scope.launch {
            val trackerPeers = trackerClient.announce(
                torrentFile = torrentFile,
                peerId = PeerHandshake.azureusPeerId(),
                port = 6881,
                uploaded = 0,
                downloaded = 0,
                left = torrentFile.info.length ?: 0,
            )
            trackerPeers.forEach { (host, port) ->
                addPeer(PeerAddress(host, port))
            }
            updateStats(TorrentState.DOWNLOADING)
        }
    }

    override suspend fun haveBitfield(): BitField = BitField.empty(peerCount)

    override suspend fun downloadPiece(pieceIndex: Int): ByteArray {
        val pieceLen = min(pieceSize.toLong(), totalSize - pieceIndex * pieceSize).toInt()
        return withTimeout(300_000) {
            val peer = peers.values.firstOrNull { it.hasPiece(pieceIndex) }
                ?: throw IllegalStateException("No peer has piece $pieceIndex")
            peer.requestPiece(pieceIndex, 0, pieceLen)
        }
    }

    override suspend fun setPiecePriority(pieceIndex: Int, priority: Int) {
        piecePicker.setPriority(pieceIndex, priority)
    }

    override suspend fun addPeers(peers: List<PeerAddress>) {
        peers.forEach { addPeer(it) }
    }

    override suspend fun remove() { close() }

    fun pause()  { paused = true;  peers.values.forEach { it.choke() } }
    fun resume() { paused = false; peers.values.forEach { it.unchoke() } }

    fun close() {
        closed = true
        peers.values.forEach { it.close() }
        peers.clear()
    }

    private fun addPeer(address: PeerAddress) {
        if (peers.containsKey(address)) return
        val peer = PeerSession(
            address = address,
            infoHashBytes = torrentFile.infoHashV1(),
            peerIdBytes = PeerHandshake.azureusPeerId(),
            peerWireHandler = peerWireHandler,
            piecePicker = piecePicker,
            utpManager = utpManager,
            scope = scope,
            onDisconnect = { peers.remove(address) },
            onHave = { piece -> piecePicker.setHave(piece) },
        )
        peers[address] = peer
        peer.connect()
    }

    private suspend fun updateStats(state: TorrentState) {
        statsMutex.withLock {
            _stats.value = makeStats(state)
        }
    }

    private fun makeStats(state: TorrentState) = TorrentStats(
        infoHash = infoHash,
        state = state,
        peersConnected = peers.size,
        peersTotal = peers.size,
        downloadedBytes = 0,
        uploadedBytes = 0,
        downloadSpeedBps = 0,
        uploadSpeedBps = 0,
        progressPermille = 0,
        seedsConnected = 0,
    )
}

// ─── PeerSession ─────────────────────────────────────────────────────────────

class PeerSession(
    val address: PeerAddress,
    private val infoHashBytes: ByteArray,
    private val peerIdBytes: ByteArray,
    private val peerWireHandler: PeerWireHandler,
    private val piecePicker: PiecePicker,
    private val utpManager: UtpManager,
    private val scope: CoroutineScope,
    private val onDisconnect: () -> Unit,
    private val onHave: (Int) -> Unit,
) {
    private var utpConn: UtpSocket? = null
    private var choked = true
    private val havePieces = ConcurrentHashMap.newKeySet<Int>()

    fun hasPiece(piece: Int) = havePieces.contains(piece)

    fun connect() {
        scope.launch {
            val connId = UtpSocket.deriveConnId(infoHashBytes, peerIdBytes, initiator = true)
            val socket = utpManager.connect(address, connId) { header, payload ->
                peerWireHandler.handlePeerMessage(address, header, payload, onHave)
            }
            utpConn = socket
            socket.connect()
        }
    }

    fun choke()   { choked = true }
    fun unchoke() { choked = false }

    suspend fun requestPiece(pieceIndex: Int, offset: Int, length: Int): ByteArray {
        val sock = utpConn ?: throw IllegalStateException("Not connected")
        val requestMsg = PeerWireMessage.Request(pieceIndex, offset, length)
        sock.send(requestMsg.encode())
        return withTimeout(60_000) {
            peerWireHandler.awaitPiece(address, pieceIndex)
        }
    }

    fun close() {
        utpConn?.close()
        onDisconnect()
    }
}
