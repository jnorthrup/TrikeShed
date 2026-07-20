package borg.trikeshed.reactor.endpoint

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfixEnvelopeCodecTest {

    private val testNuid = nuid(
        Capability.Process("test"),
        Nonce.Restored(ByteArray(16) { it.toByte() }),
        Subnet.parse("test.local")
    )

    // verifies: encode → decode → nuid, verb, payload equal to the input
    @Test
    fun roundTripsSimplePing() {
        val codec = ConfixEnvelopeCodec()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf(1, 2, 3))
        val encoded = codec.encode(envelope)
        val decoded = codec.decode(encoded)
        assertEquals(envelope, decoded)
    }

    // verifies: set maxPayloadBytes = 16, build envelope with 32-byte payload → expect IllegalArgumentException
    @Test
    fun rejectsPayloadOverMax() {
        val codec = ConfixEnvelopeCodec(ReactorEndpointConfig(maxPayloadBytes = 16))
        val envelope = ReactorActionEnvelope(testNuid, "ping", ByteArray(32))
        assertFailsWith<IllegalArgumentException> {
            codec.encode(envelope)
        }
    }

    // verifies: verb "delete" with default config (only ping/pong/echo/noop) → expect IllegalArgumentException
    @Test
    fun rejectsUnknownVerb() {
        val codec = ConfixEnvelopeCodec()
        val envelope = ReactorActionEnvelope(testNuid, "delete", byteArrayOf())
        assertFailsWith<IllegalArgumentException> {
            codec.encode(envelope)
        }
    }

    // verifies: pass a 4-byte array to decode → expect IllegalArgumentException("frame too short: 4")
    @Test
    fun rejectsShortFrame() {
        val codec = ConfixEnvelopeCodec()
        val ex = assertFailsWith<IllegalArgumentException> {
            codec.decode(ByteArray(4))
        }
        assertTrue(ex.message!!.contains("frame too short: 4"))
    }

    // verifies: header bytes with magic 0xDEADBEEF → expect IllegalArgumentException mentioning "bad magic"
    @Test
    fun rejectsBadMagic() {
        val codec = ConfixEnvelopeCodec()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf())
        val encoded = codec.encode(envelope)
        // corrupt magic
        encoded[0] = 0xDE.toByte()
        encoded[1] = 0xAD.toByte()
        encoded[2] = 0xBE.toByte()
        encoded[3] = 0xEF.toByte()
        val ex = assertFailsWith<IllegalArgumentException> {
            codec.decode(encoded)
        }
        assertTrue(ex.message!!.contains("bad magic"))
    }

    // verifies: header bytes with version 99 → expect IllegalArgumentException mentioning "bad version"
    @Test
    fun rejectsBadVersion() {
        val codec = ConfixEnvelopeCodec()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf())
        val encoded = codec.encode(envelope)
        // corrupt version
        encoded[4] = 0
        encoded[5] = 99
        val ex = assertFailsWith<IllegalArgumentException> {
            codec.decode(encoded)
        }
        assertTrue(ex.message!!.contains("bad version"))
    }

    // verifies: encode then slice first 8 bytes; assert bytes 0..3 = MAGIC big-endian; bytes 4..5 = VERSION big-endian; bytes 6..7 = payload.length big-endian
    @Test
    fun frameHeaderIsExactly8Bytes() {
        val codec = ConfixEnvelopeCodec()
        val envelope = ReactorActionEnvelope(testNuid, "ping", ByteArray(42))
        val encoded = codec.encode(envelope)
        assertEquals(0xCA.toByte(), encoded[0])
        assertEquals(0xFE.toByte(), encoded[1])
        assertEquals(0xFA.toByte(), encoded[2])
        assertEquals(0xCE.toByte(), encoded[3])
        assertEquals(0, encoded[4])
        assertEquals(1, encoded[5])
        assertEquals(0, encoded[6])
        assertEquals(42, encoded[7])
    }

    // verifies: encode the same envelope twice → identical bytes
    @Test
    fun encodeIsDeterministic() {
        val codec = ConfixEnvelopeCodec()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf(5, 6, 7))
        val encoded1 = codec.encode(envelope)
        val encoded2 = codec.encode(envelope)
        assertTrue(encoded1.contentEquals(encoded2))
    }

    // verifies: ReactorEndpointConfig(maxPayloadBytes = 0) → throws from init block
    @Test
    fun rejectsNegativeMaxPayload() {
        assertFailsWith<IllegalArgumentException> {
            ReactorEndpointConfig(maxPayloadBytes = 0)
        }
    }

    // verifies: maxPayloadBytes = 33_554_433 (over 1..16_777_216) → throws from init
    @Test
    fun rejectsExcessiveMaxPayload() {
        assertFailsWith<IllegalArgumentException> {
            ReactorEndpointConfig(maxPayloadBytes = 33_554_433)
        }
    }
}
