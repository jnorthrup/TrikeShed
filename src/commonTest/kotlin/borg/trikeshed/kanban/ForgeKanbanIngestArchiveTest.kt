package borg.trikeshed.kanban

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.seriesOf
import borg.trikeshed.treedoc.TreeDocPipeline
import borg.trikeshed.treedoc.TreeDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ForgeKanbanIngestArchiveTest {
    private val markdown1 = """
        TARGET: Target One

        6. Work packages

        A1 — First task
        Objective

        Make the graph real.
    """.trimIndent()

    private val markdown2 = """
        TARGET: Target Two

        6. Work packages

        B1 — Second task
        Depends on: A1.

        Prove one vertical cut.

        7. Production-rule behavior tied to Kanban
    """.trimIndent()

    @Test
    fun ingestReducesArchive() {
        val cas = CasStore.inMemory()
        val pipeline = TreeDocPipeline(cas, 1024)
        val docs = seriesOf(listOf(
            TreeDocument("doc1.md", "text/markdown", markdown1.encodeToByteArray()),
            TreeDocument("doc2.md", "text/markdown", markdown2.encodeToByteArray())
        ))

        val archive = pipeline.store(docs)

        val reduction = ForgeKanbanIngest.persistArchive("jim", archive, pipeline)

        assertEquals(listOf("A1", "B1"), reduction.board.cards.map { it.id.value })
        assertEquals("ready", reduction.board.cards.first { it.id.value == "A1" }.columnId.value)
        assertEquals("todo", reduction.board.cards.first { it.id.value == "B1" }.columnId.value)
        assertEquals(listOf("A1"), reduction.board.cards.first { it.id.value == "B1" }.dependencies.map { it.value })
    }
}
