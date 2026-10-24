package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreadcrumbProjectionTest {
    private fun snapshot(vararg docs: Pair<String, String>): ContextSnapshot = snapshotOf(*docs)

    private fun Projection.shown(): List<String> = (0 until excerpts.size).map { excerpts[it].text }

    private fun ContextSnapshot.original(name: String): String =
        (0 until documents.size).first { documents[it].name == name }.let { documents[it].text }

    @Test
    fun queryTermsSelectTheirLinesAndOnlyThem() {
        val snap = snapshot(
            "notes.md" to "alpha beta gamma\nthe manifest is pinned per task\nunrelated filler about weather\n",
            "src/A.kt" to "fun publishManifest() {}\nval colour = 1\n",
        )
        val p = BreadcrumbProjector.project(snap, "publish the manifest")
        val shown = p.shown()
        assertTrue(shown.any { it.contains("publishManifest") }, "camelCase identifiers match their word pieces")
        assertTrue(shown.any { it.contains("manifest is pinned") })
        assertFalse(shown.any { it.contains("weather") || it.contains("colour") || it.contains("alpha") })
    }

    @Test
    fun excerptsAreWholeOriginalLinesWithExactProvenance() {
        val snap = snapshot(
            "a.txt" to "  indented line about caching\n\n\tsecond caching line\nnothing here\n",
            "b.txt" to "caching again\r\nwindows endings\r\n",
        )
        val p = BreadcrumbProjector.project(snap, "caching")
        assertTrue(p.excerpts.size > 0)
        for (i in 0 until p.excerpts.size) {
            val e = p.excerpts[i]
            val original = snap.original(e.document)
            assertEquals(original.substring(e.range.start, e.range.end), e.text, "range resolves to the exact text")
            assertEquals(ContentId.of(bytesOf(original)), e.documentCid)
            // Whole lines: starts at a line start, ends at a line end, indentation intact, never clipped.
            assertTrue(e.range.start == 0 || original[e.range.start - 1] == '\n' || original[e.range.start - 1] == '\r')
            assertTrue(e.range.end == original.length || original[e.range.end] == '\n' || original[e.range.end] == '\r')
        }
        assertTrue(p.shown().any { it.startsWith("  indented line about caching") }, "indentation is preserved")
    }

    @Test
    fun theRenderedProjectionNeverExceedsTheBudgetAndNeverClips() {
        val lines = (1..40).joinToString("\n") { "line $it mentions the retry policy in detail number $it" }
        val snap = snapshot("big.txt" to lines)
        val originalLines = lines.lines().toSet()
        for (budget in listOf(0, 10, 60, 61, 120, 500, 5_000)) {
            val p = BreadcrumbProjector.project(snap, "retry policy", ProjectionBudget(maxChars = budget))
            val rendered = p.render()
            assertTrue(rendered.length <= budget, "budget $budget but rendered ${rendered.length}")
            assertEquals(rendered.length, p.charsUsed)
            for (t in p.shown()) assertTrue(t.lines().all { it in originalLines }, "no clipped fragments")
            if (budget < 60) assertEquals(0, p.excerpts.size)
        }
        val tight = BreadcrumbProjector.project(snap, "retry policy", ProjectionBudget(maxChars = 200))
        assertTrue(tight.requiresExpansion, "most of the matches did not fit and the projection says so")
        assertTrue(tight.omittedCandidates > 0)
    }

    @Test
    fun rareTermsOutrankCommonOnes() {
        val common = (1..10).joinToString("\n") { "cache entry number $it" }
        val snap = snapshot("a.txt" to "$common\ntombstone marker for deleted entry\n")
        val p = BreadcrumbProjector.project(snap, "cache tombstone", ProjectionBudget(maxLines = 1))
        assertEquals(listOf("tombstone marker for deleted entry"), p.shown())
    }

    @Test
    fun longerSpecificTermsBreakTiesAndShortIdentifiersSurvive() {
        val snap = snapshot("a.txt" to "id appears here\nserialization appears here\n")
        val both = BreadcrumbProjector.project(snap, "id serialization", ProjectionBudget(maxLines = 1))
        assertEquals(listOf("serialization appears here"), both.shown(), "equal popularity: the longer term is the more specific")
        val short = BreadcrumbProjector.project(snap, "id")
        assertEquals(listOf("id appears here"), short.shown(), "two-letter identifiers are not noise")
        assertEquals(listOf("publish", "manifest"), BreadcrumbProjector.tokens("Publish THE manifest"))
    }

    @Test
    fun duplicateLinesAreShownOnceAndDoNotMovePopularity() {
        val lonely = snapshot("a.txt" to "common words here\nrare token here\n")
        val copied = snapshot(
            "a.txt" to "common words here\nrare token here\n",
            "b.txt" to "common words here\n",
            "c.txt" to "common words here\n",
        )
        val q = "common rare"
        val one = BreadcrumbProjector.project(lonely, q)
        val many = BreadcrumbProjector.project(copied, q)
        assertEquals(one.shown(), many.shown(), "a line copied into other documents is still shown once")
        assertEquals(
            (0 until one.excerpts.size).map { one.excerpts[it].score },
            (0 until many.excerpts.size).map { many.excerpts[it].score },
            "copies do not inflate or deflate anyone's popularity",
        )
    }

    @Test
    fun actionLinesBreakTiesBetweenOtherwiseEqualMatches() {
        val snap = snapshot("a.txt" to "manifest publish now\npublish manifest now\n")
        val p = BreadcrumbProjector.project(snap, "publish manifest", ProjectionBudget(maxLines = 1))
        assertEquals(listOf("publish manifest now"), p.shown())
        assertTrue("action" in p.excerpts[0].reasons)
    }

    @Test
    fun aProtectedConstraintTravelsWithItsMatchAndIsNeverSilentlyLost() {
        val match = "publish the manifest to peers"
        val constraint = "Do not announce until every block is pinned."
        val snap = snapshot("a.txt" to "$match\n$constraint\nunrelated filler line\n")

        val roomy = BreadcrumbProjector.project(snap, "publish manifest")
        assertEquals(1, roomy.excerpts.size, "adjacent lines are merged into one run")
        assertEquals("$match\n$constraint", roomy.excerpts[0].text)
        assertTrue("constraint:neighbor" in roomy.excerpts[0].reasons)
        assertEquals(0, roomy.droppedConstraints)

        // Exactly enough for both: both are shown. One character less: the match is shown, the constraint is
        // reported as dropped, and the projection admits it is not a faithful summary.
        val exact = roomy.charsUsed
        val fits = BreadcrumbProjector.project(snap, "publish manifest", ProjectionBudget(maxChars = exact))
        assertEquals(0, fits.droppedConstraints)
        assertFalse(fits.requiresExpansion)

        val short = BreadcrumbProjector.project(snap, "publish manifest", ProjectionBudget(maxChars = exact - 1))
        assertEquals(listOf(match), short.shown())
        assertEquals(1, short.droppedConstraints)
        assertTrue(short.requiresExpansion)
    }

    @Test
    fun numericLimitsAreProtectedToo() {
        // The neighbour shares no query term and no negation word: only the number-with-a-limit rule can pull it in.
        val limited = snapshot("a.txt" to "read the block from the peer\nvalues stay under 1048576 bytes\nfiller\n")
        assertTrue(BreadcrumbProjector.project(limited, "read block peer").shown().joinToString("\n").contains("1048576 bytes"))

        // Control: the same shape without a limit is not dragged in.
        val plain = snapshot("a.txt" to "read the block from the peer\nvalues stay small and tidy\nfiller\n")
        assertFalse(BreadcrumbProjector.project(plain, "read block peer").shown().joinToString("\n").contains("tidy"))
    }

    @Test
    fun stopwordsAloneNeverSelectLines() {
        // The corpus really contains the stopwords, so only the filter can keep them from matching.
        val snap = snapshot("a.txt" to "the cat sat on the mat\nunrelated words here\n")
        assertEquals(0, BreadcrumbProjector.project(snap, "the on").excerpts.size)
        assertEquals(listOf("the cat sat on the mat"), BreadcrumbProjector.project(snap, "the cat").shown())
    }

    @Test
    fun theSameInputsGiveByteIdenticalOutputAndNoMatchGivesNothing() {
        val snap = snapshot("a.txt" to "alpha beta\nbeta gamma\ngamma delta\n", "b.txt" to "beta everywhere\n")
        val first = BreadcrumbProjector.project(snap, "beta gamma").render()
        val second = BreadcrumbProjector.project(snap, "beta gamma").render()
        assertContentEquals(first.encodeToByteArray(), second.encodeToByteArray())

        val none = BreadcrumbProjector.project(snap, "zebra")
        assertEquals(0, none.excerpts.size)
        assertFalse(none.requiresExpansion)
        assertEquals(0, BreadcrumbProjector.project(snap, "the of and").excerpts.size, "a question of only stopwords selects nothing")
    }
}
