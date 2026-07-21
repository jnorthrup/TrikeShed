package borg.trikeshed.wireproto

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.reactor.endpoint.ReactorEndpoint as ExtReactorEndpoint
import borg.trikeshed.reactor.endpoint.ReactorActionEnvelope as EndpointReactorActionEnvelope
import borg.trikeshed.litebike.tunnel.ReactorEndpoint as TunnelEndpoint
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class PathCursorTransport(
    private val delegate: TunnelEndpoint<EndpointReactorActionEnvelope, EndpointReactorActionEnvelope>
) : ExtReactorEndpoint {

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun invoke(action: EndpointReactorActionEnvelope, pathCursor: Cursor?): EndpointReactorActionEnvelope {
        var payloadToSend = action.payload

        if (pathCursor != null) {
            val rows = pathCursor.a
            val cols = if (rows > 0) pathCursor.b(0).a else 0
            val cursorStr = "$rows,$cols"
            val b64 = Base64.encode(cursorStr.encodeToByteArray())

            payloadToSend = b64.encodeToByteArray()
        }

        val envelopeToSend = action.copy(payload = payloadToSend)
        return delegate.invoke(envelopeToSend)
    }
}
