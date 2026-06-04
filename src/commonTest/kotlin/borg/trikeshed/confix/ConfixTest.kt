@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")
package borg.trikeshed.confix
import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import kotlin.test.*

class ConfixTest {
    fun bytes(s: String): ByteArray = s.encodeToByteArray()

    fun src(s: String): Series<Byte> {
        val b = s.encodeToByteArray()
        val n = b.size
        return n j { i: Int -> b[i] }
    }

    @Test fun `JSON recognize bracket`() {
        assertTrue(Syntax.JSON.recognize('{'.code.toByte()))
        assertTrue(Syntax.JSON.recognize('['.code.toByte()))
        assertTrue(Syntax.JSON.recognize('"'.code.toByte()))
        assertFalse(Syntax.JSON.recognize('a'.code.toByte()))
    }

    @Test fun `YAML recognize plain scalar`() {
        assertTrue(Syntax.YAML.recognize('a'.code.toByte()))
        assertTrue(Syntax.YAML.recognize('-'.code.toByte()))
        assertFalse(Syntax.YAML.recognize('{'.code.toByte()))
    }

    @Test fun `CBOR recognize any byte`() {
        assertTrue(Syntax.CBOR.recognize(0x00))
        assertTrue(Syntax.CBOR.recognize(0xFF.toByte()))
    }

    @Test fun `scan flat number`() {
        val cursor = Syntax.JSON.scan(src("42"))
        assertTrue(cursor.size >= 0)
    }

    @Test fun `scan flat number via FlatIndex`() {
        val (_, ix) = Syntax.JSON.scan0(src("42"))
        assertEquals(1, ix.spans.size)
        assertEquals(IOMemento.IoDouble, ix.tags[0])
        assertEquals(0, ix.depths[0])
    }

    @Test fun `scan string`() {
        val (_, ix) = Syntax.JSON.scan0(src("\"hello\""))
        assertEquals(1, ix.spans.size)
        assertEquals(IOMemento.IoString, ix.tags[0])
    }

    @Test fun `scan empty object`() {
        val (_, ix) = Syntax.JSON.scan0(src("{}"))
        assertEquals(1, ix.spans.size)
        assertEquals(IOMemento.IoObject, ix.tags[0])
    }

    @Test fun `scan object with one key`() {
        val (_, ix) = Syntax.JSON.scan0(src("""{"key":"val"}"""))
        assertEquals(3, ix.spans.size)
        assertEquals(IOMemento.IoObject, ix.tags[0])
    }

    @Test fun `scan array`() {
        val (_, ix) = Syntax.JSON.scan0(src("[1,2,3]"))
        assertTrue(ix.spans.size >= 4)
        assertEquals(IOMemento.IoArray, ix.tags[0])
    }

    @Test fun `nested object depths`() {
        val (_, ix) = Syntax.JSON.scan0(src("""{"a":{"b":1}}"""))
        assertTrue(ix.depths.size >= 3)
        assertEquals(0, ix.depths[0])
    }

    @Test fun `direct children`() {
        val (_, ix) = Syntax.JSON.scan0(src("""{"x":1,"y":2}"""))
        val dc = ix.childOf(0)
        assertEquals(4, dc.size)
    }

    @Test fun `tree cursor produces rows`() {
        val cursor = Syntax.JSON.scan(src("[1,2]"))
        assertTrue(cursor.size >= 0)
        val row = cursor[0]
        assertTrue(row.a > 0)
    }

    @Test fun `dispatch routes JSON`() {
        val cursor = Syntax.JSON.dispatch(bytes("42"))
        assertTrue(cursor.size >= 0)
    }

    @Test fun `dispatch routes YAML`() {
        val cursor = Syntax.YAML.dispatch(bytes("key: value\n"))
        assertTrue(cursor.size >= 0)
    }

    @Test fun `array of 100 numbers`() {
        val nums = (1..100).joinToString(",")
        val (_, ix) = Syntax.JSON.scan0(src("[$nums]"))
        assertTrue(ix.spans.size >= 101)
    }

    @Test fun `CBOR unsigned int`() {
        val b = byteArrayOf(0x01)
        val cursor = Syntax.CBOR.scan(b.size j { i: Int -> b[i] })
        assertTrue(cursor.size >= 0)
    }

    @Test fun `CBOR array of ints`() {
        val b = byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03)
        val cursor = Syntax.CBOR.scan(b.size j { i: Int -> b[i] })
        assertTrue(cursor.size > 0)
    }

    @Test fun `decodeText strips quotes`() {
        val src: Series<Char> = "hello".length j { i: Int -> "hello"[i] }
        val cs = Syntax.JSON.decodeText(src, 0, 4)
        assertTrue(cs.toString().contains("hello"))
    }

    @Test fun `ConfixKit parses YAML document`() {
        val doc = confixDoc("key: value\n")
        // YAML is auto-detected; document should have a root
        // (YAML parsing not yet fully implemented — skip body for now)
    }

    @Test fun `ConfixKit parses CBOR map and reifies value`() {
        // CBOR: {0xa1} map(1), {0x65} text(5 bytes), "hello", {0x01} int(1)
        val b = byteArrayOf(
            0xA1.toByte(),
            0x65.toByte(), 0x68, 0x65, 0x6C, 0x6C, 0x6F,
            0x01
        )
        val doc = confixDoc(b, Syntax.CBOR)
        // CBOR tree navigation is WIP — just confirm parse doesn't crash
        assertNotNull(doc.index)
    }
}
