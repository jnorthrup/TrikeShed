package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The RFC brief: parsed spec, evidence from the plane (source first, bounded), daemon state, lessons, reply shape. */
class PlaneBriefTest {
    private fun row(p: String, id: String, vararg f: Pair<String, Any?>) = PlaneBrief.Row(p, id, mapOf(*f))

    private val rows = listOf(
        row("trikeshed", "projects/trikeshed/src/jvmMain/kotlin/borg/trikeshed/graal/vitals/GraalFactElement.kt", "kind" to "repository-document"),
        row("trikeshed", "projects/trikeshed/build/live/classes/borg/trikeshed/graal/vitals/GraalFactElement\$1.class"),
        row("trikeshed", "projects/trikeshed/src/commonMain/kotlin/borg/trikeshed/couch/CouchChangesFactElement.kt", "kind" to "repository-document"),
        row("graal", "vitals/memory", "kind" to "memory", "heapUsed" to 775L * 1_048_576, "heapMax" to 9216L * 1_048_576),
        row("graal", "gc/G1New", "kind" to "gc", "collector" to "G1New", "collections" to 7),
        row("panels", "preset-brain-mux/node/p1", "kind" to "node", "type" to "prompt.chat"),
    ) + (1..100).map { row("trikeshed", "projects/trikeshed/docs/unrelated-$it.md") }

    @Test
    fun specParsesRfcLinesAndDefaultsOneMust() {
        val s = PlaneBrief.parseSpec("t", "GOAL: reaper wired\nMUST: name the file\nmust: cite a fact\nSHOULD: keep it short\nOUT-OF-SCOPE: the UI\nREVIEW: human\nMODEL: glm-5.3\nTOKENS: 512\nsome prose")
        assertEquals("reaper wired", s.goal)
        assertEquals(listOf("MUST-1", "MUST-2", "SHOULD-1"), s.criteria.map { it.label })
        assertEquals(listOf("the UI"), s.outOfScope); assertTrue(s.humanReview); assertEquals("glm-5.3", s.model); assertEquals(512, s.tokens)
        val d = PlaneBrief.parseSpec("title only", "")
        assertEquals("title only", d.goal); assertEquals(1, d.musts.size); assertEquals(PlaneBrief.DEFAULT_MUST, d.musts[0].text); assertTrue(!d.humanReview)
    }

    @Test
    fun selectionScoresByTermsAndPrefersSourceOverBuild() {
        val picked = PlaneBrief.select(rows, "the GraalFactElement tick lands before the couch reconcile")
        assertEquals(3, picked.size, "only rows that mention a term: ${picked.map { it.id }}")
        assertTrue(picked[0].id.endsWith("GraalFactElement.kt"), "source first: ${picked[0].id}")
        assertTrue(picked[1].id.endsWith("CouchChangesFactElement.kt"), "the other source next: ${picked[1].id}")
        assertTrue(picked[2].id.endsWith("GraalFactElement\$1.class"), "build output last")
        assertEquals(PlaneBrief.MAX_EVIDENCE, PlaneBrief.select((1..200).map { row("trikeshed", "projects/trikeshed/src/graal-$it.kt") }, "graal").size)
    }

    @Test
    fun renderIsAnRfcWithEvidenceStateLessonsAndTheReplyShape() {
        val spec = PlaneBrief.parseSpec("GraalFactElement tick", "MUST: name the file\nSHOULD: cite the tick")
        val lessons = PlaneBrief.lessons(listOf(
            PlaneBrief.Receipt("glm-5.3-flash", false, "provider billed 256 completion tokens but returned no content"),
            PlaneBrief.Receipt("glm-5.3-flash", true, ""), PlaneBrief.Receipt("nemotron", true, ""),
        ))
        val text = PlaneBrief.render("j1", "GraalFactElement tick", spec, PlaneBrief.select(rows, "GraalFactElement"), PlaneBrief.state(rows), lessons)
        assertTrue(text.startsWith("Card j1 — brief (RFC 2119: MUST, SHOULD, MAY)\nGOAL: GraalFactElement tick\nACCEPTANCE:\n  MUST-1: name the file\n  SHOULD-1: cite the tick\n"), text)
        assertTrue("  trikeshed/projects/trikeshed/src/jvmMain/kotlin/borg/trikeshed/graal/vitals/GraalFactElement.kt  (repository-document)" in text, text)
        assertTrue("DAEMON: heap 775/9216 MB · G1New 7 gcs" in text, text)
        assertTrue("AVOID  glm-5.3-flash: 1 of 2 claims failed — last: provider billed 256" in text, text)
        assertTrue("DO     nemotron: 1 of 1 claims answered" in text, text)
        assertTrue("READY --claim--> RUNNING" in text)
        assertTrue("  MUST-1: MET | NOT-MET — evidence: <one id from EVIDENCE, or none>" in text, text)
        assertTrue(text.lines().all { it.length <= PlaneBrief.MAX_LINE + 4 }, "no line wider than the cap")
        assertTrue(text.lines().none { it.contains("heapUsed=") }, "no field dumps")
    }
}
