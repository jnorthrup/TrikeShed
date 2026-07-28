import borg.trikeshed.reactor.endpoint.ConfixEnvelopeCodec
import borg.trikeshed.reactor.endpoint.ReactorActionEnvelope
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.lcnc.reactor.ReactorAction

fun main() {
    val codec = ConfixEnvelopeCodec()
    val action = ReactorAction.Opened(nuid(Capability.Process("test"), Nonce.RandomBytes(), Subnet.core))
    
    // map action to envelope
    val env = ReactorActionEnvelope(
        action.nuid,
        "Opened",
        ByteArray(0)
    )
    val encoded = codec.encode(env)
    val decoded = codec.decode(encoded)
    
    println("Decoded NUID: ${decoded.nuid}")
}
