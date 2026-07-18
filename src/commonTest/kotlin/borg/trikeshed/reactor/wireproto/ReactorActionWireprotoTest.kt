package borg.trikeshed.reactor.wireproto

import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.isam.FieldSynapse
import borg.trikeshed.isam.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactorActionWireprotoTest {
    @Test
    fun `roundtrip Opened action`() {
        val action = ReactorAction.Opened
        val synapse = action.toFieldSynapse()
        assertEquals(Phase.INIT.int, synapse.phase)
        assertEquals(OPCODE_OPENED, synapse.opcode)

        val recovered = synapse.toReactorAction()
        assertTrue(recovered is ReactorAction.Opened)
    }

    @Test
    fun `roundtrip Activated action`() {
        val action = ReactorAction.Activated
        val synapse = action.toFieldSynapse()
        assertEquals(Phase.EXECUTE.int, synapse.phase)
        assertEquals(OPCODE_ACTIVATED, synapse.opcode)

        val recovered = synapse.toReactorAction()
        assertTrue(recovered is ReactorAction.Activated)
    }

    @Test
    fun `roundtrip Draining action`() {
        val action = ReactorAction.Draining
        val synapse = action.toFieldSynapse()
        assertEquals(Phase.COMPLETE.int, synapse.phase)
        assertEquals(OPCODE_DRAINING, synapse.opcode)

        val recovered = synapse.toReactorAction()
        assertTrue(recovered is ReactorAction.Draining)
    }

    @Test
    fun `roundtrip Closed action`() {
        val action = ReactorAction.Closed
        val synapse = action.toFieldSynapse()
        assertEquals(Phase.RECLAIM.int, synapse.phase)
        assertEquals(OPCODE_CLOSED, synapse.opcode)

        val recovered = synapse.toReactorAction()
        assertTrue(recovered is ReactorAction.Closed)
    }
}
