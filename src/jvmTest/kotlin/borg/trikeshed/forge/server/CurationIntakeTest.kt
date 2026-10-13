package borg.trikeshed.forge.server

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.*
import borg.trikeshed.narsese.ConstellationNodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurationIntakeTest {
    private class Corpus : ProjectCorpus {
        val extracted = mutableListOf<String>()
        override suspend fun projects(): List<ProjectRef> = emptyList()
        override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> = emptyList()
        override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = null
        override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText {
            extracted.add(id)
            return ProjectText(project, id, ContentId.of(id.encodeToByteArray()).value, "1", 0, id)
        }
    }

    @Test
    fun blockedCurationBoundsExtractedDocumentsAndDrainFinishesEachAdmission(): Unit = runBlocking {
        val corpus = Corpus()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val curated = mutableListOf<String>()
        val joined = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs ->
                started.complete(Unit)
                release.await()
                val id = (inputs.getValue("doc") as Map<*, *>)["id"] as String
                curated.add(id)
                mapOf("book" to id, "statements" to 1, "sections" to 1)
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, inputs ->
                joined.add(inputs.getValue("book") as String)
                emptyMap()
            },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this)
        val ids = List(CurationIntake.DOCUMENTS * 4) { "book-$it.pdf" }
        for (id in ids) { intake.offer("project", id); intake.offer("project", id) }
        intake.offer("project", "book.pdf${ProjectNodes.EXTRACT_SUFFIX}")
        try {
            withTimeout(5_000) {
                started.await()
                // All runnable admissions may advance; only the parser's deferred remains blocked.
                repeat(ids.size * 3) { yield() }
            }
            assertTrue(corpus.extracted.size <= CurationIntake.DOCUMENTS,
                "${corpus.extracted.size} decoded documents accumulated behind one blocked parser")
            assertEquals(CurationIntake.DOCUMENTS, corpus.extracted.size,
                "a startup backlog must wake each bounded extraction consumer")
            val draining = async { intake.drain() }
            yield()
            assertFalse(draining.isCompleted, "drain must join the admitted parse")
            assertFailsWith<IllegalStateException> { intake.offer("project", "after.pdf") }
            release.complete(Unit)
            withTimeout(5_000) { draining.await() }

            assertEquals(ids.sorted(), corpus.extracted.sorted())
            assertEquals(ids.sorted(), curated.sorted())
            assertEquals(ids.sorted(), joined.sorted())
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(ids.size, status["queued"])
            assertEquals(ids.size, status["curated"])
            assertEquals(0, status["pending"])
            assertEquals(0, status["failed"])
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun forgottenQueuedDocumentIsReleasedWithoutExtraction(): Unit = runBlocking {
        val corpus = Corpus()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs -> mapOf("book" to inputs.getValue("text")) },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> emptyMap() },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this)
        intake.offer("project", "held.pdf")
        intake.offer("project", "removed.pdf")
        intake.forget("project", "removed.pdf")
        intake.drain()

        assertEquals(listOf("held.pdf"), corpus.extracted)
        val status = intake.status().getValue("project") as Map<*, *>
        assertEquals(1, status["queued"])
        assertEquals(1, status["curated"])
        assertEquals(0, status["pending"])
        assertEquals(0, status["failed"])
    }
}
