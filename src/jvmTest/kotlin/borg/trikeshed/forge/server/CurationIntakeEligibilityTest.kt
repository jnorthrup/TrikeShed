package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.*
import borg.trikeshed.narsese.ConstellationNodes
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CurationIntakeEligibilityTest {
    private class Corpus : ProjectCorpus {
        val enumerated = mutableListOf<String>()
        val extracted = mutableListOf<String>()
        override suspend fun projects() = listOf(
            ProjectRef("primary", "assets", "@primary", 1),
            ProjectRef("secondary", "assets", "@upload", 1))
        override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> {
            enumerated.add(project)
            return listOf(ProjectDoc(project, "agreement.pdf", "cid", "rev", 0, 1, "application/pdf"))
        }
        override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = null
        override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText {
            extracted.add(project)
            return ProjectText(project, id, "cid", "rev", 0, "Agreement text")
        }
    }

    private val runners = mapOf(
        ConstellationNodes.CURATE to LcncNodeRunner { _, _ -> mapOf("book" to "agreement") },
        ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> emptyMap() })

    @Test
    fun resumeAndExplicitOffersDoNotAdmitIneligibleProjects(): Unit = runBlocking {
        val corpus = Corpus()
        val providerLookups = mutableListOf<String>()
        val intake = CurationIntake(corpus, { runners }, { null }, this,
            selectedProvider = { project, _ -> providerLookups.add(project); null },
            eligible = { it == "secondary" })
        intake.resume()
        intake.offer("primary", "direct.pdf")
        intake.offer("primary", "direct.pdf", null)
        val text = ProjectDoc("primary", "agreement.txt", "cid", "rev", 0, 1, "text/plain")
        assertFailsWith<IllegalArgumentException> { intake.offerTextForReview(text) }
        intake.drain()
        assertEquals(listOf("secondary"), corpus.enumerated)
        assertEquals(listOf("secondary"), providerLookups)
        assertEquals(listOf("secondary"), corpus.extracted)
        assertEquals(setOf("secondary"), intake.status().keys)
        assertEquals(1, (intake.status().getValue("secondary") as Map<*, *>)["curated"])
    }

    @Test
    fun eligibilityIsRecheckedBeforeQueuedWorkStarts(): Unit = runBlocking {
        val corpus = Corpus()
        var allowed = true
        val intake = CurationIntake(corpus, { runners }, { null }, this, eligible = { allowed })
        intake.offer("secondary", "agreement.pdf")
        allowed = false
        intake.drain()
        assertTrue(corpus.extracted.isEmpty())
        val tally = intake.status().getValue("secondary") as Map<*, *>
        assertEquals(0, tally["queued"])
        assertEquals(0, tally["failed"])
    }

    @Test
    fun defaultEligibilityStillAdmitsDocuments(): Unit = runBlocking {
        val corpus = Corpus()
        val intake = CurationIntake(corpus, { runners }, { null }, this)
        intake.offer("secondary", "agreement.pdf")
        intake.drain()
        assertEquals(listOf("secondary"), corpus.extracted)
        assertEquals(1, (intake.status().getValue("secondary") as Map<*, *>)["curated"])
    }
}
