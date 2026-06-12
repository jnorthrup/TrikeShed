package borg.trikeshed.htx.client.ipfs

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * CAR (Content Addressable aRchive) Format Parser/Writer — v1 and v2.
 * 
 * CAR v1: header + blocks
 * CAR v2: header + blocks + index
 * 
 * Structure:
 * - Magic: 0xC5D1 (little-endian)
 * - Version: varint (1 or 2)
 * - Header length: varint
 * - Header: CBOR-encoded { roots: [CID], version: int, ... }
 * - Blocks: varint(length) + CID bytes + block data
 * - Index (v2): marker (0xFFFFFFFF) + varint(length) + CBOR index
 */
object CarParser {

    private const val CAR_MAGIC = 0xC5D1.toShort()
    private const val CAR_VERSION_1 = 1
    private const val CAR_VERSION_2 = 2

    /** Parse a CAR file from byte array. */
    @Throws(IOException::class)
    fun parse(data: ByteArray): CarParseResult {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Read magic
        val magic = buffer.short
        require(magic == CAR_MAGIC) { "Invalid CAR magic: ${magic.toString(16)}" }

        // Read version
        val version = readVarint(buffer)
        require(version == CAR_VERSION_1 || version == CAR_VERSION_2) {
            "Unsupported CAR version: $version"
        }

        // Read header
        val headerLength = readVarint(buffer).toInt()
        val headerData = ByteArray(headerLength)
        buffer.get(headerData)
        val roots = parseRootsFromHeader(headerData)

        // Read blocks
        val blocks = mutableListOf<CarBlock>()
        while (buffer.remaining() > 0) {
            // Check for v2 index marker
            if (version == CAR_VERSION_2 && buffer.remaining() > 0) {
                val pos = buffer.position
                val marker = readVarint(buffer)
                if (marker == 0xFFFFFFFFL) {
                    buffer.position = pos
                    break
                }
                buffer.position = pos
            }

            try {
                blocks.add(readBlock(buffer))
            } catch (e: Exception) {
                break
            }
        }

        // Parse index if v2
        val index = if (version == CAR_VERSION_2) parseIndex(buffer) else null

        return CarParseResult(
            roots = roots,
            blocks = blocks,
            version = version.toInt(),
            index = index,
            dataCid = computeDataCid(blocks),
        )
    }

    private fun readVarint(buffer: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = (buffer.get() and 0xFF).toLong()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift > 63) throw IOException("Varint too long")
        }
        return result
    }

    private fun parseRootsFromHeader(data: ByteArray): List<CID> {
        // Simplified: try to find CID-like patterns in header
        // Real impl would use full CBOR decoding
        return emptyList()
    }

    private fun readBlock(buffer: ByteBuffer): CarBlock {
        val blockLength = readVarint(buffer).toInt()
        val blockData = ByteArray(blockLength)
        buffer.get(blockData)
        val cid = parseCidFromBlock(blockData)
        return CarBlock(cid, blockData)
    }

    private fun parseCidFromBlock(blockData: ByteArray): CID {
        // CID is multihash in the block - simplified extraction
        return CID(blockData.copyOfRange(0, kotlin.math.min(32, blockData.size)))
    }

    private fun parseIndex(buffer: ByteBuffer): CarIndex {
        val marker = readVarint(buffer)
        require(marker == 0xFFFFFFFFL) { "Expected index marker" }
        val indexLength = readVarint(buffer).toInt()
        val indexData = ByteArray(indexLength)
        buffer.get(indexData)
        return CarIndex(offsets = emptyMap())
    }

    private fun computeDataCid(blocks: List<CarBlock>): CID {
        val digest = MessageDigest.getInstance("SHA-256")
        blocks.forEach { digest.update(it.data) }
        return CID(digest.digest())
    }

    companion object {
        fun writeVarint(value: Long): ByteArray {
            return buildByteArray {
                var v = value
                while (v >= 0x80) {
                    writeByte((v and 0x7F | 0x80).toByte())
                    v = v ushr 7
                }
                writeByte(v.toByte())
            }
        }
    }
}

/** CAR Block: CID + raw data */
data class CarBlock(
    val cid: CID,
    val data: ByteArray,
)

/** CAR Index: CID hex -> byte offset in file */
data class CarIndex(
    val offsets: Map<String, Long>,
)

/** CAR Parse Result */
data class CarParseResult(
    val roots: List<CID>,
    val blocks: List<CarBlock>,
    val version: Int,
    val index: CarIndex?,
    val dataCid: CID,
) {
    /** Verify all CIDs match content. */
    fun verified(): Boolean = blocks.all { block ->
        val computedCid = computeCid(block.data)
        computedCid.bytes.contentEquals(block.cid.bytes)
    }

    private fun computeCid(data: ByteArray): CID {
        val digest = MessageDigest.getInstance("SHA-256")
        return CID(digest.digest(data))
    }
}

/** CAR Writer */
object CarWriter {
    fun write(blocks: List<CarBlock>, roots: List<CID>, version: Int = 2): ByteArray {
        return buildByteArray {
            // Magic
            writeShort(0xC5D1.toShort())
            // Version
            write(CarParser.writeVarint(version.toLong()))
            // Header placeholder (will be updated)
            val headerStart = size
            writeInt(0)
            
            // Write header (simplified CBOR)
            val headerData = buildHeader(roots, version)
            write(headerData)
            
            // Update header length
            val headerLength = headerData.size
            val headerLenBytes = CarParser.writeVarint(headerLength.toLong())
            // Note: In real impl, would need proper buffer management
            
            // Write blocks
            blocks.forEach { block ->
                write(CarParser.writeVarint(block.data.size.toLong()))
                write(block.cid.bytes)
                write(block.data)
            }
            
            // Write index (v2)
            if (version == 2) writeIndex(blocks)
        }
    }

    private fun buildHeader(roots: List<CID>, version: Int): ByteArray {
        return buildByteArray {
            // Simplified CBOR map: { "roots": [...], "version": version }
            writeByte(0xA2) // map(2)
            // "roots" key
            writeByte(0x65) // text(5)
            write("roots".toByteArray())
            // roots array
            writeByte(0x80 + roots.size) // array
            roots.forEach { cid ->
                writeByte(0x40 + cid.bytes.size) // bytes
                write(cid.bytes)
            }
            // "version" key
            writeByte(0x67) // text(7)
            write("version".toByteArray())
            // version value
            writeByte(version.toByte())
        }
    }

    private fun writeIndex(blocks: List<CarBlock>) {
        write(CarParser.writeVarint(0xFFFFFFFFL)) // index marker
        // Simplified index - real impl would write proper CBOR
    }
}