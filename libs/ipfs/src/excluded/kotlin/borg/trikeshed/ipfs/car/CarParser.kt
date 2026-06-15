package borg.trikeshed.ipfs.car

import borg.trikeshed.ipfs.CID
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * CAR (Content Addressable aRchive) Format Parser
 *
 * CAR v1: https://github.com/ipld/specs/blob/main/block-layer/car/carv1.md
 * CAR v2: https://github.com/ipld/specs/blob/main/block-layer/car/carv2.md
 *
 * Structure:
 * - Header: magic bytes (0xC5 0xD1) + version (1 or 2) + header data
 * - Blocks: varint(length) + CID + block data
 * - For v2: additional index section at end
 */
object CarParser {

    private const val CAR_MAGIC = 0xC5D1.toShort()
    private const val CAR_VERSION_1 = 1
    private const val CAR_VERSION_2 = 2

    /**
     * Parse a CAR file from byte array.
     * Returns (roots, blocks, version, index)
     */
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
        val header = readHeader(buffer, version)

        // Read blocks
        val blocks = mutableListOf<CarBlock>()
        while (buffer.remaining() > 0) {
            // Peek - if we're at index section (v2), break
            if (version == CAR_VERSION_2 && buffer.remaining() > 0) {
                // Check if next is index marker
                val pos = buffer.position()
                val marker = readVarint(buffer)
                if (marker == 0xFFFFFFFFL) {
                    // Index section marker
                    buffer.position() = pos
                    break
                }
                buffer.position() = pos
            }

            try {
                blocks.add(readBlock(buffer))
            } catch (e: Exception) {
                // End of blocks or malformed
                break
            }
        }

        // Parse index if v2
        val index = if (version == CAR_VERSION_2) parseIndex(buffer) else null

        return CarParseResult(
            roots = header.roots,
            blocks = blocks,
            version = version,
            index = index,
            dataCid = computeDataCid(blocks)
        )
    }

    private data class CarHeader(
        val roots: List<CID>,
        val version: Int,
    )

    private fun readHeader(buffer: ByteBuffer, version: Int): CarHeader {
        val headerLength = readVarint(buffer)
        val headerData = ByteArray(headerLength.toInt())
        buffer.get(headerData)

        // Parse header using CBOR/DAG-CBOR
        // For simplicity, we'll parse the roots array
        // Real impl would use full CBOR decoding
        return CarHeader(
            roots = parseRootsFromHeader(headerData),
            version = version,
        )
    }

    private fun parseRootsFromHeader(data: ByteArray): List<CID> {
        // Simplified: assume header is a CBOR map with "roots" key
        // Real impl: use CborDecoder
        return emptyList() // Placeholder
    }

    private fun readBlock(buffer: ByteBuffer): CarBlock {
        val blockLength = readVarint(buffer)
        val blockData = ByteArray(blockLength.toInt())
        buffer.get(blockData)

        // Parse CID from block data
        val cid = parseCidFromBlock(blockData)

        return CarBlock(cid = cid, data = blockData)
    }

    private fun parseCidFromBlock(blockData: ByteArray): CID {
        // CID is multihash prefixed in the block
        // For simplicity, extract from first bytes
        // Real impl: proper multihash parsing
        return CID(blockData.copyOfRange(0, min(32, blockData.size)))
    }

    private fun parseIndex(buffer: ByteBuffer): CarIndex {
        // Read index marker
        val marker = readVarint(buffer)
        require(marker == 0xFFFFFFFFL) { "Expected index marker" }

        val indexLength = readVarint(buffer)
        val indexData = ByteArray(indexLength.toInt())
        buffer.get(indexData)

        // Parse index (CBOR encoded)
        // Simplified for now
        return CarIndex(offsets = emptyMap())
    }

    private fun computeDataCid(blocks: List<CarBlock>): CID {
        // Compute CID of all block data concatenated
        val digest = MessageDigest.getInstance("SHA-256")
        blocks.forEach { digest.update(it.data) }
        return CID(digest.digest())
    }

    // Varint encoding/decoding (LEB128)
    private fun readVarint(buffer: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buffer.get() and 0xFFL
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift > 63) throw IOException("Varint too long")
        }
        return result
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

// ─── CAR Data Structures ───

data class CarBlock(
    val cid: CID,
    val data: ByteArray,
)

data class CarIndex(
    val offsets: Map<String, Long>, // CID hex -> byte offset
)

data class CarParseResult(
    val roots: List<CID>,
    val blocks: List<CarBlock>,
    val version: Int,
    val index: CarIndex?,
    val dataCid: CID,
) {
    fun verified(): Boolean {
        // Verify all CIDs match content
        return blocks.all { block ->
            val computedCid = computeCid(block.data)
            computedCid.bytes.contentEquals(block.cid.bytes)
        }
    }

    private fun computeCid(data: ByteArray): CID {
        val digest = MessageDigest.getInstance("SHA-256")
        return CID(digest.digest(data))
    }
}

// ─── CAR Writer ───

object CarWriter {
    fun write(blocks: List<CarBlock>, roots: List<CID>, version: Int = 2): ByteArray {
        return buildByteArray {
            // Magic
            writeShort(0xC5D1.toShort())
            // Version
            write(CarParser.writeVarint(version.toLong()))
            // Placeholder for header length
            val headerStart = size
            writeInt(0) // Will fill in later

            // Write header (simplified)
            val headerData = buildHeader(roots, version)
            write(headerData)

            // Go back and write header length
            val headerLength = headerData.size
            val headerLenBytes = CarParser.writeVarint(headerLength.toLong())
            // This is simplified - real impl needs proper buffer management

            // Write blocks
            blocks.forEach { block ->
                write(CarParser.writeVarint(block.data.size.toLong()))
                write(block.data)
            }

            // Write index (v2)
            if (version == 2) {
                writeIndex(blocks)
            }
        }
    }

    private fun buildHeader(roots: List<CID>, version: Int): ByteArray {
        // Build CBOR header
        // Simplified
        return byteArrayOf()
    }

    private fun writeIndex(blocks: List<CarBlock>) {
        // Write index marker
        write(CarParser.writeVarint(0xFFFFFFFFL))
        // Write index data (simplified)
    }
}

// Helper functions
private fun ByteBuffer.writeShort(value: Short) = putShort(value)
private fun ByteBuffer.writeInt(value: Int) = putInt(value)
private fun ByteBuffer.writeLong(value: Long) = putLong(value)
private fun ByteBuffer.writeByte(value: Byte) = put(value)
private fun ByteBuffer.write(data: ByteArray) = put(data)