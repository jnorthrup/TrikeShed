package borg.trikeshed.ipfs.codec

import borg.trikeshed.ipfs.CID
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charsets
import java.security.MessageDigest

/**
 * Dag-CBOR Codec — IPLD CBOR with CID links
 *
 * Dag-CBOR is a restricted subset of CBOR (RFC 8949) designed for IPLD:
 * - No tags except tag 42 (CID link)
 * - No indefinite-length items
 * - Maps must have keys sorted lexicographically
 * - No floating point (use integers or bigints)
 *
 * CID Link format (tag 42): { "/": { "bytes": "<base64url CID bytes>" } }
 */
object DagCborCodec {

    private const val CID_TAG = 42

    /**
     * Encode a value to Dag-CBOR bytes.
     */
    fun encode(value: Any): ByteArray {
        val output = ByteArrayOutputStream()
        encodeValue(output, value)
        return output.toByteArray()
    }

    /**
     * Decode Dag-CBOR bytes to a value.
     */
    fun decode(data: ByteArray): Any {
        val cursor = Cursor(data)
        return decodeValue(cursor)
    }

    private fun encodeValue(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            null -> writeByte(out, 0xF6) // null
            is Boolean -> writeByte(out, if (value) 0xF5 else 0xF4) // true/false
            is Int -> encodeInt(out, value)
            is Long -> encodeLong(out, value)
            is BigInteger -> encodeBigInt(out, value)
            is String -> encodeString(out, value)
            is ByteArray -> encodeBytes(out, value)
            is List<*> -> encodeArray(out, value)
            is Map<*, *> -> encodeMap(out, value)
            is CID -> encodeCIDLink(out, value)
            else -> throw IllegalArgumentException("Unsupported Dag-CBOR type: ${value.javaClass}")
        }
    }

    private fun encodeInt(out: ByteArrayOutputStream, value: Int) {
        if (value >= 0) {
            if (value <= 23) writeByte(out, value.toByte())
            else if (value <= 0xFF) { writeByte(out, 0x18); writeByte(out, value.toByte()) }
            else if (value <= 0xFFFF) { writeByte(out, 0x19); writeShort(out, value) }
            else { writeByte(out, 0x1A); writeInt(out, value) }
        } else {
            val n = -1 - value
            if (n <= 23) writeByte(out, 0x20 + n)
            else if (n <= 0xFF) { writeByte(out, 0x38); writeByte(out, n.toByte()) }
            else if (n <= 0xFFFF) { writeByte(out, 0x39); writeShort(out, n.toInt()) }
            else { writeByte(out, 0x3A); writeInt(out, n.toInt()) }
        }
    }

    private fun encodeLong(out: ByteArrayOutputStream, value: Long) {
        if (value >= 0) {
            if (value <= 23) writeByte(out, value.toByte())
            else if (value <= 0xFF) { writeByte(out, 0x18); writeByte(out, value.toByte()) }
            else if (value <= 0xFFFF) { writeByte(out, 0x19); writeShort(out, value.toInt()) }
            else if (value <= 0xFFFFFFFF) { writeByte(out, 0x1A); writeInt(out, value.toInt()) }
            else { writeByte(out, 0x1B); writeLong(out, value) }
        } else {
            val n = -1 - value
            if (n <= 23) writeByte(out, 0x20 + n)
            else if (n <= 0xFF) { writeByte(out, 0x38); writeByte(out, n.toByte()) }
            else if (n <= 0xFFFF) { writeByte(out, 0x39); writeShort(out, n.toInt()) }
            else if (n <= 0xFFFFFFFF) { writeByte(out, 0x3A); writeInt(out, n.toInt()) }
            else { writeByte(out, 0x3B); writeLong(out, n) }
        }
    }

    private fun encodeBigInt(out: ByteArrayOutputStream, value: BigInteger) {
        // Encode as tagged bignum (tag 2/3)
        if (value.signum >= 0) {
            writeByte(out, 0xC2) // tag 2
        } else {
            writeByte(out, 0xC3) // tag 3
            value = value.abs()
        }
        encodeBytes(out, value.toByteArray())
    }

    private fun encodeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 23) writeByte(out, 0x60 + bytes.size)
        else if (bytes.size <= 0xFF) { writeByte(out, 0x78); writeByte(out, bytes.size.toByte()) }
        else if (bytes.size <= 0xFFFF) { writeByte(out, 0x79); writeShort(out, bytes.size) }
        else { writeByte(out, 0x7A); writeInt(out, bytes.size) }
        out.write(bytes)
    }

    private fun encodeBytes(out: ByteArrayOutputStream, value: ByteArray) {
        if (value.size <= 23) writeByte(out, 0x40 + value.size)
        else if (value.size <= 0xFF) { writeByte(out, 0x58); writeByte(out, value.size.toByte()) }
        else if (value.size <= 0xFFFF) { writeByte(out, 0x59); writeShort(out, value.size) }
        else { writeByte(out, 0x5A); writeInt(out, value.size) }
        out.write(value)
    }

    private fun encodeArray(out: ByteArrayOutputStream, value: List<*>) {
        if (value.size <= 23) writeByte(out, 0x80 + value.size)
        else if (value.size <= 0xFF) { writeByte(out, 0x98); writeByte(out, value.size.toByte()) }
        else if (value.size <= 0xFFFF) { writeByte(out, 0x99); writeShort(out, value.size) }
        else { writeByte(out, 0x9A); writeInt(out, value.size) }
        value.forEach { encodeValue(out, it) }
    }

    private fun encodeMap(out: ByteArrayOutputStream, value: Map<*, *>) {
        // Sort keys lexicographically as required by Dag-CBOR
        val sortedEntries = value.entries.sortedBy { it.key.toString() }
        if (sortedEntries.size <= 23) writeByte(out, 0xA0 + sortedEntries.size)
        else if (sortedEntries.size <= 0xFF) { writeByte(out, 0xB8); writeByte(out, sortedEntries.size.toByte()) }
        else if (sortedEntries.size <= 0xFFFF) { writeByte(out, 0xB9); writeShort(out, sortedEntries.size) }
        else { writeByte(out, 0xBA); writeInt(out, sortedEntries.size) }
        sortedEntries.forEach { entry ->
            encodeValue(out, entry.key)
            encodeValue(out, entry.value)
        }
    }

    private fun encodeCIDLink(out: ByteArrayOutputStream, cid: CID) {
        // Tag 42 + map with "/" key containing bytes
        writeByte(out, 0xD8) // tag
        writeByte(out, CID_TAG.toByte())
        // Map with 1 entry
        writeByte(out, 0xA1) // map(1)
        // Key: "/"
        writeByte(out, 0x61) // text(1)
        writeByte(out, 0x2F) // "/"
        // Value: bytes of CID
        encodeBytes(out, cid.bytes)
    }

    // ─── Byte writing helpers ───

    private inline fun writeByte(out: ByteArrayOutputStream, b: Byte) = out.write(b.toInt())
    private inline fun writeByte(out: ByteArrayOutputStream, b: Int) = out.write(b)

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8).toByte())
        out.write(value.toByte())
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24).toByte())
        out.write((value shr 16).toByte())
        out.write((value shr 8).toByte())
        out.write(value.toByte())
    }

    private fun writeLong(out: ByteArrayOutputStream, value: Long) {
        out.write((value shr 56).toByte())
        out.write((value shr 48).toByte())
        out.write((value shr 40).toByte())
        out.write((value shr 32).toByte())
        out.write((value shr 24).toByte())
        out.write((value shr 16).toByte())
        out.write((value shr 8).toByte())
        out.write(value.toByte())
    }

    // ─── Decoding ───

    private fun decodeValue(cursor: Cursor): Any {
        val b = cursor.readByte() and 0xFF
        val majorType = b shr 5
        val additional = b and 0x1F

        return when (majorType) {
            0 -> decodeUInt(cursor, additional) // unsigned int
            1 -> decodeNInt(cursor, additional) // negative int
            2 -> decodeBytes(cursor, additional) // byte string
            3 -> decodeString(cursor, additional) // text string
            4 -> decodeArray(cursor, additional) // array
            5 -> decodeMap(cursor, additional) // map
            6 -> decodeTag(cursor, additional) // tag
            7 -> decodeSimple(cursor, additional) // simple/float
            else -> throw IllegalArgumentException("Invalid major type: $majorType")
        }
    }

    private fun decodeUInt(cursor: Cursor, additional: Int): Long {
        return when (additional) {
            in 0..23 -> additional.toLong()
            24 -> cursor.readByte().toLong()
            25 -> cursor.readShort().toLong()
            26 -> cursor.readInt().toLong()
            27 -> cursor.readLong()
            else -> throw IllegalArgumentException("Invalid uint additional: $additional")
        }
    }

    private fun decodeNInt(cursor: Cursor, additional: Int): Long {
        val n = decodeUInt(cursor, additional)
        return -1 - n
    }

    private fun decodeBytes(cursor: Cursor, additional: Int): ByteArray {
        val len = decodeUInt(cursor, additional).toInt()
        return cursor.readBytes(len)
    }

    private fun decodeString(cursor: Cursor, additional: Int): String {
        val bytes = decodeBytes(cursor, additional)
        return String(bytes, Charsets.UTF_8)
    }

    private fun decodeArray(cursor: Cursor, additional: Int): List<Any> {
        val len = decodeUInt(cursor, additional).toInt()
        return List(len) { decodeValue(cursor) }
    }

    private fun decodeMap(cursor: Cursor, additional: Int): Map<String, Any> {
        val len = decodeUInt(cursor, additional).toInt()
        val map = mutableMapOf<String, Any>()
        repeat(len) {
            val key = decodeValue(cursor) as String
            val value = decodeValue(cursor)
            map[key] = value
        }
        return map
    }

    private fun decodeTag(cursor: Cursor, additional: Int): Any {
        val tagNum = decodeUInt(cursor, additional)
        val taggedValue = decodeValue(cursor)

        if (tagNum == CID_TAG.toLong()) {
            // CID link: { "/": { "bytes": <cid-bytes> } }
            val map = taggedValue as Map<String, Any>
            val link = map["/"] as Map<String, Any>
            val cidBytes = link["bytes"] as ByteArray
            return CID(cidBytes)
        }

        if (tagNum == 2L || tagNum == 3L) {
            // Bignum
            val bytes = taggedValue as ByteArray
            val bi = BigInteger(if (tagNum == 2L) 1 else -1, bytes)
            return bi
        }

        // Unknown tag - return wrapped
        return TaggedValue(tagNum, taggedValue)
    }

    private fun decodeSimple(cursor: Cursor, additional: Int): Any {
        return when (additional) {
            20 -> false
            21 -> true
            22 -> null
            23 -> Unit // undefined
            24 -> cursor.readByte().toDouble() // half-precision (not supported)
            25 -> cursor.readShort().toInt().toFloat() // float16 (not supported)
            26 -> cursor.readFloat() // float32
            27 -> cursor.readDouble() // float64
            else -> throw IllegalArgumentException("Invalid simple value: $additional")
        }
    }

    data class TaggedValue(val tag: Long, val value: Any)

    // ─── Cursor Helper ───

    private class Cursor(private val data: ByteArray) {
        var pos = 0

        fun readByte(): Byte = data[pos++]
        fun readShort(): Short = ((data[pos++] & 0xFF) shl 8 | (data[pos++] & 0xFF)).toShort()
        fun readInt(): Int {
            val b1 = data[pos++].toInt()
            val b2 = data[pos++].toInt()
            val b3 = data[pos++].toInt()
            val b4 = data[pos++].toInt()
            return (b1 shl 24) | (b2 shl 16) | (b3 shl 8) | b4
        }
        fun readLong(): Long {
            val b1 = data[pos++].toLong() & 0xFFL
            val b2 = data[pos++].toLong() & 0xFFL
            val b3 = data[pos++].toLong() & 0xFFL
            val b4 = data[pos++].toLong() & 0xFFL
            val b5 = data[pos++].toLong() & 0xFFL
            val b6 = data[pos++].toLong() & 0xFFL
            val b7 = data[pos++].toLong() & 0xFFL
            val b8 = data[pos++].toLong() & 0xFFL
            return (b1 shl 56) | (b2 shl 48) | (b3 shl 40) | (b4 shl 32) | (b5 shl 24) | (b6 shl 16) | (b7 shl 8) | b8
        }
        fun readFloat(): Float = java.lang.Float.intBitsToFloat(readInt())
        fun readDouble(): Double = java.lang.Double.longBitsToDouble(readLong())
        fun readBytes(len: Int): ByteArray {
            val result = data.copyOfRange(pos, pos + len)
            pos += len
            return result
        }
    }
}

// ─── Dag-PB Codec ───

/**
 * Dag-PB (Protocol Buffers) Codec
 *
 * Dag-PB uses protobuf for IPLD nodes with CID links.
 * Used for UnixFS and some legacy IPFS data structures.
 *
 * Message types:
 * - PBNode: { Links: repeated PBLink, Data: bytes }
 * - PBLink: { Name: string, Hash: CID (bytes), Tsize: uint64 }
 */
object DagPbCodec {

    /**
     * Encode a PBNode to Dag-PB bytes.
     */
    fun encodeNode(links: List<PBLink>, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        // Encode as protobuf
        // Links field (tag 1, repeated)
        links.forEach { link ->
            encodeLink(output, 1, link)
        }

        // Data field (tag 2, bytes)
        if (data.isNotEmpty()) {
            encodeBytes(output, 2, data)
        }

        return output.toByteArray()
    }

    private fun encodeLink(output: ByteArrayOutputStream, fieldTag: Int, link: PBLink) {
        // PBLink is a nested message (field 1, length-delimited)
        val linkData = ByteArrayOutputStream()

        // Name field (tag 1, string)
        if (link.name.isNotEmpty()) {
            encodeString(linkData, 1, link.name)
        }

        // Hash field (tag 2, bytes) - CID bytes
        encodeBytes(linkData, 2, link.cid.bytes)

        // Tsize field (tag 3, varint)
        if (link.tsize > 0) {
            encodeVarint(linkData, 3, link.tsize)
        }

        // Wrap as length-delimited field
        encodeLengthDelimited(output, fieldTag, linkData.toByteArray())
    }

    private fun encodeLengthDelimited(output: ByteArrayOutputStream, tag: Int, data: ByteArray) {
        val wireType = 2 // length-delimited
        val key = (tag shl 3) | wireType
        encodeVarintRaw(output, key.toLong())
        encodeVarintRaw(output, data.size.toLong())
        output.write(data)
    }

    private fun encodeVarint(output: ByteArrayOutputStream, tag: Int, value: Long) {
        val wireType = 0 // varint
        val key = (tag shl 3) | wireType
        encodeVarintRaw(output, key.toLong())
        encodeVarintRaw(output, value)
    }

    private fun encodeBytes(output: ByteArrayOutputStream, tag: Int, value: ByteArray) {
        val wireType = 2 // length-delimited
        val key = (tag shl 3) | wireType
        encodeVarintRaw(output, key.toLong())
        encodeVarintRaw(output, value.size.toLong())
        output.write(value)
    }

    private fun encodeString(output: ByteArrayOutputStream, tag: Int, value: String) {
        encodeBytes(output, tag, value.toByteArray(Charsets.UTF_8))
    }

    private fun encodeVarintRaw(output: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            writeByte(output, (v and 0x7F | 0x80).toByte())
            v = v ushr 7
        }
        writeByte(output, v.toByte())
    }

    // ─── Byte writing helpers for Dag-PB ───

    private inline fun writeByte(out: ByteArrayOutputStream, b: Byte) = out.write(b.toInt())
    private inline fun writeByte(out: ByteArrayOutputStream, b: Int) = out.write(b)

    // ─── Decoding ───

    data class PBLink(val name: String, val cid: CID, val tsize: Long)

    fun decodeNode(data: ByteArray): Pair<List<PBLink>, ByteArray> {
        val cursor = PbCursor(data)
        val links = mutableListOf<PBLink>()
        var nodeData = byteArrayOf()

        while (cursor.hasRemaining()) {
            val (fieldTag, wireType) = cursor.readKey()
            when (fieldTag) {
                1 -> { // Links
                    require(wireType == 2) { "Links must be length-delimited" }
                    val length = cursor.readVarint()
                    val linkData = cursor.readBytes(length.toInt())
                    links.add(decodeLink(linkData))
                }
                2 -> { // Data
                    require(wireType == 2) { "Data must be length-delimited" }
                    val length = cursor.readVarint()
                    nodeData = cursor.readBytes(length.toInt())
                }
                else -> { // Skip unknown fields
                    cursor.skipField(wireType)
                }
            }
        }

        return links to nodeData
    }

    private fun decodeLink(data: ByteArray): PBLink {
        val cursor = PbCursor(data)
        var name = ""
        var cidBytes = byteArrayOf()
        var tsize = 0L

        while (cursor.hasRemaining()) {
            val (fieldTag, wireType) = cursor.readKey()
            when (fieldTag) {
                1 -> { require(wireType == 2); name = String(cursor.readBytes(cursor.readVarint().toInt()), Charsets.UTF_8) }
                2 -> { require(wireType == 2); cidBytes = cursor.readBytes(cursor.readVarint().toInt()) }
                3 -> { require(wireType == 0); tsize = cursor.readVarint() }
                else -> cursor.skipField(wireType)
            }
        }

        return PBLink(name, CID(cidBytes), tsize)
    }

    private class PbCursor(private val data: ByteArray) {
        var pos = 0

        fun hasRemaining() = pos < data.size
        fun readByte(): Byte = data[pos++]
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = readByte().toLong()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0L) break
                shift += 7
            }
            return result
        }
        fun readKey(): Pair<Int, Int> {
            val key = readVarint()
            val tag = (key shr 3).toInt()
            val wireType = (key and 0x7).toInt()
            return tag to wireType
        }
        fun readBytes(len: Int): ByteArray {
            val result = data.copyOfRange(pos, pos + len)
            pos += len
            return result
        }
        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint() // varint
                1 -> pos += 8 // 64-bit
                2 -> { val len = readVarint(); pos += len.toInt() } // length-delimited
                5 -> pos += 4 // 32-bit
                else -> throw IllegalArgumentException("Unknown wire type: $wireType")
            }
        }
    }
}