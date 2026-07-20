package borg.trikeshed.http3

import borg.trikeshed.mplex.MplexStream
import borg.trikeshed.mplex.SessionWindow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel

class Http3Session(
    private val isServer: Boolean = true,
    private val onSendDatagram: suspend (ByteArray) -> Unit
) {
    val sessionWindow = SessionWindow(1048576) // 1MB session window
    private val streams = mutableMapOf<Long, MplexStream>()
    private val mutex = Mutex()
    // Client initiates with 0, 4, 8... Server initiates with 1, 5, 9...
    private var nextStreamId = if (isServer) 1L else 0L

    suspend fun createStream(): MplexStream = mutex.withLock {
        val id = nextStreamId
        nextStreamId += 4 // Bi-directional streams increment by 4
        
        val stream = MplexStream(id, sessionWindow) { streamId, data ->
            // In a real QUIC implementation, this would format a STREAM frame
            // For now, we simulate sending a datagram containing the stream data
            val frame = ByteArray(8 + data.size)
            // Encode streamId (8 bytes) + data
            for (i in 0..7) {
                frame[i] = (streamId ushr (8 * (7 - i))).toByte()
            }
            data.copyInto(frame, 8)
            onSendDatagram(frame)
        }
        streams[id] = stream
        stream
    }
    
    suspend fun getStream(id: Long): MplexStream? = mutex.withLock {
        streams[id]
    }

    suspend fun receiveDatagram(datagram: ByteArray) {
        // In a real QUIC implementation, this would parse QUIC frames
        // For our multiplexing simulation, we extract the stream ID and route the data
        if (datagram.size < 8) return
        
        var streamId = 0L
        for (i in 0..7) {
            streamId = (streamId shl 8) or (datagram[i].toLong() and 0xFF)
        }
        
        val data = datagram.copyOfRange(8, datagram.size)
        val stream = getStream(streamId)
        
        if (stream == null) {
            // Unidirectional stream from peer, auto-create
            mutex.withLock {
                if (!streams.containsKey(streamId)) {
                    val newStream = MplexStream(streamId, sessionWindow) { sId, d ->
                        val frame = ByteArray(8 + d.size)
                        for (i in 0..7) {
                            frame[i] = (sId ushr (8 * (7 - i))).toByte()
                        }
                        d.copyInto(frame, 8)
                        onSendDatagram(frame)
                    }
                    streams[streamId] = newStream
                }
            }
            getStream(streamId)?.receiveData(data)
        } else {
            stream.receiveData(data)
        }
    }
    
    suspend fun close() {
        mutex.withLock {
            for (stream in streams.values) {
                stream.close()
            }
            streams.clear()
        }
    }
}
