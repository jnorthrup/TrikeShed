package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class LcncBoardStoreBindingTest {
    private suspend fun withStores(body: suspend (BoardStoreElement, BoardStoreElement) -> Unit) {
        val host = BoardStoreElement(null, CasStore.inMemory())
        val caller = BoardStoreElement(null, CasStore.inMemory())
        try {
            host.open()
            caller.open()
            withTimeout(10_000) { body(host, caller) }
        } finally {
            caller.close()
            host.close()
        }
    }

    private suspend fun submit(store: BoardStoreElement, id: String) {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(mapOf(
            "type" to "submit", "jobId" to id, "title" to id, "idempotencyKey" to id,
        ), reply))
        assertIs<BoardApply.Committed>(reply.await())
    }

    private fun items(board: Any?): List<Map<*, *>> =
        ((board as Map<*, *>)["items"] as List<*>).map { it as Map<*, *> }

    private fun assertSheets(id: String, sheets: Map<*, *>) {
        assertEquals(listOf(id), items(sheets["boardView"]).map { it["id"] })
        val rows = (sheets["board"] as Map<*, *>)["rows"] as List<*>
        assertEquals(id, (rows.single() as List<*>).first())
        for (partition in arrayOf("byStatus", "byPriority")) {
            val grouped = (sheets[partition] as List<*>).drop(1)
            val ids = grouped.flatMap { sheet ->
                ((sheet as Map<*, *>)["rows"] as List<*>).map { (it as List<*>).first() }
            }
            assertEquals(listOf(id), ids, partition)
        }
    }

    @Test
    fun readsResolveCallerKeyAndRestoreHostDefault() = runBlocking {
        withStores { host, caller ->
            submit(host, "host")
            submit(caller, "caller")
            var attentionStore: BoardStoreElement? = null
            val experience = LcncKanbanExperience(host, attention = { store ->
                attentionStore = store
                store.cards().associate { it.jobId to mapOf("attention" to 0.5f) }
            })
            val registry = experience.registry()
            for (type in arrayOf("board.get", "board.view", "kanban.activeSheets", "kanban.submit", "kanban.move", "kanban.import")) {
                val binding = assertIs<LcncServiceBinding>(registry.getValue(type))
                assertEquals(1, binding.requiredKeys.size, type)
                assertEquals(1, binding.providedKeys.size, type)
                assertSame(BoardStoreElement.Key, binding.requiredKeys[0], type)
                assertSame(BoardStoreElement.Key, binding.providedKeys[0], type)
            }
            for ((type, port) in mapOf("board.get" to "json", "board.view" to "board")) {
                val runner = registry.getValue(type)
                val node = LcncNode(type, type)
                assertEquals("host", items(runner.run(node, emptyMap())[port]).single()["id"])
                assertSame(host, attentionStore)
                val result = withContext(caller) { runner.run(node, emptyMap()) }
                assertSame(caller, attentionStore)
                assertEquals("caller", items(result[port]).single()["id"])
                assertEquals(0.5f, items(result[port]).single()["attention"])
                assertEquals("host", items(runner.run(node, emptyMap())[port]).single()["id"])
            }
            val sheets = registry.getValue("kanban.activeSheets")
            val node = LcncNode("sheets", "kanban.activeSheets")
            assertSheets("caller", withContext(caller) { sheets.run(node, emptyMap()) })
            assertSheets("host", sheets.run(node, emptyMap()))
            assertNull(currentCoroutineContext()[BoardStoreElement.Key])
            assertEquals(ElementState.ACTIVE, host.state)
            assertEquals(ElementState.ACTIVE, caller.state)
        }
    }

    @Test
    fun commandsCommitThroughResolvedIntakeAndKeepBorrowedStoresOpen() = runBlocking {
        withStores { host, caller ->
            val registry = LcncKanbanExperience(host).registry()
            suspend fun run(type: String, inputs: Map<String, Any?>): Map<String, Any?> =
                registry.getValue(type).run(LcncNode(type, type), inputs)
            assertEquals(true, run("kanban.submit", mapOf("command" to mapOf("jobId" to "host")))["accepted"])

            withContext(caller) {
                val submitted = run("kanban.submit", mapOf("command" to mapOf("jobId" to "caller")))
                assertEquals(true, submitted["accepted"])
                assertSheets("caller", submitted["sheets"] as Map<*, *>)
                assertNull(host.card("caller"))
                val move = mapOf("command" to mapOf(
                    "jobId" to "caller", "toColumn" to "ready",
                    "expectedRevision" to 1, "idempotencyKey" to "move-caller",
                ))
                val moved = run("kanban.move", move)
                assertEquals(true, moved["accepted"])
                assertSheets("caller", moved["sheets"] as Map<*, *>)
                assertEquals(BoardCol.READY, caller.card("caller")?.col)

                val rejected = run("kanban.move", move)
                assertEquals(false, rejected["accepted"])
                assertSheets("caller", rejected["sheets"] as Map<*, *>)
                val failure = runCatching {
                    run("kanban.move", mapOf("command" to mapOf("jobId" to "caller")))
                }.exceptionOrNull()
                assertIs<IllegalStateException>(failure)
                assertSame(caller, currentCoroutineContext()[BoardStoreElement.Key])

                val imported = run("kanban.import", mapOf("text" to "- Imported caller card"))
                assertEquals(1, imported["imported"])
                val importedId = (imported["jobIds"] as List<*>).single() as String
                assertEquals("Imported caller card", caller.card(importedId)?.title)
                assertNull(host.card(importedId))
                assertEquals(2, caller.cards().size)
                assertEquals(2L, caller.card("caller")?.revision)
            }

            assertEquals(listOf("host"), host.cards().map { it.jobId })
            assertEquals(1L, host.card("host")?.revision)
            val moved = registry.getValue("kanban.move").run(LcncNode("host-move", "kanban.move", mapOf(
                "jobId" to "host", "toColumn" to "ready",
                "expectedRevision" to "1", "idempotencyKey" to "host-move",
            )), emptyMap())
            assertEquals(true, moved["accepted"])
            assertEquals(BoardCol.READY, host.card("host")?.col)
            assertEquals(1, run("kanban.import", mapOf("text" to "- Imported host card"))["imported"])
            assertEquals(2, host.cards().size)
            assertEquals(2, caller.cards().size)
            assertEquals(ElementState.ACTIVE, host.state)
            assertEquals(ElementState.ACTIVE, caller.state)
            submit(host, "host-after")
            submit(caller, "caller-after")
        }
    }
}
