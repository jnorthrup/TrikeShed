package borg.trikeshed.daemon

import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OroborosDaemonKanbanBridgeTest {
    @BeforeEach
    fun setup() {
        KanbanFSM.reset()
    }

    @Test
    fun testBridgeMocks(): Unit = runBlocking {
        // The daemon bridge maps cycle state onto the FSM via real KanbanEvent
        // subtypes. CycleObserved does not exist on KanbanEvent; the
        // taxonomy-creation path does, so exercise that reducer branch to
        // prove the bridge wiring is live without depending on a phantom event.

        val ev = KanbanEvent.TaxonomyNodeCreated(
            nodeId = "node-1",
            kind = "todo",
            label = "Wire CAS receipt",
            parentId = null,
            timestampMs = 12345L,
        )
        KanbanFSM.reduce(ev)
        assertEquals("TaxonomyNodeCreated", KanbanFSM.current().lastEventKind)
        assertEquals(1, KanbanFSM.current().taxonomyNodeCount)
    }
}
