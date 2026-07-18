package borg.trikeshed.reactor

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals

class ReactorCodecTest {
    @Test
    fun testReactorEnvelopeRoundTrip() {
        val cap = Capability.Custom("custom", "test-token")
        val nonce = Nonce.RandomBytes()
        val subnet = Subnet.local
        val nuid = nuid(cap, nonce, subnet)
        val verb = "ping"
        val payload = "hello".encodeToByteArray()

        val action: ReactorAction = nuid j (verb j payload)

        val envelope = action.toConfixEnvelope()
        val restored = envelope.toReactorAction()

        assertEquals(action.a.a, restored.a.a) // capability
        // nonce bytes
        assertEquals(action.a.b.a.bytes.contentToString(), restored.a.b.a.bytes.contentToString())
        // subnet
        assertEquals(action.a.b.b.toString(), restored.a.b.b.toString())
        // verb
        assertEquals(action.b.a, restored.b.a)
        // payload
        assertEquals(action.b.b.contentToString(), restored.b.b.contentToString())
    }
}
