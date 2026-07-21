package borg.trikeshed.wireproto

import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.lib.j

object WireprotoCodec {
    private val encoder = ActionEncoder()
    private val decoder = ActionDecoder()

    fun encode(action: ReactorAction): ByteArray {
        val nuid = action.a
        val verb = action.b.a
        val payload = action.b.b
        val envelope = ReactorActionEnvelope(nuid, verb, payload)
        return encoder.encode(envelope)
    }

    fun decode(bytes: ByteArray): ReactorAction {
        val envelope = decoder.decode(bytes)
        return envelope.nuid j (envelope.verb j envelope.payload)
    }
}
