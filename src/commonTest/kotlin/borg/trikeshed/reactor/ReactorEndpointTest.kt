package borg.trikeshed.reactor

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReactorEndpointTest {
    @Test
    fun testReactorActionResult() = runTest {
        val cap = Capability.Custom("test", "token")
        val nonce = Nonce.RandomBytes()
        val subnet = Subnet.local
        val nuid = nuid(cap, nonce, subnet)
        val verb = "ping"
        val payload = "hello".encodeToByteArray()

        val action: ReactorAction = nuid j (verb j payload)

        val endpoint = object : ReactorEndpoint {
            override suspend fun invoke(action: ReactorAction): ReactorResult {
                val reqVerb = action.b.a
                val reqPayload = action.b.b
                val resPayload = (reqPayload.decodeToString() + " world").encodeToByteArray()
                return action.a j ("pong" j resPayload)
            }
        }

        val result = endpoint.invoke(action)

        assertEquals(nuid, result.a)
        assertEquals("pong", result.b.a)
        assertEquals("hello world", result.b.b.decodeToString())
    }
}
