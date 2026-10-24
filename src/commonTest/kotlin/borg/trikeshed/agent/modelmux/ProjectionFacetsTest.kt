package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The facet layer must save work and change nothing else. Every test that looks at an answer compares it with the plain
 * oracle; the counters are how the savings are pinned.
 */
class ProjectionFacetsTest {
    /** [documents] documents of [lines] distinct lines each; one of them carries a term nothing else has. */
    private fun corpus(documents: Int, lines: Int, flavour: String = ""): Array<Pair<String, String>> =
        Array(documents) { d ->
            "doc$d.md" to (0 until lines).joinToString("\n", postfix = "\n") { l ->
                if (d == 1 && l == 3) "The rareterm$flavour must be kept." else "line $d number $l about common topic"
            }
        }

    private fun distinctLines(docs: Array<Pair<String, String>>): Int =
        docs.flatMap { it.second.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() } }.toSet().size

    private fun same(snapshot: ContextSnapshot, question: String, facets: ProjectionFacets, budget: ProjectionBudget = ProjectionBudget()) {
        val expected = ReferenceProjector.project(snapshot, question, budget)
        val actual = BreadcrumbProjector.project(snapshot, question, budget, facets)
        assertEquals(expected.render(), actual.render(), "'$question'")
        assertEquals(expected.omittedCandidates, actual.omittedCandidates, "'$question' omitted")
        assertEquals(expected.droppedConstraints, actual.droppedConstraints, "'$question' dropped")
    }

    @Test
    fun aWarmIndexScoresOnlyTheLinesThatContainTheQuestionsTerms() {
        val snap = snapshotOf(*corpus(10, 100))
        val facets = ProjectionFacets()
        same(snap, "rareterm", facets)
        val first = facets.counters()
        assertEquals(1, first.indexesBuilt)
        assertEquals(0, first.indexHits)
        assertEquals(10, first.documentsIndexed)
        assertEquals(1L, first.linesScored, "1000 distinct lines, one of which contains the term")

        same(snap, "kept rareterm", facets)
        same(snap, "no such term anywhere", facets)
        val later = facets.counters()
        assertEquals(1, later.indexesBuilt, "the index is built once for the snapshot")
        assertEquals(2, later.indexHits)
        assertEquals(first.documentsIndexed, later.documentsIndexed)
        assertEquals(first.featuresComputed, later.featuresComputed, "no line is tokenized again")
        assertEquals(2L, later.linesScored, "a question that matches nothing scores nothing")
    }

    @Test
    fun aLineIsTokenizedOncePerDistinctContentAcrossRevisions() {
        val first = corpus(4, 25)
        val second = first.copyOf().also { it[2] = "doc2.md" to (it[2].second + "one brand new line\n") }
        val facets = ProjectionFacets()
        same(snapshotOf(*first), "topic", facets)
        val afterFirst = facets.counters()
        assertEquals(distinctLines(first), afterFirst.featuresComputed)

        same(snapshotOf(*second), "topic", facets)
        val afterSecond = facets.counters()
        assertEquals(1, afterSecond.featuresComputed - afterFirst.featuresComputed, "only the line the new revision added is tokenized")
        assertEquals(4 + 1, afterSecond.documentsIndexed, "the changed document is the only new document facet")
        assertEquals(3, afterSecond.documentHits, "unchanged documents are found by their content id")
        assertEquals(2, afterSecond.indexesBuilt, "a different revision is a different index")
    }

    @Test
    fun identicalLinesInManyDocumentsShareOneFeatureEntry() {
        val boilerplate = "Verify the sha256 before caching.\n"
        val docs = Array(6) { d -> "d$d.md" to (boilerplate + "unique line number $d\n") }
        val facets = ProjectionFacets()
        same(snapshotOf(*docs), "sha256 caching", facets)
        assertEquals(1 + 6, facets.counters().featuresComputed, "one shared line plus six unique ones")
    }

    @Test
    fun evictionNeverChangesAnAnswer() {
        val a = snapshotOf(*corpus(3, 12, "A"))
        val b = snapshotOf(*corpus(3, 12, "B"))
        val tiny = ProjectionFacets(maxDocumentLines = 5, maxFeatureLines = 7, maxIndexedLines = 40, maxSpines = 1)
        repeat(3) {
            same(a, "rarettermA", tiny)
            same(b, "rarettermB", tiny)
            same(a, "common topic", tiny)
            same(b, "number 2 about", tiny, ProjectionBudget(maxChars = 120, maxLines = 3))
        }
        val c = tiny.counters()
        assertTrue(c.evictions > 0, "the bounds were really exercised")
        assertTrue(c.indexesBuilt >= 4, "an index that does not fit is rebuilt, not trusted: ${c.indexesBuilt}")
    }

    @Test
    fun anEntryHeavierThanItsBoundIsBuiltPerRequestAndNeverRetained() {
        val snap = snapshotOf(*corpus(2, 15))
        val facets = ProjectionFacets(maxIndexedLines = 5)
        same(snap, "common", facets)
        same(snap, "topic", facets)
        val c = facets.counters()
        assertEquals(2, c.indexesBuilt)
        assertEquals(0, c.indexHits)
        assertEquals(0, c.evictions, "an oversize entry is not admitted, so nothing needs evicting for it")
    }

    @Test
    fun facetsBuiltForAnotherPolicyAreRefused() {
        val snap = snapshotOf(*corpus(1, 3))
        assertFailsWith<IllegalArgumentException> { BreadcrumbProjector.project(snap, "common", facets = ProjectionFacets(policy = "lexical-0")) }
    }

    @Test
    fun aSpineIsComputedOncePerContentIdAndAWrongOneStillFails() = runTest {
        val scope = "facets/a"
        val rev = revision(scope, *corpus(3, 10))
        val cas = rev.intoCas(CasStore.inMemory())
        val context = SharedContext(cas, null, ContextReadPolicy(allowedScopes = setOf(scope)))
        context.acquire(rev.cid, scope)
        context.acquire(rev.cid, scope)
        val c = context.facets.counters()
        assertEquals(3, c.spinesComputed, "one normalization and hash per distinct document")
        assertEquals(3, c.spineHits)

        // The memo holds the true spine of content that was already verified. A manifest that names that content with a
        // different spine is still refused: the comparison is per entry, against what the manifest claims.
        val real = rev.manifest.documents[0]
        val other = rev.manifest.documents[1]
        val hostile = BreadcrumbContextManifest.of(scope, "policy-1", "mallory", emptyList(), listOf(ManifestDocument("evil.md", real.content, other.spine)))
        cas.put(hostile.encode())
        val refusal = assertFailsWith<ContextBlockException> { context.acquire(hostile.cid, scope) }
        assertEquals(ContextRejection.SPINE_MISMATCH, refusal.reason)
    }

    @Test
    fun concurrentFirstQuestionsBuildTheIndexOnce() = runTest {
        val snap = snapshotOf(*corpus(6, 60))
        val facets = ProjectionFacets()
        val lock = SynchronizedObject()
        var arrivals = 0
        // A build waits briefly for a second builder. If builds are not deduplicated a second one arrives at once.
        facets.buildObserver = { kind ->
            if (kind == "index") {
                synchronized(lock) { arrivals++ }
                val started = TimeSource.Monotonic.markNow()
                while (synchronized(lock) { arrivals } < 2 && started.elapsedNow() < 150.milliseconds) { /* wait */ }
            }
        }
        val questions = listOf("common", "topic", "rareterm", "line number", "about common", "kept")
        val answers = withContext(Dispatchers.Default) {
            questions.map { q -> async { q to BreadcrumbProjector.project(snap, q, facets = facets).render() } }.awaitAll()
        }
        for ((q, rendered) in answers) assertEquals(ReferenceProjector.project(snap, q).render(), rendered, q)
        assertEquals(1, synchronized(lock) { arrivals }, "one builder for one key")
        assertEquals(1, facets.counters().indexesBuilt)
    }
}
