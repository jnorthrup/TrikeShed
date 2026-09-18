package com.ngsctp.protocol

import kotlinx.serialization.Serializable

/**
 * ngSCTP - Next Generation SCTP Protocol
 * 
 * A modern SCTP implementation with:
 * - Multi-homing support built-in
 * - Native connection migration
 * - Cleartext control plane (unlike QUIC)
 * - Structured concurrency via Kotlin coroutines
 * - io_uring for high-performance I/O
 */

// ============================================
// Core Protocol Types
// ============================================

/** SCTP Port numbers */
typealias SctpPort = UShort

/** Verification Tag for endpoint verification */
typealias VerificationTag = UInt

/** Stream Identifier */
typealias StreamId = UShort

/** Transmission Sequence Number */
typealias TSN = UInt

/** Association ID */
@Serializable
data class AssociationId(
    val localTag: VerificationTag,
    val remoteTag: VerificationTag,
    val localPort: SctpPort,
    val remotePort: SctpPort
)

/** SCTP Chunk Types */
enum class ChunkType(val value: UByte) {
    DATA(0x00),
    INIT(0x01),
    INIT_ACK(0x02),
    SACK(0x03),
    HEARTBEAT(0x04),
    HEARTBEAT_ACK(0x05),
    ABORT(0x06),
    SHUTDOWN(0x07),
    SHUTDOWN_ACK(0x08),
    ERROR(0x09),
    COOKIE_ECHO(0x0A),
    COOKIE_ACK(0x0B),
    ECNE(0x0C),
    CWR(0x0D),
    SHUTDOWN_COMPLETE(0x0E),
    // ngSCTP extension types
    RE_CONFIG(0x82),
    FORWARD_TSN(0xC0),
    I_DATA(0xD0),
    ASCONF_ACK(0x80),
    ASCONF(0x81);
    
    companion object {
        fun fromByte(b: UByte): ChunkType? = entries.find { it.value == b }
    }
}

/** Chunk Flags */
data class ChunkFlags(val value: UByte) {
    val isEndOfFragment: Boolean get() = (value and 0x01u) != 0.u
    val isBeginningOfFragment: Boolean get() = (value and 0x02u) != 0.u
    val isUnordered: Boolean get() = (value and 0x04u) != 0.u
    val isImmediateSack: Boolean get() = (value and 0x01u) != 0.u
    
    companion object {
        fun empty() = ChunkFlags(0u)
    }
}

/** Parameter Types */
enum class ParameterType(val value: UShort) {
    HEARTBEAT_INFO(0x0001),
    STATE_COOKIE(0x0007),
    UNRECOGNIZED_PARAMETERS(0x0008),
    COOKIE_PRESERVATIVE(0x0009),
    HOST_NAME_ADDRESS(0x000B),
    SUPPORTED_ADDRESS_TYPES(0x000C),
    INBOUND_STREAMS(0x000D),
    OUTBOUND_STREAMS(0x000E),
    INITIAL_TSN(0x000F),
    FORWARD_TSN_SUPPORTED(0x8000),
    RELIABILITY_SUPPORTED(0x8001),
    PR_SCTP_SUPPORTED(0x8002),
    NEGOTIATED_MAXIMUM_INBOUND_STREAMS(0x8003),
    NEGOTIATED_MAXIMUM_OUTBOUND_STREAMS(0x8004);
    
    companion object {
        fun fromShort(s: UShort): ParameterType? = entries.find { it.value == s }
    }
}

// ============================================
// Common Headers
// ============================================

/** SCTP Common Header (12 bytes) */
@Serializable
data class SctpCommonHeader(
    val sourcePort: SctpPort,
    val destinationPort: SctpPort,
    val verificationTag: VerificationTag,
    val checksum: UInt
) {
    companion object {
        const val SIZE = 12
        
        /** Parse from ByteBuffer */
        fun parse(buffer: java.nio.ByteBuffer): SctpCommonHeader {
            return SctpCommonHeader(
                sourcePort = buffer.getShort().toUShort(),
                destinationPort = buffer.getShort().toUShort(),
                verificationTag = buffer.getInt().toUInt(),
                checksum = buffer.getInt().toUInt()
            )
        }
    }
    
    /** Serialize to ByteBuffer */
    fun serialize(buffer: java.nio.ByteBuffer) {
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())
        buffer.putInt(verificationTag.toInt())
        buffer.putInt(checksum.toInt())
    }
    
    /** Serialize to ByteArray */
    fun serialize(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(SIZE)
        serialize(buffer)
        return buffer.array()
    }
}

/** Chunk Header */
data class ChunkHeader(
    val type: ChunkType,
    val flags: ChunkFlags,
    val length: UShort
) {
    companion object {
        const val SIZE = 4
    }
}

/** Parameter Header */
data class ParameterHeader(
    val type: ParameterType,
    val length: UShort
) {
    companion object {
        const val SIZE = 4
    }
}

// ============================================
// Chunk Definitions
// ============================================

/** INIT Chunk */
@Serializable
data class InitChunk(
    val initiateTag: VerificationTag,
    val initialTSN: TSN,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val fixedParameter: UInt, // Forward TSN support indicator
    val parameters: List<SctpParameter> = emptyList()
) {
    companion object {
        const val MIN_SIZE = 16
    }
}

/** INIT-ACK Chunk */
@Serializable  
data class InitAckChunk(
    val initiateTag: VerificationTag,
    val initialTSN: TSN,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val stateCookie: ByteArray,
    val parameters: List<SctpParameter> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InitAckChunk) return false
        return stateCookie.contentEquals(other.stateCookie)
    }
    
    override fun hashCode(): Int = stateCookie.contentHashCode()
    
    companion object {
        const val MIN_SIZE = 16
    }
}

/** DATA Chunk */
@Serializable
data class DataChunk(
    val streamId: StreamId,
    val streamSequenceNumber: UShort,
    val payloadProtocolId: UInt,
    val transmissionSequenceNumber: TSN,
    val userData: ByteArray,
    val unordered: Boolean = false,
    val beginning: Boolean = true,
    val end: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataChunk) return false
        return userData.contentEquals(other.userData)
    }
    
    override fun hashCode(): Int = userData.contentHashCode()
    
    companion object {
        const val MIN_SIZE = 16
    }
}

/** SACK Chunk */
@Serializable
data class SackChunk(
    val cumulativeTSNAck: TSN,
    val advertisedReceiverWindowCredit: UInt,
    val gapAckBlocks: List<GapAckBlock> = emptyList(),
    val duplicateTSNs: List<TSN> = emptyList()
) {
    companion object {
        const val MIN_SIZE = 16
    }
}

/** Gap Ack Block */
@Serializable
data class GapAckBlock(
    val start: UShort,
    val end: UShort
)

// ============================================
// Parameters
// ============================================

sealed class SctpParameter {
    abstract val type: ParameterType
    
    data class HeartbeatInfo(val heartbeatInfo: ByteArray) : SctpParameter() {
        override val type = ParameterType.HEARTBEAT_INFO
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is HeartbeatInfo && heartbeatInfo.contentEquals(other.heartbeatInfo)
        }
        override fun hashCode() = heartbeatInfo.contentHashCode()
    }
    
    data class StateCookie(val cookie: ByteArray) : SctpParameter() {
        override val type = ParameterType.STATE_COOKIE
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is StateCookie && cookie.contentEquals(other.cookie)
        }
        override fun hashCode() = cookie.contentHashCode()
    }
    
    data class InboundStreams(val streams: UShort) : SctpParameter() {
        override val type = ParameterType.INBOUND_STREAMS
    }
    
    data class OutboundStreams(val streams: UShort) : SctpParameter() {
        override val type = ParameterType.OUTBOUND_STREAMS
    }
    
    data class InitialTSN(val tsn: TSN) : SctpParameter() {
        override val type = ParameterType.INITIAL_TSN
    }
    
    data class ForwardTSNSupported(val supported: Boolean = true) : SctpParameter() {
        override val type = ParameterType.FORWARD_TSN_SUPPORTED
    }
}

// ============================================
// Association State
// ============================================

enum class AssociationState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED,
    SHUTDOWN_ACK_SENT
}

/**
 * SCTP Association - represents a connection between two endpoints
 */
data class SctpAssociation(
    val id: AssociationId,
    val state: AssociationState,
    val localTag: VerificationTag,
    val remoteTag: VerificationTag,
    val initialTSN: TSN,
    val nextTSN: TSN,
    val lastAcknowledgedTSN: TSN,
    val localRwnd: UInt,
    val remoteRwnd: UInt,
    val outboundStreams: UShort,
    val inboundStreams: UShort,
    val primaryPath: TransportAddress,
    val alternatePaths: List<TransportAddress> = emptyList(),
    val streams: Map<StreamId, Stream> = emptyMap()
)

/**
 * Transport address for SCTP multi-homing
 */
@Serializable
data class TransportAddress(
    val address: String,  // IP address
    val port: SctpPort,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true
)

/**
 * Stream within an association
 */
data class Stream(
    val id: StreamId,
    val nextSequenceNumber: UShort,
    val messages: List<SctpMessage> = emptyList()
)

/**
 * Complete SCTP message (application-level)
 */
@Serializable
data class SctpMessage(
    val streamId: StreamId,
    val streamSequenceNumber: UShort,
    val payloadProtocolId: UInt,
    val userData: ByteArray,
    val unordered: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is SctpMessage && userData.contentEquals(other.userData)
    }
    
    override fun hashCode(): Int = userData.contentHashCode()
}

/**
 * SCTP Packet - complete wire format with header and chunks
 */
data class SctpPacket(
    val header: SctpCommonHeader,
    val chunks: List<dev.jnorthrup.ngsctp.NgChunk>,
    val remote: TransportAddress
)

/**
 * SCTP Transport interface for sending/receiving packets
 */
interface SctpTransport {
    suspend fun send(data: ByteArray, remote: java.net.InetSocketAddress)
    fun receive(): Flow<ByteArray>
}
