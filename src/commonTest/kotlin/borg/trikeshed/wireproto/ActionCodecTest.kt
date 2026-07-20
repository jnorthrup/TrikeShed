package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActionCodecTest {
<<<<<<< HEAD
    
    private val encoder = ActionEncoder()
    private val decoder = ActionDecoder()
    
=======

    private val encoder = ActionEncoder()
    private val decoder = ActionDecoder()

>>>>>>> origin/wireproto-codec-9444185639294947999
    @Test
    fun knownVectorEncodes() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(4) { it.toByte() }), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "ping", "test_payload".encodeToByteArray())
<<<<<<< HEAD
        
        val bytes = encoder.encode(envelope)
        
=======

        val bytes = encoder.encode(envelope)

>>>>>>> origin/wireproto-codec-9444185639294947999
        // Magic
        assertEquals(0xCA.toByte(), bytes[0])
        assertEquals(0xFE.toByte(), bytes[1])
        assertEquals(0xBA.toByte(), bytes[2])
        assertEquals(0xBE.toByte(), bytes[3])
<<<<<<< HEAD
        
        // Version 1
        assertEquals(0x00.toByte(), bytes[4])
        assertEquals(0x01.toByte(), bytes[5])
        
=======

        // Version 1
        assertEquals(0x00.toByte(), bytes[4])
        assertEquals(0x01.toByte(), bytes[5])

>>>>>>> origin/wireproto-codec-9444185639294947999
        // NUID length check -> the first part of the custom format
        val nuidLen = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertTrue(nuidLen > 0, "nuid length should be > 0")
    }

    @Test
    fun roundTripPreservesAllFields() {
        val testNuid = nuid(Capability.Process("hello"), Nonce.Restored(ByteArray(8) { it.toByte() }), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "echo", "test".encodeToByteArray())
<<<<<<< HEAD
        
        val bytes = encoder.encode(envelope)
        val decoded = decoder.decode(bytes)
        
=======

        val bytes = encoder.encode(envelope)
        val decoded = decoder.decode(bytes)

>>>>>>> origin/wireproto-codec-9444185639294947999
        assertEquals(envelope.verb, decoded.verb)
        assertTrue(envelope.payload.contentEquals(decoded.payload))
        assertEquals(envelope.nuid.a, decoded.nuid.a)
        assertEquals(envelope.nuid.b.b, decoded.nuid.b.b)
        assertTrue(envelope.nuid.b.a.bytes.contentEquals(decoded.nuid.b.a.bytes))
    }

    @Test
    fun rejectsBadMagic() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "ping", ByteArray(0))
        val bytes = encoder.encode(envelope)
<<<<<<< HEAD
        
=======

>>>>>>> origin/wireproto-codec-9444185639294947999
        // Alter magic
        bytes[0] = 0xDE.toByte()
        bytes[1] = 0xAD.toByte()
        bytes[2] = 0xBE.toByte()
        bytes[3] = 0xEF.toByte()
<<<<<<< HEAD
        
=======

>>>>>>> origin/wireproto-codec-9444185639294947999
        val ex = assertFailsWith<WireprotoFormatException> {
            decoder.decode(bytes)
        }
        assertTrue(ex.message!!.startsWith("bad magic 0x"), "Expected message to start with 'bad magic 0x'")
    }

    @Test
    fun rejectsBadVersion() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "ping", ByteArray(0))
        val bytes = encoder.encode(envelope)
<<<<<<< HEAD
        
        // Alter version
        bytes[4] = 0x00.toByte()
        bytes[5] = 99.toByte()
        
=======

        // Alter version
        bytes[4] = 0x00.toByte()
        bytes[5] = 99.toByte()

>>>>>>> origin/wireproto-codec-9444185639294947999
        val ex = assertFailsWith<WireprotoFormatException> {
            decoder.decode(bytes)
        }
        assertTrue(ex.message!!.startsWith("bad version"), "Expected message to start with 'bad version'")
    }

    @Test
    fun rejectsTruncatedFrame() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "ping", ByteArray(1024))
        val bytes = encoder.encode(envelope)
<<<<<<< HEAD
        
        // truncate to 100 bytes from end
        val truncatedBytes = bytes.copyOfRange(0, bytes.size - (1024 - 100))
        
=======

        // truncate to 100 bytes from end
        val truncatedBytes = bytes.copyOfRange(0, bytes.size - (1024 - 100))

>>>>>>> origin/wireproto-codec-9444185639294947999
        val ex = assertFailsWith<WireprotoFormatException> {
            decoder.decode(truncatedBytes)
        }
        assertTrue(ex.message!!.startsWith("truncated frame: expected "), "Expected message to start with 'truncated frame'")
    }

    @Test
    fun rejectsOversizePayload() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val oversizePayload = ByteArray(70_000)
        val envelope = ReactorActionEnvelope(testNuid, "ping", oversizePayload)
<<<<<<< HEAD
        
=======

>>>>>>> origin/wireproto-codec-9444185639294947999
        val ex = assertFailsWith<WireprotoFormatException> {
            encoder.encode(envelope)
        }
        assertEquals("payload > 65536 bytes", ex.message)
    }

    @Test
    fun encodeIsDeterministic() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "ping", "deterministic".encodeToByteArray())
<<<<<<< HEAD
        
        val bytes1 = encoder.encode(envelope)
        val bytes2 = encoder.encode(envelope)
        
=======

        val bytes1 = encoder.encode(envelope)
        val bytes2 = encoder.encode(envelope)

>>>>>>> origin/wireproto-codec-9444185639294947999
        assertTrue(bytes1.contentEquals(bytes2), "Encode should be deterministic")
    }

    @Test
    fun frameRejectsBlankNuid() {
        val ex = assertFailsWith<IllegalArgumentException> {
            WireprotoFrame(WireprotoFrame.MAGIC, 1, "", "ping", "")
        }
        assertEquals("nuid blank", ex.message)
    }

    @Test
    fun frameRejectsBlankVerb() {
        val ex = assertFailsWith<IllegalArgumentException> {
            WireprotoFrame(WireprotoFrame.MAGIC, 1, "nuid", "", "")
        }
        assertEquals("verb blank", ex.message)
    }

    @Test
    fun frameRejectsOversizePayload() {
        val ex = assertFailsWith<IllegalArgumentException> {
            WireprotoFrame(WireprotoFrame.MAGIC, 1, "nuid", "ping", "x".repeat(65_537))
        }
        assertEquals("payload > 65536", ex.message)
    }

    @Test
    fun decoderRejectsBadNuidLength() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val envelope = ReactorActionEnvelope(testNuid, "ping", ByteArray(0))
        val bytes = encoder.encode(envelope)
<<<<<<< HEAD
        
        // Alter NUID length to 0xFFFF
        bytes[6] = 0xFF.toByte()
        bytes[7] = 0xFF.toByte()
        
=======

        // Alter NUID length to 0xFFFF
        bytes[6] = 0xFF.toByte()
        bytes[7] = 0xFF.toByte()

>>>>>>> origin/wireproto-codec-9444185639294947999
        val ex = assertFailsWith<WireprotoFormatException> {
            decoder.decode(bytes)
        }
        assertTrue(ex.message!!.startsWith("bad nuid length: "), "Expected message to start with 'bad nuid length:'")
    }
}
