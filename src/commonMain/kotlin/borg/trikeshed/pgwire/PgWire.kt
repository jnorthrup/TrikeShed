package borg.trikeshed.pgwire

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.close
import borg.trikeshed.parse.confix.open
import borg.trikeshed.parse.confix.row
import borg.trikeshed.parse.confix.src
import borg.trikeshed.parse.kursive.sql.confixCellOf
import borg.trikeshed.parse.kursive.sql.scalarText
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.platform.spi.PlatformCodec

/**
 * The PostgreSQL frontend/backend protocol, version 3.0, as bytes: a message is a type byte and an
 * Int32 length that counts itself, in network order; the startup messages carry no type byte. This is
 * the length-framed sibling of the process frame channel's newline framer: [decode] answers one
 * message and the bytes it took, or null until a whole frame is present.
 */
sealed class PgFrontend {
    class Startup(val parameters: Map<String, String>) : PgFrontend()
    data object SslRequest : PgFrontend()
    data object GssEncRequest : PgFrontend()
    class CancelRequest(val pid: Int, val secret: Int) : PgFrontend()
    class Query(val text: String) : PgFrontend()
    class Parse(val name: String, val text: String, val types: IntArray) : PgFrontend()
    /** [values] are the parameter bytes (null for SQL NULL) in [formats] (0 text, 1 binary, per parameter after pg's expansion rule). */
    class Bind(val portal: String, val statement: String, val formats: IntArray, val values: Array<ByteArray?>, val resultFormats: IntArray) : PgFrontend()
    class Describe(val kind: Char, val name: String) : PgFrontend()
    class Execute(val portal: String, val maxRows: Int) : PgFrontend()
    class Close(val kind: Char, val name: String) : PgFrontend()
    class Password(val text: String) : PgFrontend()
    data object Sync : PgFrontend()
    data object Flush : PgFrontend()
    data object Terminate : PgFrontend()
    class Unknown(val type: Char) : PgFrontend()
    /** A frame the decoder refused; the session answers `08P01` and ends. */
    class Malformed(val reason: String) : PgFrontend()
}

const val PROTOCOL_3_0 = 196608
const val SSL_REQUEST = 80877103
const val GSSENC_REQUEST = 80877104
const val CANCEL_REQUEST = 80877102

/** One result column as RowDescription describes it. */
class PgField(val name: String, val typeOid: Long, val format: Int = 0) {
    val typeLength: Int get() = PG_TYPES_BY_OID[typeOid]?.length?.toInt() ?: -1
}

private val PG_TYPES_BY_OID = borg.trikeshed.cursor.query.PG_TYPES.associateBy { it.oid }

/** A backend message under construction: the type byte, a length patched at [bytes], then the payload. */
class PgFrame(type: Char) {
    private var buffer = ByteArray(64)
    private var size = 5

    init { buffer[0] = type.code.toByte() }

    private fun room(n: Int) {
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
    }

    fun byte(b: Int): PgFrame { room(1); buffer[size++] = b.toByte(); return this }
    fun int16(v: Int): PgFrame { room(2); buffer[size++] = (v shr 8).toByte(); buffer[size++] = v.toByte(); return this }
    fun int32(v: Int): PgFrame {
        room(4)
        buffer[size++] = (v shr 24).toByte(); buffer[size++] = (v shr 16).toByte(); buffer[size++] = (v shr 8).toByte(); buffer[size++] = v.toByte()
        return this
    }
    fun bytes(b: ByteArray): PgFrame { room(b.size); b.copyInto(buffer, size); size += b.size; return this }
    fun cstring(s: String): PgFrame = bytes(s.encodeToByteArray()).byte(0)

    /** The framed message: the length counts everything after the type byte, itself included. */
    fun bytes(): ByteArray {
        val length = size - 1
        buffer[1] = (length shr 24).toByte(); buffer[2] = (length shr 16).toByte(); buffer[3] = (length shr 8).toByte(); buffer[4] = length.toByte()
        return buffer.copyOf(size)
    }
}

object PgBackend {
    /** The one-byte answer to SSLRequest and GSSENCRequest: no encryption here, continue in the clear. */
    val NO: ByteArray = byteArrayOf('N'.code.toByte())

    fun authenticationOk(): ByteArray = PgFrame('R').int32(0).bytes()
    fun parameterStatus(name: String, value: String): ByteArray = PgFrame('S').cstring(name).cstring(value).bytes()
    fun backendKeyData(pid: Int, secret: Int): ByteArray = PgFrame('K').int32(pid).int32(secret).bytes()
    fun readyForQuery(status: Char): ByteArray = PgFrame('Z').byte(status.code).bytes()
    fun parseComplete(): ByteArray = PgFrame('1').bytes()
    fun bindComplete(): ByteArray = PgFrame('2').bytes()
    fun closeComplete(): ByteArray = PgFrame('3').bytes()
    fun noData(): ByteArray = PgFrame('n').bytes()
    fun portalSuspended(): ByteArray = PgFrame('s').bytes()
    fun emptyQueryResponse(): ByteArray = PgFrame('I').bytes()
    fun commandComplete(tag: String): ByteArray = PgFrame('C').cstring(tag).bytes()

    fun parameterDescription(oids: IntArray): ByteArray = PgFrame('t').int16(oids.size).also { f -> for (oid in oids) f.int32(oid) }.bytes()

    fun rowDescription(fields: List<PgField>): ByteArray {
        val f = PgFrame('T').int16(fields.size)
        for (field in fields) f.cstring(field.name).int32(0).int16(0).int32(field.typeOid.toInt()).int16(field.typeLength).int32(-1).int16(field.format)
        return f.bytes()
    }

    fun dataRow(cells: List<ByteArray?>): ByteArray {
        val f = PgFrame('D').int16(cells.size)
        for (cell in cells) if (cell == null) f.int32(-1) else f.int32(cell.size).bytes(cell)
        return f.bytes()
    }

    fun errorResponse(code: String, message: String, detail: String? = null, hint: String? = null, severity: String = "ERROR"): ByteArray {
        val f = PgFrame('E').byte('S'.code).cstring(severity).byte('V'.code).cstring(severity).byte('C'.code).cstring(code).byte('M'.code).cstring(message)
        detail?.let { f.byte('D'.code).cstring(it) }
        hint?.let { f.byte('H'.code).cstring(it) }
        return f.byte(0).bytes()
    }

    /**
     * A cell in pg's text format: null stays null (Int32 -1), booleans `t`/`f`, a vector as the `real[]` its OID
     * declares (`{1.0,2.0}`), jsonb by its source bytes.
     */
    fun text(value: Any?): ByteArray? = when (value) {
        null -> null
        is Boolean -> (if (value) "t" else "f").encodeToByteArray()
        is String -> value.encodeToByteArray()
        is FloatArray -> value.joinToString(",", "{", "}").encodeToByteArray()
        is Array<*> -> value.joinToString(",", "{", "}") { arrayElement(it) }.encodeToByteArray()
        is Number -> value.toString().encodeToByteArray()
        else -> confixCellOf(value)?.let { cell -> ByteArray(cell.row.close - cell.row.open + 1) { cell.src[cell.row.open + it] } }
            ?: scalarText(value).encodeToByteArray()
    }

    /** One element of a pg array literal: `NULL`, or its text, double-quoted when empty, spelled NULL, or holding a brace, comma, quote, backslash or space. */
    fun arrayElement(x: Any?): String {
        if (x == null) return "NULL"
        val s = scalarText(x)
        return if (s.isEmpty() || s.equals("NULL", ignoreCase = true) || s.any { it in "{},\"\\ " }) "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else s
    }

    /**
     * A cell in pg's binary format for the type the RowDescription declared: the fixed-width scalars by their
     * bits, text types by their UTF-8 bytes, jsonb as version byte 1 and its text, `real[]` with pg's array
     * header (ndim, hasnull, element type, dimension, lower bound) then length-prefixed elements.
     */
    fun binary(value: Any?, typeOid: Long): ByteArray? = when {
        value == null -> null
        typeOid == 16L -> byteArrayOf(if (value == true) 1 else 0)
        typeOid == 20L && value is Number -> PlatformCodec.writeLong(value.toLong())
        typeOid == 23L && value is Number -> PlatformCodec.writeInt(value.toInt())
        typeOid == 21L && value is Number -> PlatformCodec.writeShort(value.toShort())
        typeOid == 701L && value is Number -> PlatformCodec.writeDouble(value.toDouble())
        typeOid == 700L && value is Number -> PlatformCodec.writeFloat(value.toFloat())
        typeOid == 3802L -> byteArrayOf(1) + (text(value) ?: ByteArray(0))
        typeOid == 1021L && value is FloatArray -> {
            val f = PgFrame('A').int32(1).int32(0).int32(700).int32(value.size).int32(1)
            for (x in value) f.int32(4).int32(x.toBits())
            f.bytes().copyOfRange(5, 5 + 20 + 8 * value.size)
        }
        else -> text(value)
    }

    /** Every type this wire declares has a binary form; a client's requested format is honoured, never downgraded. */
    fun binaryCapable(typeOid: Long): Boolean = typeOid in BINARY_TYPES

    val BINARY_TYPES: Set<Long> = setOf(16L, 19L, 20L, 21L, 23L, 25L, 700L, 701L, 705L, 1021L, 1043L, 3802L)
}

/** A reader over one frame's payload. */
class PgReader(val bytes: ByteArray, var pos: Int, val end: Int) {
    private val view = ByteBuffer(bytes, 0, bytes.size)
    private fun need(n: Int) { if (n < 0 || pos + n > end) throw PgProtocolError("insufficient data left in message") }
    fun byte(): Int { need(1); return bytes[pos++].toInt() }
    fun int16(): Int { need(2); return view.getShort(pos).toInt().also { pos += 2 } }
    /** A count: pg sends Int16 counts, never negative. */
    fun count(): Int = int16().also { if (it < 0) throw PgProtocolError("negative count in message") }
    fun int32(): Int { need(4); return view.getInt(pos).also { pos += 4 } }
    fun cstring(): String {
        var i = pos
        while (i < end && bytes[i] != 0.toByte()) i++
        if (i >= end) throw PgProtocolError("unterminated string in message")
        return bytes.decodeToString(pos, i).also { pos = i + 1 }
    }
    fun bytes(n: Int): ByteArray { need(n); return bytes.copyOfRange(pos, pos + n).also { pos += n } }
}

/** A malformed frame; the session answers `08P01 protocol_violation` and closes. */
class PgProtocolError(message: String) : IllegalArgumentException(message)

object PgWire {
    /** The largest frame accepted, as pg's own limit on a message. */
    const val MAX_FRAME = 1 shl 30

    /** The largest startup packet, as pg's MAX_STARTUP_PACKET_LENGTH. */
    const val MAX_STARTUP = 10000

    /**
     * One frontend message from `bytes[offset until limit]`, with the bytes it took, or null while the frame is
     * incomplete. [startup] reads the typeless startup form; after a Startup message the typed form follows.
     */
    fun decode(bytes: ByteArray, offset: Int, limit: Int, startup: Boolean): Join<PgFrontend, Int>? {
        val available = limit - offset
        if (startup) {
            if (available < 4) return null
            val length = ByteBuffer(bytes, 0, bytes.size).getInt(offset)
            if (length < 8 || length > MAX_STARTUP) throw PgProtocolError("invalid length of startup packet")
            if (available < length) return null
            val r = PgReader(bytes, offset + 4, offset + length)
            val message = when (val code = r.int32()) {
                SSL_REQUEST -> PgFrontend.SslRequest
                GSSENC_REQUEST -> PgFrontend.GssEncRequest
                CANCEL_REQUEST -> PgFrontend.CancelRequest(r.int32(), r.int32())
                PROTOCOL_3_0 -> {
                    val parameters = LinkedHashMap<String, String>()
                    while (r.pos < r.end - 1) {
                        val key = r.cstring()
                        if (key.isEmpty()) break
                        parameters[key] = r.cstring()
                    }
                    PgFrontend.Startup(parameters)
                }
                else -> throw PgProtocolError("unsupported frontend protocol ${code ushr 16}.${code and 0xffff}: server supports 3.0")
            }
            return message j length
        }
        if (available < 5) return null
        val type = bytes[offset].toInt().toChar()
        val length = ByteBuffer(bytes, 0, bytes.size).getInt(offset + 1)
        if (length < 4 || length > MAX_FRAME) throw PgProtocolError("invalid message length $length")
        if (available < length + 1) return null
        val r = PgReader(bytes, offset + 5, offset + 1 + length)
        val message: PgFrontend = when (type) {
            'Q' -> PgFrontend.Query(r.cstring())
            'P' -> {
                val name = r.cstring()
                val text = r.cstring()
                PgFrontend.Parse(name, text, IntArray(r.count()) { r.int32() })
            }
            'B' -> {
                val portal = r.cstring()
                val statement = r.cstring()
                val formats = IntArray(r.count()) { r.int16() }
                val count = r.count()
                if (formats.size > 1 && formats.size != count) throw PgProtocolError("bind message has ${formats.size} parameter formats but $count parameters")
                val values = Array(count) { r.int32().let { n -> if (n < 0) null else r.bytes(n) } }
                val perValue = IntArray(count) { i -> if (formats.isEmpty()) 0 else if (formats.size == 1) formats[0] else formats[i] }
                PgFrontend.Bind(portal, statement, perValue, values, IntArray(r.count()) { r.int16() })
            }
            'D' -> PgFrontend.Describe(r.byte().toChar(), r.cstring())
            'E' -> PgFrontend.Execute(r.cstring(), r.int32())
            'C' -> PgFrontend.Close(r.byte().toChar(), r.cstring())
            'p' -> PgFrontend.Password(r.cstring())
            'S' -> PgFrontend.Sync
            'H' -> PgFrontend.Flush
            'X' -> PgFrontend.Terminate
            else -> PgFrontend.Unknown(type)
        }
        return message j (length + 1)
    }
}
