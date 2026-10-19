package borg.trikeshed.graal

import borg.trikeshed.context.ElementState
import borg.trikeshed.dag.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlackboardProjectionLifecycleTest {
    private fun assertFailure(expected: Throwable, actual: Throwable) {
        // Coroutine stack recovery may copy an exception while retaining its original cause.
        var source = actual
        while (source !== expected && source.cause != null) source = source.cause!!
        assertSame(expected, source, "The original projection failure must be preserved")
    }

    @Test fun theRevisionBarrierRequiresCompletedProjection() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        val element = BlackboardChangesFactElement(board, rete)
        try {
            element.open()
            board.put("probe/barrier", "projected", "test")
            val revision = board.snapshot().revision
            element.awaitRevision(revision)
            assertEquals("projected", rete.snapshot()[0].fields[BlackboardChangesFactElement.VALUE])
            element.close()
            element.awaitRevision(revision)
            assertFailsWith<IllegalStateException> { element.awaitRevision(revision + 1) }
            assertFailsWith<IllegalArgumentException> { element.awaitRevision(-1) }
        } finally { element.supervisor.cancelAndJoin() }
    }

    @Test fun aWorkerFailureFailsItsBarrierAndJoinedClose() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        board.put("probe/first", 1, "test")
        board.put("probe/failure", 2, "test")
        val failure = IllegalStateException("projection failure")
        val element = BlackboardChangesFactElement(board, rete, admit = { key ->
            if (key == "probe/failure") throw failure
            true
        })
        try {
            element.open()
            assertFailure(failure, assertFailsWith<IllegalStateException> {
                element.awaitRevision(board.snapshot().revision)
            })
            assertFailure(failure, assertFailsWith<IllegalStateException> { element.close() })
            assertEquals(1, rete.snapshot().size, "Partial work is not a complete revision acknowledgement")
            assertEquals(ElementState.CLOSED, element.state)
            assertTrue(element.supervisor.isCompleted)
            assertTrue(element.supervisor.children.none())
        } finally { element.supervisor.cancelAndJoin() }
    }

    @Test fun aPassUsesOneValueAndProvenanceSnapshot() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        board.put("probe/a", "old-a", "first")
        board.put("probe/b", "old-b", "first")
        var changed = false
        val element = BlackboardChangesFactElement(board, rete, admit = { key ->
            if (key == "probe/a" && !changed) {
                changed = true
                board.put("probe/b", "new-b", "second")
            }
            true
        })
        try {
            element.drainKeys()
            val first = rete.workingMemory.facts(FactId(PlaneFacts.BLACKBOARD, "probe/b")).firstOrNull()!!
            assertEquals("old-b", first.fields[BlackboardChangesFactElement.VALUE])
            assertEquals("first", first.fields[PlaneFacts.ACTOR])
            element.drainKeys()
            val second = rete.workingMemory.facts(FactId(PlaneFacts.BLACKBOARD, "probe/b")).firstOrNull()!!
            assertEquals("new-b", second.fields[BlackboardChangesFactElement.VALUE])
            assertEquals("second", second.fields[PlaneFacts.ACTOR])
        } finally { element.supervisor.cancelAndJoin() }
    }

    @Test fun anUnopenedDrainTerminatesAndDetachesItsSupervisor() = runTest {
        val parent = Job()
        val element = BlackboardChangesFactElement(ConfixBlackboard.empty(), ReteNetwork(), parentJob = parent)
        try {
            withTimeout(1_000) { element.drain() }
            assertEquals(ElementState.CLOSED, element.state)
            assertTrue(element.supervisor.isCompleted)
            assertTrue(parent.children.none())
            element.close()
        } finally { parent.cancelAndJoin() }
    }

    @Test fun aClosedProjectionRejectsManualWork() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        val element = BlackboardChangesFactElement(board, rete)
        try {
            element.open()
            element.close()
            board.put("probe/late", "late", "test")
            assertFailsWith<IllegalStateException> { element.drainKeys() }
            assertEquals(0, rete.snapshot().size)
        } finally { element.supervisor.cancelAndJoin() }
    }

    @Test fun closeProjectsTheFinalSnapshotAfterConflatedUpdatesAndRemovals() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        board.put("probe/gone", "before", "test")
        val element = BlackboardChangesFactElement(board, rete)
        try {
            element.drainKeys()
            element.open()
            repeat(300) { board.put("probe/live", it, "test") }
            board.remove("probe/gone")
            element.close()
            assertEquals(ElementState.CLOSED, element.state)
            assertTrue(element.supervisor.isCompleted)
            assertTrue(element.supervisor.children.none())
            val facts = rete.snapshot()
            assertEquals(1, facts.size)
            assertEquals("probe/live", facts[0].factId.b)
            assertEquals("299", facts[0].fields[BlackboardChangesFactElement.VALUE])
            val applied = element.factsApplied
            board.put("probe/live", 300, "test")
            element.close()
            assertEquals(applied, element.factsApplied)
        } finally { element.supervisor.cancelAndJoin() }
    }

    @Test fun aFailedProjectionCannotReportASuccessfulClose() = runTest {
        val board = ConfixBlackboard.empty()
        board.put("probe/failure", 1, "test")
        val failure = IllegalStateException("admission failure")
        val element = BlackboardChangesFactElement(board, ReteNetwork(), admit = { throw failure })
        try {
            assertFailure(failure, assertFailsWith<IllegalStateException> { element.drainKeys() })
            assertFailure(failure, assertFailsWith<IllegalStateException> { element.close() })
            assertEquals(ElementState.CLOSED, element.state)
            assertTrue(element.supervisor.isCompleted)
            assertFailure(failure, assertFailsWith<IllegalStateException> { element.close() })
        } finally { element.supervisor.cancelAndJoin() }
    }
}
