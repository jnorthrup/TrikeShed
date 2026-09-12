package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.view
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** Remote observations only. Local verified piece ownership belongs to TorrentSession. */
class PeerWireState(
    val handshake: PeerHandshake? = null,
    val choked: Boolean = true,
    val interested: Boolean = false,
    val pieceCount: Int = 0,
    bits: ByteArray = byteArrayOf(),
    val metadataId: Int = 0,
    val metadataSize: Int? = null,
) {
    private val availability = bits.copyOf()
    val bitfield: ByteArray get() = availability.copyOf()
    fun hasPiece(index: Int): Boolean = index in 0 until pieceCount && index / 8 < availability.size &&
        availability[index / 8].toInt() and (128 ushr (index % 8)) != 0
}

/** BEP 10 local extension ID and BEP 9 bencoded headers. Peer IDs are negotiated independently. */
object PeerExtensions {
    const val METADATA_ID = 1
    const val MAX_METADATA = 4 * 1024 * 1024
    const val METADATA_BLOCK = 16384
    fun handshake(metadataSize: Int? = null): PeerWireMessage.Extension {
        metadataSize?.let { require(it in 1..MAX_METADATA) }
        val entries = mutableListOf<Pair<String, BencodeValue>>("m" to TorrentBencode.dictionary("ut_metadata" to BencodeValue.Integer(METADATA_ID.toLong())))
        metadataSize?.let { entries += "metadata_size" to BencodeValue.Integer(it.toLong()) }
        return PeerWireMessage.Extension(0, TorrentBencode.encode(TorrentBencode.dictionary(*entries.toTypedArray())))
    }
    fun metadata(id: Int, type: Int, piece: Int, totalSize: Int? = null, bytes: ByteArray = byteArrayOf()): PeerWireMessage.Extension {
        require(id in 1..255 && piece >= 0 && type in 0..2)
        val entries = mutableListOf<Pair<String, BencodeValue>>("msg_type" to BencodeValue.Integer(type.toLong()), "piece" to BencodeValue.Integer(piece.toLong()))
        totalSize?.let { require(it in 1..MAX_METADATA); entries += "total_size" to BencodeValue.Integer(it.toLong()) }
        return PeerWireMessage.Extension(id, TorrentBencode.encode(TorrentBencode.dictionary(*entries.toTypedArray())) + bytes)
    }
}

private fun authenticatedPeerMetadata(hash: InfoHash, bytes: ByteArray): ByteArray {
    val accumulator = TorrentMetadata(hash, bytes.size, PeerExtensions.MAX_METADATA)
    for (piece in 0 until accumulator.pieceCount) {
        val from = piece * PeerExtensions.METADATA_BLOCK
        accumulator.accept(piece, bytes.copyOfRange(from, minOf(bytes.size, from + PeerExtensions.METADATA_BLOCK)))
    }
    return accumulator.infoBytes()
}

private fun peerHandlerParent(scope: CoroutineScope, hash: InfoHash, count: Int, peerId: ByteArray?, metadata: ByteArray?): Job? {
    require(count in 0..8 * 1024 * 1024)
    require(peerId == null || peerId.size == 20)
    metadata?.let { authenticatedPeerMetadata(hash, it) }
    return scope.coroutineContext[Job]
}

/**
 * One peer, one bounded command channel and owning supervisor. Transport supplies ordered bytes.
 * Downloads leave this element as unverified blocks; only the session may publish verified pieces.
 */
class PeerWireHandler(
    private val expectedInfoHash: InfoHash,
    pieceCount: Int,
    private var pieceLength: (Int) -> Int,
    private val send: suspend (ByteArray) -> Unit,
    scope: CoroutineScope,
    expectedPeerId: ByteArray? = null,
    metadata: ByteArray? = null,
    private val serveBlock: (suspend (PeerWireMessage.Request) -> ByteArray?)? = null,
    private val serveHashes: (suspend (PeerHashRange) -> ByteArray?)? = null,
) : AsyncContextElement(parentJob = peerHandlerParent(scope, expectedInfoHash, pieceCount, expectedPeerId, metadata)) {
    companion object Key : CoroutineContext.Key<PeerWireHandler>
    override val key: CoroutineContext.Key<*> get() = Key
    private val context = scope.coroutineContext + supervisor + this
    private val admission = Mutex()
    private val termination = Mutex()
    private val metadataRequest = Mutex()
    private val commands = Channel<Command<*>>(32)
    private var worker: Job? = null
    private val framer = PeerWireFramer(expectedInfoHash.wireBytes, expectedPeerId)
    private val handshakeReply = CompletableDeferred<PeerHandshake>()
    private val pending = mutableMapOf<PeerBlock, CompletableDeferred<ByteArray>>()
    private val cancelled = mutableSetOf<PeerBlock>()
    private val metadataPending = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val hashPending = mutableListOf<Pair<PeerHashRange, CompletableDeferred<ByteArray>>>()
    private val uploads = mutableMapOf<PeerBlock, Job>()
    private val hashUploads = mutableListOf<Job>()
    private val remoteHaves = mutableSetOf<Int>()
    private var count = pieceCount
    private var bits = ByteArray((pieceCount + 7) / 8)
    private var bitfieldSeen = false
    private var availabilityStarted = false
    private var remoteChoked = true
    private var remoteInterested = false
    private var localChoked = true
    private var metadataId = 0
    private var metadataSize: Int? = null
    private var localMetadata = metadata?.copyOf()
    private val observations = MutableStateFlow(PeerWireState(pieceCount = pieceCount, bits = bits))
    val peerState: StateFlow<PeerWireState> = observations.asStateFlow()

    private class Command<T>(val action: suspend () -> T, val reply: CompletableDeferred<T>) {
        suspend fun execute() {
            try { reply.complete(action()) }
            catch (failure: Throwable) {
                reply.completeExceptionally(failure)
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
            }
        }
    }
    override suspend fun open() = admission.withLock {
        check(state == ElementState.CREATED)
        supervisor.ensureActive()
        state = ElementState.OPEN
        worker = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            try { for (command in commands) command.execute() }
            finally {
                val failure = CancellationException("Peer wire closed")
                commands.close(failure)
                failPending(failure)
                while (true) (commands.tryReceive().getOrNull() ?: break).reply.completeExceptionally(failure)
            }
        }
        state = ElementState.ACTIVE
    }
    private suspend fun <T> call(internal: Boolean = false, action: suspend () -> T): T {
        val reply = CompletableDeferred<T>()
        if (internal) commands.send(Command(action, reply))
        else admission.withLock {
            check(state == ElementState.ACTIVE) { "Peer wire is not accepting work" }
            commands.send(Command(action, reply))
        }
        return reply.await()
    }
    private suspend fun transmit(bytes: ByteArray) = withTimeout(30000) { send(bytes) }
    private fun observe() {
        observations.value = PeerWireState(framer.handshake, remoteChoked, remoteInterested, count, bits, metadataId, metadataSize)
    }
    suspend fun awaitHandshake(): PeerHandshake = handshakeReply.await()

    /** A read loop must close this element and its transport if receive rejects a malformed stream. */
    suspend fun receive(bytes: ByteArray) {
        require(bytes.size <= 65536) { "Peer read chunk exceeds bound" }
        call {
            try {
                val messages = mutableListOf<PeerWireMessage>()
                framer.feed(bytes) { messages += it }
                framer.handshake?.let {
                    if (!handshakeReply.isCompleted) { handshakeReply.complete(it); observe() }
                }
                for (message in messages) dispatch(message)
            } catch (failure: Throwable) { failPending(failure); throw failure }
        }
    }
    suspend fun end() = call { framer.end(); failPending(IllegalStateException("Peer EOF")) }
    suspend fun configurePieces(pieceCount: Int, pieceLength: (Int) -> Int) = call {
        require(pieceCount in 1..8 * 1024 * 1024)
        if (count != 0) require(count == pieceCount) { "Peer piece count changed" }
        else if (!bitfieldSeen) bits = ByteArray((pieceCount + 7) / 8)
        count = pieceCount; this.pieceLength = pieceLength
        if (bitfieldSeen) validateBitfield(bits)
        for (index in remoteHaves) { validateIndex(index); setHave(index) }
        remoteHaves.clear(); observe()
    }
    suspend fun installMetadata(bytes: ByteArray) {
        val verified = authenticatedPeerMetadata(expectedInfoHash, bytes)
        call { localMetadata = verified }
    }
    suspend fun send(message: PeerWireMessage) = call {
        when (message) {
            PeerWireMessage.Choke -> {
                localChoked = true
                uploads.forEach { (block, job) ->
                    job.cancel()
                    if (expectedInfoHash.isV2) transmit(PeerWireMessage.Reject(block.pieceIndex, block.offset, block.length).encode())
                }
                uploads.clear()
            }
            PeerWireMessage.Unchoke -> localChoked = false
            is PeerWireMessage.Have -> validateIndex(message.pieceIndex)
            is PeerWireMessage.PWPieceBitField -> validateBitfield(message.have.bits)
            else -> Unit
        }
        transmit(message.encode())
    }
    suspend fun requestBlock(pieceIndex: Int, offset: Int, length: Int, timeoutMillis: Long = 30000): ByteArray {
        require(timeoutMillis in 1..300000)
        val block = PeerBlock(pieceIndex, offset, length)
        val reply = CompletableDeferred<ByteArray>()
        return try {
            withTimeout(timeoutMillis) {
                call {
                    validateBlock(block)
                    check(framer.handshake != null && !remoteChoked && peerState.value.hasPiece(pieceIndex)) { "Peer cannot supply requested piece" }
                    check(pending.size < 64 && block !in pending) { "Peer block request limit or duplicate" }
                    cancelled.remove(block)
                    pending[block] = reply
                    transmit(PeerWireMessage.Request(pieceIndex, offset, length).encode())
                }
                reply.await()
            }
        } finally {
            withContext(NonCancellable) {
                if (state == ElementState.ACTIVE) runCatching { call {
                    if (pending[block] === reply) {
                        pending.remove(block)
                        rememberCancelled(block)
                        transmit(PeerWireMessage.Cancel(pieceIndex, offset, length).encode())
                    }
                } }
            }
        }
    }
    suspend fun requestMetadata(timeoutMillis: Long = 60000): ByteArray = metadataRequest.withLock {
        require(timeoutMillis in 1..300000)
        withTimeout(timeoutMillis) {
            val remote = peerState.first { it.metadataId != 0 && it.metadataSize != null }
            val accumulator = TorrentMetadata(expectedInfoHash, remote.metadataSize!!, PeerExtensions.MAX_METADATA)
            for (piece in 0 until accumulator.pieceCount) {
                val reply = CompletableDeferred<ByteArray>()
                try {
                    call {
                        check(metadataId != 0 && metadataSize == remote.metadataSize)
                        check(metadataPending.put(piece, reply) == null)
                        transmit(PeerExtensions.metadata(metadataId, 0, piece).encode())
                    }
                    accumulator.accept(piece, reply.await())
                } finally {
                    withContext(NonCancellable) {
                        if (state == ElementState.ACTIVE) runCatching { call { if (metadataPending[piece] === reply) metadataPending.remove(piece) } }
                    }
                }
            }
            accumulator.infoBytes().also { verified -> call { localMetadata = verified.copyOf() } }
        }
    }
    suspend fun requestHashes(range: PeerHashRange, timeoutMillis: Long = 30000): ByteArray {
        require(expectedInfoHash.isV2 && timeoutMillis in 1..300000)
        val reply = CompletableDeferred<ByteArray>()
        return try {
            withTimeout(timeoutMillis) {
                call {
                    check(framer.handshake != null && hashPending.size < 8 && hashPending.none { it.first.matches(range) })
                    hashPending += range to reply
                    transmit(PeerWireMessage.HashRequest(range).encode())
                }
                reply.await()
            }
        } finally {
            withContext(NonCancellable) { if (state == ElementState.ACTIVE) runCatching { call { hashPending.removeAll { it.second === reply } } } }
        }
    }
    private fun validateIndex(index: Int) { require(count > 0 && index in 0 until count) { "Peer piece index out of range" } }
    private fun validateBlock(block: PeerBlock) {
        validateIndex(block.pieceIndex)
        val size = pieceLength(block.pieceIndex)
        require(size > 0 && block.offset <= size - block.length) { "Peer block exceeds piece boundary" }
    }
    private fun validateBitfield(field: ByteArray) {
        require(count > 0 && field.size == (count + 7) / 8) { "Peer bitfield width mismatch" }
        if (count % 8 != 0) require(field.last().toInt() and ((1 shl (8 - count % 8)) - 1) == 0) { "Nonzero peer bitfield padding" }
    }
    private fun setHave(index: Int) { bits[index / 8] = (bits[index / 8].toInt() or (128 ushr (index % 8))).toByte() }
    private fun rememberCancelled(block: PeerBlock) {
        if (cancelled.size == 128) cancelled.remove(cancelled.first())
        cancelled += block
    }
    private fun failPending(failure: Throwable) {
        handshakeReply.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }; pending.clear()
        metadataPending.values.forEach { it.completeExceptionally(failure) }; metadataPending.clear()
        hashPending.forEach { it.second.completeExceptionally(failure) }; hashPending.clear()
    }
    private suspend fun dispatch(message: PeerWireMessage) {
        when (message) {
            PeerWireMessage.KeepAlive -> return
            PeerWireMessage.Choke -> {
                remoteChoked = true
                if (!expectedInfoHash.isV2) {
                    pending.forEach { (block, reply) -> rememberCancelled(block); reply.completeExceptionally(IllegalStateException("Peer choked")) }
                    pending.clear()
                }
            }
            PeerWireMessage.Unchoke -> remoteChoked = false
            PeerWireMessage.Interested -> remoteInterested = true
            PeerWireMessage.NotInterested -> remoteInterested = false
            is PeerWireMessage.Have -> {
                if (count == 0) { require(message.pieceIndex < 8 * 1024 * 1024 && remoteHaves.size < 65536); remoteHaves += message.pieceIndex }
                else { validateIndex(message.pieceIndex); setHave(message.pieceIndex) }
            }
            is PeerWireMessage.PWPieceBitField -> {
                require(!bitfieldSeen && !availabilityStarted) { "Repeated or late peer bitfield" }
                if (count > 0) validateBitfield(message.have.bits)
                bits = message.have.bits.copyOf(); bitfieldSeen = true
            }
            is PeerWireMessage.Piece -> {
                val block = PeerBlock(message.pieceIndex, message.offset, message.data.size)
                validateBlock(block)
                val reply = pending.remove(block)
                if (reply != null) reply.complete(message.data.copyOf())
                else require(cancelled.remove(block)) { "Unsolicited or mismatched peer block" }
            }
            is PeerWireMessage.Reject -> {
                val block = message.block
                validateBlock(block)
                val reply = pending.remove(block)
                if (reply != null) reply.completeExceptionally(IllegalStateException("Peer rejected block"))
                else require(cancelled.remove(block)) { "Unsolicited peer rejection" }
            }
            is PeerWireMessage.Request -> upload(message)
            is PeerWireMessage.Cancel -> {
                validateBlock(message.block)
                uploads.remove(message.block)?.cancel()
                if (expectedInfoHash.isV2) transmit(PeerWireMessage.Reject(message.pieceIndex, message.offset, message.length).encode())
            }
            is PeerWireMessage.Extension -> { extension(message); observe(); return }
            is PeerWireMessage.HashRequest -> uploadHashes(message.range)
            is PeerWireMessage.Hashes -> {
                val found = hashPending.indexOfFirst { it.first.matches(message.range) }
                require(found >= 0) { "Unsolicited peer hashes" }
                hashPending.removeAt(found).second.complete(message.hashes.copyOf())
            }
            is PeerWireMessage.HashReject -> {
                val found = hashPending.indexOfFirst { it.first.matches(message.range) }
                require(found >= 0) { "Unsolicited peer hash rejection" }
                hashPending.removeAt(found).second.completeExceptionally(IllegalStateException("Peer rejected hash range"))
            }
            is PeerWireMessage.Port, is PeerWireMessage.Unknown -> Unit
        }
        availabilityStarted = true
        observe()
    }
    private suspend fun upload(request: PeerWireMessage.Request) {
        val block = request.block
        validateBlock(block)
        if (localChoked || serveBlock == null || uploads.size >= 16) {
            if (expectedInfoHash.isV2) transmit(PeerWireMessage.Reject(block.pieceIndex, block.offset, block.length).encode())
            return
        }
        require(block !in uploads) { "Duplicate upload request" }
        // Lazy start installs the pending upload before even an immediate storage callback can finish.
        val job = CoroutineScope(context).launch(start = CoroutineStart.LAZY) {
            val result = runCatching { serveBlock.invoke(request) }
            currentCoroutineContext().ensureActive()
            val bytes = result.getOrNull()
            call(internal = true) {
                if (uploads.remove(block) != null) {
                    if (bytes != null && !localChoked) {
                        require(bytes.size == block.length) { "Verified store returned wrong block length" }
                        transmit(PeerWireMessage.Piece(block.pieceIndex, block.offset, bytes).encode())
                    } else if (expectedInfoHash.isV2) transmit(PeerWireMessage.Reject(block.pieceIndex, block.offset, block.length).encode())
                }
            }
        }
        uploads[block] = job
        job.start()
    }
    private suspend fun uploadHashes(range: PeerHashRange) {
        require(expectedInfoHash.isV2) { "BEP 52 request on v1 swarm" }
        hashUploads.removeAll { it.isCompleted }
        if (serveHashes == null || hashUploads.size >= 8) { transmit(PeerWireMessage.HashReject(range).encode()); return }
        val job = CoroutineScope(context).launch {
            val result = runCatching { serveHashes.invoke(range) }
            currentCoroutineContext().ensureActive()
            val hashes = result.getOrNull()
            call(internal = true) { transmit((if (hashes == null) PeerWireMessage.HashReject(range) else PeerWireMessage.Hashes(range, hashes)).encode()) }
        }
        hashUploads += job
    }
    private suspend fun extension(message: PeerWireMessage.Extension) {
        require(framer.handshake?.extensions == true) { "Unnegotiated peer extension" }
        if (message.id == 0) {
            val dict = TorrentBencode.decode(message.payload) as? BencodeValue.Dictionary ?: error("Extension handshake must be a dictionary")
            val map = dict["m"] as? BencodeValue.Dictionary
            if (map != null) {
                val ids = mutableSetOf<Int>()
                for (entry in map.entries.view) {
                    val id = (entry.b as? BencodeValue.Integer)?.value ?: error("Invalid extension ID")
                    require(id in 0..255 && (id == 0L || ids.add(id.toInt())))
                }
                map["ut_metadata"]?.let { metadataId = (it as BencodeValue.Integer).value.toInt() }
            }
            dict["metadata_size"]?.let {
                val size = (it as? BencodeValue.Integer)?.value ?: error("Invalid metadata size")
                require(size in 1..PeerExtensions.MAX_METADATA.toLong())
                require(metadataPending.isEmpty() || metadataSize == size.toInt()) { "Metadata size changed during transfer" }
                metadataSize = size.toInt()
            }
            if (metadataId == 0) { metadataPending.values.forEach { it.completeExceptionally(IllegalStateException("Peer disabled metadata")) }; metadataPending.clear() }
            return
        }
        if (message.id != PeerExtensions.METADATA_ID) return
        val parsed = TorrentBencode.decodePrefix(message.payload)
        require(parsed.b <= 1024) { "Metadata header exceeds bound" }
        val dict = parsed.a as? BencodeValue.Dictionary ?: error("Metadata header must be a dictionary")
        val type = (dict["msg_type"] as? BencodeValue.Integer)?.value ?: error("Metadata message has no type")
        if (type !in 0..2) return // BEP 9 requires ignoring future message types.
        val piece = (dict["piece"] as? BencodeValue.Integer)?.value ?: error("Metadata message has no piece")
        require(piece in 0..(PeerExtensions.MAX_METADATA / PeerExtensions.METADATA_BLOCK).toLong())
        val index = piece.toInt()
        when (type.toInt()) {
            0 -> {
                require(parsed.b == message.payload.size && metadataId != 0)
                val bytes = localMetadata
                val from = index * PeerExtensions.METADATA_BLOCK
                if (bytes == null || from >= bytes.size) transmit(PeerExtensions.metadata(metadataId, 2, index).encode())
                else transmit(PeerExtensions.metadata(metadataId, 1, index, bytes.size,
                    bytes.copyOfRange(from, minOf(bytes.size, from + PeerExtensions.METADATA_BLOCK))).encode())
            }
            1 -> {
                val size = (dict["total_size"] as? BencodeValue.Integer)?.value ?: error("Metadata response has no total size")
                require(size == metadataSize?.toLong() && size in 1..PeerExtensions.MAX_METADATA.toLong())
                val from = index * PeerExtensions.METADATA_BLOCK
                require(from < size && message.payload.size - parsed.b == minOf(PeerExtensions.METADATA_BLOCK.toLong(), size - from).toInt())
                val reply = metadataPending.remove(index) ?: error("Unsolicited metadata response")
                reply.complete(message.payload.copyOfRange(parsed.b, message.payload.size))
            }
            2 -> {
                require(parsed.b == message.payload.size)
                metadataPending.remove(index)?.completeExceptionally(IllegalStateException("Peer rejected metadata block"))
            }
        }
    }
    override suspend fun drain() = withContext(NonCancellable) {
        termination.withLock {
            if (state == ElementState.CLOSED) return@withLock
            admission.withLock { state = ElementState.DRAINING }
            try {
                if (worker?.isActive == true) {
                    val active = call(internal = true) { uploads.values.toList() + hashUploads.toList() }
                    active.joinAll()
                }
            } finally {
                commands.close()
                worker?.join()
                failPending(CancellationException("Peer wire drained"))
                supervisor.complete(); supervisor.join()
                state = ElementState.CLOSED
            }
        }
    }
    override suspend fun close() = drain()
}
