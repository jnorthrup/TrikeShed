package borg.trikeshed.ws.mux

import borg.trikeshed.http3.Http3Session
import borg.trikeshed.ws.WebSocketFrame
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WsHttp3MuxTest {
    @Test
    fun testWsHttp3Mux() = runTest {
        val session1 = Http3Session(isServer = false) { datagram -> 
            // In a real test we'd route this datagram to session2
        }
        val session2 = Http3Session(isServer = true) { _ -> }
        
        val mux1 = WsHttp3Mux(session1)
        val mux2 = WsHttp3Mux(session2)
        
        val stream = session1.createStream()
        
        val frameData = byteArrayOf(104, 101, 108, 108, 111) // "hello"
        
        launch {
            mux1.sendFrame(stream.id, frameData, WebSocketFrame.OpCode.TEXT)
        }
        
        // Build a raw websocket frame for simulation
        // Mask it to match what we expect to receive on the server side
        val rawWsFrame = WebSocketFrame.buildFrame(
            opcode = WebSocketFrame.OpCode.TEXT,
            fin = true,
            masked = true,
            maskingKey = byteArrayOf(1, 2, 3, 4),
            payload = frameData
        )
        
        // Simulate sending datagram to session2
        val simulatedDatagram = ByteArray(8 + rawWsFrame.size)
        // Stream ID 0
        for (i in 0..7) simulatedDatagram[i] = 0
        
        rawWsFrame.copyInto(simulatedDatagram, 8)
        
        session2.receiveDatagram(simulatedDatagram)
        
        val receivedPair = mux2.receiveFrameData(0L)
        assertNotNull(receivedPair)
        assertEquals(WebSocketFrame.OpCode.TEXT, receivedPair.first)
        assertTrue(frameData.contentEquals(receivedPair.second))
    }
}
