package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncDomEventTest {
    private val fields = mapOf("title" to mapOf("ph" to "Title"),
        "priority" to mapOf("v" to "2", "opts" to listOf("0", "1", "2")))

    private fun event(revision: Long = 1L, title: String = "Edited", id: String = "edit") = mapOf(
        "id" to id, "target" to "board", "kind" to "edit",
        "item" to mapOf("id" to "card", "revision" to revision), "fields" to mapOf("title" to title))

    private suspend fun send(store: BoardStoreElement, raw: Map<String, Any?>): BoardApply =
        CompletableDeferred<BoardApply>().let { reply ->
            store.intake.send(BoardIntake(raw, reply)); reply.await()
        }

    @Test fun eventDispatchesOnceThenProjectsWithoutReplayingIndependentWork() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            assertIs<BoardApply.Committed>(send(store, mapOf("type" to "submit", "jobId" to "card",
                "idempotencyKey" to "create", "title" to "Original")))
            var independent = 0
            var commands = 0
            val registry = PureNodes.registry { 1L } + LcncKanbanExperience(store).registry() +
                SurfaceNodes.registry { method, path, body ->
                    assertEquals("POST", method); assertEquals("/api/invoke", path)
                    val raw = (body as Map<*, *>)["commands"] as List<*>
                    mapOf("results" to raw.map { value ->
                        commands++
                        @Suppress("UNCHECKED_CAST")
                        when (val result = send(store, value as Map<String, Any?>)) {
                            is BoardApply.Committed -> mapOf("verdict" to "committed", "idempotencyKey" to result.idempotencyKey)
                            is BoardApply.Rejected -> mapOf("verdict" to "rejected", "reason" to result.reason,
                                "idempotencyKey" to result.idempotencyKey)
                        }
                    })
                } + mapOf(
                    "test.independent" to LcncNodeRunner { _, _ -> independent++; emptyMap() },
                    "test.command" to LcncNodeRunner { _, inputs ->
                        val event = inputs["event"] as? Map<*, *> ?: return@LcncNodeRunner emptyMap()
                        val item = event["item"] as Map<*, *>
                        mapOf("commands" to listOf(mapOf("type" to "update", "jobId" to item["id"],
                            "expectedRevision" to item["revision"], "idempotencyKey" to event["id"],
                            "title" to (event["fields"] as Map<*, *>)["title"])))
                    })
            val program = LcncProgram("dom-edit", s_[
                LcncNode("independent", "test.independent"), LcncNode("events", "dom.events"),
                LcncNode("refresh", "button"), LcncNode("command", "test.command"),
                LcncNode("batch", "job.batch"), LcncNode("projection", "kanban.activeSheets"),
                LcncNode("items", "pick", mapOf("path" to "items")),
                LcncNode("groups", "list.groupBy", mapOf("key" to "status")),
                LcncNode("fields", "json.value", mapOf("value" to borg.trikeshed.parse.jsonOf(fields))),
                LcncNode("board", "dom.board")], s_[
                LcncWire("events", "event", "command", "event"),
                LcncWire("command", "commands", "batch", "commands"),
                LcncWire("batch", "results", "projection", "after?"),
                LcncWire("refresh", "press", "projection", "trigger?"),
                LcncWire("projection", "boardView", "items", "x"), LcncWire("items", "y", "groups", "x"),
                LcncWire("groups", "groups", "board", "groups"),
                LcncWire("fields", "value", "board", "fields?"),
                LcncWire("events", "source", "board", "events?"),
                LcncWire("batch", "results", "board", "result?")])
            assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
            val runner = LcncRunner(registry)
            val initial = runner.runProcedure(program)
            assertEquals(0, commands)
            assertEquals(1, independent)
            assertEquals("events", initial.nodeOutputs["board"]?.get("events"))
            runner.pulse = LcncPulse("events", mapOf("event" to event()), initial.retained)
            val edited = runner.runProcedure(program)
            assertEquals(1, commands)
            assertEquals(1, independent)
            assertEquals("Edited", store.card("card")?.title)
            assertEquals(2L, store.lastSequence)
            fun projected(result: LcncRunner.ScopeResult): String? {
                val groups = result.nodeOutputs["board"]?.get("rendered") as Map<*, *>
                return groups.values.flatMap { it as List<*> }.map { it as Map<*, *> }
                    .single { it["id"] == "card" }["title"] as? String
            }
            assertEquals("Edited", projected(edited))
            assertEquals(mapOf("source" to "events"), edited.retained.outputs["events"])
            runner.pulse = LcncPulse("refresh", mapOf("press" to 2L), edited.retained)
            val refreshed = runner.runProcedure(program)
            assertEquals(1, commands, "an old edit must not survive as an event")
            assertEquals(1, independent)
            runner.pulse = LcncPulse("events", mapOf("event" to event(id = "stale")), refreshed.retained)
            val refused = runner.runProcedure(program)
            val verdict = (refused.nodeOutputs["batch"]?.get("results") as List<*>).single() as Map<*, *>
            assertEquals("rejected", verdict["verdict"])
            assertTrue(verdict["reason"].toString().contains("stale expectedRevision"))
            assertEquals("Edited", projected(refused), "a refusal must also project current state")
            assertEquals(2L, store.lastSequence)
            assertEquals(1, independent)
        } finally { store.close() }
    }

    @Test fun boardFormUsesTheAuthoredScalarParameterSchema() = runTest {
        val board = PureNodes.registry { 0L }.getValue("dom.board")
        val node = LcncNode("board", "dom.board")
        val inputs = mapOf("groups" to emptyMap<String, Any?>(), "fields?" to fields,
            "events?" to "events", "result?" to listOf(mapOf("verdict" to "rejected", "idempotencyKey" to "edit")))
        val output = board.run(node, inputs)
        assertEquals(fields, output["fields"])
        assertEquals("events", output["events"])
        assertEquals(inputs["result?"], output["result"])
        for (invalid in listOf(mapOf("title" to mapOf("opts" to listOf(1))),
            mapOf("title" to mapOf("optsFrom" to "hidden.fetch")), mapOf("title" to "text"))) {
            assertFailsWith<IllegalArgumentException> { board.run(node, inputs + ("fields?" to invalid)) }
        }
    }

    @Test fun eventsRequireTheRetainedTargetBindingAndValidFields() {
        val program = LcncProgram("binding", s_[LcncNode("events", "dom.events"), LcncNode("board", "dom.board")], emptyList<LcncWire>().toSeries())
        val retained = LcncRetainedFrame(mapOf("events" to mapOf("source" to "events"),
            "board" to mapOf("events" to "events", "fields" to fields)))
        assertEquals(setOf("events"), LcncPulse("events", mapOf("event" to event()), retained).roots(program))
        for (invalid in listOf(event() + ("target" to "missing"), event() + ("id" to ""),
            event() + ("fields" to emptyMap<String, Any?>()), event() + ("fields" to mapOf("hidden" to "x")),
            event() + ("fields" to mapOf("priority" to "7")), event() + ("fields" to mapOf("title" to 42)))) {
            assertFailsWith<IllegalArgumentException> {
                LcncPulse("events", mapOf("event" to invalid), retained).roots(program)
            }
        }
        val other = retained.copy(outputs = retained.outputs + ("board" to mapOf("events" to "other", "fields" to fields)))
        assertFailsWith<IllegalArgumentException> { LcncPulse("events", mapOf("event" to event()), other).roots(program) }
        val create = event() - "item" + ("kind" to "create")
        assertFailsWith<IllegalArgumentException> { LcncPulse("events", mapOf("event" to create), retained).roots(program) }
        assertEquals(setOf("events"), LcncPulse("events", mapOf("event" to
            (create + ("fields" to mapOf("title" to "New", "priority" to "2")))), retained).roots(program))
        val move = event() - "fields" + mapOf("kind" to "move", "itemId" to "card", "from" to "triage", "to" to "todo")
        assertEquals(setOf("events"), LcncPulse("events", mapOf("event" to move), retained).roots(program))
        assertFailsWith<IllegalArgumentException> { LcncPulse("events", mapOf("event" to (move - "to")), retained).roots(program) }
    }

    @Test fun presetOrdersCommandBeforeProjectionAndHasNoUnconditionalSubmit() {
        val program = LcncProgramConfix.fromJson("preset-kanban", LcncPresets.all().getValue("preset-kanban"))
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val nodes = program.nodes.view.toList()
        assertTrue(nodes.none { it.type == "kanban.submit" })
        assertTrue(nodes.indexOfFirst { it.type == "job.batch" } < nodes.indexOfFirst { it.type == "kanban.activeSheets" })
        assertTrue(nodes.indexOfFirst { it.type == "kanban.activeSheets" } < nodes.indexOfFirst { it.type == "dom.board" })
    }
}
