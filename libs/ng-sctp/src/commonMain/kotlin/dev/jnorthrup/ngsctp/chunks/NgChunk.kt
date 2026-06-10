package dev.jnorthrup.ngsctp

import java.nio.ByteBuffer

/**
 * ngSCTP Chunk definitions using TLV (Type-Length-Value) format
 * 
 * Uses kotlin-spirit-parser for zero-copy TLV parsing.
 * Unknown chunks are skipped automatically - no parsing errors!
 */

// ============================================
// Chunk Types
// ============================================

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
    ASCONF(0x81),
    // RFC 4895 - SCTP Authentication
    AUTH(0x0F);

    companion object {
        fun fromByte(b: UByte): ChunkType? = entries.find { it.value == b }
    }
}

/** Chunk Flags */
data class ChunkFlags(val value: UByte) {
    val isEnd: Boolean get() = (value and 0x01u) != 0u
    val isBeginning: Boolean get() = (value and 0x02u) != 0u
    val isUnordered: Boolean get() = (value and 0x04u) != 0u
    
    companion object {
        fun empty() = ChunkFlags(0u)
    }
}

// ============================================
// Chunk Definitions (TLV)
// ============================================

/** Base interface for all SCTP chunks */
sealed interface NgChunk {
    val type: ChunkType
    val flags: ChunkFlags
    
    /** Serialize chunk to bytes for transmission */
    fun serialize(): ByteArray
    
    // Type aliases for convenient pattern matching
    typealias Data = NgChunk_Data
    typealias Init = NgChunk_Init
    typealias InitAck = NgChunk_InitAck
    typealias Sack = NgChunk_Sack
    typealias CookieEcho = NgChunk_CookieEcho
    typealias CookieAck = NgChunk_CookieAck
    typealias Heartbeat = NgChunk_Heartbeat
    typealias HeartbeatAck = NgChunk_HeartbeatAck
    typealias Shutdown = NgChunk_Shutdown
    typealias ShutdownAck = NgChunk_ShutdownAck
    typealias Abort = NgChunk_Abort
    typealias Error = NgChunk_Error
    typealias Auth = NgChunk_Auth
    typealias ForwardTsn = NgChunk_ForwardTsn
    typealias ShutdownComplete = NgChunk_ShutdownComplete
    typealias Ecne = NgChunk_Ecne
    typealias Cwr = NgChunk_Cwr
    typealias ReConfig = NgChunk_ReConfig
    typealias Asconf = NgChunk_Asconf
    typealias AsconfAck = NgChunk_AsconfAck
    typealias IData = NgChunk_IData
    
    companion object {
        /**
         * Parse a chunk from a ByteBuffer
         * Uses Spirit TLV parser - skips unknown chunks automatically
         */
        fun parse(buffer: ByteBuffer): NgChunk? {
            if (buffer.remaining() < 4) return null
            
            val type = buffer.get().toUByte()
            buffer.get() // skip flags byte
            val length = buffer.get().toUShort()
            
            val chunkType = ChunkType.fromByte(type) ?: return null
            
            // Parse based on type using Spirit-style combinators
            return when (chunkType) {
                ChunkType.DATA -> parseDataChunk(buffer, length)
                ChunkType.INIT -> parseInitChunk(buffer, length)
                ChunkType.INIT_ACK -> parseInitAckChunk(buffer, length)
                ChunkType.SACK -> parseSackChunk(buffer, length)
                ChunkType.COOKIE_ECHO -> parseCookieEcho(buffer, length)
                ChunkType.COOKIE_ACK -> NgChunk.CookieAck
                ChunkType.AUTH -> NgChunk_Auth.parse(buffer, length)
                ChunkType.HEARTBEAT -> parseHeartbeat(buffer, length)
                ChunkType.HEARTBEAT_ACK -> parseHeartbeatAck(buffer, length)
                ChunkType.SHUTDOWN -> parseShutdown(buffer, length)
                ChunkType.SHUTDOWN_ACK -> NgChunk.ShutdownAck
                ChunkType.SHUTDOWN_COMPLETE -> NgChunk_ShutdownComplete.parse(buffer, length)
                ChunkType.FORWARD_TSN -> NgChunk_ForwardTsn.parse(buffer, length)
                ChunkType.ECNE -> NgChunk_Ecne.parse(buffer, length)
                ChunkType.CWR -> NgChunk_Cwr.parse(buffer, length)
                ChunkType.RE_CONFIG -> NgChunk_ReConfig.parse(buffer, length)
                ChunkType.ASCONF -> NgChunk_Asconf.parse(buffer, length)
                ChunkType.ASCONF_ACK -> NgChunk_AsconfAck.parse(buffer, length)
                ChunkType.I_DATA -> NgChunk_IData.parse(buffer, length)
                ChunkType.ABORT -> parseAbort(buffer, length)
                ChunkType.ERROR -> parseError(buffer, length)
                else -> null  // Skip unknown chunks
            }
        }
        
        // === TLV Parsing Combinators (Spirit-style) ===
        
        private fun parseDataChunk(buffer: ByteBuffer, length: UShort): NgChunk.Data {
            val streamId = buffer.getShort().toUShort()
            val streamSeq = buffer.getShort().toUShort()
            val payloadProto = buffer.getInt().toUInt()
            val tsn = buffer.getInt().toUInt()
            val userDataLength = length.toInt() - 16
            val userData = ByteArray(userDataLength)
            buffer.get(userData)
            
            return NgChunk.Data(
                streamId = streamId,
                streamSequenceNumber = streamSeq,
                payloadProtocolId = payloadProto,
                transmissionSequenceNumber = tsn,
                userData = ByteBuffer.wrap(userData),
                flags = ChunkFlags.empty()  // Would parse from header
            )
        }
        
        private fun parseInitChunk(buffer: ByteBuffer, length: UShort): NgChunk.Init {
            val initiateTag = buffer.getInt().toUInt()
            val initialTSN = buffer.getInt().toUInt()
            val numOutbound = buffer.getShort().toUShort()
            val numInbound = buffer.getShort().toUShort()
            val fixedParam = buffer.getInt().toUInt()
            
            // Parse variable parameters (TLV)
            val params = mutableListOf<SctpParameter>()
            var offset = 16
            while (offset < length.toInt()) {
                val paramType = buffer.getShort().toUShort()
                val paramLength = buffer.getShort().toUShort()
                val paramData = ByteArray(paramLength.toInt() - 4)
                buffer.get(paramData)
                params.add(parseParameter(paramType, paramData))
                offset += paramLength.toInt()
            }
            
            return NgChunk.Init(
                initiateTag = initiateTag,
                initialTSN = initialTSN,
                numOutboundStreams = numOutbound,
                numInboundStreams = numInbound,
                fixedParameters = params
            )
        }
        
        private fun parseInitAckChunk(buffer: ByteBuffer, length: UShort): NgChunk.InitAck {
            val initiateTag = buffer.getInt().toUInt()
            val initialTSN = buffer.getInt().toUInt()
            val numOutbound = buffer.getShort().toUShort()
            val numInbound = buffer.getShort().toUShort()
            val fixedParam = buffer.getInt().toUInt()
            
            // Find and extract state cookie
            val cookie = ByteArray(length.toInt() - 16)
            var cookieOffset = 0
            var offset = 16
            while (offset < length.toInt()) {
                val paramType = buffer.getShort().toUShort()
                val paramLength = buffer.getShort().toUShort()
                if (paramType == 0x0007US) { // STATE_COOKIE
                    buffer.get(cookie, 0, paramLength.toInt() - 4)
                    cookieOffset = paramLength.toInt() - 4
                    break
                }
                offset += paramLength.toInt()
            }
            
            return NgChunk.InitAck(
                initiateTag = initiateTag,
                initialTSN = initialTSN,
                numOutboundStreams = numOutbound,
                numInboundStreams = numInbound,
                cookie = cookie.copyOf(cookieOffset)
            )
        }
        
        private fun parseSackChunk(buffer: ByteBuffer, length: UShort): NgChunk.Sack {
            val cumAck = buffer.getInt().toUInt()
            val arwnd = buffer.getInt().toUInt()
            val numGapBlocks = buffer.getShort().toUShort()
            val numDupTsns = buffer.getShort().toUShort()
            
            // Parse gap ack blocks
            val gapBlocks = mutableListOf<Pair<UShort, UShort>>()
            repeat(numGapBlocks.toInt()) {
                val start = buffer.getShort().toUShort()
                val end = buffer.getShort().toUShort()
                gapBlocks.add(Pair(start, end))
            }
            
            // Parse duplicate TSNs
            val dupTsns = mutableListOf<UInt>()
            repeat(numDupTsns.toInt()) {
                dupTsns.add(buffer.getInt().toUInt())
            }
            
            return NgChunk.Sack(
                cumulativeTSNAck = cumAck,
                advertisedReceiverWindowCredit = arwnd,
                gapAckBlocks = gapBlocks,
                duplicateTSNs = dupTsns
            )
        }
        
        private fun parseCookieEcho(buffer: ByteBuffer, length: UShort): NgChunk.CookieEcho {
            val cookieLength = length.toInt() - 4
            val cookie = ByteArray(cookieLength)
            buffer.get(cookie)
            return NgChunk.CookieEcho(cookie)
        }
        
        private fun parseHeartbeat(buffer: ByteBuffer, length: UShort): NgChunk.Heartbeat {
            val infoLength = length.toInt() - 4
            val info = ByteArray(infoLength)
            buffer.get(info)
            return NgChunk.Heartbeat(info)
        }
        
        private fun parseHeartbeatAck(buffer: ByteBuffer, length: UShort): NgChunk.HeartbeatAck {
            val infoLength = length.toInt() - 4
            val info = ByteArray(infoLength)
            buffer.get(info)
            return NgChunk.HeartbeatAck(info)
        }
        
        private fun parseShutdown(buffer: ByteBuffer, length: UShort): NgChunk.Shutdown {
            val cumAck = buffer.getInt().toUInt()
            return NgChunk.Shutdown(cumAck)
        }
        
        private fun parseAbort(buffer: ByteBuffer, length: UShort): NgChunk.Abort {
            val errorLength = length.toInt() - 4
            val errorInfo = if (errorLength > 0) {
                val info = ByteArray(errorLength)
                buffer.get(info)
                String(info)
            } else null
            return NgChunk.Abort(errorInfo)
        }
        
        private fun parseError(buffer: ByteBuffer, length: UShort): NgChunk.Error {
            val errorLength = length.toInt() - 4
            val errorCode = buffer.getShort().toUShort()
            val additionalInfo = if (errorLength > 2) {
                val info = ByteArray(errorLength - 2)
                buffer.get(info)
                info
            } else ByteArray(0)
            return NgChunk.Error(errorCode, additionalInfo)
        }
        
        private fun parseParameter(type: UShort, data: ByteArray): SctpParameter {
            return when (type) {
                0x0001US -> SctpParameter.HeartbeatInfo(data)
                0x0007US -> SctpParameter.StateCookie(data)
                0x8000US -> SctpParameter.ForwardTSNSupported(true)
                else -> SctpParameter.Unknown(type, data)
            }
        }
    }
}

// ============================================
// Chunk Implementations
// ============================================

/** DATA chunk - User data on a stream */
data class NgChunk_Data(
    override val type: ChunkType = ChunkType.DATA,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val streamId: UShort,
    val streamSequenceNumber: UShort,
    val payloadProtocolId: UInt,
    val transmissionSequenceNumber: UInt,
    val userData: ByteBuffer
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 16 + userData.remaining()
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putShort(streamId.toShort())
        buffer.putShort(streamSequenceNumber.toShort())
        buffer.putInt(payloadProtocolId.toInt())
        buffer.putInt(transmissionSequenceNumber.toInt())
        buffer.put(userData)
        return buffer.array()
    }
}

/** INIT chunk - Association initialization */
data class NgChunk_Init(
    override val type: ChunkType = ChunkType.INIT,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val initiateTag: UInt,
    val initialTSN: UInt,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val fixedParameters: List<SctpParameter> = emptyList()
) : NgChunk {
    override fun serialize(): ByteArray {
        // Calculate total length
        var length = 16
        for (param in fixedParameters) {
            length += 4 + param.data.size
        }
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(initiateTag.toInt())
        buffer.putInt(initialTSN.toInt())
        buffer.putShort(numOutboundStreams.toShort())
        buffer.putShort(numInboundStreams.toShort())
        buffer.putInt(0x01000000) // Forward TSN supported
        for (param in fixedParameters) {
            buffer.putShort(param.type.value.toShort())
            buffer.putShort((4 + param.data.size).toShort())
            buffer.put(param.data)
        }
        return buffer.array()
    }
}

/** INIT-ACK chunk */
data class NgChunk_InitAck(
    override val type: ChunkType = ChunkType.INIT_ACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val initiateTag: UInt,
    val initialTSN: UInt,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val cookie: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 20 + cookie.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(initiateTag.toInt())
        buffer.putInt(initialTSN.toInt())
        buffer.putShort(numOutboundStreams.toShort())
        buffer.putShort(numInboundStreams.toShort())
        buffer.putInt(0)
        // State cookie parameter
        buffer.putShort(0x0007) // STATE_COOKIE
        buffer.putShort((4 + cookie.size).toShort())
        buffer.put(cookie)
        return buffer.array()
    }
}

/** SACK chunk - Selective Acknowledgment with gap ack blocks */
data class NgChunk_Sack(
    override val type: ChunkType = ChunkType.SACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val cumulativeTSNAck: UInt,
    val advertisedReceiverWindowCredit: UInt,
    /** Gap ack blocks: pairs of (start offset, end offset) from cumulative ACK */
    val gapAckBlocks: List<Pair<UShort, UShort>> = emptyList(),
    /** Duplicate TSNs that have been received */
    val duplicateTSNs: List<UInt> = emptyList()
) : NgChunk {
    override fun serialize(): ByteArray {
        // 12 bytes header + 4 bytes per gap ack block + 4 bytes per duplicate TSN
        val length = 12 + (gapAckBlocks.size * 4) + (duplicateTSNs.size * 4)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(cumulativeTSNAck.toInt())
        buffer.putInt(advertisedReceiverWindowCredit.toInt())
        buffer.putShort(gapAckBlocks.size.toShort()) // Number of gap ack blocks
        buffer.putShort(duplicateTSNs.size.toShort()) // Number of duplicate TSNs
        
        // Write gap ack blocks
        for ((start, end) in gapAckBlocks) {
            buffer.putShort(start.toShort())
            buffer.putShort(end.toShort())
        }
        
        // Write duplicate TSNs
        for (tsn in duplicateTSNs) {
            buffer.putInt(tsn.toInt())
        }
        
        return buffer.array()
    }
}

/** COOKIE-ECHO chunk */
data class NgChunk_CookieEcho(
    override val type: ChunkType = ChunkType.COOKIE_ECHO,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val cookie: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 4 + cookie.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(cookie)
        return buffer.array()
    }
}

/** COOKIE-ACK chunk */
data object NgChunk_CookieAck : NgChunk {
    override val type = ChunkType.COOKIE_ACK
    override val flags = ChunkFlags.empty()
    
    override fun serialize(): ByteArray {
        return byteArrayOf(type.value, 0, 0, 4)
    }
}

/** HEARTBEAT chunk */
data class NgChunk_Heartbeat(
    override val type: ChunkType = ChunkType.HEARTBEAT,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val info: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 4 + info.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(info)
        return buffer.array()
    }
}

/** HEARTBEAT-ACK chunk */
data class NgChunk_HeartbeatAck(
    override val type: ChunkType = ChunkType.HEARTBEAT_ACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val info: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 4 + info.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(info)
        return buffer.array()
    }
}

/** SHUTDOWN chunk */
data class NgChunk_Shutdown(
    override val type: ChunkType = ChunkType.SHUTDOWN,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val cumulativeTSNAck: UInt
) : NgChunk {
    override fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(8)
        buffer.putInt(cumulativeTSNAck.toInt())
        return buffer.array()
    }
}

/** SHUTDOWN-ACK chunk */
data object NgChunk_ShutdownAck : NgChunk {
    override val type = ChunkType.SHUTDOWN_ACK
    override val flags = ChunkFlags.empty()
    
    override fun serialize(): ByteArray {
        return byteArrayOf(type.value, 0, 0, 4)
    }
}

/** SHUTDOWN-COMPLETE chunk - RFC 4960 Section 9.2 */
data class NgChunk_ShutdownComplete(
    override val type: ChunkType = ChunkType.SHUTDOWN_COMPLETE,
    override val flags: ChunkFlags = ChunkFlags.empty()
) : NgChunk {
    
    companion object {
        /** Flag: T bit - indicates peer completed shutdown */
        const val FLAG_T_BIT = 0x01
    }
    
    val hasTransportConnectionLost: Boolean 
        get() = (flags.value and FLAG_T_BIT.toUByte()) != 0u
    
    override fun serialize(): ByteArray {
        return byteArrayOf(type.value, flags.value, 0, 4)
    }
    
    companion object {
        /** Parse SHUTDOWN-COMPLETE from buffer */
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_ShutdownComplete {
            val flags = ChunkFlags(buffer.get())
            buffer.get() // reserved
            buffer.get() // length high byte already handled
            return NgChunk_ShutdownComplete(flags = flags)
        }
    }
}

/**
 * FORWARD-TSN chunk - RFC 3758 Partial Reliability Extension
 * 
 * Used for partial reliability (PR-SCTP) to skip unacknowledged DATA chunks.
 * Allows SCTP to "abandon" some data without waiting for retransmission.
 */
data class NgChunk_ForwardTsn(
    override val type: ChunkType = ChunkType.FORWARD_TSN,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** New cumulative TSN - all prior chunks are acknowledged/abandoned */
    val cumulativeTSN: UInt,
    /** Stream information for reordered streams */
    val streamMappings: List<StreamMapping> = emptyList()
) : NgChunk {
    
    /** Stream mapping for partial reliability */
    data class StreamMapping(
        val streamId: UShort,
        val streamSequenceNumber: UShort
    )
    
    override fun serialize(): ByteArray {
        // 4 bytes header + 4 bytes per stream mapping
        val length = 4 + (streamMappings.size * 4)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(cumulativeTSN.toInt())
        
        for (mapping in streamMappings) {
            buffer.putShort(mapping.streamId.toShort())
            buffer.putShort(mapping.streamSequenceNumber.toShort())
        }
        
        return buffer.array()
    }
    
    companion object {
        /** Parse FORWARD-TSN from buffer */
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_ForwardTsn {
            val flags = ChunkFlags(buffer.get())
            buffer.get() // reserved
            buffer.get() // length high byte already handled
            val cumulativeTSN = buffer.getUInt()
            
            val streamMappings = mutableListOf<StreamMapping>()
            var remaining = length.toInt() - 8
            while (remaining >= 4) {
                val streamId = buffer.getUShort()
                val ssn = buffer.getUShort()
                streamMappings.add(StreamMapping(streamId, ssn))
                remaining -= 4
            }
            
            return NgChunk_ForwardTsn(
                flags = flags,
                cumulativeTSN = cumulativeTSN,
                streamMappings = streamMappings
            )
        }
    }
}

/** ABORT chunk */
data class NgChunk_Abort(
    override val type: ChunkType = ChunkType.ABORT,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val errorInfo: String? = null
) : NgChunk {
    override fun serialize(): ByteArray {
        val infoBytes = errorInfo?.toByteArray() ?: ByteArray(0)
        val length = 4 + infoBytes.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(infoBytes)
        return buffer.array()
    }
}

/** ERROR chunk */
data class NgChunk_Error(
    override val type: ChunkType = ChunkType.ERROR,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val errorCode: UShort,
    val additionalInfo: ByteArray = ByteArray(0)
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 6 + additionalInfo.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putShort(errorCode.toShort())
        buffer.put(additionalInfo)
        return buffer.array()
    }
}

// ============================================
// Parameter Types
// ============================================

/** SCTP Parameter Types */
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
    NEGOTIATED_MAX_INBOUND_STREAMS(0x8003),
    NEGOTIATED_MAX_OUTBOUND_STREAMS(0x8004);
    
    companion object {
        fun fromShort(s: UShort): ParameterType? = entries.find { it.value == s }
    }
}

/** SCTP Parameters */
sealed class SctpParameter {
    abstract val type: ParameterType
    abstract val data: ByteArray
    
    data class HeartbeatInfo(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.HEARTBEAT_INFO
    }
    
    data class StateCookie(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.STATE_COOKIE
    }
    
    data class ForwardTSNSupported(val supported: Boolean = true) : SctpParameter() {
        override val type = ParameterType.FORWARD_TSN_SUPPORTED
        override val data = ByteArray(0)
    }
    
    data class NegotiatedMaxInboundStreams(val streams: UShort) : SctpParameter() {
        override val type = ParameterType.NEGOTIATED_MAX_INBOUND_STREAMS
        override val data = byteArrayOf(
            (streams.toInt() shr 8).toByte(),
            streams.toByte()
        )
    }
    
    data class NegotiatedMaxOutboundStreams(val streams: UShort) : SctpParameter() {
        override val type = ParameterType.NEGOTIATED_MAX_OUTBOUND_STREAMS
        override val data = byteArrayOf(
            (streams.toInt() shr 8).toByte(),
            streams.toByte()
        )
    }
    
    data class Unknown(override val type: ParameterType, override val data: ByteArray) : SctpParameter()
}

/**
 * SCTP Authentication Chunk (RFC 4895)
 * 
 * Provides integrity and authentication for SCTP packets using
 * HMAC-SHA1 or other algorithms.
 */
class NgChunk_Auth(
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** Authentication Key Identifier (shared secret ID) */
    val keyId: UShort = 0u,
    /** HMAC Algorithm Identifier */
    val algorithm: AuthAlgorithm = AuthAlgorithm.HMAC_SHA_1,
    /** HMAC value (variable length, typically 20 bytes for SHA1) */
    val hmac: ByteArray = ByteArray(20)
) : NgChunk {
    
    override val type: ChunkType = ChunkType.AUTH
    
    /** Supported HMAC algorithms */
    enum class AuthAlgorithm(val value: UShort) {
        HMAC_SHA_1(1u),
        HMAC_SHA_256(3u);
        
        companion object {
            fun fromUShort(v: UShort): AuthAlgorithm = 
                entries.find { it.value == v } ?: HMAC_SHA_1
        }
    }
    
    override fun serialize(): ByteArray {
        // 4 byte header + 2 byte key ID + 2 byte algorithm + HMAC
        val length = 4 + 2 + 2 + hmac.size
        val paddedLength = (length + 3) and 0xFFFFFFFC.toInt() // 4-byte aligned
        
        val buffer = ByteBuffer.allocate(paddedLength)
        buffer.put(type.value.toByte())
        buffer.put(flags.value.toByte())
        buffer.putShort(paddedLength.toShort())
        buffer.putShort(keyId.toShort())
        buffer.putShort(algorithm.value.toShort())
        buffer.put(hmac)
        
        // Padding
        while (buffer.position() < paddedLength) {
            buffer.put(0)
        }
        
        return buffer.array()
    }
    
    companion object {
        /** Parse AUTH chunk from buffer */
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_Auth {
            val keyId = buffer.getShort().toUShort()
            val algorithm = AuthAlgorithm.fromUShort(buffer.getShort().toUShort())
            
            val hmacLength = length.toInt() - 8 // minus keyId (2) + algorithm (2) + header (4)
            val hmac = ByteArray(hmacLength.coerceAtLeast(0))
            buffer.get(hmac)
            
            // Skip padding
            val paddedLength = (length.toInt() + 3) and 0xFFFFFFFC.toInt()
            val skip = paddedLength - length.toInt()
            repeat(skip) { buffer.get() }
            
            return NgChunk_Auth(
                keyId = keyId,
                algorithm = algorithm,
                hmac = hmac
            )
        }
    }
}

/**
 * Authentication Parameters for SCTP (RFC 4895)
 */
sealed class AuthParameter {
    abstract val type: AuthParameterType
    abstract val data: ByteArray
    
    /** Random Number - used as part of key generation */
    data class Random(val randomData: ByteArray) : AuthParameter() {
        override val type = AuthParameterType.RANDOM
        override val data = randomData
    }
    
    /** Chunk List - specifies which chunks require authentication */
    data class ChunkList(val chunkTypes: List<UByte>) : AuthParameter() {
        override val type = AuthParameterType.CHUNK_LIST
        override val data = chunkTypes.map { it.toByte() }.toByteArray()
    }
    
    /** Shared Key Identifier */
    data class SharedKey(val keyId: UShort, val sharedKey: ByteArray) : AuthParameter() {
        override val type = AuthParameterType.SHARED_KEY
        override val data = byteArrayOf(
            (keyId.toInt() shr 8).toByte(),
            keyId.toByte()
        ) + sharedKey
    }
}

/** Authentication Parameter Types */
enum class AuthParameterType(val value: UShort) {
    RANDOM(0x0001),
    CHUNK_LIST(0x0002),
    SHARED_KEY(0x0003),
    HMAC_ALGORITHM(0x0004);
    
    companion object {
        fun fromUShort(v: UShort): AuthParameterType? = entries.find { it.value == v }
    }
}

/**
 * ECNE chunk - Explicit Congestion Notification Echo (RFC 4960 Section 12.3)
 * 
 * Sent by an SCTP endpoint to its peer to indicate that it experienced
 * congestion (ECN-capable router marked the packet with ECN-CE).
 */
data class NgChunk_Ecne(
    override val type: ChunkType = ChunkType.ECNE,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** Lowest TSN that was marked with ECN-CE */
    val lowestTSN: UInt = 0u
) : NgChunk {
    override fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(8) // Length
        buffer.putInt(lowestTSN.toInt())
        return buffer.array()
    }
    
    companion object {
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_Ecne {
            val lowestTSN = buffer.getInt().toUInt()
            return NgChunk_Ecne(lowestTSN = lowestTSN)
        }
    }
}

/**
 * CWR chunk - Congestion Window Reduced (RFC 4960 Section 12.4)
 * 
 * Sent by an SCTP endpoint to acknowledge receipt of an ECNE chunk
 * and indicate that it has reduced its congestion window.
 */
data class NgChunk_Cwr(
    override val type: ChunkType = ChunkType.CWR,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** TSN that was reported in the ECNE that caused this CWR */
    val lowestTSN: UInt = 0u
) : NgChunk {
    override fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(8) // Length
        buffer.putInt(lowestTSN.toInt())
        return buffer.array()
    }
    
    companion object {
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_Cwr {
            val lowestTSN = buffer.getInt().toUInt()
            return NgChunk_Cwr(lowestTSN = lowestTSN)
        }
    }
}

/**
 * RE-CONFIG chunk - Stream Reconfiguration (RFC 6525)
 * 
 * Allows SCTP endpoints to dynamically add or reset streams
 * without closing the association.
 */
data data class NgChunk_ReConfig(
    override val type: ChunkType = ChunkType.RE_CONFIG,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** Reconfiguration requests */
    val requests: List<ReConfigRequest> = emptyList(),
    /** Reconfiguration responses (for responding to peer's requests) */
    val responses: List<ReConfigResponse> = emptyList()
) : NgChunk {
    
    /** Reconfiguration request types */
    enum class ReConfigType(val value: UShort) {
        ADD_OUTBOUND(0x01),
        ADD_INBOUND(0x02),
        STREAM_RESET(0x03),
        RESET_OUTGOING(0x04),
        RESET_INCOMING(0x05)
    }
    
    /** Reconfiguration result codes */
    enum class ReConfigResult(val value: UShort) {
        SUCCESS(0x00),
        IN_PROGRESS(0x01),
        DENIED(0x02),
        ERROR_NO_EXIST(0x03),
        ERROR_BAD_SEQ(0x04),
        ERROR_IN_PROGRESS(0x05),
        ERROR_DENIED(0x06)
    }
    
    /** Reconfiguration request parameter */
    sealed class ReConfigRequest {
        abstract val requestType: ReConfigType
        abstract val streamIds: List<UShort>
        abstract val requestSequenceNumber: UInt
        
        data class AddOutbound(
            override val streamIds: List<UShort> = emptyList(),
            override val requestSequenceNumber: UInt = 0u
        ) : ReConfigRequest() {
            override val requestType: ReConfigType = ReConfigType.ADD_OUTBOUND
        }
        
        data class AddInbound(
            override val streamIds: List<UShort> = emptyList(),
            override val requestSequenceNumber: UInt = 0u
        ) : ReConfigRequest() {
            override val requestType: ReConfigType = ReConfigType.ADD_INBOUND
        }
        
        data class StreamReset(
            override val streamIds: List<UShort> = emptyList(),
            override val requestSequenceNumber: UInt = 0u
        ) : ReConfigRequest() {
            override val requestType: ReConfigType = ReConfigType.STREAM_RESET
        }
        
        data class ResetOutgoing(
            override val streamIds: List<UShort> = emptyList(),
            override val requestSequenceNumber: UInt = 0u
        ) : ReConfigRequest() {
            override val requestType: ReConfigType = ReConfigType.RESET_OUTGOING
        }
        
        data class ResetIncoming(
            override val streamIds: List<UShort> = emptyList(),
            override val requestSequenceNumber: UInt = 0u
        ) : ReConfigRequest() {
            override val requestType: ReConfigType = ReConfigType.RESET_INCOMING
        }
    }
    
    /** Reconfiguration response parameter */
    data class ReConfigResponse(
        val requestType: ReConfigType,
        val streamId: UShort = 0u,
        val result: ReConfigResult = ReConfigResult.SUCCESS,
        val responseSequenceNumber: UInt = 0u
    )
    
    override fun serialize(): ByteArray {
        // 4 bytes header + 8 bytes per request/response
        val items = requests.ifEmpty { responses }
        val length = 4 + (items.size * 8)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        
        // Serialize requests
        for (req in requests) {
            buffer.putShort(req.requestType.value)
            buffer.putShort((8 + req.streamIds.size * 2).toShort()) // param length
            buffer.putInt(req.requestSequenceNumber.toInt())
            for (streamId in req.streamIds) {
                buffer.putShort(streamId.toShort())
            }
        }
        
        // Serialize responses
        for (resp in responses) {
            buffer.putShort(resp.requestType.value)
            buffer.putShort(8.toShort()) // param length
            buffer.putInt(resp.responseSequenceNumber.toInt())
            buffer.putShort(resp.result.value)
            buffer.putShort(0) // padding
        }
        
        return buffer.array()
    }
    
    companion object {
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_ReConfig {
            val requests = mutableListOf<ReConfigRequest>()
            val responses = mutableListOf<ReConfigResponse>()
            var remaining = length.toInt() - 4
            
            while (remaining >= 8) {
                val reqType = buffer.getShort().toUShort()
                val reqLen = buffer.getShort().toUShort()
                val seqNum = buffer.getInt().toUInt()
                
                val configType = ReConfigType.entries.find { it.value == reqType } 
                    ?: ReConfigType.STREAM_RESET
                
                // Check if this is a response (result field present) or request
                val isResponse = reqLen.toInt() >= 10
                
                if (isResponse) {
                    val result = ReConfigResult.entries.find { it.value == buffer.getShort().toUShort() }
                        ?: ReConfigResult.SUCCESS
                    buffer.getShort() // skip padding
                    responses.add(ReConfigResponse(configType, 0u, result, seqNum))
                } else {
                    val numStreams = (reqLen.toInt() - 8) / 2
                    val streamIds = mutableListOf<UShort>()
                    repeat(numStreams) {
                        streamIds.add(buffer.getShort().toUShort())
                    }
                    
                    val request = when (configType) {
                        ReConfigType.ADD_OUTBOUND -> ReConfigRequest.AddOutbound(streamIds, seqNum)
                        ReConfigType.ADD_INBOUND -> ReConfigRequest.AddInbound(streamIds, seqNum)
                        ReConfigType.STREAM_RESET -> ReConfigRequest.StreamReset(streamIds, seqNum)
                        ReConfigType.RESET_OUTGOING -> ReConfigRequest.ResetOutgoing(streamIds, seqNum)
                        ReConfigType.RESET_INCOMING -> ReConfigRequest.ResetIncoming(streamIds, seqNum)
                    }
                    requests.add(request)
                }
                remaining -= reqLen.toInt()
            }
            
            return NgChunk_ReConfig(requests = requests, responses = responses)
        }
    }
}

// ============================================
// ASCONF Chunk (RFC 5061) - Address Configuration
// ============================================

/**
 * ASCONF Chunk - Address Configuration Change
 * RFC 5061 Section 3.1
 * 
 * Allows endpoints to add, remove, or change addresses
 * used for the association.
 */
data class NgChunk_Asconf(
    override val type: ChunkType = ChunkType.ASCONF,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** Serial number for this ASCONF */
    val serial: UInt = 0u,
    /** Address configuration parameters */
    val parameters: List<AsconfParameter> = emptyList()
) : NgChunk {
    
    /** ASCONF Parameter Types */
    enum class AsconfParamType(val value: UShort) {
        ADD_IP(0x01),
        DEL_IP(0x02),
        SET_PRIMARY(0x03),
        SUCCESS_INDICATION(0x04),
        ERROR_INDICATION(0x05)
    }
    
    /** ASCONF Parameter */
    sealed class AsconfParameter {
        abstract val paramType: AsconfParamType
        abstract val address: String?
        
        data class AddIP(override val address: String) : AsconfParameter() {
            override val paramType: AsconfParamType = AsconfParamType.ADD_IP
        }
        
        data class DelIP(override val address: String) : AsconfParameter() {
            override val paramType: AsconfParamType = AsconfParamType.DEL_IP
        }
        
        data class SetPrimary(override val address: String) : AsconfParameter() {
            override val paramType: AsconfParamType = AsconfParamType.SET_PRIMARY
        }
    }
    
    override fun serialize(): ByteArray {
        // Calculate length: 4 (header) + 4 (serial) + parameters
        var paramLength = 0
        for (param in parameters) {
            paramLength += 8 + (param.address?.length ?: 0)
        }
        val length = 8 + paramLength
        
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(serial.toInt())
        
        for (param in parameters) {
            buffer.putShort(param.paramType.value)
            val addrLen = param.address?.length ?: 0
            buffer.putShort((8 + addrLen).toShort())
            // Address data would go here (IPv4/IPv6)
            param.address?.let { buffer.put(it.toByteArray()) }
        }
        
        return buffer.array()
    }
    
    companion object {
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_Asconf {
            val serial = buffer.getInt().toUInt()
            val params = mutableListOf<AsconfParameter>()
            var remaining = length.toInt() - 8
            
            while (remaining >= 8) {
                val paramType = buffer.getShort().toUShort()
                val paramLen = buffer.getShort().toUShort()
                
                val asconfType = AsconfParamType.entries.find { it.value == paramType }
                    ?: AsconfParamType.ADD_IP
                
                // Parse address from parameter
                val addrLen = paramLen.toInt() - 4
                if (addrLen > 0) {
                    val addrBytes = ByteArray(addrLen)
                    buffer.get(addrBytes)
                    val address = String(addrBytes)
                    
                    val param = when (asconfType) {
                        AsconfParamType.ADD_IP -> AsconfParameter.AddIP(address)
                        AsconfParamType.DEL_IP -> AsconfParameter.DelIP(address)
                        AsconfParamType.SET_PRIMARY -> AsconfParameter.SetPrimary(address)
                        else -> AsconfParameter.AddIP(address)
                    }
                    params.add(param)
                }
                remaining -= paramLen.toInt()
            }
            
            return NgChunk_Asconf(serial = serial, parameters = params)
        }
    }
}

/**
 * ASCONF-ACK Chunk - Address Configuration Acknowledgment
 * RFC 5061 Section 3.2
 */
data class NgChunk_AsconfAck(
    override val type: ChunkType = ChunkType.ASCONF_ACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** Serial number being acknowledged */
    val serial: UInt = 0u,
    /** Success/error indication parameters */
    val parameters: List<AsconfResponseParameter> = emptyList()
) : NgChunk {
    
    /** Response result codes */
    enum class AsconfResult(val value: UShort) {
        SUCCESS(0x00),
        DENIED(0x01),
        ERROR_BAD_SEQ(0x02),
        ERROR_NO_EXIST(0x03)
    }
    
    /** ASCONF Response Parameter */
    data class AsconfResponseParameter(
        val result: AsconfResult,
        val errorCode: UShort = 0u
    )
    
    override fun serialize(): ByteArray {
        val length = 8 + (parameters.size * 8)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(serial.toInt())
        
        for (param in parameters) {
            buffer.putShort(param.result.value)
            buffer.putShort(8) // param length
            buffer.putShort(param.errorCode.toShort())
            buffer.putShort(0) // padding
        }
        
        return buffer.array()
    }
    
    companion object {
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_AsconfAck {
            val serial = buffer.getInt().toUInt()
            val params = mutableListOf<AsconfResponseParameter>()
            var remaining = length.toInt() - 8
            
            while (remaining >= 8) {
                val resultVal = buffer.getShort().toUShort()
                buffer.getShort() // skip length
                val errorCode = buffer.getShort().toUShort()
                buffer.getShort() // skip padding
                
                val result = AsconfResult.entries.find { it.value == resultVal }
                    ?: AsconfResult.SUCCESS
                params.add(AsconfResponseParameter(result, errorCode))
                remaining -= 8
            }
            
            return NgChunk_AsconfAck(serial = serial, parameters = params)
        }
    }
}

// ============================================
// I_DATA Chunk (RFC 4960) - Interleaved Data
// ============================================

/**
 * I_DATA Chunk - Interleaved Data Chunk
 * RFC 4960 Section 3.3.10
 * 
 * Provides interleaving support for simultaneous ordered
 * and unordered data delivery on the same stream.
 */
data class NgChunk_IData(
    override val type: ChunkType = ChunkType.I_DATA,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    /** Transmission Sequence Number */
    val tsn: UInt = 0u,
    /** Stream ID */
    val streamId: UShort = 0u,
    /** Stream Sequence Number */
    val streamSeqNum: UShort = 0u,
    /** Payload Protocol Identifier */
    val ppId: UInt = 0u,
    /** User data */
    val userData: ByteArray = ByteArray(0)
) : NgChunk {
    
    override fun serialize(): ByteArray {
        // 20 bytes header + user data
        val length = 20 + userData.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(tsn.toInt())
        buffer.putShort(streamId)
        buffer.putShort(streamSeqNum)
        buffer.putInt(ppId.toInt())
        buffer.put(userData)
        return buffer.array()
    }
    
    companion object {
        fun parse(buffer: ByteBuffer, length: UShort): NgChunk_IData {
            val tsn = buffer.getInt().toUInt()
            val streamId = buffer.getShort().toUShort()
            val streamSeqNum = buffer.getShort().toUShort()
            val ppId = buffer.getInt().toUInt()
            
            val dataLen = length.toInt() - 20
            val userData = if (dataLen > 0) {
                val data = ByteArray(dataLen)
                buffer.get(data)
                data
            } else {
                ByteArray(0)
            }
            
            return NgChunk_IData(
                tsn = tsn,
                streamId = streamId,
                streamSeqNum = streamSeqNum,
                ppId = ppId,
                userData = userData
            )
        }
    }
}
