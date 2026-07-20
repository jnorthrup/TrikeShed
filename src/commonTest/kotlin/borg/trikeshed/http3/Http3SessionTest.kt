package borg.trikeshed.http3

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Http3SessionTest {
    @Test
    fun testSessionCreateAndReceive() = runTest {
        var sentDatagrams = mutableListOf<ByteArray>()
        val session = Http3Session(isServer = false) { datagram ->
            sentDatagrams.add(datagram)
        }
        
        val stream = session.createStream()
        assertEquals(0L, stream.id)
        
        stream.write(byteArrayOf(42, 43))
        assertEquals(1, sentDatagrams.size)
        
        val datagram = sentDatagrams[0]
        assertEquals(10, datagram.size) // 8 bytes ID + 2 bytes data
        
        var streamId = 0L
        for (i in 0..7) {
            streamId = (streamId shl 8) or (datagram[i].toLong() and 0xFF)
        }
        assertEquals(0L, streamId)
        assertEquals(42, datagram[8])
        assertEquals(43, datagram[9])
        
        // Test receiving data for an existing stream
        val receiveDatagram = ByteArray(10)
        receiveDatagram[7] = 0 // Stream 0
        receiveDatagram[8] = 99
        receiveDatagram[9] = 100
        
        session.receiveDatagram(receiveDatagram)
        val readData = stream.read(2)
        assertTrue(byteArrayOf(99, 100).contentEquals(readData))
        
        // Test receiving data for a new peer-initiated stream
        val peerDatagram = ByteArray(11)
        peerDatagram[7] = 1 // Stream 1
        peerDatagram[8] = 10
        peerDatagram[9] = 11
        peerDatagram[10] = 12
        
        session.receiveDatagram(peerDatagram)
        val peerStream = session.getStream(1L)
        assertNotNull(peerStream)
        
        val peerData = peerStream.read(3)
        assertTrue(byteArrayOf(10, 11, 12).contentEquals(peerData))
    }
}
