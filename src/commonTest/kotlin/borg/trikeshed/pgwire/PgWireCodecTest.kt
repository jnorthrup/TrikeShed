package borg.trikeshed.pgwire

import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.rootCell
import kotlin.test.*

/** The wire's bytes, both directions, against the protocol's own examples. */
@OptIn(ExperimentalStdlibApi::class)
class PgWireCodecTest {
    private fun decode(bytes: ByteArray, startup: Boolean) = PgWire.decode(bytes, 0, bytes.size, startup)

    @Test fun startupFormsDecodeWholeOrNotAtAll() {
        val ssl = "0000000804d2162f".hexToByteArray()
        for (n in 1 until ssl.size) assertNull(decode(ssl.copyOf(n), true), "a $n-byte prefix is not a frame")
        decode(ssl, true)!!.let { (m, used) -> assertIs<PgFrontend.SslRequest>(m); assertEquals(8, used) }
        decode("0000000804d21630".hexToByteArray(), true)!!.let { (m, _) -> assertIs<PgFrontend.GssEncRequest>(m) }
        decode("0000001004d2162e0000002a00000007".hexToByteArray(), true)!!.let { (m, _) -> assertIs<PgFrontend.CancelRequest>(m).let { assertEquals(42, it.pid); assertEquals(7, it.secret) } }
        val body = "user\u0000x\u0000database\u0000y\u0000client_encoding\u0000UTF8\u0000\u0000".encodeToByteArray()
        val startup = "0000001b00030000".hexToByteArray().also { it[3] = (8 + body.size).toByte() } + body
        for (n in 1 until startup.size) assertNull(decode(startup.copyOf(n), true))
        decode(startup, true)!!.let { (m, used) ->
            assertEquals(mapOf("user" to "x", "database" to "y", "client_encoding" to "UTF8"), assertIs<PgFrontend.Startup>(m).parameters)
            assertEquals(startup.size, used)
        }
        assertFailsWith<PgProtocolError> { decode("0000000800020000".hexToByteArray(), true) }
    }

    @Test fun typedFramesDecodeByTypeAndLength() {
        val query = "51".hexToByteArray() + "0000000d".hexToByteArray() + "SELECT 1\u0000".encodeToByteArray()
        for (n in 1 until query.size) assertNull(decode(query.copyOf(n), false))
        decode(query + byteArrayOf(0x58), false)!!.let { (m, used) -> assertEquals("SELECT 1", assertIs<PgFrontend.Query>(m).text); assertEquals(14, used) }
        decode("5800000004".hexToByteArray(), false)!!.let { (m, used) -> assertIs<PgFrontend.Terminate>(m); assertEquals(5, used) }
        val parse = PgFrame('P').cstring("s1").cstring("SELECT $1").int16(1).int32(23).bytes()
        decode(parse, false)!!.let { (m, _) -> assertIs<PgFrontend.Parse>(m).let { assertEquals("s1", it.name); assertEquals("SELECT $1", it.text); assertContentEquals(intArrayOf(23), it.types) } }
        val bind = PgFrame('B').cstring("").cstring("s1").int16(1).int16(1).int16(2).int32(4).int32(42).int32(-1).int16(1).int16(1).bytes()
        decode(bind, false)!!.let { (m, _) ->
            val b = assertIs<PgFrontend.Bind>(m)
            assertContentEquals(intArrayOf(1, 1), b.formats, "one format code applies to every parameter")
            assertContentEquals("0000002a".hexToByteArray(), b.values[0])
            assertNull(b.values[1])
            assertContentEquals(intArrayOf(1), b.resultFormats)
        }
        decode(PgFrame('D').byte('P'.code).cstring("").bytes(), false)!!.let { (m, _) -> assertEquals('P', assertIs<PgFrontend.Describe>(m).kind) }
        decode(PgFrame('E').cstring("p").int32(5).bytes(), false)!!.let { (m, _) -> assertEquals(5, assertIs<PgFrontend.Execute>(m).maxRows) }
        assertFailsWith<PgProtocolError> { decode("5100000002".hexToByteArray(), false) }
    }

    @Test fun backendFramesAreTheProtocolsBytes() {
        assertEquals("520000000800000000", PgBackend.authenticationOk().toHexString())
        assertEquals("5a0000000549", PgBackend.readyForQuery('I').toHexString())
        assertEquals("430000000853455400", PgBackend.commandComplete("SET").toHexString())
        assertEquals("4b0000000c000000070000002a", PgBackend.backendKeyData(7, 42).toHexString())
        assertEquals("5300000019636c69656e745f656e636f64696e67005554463800", PgBackend.parameterStatus("client_encoding", "UTF8").toHexString())
        // T: 1 field "n", table 0, attnum 0, int8 (20), typlen 8, typmod -1, text
        assertEquals("540000001a00016e00000000000000000000140008ffffffff0000", PgBackend.rowDescription(listOf(PgField("n", 20))).toHexString())
        assertEquals("440000000d000100000003313233", PgBackend.dataRow(listOf("123".encodeToByteArray())).toHexString())
        assertEquals("440000000a0001ffffffff", PgBackend.dataRow(listOf(null)).toHexString())
        assertEquals("4500000037534552524f5200564552524f520043343250303100" + "4d72656c6174696f6e2022782220646f6573206e6f742065786973740000",
            PgBackend.errorResponse("42P01", "relation \"x\" does not exist").toHexString())
        assertEquals("4e", PgBackend.NO.toHexString())
    }

    @Test fun cellsRenderAsPgTextOrBinary() {
        assertNull(PgBackend.text(null))
        assertEquals("t", PgBackend.text(true)!!.decodeToString())
        assertEquals("f", PgBackend.text(false)!!.decodeToString())
        assertEquals("2.5", PgBackend.text(2.5)!!.decodeToString())
        assertEquals("{1.0,2.0}", PgBackend.text(floatArrayOf(1f, 2f))!!.decodeToString(), "a vector is the real[] its OID declares")
        assertEquals("héllo", PgBackend.text("héllo")!!.decodeToString())
        val cell = confixDoc("""{"a": [1, "é"]}""").rootCell
        assertEquals("""{"a": [1, "é"]}""", PgBackend.text(cell)!!.decodeToString(), "jsonb text is the source's own bytes")
        assertEquals("000000000000002a", PgBackend.binary(42L, 20)!!.toHexString())
        assertEquals("0000002a", PgBackend.binary(42, 23)!!.toHexString())
        assertEquals("01", PgBackend.binary(true, 16)!!.toHexString())
        assertEquals("4004000000000000", PgBackend.binary(2.5, 701)!!.toHexString())
        // real[] binary: ndim 1, no nulls, element type 700, one element, lower bound 1, then length 4 + the float
        assertEquals("00000001" + "00000000" + "000002bc" + "00000001" + "00000001" + "00000004" + "3f800000", PgBackend.binary(floatArrayOf(1f), 1021)!!.toHexString())
        assertEquals("01" + "7b7d", PgBackend.binary(confixDoc("{}").rootCell, 3802)!!.toHexString(), "jsonb binary is version 1 and the text")
        assertEquals("abc", PgBackend.binary("abc", 25)!!.decodeToString())
        assertTrue(PgBackend.binaryCapable(25))
        assertFalse(PgBackend.binaryCapable(1700))
    }

    @Test fun malformedFramesAreProtocolViolations() {
        // a Bind whose parameter count is 0xFFFF, a Query with no terminator, a Parse whose type list outruns the frame
        assertFailsWith<PgProtocolError> { decode(PgFrame('B').cstring("").cstring("").int16(0).int16(-1).bytes(), false) }
        assertFailsWith<PgProtocolError> { decode("510000000a".hexToByteArray() + "SELECT".encodeToByteArray(), false) }
        assertFailsWith<PgProtocolError> { decode(PgFrame('P').cstring("").cstring("x").int16(3).int32(23).bytes(), false) }
        assertFailsWith<PgProtocolError> { decode("00002711".hexToByteArray() + ByteArray(4), true) }
    }
}
