package borg.trikeshed.kanban

import borg.trikeshed.dag.*
import borg.trikeshed.dag.Activation
import borg.trikeshed.job.*
import borg.trikeshed.kanban.rules.*
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BoardExecutionFamilyTest {
    private class Board {
        val store = BoardStoreElement(null, CasStore.inMemory())
        val net = ReteNetwork()
        val facts = BoardFactElement(net)
        val observed = MutableStateFlow(0L)
        val fired = mutableListOf<Activation>()
        private var ordinal = 0

        init { net.productionSink = { fired += it } }

        suspend fun apply(raw: Map<String, Any?>): BoardApply {
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(raw + ("idempotencyKey" to "family-test/${++ordinal}"), reply))
            return reply.await().also { result ->
                if (result is BoardApply.Committed) observed.first { it >= result.sequence }
            }
        }

        suspend fun submit(id: String, family: String = "", parent: String? = null): BoardApply = apply(buildMap {
            put("type", "submit")
            put("jobId", id)
            if (family.isNotEmpty()) {
                put("owner", "lcnc-runner")
                put("lcncRun", buildMap {
                    put("runId", id.removePrefix("lcnc/run/"))
                    put("executionFamily", family)
                    parent?.let { put("parentRunId", it) }
                })
                parent?.let { put("parent", "lcnc/run/$it") }
            }
        })

        private suspend fun receipt(id: String): Map<String, Any?> =
            (store.command(id)?.get("lcncRun") as? Map<*, *>)?.let { mapOf("lcncRun" to it) } ?: emptyMap()

        suspend fun move(id: String, col: BoardCol): BoardApply = apply(mapOf(
            "type" to "move", "jobId" to id, "toColumn" to col.wire,
            "expectedRevision" to assertNotNull(store.card(id)).revision,
        ) + receipt(id))

        suspend fun start(id: String): BoardApply = apply(mapOf(
            "type" to "start", "jobId" to id, "expectedRevision" to assertNotNull(store.card(id)).revision,
        ) + receipt(id))
    }

    private suspend fun withBoard(body: suspend Board.() -> Unit) = coroutineScope {
        val board = Board()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            board.store.committed.collect {
                board.facts.onCommitted(it)
                board.observed.value = it.sequence
            }
        }
        board.store.open()
        try { board.body() }
        finally {
            board.store.drain()
            collector.cancelAndJoin()
            board.facts.retractAll()
            board.net.close()
        }
    }

    private fun committed(id: String, col: BoardCol, family: String = "", clock: Long = 0L): BoardCommitted = BoardCommitted(
        sequence = clock + 1,
        jobId = id,
        snapshot = JobSnapshot(JobId(id), 1L, "", if (col == BoardCol.RUNNING) "active" else "ready"),
        cid = ContentId.of("$id/${col.wire}/$family/$clock".encodeToByteArray()),
        command = JobCommand.Move(JobId(id), "fact/$id", 0L, KanbanColumnId(col.wire)),
        col = col,
        previousCol = null,
        lastMoveMs = clock,
        owner = if (family.isEmpty()) "" else "lcnc-runner",
        executionFamily = family,
    )

    @Test
    fun oneFamilyLeavesOrdinaryClaimCapacityAndProjectsBothCounts() = runTest {
        withBoard {
            net.register(WipBreachProduction())
            net.register(ClaimProduction())
            val root = "lcnc/run/root"
            assertIs<BoardApply.Committed>(submit(root, root))
            assertIs<BoardApply.Committed>(start(root))
            for (i in 1..4) {
                val child = "lcnc/run/child$i"
                assertIs<BoardApply.Committed>(submit(child, root, "root"))
                assertIs<BoardApply.Committed>(if (i % 2 == 0) move(child, BoardCol.RUNNING) else start(child))
            }
            assertIs<BoardApply.Committed>(submit("ordinary"))
            assertIs<BoardApply.Committed>(move("ordinary", BoardCol.READY))
            assertEquals(listOf("ordinary"), fired.filter { it.ruleId == BoardRules.CLAIM }.map { it.bindings["jobId"] })
            assertIs<BoardApply.Committed>(move("ordinary", BoardCol.RUNNING))
            assertTrue(fired.none { it.ruleId == BoardRules.WIP_BREACH })
            val current = net.snapshot().view.associateBy { it.factId.b }
            assertFalse(current.getValue("ordinary").fields.containsKey("executionFamily"))
            for (id in listOf(root) + (1..4).map { "lcnc/run/child$it" }) {
                assertEquals(root, current.getValue(id).fields["executionFamily"])
                assertEquals(root, store.card(id)?.executionFamily)
            }
            val experience = LcncKanbanExperience(store)
            fun verify(view: Map<*, *>) {
                val running = (view["columns"] as List<*>).map { it as Map<*, *> }.single { it["id"] == "running" }
                assertEquals(6, running["count"])
                assertEquals(2, running["wipCount"])
                val items = (view["items"] as List<*>).map { it as Map<*, *> }
                assertFalse(items.single { it["id"] == "ordinary" }.containsKey("executionFamily"))
                assertEquals(5, items.count { it["executionFamily"] == root })
            }
            verify(experience.boardView())
            verify(experience.activeSheets()["boardView"] as Map<*, *>)
        }
    }

    @Test
    fun threeDistinctFamiliesRefuseANewRootButAdmitTheirOwnChildren() = runTest {
        withBoard {
            net.register(WipBreachProduction())
            net.register(ClaimProduction())
            for (name in listOf("a", "b", "c")) {
                val id = "lcnc/run/$name"
                assertIs<BoardApply.Committed>(submit(id, id))
                assertIs<BoardApply.Committed>(start(id))
            }
            assertIs<BoardApply.Committed>(submit("lcnc/run/a-child", "lcnc/run/a", "a"))
            assertIs<BoardApply.Committed>(start("lcnc/run/a-child"))
            assertIs<BoardApply.Committed>(submit("ordinary"))
            assertIs<BoardApply.Committed>(move("ordinary", BoardCol.READY))
            val fourth = "lcnc/run/d"
            assertIs<BoardApply.Committed>(submit(fourth, fourth))
            assertIs<BoardApply.Committed>(move(fourth, BoardCol.READY))
            assertTrue(assertIs<BoardApply.Rejected>(start(fourth)).reason.contains("WIP limit 3"))
            assertTrue(assertIs<BoardApply.Rejected>(move(fourth, BoardCol.RUNNING)).reason.contains("WIP limit 3"))
            assertEquals(BoardCol.READY, store.card(fourth)?.col)
            assertTrue(fired.none { it.ruleId == BoardRules.CLAIM })
            assertTrue(fired.none { it.ruleId == BoardRules.WIP_BREACH })
        }
    }

    @Test
    fun importedFourthFamilyBreachesWithEveryPhysicalCardAsSupport() = runTest {
        val net = ReteNetwork()
        val facts = BoardFactElement(net)
        val fired = mutableListOf<Activation>()
        net.productionSink = { fired += it }
        net.register(WipBreachProduction())
        try {
            val events = (0..4).map { committed("lcnc/run/a-$it", BoardCol.RUNNING, "lcnc/run/a") } +
                listOf("b", "c").map { committed("lcnc/run/$it", BoardCol.RUNNING, "lcnc/run/$it") }
            for (event in events) facts.onCommitted(event)
            assertTrue(fired.isEmpty(), "Seven physical cards in three execution families are within the limit")
            val fourth = committed("lcnc/run/d", BoardCol.RUNNING, "lcnc/run/d")
            facts.onCommitted(fourth)
            val breach = fired.single { it.ruleId == BoardRules.WIP_BREACH }
            assertEquals(mapOf("column" to "running", "count" to "4", "limit" to "3"), breach.bindings)
            assertEquals((events + fourth).map { it.cid }.toSet(), breach.supportCids.toSet())
        } finally { facts.retractAll(); net.close() }
    }

    @Test
    fun serviceReadyReceiptsLeaveTheRemainingClaimSlotsForOrdinaryWork() = runTest {
        val net = ReteNetwork()
        val facts = BoardFactElement(net)
        val fired = mutableListOf<Activation>()
        net.productionSink = { fired += it }
        try {
            facts.onCommitted(committed("lcnc/run/a", BoardCol.RUNNING, "lcnc/run/a"))
            facts.onCommitted(committed("lcnc/run/b", BoardCol.READY, "lcnc/run/b", 1))
            facts.onCommitted(committed("ordinary-1", BoardCol.READY, clock = 2))
            facts.onCommitted(committed("ordinary-2", BoardCol.READY, clock = 3))
            facts.onCommitted(committed("lcnc/run/b-child-1", BoardCol.READY, "lcnc/run/b", 4))
            facts.onCommitted(committed("lcnc/run/b-child-2", BoardCol.READY, "lcnc/run/b", 5))
            facts.onCommitted(committed("ordinary-3", BoardCol.READY, clock = 6))
            net.register(ClaimProduction())
            net.register(ReaperProduction(thresholdMs = 1))
            facts.onCommitted(committed("trigger", BoardCol.TODO))
            assertEquals(listOf("ordinary-1", "ordinary-2"),
                fired.filter { it.ruleId == BoardRules.CLAIM }.map { it.bindings["jobId"] })
            facts.tick(100)
            assertTrue(fired.none { it.ruleId == BoardRules.REAPER }, "The reaper cannot reassign service receipts either")
            assertNotEquals(boardWipUnit("lcnc/run/b", ""), boardWipUnit("child", "lcnc/run/b"))
        } finally { facts.retractAll(); net.close() }
    }

    @Test
    fun ordinaryCardsKeepTheThreeCardLimit() = runTest {
        withBoard {
            for (id in listOf("a", "b", "c", "d")) assertIs<BoardApply.Committed>(submit(id))
            for (id in listOf("a", "b", "c")) assertIs<BoardApply.Committed>(start(id))
            assertIs<BoardApply.Rejected>(start("d"))
            assertIs<BoardApply.Rejected>(move("d", BoardCol.RUNNING))
            net.register(ClaimProduction())
            assertIs<BoardApply.Committed>(move("d", BoardCol.READY))
            assertTrue(fired.none { it.ruleId == BoardRules.CLAIM })
            val running = (LcncKanbanExperience(store).boardView()["columns"] as List<*>)
                .map { it as Map<*, *> }.single { it["id"] == "running" }
            assertEquals(3, running["count"])
            assertEquals(3, running["wipCount"])
        }
    }

    @Test
    fun executionFamiliesRequireARealMatchingParentAndRetainTheirOwner() = runTest {
        withBoard {
            val a = "lcnc/run/a"
            val b = "lcnc/run/b"
            assertIs<BoardApply.Committed>(submit(a, a))
            assertIs<BoardApply.Committed>(submit(b, b))
            val malformed = "lcnc/run/malformed"
            for (receipt in listOf(
                mapOf("runId" to "another", "executionFamily" to malformed),
                mapOf("runId" to "malformed", "executionFamily" to null),
                mapOf("runId" to "malformed", "executionFamily" to 7),
                mapOf("runId" to "malformed", "executionFamily" to " "),
                mapOf("runId" to "malformed", "executionFamily" to "another"),
            )) {
                assertIs<BoardApply.Rejected>(apply(mapOf("type" to "submit", "jobId" to malformed,
                    "owner" to "lcnc-runner", "lcncRun" to receipt)))
            }
            assertIs<BoardApply.Rejected>(apply(mapOf("type" to "submit", "jobId" to malformed,
                "owner" to "jim", "lcncRun" to mapOf("runId" to "malformed", "executionFamily" to malformed))))
            assertNull(store.card(malformed), "A refused family cannot install a partial card")
            assertIs<BoardApply.Rejected>(submit("lcnc/run/missing-parent", a))
            assertIs<BoardApply.Rejected>(submit("lcnc/run/unknown-parent", a, "absent"))
            assertIs<BoardApply.Rejected>(submit("lcnc/run/wrong-family", b, "a"))
            val child = "lcnc/run/child"
            assertIs<BoardApply.Committed>(submit(child, a, "a"))
            assertIs<BoardApply.Rejected>(submit(child, b, "b"))
            assertIs<BoardApply.Rejected>(submit(child, a, "b"))
            assertIs<BoardApply.Rejected>(submit(child, a))
            assertIs<BoardApply.Rejected>(apply(mapOf("type" to "submit", "jobId" to child, "owner" to "jim")))
            val retained = assertNotNull(store.card(child)).commandCid
            assertIs<BoardApply.Rejected>(apply(mapOf("type" to "move", "jobId" to child,
                "toColumn" to "archived", "expectedRevision" to assertNotNull(store.card(child)).revision)))
            assertEquals(retained, store.card(child)?.commandCid, "A generic move cannot erase the retained run receipt")
            assertEquals(a, store.card(child)?.executionFamily)
            assertEquals("a", store.card(child)?.parentRunId)
            assertEquals("lcnc-runner", store.card(child)?.owner)
            assertIs<BoardApply.Committed>(submit("ordinary"))
            assertIs<BoardApply.Committed>(apply(mapOf("type" to "submit", "jobId" to "ordinary", "owner" to "jim")))
            assertEquals("jim", store.card("ordinary")?.owner)
            assertEquals("", store.card("ordinary")?.executionFamily)
        }
    }
}
