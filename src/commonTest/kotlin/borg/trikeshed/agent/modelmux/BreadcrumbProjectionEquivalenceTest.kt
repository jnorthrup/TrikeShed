package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential test: the indexed projector against the plain oracle in [ReferenceProjector], over generated corpora that
 * contain what makes the indexed version easy to get wrong: duplicate lines inside and across documents, blank and
 * whitespace-only lines, indentation, CRLF and LF, missing final newlines, identifiers that split into several terms,
 * stopwords, non-ASCII, protected constraints beside matches, and budgets from absurdly small to roomy.
 *
 * Agreement is checked on everything observable, including the exact bits of every score.
 */
class BreadcrumbProjectionEquivalenceTest {
    private class Rng(private var s: Long) {
        fun next(bound: Int): Int {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            return ((s ushr 33).toInt() and 0x7fffffff) % bound
        }
        fun <T> pick(items: List<T>): T = items[next(items.size)]
        fun chance(percent: Int): Boolean = next(100) < percent
    }

    private val words = listOf(
        "add", "fix", "verify", "publish", "fetch", "pin", "read", "write", "merge", "build",
        "not", "must", "never", "error", "failed", "cannot", "required", "only",
        "max", "limit", "bytes", "seconds", "42", "1048576", "7",
        "manifest", "spine", "block", "digest", "scope", "peer", "cache", "revision", "author", "head",
        "parseHeader", "MAX_BLOCK_BYTES", "sha256sum", "LineCasIndex", "snake_case_name",
        "the", "of", "and", "with", "caf\u00e9", "na\u00efve", "\u65e5\u672c\u8a9e",
    )
    private val separators = listOf(" ", ", ", " - ", "; ", "  ")

    private fun line(rng: Rng): String {
        val text = (0 until 1 + rng.next(9)).joinToString("") { i -> (if (i == 0) "" else rng.pick(separators)) + rng.pick(words) }
        val cased = if (rng.chance(25)) text.replaceFirstChar { it.uppercase() } else text
        val indent = when (rng.next(6)) { 0 -> "  "; 1 -> "\t"; 2 -> "    "; else -> "" }
        return indent + cased + (if (rng.chance(20)) "." else "") + (if (rng.chance(10)) "   " else "")
    }

    private fun corpus(rng: Rng): Array<Pair<String, String>> {
        val shared = List(10) { line(rng) }
        return Array(1 + rng.next(5)) { d ->
            val eol = if (rng.chance(30)) "\r\n" else "\n"
            val body = StringBuilder()
            repeat(rng.next(31)) {
                when {
                    rng.chance(12) -> body.append(if (rng.chance(50)) "" else "   ").append(eol)
                    rng.chance(22) -> body.append(rng.pick(shared)).append(eol)
                    else -> body.append(line(rng)).append(eol)
                }
            }
            val text = if (rng.chance(25) && body.endsWith(eol)) body.dropLast(eol.length).toString() else body.toString()
            "doc$d.md" to text
        }
    }

    private fun question(rng: Rng): String {
        val picked = List(1 + rng.next(4)) { if (rng.chance(8)) "zzznomatch" else rng.pick(words) }
        return picked.joinToString(" ") { if (rng.chance(15)) it.uppercase() else it } + (if (rng.chance(20)) "?" else "")
    }

    private fun budget(rng: Rng) = ProjectionBudget(rng.pick(listOf(40, 80, 150, 300, 700, 2_000)), rng.pick(listOf(1, 2, 3, 8, 24)), rng.pick(listOf(0, 1, 2)))

    private fun assertAgree(expected: Projection, actual: Projection, label: String) {
        assertEquals(expected.policy, actual.policy, "$label: policy")
        assertEquals(expected.question, actual.question, "$label: question")
        assertEquals(expected.charsUsed, actual.charsUsed, "$label: charsUsed")
        assertEquals(expected.omittedCandidates, actual.omittedCandidates, "$label: omittedCandidates")
        assertEquals(expected.droppedConstraints, actual.droppedConstraints, "$label: droppedConstraints")
        assertEquals(expected.excerpts.size, actual.excerpts.size, "$label: excerpt count")
        for (i in 0 until expected.excerpts.size) {
            val e = expected.excerpts[i]
            val a = actual.excerpts[i]
            assertEquals(e.document, a.document, "$label: #$i document")
            assertEquals(e.documentCid, a.documentCid, "$label: #$i documentCid")
            assertEquals(e.firstLine, a.firstLine, "$label: #$i firstLine")
            assertEquals(e.lastLine, a.lastLine, "$label: #$i lastLine")
            assertEquals(e.range, a.range, "$label: #$i range")
            assertEquals(e.text, a.text, "$label: #$i text")
            assertEquals(e.score.toRawBits(), a.score.toRawBits(), "$label: #$i score bits (${e.score} vs ${a.score})")
            assertEquals(e.reasons, a.reasons, "$label: #$i reasons")
        }
        assertEquals(expected.render(), actual.render(), "$label: rendered")
    }

    @Test
    fun theIndexedProjectorAgreesWithThePlainOneOnGeneratedCorpora() {
        val rng = Rng(0x2545F4914F6CDD1DL)
        var compared = 0
        var nonEmpty = 0
        var droppedSeen = 0
        var omittedSeen = 0
        repeat(150) { round ->
            val snapshot = snapshotOf(*corpus(rng))
            repeat(5) { k ->
                val q = question(rng)
                val b = budget(rng)
                val expected = ReferenceProjector.project(snapshot, q, b)
                val actual = BreadcrumbProjector.project(snapshot, q, b)
                assertAgree(expected, actual, "round $round question $k '$q' $b")
                compared++
                if (expected.excerpts.size > 0) nonEmpty++
                if (expected.droppedConstraints > 0) droppedSeen++
                if (expected.omittedCandidates > 0) omittedSeen++
            }
        }
        // The generator must actually reach the interesting regimes, or agreement proves little.
        assertTrue(nonEmpty > compared / 3, "too few projections selected anything: $nonEmpty of $compared")
        assertTrue(droppedSeen > 5, "too few projections dropped a constraint: $droppedSeen")
        assertTrue(omittedSeen > 20, "too few projections omitted a candidate: $omittedSeen")
    }

    @Test
    fun coldWarmAndEvictingFacetsAllAgreeWithThePlainOne() {
        val rng = Rng(0x7F4A7C15B3C6EF37L)
        // One instance with absurdly small bounds is shared by every corpus, so eviction happens between questions.
        val evicting = ProjectionFacets(maxDocumentLines = 9, maxFeatureLines = 17, maxIndexedLines = 31, maxSpines = 2, maxTerms = 5)
        repeat(80) { round ->
            val snapshot = snapshotOf(*corpus(rng))
            val warm = ProjectionFacets()
            repeat(6) { k ->
                val q = question(rng)
                val b = budget(rng)
                val expected = ReferenceProjector.project(snapshot, q, b)
                assertAgree(expected, BreadcrumbProjector.project(snapshot, q, b, warm), "warm round $round question $k '$q' $b")
                assertAgree(expected, BreadcrumbProjector.project(snapshot, q, b, ProjectionFacets()), "cold round $round question $k '$q' $b")
                assertAgree(expected, BreadcrumbProjector.project(snapshot, q, b, evicting), "evicting round $round question $k '$q' $b")
            }
            assertEquals(1, warm.counters().indexesBuilt, "six questions about one snapshot build one index")
        }
        assertTrue(evicting.counters().evictions > 0, "the evicting instance was really evicting")
    }
}
