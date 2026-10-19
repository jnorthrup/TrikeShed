package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncKanbanRevisionTest {
    private suspend fun board(block: suspend (BoardStoreElement, LcncNodeRunner) -> Unit) {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "submit", "jobId" to "card",
                "idempotencyKey" to "create", "title" to "Original"), reply))
            assertIs<BoardApply.Committed>(reply.await())
            block(store, LcncKanbanExperience(store).registry().getValue("kanban.move"))
        } finally { store.close() }
    }

    private fun node(revision: String = "1", key: String = "parameter") = LcncNode("move", "kanban.move",
        mapOf("jobId" to "card", "toColumn" to "ready", "expectedRevision" to revision, "idempotencyKey" to key))

    @Test
    fun wiredRevisionCannotBeTruncatedOrReplacedByAParameter() = runTest {
        board { store, move ->
            val before = assertNotNull(store.card("card"))
            for (port in listOf("command", "command?")) {
                for ((index, value) in listOf<Any?>(1.5, null, "", "invalid", "1.0", "1.5", true,
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Long.MAX_VALUE.toDouble(), Long.MIN_VALUE.toDouble() * 2).withIndex()) {
                    val output = move.run(node(), mapOf(port to mapOf("expectedRevision" to value,
                        "idempotencyKey" to "$port-invalid-$index")))
                    assertEquals(false, output["accepted"], "$port expectedRevision=$value")
                    assertTrue("expectedRevision" in output["reason"].toString(), output.toString())
                    assertEquals(before, store.card("card"))
                    assertEquals(1L, store.lastSequence)
                }
                for (revision in listOf(Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L)) {
                    val output = move.run(node(), mapOf(port to mapOf("expectedRevision" to revision,
                        "idempotencyKey" to "$port-exact-$revision")))
                    assertEquals(false, output["accepted"])
                    assertEquals("stale expectedRevision: expected $revision, actual 1", output["reason"])
                    assertEquals(before, store.card("card"))
                }
            }
            val accepted = move.run(node("99"), mapOf("command" to mapOf("expectedRevision" to 1.0,
                "idempotencyKey" to "integral")))
            assertEquals(true, accepted["accepted"])
            assertEquals(2L, accepted["revision"])
            assertEquals(BoardCol.READY, store.card("card")?.col)
        }
    }

    @Test
    fun parameterRevisionUsesTheSameIntegerBoundary() = runTest {
        board { store, move ->
            val before = assertNotNull(store.card("card"))
            for ((index, value) in listOf("1.5", "1.0", "", "invalid", "NaN", "Infinity",
                "9223372036854775808", "-9223372036854775809").withIndex()) {
                val output = move.run(node(value, "invalid-$index"), emptyMap())
                assertEquals(false, output["accepted"], "parameter expectedRevision=$value")
                assertTrue("expectedRevision" in output["reason"].toString(), output.toString())
                assertEquals(before, store.card("card"))
                assertEquals(1L, store.lastSequence)
            }
            val precise = Long.MAX_VALUE - 1L
            val stale = move.run(node(precise.toString(), "exact"), emptyMap())
            assertEquals("stale expectedRevision: expected $precise, actual 1", stale["reason"])
            assertEquals(before, store.card("card"))
            val accepted = move.run(node("1", "integral"), emptyMap())
            assertEquals(true, accepted["accepted"])
            assertEquals(2L, accepted["revision"])
        }
    }
}
