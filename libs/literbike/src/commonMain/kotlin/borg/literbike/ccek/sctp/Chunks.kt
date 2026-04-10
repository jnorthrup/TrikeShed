package borg.literbike.ccek.sctp

/**
 * SCTP TLV Chunk Definitions
 *
 * Matches KMPngSCTP protocol spec
 * All chunks use Type-Length-Value format for forward compatibility.
 * Unknown chunks are automatically skipped - no parsing errors!
 */

/**
 * SCTP Chunk Types (RFC 4960 + ngSCTP extensions)
 */
enum class ChunkTypeExtended(val value: UByte) {
    // RFC 4960 Core Types
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
    ShutdownComplete(0x0Eu),
    Auth(0x0Fu),

    // ngSCTP Extensions
    AsconfAck(0x80u),
    Asconf(0x81u),
    ReConfig(0x82u),
    ForwardTsn(0xC0u),
    IData(0xD0u);

    companion object {
        /**
         * Parse chunk type from byte, returns None for unknown types
         */
        fun fromByte(b: UByte): ChunkTypeExtended? = entries.find { it.value == b }

        /**
         * Check if this chunk type is known
         */
        fun isKnown(b: UByte): Boolean = fromByte(b) != null
    }
}

/**
 * Chunk flags (bitwise)
 */
@JvmInline
value class ChunkFlags(val value: UByte = 0u) {
    companion object {
        val EMPTY = ChunkFlags(0u)
    }

    /**
     * End fragment flag (E)
     */
    fun isEnd(): Boolean = (value and 0x01u) != 0u

    /**
     * Beginning fragment flag (B)
     */
    fun isBeginning(): Boolean = (value and 0x02u) != 0u

    /**
     * Unordered delivery flag (U)
     */
    fun isUnordered(): Boolean = (value and 0x04u) != 0u

    /**
     * Set end flag
     */
    fun withEnd(): ChunkFlags = ChunkFlags(value or 0x01u)

    /**
     * Set beginning flag
     */
    fun withBeginning(): ChunkFlags = ChunkFlags(value or 0x02u)

    /**
     * Set unordered flag
     */
    fun withUnordered(): ChunkFlags = ChunkFlags(value or 0x04u)
}

/**
 * TLV chunk header (4 bytes)
 */
data class ChunkHeaderTlv(
    val chunkType: ChunkTypeExtended,
    val flags: ChunkFlags,
    val length: UShort
) {
    companion object {
        const val SIZE: Int = 4

        /**
         * Parse header from bytes
         */
        fun parse(data: ByteArray): ChunkHeaderTlv? {
            if (data.size < SIZE) return null

            val chunkType = ChunkTypeExtended.fromByte(data[0].toUByte()) ?: return null
            val flags = ChunkFlags(data[1].toUByte())
            val length = ((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)

            return ChunkHeaderTlv(chunkType, flags, length)
        }
    }

    /**
     * Serialize header to bytes
     */
    fun serialize(): ByteArray {
        return byteArrayOf(
            chunkType.value.toByte(),
            flags.value.toByte(),
            ((length.toInt() ushr 8) and 0xFF).toByte(),
            (length.toInt() and 0xFF).toByte()
        )
    }
}

/**
 * Chunk parsing error
 */
sealed class ChunkError(message: String) : Exception(message) {
    class BufferTooShort(val needed: Int, val have: Int) :
        ChunkError("Buffer too short: need $needed bytes, have $have")
    class InvalidChunkType(val type: UByte) : ChunkError("Invalid chunk type: $type")
    class InvalidLength(val length: UShort) : ChunkError("Invalid chunk length: $length")
    class ChecksumMismatch(val expected: UInt, val actual: UInt) :
        ChunkError("Checksum mismatch: expected $expected, got $actual")
}

/**
 * DATA chunk (Type 0x00)
 */
data class DataChunkExtended(
    val flags: ChunkFlags,
    val streamId: UShort,
    val streamSeq: UShort,
    val ppid: UInt,
    val tsn: UInt,
    val payload: ByteArray
) {
    companion object {
        /**
         * Minimum size: header (4) + stream_id (2) + stream_seq (2) + ppid (4) + tsn (4) = 16
         */
        const val MIN_SIZE: Int = 16

        fun parse(data: ByteArray): Result<DataChunkExtended> {
            if (data.size < MIN_SIZE) {
                return Result.failure(ChunkError.BufferTooShort(MIN_SIZE, data.size))
            }

            val flags = ChunkFlags(data[1].toUByte())
            val length = (((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)).toInt()

            if (data.size < length) {
                return Result.failure(ChunkError.InvalidLength(length.toUShort()))
            }

            val streamId = ((data[4].toUShort() and 0xFFu) shl 8) or (data[5].toUShort() and 0xFFu)
            val streamSeq = ((data[6].toUShort() and 0xFFu) shl 8) or (data[7].toUShort() and 0xFFu)
            val ppid = ((data[8].toUInt() and 0xFFu) shl 24) or
                    ((data[9].toUInt() and 0xFFu) shl 16) or
                    ((data[10].toUInt() and 0xFFu) shl 8) or
                    (data[11].toUInt() and 0xFFu)
            val tsn = ((data[12].toUInt() and 0xFFu) shl 24) or
                    ((data[13].toUInt() and 0xFFu) shl 16) or
                    ((data[14].toUInt() and 0xFFu) shl 8) or
                    (data[15].toUInt() and 0xFFu)

            val payload = data.sliceArray(16 until length)

            return Result.success(
                DataChunkExtended(flags, streamId, streamSeq, ppid, tsn, payload)
            )
        }
    }

    fun serialize(): ByteArray {
        val length = MIN_SIZE + payload.size
        val bytes = mutableListOf<Byte>()

        bytes.add(ChunkTypeExtended.Data.value.toByte())
        bytes.add(flags.value.toByte())
        bytes.add(((length ushr 8) and 0xFF).toByte())
        bytes.add((length and 0xFF).toByte())
        bytes.add(((streamId.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((streamId.toInt() and 0xFF).toByte())
        bytes.add(((streamSeq.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((streamSeq.toInt() and 0xFF).toByte())
        bytes.add(((ppid.toInt() ushr 24) and 0xFF).toByte())
        bytes.add(((ppid.toInt() ushr 16) and 0xFF).toByte())
        bytes.add(((ppid.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((ppid.toInt() and 0xFF).toByte())
        bytes.add(((tsn.toInt() ushr 24) and 0xFF).toByte())
        bytes.add(((tsn.toInt() ushr 16) and 0xFF).toByte())
        bytes.add(((tsn.toInt() ushr 8) and 0xFF).toByte())
        bytes.add((tsn.toInt() and 0xFF).toByte())
        bytes.addAll(payload.toList())

        return bytes.toByteArray()
    }
}

/**
 * INIT chunk (Type 0x01)
 */
data class InitChunkExtended(
    val flags: ChunkFlags,
    val initiateTag: UInt,
    val aRwnd: UInt,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val initialTsn: UInt,
    val params: ByteArray = byteArrayOf()
) {
    companion object {
        const val MIN_SIZE: Int = 20

        fun parse(data: ByteArray): Result<InitChunkExtended> {
            if (data.size < MIN_SIZE) {
                return Result.failure(ChunkError.BufferTooShort(MIN_SIZE, data.size))
            }

            val flags = ChunkFlags(data[1].toUByte())
            val length = (((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)).toInt()

            val initiateTag = ((data[4].toUInt() and 0xFFu) shl 24) or
                    ((data[5].toUInt() and 0xFFu) shl 16) or
                    ((data[6].toUInt() and 0xFFu) shl 8) or
                    (data[7].toUInt() and 0xFFu)
            val aRwnd = ((data[8].toUInt() and 0xFFu) shl 24) or
                    ((data[9].toUInt() and 0xFFu) shl 16) or
                    ((data[10].toUInt() and 0xFFu) shl 8) or
                    (data[11].toUInt() and 0xFFu)
            val numOutboundStreams = ((data[12].toUShort() and 0xFFu) shl 8) or (data[13].toUShort() and 0xFFu)
            val numInboundStreams = ((data[14].toUShort() and 0xFFu) shl 8) or (data[15].toUShort() and 0xFFu)
            val initialTsn = ((data[16].toUInt() and 0xFFu) shl 24) or
                    ((data[17].toUInt() and 0xFFu) shl 16) or
                    ((data[18].toUInt() and 0xFFu) shl 8) or
                    (data[19].toUInt() and 0xFFu)

            val params = if (length > MIN_SIZE) {
                data.sliceArray(MIN_SIZE until length)
            } else {
                byteArrayOf()
            }

            return Result.success(
                InitChunkExtended(flags, initiateTag, aRwnd, numOutboundStreams, numInboundStreams, initialTsn, params)
            )
        }
    }
}

/**
 * SACK chunk (Type 0x03)
 */
data class SackChunkExtended(
    val flags: ChunkFlags,
    val cumulativeTsnAck: UInt,
    val aRwnd: UInt,
    val gapAckBlocks: List<Pair<UShort, UShort>> = emptyList(),
    val dupTsns: List<UInt> = emptyList()
) {
    companion object {
        const val MIN_SIZE: Int = 16

        fun parse(data: ByteArray): Result<SackChunkExtended> {
            if (data.size < MIN_SIZE) {
                return Result.failure(ChunkError.BufferTooShort(MIN_SIZE, data.size))
            }

            val flags = ChunkFlags(data[1].toUByte())
            val cumulativeTsnAck = ((data[4].toUInt() and 0xFFu) shl 24) or
                    ((data[5].toUInt() and 0xFFu) shl 16) or
                    ((data[6].toUInt() and 0xFFu) shl 8) or
                    (data[7].toUInt() and 0xFFu)
            val aRwnd = ((data[8].toUInt() and 0xFFu) shl 24) or
                    ((data[9].toUInt() and 0xFFu) shl 16) or
                    ((data[10].toUInt() and 0xFFu) shl 8) or
                    (data[11].toUInt() and 0xFFu)
            val numGapAckBlocks = (((data[12].toUShort() and 0xFFu) shl 8) or (data[13].toUShort() and 0xFFu)).toInt()
            val numDupTsns = (((data[14].toUShort() and 0xFFu) shl 8) or (data[15].toUShort() and 0xFFu)).toInt()

            // Parse gap ack blocks
            val gapAckBlocks = mutableListOf<Pair<UShort, UShort>>()
            var offset = MIN_SIZE
            repeat(numGapAckBlocks) { i ->
                val start = offset + i * 4
                if (start + 4 > data.size) return@repeat
                val startAck = ((data[start].toUShort() and 0xFFu) shl 8) or (data[start + 1].toUShort() and 0xFFu)
                val endAck = ((data[start + 2].toUShort() and 0xFFu) shl 8) or (data[start + 3].toUShort() and 0xFFu)
                gapAckBlocks.add(startAck to endAck)
            }

            // Parse duplicate TSNs
            val dupOffset = offset + numGapAckBlocks * 4
            val dupTsns = mutableListOf<UInt>()
            repeat(numDupTsns) { i ->
                val start = dupOffset + i * 4
                if (start + 4 > data.size) return@repeat
                val tsn = ((data[start].toUInt() and 0xFFu) shl 24) or
                        ((data[start + 1].toUInt() and 0xFFu) shl 16) or
                        ((data[start + 2].toUInt() and 0xFFu) shl 8) or
                        (data[start + 3].toUInt() and 0xFFu)
                dupTsns.add(tsn)
            }

            return Result.success(
                SackChunkExtended(flags, cumulativeTsnAck, aRwnd, gapAckBlocks, dupTsns)
            )
        }
    }
}

/**
 * HEARTBEAT chunk (Type 0x04)
 */
data class HeartbeatChunkExtended(
    val flags: ChunkFlags,
    val heartbeatInfo: ByteArray = byteArrayOf()
) {
    companion object {
        const val MIN_SIZE: Int = 4

        fun parse(data: ByteArray): Result<HeartbeatChunkExtended> {
            if (data.size < MIN_SIZE) {
                return Result.failure(ChunkError.BufferTooShort(MIN_SIZE, data.size))
            }

            val flags = ChunkFlags(data[1].toUByte())
            val length = (((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)).toInt()

            val heartbeatInfo = if (length > MIN_SIZE) {
                data.sliceArray(MIN_SIZE until length)
            } else {
                byteArrayOf()
            }

            return Result.success(HeartbeatChunkExtended(flags, heartbeatInfo))
        }
    }
}

/**
 * COOKIE ECHO chunk (Type 0x0A)
 */
data class CookieEchoChunkExtended(
    val flags: ChunkFlags,
    val cookie: ByteArray = byteArrayOf()
) {
    companion object {
        const val MIN_SIZE: Int = 4

        fun parse(data: ByteArray): Result<CookieEchoChunkExtended> {
            if (data.size < MIN_SIZE) {
                return Result.failure(ChunkError.BufferTooShort(MIN_SIZE, data.size))
            }

            val flags = ChunkFlags(data[1].toUByte())
            val length = (((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)).toInt()
            val cookie = data.sliceArray(MIN_SIZE until length)

            return Result.success(CookieEchoChunkExtended(flags, cookie))
        }
    }
}

/**
 * ABORT chunk (Type 0x06)
 */
data class AbortChunkExtended(
    val flags: ChunkFlags,
    val errorCauses: ByteArray = byteArrayOf()
) {
    companion object {
        const val MIN_SIZE: Int = 4

        fun parse(data: ByteArray): Result<AbortChunkExtended> {
            if (data.size < MIN_SIZE) {
                return Result.failure(ChunkError.BufferTooShort(MIN_SIZE, data.size))
            }

            val flags = ChunkFlags(data[1].toUByte())
            val length = (((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)).toInt()
            val errorCauses = data.sliceArray(MIN_SIZE until length)

            return Result.success(AbortChunkExtended(flags, errorCauses))
        }
    }
}

/**
 * Generic chunk for unknown types
 */
data class UnknownChunkExtended(
    val chunkType: UByte,
    val flags: ChunkFlags,
    val data: ByteArray = byteArrayOf()
) {
    companion object {
        fun parse(data: ByteArray): Result<UnknownChunkExtended> {
            if (data.size < ChunkHeaderTlv.SIZE) {
                return Result.failure(ChunkError.BufferTooShort(ChunkHeaderTlv.SIZE, data.size))
            }

            val chunkType = data[0].toUByte()
            val flags = ChunkFlags(data[1].toUByte())
            val length = (((data[2].toUShort() and 0xFFu) shl 8) or (data[3].toUShort() and 0xFFu)).toInt()

            if (data.size < length) {
                return Result.failure(ChunkError.InvalidLength(length.toUShort()))
            }

            // Skip unknown chunks per KMPngSCTP spec
            return Result.success(
                UnknownChunkExtended(chunkType, flags, data.sliceArray(ChunkHeaderTlv.SIZE until length))
            )
        }
    }
}

/**
 * Parsed chunk enum
 */
sealed class ChunkExtended {
    data class Data(val chunk: DataChunkExtended) : ChunkExtended()
    data class Init(val chunk: InitChunkExtended) : ChunkExtended()
    data class InitAck(val chunk: InitChunkExtended) : ChunkExtended()
    data class Sack(val chunk: SackChunkExtended) : ChunkExtended()
    data class Heartbeat(val chunk: HeartbeatChunkExtended) : ChunkExtended()
    data class HeartbeatAck(val chunk: HeartbeatChunkExtended) : ChunkExtended()
    data class Abort(val chunk: AbortChunkExtended) : ChunkExtended()
    data class CookieEcho(val chunk: CookieEchoChunkExtended) : ChunkExtended()
    data class CookieAck(val chunk: CookieEchoChunkExtended) : ChunkExtended()
    data class Unknown(val chunk: UnknownChunkExtended) : ChunkExtended()

    companion object {
        /**
         * Parse a chunk from raw bytes
         * Returns None for unknown chunk types (per KMPngSCTP spec, skip unknowns)
         */
        fun parse(data: ByteArray): Result<ChunkExtended?> {
            if (data.size < ChunkHeaderTlv.SIZE) {
                return Result.failure(ChunkError.BufferTooShort(ChunkHeaderTlv.SIZE, data.size))
            }

            val chunkTypeByte = data[0].toUByte()

            return when (val chunkType = ChunkTypeExtended.fromByte(chunkTypeByte)) {
                ChunkTypeExtended.Data -> DataChunkExtended.parse(data).map { Data(it) }
                ChunkTypeExtended.Init -> InitChunkExtended.parse(data).map { Init(it) }
                ChunkTypeExtended.InitAck -> InitChunkExtended.parse(data).map { InitAck(it) }
                ChunkTypeExtended.Sack -> SackChunkExtended.parse(data).map { Sack(it) }
                ChunkTypeExtended.Heartbeat -> HeartbeatChunkExtended.parse(data).map { Heartbeat(it) }
                ChunkTypeExtended.HeartbeatAck -> HeartbeatChunkExtended.parse(data).map { HeartbeatAck(it) }
                ChunkTypeExtended.Abort -> AbortChunkExtended.parse(data).map { Abort(it) }
                ChunkTypeExtended.CookieEcho -> CookieEchoChunkExtended.parse(data).map { CookieEcho(it) }
                ChunkTypeExtended.CookieAck -> CookieEchoChunkExtended.parse(data).map { CookieAck(it) }
                else -> {
                    // Unknown chunk type - skip per KMPngSCTP spec
                    UnknownChunkExtended.parse(data).map { Unknown(it) }
                }
            }
        }

        /**
         * Get the chunk type
         */
        fun ChunkExtended.chunkType(): ChunkTypeExtended? = when (this) {
            is Data -> ChunkTypeExtended.Data
            is Init -> ChunkTypeExtended.Init
            is InitAck -> ChunkTypeExtended.InitAck
            is Sack -> ChunkTypeExtended.Sack
            is Heartbeat -> ChunkTypeExtended.Heartbeat
            is HeartbeatAck -> ChunkTypeExtended.HeartbeatAck
            is Abort -> ChunkTypeExtended.Abort
            is CookieEcho -> ChunkTypeExtended.CookieEcho
            is CookieAck -> ChunkTypeExtended.CookieAck
            is Unknown -> null
        }
    }
}

/**
 * Chunk parser for reading multiple chunks from a packet
 */
object ChunkParser {
    /**
     * Parse all chunks from a packet buffer
     * Unknown chunks are skipped per KMPngSCTP spec
     */
    fun parseAll(data: ByteArray): Result<List<ChunkExtended>> {
        val chunks = mutableListOf<ChunkExtended>()
        var offset = 0

        while (offset < data.size) {
            if (offset + ChunkHeaderTlv.SIZE > data.size) {
                break
            }

            // Read length from header
            val length = (((data[offset + 2].toUShort() and 0xFFu) shl 8) or
                    (data[offset + 3].toUShort() and 0xFFu)).toInt()
            if (length == 0) {
                break // Invalid length
            }

            // Pad to 4-byte boundary
            val paddedLength = (length + 3) and 0xFFFFFFFC.inv()

            if (offset + paddedLength > data.size) {
                return Result.failure(
                    ChunkError.BufferTooShort(offset + paddedLength, data.size)
                )
            }

            val chunkData = data.sliceArray(offset until (offset + length))
            ChunkExtended.parse(chunkData).getOrNull()?.let { chunk ->
                chunks.add(chunk)
            }

            offset += paddedLength
        }

        return Result.success(chunks)
    }
}
