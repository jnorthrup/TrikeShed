package borg.literbike.ccek.sctp.chunk_element

/**
 * SCTP Chunk - protocol messages
 *
 * This module CANNOT see association or stream.
 */

/**
 * ChunkKey - SCTP chunk processing
 */
object ChunkKey {
    val FACTORY: () -> ChunkElement = { ChunkElement() }
}

/**
 * ChunkElement - chunk registry
 */
object ChunkElement

/**
 * SCTP chunk header
 */
data class ChunkHeaderElement(
    val chunkType: UByte,
    val flags: UByte,
    val length: UShort
)

/**
 * SCTP DATA chunk
 */
data class DataChunkElement(
    val header: ChunkHeaderElement,
    val tsn: UInt,
    val streamId: UShort,
    val streamSeq: UShort,
    val payloadProto: UInt,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataChunkElement) return false
        return header == other.header && tsn == other.tsn &&
                streamId == other.streamId && streamSeq == other.streamSeq &&
                payloadProto == other.payloadProto && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + tsn.hashCode()
        result = 31 * result + streamId.hashCode()
        result = 31 * result + streamSeq.hashCode()
        result = 31 * result + payloadProto.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * SCTP INIT chunk
 */
data class InitChunkElement(
    val header: ChunkHeaderElement,
    val initiateTag: UInt,
    val advertisedWindow: UInt,
    val numOutStreams: UShort,
    val maxInStreams: UShort,
    val initialTsn: UInt
)

/**
 * SCTP SACK chunk
 */
data class SackChunkElement(
    val header: ChunkHeaderElement,
    val cumulativeTsn: UInt,
    val advertisedWindow: UInt,
    val numGaps: UShort,
    val numDupTsns: UShort
)
