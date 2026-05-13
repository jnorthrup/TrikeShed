package borg.trikeshed.bugzee

import borg.trikeshed.hazelnut.Transport
import borg.trikeshed.hazelnut.TransportBinding
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

// ── 1. BugzeeMessageType ─────────────────────────────────────────────────────

/**
 * Message types for Bugzee's distributed gossip protocol.
 */
enum class BugzeeMessageType {
    PUBLISH,
    QUERY,
    VOTE,
    COMMENT,
    GOSSIP,
    HEARTBEAT,
    REPLICATE,
    SUBSCRIBE,
    ACK,
    NACK,
}

// ── 2. BugzeeMessageHeader ───────────────────────────────────────────────────

/**
 * Wire header for every Bugzee message. All identity fields are CharSequence
 * to remain platform-agnostic in commonMain (no String allocations).
 */
data class BugzeeMessageHeader(
    val version: Int,
    val msgType: BugzeeMessageType,
    val payloadLen: Int,
    val checksum: Long,
    val transportTag: CharSequence,
    val correlationId: CharSequence,
    val timestamp: Long,
    val nodeId: CharSequence,
)

// ── 3. BugzeeNetworkMessage ──────────────────────────────────────────────────

/**
 * Transport-agnostic network message carrying a header and raw payload bytes.
 */
data class BugzeeNetworkMessage(
    val header: BugzeeMessageHeader,
    val payload: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BugzeeNetworkMessage) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

// ── 4. ProtocolCodec ────────────────────────────────────────────────────────

/**
 * Binary codec for BugzeeNetworkMessage ↔ ByteArray.
 *
 * Framing layout:
 *   [6 bytes magic: 0xB7 0x05 0xB7 0x05 0xB7 0x05]
 *   [4 bytes total frame length (big-endian, uint32)]
 *   [1 byte version]
 *   [1 byte msgType ordinal]
 *   [4 bytes payloadLen (big-endian)]
 *   [8 bytes checksum (FNV-1a, big-endian uint64)]
 *   [N bytes transportTag (UTF-8, length-prefixed uint16)]
 *   [N bytes correlationId (UTF-8, length-prefixed uint16)]
 *   [8 bytes timestamp (big-endian)]
 *   [8 bytes nodeIdLength (big-endian uint64) — wait, let me use uint16 for nodeId too]
 *   [N bytes nodeId (UTF-8, length-prefixed uint16)]
 *   [payloadLen bytes payload]
 *
 * Total fixed header size: 6 + 4 + 1 + 1 + 4 + 8 = 24 bytes minimum,
 * then variable-length CharSequence fields, then payload.
 */
object ProtocolCodec {

    /** Magic bytes identifying a Bugzee frame. */
    private val MAGIC = byteArrayOf(0xB7.toByte(), 0x05, 0xB7.toByte(), 0x05, 0xB7.toByte(), 0x05)

    /** Offset within the frame where the total length field begins. */
    private const val MAGIC_SIZE = 6
    private const val LENGTH_FIELD_SIZE = 4
    private const val HEADER_FIXED_SIZE = MAGIC_SIZE + LENGTH_FIELD_SIZE + 1 + 1 + 4 + 8
    // version(1) + msgType(1) + payloadLen(4) + checksum(8) = 14 after magic+length

    /**
     * FNV-1a 64-bit hash over the header fields (excluding the checksum itself)
     * plus the payload bytes. This provides a simple integrity check.
     */
    fun fnv1a64(data: ByteArray): Long {
        const val FNV_PRIME: Long = 1099511628211L
        const val FNV_OFFSET: Long = -3750763034362895575L // unsigned 0xcbf29ce484222325

        var hash = FNV_OFFSET
        for (b in data) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash = (hash * FNV_PRIME) and 0xFFFFFFFFFFFFFFFFL
        }
        return hash
    }

    /**
     * Serialize a BugzeeNetworkMessage into a wire-framed ByteArray.
     */
    fun encode(msg: BugzeeNetworkMessage): ByteArray {
        val transportTagBytes = msg.header.transportTag.toString().encodeToByteArray()
        val correlationIdBytes = msg.header.correlationId.toString().encodeToByteArray()
        val nodeIdBytes = msg.header.nodeId.toString().encodeToByteArray()

        // Compute the variable header size (CharSequence fields)
        val varHeaderSize =
            2 + transportTagBytes.size +          // length(2) + data
            2 + correlationIdBytes.size +         // length(2) + data
            2 + nodeIdBytes.size                  // length(2) + data

        val totalPayloadSize = HEADER_FIXED_SIZE + varHeaderSize - MAGIC_SIZE - LENGTH_FIELD_SIZE + msg.payload.size
        // totalPayloadSize is everything after magic + length field

        val totalFrameSize = MAGIC_SIZE + LENGTH_FIELD_SIZE + totalPayloadSize

        val buf = ByteArray(totalFrameSize)
        var offset = 0

        // Magic
        MAGIC.copyInto(buf, offset, 0, MAGIC_SIZE)
        offset += MAGIC_SIZE

        // Total frame length (big-endian)
        buf.putInt32(offset, totalFrameSize)
        offset += LENGTH_FIELD_SIZE

        // Version
        buf[offset++] = msg.header.version.toByte()

        // Message type ordinal
        buf[offset++] = msg.header.msgType.ordinal.toByte()

        // Payload length (big-endian)
        buf.putInt32(offset, msg.header.payloadLen)
        offset += 4

        // Checksum: compute FNV-1a over everything from here onward that will be in the frame
        // We'll encode fields first, set placeholder checksum, compute, then overwrite.
        val checksumOffset = offset
        // Placeholder
        buf.putInt64(offset, 0L)
        offset += 8

        // CharSequence fields
        buf.putLengthPrefixed(offset, transportTagBytes)
        offset += 2 + transportTagBytes.size

        buf.putLengthPrefixed(offset, correlationIdBytes)
        offset += 2 + correlationIdBytes.size

        // Timestamp (big-endian int64)
        buf.putInt64(offset, msg.header.timestamp)
        offset += 8

        buf.putLengthPrefixed(offset, nodeIdBytes)
        offset += 2 + nodeIdBytes.size

        // Payload
        if (msg.payload.isNotEmpty()) {
            msg.payload.copyInto(buf, offset, 0, msg.payload.size)
            offset += msg.payload.size
        }

        // Now compute checksum over the data after the checksum field itself
        val checkData = buf.copyOfRange(checksumOffset + 8, totalFrameSize)
        val checksum = fnv1a64(checkData)

        // Overwrite the placeholder checksum
        buf.putInt64(checksumOffset, checksum)

        return buf
    }

    /**
     * Decode a wire-framed ByteArray into a BugzeeNetworkMessage.
     * Returns null if the frame is invalid or truncated.
     */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size): BugzeeNetworkMessage? {
        if (length < HEADER_FIXED_SIZE) return null

        // Validate magic
        for (i in 0 until MAGIC_SIZE) {
            if (data[offset + i] != MAGIC[i]) return null
        }

        // Read total frame length
        val totalLen = data.getInt32(offset + MAGIC_SIZE)
        if (offset + totalLen > data.size) return null

        val pos = offset + MAGIC_SIZE + LENGTH_FIELD_SIZE

        val version = data[pos].toInt() and 0xFF
        val msgTypeOrdinal = data[pos + 1].toInt() and 0xFF
        val msgType = try {
            BugzeeMessageType.entries[msgTypeOrdinal]
        } catch (e: IndexOutOfBoundsException) {
            return null
        }

        val payloadLen = data.getInt32(pos + 2)
        val checksum = data.getInt64(pos + 6)

        var cursor = pos + 14 // after checksum

        // Read transportTag
        val ttLen = data.getUint16(cursor)
        cursor += 2
        if (cursor + ttLen > offset + totalLen) return null
        val transportTag = data.decodeToString(cursor, cursor + ttLen)
        cursor += ttLen

        // Read correlationId
        val ciLen = data.getUint16(cursor)
        cursor += 2
        if (cursor + ciLen > offset + totalLen) return null
        val correlationId = data.decodeToString(cursor, cursor + ciLen)
        cursor += ciLen

        // Read timestamp
        val timestamp = data.getInt64(cursor)
        cursor += 8

        // Read nodeId
        val niLen = data.getUint16(cursor)
        cursor += 2
        if (cursor + niLen > offset + totalLen) return null
        val nodeId = data.decodeToString(cursor, cursor + niLen)
        cursor += niLen

        // Verify checksum: FNV-1a over everything after checksum field
        val checkRegion = data.copyOfRange(pos + 14, offset + totalLen)
        val computedChecksum = fnv1a64(checkRegion)
        if (checksum != computedChecksum) return null

        // Extract payload
        val payloadSize = payloadLen
        val payload = if (payloadSize > 0) {
            data.copyOfRange(cursor, cursor + payloadSize)
        } else {
            byteArrayOf()
        }

        return BugzeeNetworkMessage(
            header = BugzeeMessageHeader(
                version = version,
                msgType = msgType,
                payloadLen = payloadLen,
                checksum = checksum,
                transportTag = transportTag,
                correlationId = correlationId,
                timestamp = timestamp,
                nodeId = nodeId,
            ),
            payload = payload,
        )
    }

    // -- ByteArray helpers --

    private fun ByteArray.putInt32(offset: Int, value: Int) {
        this[offset] = ((value ushr 24) and 0xFF).toByte()
        this[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 3] = (value and 0xFF).toByte()
    }

    private fun ByteArray.getInt32(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.putInt64(offset: Int, value: Long) {
        var v = value
        for (i in 7 downTo 0) {
            this[offset + i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
    }

    private fun ByteArray.getInt64(offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (this[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    private fun ByteArray.putLengthPrefixed(offset: Int, data: ByteArray) {
        require(data.size <= 65535) { "CharSequence exceeds uint16 length" }
        this[offset] = ((data.size ushr 8) and 0xFF).toByte()
        this[offset + 1] = (data.size and 0xFF).toByte()
        data.copyInto(this, offset + 2, 0, data.size)
    }

    private fun ByteArray.getUint16(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
}

// ── 5. Per-transport config objects ──────────────────────────────────────────

/**
 * SCTP transport configuration.
 */
data class SctpConfig(
    val streamType: SctpStreamType = SctpStreamType.STREAM,
    val ppid: Int = 0,
)

enum class SctpStreamType {
    STREAM,    // Ordered, reliable delivery
    UNORDERED, // Unordered, reliable delivery
    FRAGMENTED, // Ordered, supports large message fragmentation
}

/**
 * QUIC transport configuration.
 */
data class QuicConfig(
    val alpn: List<CharSequence> = listOf("bugzee"),
    val version: Int = 1,
    val maxStreams: Int = 100,
)

/**
 * HTX (HTTP-extension) transport configuration.
 */
data class HtxConfig(
    val method: HtxMethod = HtxMethod.POST,
    val contentType: CharSequence = "application/octet-stream",
    val frameSize: Int = 4096,
)

enum class HtxMethod {
    GET, POST, PUT,
}

/**
 * IPFS/libp2p transport configuration.
 */
data class IpfsConfig(
    val libp2pProtocol: CharSequence = "/bugzee/1.0.0",
    val dhtTopic: CharSequence = "bugzee-gossip",
    val pubsub: Boolean = true,
)

// ── 6. BugzeeSocketManager ───────────────────────────────────────────────────

/**
 * Manages socket file descriptors per peer per transport.
 * Creates and destroys sockets through FunctionalUringFacade.
 * Maintains a connection pool with max connections per peer.
 */
class BugzeeSocketManager(
    private val facade: FunctionalUringFacade,
    private val maxConnectionsPerPeer: Int = 4,
) {
    /** Peer identifier to transport to list of socket FileImpls. */
    private val peerSockets = mutableMapOf<CharSequence, MutableMap<Transport, MutableList<SocketEntry>>>()

    /** Active transport bindings. */
    private val bindings = mutableMapOf<Int, TransportBinding>() // fd -> binding

    private var socketCounter: Int = 0

    /**
     * A socket entry tracking the FileImpl and its state.
     */
    data class SocketEntry(
        val file: FileImpl,
        val transport: Transport,
        val peerId: CharSequence,
        val address: CharSequence?,
        val port: Int,
    )

    /**
     * Get or create a socket for the given peer and transport.
     * Reuses an existing socket if available and the pool limit isn't exceeded.
     */
    fun getOrCreateSocket(peerId: CharSequence, transport: Transport): SocketEntry? {
        val peerMap = peerSockets.getOrPut(peerId) { mutableMapOf() }
        val socketList = peerMap.getOrPut(transport) { mutableListOf() }

        // Return existing if pool not full
        if (socketList.isNotEmpty() && socketList.size < maxConnectionsPerPeer) {
            return socketList.first()
        }

        // Create new socket via uring SOCKET op
        if (socketList.size >= maxConnectionsPerPeer) return null

        val fd = nextFd()
        val file = FileImpl(id = fd)
        val entry = SocketEntry(
            file = file,
            transport = transport,
            peerId = peerId,
            address = null,
            port = 0,
        )

        // Enqueue socket creation
        facade.enqueue(
            UringSubmission(
                opcode = UringOp.SOCKET,
                fd = -1,
                addr = 0L,
                len = transport.defaultPort,
                offset = transport.uringOpcode.code.toLong(),
                userData = (fd.toLong() shl 48) or 1L,
            ),
        )
        facade.submit()

        socketList.add(entry)
        return entry
    }

    /**
     * Create a TransportBinding for the given entry.
     */
    fun createBinding(entry: SocketEntry): TransportBinding {
        val binding = TransportBinding(
            transport = entry.transport,
            facade = facade,
            socket = entry.file,
        )
        bindings[entry.file.id] = binding
        return binding
    }

    /**
     * Enqueue a connect operation for the given socket to the target address.
     */
    fun enqueueConnect(entry: SocketEntry, address: CharSequence, port: Int): Long {
        val userData = nextUserData(entry)
        facade.enqueue(
            UringSubmission(
                opcode = UringOp.CONNECT,
                fd = entry.file.id,
                addr = 0L,
                len = 0,
                offset = 0L,
                userData = userData,
            ),
        )
        return userData
    }

    /**
     * Release all sockets for a peer.
     */
    fun releasePeer(peerId: CharSequence) {
        val peerMap = peerSockets[peerId] ?: return
        for ((transport, entries) in peerMap) {
            for (entry in entries) {
                facade.enqueue(
                    UringSubmission(
                        opcode = UringOp.CLOSE,
                        fd = entry.file.id,
                        addr = 0L,
                        len = 0,
                        offset = 0L,
                        userData = entry.file.id.toLong() shl 48 or 2L,
                    ),
                )
                bindings.remove(entry.file.id)
            }
        }
        facade.submit()
        peerSockets.remove(peerId)
    }

    /**
     * Get all active bindings.
     */
    fun getActiveBindings(): Map<CharSequence, List<TransportBinding>> =
        peerSockets.mapValues { (_, transportMap) ->
            transportMap.values.flatten().mapNotNull { bindings[it.file.id] }
        }

    /**
     * Close a specific socket entry.
     */
    fun closeSocket(entry: SocketEntry) {
        val peerMap = peerSockets[entry.peerId] ?: return
        val list = peerMap[entry.transport] ?: return
        list.remove(entry)
        if (list.isEmpty()) {
            peerMap.remove(entry.transport)
        }
        if (peerMap.isEmpty()) {
            peerSockets.remove(entry.peerId)
        }

        facade.enqueue(
            UringSubmission(
                opcode = UringOp.CLOSE,
                fd = entry.file.id,
                addr = 0L,
                len = 0,
                offset = 0L,
                userData = entry.file.id.toLong() shl 48 or 2L,
            ),
        )
        bindings.remove(entry.file.id)
        facade.submit()
    }

    private fun nextFd(): Int = ++socketCounter

    private fun nextUserData(entry: SocketEntry): Long =
        entry.file.id.toLong() shl 48 or (++entry.userDataCounter)

    /**
     * Enqueue a recv operation on the given binding.
     */
    fun enqueueRecv(binding: TransportBinding, buffer: ByteBuffer): Long =
        binding.enqueueRecv(buffer)

    /**
     * Enqueue a send operation on the given binding.
     */
    fun enqueueSend(binding: TransportBinding, payload: ByteArray): Long =
        binding.enqueueSend(payload)
}

// ── 7. TransportSelector ────────────────────────────────────────────────────

/**
 * Selects the best available transport for a given peer.
 * Fallback chain: QUIC → SCTP → HTX → IPFS.
 */
class TransportSelector {
    /**
     * Tracks which transports are available for each peer.
     * Key: peer nodeId, Value: set of available transports ordered by preference.
     */
    private val peerAvailability = mutableMapOf<CharSequence, MutableSet<Transport>>()

    /**
     * Register that a peer supports the given transport.
     */
    fun registerTransport(peerId: CharSequence, transport: Transport) {
        val available = peerAvailability.getOrPut(peerId) { mutableSetOf() }
        available.add(transport)
    }

    /**
     * Remove a transport from a peer's available set.
     */
    fun deregisterTransport(peerId: CharSequence, transport: Transport) {
        peerAvailability[peerId]?.remove(transport)
        if (peerAvailability[peerId]?.isEmpty() == true) {
            peerAvailability.remove(peerId)
        }
    }

    /**
     * Select the highest-priority available transport for the peer.
     * Returns null if no transports are available.
     */
    fun select(peerId: CharSequence): Transport? {
        val available = peerAvailability[peerId] ?: return null
        return FALLBACK_ORDER.firstOrNull { it in available }
    }

    /**
     * Get all available transports for a peer in preference order.
     */
    fun selectAll(peerId: CharSequence): List<Transport> {
        val available = peerAvailability[peerId] ?: return emptyList()
        return FALLBACK_ORDER.filter { it in available }
    }

    /**
     * Check if any transport is available for the peer.
     */
    fun isAvailable(peerId: CharSequence): Boolean =
        peerAvailability[peerId]?.isNotEmpty() == true

    companion object {
        /** Preference-ordered fallback chain. */
        private val FALLBACK_ORDER = listOf(
            Transport.QUIC,
            Transport.SCTP,
            Transport.HTX,
            Transport.IPFS,
        )
    }
}

// ── 8. BugzeeNetworkLayer ────────────────────────────────────────────────────

/**
 * High-level network layer coordinating send/receive, connect/disconnect.
 * All socket IO goes through FunctionalUringFacade.enqueue → submit.
 */
class BugzeeNetworkLayer(
    private val facade: FunctionalUringFacade,
    private val socketManager: BugzeeSocketManager,
    private val transportSelector: TransportSelector,
    private val localNodeId: CharSequence,
    private val defaultConfig: TransportDefaults = TransportDefaults(),
) {
    /** Pending receive buffers keyed by peer+transport. */
    private val rxBuffers = mutableMapOf<CharSequence, MutableList<ByteBuffer>>()

    /** Callback invoked on each received message. */
    private var onDataCallback: ((CharSequence, BugzeeNetworkMessage) -> Unit)? = null

    /** In-flight send tokens keyed by userData. */
    private val pendingSends = mutableMapOf<Long, BugzeeNetworkMessage>()

    /** Pending receive tokens keyed by userData. */
    private val pendingReceives = mutableMapOf<Long, Pair<CharSequence, TransportBinding>>()

    /**
     * Default transport configuration wrapper.
     */
    data class TransportDefaults(
        val sctp: SctpConfig = SctpConfig(),
        val quic: QuicConfig = QuicConfig(),
        val htx: HtxConfig = HtxConfig(),
        val ipfs: IpfsConfig = IpfsConfig(),
    )

    /**
     * Connect to a peer using the specified set of transports.
     * Establishes socket FDs and registers them with the selector.
     */
    fun connect(peerId: CharSequence, address: CharSequence, transports: Set<Transport>) {
        val bindings = mutableListOf<TransportBinding>()

        for (transport in transports) {
            val socket = socketManager.getOrCreateSocket(peerId, transport)
                ?: continue

            val binding = socketManager.createBinding(socket)
            socketManager.enqueueConnect(socket, address, transport.defaultPort)

            bindings.add(binding)
            transportSelector.registerTransport(peerId, transport)
        }

        facade.submit()

        // Prime receive buffers for each binding
        for (binding in bindings) {
            primeReceive(peerId, binding)
        }

        drainCompletions()
    }

    /**
     * Disconnect from a peer, releasing all sockets.
     */
    fun disconnect(peerId: CharSequence) {
        val bindings = socketManager.getActiveBindings()[peerId]
        // Remove pending receives for this peer
        pendingReceives.entries.removeIf { it.value.first == peerId }

        transportSelector.deregisterTransport(peerId, Transport.QUIC)
        transportSelector.deregisterTransport(peerId, Transport.SCTP)
        transportSelector.deregisterTransport(peerId, Transport.HTX)
        transportSelector.deregisterTransport(peerId, Transport.IPFS)

        socketManager.releasePeer(peerId)
        facade.submit()
    }

    /**
     * Send a message to a peer. The transport is selected automatically
     * based on availability and the selector's preference chain.
     */
    fun send(peerId: CharSequence, message: BugzeeNetworkMessage) {
        val transport = transportSelector.select(peerId)
            ?: throw IllegalStateException("No available transport for peer: $peerId")

        val bindings = socketManager.getActiveBindings()[peerId]
            ?.filter { it.transport == transport }
            ?.firstOrNull()

        if (bindings == null) {
            // Need to establish a socket for this transport
            val socket = socketManager.getOrCreateSocket(peerId, transport)
                ?: throw IllegalStateException("Cannot create socket for transport: $transport")

            val binding = socketManager.createBinding(socket)
            doSend(peerId, binding, message)
        } else {
            doSend(peerId, bindings, message)
        }

        facade.submit()
    }

    /**
     * Internal send: encode, enqueue, track.
     */
    private fun doSend(peerId: CharSequence, binding: TransportBinding, message: BugzeeNetworkMessage) {
        val wireBytes = ProtocolCodec.encode(message)
        val token = socketManager.enqueueSend(binding, wireBytes)
        pendingSends[token] = message
    }

    /**
     * Register a callback for incoming data. Each invocation provides the
     * peer nodeId and the decoded message.
     */
    fun receive(onData: (CharSequence, BugzeeNetworkMessage) -> Unit) {
        onDataCallback = onData
    }

    /**
     * Prime a receive buffer on the given binding.
     */
    private fun primeReceive(peerId: CharSequence, binding: TransportBinding) {
        val buf = ByteBuffer.allocate(65536)
        val token = socketManager.enqueueRecv(binding, buf)
        pendingReceives[token] = peerId to binding
        rxBuffers.getOrPut(peerId) { mutableListOf() }.add(buf)
    }

    /**
     * Drain completion queue and dispatch received messages.
     * Call this periodically to process completions from the uring facade.
     */
    fun drainCompletions() {
        val results = facade.peek()
        if (results.isEmpty()) return

        for (result in results) {
            // Check if it's a send completion
            if (pendingSends.containsKey(result.userData)) {
                pendingSends.remove(result.userData)
                // Send completed — could handle errors via result.res
                continue
            }

            // Check if it's a receive completion
            val receiveInfo = pendingReceives[result.userData]
            if (receiveInfo != null) {
                val (peerId, binding) = receiveInfo
                processReceive(peerId, binding, result, rxBuffers[peerId]?.firstOrNull())
                pendingReceives.remove(result.userData)
                // Re-prime for next message
                primeReceive(peerId, binding)
                continue
            }

            // Check if it's a socket creation completion (userData high bits = fd, low = 1)
            if ((result.userData and 0xFFFFL) == 1L) {
                // Socket created — could update state
                continue
            }

            // Check if it's a close completion (low = 2)
            if ((result.userData and 0xFFFFL) == 2L) {
                continue
            }
        }

        // Also submit to flush any remaining operations
        facade.submit()
    }

    /**
     * Process a receive completion: decode buffer and invoke callback.
     */
    private fun processReceive(
        peerId: CharSequence,
        binding: TransportBinding,
        result: SelectionResult,
        buffer: ByteBuffer?,
    ) {
        if (result.res <= 0 || buffer == null) return

        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        buffer.compact()

        val msg = ProtocolCodec.decode(bytes)
        if (msg != null) {
            onDataCallback?.invoke(peerId, msg)
        }
    }
}

// ── 9. BugzeeRelay ───────────────────────────────────────────────────────────

/**
 * Store-and-forward relay across transports.
 * Routes messages based on nodeId + msgType routing table.
 */
class BugzeeRelay(
    private val localNodeId: CharSequence,
    private val networkLayer: BugzeeNetworkLayer,
) {
    /**
     * Routing entry: which peer should receive messages of a given type.
     */
    data class RouteEntry(
        val targetNodeId: CharSequence,
        val msgType: BugzeeMessageType,
        val viaTransport: Transport? = null, // null means any
        val active: Boolean = true,
    )

    /**
     * Pending forwarded messages awaiting acknowledgment.
     */
    data class PendingForward(
        val originalMessage: BugzeeNetworkMessage,
        val targetNodeId: CharSequence,
        val retryCount: Int = 0,
        val timestamp: Long = 0L,
    )

    /** Routing table keyed by nodeId, value is list of route entries. */
    private val routingTable = mutableMapOf<CharSequence, MutableList<RouteEntry>>()

    /** Messages forwarded but not yet acknowledged, keyed by correlationId. */
    private val pendingForwards = mutableMapOf<CharSequence, PendingForward>()

    /** Callback to invoke when a forwarded message is acknowledged. */
    private var onAckCallback: ((CharSequence) -> Unit)? = null

    /**
     * Add a route: messages of type [msgType] destined for [targetNodeId]
     * should be forwarded via the optionally specified transport.
     */
    fun addRoute(targetNodeId: CharSequence, msgType: BugzeeMessageType, via: Transport? = null) {
        val entries = routingTable.getOrPut(targetNodeId) { mutableListOf() }
        // Replace existing route for same msgType
        entries.removeAll { it.msgType == msgType }
        entries.add(RouteEntry(targetNodeId, msgType, via))
    }

    /**
     * Remove all routes for a target node.
     */
    fun removeRoutes(targetNodeId: CharSequence) {
        routingTable.remove(targetNodeId)
    }

    /**
     * Forward a message: store locally, then send via the best route.
     * Returns true if the message was forwarded, false if no route exists.
     */
    fun forward(message: BugzeeNetworkMessage): Boolean {
        val targetNode = message.header.nodeId
        val msgType = message.header.msgType

        val routes = routingTable[targetNode]
        if (routes.isNullOrEmpty()) return false

        // Find the best matching route
        val route = routes
            .filter { it.active && (it.msgType == msgType || it.msgType == BugzeeMessageType.GOSSIP) }
            .firstOrNull()
            ?: return false

        // Re-encode with local forwarding metadata
        val forwarded = message.copy(
            header = message.header.copy(
                transportTag = "${route.viaTransport?.name ?: "AUTO"}|fwd",
                correlationId = message.header.correlationId,
            ),
        )

        // Store in pending for retry
        pendingForwards[message.header.correlationId] = PendingForward(
            originalMessage = message,
            targetNodeId = targetNode,
            timestamp = SystemClock.now(),
        )

        // Send via network layer
        networkLayer.send(targetNode, forwarded)
        return true
    }

    /**
     * Process an incoming message: if it's for us, deliver directly;
     * otherwise, attempt to forward.
     */
    fun onMessage(peerId: CharSequence, message: BugzeeNetworkMessage) {
        when (message.header.msgType) {
            BugzeeMessageType.ACK -> {
                onForwardAck(message.header.correlationId)
            }

            BugzeeMessageType.NACK -> {
                onForwardNack(message.header.correlationId)
            }

            else -> {
                if (message.header.nodeId == localNodeId) {
                    // Message is for us — ACK it
                    val ack = createAck(message, BugzeeMessageType.ACK)
                    networkLayer.send(peerId.toString(), ack)
                } else {
                    // Not for us — try to forward
                    forward(message)
                }
            }
        }
    }

    /**
     * Send an acknowledgment for a received message.
     */
    fun acknowledge(peerId: CharSequence, originalMessage: BugzeeNetworkMessage) {
        val ack = createAck(originalMessage, BugzeeMessageType.ACK)
        networkLayer.send(peerId.toString(), ack)
    }

    /**
     * Handle a forward acknowledgment.
     */
    fun onForwardAck(correlationId: CharSequence) {
        val pending = pendingForwards[correlationId]
        if (pending != null) {
            pendingForwards.remove(correlationId)
            onAckCallback?.invoke(correlationId)
        }
    }

    /**
     * Handle a forward negative acknowledgment — schedule retry.
     */
    fun onForwardNack(correlationId: CharSequence) {
        val pending = pendingForwards[correlationId] ?: return

        if (pending.retryCount < MAX_RETRIES) {
            val retry = pending.copy(retryCount = pending.retryCount + 1)
            pendingForwards[correlationId] = retry
            networkLayer.send(pending.targetNodeId, retry.originalMessage)
        } else {
            pendingForwards.remove(correlationId)
        }
    }

    /**
     * Send a negative acknowledgment for an undeliverable message.
     */
    fun negativeAcknowledge(peerId: CharSequence, originalMessage: BugzeeNetworkMessage) {
        val nack = createAck(originalMessage, BugzeeMessageType.NACK)
        networkLayer.send(peerId.toString(), nack)
    }

    /**
     * Flush all pending forwards — retries messages older than the given threshold.
     */
    fun flushPending(olderThanMs: Long = 5000) {
        val now = SystemClock.now()
        for ((correlationId, pending) in pendingForwards) {
            if (now - pending.timestamp > olderThanMs && pending.retryCount < MAX_RETRIES) {
                val retry = pending.copy(retryCount = pending.retryCount + 1, timestamp = now)
                pendingForwards[correlationId] = retry
                networkLayer.send(pending.targetNodeId, retry.originalMessage)
            }
        }
    }

    /**
     * Register a callback for acknowledgment events.
     */
    fun onAck(callback: (CharSequence) -> Unit) {
        onAckCallback = callback
    }

    /**
     * Get the current routing table snapshot.
     */
    fun getRoutes(): Map<CharSequence, List<RouteEntry>> =
        routingTable.mapValues { it.value.toList() }

    /**
     * Get count of pending forwards.
     */
    val pendingCount: Int get() = pendingForwards.size

    // -- Helpers --

    private fun createAck(original: BugzeeNetworkMessage, type: BugzeeMessageType): BugzeeNetworkMessage =
        BugzeeNetworkMessage(
            header = BugzeeMessageHeader(
                version = original.header.version,
                msgType = type,
                payloadLen = 0,
                checksum = 0L,
                transportTag = original.header.transportTag,
                correlationId = original.header.correlationId,
                timestamp = SystemClock.now(),
                nodeId = localNodeId,
            ),
            payload = byteArrayOf(),
        )

    companion object {
        private const val MAX_RETRIES = 3
    }
}

// ── 10. System clock abstraction for commonMain ─────────────────────────────

/**
 * Minimal clock abstraction for timestamps in commonMain.
 * Platform implementations should provide actual time.
 */
object SystemClock {
    /**
     * Current time in milliseconds since epoch.
     * Default: 0 — override on platform-specific side or inject a real implementation.
     */
    fun now(): Long = currentMillis

    /** Setter for injecting time (useful for testing). */
    var currentMillis: Long = 0L
        internal set
}

// ── Convenience builder ──────────────────────────────────────────────────────

/**
 * Builder for constructing a complete Bugzee transport stack.
 */
class BugzeeTransportBuilder(
    private val facade: FunctionalUringFacade,
    private val localNodeId: CharSequence,
) {
    private var sctpConfig: SctpConfig = SctpConfig()
    private var quicConfig: QuicConfig = QuicConfig()
    private var htxConfig: HtxConfig = HtxConfig()
    private var ipfsConfig: IpfsConfig = IpfsConfig()
    private var maxConnectionsPerPeer: Int = 4
    private var transportSelector = TransportSelector()

    fun withSctp(config: SctpConfig) = apply { sctpConfig = config }
    fun withQuic(config: QuicConfig) = apply { quicConfig = config }
    fun withHtx(config: HtxConfig) = apply { htxConfig = config }
    fun withIpfs(config: IpfsConfig) = apply { ipfsConfig = config }
    fun withMaxConnectionsPerPeer(max: Int) = apply { maxConnectionsPerPeer = max }
    fun withTransportSelector(selector: TransportSelector) = apply { transportSelector = selector }

    /**
     * Build the full stack: SocketManager -> NetworkLayer -> Relay.
     */
    fun build(): BugzeeStack {
        val socketManager = BugzeeSocketManager(
            facade = facade,
            maxConnectionsPerPeer = maxConnectionsPerPeer,
        )

        val defaults = BugzeeNetworkLayer.TransportDefaults(
            sctp = sctpConfig,
            quic = quicConfig,
            htx = htxConfig,
            ipfs = ipfsConfig,
        )

        val networkLayer = BugzeeNetworkLayer(
            facade = facade,
            socketManager = socketManager,
            transportSelector = transportSelector,
            localNodeId = localNodeId,
            defaultConfig = defaults,
        )

        val relay = BugzeeRelay(
            localNodeId = localNodeId,
            networkLayer = networkLayer,
        )

        return BugzeeStack(
            socketManager = socketManager,
            networkLayer = networkLayer,
            relay = relay,
            transportSelector = transportSelector,
        )
    }
}

/**
 * Complete Bugzee transport stack — holds all components together.
 */
data class BugzeeStack(
    val socketManager: BugzeeSocketManager,
    val networkLayer: BugzeeNetworkLayer,
    val relay: BugzeeRelay,
    val transportSelector: TransportSelector,
)
