package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.lib.view
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib._l
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.transfer.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** The piece channel owns verification and storage; peers and web seeds only deliver untrusted bytes. */
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
    companion object Key : CoroutineContext.Key<TorrentSession> {
        /** Pieces a transfer asks for ahead of its write. */
        const val AHEAD = 16
        /** How long a wanted piece waits for a source to start it before it fails. */
        const val WAIT_MILLIS = 120_000L
    }
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
    data class PieceWork(val index: Int, val reply: CompletableDeferred<ByteArray>)
    private val work = Channel<PieceWork>(16)
    private var worker: Job? = null
    private var tracker: Job? = null
    private var discovery: Job? = null
    private val started = TimeSource.Monotonic.markNow()
    private val observationMutex = Mutex()
    private var downloaded = 0L
    private var uploaded = 0L
    @kotlin.concurrent.Volatile private var paused = false
    private val failures = MutableStateFlow<String?>(null)
    val lastFailure: StateFlow<String?> = failures.asStateFlow()
    private val observations = MutableStateFlow(TorrentStats(infoHash, TorrentState.CHECKING, 0, 0, 0, 0, 0, 0, 0, 0))
    val stats: StateFlow<TorrentStats> = observations.asStateFlow()
    override val name: String get() = metadata.value?.info?.name ?: magnet?.displayName ?: infoHash.hex()
    override val totalSize: Long get() = metadata.value?.totalSize ?: 0
    override val pieceSize: Int get() = metadata.value?.pieceSize ?: 0
    override val numPieces: Int get() = metadata.value?.numPieces ?: 0
    /** Web seeds of the metainfo (BEP 19); a magnet's session has none. */
    val webSeeds: List<TorrentWebSeed> = file?.let { metainfo -> metainfo.webSeeds.view.map { TorrentWebSeed(it, metainfo) } } ?: emptyList()
    /** Sources with a piece in flight; the picker changes it under [observationMutex]. */
    val busy = mutableSetOf<PieceSource>()
    /** Peers that sent a piece that failed verification; they are not dialled again. */
    val banned = mutableSetOf<PeerAddress>()
    val down = TransferRate()
    val up = TransferRate()
    /** The message of the last piece that failed, cleared by the next verified piece. */
    var pieceFailure: String? = null
    /** What asked for this transfer; set by its caller. */
    var context: TransferContext? = null
    /** The content id this transfer's content lands under in the store; set by whoever lands it. */
    var content: String? = null
    /** The SUMO class of this transfer's content; set by its caller. */
    var contentClass: String? = null

    internal fun start() {
        check(worker == null)
        worker = scope.launch { pick() }
        discovery = scope.launch {
            magnet?.peers?.view?.let { addresses ->
                runCatching { addPeers(addresses.toList()) }.onFailure(::report)
            }
        }
        val urls = ((fileTrackers()) + (magnet?.trackers?.view ?: emptyList())).distinct()
        if (urls.isNotEmpty()) tracker = scope.launch {
            var event = TrackerEvent.STARTED
            var completionAnnounced = false
            while (isActive) {
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
            check(!work.isClosedForSend) { "Torrent session closed" }
            connections.entries.removeAll { it.value.isCompleted && it.value.isCancelled }
            for (address in peers.distinct()) {
                if (address in banned) continue
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
        check(!work.isClosedForSend)
        peerMutex.withLock { check(peers.value.size < 64) { "Torrent peer limit" } }
        attach(stream, handshake)
    }

    private suspend fun attach(stream: PeerStream, handshake: ByteArray?) {
        val traffic = SourceTraffic()
        val peer = TorrentPeer(stream, infoHash, peerId, metadata.value, scope, traffic,
            serveBlock = { request ->
                val data = stored(request.pieceIndex)
                data?.copyOfRange(request.offset, request.offset + request.length)?.also {
                    observationMutex.withLock {
                        uploaded += it.size; up.add(it.size.toLong())
                        traffic.served += it.size; traffic.up.add(it.size.toLong())
                        observe()
                    }
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
                    check(!work.isClosedForSend); check(peers.value.size < 64) { "Torrent peer limit" }
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
        work.send(PieceWork(pieceIndex, reply))
        return reply.await()
    }

    /** A wanted piece: the replies that wait for it, the sources that failed it and its fetches in flight. */
    class Want(val index: Int) {
        val replies = mutableListOf<CompletableDeferred<ByteArray>>()
        val tried = mutableSetOf<PieceSource>()
        val fetches = mutableMapOf<PieceSource, Job>()
        var failure: Throwable? = null
        var since = TimeSource.Monotonic.markNow()
    }
    class Fetched(val index: Int, val source: PieceSource, val outcome: Result<ByteArray>)

    /**
     * The picker. It admits piece requests and starts each wanted piece on an idle source that holds it: highest
     * priority first, and among equals the piece fewest connected peers hold (rarest first). A source with nothing
     * new to take starts a piece already in flight on one other source, and the first verified copy wins (endgame).
     * A source whose piece fails is passed over for that piece; a peer whose piece fails verification is closed and
     * not dialled again. A piece fails when every source has failed it, or when no source started it for
     * [WAIT_MILLIS]. While paused nothing starts. Closing the input fails the pieces not in flight; those in flight finish.
     */
    suspend fun pick() = coroutineScope {
        val wants = LinkedHashMap<Int, Want>()
        val fetched = Channel<Fetched>(Channel.UNLIMITED)
        var open = true
        try {
            observationMutex.withLock { observe() }
            while (open || wants.values.any { it.fetches.isNotEmpty() }) {
                select<Unit> {
                    if (open) work.onReceiveCatching { result ->
                        val request = result.getOrNull()
                        if (request != null) admit(wants, request) else {
                            open = false
                            val closed = CancellationException("Torrent piece channel closed")
                            for (want in wants.values.filter { it.fetches.isEmpty() }) { wants.remove(want.index); want.replies.forEach { it.completeExceptionally(closed) } }
                        }
                    }
                    fetched.onReceive { settle(wants, it) }
                    onTimeout(25) {}
                }
                if (open && !paused) dispatch(wants, fetched)
                expire(wants)
            }
        } finally {
            val closed = CancellationException("Torrent piece channel closed")
            work.close(closed)
            for (want in wants.values) { want.fetches.values.forEach { it.cancel() }; want.replies.forEach { it.completeExceptionally(closed) } }
            while (true) (work.tryReceive().getOrNull() ?: break).reply.completeExceptionally(closed)
            withContext(NonCancellable) { observationMutex.withLock { busy.clear() } }
        }
    }

    suspend fun admit(wants: MutableMap<Int, Want>, request: PieceWork) {
        try {
            stored(request.index)?.let { request.reply.complete(it); return }
            check(priorities.value[request.index] != 0) { "Torrent piece ${request.index} is disabled" }
            wants.getOrPut(request.index) { Want(request.index) }.replies += request.reply
        } catch (failure: Throwable) {
            if (failure is CancellationException) currentCoroutineContext().ensureActive()
            request.reply.completeExceptionally(failure); report(failure)
        }
    }

    suspend fun CoroutineScope.dispatch(wants: MutableMap<Int, Want>, fetched: Channel<Fetched>) {
        val file = metadata.value ?: return
        if (wants.isEmpty()) return
        val connected = peers.value
        val active = observationMutex.withLock { busy.toSet() }
        val order = compareBy<Want>({ -(priorities.value[it.index] ?: 1) }, { want -> connected.count { it.handler.peerState.value.hasPiece(want.index) } }, { it.index })
        for (source in connected + webSeeds) {
            if (source in active) continue
            val open = wants.values.filter { source.holds(it.index) && source !in it.tried && source !in it.fetches }
            val want = open.filter { it.fetches.isEmpty() }.minWithOrNull(order) ?: open.filter { it.fetches.size == 1 }.minWithOrNull(order) ?: continue
            val job = launch(start = CoroutineStart.LAZY) {
                val outcome = runCatching {
                    val bytes = source.piece(want.index, file.pieceLength(want.index)) { count ->
                        observationMutex.withLock { source.traffic.down.add(count.toLong()); down.add(count.toLong()) }
                    }
                    if (!file.verifyPiece(want.index, bytes)) throw TorrentPieceMismatch(want.index, source.address)
                    store(want.index, bytes)
                    bytes
                }
                fetched.trySend(Fetched(want.index, source, outcome))
            }
            want.fetches[source] = job
            observationMutex.withLock { busy += source }
            job.start()
        }
    }

    suspend fun CoroutineScope.settle(wants: MutableMap<Int, Want>, result: Fetched) {
        observationMutex.withLock { busy -= result.source }
        val want = wants[result.index] ?: return
        want.fetches.remove(result.source)
        result.outcome.onSuccess { bytes ->
            wants.remove(want.index)
            want.fetches.values.forEach { it.cancel() }
            observationMutex.withLock {
                result.source.traffic.completed += bytes.size; result.source.traffic.failure = null
                downloaded += bytes.size; pieceFailure = null; observe()
            }
            want.replies.forEach { it.complete(bytes) }
        }.onFailure { cause ->
            if (cause is CancellationException) return
            want.tried += result.source; want.failure = cause; want.since = TimeSource.Monotonic.markNow()
            report(cause)
            observationMutex.withLock { result.source.traffic.failure = cause.message }
            val source = result.source
            if (cause is TorrentPieceMismatch && source is TorrentPeer) {
                source.endpoint?.let { address -> peerMutex.withLock { banned += address } }
                launch { source.close() }
            }
            if (want.fetches.isEmpty() && (peers.value + webSeeds).all { it in want.tried }) {
                wants.remove(want.index)
                want.replies.forEach { it.completeExceptionally(cause) }
                observationMutex.withLock { pieceFailure = cause.message }
            }
        }
    }

    suspend fun expire(wants: MutableMap<Int, Want>) {
        for (want in wants.values.toList()) {
            if (paused) { want.since = TimeSource.Monotonic.markNow(); continue }
            if (want.fetches.isNotEmpty() || want.since.elapsedNow().inWholeMilliseconds < WAIT_MILLIS) continue
            wants.remove(want.index)
            val cause = want.failure ?: IllegalStateException("No source started torrent piece ${want.index} in ${WAIT_MILLIS / 1000} s")
            want.replies.forEach { it.completeExceptionally(cause) }
            observationMutex.withLock { pieceFailure = cause.message }
        }
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
        transfer(file.files.view) { bytes -> bytes.copyInto(output, position); position += bytes.size }
        return output
    }
    /** The bytes of the torrent's file at [path] (its components joined by '/'), from the pieces it spans. */
    suspend fun downloadFile(path: String): ByteArray {
        val file = awaitMetadata()
        val entry = file.files.view.firstOrNull { !it.padding && it.path.joinToString("/") == path }
            ?: throw IllegalArgumentException("The torrent holds no file $path")
        require(entry.length <= Int.MAX_VALUE) { "File exceeds in-memory output limit" }
        val output = ByteArray(entry.length.toInt())
        var position = 0
        transfer(_l[entry]) { bytes -> bytes.copyInto(output, position); position += bytes.size }
        return output
    }
    suspend fun downloadTo(path: String) {
        val metainfo = awaitMetadata()
        val file = FileChannel.open(path, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        try {
            transfer(metainfo.files.view) { bytes ->
                val buffer = ByteBuffer(bytes)
                while (buffer.hasRemaining()) check(file.write(buffer) > 0) { "Torrent output made no progress" }
            }
            file.force(true)
        } finally { file.close() }
    }
    /**
     * The bytes of [entries], in metainfo order, written as the pieces they span arrive verified. The pieces are
     * asked for together, at most [AHEAD] ahead of the write, so the picker spreads them over its sources.
     */
    suspend fun transfer(entries: Iterable<TorrentFileEntry>, write: suspend (ByteArray) -> Unit) = coroutineScope {
        val file = awaitMetadata()
        val size = file.pieceSize.toLong()
        val spans = entries.filter { !it.padding && it.length > 0 }.flatMap { entry ->
            ((entry.offset / size).toInt()..((entry.offset + entry.length - 1) / size).toInt()).map { k ->
                k j ((maxOf(entry.offset, k * size) - k * size).toInt() j (minOf(entry.offset + entry.length, (k + 1) * size) - k * size).toInt())
            }
        }
        val ahead = Channel<Join<Int, Deferred<ByteArray>>>(AHEAD)
        val asking = launch {
            for (index in spans.map { it.a }.distinct()) ahead.send(index j async { downloadPiece(index) })
            ahead.close()
        }
        var piece: Join<Int, Deferred<ByteArray>>? = null
        for (span in spans) {
            val current = piece?.takeIf { it.a == span.a } ?: ahead.receive()
            piece = current
            write(current.b.await().copyOfRange(span.b.a, span.b.b))
        }
        asking.join()
    }
    override suspend fun setPiecePriority(pieceIndex: Int, priority: Int) {
        require(pieceIndex in 0 until numPieces && priority in 0..2)
        check(!work.isClosedForSend) { "Torrent session closed" }
        priorities.update { it + (pieceIndex to priority) }
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
            if (work.isClosedForSend) TorrentState.STOPPED else if (metadata.value == null) TorrentState.CHECKING
            else if (local == numPieces) TorrentState.SEEDING else TorrentState.DOWNLOADING,
            connected.size, connected.size, downloaded, uploaded, downloaded / seconds, uploaded / seconds,
            if (metadata.value == null) 0 else if (numPieces == 0) 1000 else (local.toLong() * 1000 / numPieces).toInt(),
            connected.count { peer -> (0 until numPieces).all { peer.handler.peerState.value.hasPiece(it) } })
        onObservation()
    }
    /** Repeatable: peers, uTP and the owner each close once. */
    suspend fun close() = withContext(NonCancellable) {
        work.close()
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

internal class TorrentPeer(
    private val stream: PeerStream,
    hash: InfoHash,
    private val peerId: ByteArray,
    private val file: TorrentFile?,
    parent: CoroutineScope,
    override val traffic: SourceTraffic,
    serveBlock: suspend (PeerWireMessage.Request) -> ByteArray?,
    serveHashes: suspend (PeerHashRange) -> ByteArray?,
    private val disconnected: suspend (TorrentPeer) -> Unit,
) : PieceSource {
    /** The dialled address; null for an accepted connection, whose backend gives no endpoint. */
    val endpoint: PeerAddress? get() = stream.address
    override val address: String get() = stream.address?.toString() ?: "incoming"
    override val protocol: TransferProtocol get() = TransferProtocol.BITTORRENT
    override val transport: TransferTransport get() = if (stream is UtpStream) TransferTransport.UTP else TransferTransport.TCP
    override fun holds(index: Int): Boolean = handler.peerState.value.let { !it.choked && it.hasPiece(index) }
    /** The piece's 16 KiB blocks, four requests in flight at a time. */
    override suspend fun piece(index: Int, length: Int, received: suspend (Int) -> Unit): ByteArray {
        val bytes = ByteArray(length)
        for (batch in (bytes.indices step 16384).chunked(4)) coroutineScope {
            batch.map { offset -> async {
                val block = handler.requestBlock(index, offset, minOf(16384, length - offset))
                block.copyInto(bytes, offset)
                received(block.size)
            } }.awaitAll()
        }
        return bytes
    }
    private val owner = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val hash = hash
    val handler = PeerWireHandler(hash, file?.numPieces ?: 0, { requireNotNull(file).pieceLength(it) },
        stream::write, scope, metadata = file?.infoBytes(), serveBlock = serveBlock, serveHashes = serveHashes)
    private var reader: Job? = null
    /** Completes when the reader or [close] claims cleanup; [stopped] settles once that cleanup ends. */
    val closing: CompletableJob = Job()
    private val stopped = CompletableDeferred<Unit>()
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
                    if (closing.complete()) finish(fromReader = true)
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
        if (closing.complete()) finish(fromReader = false)
        try { stopped.await() } finally { owner.complete(); owner.join() }
    }
}
