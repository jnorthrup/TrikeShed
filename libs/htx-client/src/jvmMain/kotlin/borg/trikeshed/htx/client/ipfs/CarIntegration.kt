package borg.trikeshed.htx.client.ipfs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object CarParser {

    private const val CAR_MAGIC = 0xC5D1.toShort()
    private const val CAR_VERSION_1 = 1
    private const val CAR_VERSION_2 = 2

    @Throws(IOException::class)
    fun parse(data: ByteArray): CarParseResult = parseWithBlocks(data) { }

    /**
     * Parse a CAR archive, optionally processing each block via [blockHandler].
     * Returns the parse result with block count.
     */
    @Throws(IOException::class)
    fun parseWithBlocks(data: ByteArray, blockHandler: (CarBlock) -> Unit): CarParseResult {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.short
        require(magic == CAR_MAGIC) { "Invalid CAR magic: ${magic.toString(16)}" }

        val version = readVarint(buffer)
        require(version == CAR_VERSION_1.toLong() || version == CAR_VERSION_2.toLong()) {
            "Unsupported CAR version: $version"
        }

        val headerLength = readVarint(buffer).toInt()
        val headerData = ByteArray(headerLength)
        buffer.get(headerData)
        val roots = parseRootsFromHeader(headerData)

        var blockCount = 0
        while (buffer.remaining() > 0) {
            if (version == CAR_VERSION_2.toLong() && buffer.remaining() > 0) {
                val pos = buffer.position()
                val marker = readVarint(buffer)
                if (marker == 0xFFFFFFFFL) {
                    buffer.position(pos)
                    break
                }
                buffer.position(pos)
            }

            try {
                val block = readBlock(buffer)
                blockHandler(block)
                blockCount++
            } catch (e: Exception) {
                break
            }
        }

        return CarParseResult(
            roots = roots,
            blockCount = blockCount,
            version = version.toInt(),
            dataCid = CID(byteArrayOf()),
        )
    }

    private fun readVarint(buffer: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = (buffer.get().toInt() and 0xFF).toLong()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift > 63) throw IOException("Varint too long")
        }
        return result
    }

    private fun parseRootsFromHeader(data: ByteArray): List<CID> = emptyList()

    private fun readBlock(buffer: ByteBuffer): CarBlock {
        val blockLength = readVarint(buffer).toInt()
        val blockData = ByteArray(blockLength)
        buffer.get(blockData)
        val cid = parseCidFromBlock(blockData)
        return CarBlock(cid, blockData)
    }

    private fun parseCidFromBlock(blockData: ByteArray): CID =
        CID(blockData.copyOfRange(0, kotlin.math.min(32, blockData.size)))
}

/**
 * CAR block with CID and raw data.
 * Defined here in jvmMain; used by CarWriter.
 */
data class CarBlock(val cid: CID, val data: ByteArray)

data class CarParseResult(
    val roots: List<CID>,
    val blockCount: Int,
    val version: Int,
    val dataCid: CID,
)

/**
 * CAR index for fast random access (CARv2).
 */
data class CarIndex(val offsets: Map<String, Long>)

object CarWriter {
    fun write(blocks: List<CarBlock>, roots: List<CID>, version: Int = 2): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(0xC5D1 and 0xFF)
        output.write(0xC5D1 ushr 8)
        writeVarint(output, version.toLong())
        output.write(0)
        buildHeader(roots, version).forEach { output.write(it.toInt()) }
        blocks.forEach { block ->
            writeVarint(output, block.data.size.toLong())
            block.cid.bytes.forEach { output.write(it.toInt()) }
            block.data.forEach { output.write(it.toInt()) }
        }
        if (version == 2) writeVarint(output, 0xFFFFFFFFL)
        return output.toByteArray()
    }

    private fun writeVarint(output: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            val b: Int = ((v and 0x7FL or 0x80L).toByte()).toInt()
            output.write(b)
            v = v ushr 7
        }
        val b: Int = (v.toByte()).toInt()
        output.write(b)
    }

    private fun buildHeader(roots: List<CID>, version: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(0xA2.toInt())
        output.write(0x65.toInt())
        "roots".toByteArray().forEach { output.write(it.toInt()) }
        output.write((0x80 + roots.size).toInt())
        roots.forEach { cid ->
            output.write((0x40 + cid.bytes.size).toInt())
            cid.bytes.forEach { output.write(it.toInt()) }
        }
        output.write(0x67.toInt())
        "version".toByteArray().forEach { output.write(it.toInt()) }
        output.write(version.toInt())
        return output.toByteArray()
    }
}
