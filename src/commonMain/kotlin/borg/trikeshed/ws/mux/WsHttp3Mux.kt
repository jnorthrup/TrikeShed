package borg.trikeshed.ws.mux

import borg.trikeshed.http3.Http3Session
import borg.trikeshed.ws.WebSocketFrame
import borg.trikeshed.ws.FrameHeader

class WsHttp3Mux(
    private val session: Http3Session
) {
    suspend fun sendFrame(streamId: Long, frameData: ByteArray, opcode: WebSocketFrame.OpCode = WebSocketFrame.OpCode.TEXT, fin: Boolean = true) {
        val stream = session.getStream(streamId) ?: throw IllegalArgumentException("Stream not found")
        
        val frame = WebSocketFrame.buildFrame(
            opcode = opcode,
            fin = fin,
            masked = false, // Client-to-server frames should be masked, this assumes server sending to client
            payload = frameData
        )
        
        stream.write(frame)
    }

    suspend fun receiveFrameData(streamId: Long): Pair<WebSocketFrame.OpCode, ByteArray>? {
        val stream = session.getStream(streamId) ?: return null
        
        // Ensure we properly read the bytes
        val headerPart = stream.readExactly(2)
        if (headerPart.isEmpty()) return null
        
        val b0 = headerPart[0].toInt() and 0xFF
        val opcode = WebSocketFrame.OpCode.fromCode(b0 and 0x0F)
        
        val b1 = headerPart[1].toInt() and 0xFF
        val masked = (b1 and 0x80) != 0
        val length7 = b1 and 0x7F
        
        val payloadLength = when {
            length7 < 126 -> length7.toLong()
            length7 == 126 -> {
                val lenBytes = stream.readExactly(2)
                ((lenBytes[0].toInt() and 0xFF) shl 8 or (lenBytes[1].toInt() and 0xFF)).toLong()
            }
            else -> { // 127
                val lenBytes = stream.readExactly(8)
                var len = 0L
                for (i in 0..7) { len = (len shl 8) or (lenBytes[i].toInt() and 0xFF).toLong() }
                len
            }
        }
        
        val maskingKey = if (masked) stream.readExactly(4) else null
        val payload = stream.readExactly(payloadLength.toInt())
        
        if (masked && maskingKey != null) {
            WebSocketFrame.applyMask(maskingKey, payload)
        }
        
        return Pair(opcode, payload)
    }
}
