package borg.trikeshed.sctp

import borg.trikeshed.couch.isam.Crc32C
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SctpPacketTest {
    /** RFC 3720 §B.4: CRC32c of the ASCII digits 1 to 9. */
    @Test
    fun crc32cMatchesTheRfcVector() {
        val digits = "123456789".encodeToByteArray()
        assertEquals(0xE3069283.toInt(), Crc32C.compute(digits, 0, digits.size))
    }

    @Test
    fun connectionNumberCarriesBothTags() {
        val n = ConnectionNumber(local = 0x7a5e1d03, peer = -0x10ff0001)
        assertEquals(0x7a5e1d03, n.localTag)
        assertEquals(-0x10ff0001, n.peerTag)
    }

    @Test
    fun writerAndReadersAgreeOnEveryField() {
        val w = SctpPacketWriter(ByteArray(1452))
        val cookie = "cookie".encodeToByteArray()           // 6 bytes: the parameter pads to 12
        val body = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray() // 18 bytes: the chunk pads to 36
        w.begin(sourcePort = 9899, destinationPort = 9900, tag = 0x0badcafe)
        w.init(ChunkType.INIT_ACK, tag = 0x13572468, rwnd = 1 shl 20, outbound = 10, inbound = 10, tsn = -7, cookie = cookie)
        w.data(tsn = -7, stream = 3, ssn = 65535, ppid = 0x48545831, flags = DataFlags.WHOLE, payload = body, offset = 0, count = body.size)
        w.sack(cumulative = 41, rwnd = 65536, gaps = _a[2, 3, 5, 9], gapCount = 2)
        val length = w.seal()
        val p = w.buffer

        assertTrue(sctpChecksumValid(p, length))
        assertEquals(9899, p.sourcePort())
        assertEquals(9900, p.destinationPort())
        assertEquals(0x0badcafe, p.verificationTag())
        assertEquals(length % 4, 0)

        val types = mutableListOf<Int>()
        p.forEachChunk(length) { type, flags, at, chunkLength ->
            types += type
            when (type) {
                ChunkType.INIT_ACK -> {
                    assertEquals(0x13572468, p.initTag(at))
                    assertEquals(1 shl 20, p.initRwnd(at))
                    assertEquals(10, p.initOutboundStreams(at))
                    assertEquals(10, p.initInboundStreams(at))
                    assertEquals(-7, p.initTsn(at))
                    val found = p.stateCookie(at, chunkLength)
                    val from = (found ushr 32).toInt()
                    assertContentEquals(cookie, p.copyOfRange(from, from + found.toInt()))
                }
                ChunkType.DATA -> {
                    assertEquals(DataFlags.WHOLE, flags)
                    assertEquals(-7, p.dataTsn(at))
                    assertEquals(3, p.dataStream(at))
                    assertEquals(65535, p.dataSsn(at))
                    assertEquals(0x48545831, p.dataPpid(at))
                    assertContentEquals(body, p.copyOfRange(at + DATA_HEADER, at + chunkLength))
                }
                ChunkType.SACK -> {
                    assertEquals(41, p.sackCumulative(at))
                    assertEquals(65536, p.sackRwnd(at))
                    assertEquals(2, p.sackGaps(at))
                    assertEquals(0, p.sackDuplicates(at))
                    assertEquals(_l[2, 3, 5, 9], _l[p.sackGapStart(at, 0), p.sackGapEnd(at, 0), p.sackGapStart(at, 1), p.sackGapEnd(at, 1)])
                }
            }
        }
        assertEquals(_l[ChunkType.INIT_ACK, ChunkType.DATA, ChunkType.SACK], types)
    }

    @Test
    fun aFlippedBitFailsTheChecksum() {
        val w = SctpPacketWriter(ByteArray(64))
        w.begin(1, 2, 3)
        w.bare(ChunkType.COOKIE_ACK)
        val length = w.seal()
        assertTrue(sctpChecksumValid(w.buffer, length))
        w.buffer[13] = (w.buffer[13].toInt() xor 1).toByte()
        assertFalse(sctpChecksumValid(w.buffer, length))
    }

    @Test
    fun aTruncatedChunkEndsTheWalk() {
        val w = SctpPacketWriter(ByteArray(64))
        w.begin(1, 2, 3)
        w.shutdown(cumulative = 9)
        w.bare(ChunkType.SHUTDOWN_COMPLETE)
        val length = w.seal()
        var seen = 0
        w.buffer.forEachChunk(length - 2) { _, _, _, _ -> seen++ }
        assertEquals(1, seen)
    }
}
