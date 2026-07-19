package borg.trikeshed.reactor.wireproto

import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.cursor.Cursor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ReactorEndpointTest {
    @Test
    fun `roundtrip a cursor through a wireproto-encoded action`() = runTest {
        val endpoint = LoopbackReactorEndpoint()

        val action = ReactorAction.Opened

        endpoint.sendAction(action, null)

        val (recoveredAction, recoveredCursor) = endpoint.receiveAction()

        assertTrue(recoveredAction is ReactorAction.Opened)
        assertTrue(recoveredCursor == null)
    }
}
