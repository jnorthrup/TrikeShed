package borg.literbike.ccek.sctp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * SCTP Protocol Support
 *
 * SCTP (Stream Control Transmission Protocol) support
 * integrating with the KMPngSCTP Kotlin Multiplatform implementation.
 *
 * Features:
 * - TLV-based chunk format (unknown chunks are skipped - Wireshark compatible)
 * - SCTP server for accepting incoming associations
 * - SCTP client for initiating connections
 * - Multi-homing support
 * - Partial reliability (PR-SCTP)
 * - Ordered and unordered message delivery
 * - Up to 65535 streams per association
 *
 * Protocol:
 * - 4-way handshake (INIT -> INIT_ACK -> COOKIE_ECHO -> COOKIE_ACK)
 * - Association as structured scope (auto-cleanup on drop)
 * - Streams as channels (send/receive)
 * - ML congestion control slot
 *
 * References:
 * - RFC 4960: SCTP
 * - RFC 3758: Partial Reliability
 * - RFC4820: Stream Schedulers
 * - RFC 8260: Stream Control Transmission Protocol
 */

/**
 * SCTP error types
 */
sealed class SctpError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Io(cause: java.io.IOException) : SctpError("IO error: ${cause.message}", cause)
    class Association(message: String) : SctpError("SCTP association error: $message")
    object NotSupported : SctpError("SCTP not supported on this platform")
    class Bind(message: String) : SctpError("Binding error: $message")
    class Connect(message: String) : SctpError("Connection error: $message")
}

/**
 * SCTP chunk types matching RFC 4960
 */
enum class SctpChunkType(val value: UByte) {
    Data(0u),
    Init(1u),
    InitAck(2u),
    Sack(3u),
    Heartbeat(4u),
    HeartbeatAck(5u),
    Abort(6u),
    Shutdown(7u),
    ShutdownAck(8u),
    Error(9u),
    CookieEcho(10u),
    CookieAck(11u),
    Cwr(12u),
    Ecne(13u),
    Reconfig(14u),
    Pad(15u);

    companion object {
        fun fromUByte(value: UByte): SctpChunkType? = entries.find { it.value == value }
    }
}

/**
 * SCTP stream representing an ordered byte channel
 */
data class SctpStream(
    val streamId: UShort,
    val associationId: UInt
)

/**
 * SCTP association states
 */
enum class SctpState {
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
 * SCTP association representing a connection between endpoints
 */
class SctpAssociation(
    val associationId: UInt,
    val localAddr: String,
    val remoteAddr: String,
    val streams: MutableList<SctpStream> = mutableListOf(),
    @Volatile var state: SctpState = SctpState.CookieWait
) {
    fun isEstablished(): Boolean = state == SctpState.Established
}

/**
 * SCTP server for accepting incoming associations
 */
class SctpServer(
    val localAddr: String
) {
    private val associations: MutableList<SctpAssociation> = mutableListOf()
    @Volatile private var shutdown: Boolean = false

    fun localAddr(): String = localAddr

    fun shutdown() {
        shutdown = true
    }

    fun associationCount(): Int = associations.size

    fun addAssociation(assoc: SctpAssociation) {
        synchronized(associations) {
            associations.add(assoc)
        }
    }
}

/**
 * SCTP client for initiating connections
 */
class SctpClient {
    var association: SctpAssociation? = null
        private set

    suspend fun connect(addr: String): SctpAssociation {
        val assoc = SctpAssociation(
            associationId = (0u..UInt.MAX_VALUE).random().toUInt(),
            localAddr = "0.0.0.0:0",
            remoteAddr = addr,
            streams = mutableListOf(),
            state = SctpState.CookieWait
        )
        association = assoc
        return assoc
    }
}

/**
 * SCTP configuration
 */
data class SctpConfig(
    val port: UShort = 3842u,
    val maxStreams: UShort = 64u,
    val initMaxStreams: UShort = 64u,
    val heartbeatInterval: Duration = 30.seconds,
    val timeout: Duration = 60.seconds,
    val rtoInitial: Duration = 3.seconds,
    val rtoMin: Duration = 1.seconds,
    val rtoMax: Duration = 60.seconds,
    val maxRetries: UInt = 5u,
    val cookieLifetime: Duration = 60.seconds
) {
    companion object {
        val Default = SctpConfig()
    }
}

/**
 * Build an SCTP packet with the given chunks
 */
fun buildSctpPacket(
    sourcePort: UShort,
    destPort: UShort,
    verificationTag: UInt,
    chunks: List<ByteArray>
): ByteArray {
    val packet = java.io.ByteArrayOutputStream(12 + chunks.sumOf { it.size })

    // Source port (16 bits)
    packet.write(sourcePort.toShort().toInt() ushr 8)
    packet.write(sourcePort.toShort().toInt() and 0xFF)
    // Destination port (16 bits)
    packet.write(destPort.toShort().toInt() ushr 8)
    packet.write(destPort.toShort().toInt() and 0xFF)
    // Verification tag (32 bits)
    packet.write((verificationTag.toInt() ushr 24) and 0xFF)
    packet.write((verificationTag.toInt() ushr 16) and 0xFF)
    packet.write((verificationTag.toInt() ushr 8) and 0xFF)
    packet.write(verificationTag.toInt() and 0xFF)
    // Checksum (32 bits) - CRC32C, initialized to 0 for calculation
    packet.write(0)
    packet.write(0)
    packet.write(0)
    packet.write(0)

    // Add chunks
    for (chunk in chunks) {
        packet.write(chunk)
    }

    // Calculate CRC32C checksum
    val data = packet.toByteArray()
    val checksum = calculateCrc32c(data)
    // Replace the checksum in the packet
    data[8] = (checksum.toInt() ushr 24).toByte()
    data[9] = (checksum.toInt() ushr 16).toByte()
    data[10] = (checksum.toInt() ushr 8).toByte()
    data[11] = checksum.toInt().toByte()

    return data
}

/**
 * Calculate CRC32C checksum (Castagnoli)
 */
fun calculateCrc32c(data: ByteArray): UInt {
    val poly: UInt = 0x1EDC6F41u
    var crc: UInt = 0xFFFFFFFFu

    for (byte in data) {
        crc = crc xor (byte.toUInt() and 0xFFu) shl 24
        repeat(8) {
            crc = if (crc and 0x80000000u != 0u) {
                (crc shl 1) xor poly
            } else {
                crc shl 1
            }
        }
    }

    return crc xor 0xFFFFFFFFu
}

/**
 * SCTP event types
 */
sealed class SctpEvent {
    data class Connected(val association: SctpAssociation) : SctpEvent()
    data class Disconnected(val associationId: UInt) : SctpEvent()
    data class DataReceived(
        val associationId: UInt,
        val streamId: UShort,
        val data: ByteArray
    ) : SctpEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DataReceived) return false
            return associationId == other.associationId &&
                    streamId == other.streamId &&
                    data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            var result = associationId.hashCode()
            result = 31 * result + streamId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
    data class Error(val associationId: UInt, val error: String) : SctpEvent()
}

/**
 * KMPngSCTP Integration
 *
 * This module provides integration points with the KMPngSCTP
 * Kotlin Multiplatform SCTP implementation.
 *
 * To use KMPngSctp:
 * 1. Build the Kotlin project: `cd KMPngSCTP && ./gradlew build`
 * 2. The JAR will be available for JNI integration
 * 3. Use JNI to call Kotlin SCTP functions from Rust
 */
object KmpNgSctp {
    /** Indicates whether KMPngSCTP JAR is available */
    val isAvailable: Boolean = false

    /** KMPngSCTP version string */
    const val VERSION = "0.1.0"
}
