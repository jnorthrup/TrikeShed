package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CursorRoundTripTest {

    private val encoder = ActionEncoder()
    private val decoder = ActionDecoder()

    private fun createDummyCursor(rows: Int, cols: Int): Cursor {
        val metas = cols j { c: Int ->
            val meta: () -> ColumnMeta = { ColumnMeta("col_$c", IOMemento.IoInt) }
            meta
        }
        
        return rows j { r: Int ->
            val row: RowVec = cols j { c: Int ->
                (r * cols + c) j metas.b(c)
            }
            row
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun serializeCursor(cursor: Cursor): String {
        // Just a dummy serialization: "rows,cols"
        val str = "${cursor.a},${if (cursor.a > 0) cursor.b(0).a else 0}"
        return Base64.encode(str.encodeToByteArray())
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun deserializeCursor(b64: String): Cursor {
        val str = Base64.decode(b64).decodeToString()
        val parts = str.split(",")
        val rows = parts[0].toInt()
        val cols = parts[1].toInt()
        return createDummyCursor(rows, cols)
    }

    @Test
    fun cursorRoundTrip3x4() {
        val cursor = createDummyCursor(4, 3)
        val payloadStr = serializeCursor(cursor)
        
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "cursor.put", payloadStr.encodeToByteArray())
        
        val bytes = encoder.encode(envelope)
        val decoded = decoder.decode(bytes)
        
        val decodedPayloadStr = decoded.payload.decodeToString()
        val decodedCursor = deserializeCursor(decodedPayloadStr)
        
        assertEquals(4, decodedCursor.a)
        val cols = if (decodedCursor.a > 0) decodedCursor.b(0).a else 0
        assertEquals(3, cols)
    }

    @Test
    fun cursorEmptyRoundTrip() {
        val cursor = createDummyCursor(0, 0)
        val payloadStr = serializeCursor(cursor)
        
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "cursor.put", payloadStr.encodeToByteArray())
        
        val bytes = encoder.encode(envelope)
        val decoded = decoder.decode(bytes)
        
        val decodedPayloadStr = decoded.payload.decodeToString()
        val decodedCursor = deserializeCursor(decodedPayloadStr)
        
        assertEquals(0, decodedCursor.a)
    }

    @Test
    fun cursorSingleCellRoundTrip() {
        val cursor = createDummyCursor(1, 1)
        val payloadStr = serializeCursor(cursor)
        
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "cursor.put", payloadStr.encodeToByteArray())
        
        val bytes = encoder.encode(envelope)
        val decoded = decoder.decode(bytes)
        
        val decodedPayloadStr = decoded.payload.decodeToString()
        val decodedCursor = deserializeCursor(decodedPayloadStr)
        
        assertEquals(1, decodedCursor.a)
        val cols = if (decodedCursor.a > 0) decodedCursor.b(0).a else 0
        assertEquals(1, cols)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun cursorPayloadBase64Encoded() {
        val cursor = createDummyCursor(2, 2)
        val payloadStr = serializeCursor(cursor)
        
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "cursor.put", payloadStr.encodeToByteArray())
        
        val bytes = encoder.encode(envelope)
        val decoded = decoder.decode(bytes)
        
        val decodedPayloadStr = decoded.payload.decodeToString()
        
        // Assert it is valid base64
        val decodedBytes = Base64.decode(decodedPayloadStr)
        val rawStr = decodedBytes.decodeToString()
        
        assertEquals("2,2", rawStr)
    }
}
