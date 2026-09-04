package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The plane judges: MET needs every MUST met with an evidence id that exists; a person is asked only when asked for. */
class PlaneJudgeTest {
    private val spec = PlaneBrief.parseSpec("t", "MUST: name the file\nMUST: cite the tick\nSHOULD: be brief")
    private val plane = setOf("trikeshed/src/A.kt", "graal/vitals/memory", "board/c1")

    private fun decide(reply: String, human: Boolean = false, ok: Boolean = true, s: PlaneBrief.Spec = spec) =
        PlaneJudge.decide(s, human, ok, reply, plane)

    @Test
    fun parsesTheReplyShapeLoosely() {
        val r = PlaneJudge.parse("VERDICT: MET\nMUST-1: MET — evidence: trikeshed/src/A.kt\nMUST-2: MET evidence=graal/vitals/memory.\nSHOULD-1: NOT MET — evidence: none\nACTION: Change A.kt so the tick lands first.\nSecond line.")!!
        assertEquals("MET", r.verdict)
        assertEquals(listOf("MUST-1" to "trikeshed/src/A.kt", "MUST-2" to "graal/vitals/memory", "SHOULD-1" to ""), r.lines.map { it.label to it.evidence })
        assertEquals(listOf(true, true, false), r.lines.map { it.met })
        assertEquals("Change A.kt so the tick lands first.\nSecond line.", r.action)
        assertNull(PlaneJudge.parse("Next: split it into a test."), "prose without a VERDICT is not a reply")
    }

    @Test
    fun doneOnlyWhenEveryMustHasEvidenceOnThePlane() {
        assertEquals(PlaneJudge.Outcome.DONE, decide("VERDICT: MET\nMUST-1: MET — evidence: trikeshed/src/A.kt\nMUST-2: MET — evidence: graal/vitals/memory\nACTION: x").outcome)
        assertEquals(PlaneJudge.Outcome.DONE, decide("VERDICT: MET\nMUST-1: MET — evidence: src/A.kt\nMUST-2: MET — evidence: vitals/memory\nACTION: x").outcome, "partition may be dropped")
        val noEv = decide("VERDICT: MET\nMUST-1: MET — evidence: none\nMUST-2: MET — evidence: graal/vitals/memory\nACTION: x")
        assertEquals(PlaneJudge.Outcome.RETRY, noEv.outcome); assertEquals("MUST-1 MET without evidence", noEv.reason)
        val ghost = decide("VERDICT: MET\nMUST-1: MET — evidence: trikeshed/src/A.kt\nMUST-2: MET — evidence: graal/vitals/ghost\nACTION: x")
        assertEquals(PlaneJudge.Outcome.RETRY, ghost.outcome); assertEquals("MUST-2 evidence 'graal/vitals/ghost' is not a fact on the plane", ghost.reason)
        assertEquals(PlaneJudge.Outcome.RETRY, decide("VERDICT: MET\nMUST-1: MET — evidence: trikeshed/src/A.kt\nACTION: x").outcome, "a MUST left unanswered")
        assertEquals(PlaneJudge.Outcome.RETRY, decide("VERDICT: NOT-MET\nMUST-1: NOT-MET — evidence: none\nACTION: x").outcome)
        assertEquals(PlaneJudge.Outcome.RETRY, decide("I did the thing.").outcome, "no verdict line")
        assertEquals(PlaneJudge.Outcome.RETRY, decide("VERDICT: MET", ok = false).outcome, "a failed brain call")
    }

    @Test
    fun aPersonIsAskedOnlyWhenAskedFor() {
        val met = "VERDICT: MET\nMUST-1: MET — evidence: trikeshed/src/A.kt\nMUST-2: MET — evidence: graal/vitals/memory\nACTION: x"
        assertEquals(PlaneJudge.Outcome.REVIEW, decide(met, human = true).outcome, "tagged human-review")
        assertEquals(PlaneJudge.Outcome.REVIEW, decide(met, s = PlaneBrief.parseSpec("t", "MUST: a\nREVIEW: human")).outcome, "REVIEW: human on the spec")
        assertEquals(PlaneJudge.Outcome.REVIEW, decide("VERDICT: NEEDS-HUMAN\nMUST-1: NOT-MET — evidence: none\nACTION: ask Jim").outcome)
    }
}
