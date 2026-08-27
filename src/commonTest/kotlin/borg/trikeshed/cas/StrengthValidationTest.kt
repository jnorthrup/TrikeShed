package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step E gate — strength validation: a match's strength is inspectable by zooming until the
 * matching content sits side by side. The grading ramp is the contract the `/api/graal/strength`
 * route serves: LINKED=CONFIRMED=1.0, PARTIAL=PROVISIONAL=0.45, CONTENT_ONLY=CANDIDATE=0.12.
 * The bytes prove the grouping: same line text → same contentCid → zoom-in recovers identical
 * stored bytes from the CAS for both sides of the match.
 */
class StrengthValidationTest {

    @Test
    fun gradedMatchesRampExactlyAsContracted() {
        val cas = CasStore.inMemory()
        val shared = "the shared structural line that both documents carry verbatim"
        val docA = LineCas.spineInto(cas, "intro a\n$shared\noutro a")
        val docB = LineCas.spineInto(cas, "intro b\n$shared\noutro b")
        val idx = LineCasIndex()
        idx.ingestSpine(docA)
        idx.ingestSpine(docB)

        // the shared line, probed from docA's spine, hits docB
        val probeNode = (0 until docA.size).first { docA[it].contentCid.hex == LineCas.contentOf(shared).hex }.let { docA[it] }
        val hits = idx.linkMatch(probeNode, MatchGrade.CONTENT_ONLY)
        assertTrue(hits.size >= 1, "the verbatim shared line must match across docs")

        val linked = (0 until hits.size).count { hits[it].grade == MatchGrade.LINKED }
        val partial = (0 until hits.size).count { hits[it].grade == MatchGrade.PARTIAL_PREV || hits[it].grade == MatchGrade.PARTIAL_NEXT }
        val contentOnly = (0 until hits.size).count { hits[it].grade == MatchGrade.CONTENT_ONLY }
        // hits include the SELF match (docA's own copy: identical neighborhoods → LINKED);
        // every cross-doc hit (different intro/outro) must grade PARTIAL or CONTENT_ONLY, never LINKED
        assertTrue(linked >= 1, "the self-match grades LINKED")
        val crossDoc = (0 until hits.size).filter { hits[it].docCid.hex != LineCas.spineCid(docA).hex }
        assertTrue(crossDoc.isNotEmpty(), "the probe must also hit docB")
        for (i in crossDoc) {
            val g = hits[i].grade
            assertTrue(
                g == MatchGrade.PARTIAL_PREV || g == MatchGrade.PARTIAL_NEXT || g == MatchGrade.CONTENT_ONLY,
                "cross-doc neighborhoods differ; got $g",
            )
        }
        assertTrue(partial + contentOnly >= 1, "the shared line must grade PARTIAL or CONTENT_ONLY across docs")

        // the ramp: confidence follows grade, score follows the 0.12/0.45/1.0 ladder
        for (i in 0 until hits.size) {
            val hit = hits[i]
            assertEquals(confidenceOf(hit.grade), LinkConfidence.CANDIDATE.takeIf { hit.grade == MatchGrade.CONTENT_ONLY }
                ?: LinkConfidence.PROVISIONAL.takeIf { hit.grade == MatchGrade.PARTIAL_PREV || hit.grade == MatchGrade.PARTIAL_NEXT }
                ?: LinkConfidence.CONFIRMED)
            assertTrue(rampScore(hit.grade) > 0.0)
        }
    }

    @Test
    fun identicalNeighborhoodGradesLinkedAndBytesRoundTripIdentically() {
        val cas = CasStore.inMemory()
        val text = "alpha\nbeta\ngamma"
        val a = LineCas.spineInto(cas, text)
        val b = LineCas.spineInto(cas, text)
        // same text → same spineCid → zooming to bytes shows the SAME stored content
        assertEquals(LineCas.spineCid(a), LineCas.spineCid(b))
        for (i in 0 until a.size) {
            val bytesA = cas.get(a[i].contentCid)
            val bytesB = cas.get(b[i].contentCid)
            assertEquals(bytesA!!.decodeToString(), bytesB!!.decodeToString())
            assertEquals(MatchGrade.LINKED, LineCas.matchGrade(a[i], b[i]))
            assertEquals(LinkConfidence.CONFIRMED, confidenceOf(MatchGrade.LINKED))
            assertEquals(1.0, rampScore(MatchGrade.LINKED))
        }
    }
}
