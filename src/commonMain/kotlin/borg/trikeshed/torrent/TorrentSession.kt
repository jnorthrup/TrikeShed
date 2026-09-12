package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.lib.view
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** The piece channel owns verification and storage; peer channels only deliver untrusted blocks. */
class TorrentSession internal constructor(
    file: TorrentFile?,
    private val magnet: MagnetUri?,
    private val blockStore: BlockStore,
    parent: CoroutineScope,
    private val peerId: ByteArray,
    private val listenPort: Int?,
    val destinationDir: String?,
    private val onObservation: () -> Unit,
) : TorrentHandle, CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TorrentSession>
    override val key: CoroutineContext.Key<*> get() = Key
    override val infoHash: InfoHash = file?.infoHash ?: requireNotNull(magnet).infoHash
    private val owner = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope = CoroutineScope(parent.coroutineContext + owner + this + TrackerClient())
    private val utp = UtpManager(scope)
    private val metadata = MutableStateFlow(file)
    val torrentFile: TorrentFile get() = requireNotNull(metadata.value) { "Torrent metadata not acquired" }
    private val metadataMutex = Mutex()
    private val peers = MutableStateFlow<List<TorrentPeer>>(emptyList())
    private val connections = mutableMapOf<PeerAddress, Deferred<Unit>>()
    private val peerMutex = Mutex()
    private val pieces = mutableMapOf<Int, CID>()
    private val storeMutex = Mutex()
    private val priorities = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val admission = Mutex()
    private val termination = Mutex()
    private data class PieceWork(val index: Int, val reply: CompletableDeferred<ByteArray>)
    private val work = Channel<PieceWork>(16)
    private var worker: Job? = null
    private var tracker: Job? = null
    private var discovery: Job? = null
    private val started = TimeSource.Monotonic.markNow()
    private val observationMutex = Mutex()
    private var downloaded = 0L
    private var uploaded = 0L
    @kotlin.concurrent.Volatile private var paused = false
    @kotlin.concurrent.Volatile private var closed = false
    private val failures = MutableStateFlow<String?>(null)
    val lastFailure: StateFlow<String?> = failures.asStateFlow()
    private val observations = MutableStateFlow(TorrentStats(infoHash, TorrentState.CHECKING, 0, 0, 0, 0, 0, 0, 0, 0))
    val stats: StateFlow<TorrentStats> = observations.asStateFlow()
    override val name: String get() = metadata.value?.info?.name ?: magnet?.displayName ?: infoHash.hex()
    override val totalSize: Long get() = metadata.value?.totalSize ?: 0
    override val pieceSize: Int get() = metadata.value?.pieceSize ?: 0
    override val numPieces: Int get() = metadata.value?.numPieces ?: 0

    internal fun start() {
        check(worker == null)
        worker = scope.launch {
            val queued = mutableListOf<PieceWork>()
            try {
                observationMutex.withLock { observe() }
                while (true) {
                    if (queued.isEmpty()) queued += work.receiveCatching().getOrNull() ?: break
                    while (queued.size < 16) queued += work.tryReceive().getOrNull() ?: break
                    val request = queued.maxBy { priorities.value[it.index] ?: 1 }
                    queued.remove(request)
                    try { request.reply.complete(fetchPiece(request.index)) }
                    catch (failure: Throwable) {
                        request.reply.completeExceptionally(failure)
                        report(failure)
                        if (failure is CancellationException) currentCoroutineContext().ensureActive()
                    }
                }
            } finally {
                val failure = CancellationException("Torrent piece channel closed")
                work.close(failure)
                queued.forEach { it.reply.completeExceptionally(failure) }
                while (true) (work.tryReceive().getOrNull() ?: break).reply.completeExceptionally(failure)
            }
        }
        discovery = scope.launch {
            magnet?.peers?.view?.let { addresses ->
                runCatching { addPeers(addresses.toList()) }.onFailure(::report)
            }
        }
        val urls = ((fileTrackers()) + (magnet?.trackers?.view ?: emptyList())).distinct()
        if (urls.isNotEmpty()) tracker = scope.launch {
            var event = TrackerEvent.STARTED
            var completionAnnounced = false
            while (isActive && !closed) {
                var interval = 30000L
                for (url in urls) {
                    try {
                        val snapshot = stats.value
                        val result = requireNotNull(currentCoroutineContext()[TrackerClient]).announce(url, infoHash, peerId,
                            listenPort ?: 6881, snapshot.uploadedBytes, snapshot.downloadedBytes, remaining(), event)
                        interval = result.intervalMillis
                        addPeers(result.peers.view.take(32))
                        if (event == TrackerEvent.COMPLETED) completionAnnounced = true
                        event = TrackerEvent.PERIODIC
                        break
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) currentCoroutineContext().ensureActive()
                        report(failure)
                    }
                }
                delay(interval)
                if (!completionAnnounced && metadata.value != null && remaining() == 0L && event == TrackerEvent.PERIODIC) event = TrackerEvent.COMPLETED
            }
        }
    }
    private fun fileTrackers(): List<String> = metadata.value?.let { file ->
        file.announceList.flatten() + listOfNotNull(file.announce)
    } ?: emptyList()
    private fun report(failure: Throwable) { failures.value = failure.message ?: failure::class.simpleName }

    suspend fun awaitMetadata(timeoutMillis: Long = 120000): TorrentFile = withTimeout(timeoutMillis) {
        metadata.first { it != null && it.pieceLayersComplete }!!
    }

    override suspend fun addPeers(peers: List<PeerAddress>) {
        require(peers.size <= 4096)
        val admitted = mutableListOf<Deferred<Unit>>()
        peerMutex.withLock {
            check(!closed) { "Torrent session closed" }
            connections.entries.removeAll { it.value.isCompleted && it.value.isCancelled }
            for (address in peers.distinct()) {
                val existing = connections[address]
                if (existing != null) { admitted += existing; continue }
                if (connections.size >= 32) break
                val job = scope.async(start = CoroutineStart.LAZY) {
                    val stream = try { TorrentTcpStream.connect(scope, address, 15000) }
                    catch (failure: Throwable) {
                        if (failure is CancellationException) currentCoroutineContext().ensureActive()
                        try { withContext(utp) { requireNotNull(currentCoroutineContext()[UtpManager]).connect(address) } }
                        catch (fallback: Throwable) { fallback.addSuppressed(failure); throw fallback }
                    }
                    attach(stream, null)
                }
                connections[address] = job; admitted += job; job.start()
            }
        }
        var successes = 0
        var failure: Throwable? = null
        for (job in admitted) try { job.await(); successes++ } catch (error: Throwable) {
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            failure = error; report(error)
        }
        if (admitted.isNotEmpty() && successes == 0) throw requireNotNull(failure)
    }

    internal suspend fun accept(stream: PeerStream, handshake: ByteArray) {
        check(!closed)
        peerMutex.withLock { check(peers.value.size < 64) { "Torrent peer limit" } }
        attach(stream, handshake)
    }

    private suspend fun attach(stream: PeerStream, handshake: ByteArray?) {
        val peer = TorrentPeer(stream, infoHash, peerId, metadata.value, scope,
            serveBlock = { request ->
                val data = stored(request.pieceIndex)
                data?.copyOfRange(request.offset, request.offset + request.length)?.also {
                    observationMutex.withLock { uploaded += it.size; observe() }
                }
            }, serveHashes = { range -> metadata.value?.hashes(range) },
            disconnected = { ended ->
                ended.failure?.let(::report)
                peerMutex.withLock {
                    peers.value = peers.value.filter { it !== ended }
                    stream.address?.let { connections.remove(it) }
                }
                observationMutex.withLock { observe() }
            })
        try {
            withTimeout(60000) {
                peer.open(handshake, haveBitfield().takeIf { it.numSet() > 0 })
                metadataMutex.withLock {
                    if (metadata.value == null) {
                        val bytes = peer.handler.requestMetadata()
                        val authenticated = requireNotNull(magnet).validateMetadata(bytes)
                            .acquirePieceLayers { peer.handler.requestHashes(it) }
                        metadata.value = authenticated
                    }
                }
                val file = torrentFile
                peer.configure(file)
                peer.handler.send(PeerWireMessage.Unchoke)
                peer.handler.send(PeerWireMessage.Interested)
                peerMutex.withLock {
                    check(!closed); check(peers.value.size < 64) { "Torrent peer limit" }
                    peers.value = peers.value + peer
                }
                observationMutex.withLock { observe() }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { peer.close() }
            throw failure
        }
    }

    override suspend fun haveBitfield(): BitField = storeMutex.withLock {
        BitField.empty(numPieces).also { field -> pieces.keys.forEach { field[it] = true } }
    }
    private suspend fun stored(index: Int): ByteArray? = storeMutex.withLock {
        val cid = pieces[index] ?: return@withLock null
        val data = blockStore.get(cid)
        if (data == null || !torrentFile.verifyPiece(index, data)) {
            pieces.remove(index); throw IllegalStateException("Stored torrent piece failed verification")
        }
        data.copyOf()
    }
    private suspend fun store(index: Int, bytes: ByteArray) {
        require(torrentFile.verifyPiece(index, bytes)) { "Torrent piece $index failed hash verification" }
        storeMutex.withLock {
            val cid = CID.sha256(bytes)
            blockStore.put(cid, bytes.copyOf()); pieces[index] = cid
        }
        for (peer in peers.value) runCatching { peer.handler.send(PeerWireMessage.Have(index)) }.onFailure(::report)
        observationMutex.withLock { observe() }
    }
    override suspend fun downloadPiece(pieceIndex: Int): ByteArray {
        val file = awaitMetadata()
        require(pieceIndex in 0 until file.numPieces)
        val reply = CompletableDeferred<ByteArray>()
        admission.withLock { check(!closed); work.send(PieceWork(pieceIndex, reply)) }
        return reply.await()
    }
    private suspend fun fetchPiece(index: Int): ByteArray = withTimeout(120000) {
        stored(index)?.let { return@withTimeout it }
        check(!paused) { "Torrent paused" }
        check(priorities.value[index] != 0) { "Torrent piece $index is disabled" }
        val file = torrentFile
        val attempted = mutableSetOf<TorrentPeer>()
        var last: Throwable? = null
        while (true) {
            val peer = peers.value.firstOrNull { it !in attempted && it.handler.peerState.value.let { state -> !state.choked && state.hasPiece(index) } }
            if (peer == null) {
                if (attempted.isNotEmpty() && peers.value.all { it in attempted }) throw requireNotNull(last)
                delay(10); continue
            }
            attempted += peer
            try {
                val bytes = ByteArray(file.pieceLength(index))
                for (batch in (bytes.indices step 16384).chunked(4)) coroutineScope {
                    batch.map { offset -> async {
                        val block = peer.handler.requestBlock(index, offset, minOf(16384, bytes.size - offset))
                        block.copyInto(bytes, offset)
                    } }.awaitAll()
                }
                store(index, bytes)
                observationMutex.withLock { downloaded += bytes.size; observe() }
                return@withTimeout bytes
            } catch (failure: Throwable) {
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
                last = failure; report(failure)
                peer.close()
            }
        }
        @Suppress("UNREACHABLE_CODE") error("No piece")
    }

    /** Input is concatenated real file content, in metainfo order, with padding omitted. */
    suspend fun seed(content: ByteArray) {
        val file = awaitMetadata()
        require(file.totalSize == content.size.toLong()) { "Seed content length differs from metainfo" }
        val wireSize = (0 until file.numPieces).sumOf { file.pieceLength(it).toLong() }
        require(wireSize <= TorrentElement.MAX_DOWNLOAD_BYTES) { "Seed wire representation exceeds in-memory limit" }
        suspend fun assemble(consume: suspend (Int, ByteArray) -> Unit) {
            var entryIndex = 0
            var source = 0
            for (index in 0 until file.numPieces) {
                val piece = ByteArray(file.pieceLength(index))
                val start = index.toLong() * file.pieceSize
                val end = start + piece.size
                while (entryIndex < file.files.size) {
                    val entry = file.files[entryIndex]
                    if (entry.length == 0L) { entryIndex++; continue }
                    if (entry.offset >= end) break
                    val entryEnd = entry.offset + entry.length
                    val from = maxOf(start, entry.offset)
                    val to = minOf(end, entryEnd)
                    if (!entry.padding && to > from) {
                        val input = source + (from - entry.offset).toInt()
                        content.copyInto(piece, (from - start).toInt(), input, input + (to - from).toInt())
                    }
                    if (entryEnd > end) break
                    if (!entry.padding) source += entry.length.toInt()
                    entryIndex++
                }
                consume(index, piece)
            }
            check(source == content.size) { "Seed layout did not consume the declared payload" }
        }
        // Two bounded passes: validate every piece before advertising any; retain only one wire piece.
        assemble { index, bytes -> require(file.verifyPiece(index, bytes)) { "Seed piece $index failed verification" } }
        assemble { index, bytes -> store(index, bytes) }
    }

    suspend fun downloadAll(): ByteArray {
        val file = awaitMetadata()
        require(file.totalSize <= Int.MAX_VALUE) { "Torrent exceeds in-memory output limit; use downloadTo" }
        val output = ByteArray(file.totalSize.toInt())
        var position = 0
        transfer { bytes -> bytes.copyInto(output, position); position += bytes.size }
        return output
    }
    suspend fun downloadTo(path: String) {
        val file = FileChannel.open(path, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        try {
            transfer { bytes ->
                val buffer = ByteBuffer(bytes)
                while (buffer.hasRemaining()) check(file.write(buffer) > 0) { "Torrent output made no progress" }
            }
            file.force(true)
        } finally { file.close() }
    }
    private suspend fun transfer(write: suspend (ByteArray) -> Unit) {
        val file = awaitMetadata()
        for (entry in file.files.view) {
            if (entry.padding) continue
            var remaining = entry.length
            var index = entry.firstPiece
            var offset = if (file.infoHash.isV2) 0 else (entry.offset % file.pieceSize).toInt()
            while (remaining > 0) {
                val piece = downloadPiece(index++)
                val count = minOf(remaining, (piece.size - offset).toLong()).toInt()
                write(piece.copyOfRange(offset, offset + count)); remaining -= count; offset = 0
            }
        }
    }
    override suspend fun setPiecePriority(pieceIndex: Int, priority: Int) {
        require(pieceIndex in 0 until numPieces && priority in 0..2)
        admission.withLock { check(!closed); priorities.value = priorities.value + (pieceIndex to priority) }
    }
    fun pause() { paused = true }
    fun resume() { paused = false }
    override suspend fun remove() { requireNotNull(scope.coroutineContext[TorrentEngine]).removeTorrent(infoHash) }
    private suspend fun remaining(): Long = storeMutex.withLock {
        metadata.value?.let { file -> (0 until file.numPieces).filter { it !in pieces }.sumOf { file.pieceLength(it).toLong() } } ?: 1L
    }
    private suspend fun observe() {
        val local = storeMutex.withLock { pieces.size }
        val seconds = maxOf(1L, started.elapsedNow().inWholeSeconds)
        val connected = peers.value
        observations.value = TorrentStats(infoHash,
            if (closed) TorrentState.STOPPED else if (metadata.value == null) TorrentState.CHECKING
            else if (local == numPieces) TorrentState.SEEDING else TorrentState.DOWNLOADING,
            connected.size, connected.size, downloaded, uploaded, downloaded / seconds, uploaded / seconds,
            if (metadata.value == null) 0 else if (numPieces == 0) 1000 else (local.toLong() * 1000 / numPieces).toInt(),
            connected.count { peer -> (0 until numPieces).all { peer.handler.peerState.value.hasPiece(it) } })
        onObservation()
    }
    suspend fun close() = withContext(NonCancellable) {
        termination.withLock {
            admission.withLock { if (closed) return@withContext; closed = true; work.close() }
            tracker?.cancelAndJoin(); discovery?.join()
            peerMutex.withLock { connections.values.toList() }.joinAll()
            worker?.join()
            var failure: Throwable? = null
            for (peer in peers.value) try { peer.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            peers.value = emptyList()
            try { utp.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            owner.complete(); owner.join()
            observationMutex.withLock { observe() }
            failure?.let { throw it }
        }
    }
}

internal class TorrentPeer(
    private val stream: PeerStream,
    hash: InfoHash,
    private val peerId: ByteArray,
    private val file: TorrentFile?,
    parent: CoroutineScope,
    serveBlock: suspend (PeerWireMessage.Request) -> ByteArray?,
    serveHashes: suspend (PeerHashRange) -> ByteArray?,
    private val disconnected: suspend (TorrentPeer) -> Unit,
) {
    private val owner = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val hash = hash
    val handler = PeerWireHandler(hash, file?.numPieces ?: 0, { requireNotNull(file).pieceLength(it) },
        stream::write, scope, metadata = file?.infoBytes(), serveBlock = serveBlock, serveHashes = serveHashes)
    private var reader: Job? = null
    private val termination = Mutex()
    private val stopped = CompletableDeferred<Unit>()
    private var closing = false
    @kotlin.concurrent.Volatile var failure: Throwable? = null
        private set

    suspend fun open(initial: ByteArray?, initialBitfield: BitField? = null) {
        handler.open()
        withTimeout(15000) {
            stream.write(PeerHandshake(infoHash = hash.wireBytes, peerId = peerId,
                reservedBytes = ByteArray(8).also { it[5] = 0x10 }).encode())
        }
        initialBitfield?.let { field ->
            require(field.numSet() > 0) { "Omit empty initial bitfield" }
            handler.send(PeerWireMessage.PWPieceBitField(field))
        }
        initial?.let { handler.receive(it) }
        reader = scope.launch {
            try {
                while (isActive) {
                    val bytes = stream.read(65536)
                    if (bytes.isEmpty()) { handler.end(); break }
                    handler.receive(bytes)
                }
            } catch (cause: Throwable) {
                if (cause !is CancellationException) failure = cause
            } finally {
                withContext(NonCancellable) {
                    if (claimClose()) finish(fromReader = true)
                }
            }
        }
        val remote = handler.awaitHandshake()
        require(!remote.peerId.contentEquals(peerId)) { "Torrent self connection" }
        if (remote.extensions) handler.send(PeerExtensions.handshake(file?.infoBytes()?.size))
    }

    suspend fun configure(file: TorrentFile) {
        handler.installMetadata(file.infoBytes())
        if (file.numPieces > 0) handler.configurePieces(file.numPieces, file::pieceLength)
        if (handler.peerState.value.handshake?.extensions == true) handler.send(PeerExtensions.handshake(file.infoBytes().size))
    }

    private suspend fun claimClose(): Boolean = termination.withLock {
        if (closing) false else { closing = true; true }
    }

    /** Reader-owned cleanup cannot join its own owner; an external close joins after settlement. */
    private suspend fun finish(fromReader: Boolean) {
        var cleanupFailure: Throwable? = null
        suspend fun settle(action: suspend () -> Unit) {
            try { action() } catch (cause: Throwable) {
                if (cleanupFailure == null) cleanupFailure = cause else cleanupFailure!!.addSuppressed(cause)
            }
        }
        if (fromReader) settle { stream.close() }
        settle { handler.drain() }
        if (!fromReader) {
            settle { stream.close() }
            settle { reader?.cancelAndJoin() }
        }
        cleanupFailure?.let { if (failure == null) failure = it else if (failure !== it) failure!!.addSuppressed(it) }
        settle { disconnected(this) }
        owner.complete()
        if (cleanupFailure == null) stopped.complete(Unit) else stopped.completeExceptionally(cleanupFailure!!)
    }

    suspend fun close() = withContext(NonCancellable) {
        if (claimClose()) finish(fromReader = false)
        try { stopped.await() } finally { owner.complete(); owner.join() }
    }
}
