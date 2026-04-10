package borg.literbike.ccek.sctp

/**
 * SCTP Chunk Types - TLV format per ngSCTP protocol specification
 *
 * All chunks use Type-Length-Value format for forward compatibility.
 * Unknown chunks are automatically skipped.
 */

/**
 * SCTP chunk type identifiers
 */
enum class ChunkType(val value: UByte) {
    Data(0x00u),
    Init(0x01u),
    InitAck(0x02u),
    Sack(0x03u),
    Heartbeat(0x04u),
    HeartbeatAck(0x05u),
    Abort(0x06u),
    Shutdown(0x07u),
    ShutdownAck(0x08u),
    Error(0x09u),
    CookieEcho(0x0Au),
    CookieAck(0x0Bu),
    Ecne(0x0Cu),
    Cwr(0x0Du),
    ShutdownComplete(0x0Eu);

    companion object {
        fun fromUByte(value: UByte): ChunkType? = entries.find { it.value == value }
    }
}

/**
 * DATA chunk flags
 */
@JvmInline
value class DataFlags(val value: UByte) {
    companion object {
        const val END: UByte = 0x01u
        const val BEGIN: UByte = 0x02u
        const val UNORDERED: UByte = 0x04u
    }

    fun isEnd(): Boolean = (value and END) != 0u
    fun isBegin(): Boolean = (value and BEGIN) != 0u
    fun isUnordered(): Boolean = (value and UNORDERED) != 0u
}

/**
 * SCTP chunk header (4 bytes)
 */
data class ChunkHeader(
    val chunkType: UByte,
    val flags: UByte,
    val length: UShort
) {
    companion object {
        const val SIZE: Int = 4

        fun fromBytes(bytes: ByteArray): Result<ChunkHeader> {
            if (bytes.size < SIZE) {
                return Result.failure(IllegalArgumentException("chunk header too short"))
            }
            return Result.success(
                ChunkHeader(
                    chunkType = bytes[0].toUByte(),
                    flags = bytes[1].toUByte(),
                    length = ((bytes[2].toUShort() and 0xFFu) shl 8) or (bytes[3].toUShort() and 0xFFu)
                )
            )
        }
    }

    fun toBytes(): ByteArray {
        return byteArrayOf(
            chunkType.toByte(),
            flags.toByte(),
            ((length.toInt() ushr 8) and 0xFF).toByte(),
            (length.toInt() and 0xFF).toByte()
        )
    }

    fun valueLength(): Int = length.toInt() - SIZE
}

/**
 * DATA chunk (0x00)
 */
data class DataChunk(
    val flags: DataFlags,
    val streamId: UShort,
    val streamSeqNum: UShort,
    val payloadProtocolId: UInt,
    val transmissionSeqNum: UInt,
    val userData: ByteArray
) {
    companion object {
        /**
         * Parse DATA chunk from bytes
         */
        fun fromBytes(bytes: ByteArray): Result<DataChunk> {
            val header = ChunkHeader.fromBytes(bytes).getOrNull()
                ?: return Result.failure(IllegalArgumentException("Failed to parse chunk header"))
            val valueLen = header.valueLength()
            val data = bytes.drop(ChunkHeader.SIZE).toByteArray()

            if (data.size < 12 || valueLen < 12) {
                return Result.failure(IllegalArgumentException("DATA chunk too short"))
            }

            val streamId = ((data[0].toUShort() and 0xFFu) shl 8) or (data[1].toUShort() and 0xFFu)
            val streamSeqNum = ((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)
            val payloadProtocolId = ((data[4].toUInt() and 0xFFu) shl 24) or
                    ((data[5].toUInt() and 0xFFu) shl 16) or
                    ((data[6].toUInt() and 0xFFu) shl 8) or
                    (data[7].toUInt() and 0xFFu)
            val transmissionSeqNum = ((data[8].toUInt() and 0xFFu) shl 24) or
                    ((data[9].toUInt() and 0xFFu) shl 16) or
                    ((data[10].toUInt() and 0xFFu) shl 8) or
                    (data[11].toUInt() and 0xFFu)
            // Use header length to exclude padding bytes
            val dataEnd = minOf(valueLen, data.size)
            val userData = data.sliceArray(12 until dataEnd)

            return Result.success(
                DataChunk(
                    flags = DataFlags(header.flags),
                    streamId = streamId,
                    streamSeqNum = streamSeqNum,
                    payloadProtocolId = payloadProtocolId,
                    transmissionSeqNum = transmissionSeqNum,
                    userData = userData
                )
            )
        }
    }

    /**
     * Serialize DATA chunk to bytes
     */
    fun toBytes(): ByteArray {
        // Fixed value fields: stream_id(2) + stream_seq(2) + ppid(4) + tsn(4) = 12
        val valueLen = 12 + userData.size
        val length = (ChunkHeader.SIZE + valueLen).toUShort()

        val bytes = mutableListOf<Byte>()
        bytes.add(ChunkType.Data.value.toByte())
        bytes.add(flags.value.toByte())
        bytes.add(((length.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((length.toInt() and 0xFF).toByte())
        bytes.add(((streamId.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((streamId.toInt() and 0xFF).toByte())
        bytes.add(((streamSeqNum.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((streamSeqNum.toInt() and 0xFF).toByte())
        bytes.add(((payloadProtocolId.toInt() ushr 24) and 0xFF).toByte())
        bytes.add(((payloadProtocolId.toInt() ushr 16) and 0xFF).toByte())
        bytes.add(((payloadProtocolId.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((payloadProtocolId.toInt() and 0xFF).toByte())
        bytes.add(((transmissionSeqNum.toInt() ushr 24) and 0xFF).toByte())
        bytes.add(((transmissionSeqNum.toInt() ushr 16) and 0xFF).toByte())
        bytes.add(((transmissionSeqNum.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((transmissionSeqNum.toInt() and 0xFF).toByte())
        bytes.addAll(userData.toList())

        // Pad to 4-byte boundary
        while (bytes.size % 4 != 0) {
            bytes.add(0)
        }

        return bytes.toByteArray()
    }
}

/**
 * INIT chunk (0x01)
 */
data class InitChunk(
    val initiateTag: UInt,
    val advertisedReceiverWindowCredit: UInt,
    val outboundStreams: UShort,
    val inboundStreams: UShort,
    val initialTsn: UInt,
    val parameters: List<InitParam> = emptyList()
) {
    companion object {
        fun fromBytes(bytes: ByteArray): Result<InitChunk> {
            val header = ChunkHeader.fromBytes(bytes).getOrNull()
                ?: return Result.failure(IllegalArgumentException("Failed to parse chunk header"))
            val data = bytes.drop(ChunkHeader.SIZE)

            if (data.size < 16) {
                return Result.failure(IllegalArgumentException("INIT chunk too short"))
            }

            val initiateTag = ((data[0].toUInt() and 0xFFu) shl 24) or
                    ((data[1].toUInt() and 0xFFu) shl 16) or
                    ((data[2].toUInt() and 0xFFu) shl 8) or
                    (data[3].toUInt() and 0xFFu)
            val arwc = ((data[4].toUInt() and 0xFFu) shl 24) or
                    ((data[5].toUInt() and 0xFFu) shl 16) or
                    ((data[6].toUInt() and 0xFFu) shl 8) or
                    (data[7].toUInt() and 0xFFu)
            val outboundStreams = ((data[8].toUShort() and 0xFFu) shl 8) or (data[9].toUShort() and 0xFFu)
            val inboundStreams = ((data[10].toUShort() and 0xFFu) shl 8) or (data[11].toUShort() and 0xFFu)
            val initialTsn = ((data[12].toUInt() and 0xFFu) shl 24) or
                    ((data[13].toUInt() and 0xFFu) shl 16) or
                    ((data[14].toUInt() and 0xFFu) shl 8) or
                    (data[15].toUInt() and 0xFFu)

            // Parse parameters (skip for now, would need full TLV parsing)
            return Result.success(
                InitChunk(
                    initiateTag = initiateTag,
                    advertisedReceiverWindowCredit = arwc,
                    outboundStreams = outboundStreams,
                    inboundStreams = inboundStreams,
                    initialTsn = initialTsn,
                    parameters = emptyList()
                )
            )
        }
    }
}

/**
 * INIT parameters (TLV)
 */
data class InitParam(
    val paramType: UShort,
    val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InitParam) return false
        return paramType == other.paramType && value.contentEquals(other.value)
    }
    override fun hashCode(): Int {
        var result = paramType.hashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}

/**
 * SACK chunk (0x03) - Selective Acknowledgment
 */
data class SackChunk(
    val cumulativeTsnAck: UInt,
    val aRwnd: UInt,
    val gapAckBlocks: List<GapAckBlock> = emptyList(),
    val duplicateTsn: List<UInt> = emptyList()
)

data class GapAckBlock(
    val start: UShort,
    val end: UShort
)

/**
 * Generic SCTP chunk
 */
sealed class Chunk {
    data class Data(val chunk: DataChunk) : Chunk()
    data class Init(val chunk: InitChunk) : Chunk()
    data class InitAck(val chunk: InitChunk) : Chunk()
    data class Sack(val chunk: SackChunk) : Chunk()
    object Heartbeat : Chunk()
    object HeartbeatAck : Chunk()
    object Abort : Chunk()
    object Shutdown : Chunk()
    object ShutdownAck : Chunk()
    object Error : Chunk()
    data class CookieEcho(val data: ByteArray) : Chunk()
    object CookieAck : Chunk()
    object Ecne : Chunk()
    object Cwr : Chunk()
    object ShutdownComplete : Chunk()
    data class Unknown(val chunkType: UByte, val data: ByteArray) : Chunk()

    companion object {
        fun fromBytes(bytes: ByteArray): Result<Chunk> {
            if (bytes.isEmpty()) {
                return Result.failure(IllegalArgumentException("empty chunk"))
            }

            val chunkType = bytes[0].toUByte()
            return when (val type = ChunkType.fromUByte(chunkType)) {
                ChunkType.Data -> DataChunk.fromBytes(bytes).map { Data(it) }
                ChunkType.Init -> InitChunk.fromBytes(bytes).map { Init(it) }
                ChunkType.InitAck -> InitChunk.fromBytes(bytes).map { InitAck(it) }
                ChunkType.Sack -> Result.success(
                    Sack(SackChunk(0u, 0u, emptyList(), emptyList()))
                )
                ChunkType.Heartbeat -> Result.success(Heartbeat)
                ChunkType.HeartbeatAck -> Result.success(HeartbeatAck)
                ChunkType.Abort -> Result.success(Abort)
                ChunkType.Shutdown -> Result.success(Shutdown)
                ChunkType.ShutdownAck -> Result.success(ShutdownAck)
                ChunkType.Error -> Result.success(Error)
                ChunkType.CookieEcho -> Result.success(
                    CookieEcho(bytes.drop(4).toByteArray())
                )
                ChunkType.CookieAck -> Result.success(CookieAck)
                ChunkType.Ecne -> Result.success(Ecne)
                ChunkType.Cwr -> Result.success(Cwr)
                ChunkType.ShutdownComplete -> Result.success(ShutdownComplete)
                null -> Result.success(Unknown(chunkType, bytes))
            }
        }
    }
}
