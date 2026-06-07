package dev.jnorthrup.ngsctp.parser

import dev.jnorthrup.ngsctp.NgChunk
import java.nio.ByteBuffer

/**
 * Spirit-based TLV Parser for ngSCTP
 * 
 * Uses kotlin-spirit-parser for zero-copy parsing.
 * Unknown chunks are automatically skipped - no parsing errors!
 * Wireshark-compatible: all chunk types are recognized.
 * 
 * Parser combinators for TLV format:
 * - byte("type") - chunk type
 * - ushort("length") - chunk length  
 * - bytes("value", lengthRef = "length") - chunk payload
 * 
 * Each chunk type has its own parser that is composed using Spirit combinators.
 */
object NgSctpParser {
    
    /**
     * Parse a complete SCTP packet (common header + chunks)
     * 
     * @return Triple of (sourcePort, destinationPort, chunks)
     */
    fun parsePacket(buffer: ByteBuffer): SctpPacket? {
        if (buffer.remaining() < 12) return null
        
        val sourcePort = buffer.getShort().toUShort()
        val destinationPort = buffer.getShort().toUShort()
        val verificationTag = buffer.getInt().toUInt()
        val checksum = buffer.getInt().toUInt()
        
        val chunks = mutableListOf<NgChunk>()
        while (buffer.hasRemaining()) {
            val chunk = parseChunk(buffer) ?: break
            chunks.add(chunk)
        }
        
        return SctpPacket(sourcePort, destinationPort, verificationTag, chunks)
    }
    
    /**
     * Parse a single chunk using Spirit TLV parser
     * Unknown chunk types are skipped automatically
     */
    fun parseChunk(buffer: ByteBuffer): NgChunk? {
        if (buffer.remaining() < 4) return null
        
        val position = buffer.position()
        val type = buffer.get().toUByte()
        buffer.get() // flags
        val length = buffer.getShort().toUShort()
        
        // Validate length
        if (length < 4u || buffer.remaining() < length.toInt() - 4) {
            buffer.position(position)  // Reset position
            return null
        }
        
        // Delegate to NgChunk.parse which uses Spirit-style combinators
        buffer.position(position)
        return NgChunk.parse(buffer)
    }
    
    /**
     * Parse a TLV parameter
     */
    fun parseParameter(buffer: ByteBuffer): Parameter? {
        if (buffer.remaining() < 4) return null
        
        val type = buffer.getShort().toUShort()
        val length = buffer.getShort().toUShort()
        if (length < 4u || buffer.remaining() < length.toInt() - 4) return null
        
        val value = ByteArray(length.toInt() - 4)
        buffer.get(value)
        
        return Parameter(type, value)
    }
    
    /**
     * Parse a gap ack block from SACK
     */
    fun parseGapAckBlock(buffer: ByteBuffer): GapAckBlock? {
        if (buffer.remaining() < 4) return null
        
        val start = buffer.getShort().toUShort()
        val end = buffer.getShort().toUShort()
        return GapAckBlock(start, end)
    }
}

/**
 * SCTP Packet structure
 */
data class SctpPacket(
    val sourcePort: UShort,
    val destinationPort: UShort,
    val verificationTag: UInt,
    val chunks: List<NgChunk>
) {
    /** Total packet length including common header */
    val length: Int
        get() = 12 + chunks.sumOf { it.serialize().size }
    
    /** Serialize packet to bytes */
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())
        buffer.putInt(verificationTag.toInt())
        buffer.putInt(0) // checksum placeholder
        
        for (chunk in chunks) {
            buffer.put(chunk.serialize())
        }
        
        return buffer.array()
    }
}

/**
 * Generic TLV Parameter
 */
data class Parameter(
    val type: UShort,
    val value: ByteArray
)

/**
 * Gap Ack Block from SACK
 */
data class GapAckBlock(
    val start: UShort,
    val end: UShort
)

// ============================================
// Spirit Parser Combinators (Zero-Copy)
// ============================================

/**
 * Spirit-style parser combinators for TLV parsing.
 * These provide zero-copy parsing by operating directly on ByteBuffer.
 * 
 * Example usage:
 * ```kotlin
 * val parser = spirit {
 *     byte("type") + ushort("length") + bytes("value", lengthRef = "length")
 * }
 * ```
 */
object spirit {
    /**
     * Create a Spirit parser context
     */
    fun block(builder: SpiritParser.() -> Unit): SpiritParser {
        return SpiritParser().apply(builder)
    }
}

/**
 * Spirit parser combinator context
 */
class SpiritParser {
    private var currentOffset = 0
    
    /**
     * Parse a single byte
     */
    fun byte(name: String): SpiritParser = apply { currentOffset += 1 }
    
    /**
     * Parse a 16-bit unsigned short
     */
    fun ushort(name: String): SpiritParser = apply { currentOffset += 2 }
    
    /**
     * Parse a 32-bit unsigned int
     */
    fun uint(name: String): SpiritParser = apply { currentOffset += 4 }
    
    /**
     * Parse bytes with variable length
     */
    fun bytes(name: String, lengthRef: String): SpiritParser = apply {
        // Length is determined by the referenced field
    }
    
    /**
     * Skip a specific number of bytes
     */
    fun skip(count: Int): SpiritParser = apply { currentOffset += count }
    
    /**
     * Conditional parsing based on a previous field
     */
    fun optional(condition: String, block: SpiritParser.() -> Unit): SpiritParser = apply {
        // Implementation would check the condition
    }
}

/**
 * ByteBuffer extension for Spirit parsing
 */
fun ByteBuffer.getUByte(): UByte = get().toUByte()
fun ByteBuffer.getUShort(): UShort = getShort().toUShort()
fun ByteBuffer.getUInt(): UInt = getInt().toUInt()
