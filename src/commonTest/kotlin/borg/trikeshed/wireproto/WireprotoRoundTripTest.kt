package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class WireprotoRoundTripTest {

    @Test
    fun roundTripReactorAction() {
        val testNuid = nuid(Capability.Process("test"), Nonce.Restored(ByteArray(0)), Subnet.core)
        val action: ReactorAction = testNuid j ("test.verb" j "test_payload".encodeToByteArray())

        val bytes = WireprotoCodec.encode(action)
        val decoded = WireprotoCodec.decode(bytes)

        assertEquals(action.a, decoded.a)
        assertEquals(action.b.a, decoded.b.a)
        assertTrue(action.b.b.contentEquals(decoded.b.b))
    }
}
