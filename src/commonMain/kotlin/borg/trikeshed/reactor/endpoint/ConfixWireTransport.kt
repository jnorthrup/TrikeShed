package borg.trikeshed.reactor.endpoint

import borg.trikeshed.cursor.Cursor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A ReactorEndpoint implementation that transports ReactorActionEnvelope
 * instances over a wire (byte stream) via the provided suspend functions.
 */
class ConfixWireTransport(
    private val sendBytes: suspend (ByteArray) -> Unit,
    private val receiveBytes: suspend () -> ByteArray,
    private val codec: ConfixEnvelopeCodec = ConfixEnvelopeCodec()
) : ReactorEndpoint {
    
    // We use a mutex to ensure that invoke (send + receive pair) is atomic
    // over the underlying transport, preventing interleaved requests and responses
    // if invoked concurrently from multiple coroutines.
    private val invokeMutex = Mutex()

    override suspend fun invoke(action: ReactorActionEnvelope, pathCursor: Cursor?): ReactorActionEnvelope {
        val encodedAction = codec.encode(action)
        
        invokeMutex.withLock {
            sendBytes(encodedAction)
            
            // Loop until we get a valid frame.
            // (A full implementation would need frame buffering and parsing over
            // a continuous stream, but this matches the provided `receiveBytes` which
            // we assume yields exactly one framed message at a time.)
            val responseBytes = receiveBytes()
            return codec.decode(responseBytes)
        }
    }
}
