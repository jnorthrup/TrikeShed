package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Owned torrent control commands; engine transport and file I/O stay behind the common userspace boundary. */
class TorrentElement(
    private val blockStore: BlockStore = MemoryBlockStore(),
    private val downloadDir: String? = null,
    parentJob: Job? = null,
    private val reactorContext: CoroutineContext = EmptyCoroutineContext,
    listenAddress: PeerAddress? = PeerAddress("0.0.0.0", 6881),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = TorrentElementKey
    private val scope = CoroutineScope(reactorContext + this + supervisor)
    private val engine = TorrentEngine(blockStore, scope, listenAddress)
    private val admission = Mutex()
    private val commands = Channel<Command>(32)
    private val closed = CompletableDeferred<Unit>()
    private var worker: Job? = null

    private interface Command {
        suspend fun execute(element: TorrentElement)
        fun fail(cause: Throwable)
    }
    private class Request<T>(val body: suspend TorrentElement.() -> T, val result: CompletableDeferred<T>) : Command {
        override suspend fun execute(element: TorrentElement) {
            try { result.complete(element.body()) }
            catch (failure: Throwable) {
                result.completeExceptionally(failure)
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
            }
        }
        override fun fail(cause: Throwable) { result.completeExceptionally(cause) }
    }

    val activeTorrents: Map<String, TorrentHandle> get() = engine.getAllTorrents().associateBy { it.infoHash.hex() }
    val stats: StateFlow<TorrentEngineStats> get() = engine.stats

    override suspend fun open() = admission.withLock {
        if (state == ElementState.CREATED) {
            super.open()
            worker = scope.launch {
                try {
                    val element = requireNotNull(currentCoroutineContext()[TorrentElementKey])
                    for (command in commands) command.execute(element)
                } finally {
                    val failure = IllegalStateException("Torrent command worker closed")
                    commands.close(failure)
                    while (true) (commands.tryReceive().getOrNull() ?: break).fail(failure)
                }
            }
            state = ElementState.ACTIVE
        } else check(state == ElementState.ACTIVE) { "Torrent element is $state" }
    }
    private suspend fun <T> command(body: suspend TorrentElement.() -> T): T {
        open()
        val result = CompletableDeferred<T>()
        admission.withLock {
            check(state == ElementState.ACTIVE) { "Torrent element is draining" }
            commands.send(Request(body, result))
        }
        return result.await()
    }

    suspend fun addTorrent(uri: String, destinationDir: String? = downloadDir): TorrentHandle = when {
        uri.startsWith("magnet:", ignoreCase = true) -> addMagnet(uri, destinationDir)
        uri.startsWith("file:", ignoreCase = true) -> addTorrentFile(fileUri(uri), destinationDir)
        uri.startsWith('/') -> addTorrentFile(uri, destinationDir)
        else -> throw IllegalArgumentException("Expected magnet URI or absolute torrent file path")
    }
    suspend fun addMagnet(uri: String, destinationDir: String? = downloadDir): TorrentHandle =
        command { engine.addMagnet(uri, destinationDir) }
    suspend fun addTorrentFile(path: String, destinationDir: String? = downloadDir): TorrentHandle =
        command { engine.addTorrent(readMetainfo(path), destinationDir) }
    suspend fun addTorrentData(data: ByteArray, destinationDir: String? = downloadDir): TorrentHandle {
        require(data.size <= TorrentBencode.MAX_BYTES)
        val bytes = data.copyOf()
        return command { engine.addTorrent(bytes, destinationDir) }
    }
    suspend fun seedTorrent(data: ByteArray, content: ByteArray): TorrentHandle {
        require(data.size <= TorrentBencode.MAX_BYTES && content.size <= MAX_DOWNLOAD_BYTES)
        val metadata = data.copyOf(); val payload = content.copyOf()
        return command { engine.seedTorrent(metadata, payload) }
    }
    suspend fun removeTorrent(hex: String) { val hash = infoHash(hex); command { engine.removeTorrent(hash) } }
    fun getTorrent(hex: String): TorrentHandle? = engine.getTorrent(infoHash(hex))
    suspend fun download(hex: String): ByteArray = command {
        val handle = requireNotNull(engine.getTorrent(infoHash(hex))) { "Unknown torrent" }
        handle.downloadAll()
    }
    suspend fun downloadTo(hex: String, path: String): Unit = command {
        val handle = requireNotNull(engine.getTorrent(infoHash(hex))) { "Unknown torrent" }
        require(handle is TorrentSession) { "Torrent session required" }
        handle.downloadTo(path)
    }

    /** Materialize the actual info-dictionary block before returning its CAS identity. */
    suspend fun resolve(infoHash: InfoHash): CID = command {
        val session = engine.getTorrent(infoHash) as? TorrentSession ?: throw IllegalArgumentException("Unknown torrent")
        val data = session.torrentFile.infoBytes()
        val cid = CID.sha256(data)
        blockStore.put(cid, data)
        cid
    }
    suspend fun put(data: ByteArray): CID {
        val bytes = data.copyOf()
        return command { CID.sha256(bytes).also { blockStore.put(it, bytes) } }
    }
    suspend fun get(cid: CID): ByteArray? = command { blockStore.get(cid) }

    override suspend fun drain() = close()
    override suspend fun close() {
        val owner = admission.withLock {
            if (state == ElementState.CLOSED || state == ElementState.DRAINING) false
            else { state = ElementState.DRAINING; commands.close(); true }
        }
        if (!owner) { closed.await(); return }
        withContext(NonCancellable) {
            try {
                worker?.join()
                engine.close()
                supervisor.complete()
                supervisor.join()
                state = ElementState.CLOSED
                closed.complete(Unit)
            } catch (failure: Throwable) {
                closed.completeExceptionally(failure)
                throw failure
            }
        }
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024
        fun infoHash(hex: String): InfoHash {
            require(hex.length == 40 || hex.length == 64) { "Expected full v1/v2 info hash" }
            return InfoHash(ByteArray(hex.length / 2) { i ->
                val high = hex[i * 2].digitToIntOrNull(16); val low = hex[i * 2 + 1].digitToIntOrNull(16)
                require(high != null && low != null) { "Invalid info-hash hex" }; ((high shl 4) or low).toByte()
            })
        }
        private fun fileUri(uri: String): String {
            var path = uri.substring(5)
            if (path.startsWith("//localhost/")) path = path.removePrefix("//localhost")
            else if (path.startsWith("//")) { require(path.startsWith("///")) { "Remote file URI authority is unsupported" }; path = path.substring(2) }
            require(path.startsWith('/') && '?' !in path && '#' !in path) { "Expected absolute file URI without query/fragment" }
            return MagnetUri.percent(path)
        }
        private fun readMetainfo(path: String): ByteArray {
            require(path.isNotEmpty() && '\u0000' !in path)
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                require(size in 1..TorrentBencode.MAX_BYTES.toLong()) { "Torrent file size limit" }
                val bytes = ByteArray(size.toInt()); val buffer = ByteBuffer(bytes)
                while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated torrent file" }
                return bytes
            } finally { channel.close() }
        }
    }
}

object TorrentElementKey : CoroutineContext.Key<TorrentElement>

/** Download verified payload bytes; the in-memory convenience has an explicit allocation bound. */
suspend fun TorrentHandle.downloadAll(): ByteArray {
    require(totalSize in 0..TorrentElement.MAX_DOWNLOAD_BYTES.toLong()) { "Use file download for larger torrents" }
    if (this is TorrentSession) return downloadAll()
    val result = ByteArray(totalSize.toInt())
    var at = 0
    for (index in 0 until numPieces) {
        val piece = downloadPiece(index)
        require(piece.size <= result.size - at) { "Piece data exceeds declared payload size" }
        piece.copyInto(result, at); at += piece.size
    }
    check(at == result.size) { "Piece data does not fill declared payload size" }
    return result
}
