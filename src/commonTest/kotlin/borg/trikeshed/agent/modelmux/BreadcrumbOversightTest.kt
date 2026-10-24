package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Jeva
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BreadcrumbOversightTest {
    /** Answers in Jeva's reply shape; records exactly what it was asked. */
    private class FakeJudge(
        private val probabilities: List<Any?> = emptyList(),
        private val failWith: Throwable? = null,
        private val reshape: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
    ) {
        var calls = 0
        var state: Any? = null
        var questions: Map<String, Map<String, Any?>> = emptyMap()

        suspend fun ask(s: Any, q: Map<String, Map<String, Any?>>): Map<String, Any?> {
            calls++
            state = s
            questions = q
            failWith?.let { throw it }
            val answers = q.keys.toList().mapIndexed { i, id -> id to mapOf("noul" to probabilities[i]) }.toMap()
            val reply = mapOf("model" to "fake-jeva", "usage" to null, "answers" to answers)
            return reshape?.invoke(reply) ?: reply
        }
    }

    private fun oversight(judge: FakeJudge, policy: OversightPolicy = OversightPolicy()) = JevaOversight(policy) { s, q -> judge.ask(s, q) }

    /** One excerpt per document: each holds a single line containing the query term. */
    private fun projectionOf(count: Int, text: (Int) -> String = { "alpha line number $it" }): Projection {
        val snap = snapshotOf(*(0 until count).map { "doc-${it.toString().padStart(3, '0')}.txt" to text(it) + "\n" }.toTypedArray())
        return BreadcrumbProjector.project(snap, "alpha", ProjectionBudget(maxChars = 100_000, maxLines = 200))
    }

    @Test
    fun noOversightAsksNothing() = runTest {
        val p = projectionOf(3)
        val r = NoOversight.review("alpha", p)
        assertEquals(OversightStatus.NOT_REQUESTED, r.status)
        assertEquals(3, r.verdicts.size)
        assertTrue(r.verdicts.all { it == null })
        assertEquals(0, r.asked)
    }

    @Test
    fun relevanceThresholdsMapToVerdicts() = runTest {
        val p = projectionOf(3)
        val judge = FakeJudge(listOf(0.65, 0.35, 0.36))
        val r = oversight(judge).review("alpha", p)
        assertEquals(OversightStatus.REVIEWED, r.status)
        assertEquals("fake-jeva", r.model)
        assertEquals(listOf(Verdict.RELEVANT, Verdict.NOT_RELEVANT, Verdict.UNCERTAIN), r.verdicts)
        assertEquals(listOf(0.65, 0.35, 0.36), r.probabilities)
        assertEquals(3, r.asked)
        assertEquals(0, r.unreviewed)
    }

    @Test
    fun questionsAreFiniteOnePerExcerptAndTheStateCarriesTheTask() = runTest {
        val p = projectionOf(3)
        val judge = FakeJudge(listOf(0.9, 0.9, 0.9))
        oversight(judge).review("what does alpha do", p)

        assertEquals(listOf("span-0", "span-1", "span-2"), judge.questions.keys.toList())
        for ((id, q) in judge.questions) {
            assertEquals("noul", q["type"])
            assertTrue((q["instructions"] as String).contains(id))
        }
        // The production validator accepts exactly what we send.
        assertEquals(3, Jeva.questions(judge.questions).size)

        @Suppress("UNCHECKED_CAST")
        val state = judge.state as Map<String, Any?>
        assertEquals("what does alpha do", state["task"])
        @Suppress("UNCHECKED_CAST")
        val spans = state["spans"] as List<Map<String, Any?>>
        assertEquals(listOf("span-0", "span-1", "span-2"), spans.map { it["id"] })
        assertEquals((0 until 3).map { p.excerpts[it].text }, spans.map { it["text"] })
    }

    @Test
    fun anUnavailableJudgeIsNeverApproval() = runTest {
        val p = projectionOf(2)
        val down = oversight(FakeJudge(failWith = IllegalStateException("judge process died: secret-detail"))).review("alpha", p)
        assertEquals(OversightStatus.UNAVAILABLE, down.status)
        assertTrue(down.verdicts.all { it == null })
        assertEquals(0, down.asked)
        assertTrue(down.detail!!.contains("IllegalStateException"))
        assertTrue(!down.detail!!.contains("secret-detail"), "the failure message is not copied into the receipt")

        // Nothing is dropped on the strength of a judge that did not answer, and the caller can see why.
        val applied = applyOversight(p, down)
        assertEquals(2, applied.kept.size)
        assertEquals(0, applied.flagged.size)
        assertTrue(applied.receipt.status != OversightStatus.REVIEWED)
    }

    @Test
    fun malformedRepliesAreRejectedWholesale() = runTest {
        val p = projectionOf(2)
        val shapes = listOf<FakeJudge>(
            FakeJudge(listOf(0.9, 0.9)) { mapOf("model" to "x") },                                                   // no answers
            FakeJudge(listOf(0.9, 0.9)) { r -> r + ("answers" to mapOf("span-0" to mapOf("noul" to 0.9))) },           // missing an answer
            FakeJudge(listOf(0.9, 0.9)) { r -> r + ("answers" to mapOf("span-0" to mapOf("noul" to 0.9), "span-1" to mapOf("noul" to 0.9), "span-9" to mapOf("noul" to 0.9))) }, // extra
            FakeJudge(listOf(1.5, 0.9)),                                                                              // out of range
            FakeJudge(listOf(-0.1, 0.9)),
            FakeJudge(listOf("high", 0.9)),                                                                           // not a number
            FakeJudge(listOf(Double.NaN, 0.9)),
            FakeJudge(listOf(null, 0.9)),
        )
        for ((i, judge) in shapes.withIndex()) {
            val r = oversight(judge).review("alpha", p)
            assertEquals(OversightStatus.UNAVAILABLE, r.status, "shape #$i")
            assertTrue(r.verdicts.all { it == null }, "shape #$i: a partially valid reply is not trusted")
        }
    }

    @Test
    fun cancellationPropagates() = runTest {
        val cancel = CancellationException("owner cancelled")
        val thrown = assertFailsWith<CancellationException> { oversight(FakeJudge(failWith = cancel)).review("alpha", projectionOf(1)) }
        assertSame(cancel, thrown)
    }

    @Test
    fun boundedReviewNeverSilentlySamples() = runTest {
        val p = projectionOf(30)
        assertEquals(30, p.excerpts.size)
        val judge = FakeJudge(List(24) { 0.9 })
        val r = oversight(judge, OversightPolicy(maxReviewed = 24)).review("alpha", p)

        assertEquals(OversightStatus.REVIEWED, r.status)
        assertEquals(24, judge.questions.size)
        assertEquals(24, r.asked)
        assertEquals(6, r.unreviewed)
        assertEquals((24 until 30).toList(), r.verdicts.indices.filter { r.verdicts[it] == null }, "reading order: the tail is what goes unreviewed")
        assertTrue(r.detail!!.contains("6 of 30"))

        // An excerpt too long to show whole is left unreviewed, never truncated.
        val long = "alpha " + "x".repeat(300)
        val snap = snapshotOf("a.txt" to "alpha short\n", "b.txt" to "$long\n")
        val mixed = BreadcrumbProjector.project(snap, "alpha")
        val judge2 = FakeJudge(listOf(0.9))
        val r2 = oversight(judge2, OversightPolicy(maxExcerptChars = 100)).review("alpha", mixed)
        assertEquals(listOf("span-0"), judge2.questions.keys.toList())
        assertNull(r2.verdicts[1])
        assertTrue(!jsonOf(judge2.state!!).contains("xxxxxxxxxx"), "no clipped fragment of the long excerpt was sent")
    }

    @Test
    fun theSerializedStateStaysUnderItsLimit() = runTest {
        val p = projectionOf(40) { "alpha " + "word ".repeat(30) + it }
        val judge = FakeJudge(List(40) { 0.9 })
        val policy = OversightPolicy(maxStateChars = 1_200)
        val r = oversight(judge, policy).review("alpha", p)
        assertTrue(jsonOf(judge.state!!).length <= 1_200)
        assertTrue(r.unreviewed > 0 && r.asked > 0)

        val nothingFits = oversight(FakeJudge(), OversightPolicy(maxStateChars = 50)).review("alpha", p)
        assertEquals(OversightStatus.UNAVAILABLE, nothingFits.status)
    }

    @Test
    fun anEmptyProjectionNeedsNoQuestion() = runTest {
        val judge = FakeJudge()
        val r = oversight(judge).review("alpha", BreadcrumbProjector.project(snapshotOf("a.txt" to "beta\n"), "zebra"))
        assertEquals(OversightStatus.REVIEWED, r.status)
        assertEquals(0, judge.calls)
    }

    @Test
    fun oversightAnnotatesAndNeverRewritesEvidence() = runTest {
        val p = projectionOf(3)
        val before = (0 until 3).map { p.excerpts[it].text }
        val r = oversight(FakeJudge(listOf(0.9, 0.1, 0.5))).review("alpha", p)
        val applied = applyOversight(p, r)

        assertEquals(before, (0 until 3).map { p.excerpts[it].text })
        assertEquals(2, applied.kept.size)
        assertSame(p.excerpts[0], applied.kept[0], "kept excerpts are the very same objects")
        assertSame(p.excerpts[2], applied.kept[1], "uncertain is kept")
        assertSame(p.excerpts[1], applied.flagged[0])
        assertEquals(p.excerpts[0].rendered() + p.excerpts[2].rendered(), applied.render())
        assertNotNull(applied.receipt)
    }

    @Test
    fun notRelevantRemovesAnExcerptExceptOneCarryingAProtectedConstraint() = runTest {
        val snap = snapshotOf(
            "a.txt" to "publish the manifest to peers\nDo not announce until every block is pinned.\nfiller\n",
            "b.txt" to "publish a different thing entirely\n",
        )
        val p = BreadcrumbProjector.project(snap, "publish manifest")
        assertEquals(2, p.excerpts.size)
        assertTrue("constraint:neighbor" in p.excerpts[0].reasons)

        val applied = applyOversight(p, oversight(FakeJudge(listOf(0.05, 0.05))).review("publish manifest", p))
        assertEquals(listOf("a.txt"), (0 until applied.kept.size).map { applied.kept[it].document }, "the run holding a prohibition survives")
        assertEquals(listOf("b.txt"), (0 until applied.flagged.size).map { applied.flagged[it].document })
    }

    @Test
    fun aReceiptFromAnotherProjectionIsRefused() = runTest {
        val p = projectionOf(3)
        val other = oversight(FakeJudge(listOf(0.9))).review("alpha", projectionOf(1))
        assertFailsWith<IllegalArgumentException> { applyOversight(p, other) }
    }

    @Test
    fun theExactReceiptContentIsStable() = runTest {
        val p = projectionOf(2)
        val a = oversight(FakeJudge(listOf(0.7, 0.2))).review("alpha", p)
        val b = oversight(FakeJudge(listOf(0.7, 0.2))).review("alpha", p)
        assertContentEquals(a.verdicts.map { it?.name }.toTypedArray(), b.verdicts.map { it?.name }.toTypedArray())
        assertEquals(a.probabilities, b.probabilities)
    }
}
