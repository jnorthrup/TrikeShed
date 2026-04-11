package borg.literbike.ccek.sctp

/**
 * SCTP Socket implementation for reactor integration
 *
 * Provides SctpSocket type that implements SelectableChannel for the Literbike reactor.
 * Supports multi-homing, stream multiplexing, and the 4-way handshake.
 */

/**
 * SelectableChannel interface for reactor integration
 */
interface SelectableChannel {
    fun asRawFd(): Int
    fun rawFd(): Int
    fun isOpen(): Boolean
    fun close(): Result<Unit>
    fun bind(addr: String): Result<Unit>
    fun listen(): Result<Unit>
}

/**
 * SCTP protocol constants
 */
object SctpConstants {
    const val IPPROTO_SCTP: UByte = 132u
    const val SCTP_PORT: UShort = 8888u
}

/**
 * SCTP association state
 */
enum class AssociationState {
    Closed,
    CookieWait,
    CookieEchoed,
    Established,
    ShutdownPending,
    ShutdownSent,
    ShutdownReceived,
    ShutdownAckSent
}

/**
 * SCTP socket wrapper
 */
class SctpSocket(
    private val fd: Int = -1,
    @Volatile var state: AssociationState = AssociationState.Closed,
    val localTag: UInt = generateTag(),
    @Volatile var remoteTag: UInt = 0u,
    @Volatile var localPort: UShort = 0u,
    @Volatile var remotePort: UShort = 0u,
    @Volatile var primaryPath: String? = null,
    val alternatePaths: MutableList<String> = mutableListOf()
) : SelectableChannel {
    companion object {
        /**
         * Create a new SCTP socket bound to the specified port
         * Note: Platform-specific - actual SCTP requires kernel support
         */
        fun bind(port: UShort): Result<SctpSocket> {
            // On non-Linux platforms, SCTP socket creation would use UDP emulation
            // On Linux with SCTP support, use actual SCTP socket
            // For Kotlin multiplatform, this is a stub that would be
            // implemented per-platform in actual use
            return Result.success(
                SctpSocket(
                    fd = -1,
                    localPort = port,
                    state = AssociationState.Closed
                )
            )
        }

        /**
         * Generate a random verification tag
         */
        private fun generateTag(): UInt {
            val nonce = Clocks.System.now()
            // Simple hash-based tag generation
            val hashed = (nonce * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFL
            return hashed.toUInt()
        }
    }

    /**
     * Connect to a remote SCTP endpoint (initiates 4-way handshake)
     */
    fun connect(remote: String): Result<Unit> {
        // Parse remote address
        val parts = remote.split(":")
        if (parts.size != 2) {
            return Result.failure(IllegalArgumentException("Invalid address format: $remote"))
        }
        remotePort = parts[1].toUShortOrNull() ?: return Result.failure(
            IllegalArgumentException("Invalid port in: $remote")
        )
        primaryPath = remote
        state = AssociationState.CookieWait
        return Result.success(Unit)
    }

    /**
     * Send data on a specific stream
     */
    fun send(streamId: UShort, data: ByteArray): Result<Int> {
        if (state != AssociationState.Established) {
            return Result.failure(IllegalStateException("association not established"))
        }

        val chunk = DataChunk(
            flags = DataFlags(DataFlags.BEGIN or DataFlags.END),
            streamId = streamId,
            streamSeqNum = 0u,
            payloadProtocolId = 0u,
            transmissionSeqNum = 0u,
            userData = data
        )

        val packet = chunk.toBytes()
        return sendPacket(packet)
    }

    /**
     * Receive data from any stream
     */
    fun recv(buf: ByteArray): Result<Pair<UShort, Int>> {
        val packetBuf = ByteArray(65536)
        val n = recvPacket(packetBuf).getOrNull() ?: return Result.failure(
            IllegalStateException("Failed to receive packet")
        )

        // Parse the SCTP packet
        return Chunk.fromBytes(packetBuf.sliceArray(0 until n)).map { chunk ->
            when (chunk) {
                is Chunk.Data -> {
                    val len = minOf(buf.size, chunk.chunk.userData.size)
                    chunk.chunk.userData.copyInto(buf, 0, 0, len)
                    chunk.chunk.streamId to len
                }
                else -> throw IllegalStateException("non-DATA chunk received")
            }
        }
    }

    /**
     * Send a raw SCTP packet
     */
    private fun sendPacket(data: ByteArray): Result<Int> {
        // Platform-specific implementation
        // Would use actual socket send on native, or network library on JVM
        return Result.success(data.size)
    }

    /**
     * Receive a raw SCTP packet
     */
    private fun recvPacket(buf: ByteArray): Result<Int> {
        // Platform-specific implementation
        return Result.failure(UnsupportedOperationException("Not implemented for this platform"))
    }

    /**
     * Add an alternate path for multi-homing
     */
    fun addAlternatePath(addr: String) {
        alternatePaths.add(addr)
    }

    /**
     * Get all paths (primary + alternates)
     */
    fun allPaths(): List<String> {
        return buildList {
            primaryPath?.let { add(it) }
            addAll(alternatePaths)
        }
    }

    override fun asRawFd(): Int = fd
    override fun rawFd(): Int = fd
    override fun isOpen(): Boolean = fd >= 0

    override fun close(): Result<Unit> {
        state = AssociationState.Closed
        return Result.success(Unit)
    }

    override fun bind(addr: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("use SctpSocket::bind() instead"))
    }

    override fun listen(): Result<Unit> {
        // Platform-specific - would call listen() on actual socket
        return Result.success(Unit)
    }
}

/**
 * SCTP packet header (12 bytes common header)
 */
data class PacketHeader(
    val srcPort: UShort,
    val dstPort: UShort,
    val verificationTag: UInt,
    val checksum: UInt
) {
    companion object {
        const val SIZE: Int = 12

        fun fromBytes(bytes: ByteArray): Result<PacketHeader> {
            if (bytes.size < SIZE) {
                return Result.failure(IllegalArgumentException("packet header too short"))
            }
            val srcPort = ((bytes[0].toUShort() and 0xFFu) shl 8) or (bytes[1].toUShort() and 0xFFu)
            val dstPort = ((bytes[2].toUShort() and 0xFFu) shl 8) or (bytes[3].toUShort() and 0xFFu)
            val verificationTag = ((bytes[4].toUInt() and 0xFFu) shl 24) or
                    ((bytes[5].toUInt() and 0xFFu) shl 16) or
                    ((bytes[6].toUInt() and 0xFFu) shl 8) or
                    (bytes[7].toUInt() and 0xFFu)
            val checksum = ((bytes[8].toUInt() and 0xFFu) shl 24) or
                    ((bytes[9].toUInt() and 0xFFu) shl 16) or
                    ((bytes[10].toUInt() and 0xFFu) shl 8) or
                    (bytes[11].toUInt() and 0xFFu)
            return Result.success(
                PacketHeader(srcPort, dstPort, verificationTag, checksum)
            )
        }
    }

    fun toBytes(): ByteArray {
        return byteArrayOf(
            ((srcPort.toInt() ushr 8) and 0xFF).toByte(),
            (srcPort.toInt() and 0xFF).toByte(),
            ((dstPort.toInt() ushr 8) and 0xFF).toByte(),
            (dstPort.toInt() and 0xFF).toByte(),
            ((verificationTag.toInt() ushr 24) and 0xFF).toByte(),
            ((verificationTag.toInt() ushr 16) and 0xFF).toByte(),
            ((verificationTag.toInt() ushr 8) and 0xFF).toByte(),
            (verificationTag.toInt() and 0xFF).toByte(),
            ((checksum.toInt() ushr 24) and 0xFF).toByte(),
            ((checksum.toInt() ushr 16) and 0xFF).toByte(),
            ((checksum.toInt() ushr 8) and 0xFF).toByte(),
            (checksum.toInt() and 0xFF).toByte()
        )
    }
}
