package borg.trikeshed.wireproto

import borg.trikeshed.litebike.tunnel.ReactorEndpoint

class ConfixWorker(private val encoder: ActionEncoder, private val decoder: ActionDecoder) : ReactorEndpoint<ReactorActionEnvelope, ReactorActionEnvelope> {
    override suspend fun invoke(action: ReactorActionEnvelope): ReactorActionEnvelope {
        val bytes = encoder.encode(action)
        val decoded = decoder.decode(bytes)
        return decoded
    }
}
