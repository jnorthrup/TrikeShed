package borg.trikeshed.kanban

import borg.trikeshed.dag.*
import borg.trikeshed.dag.Activation
import borg.trikeshed.job.*
import borg.trikeshed.kanban.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BoardPriorityAdmissionTest {
    @Test fun priorityAndLaneEditsChangeTheNextAdmittedCard() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 10L }, parentJob = coroutineContext[Job])
        store.open()
        try {
            suspend fun commit(raw: Map<String, Any?>): BoardApply.Committed {
                val reply = CompletableDeferred<BoardApply>()
                store.intake.send(BoardIntake(raw, reply))
                return assertIs<BoardApply.Committed>(reply.await())
            }
            suspend fun move(id: String, column: BoardCol) = commit(mapOf(
                "type" to "move", "jobId" to id, "expectedRevision" to store.card(id)!!.revision,
                "idempotencyKey" to "$id/${column.wire}/${store.card(id)!!.revision}", "toColumn" to column.wire,
            ))
            for (id in listOf("occupied-a", "occupied-b", "older", "urgent")) {
                commit(mapOf("type" to "submit", "jobId" to id, "idempotencyKey" to "$id/new",
                    "priority" to if (id == "urgent") 0 else 2))
                move(id, if (id.startsWith("occupied")) BoardCol.RUNNING else BoardCol.READY)
            }
            suspend fun next(): Activation {
                val net = ReteNetwork()
                val facts = BoardFactElement(net)
                for (row in store.cards()) facts.onCommitted(BoardCommitted(
                    row.lastSequence, row.jobId,
                    JobSnapshot(JobId(row.jobId), row.revision, "", "moved"),
                    checkNotNull(row.commandCid), JobCommand.Acknowledge(JobId(row.jobId), "seed", row.revision),
                    row.col, null, row.lastMoveMs, row.owner, row.spec, row.parent,
                    priority = row.priority, order = row.order, strikes = row.strikes,
                ))
                val selected = mutableListOf<Activation>()
                ClaimProduction().evaluate(net, "board", selected::add)
                return selected.single()
            }
            assertEquals("urgent", next().bindings["jobId"], "Only one WIP unit remains")
            val before = store.card("older")!!
            commit(mapOf("type" to "update", "jobId" to "older", "idempotencyKey" to "older/priority",
                "expectedRevision" to before.revision, "priority" to 0))
            assertEquals(before.lastMoveMs, store.card("older")!!.lastMoveMs)
            assertEquals("older", next().bindings["jobId"], "Equal priority retains the lane's order")
            val stale = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf(
                "type" to "move", "jobId" to "urgent", "expectedRevision" to store.card("urgent")!!.revision,
                "idempotencyKey" to "stale-activation", "toColumn" to "running", "owner" to BoardRules.CLAIM_OWNER,
            ), stale))
            assertTrue(assertIs<BoardApply.Rejected>(stale.await()).reason.contains("allocation changed"))
            commit(mapOf("type" to "move", "jobId" to "urgent", "expectedRevision" to store.card("urgent")!!.revision,
                "idempotencyKey" to "urgent/reorder", "toColumn" to "ready", "beforeJobId" to "older"))
            assertEquals("urgent", next().bindings["jobId"], "Authored position breaks equal-priority ties")
            move("older", BoardCol.TODO)
            val activation = next()
            assertEquals("urgent", activation.bindings["jobId"], "Moving out of READY withdraws admission")
            commit(mapOf("type" to "move", "jobId" to activation.bindings.getValue("jobId"),
                "expectedRevision" to activation.bindings.getValue("expectedRevision").toLong(),
                "idempotencyKey" to activation.activationId, "toColumn" to "running",
                "owner" to activation.bindings.getValue("owner")))
            assertEquals(BoardCol.RUNNING, store.card("urgent")!!.col)
            assertEquals(BoardCol.TODO, store.card("older")!!.col)
        } finally {
            store.drain()
        }
    }
}
