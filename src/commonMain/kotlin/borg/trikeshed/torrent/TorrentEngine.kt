package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.lib.view
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

data class TorrentEngineStats(
    val activeTorrents: Int = 0,
    val totalPeers: Int = 0,
    val totalDownloadSpeedBps: Long = 0,
    val totalUploadSpeedBps: Long = 0,
)

/** Owns listener admission and torrent sessions; all socket effects use the common ring. */
class TorrentEngine(
    private val blockStore: BlockStore,
    scope: CoroutineScope,
    private val listenAddress: PeerAddress? = PeerAddress("0.0.0.0", 6881),
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TorrentEngine>
    override val key: CoroutineContext.Key<*> get() = Key
    private val owner = SupervisorJob(requireNotNull(scope.coroutineContext[Job]))
    private val scope = CoroutineScope(scope.coroutineContext + Dispatchers.Default + owner + this)
    private val admission = Mutex()
    private val termination = Mutex()
    private val sessions = MutableStateFlow<Map<InfoHash, TorrentSession>>(emptyMap())
    private val observations = MutableStateFlow(TorrentEngineStats())
    val stats: StateFlow<TorrentEngineStats> = observations.asStateFlow()
    private val peerId = PeerHandshake.azureusPeerId()
    private var listener: TorrentTcpListener? = null
    private var accepts: Job? = null
    private val incoming = mutableSetOf<Job>()
    private val incomingMutex = Mutex()
    private var closed = false

    suspend fun addTorrent(torrentData: ByteArray, destinationDir: String? = null): TorrentHandle =
        addTorrent(TorrentFile.parse(torrentData), destinationDir)

    suspend fun addTorrent(torrentFile: TorrentFile, destinationDir: String? = null): TorrentHandle = admission.withLock {
        check(!closed) { "Torrent engine closed" }
        sessions.value[torrentFile.infoHash]?.let { return@withLock it }
        ensureListener()
        install(torrentFile, null, destinationDir)
    }

    suspend fun addMagnet(uri: String, destinationDir: String? = null): TorrentHandle {
        val magnet = MagnetUri.parse(uri)
        var existing = false
        val session = admission.withLock {
            check(!closed) { "Torrent engine closed" }
            sessions.value[magnet.infoHash]?.also { existing = true } ?: run {
                require(magnet.peers.view.any() || magnet.trackers.view.any()) {
                    "Magnet requires a tracker (tr) or direct peer (x.pe); BitTorrent DHT discovery is not implemented"
                }
                ensureListener(); install(null, magnet, destinationDir)
            }
        }
        if (existing && magnet.peers.view.any()) session.addPeers(magnet.peers.view.toList())
        session.awaitMetadata()
        return session
    }

    suspend fun seedTorrent(torrentData: ByteArray, content: ByteArray): TorrentHandle {
        val session = addTorrent(torrentData) as TorrentSession
        session.seed(content)
        return session
    }

    private fun install(file: TorrentFile?, magnet: MagnetUri?, destinationDir: String?): TorrentSession {
        check(sessions.value.size < 64) { "Torrent session limit" }
        val session = TorrentSession(file, magnet, blockStore, scope, peerId,
            listenAddress?.port, destinationDir, ::observe)
        sessions.value = sessions.value + (session.infoHash to session)
        session.start()
        observe()
        return session
    }

    private suspend fun ensureListener() {
        if (listener != null || listenAddress == null) return
        val socket = TorrentTcpListener.bind(scope, listenAddress)
        listener = socket
        accepts = scope.launch {
            while (isActive) {
                val stream = socket.accept()
                var transferred = false
                try {
                    incomingMutex.withLock {
                        incoming.removeAll { it.isCompleted }
                        if (incoming.size < 64) {
                            // Enter try/finally before handing the descriptor to a child. LAZY/DEFAULT
                            // launch can be cancelled before its body and therefore before its cleanup.
                            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                var attached = false
                                try {
                                    withTimeout(15000) {
                                        val bytes = ByteArray(68)
                                        var offset = 0
                                        while (offset < bytes.size) {
                                            val chunk = stream.read(bytes.size - offset)
                                            check(chunk.isNotEmpty()) { "EOF during peer handshake" }
                                            chunk.copyInto(bytes, offset); offset += chunk.size
                                        }
                                        val handshake = requireNotNull(PeerHandshake.decode(bytes)) { "Invalid peer handshake" }
                                        val session = sessions.value.values.firstOrNull {
                                            it.infoHash.wireBytes.contentEquals(handshake.infoHash)
                                        } ?: error("Unknown torrent swarm")
                                        session.accept(stream, bytes)
                                        attached = true
                                    }
                                } catch (failure: Throwable) {
                                    if (failure is CancellationException) currentCoroutineContext().ensureActive()
                                } finally {
                                    if (!attached) withContext(NonCancellable) { stream.close() }
                                }
                            }
                            incoming += job
                            transferred = true
                        }
                    }
                } finally {
                    if (!transferred) withContext(NonCancellable) { stream.close() }
                }
            }
        }
    }

    private fun observe() {
        val active = sessions.value.values
        observations.value = TorrentEngineStats(active.size, active.sumOf { it.stats.value.peersConnected },
            active.sumOf { it.stats.value.downloadSpeedBps }, active.sumOf { it.stats.value.uploadSpeedBps })
    }
    fun getTorrent(infoHash: InfoHash): TorrentHandle? = sessions.value[infoHash]
    fun getAllTorrents(): List<TorrentHandle> = sessions.value.values.toList()
    fun pauseAll() { sessions.value.values.forEach { it.pause() } }
    fun resumeAll() { sessions.value.values.forEach { it.resume() } }
    suspend fun removeTorrent(infoHash: InfoHash) = admission.withLock {
        sessions.value[infoHash]?.let { session -> session.close(); sessions.value = sessions.value - infoHash; observe() }
    }

    suspend fun close() = withContext(NonCancellable) {
        termination.withLock {
            admission.withLock { if (closed) return@withContext; closed = true }
            accepts?.cancelAndJoin()
            incomingMutex.withLock { incoming.toList() }.joinAll()
            var failure: Throwable? = null
            for (session in sessions.value.values) try { session.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try { listener?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            sessions.value = emptyMap(); observe()
            owner.complete(); owner.join()
            failure?.let { throw it }
        }
    }
}
