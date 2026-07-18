package borg.trikeshed.reactor.wireproto

import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.isam.FieldSynapse
import borg.trikeshed.isam.Phase
import borg.trikeshed.isam.encodeWireProto
import borg.trikeshed.isam.decodeWireProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactorActionWireprotoIntegrationTest {
    @Test
    fun `roundtrip through wireproto encoded action`() {
        val action = ReactorAction.Opened

        // 1. Action -> Synapse
        val synapse = action.toFieldSynapse()

        // 2. Synapse -> Wire bytes
        val bytes = encodeWireProto(synapse)

        // 3. Wire bytes -> Synapse
        val recoveredSynapse = decodeWireProto(bytes)

        // 4. Synapse -> Action
        val recoveredAction = recoveredSynapse.toReactorAction()

        assertTrue(recoveredAction is ReactorAction.Opened)
        assertEquals(OPCODE_OPENED, recoveredSynapse.opcode)
    }
}
