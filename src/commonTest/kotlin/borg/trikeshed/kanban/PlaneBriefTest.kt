package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The brief carries the plane facts that mention the card, source before build output, bounded, plus the daemon's tick. */
class PlaneBriefTest {
    private fun row(p: String, id: String, vararg f: Pair<String, Any?>) = PlaneBrief.Row(p, id, mapOf(*f))

    private val rows = listOf(
        row("trikeshed", "projects/trikeshed/src/jvmMain/kotlin/borg/trikeshed/graal/vitals/GraalFactElement.kt", "kind" to "repository-document"),
        row("trikeshed", "projects/trikeshed/build/live/classes/borg/trikeshed/graal/vitals/GraalFactElement\$1.class"),
        row("trikeshed", "projects/trikeshed/src/commonMain/kotlin/borg/trikeshed/couch/CouchChangesFactElement.kt", "kind" to "repository-document"),
        row("graal", "vitals/memory", "kind" to "memory", "heapUsed" to 775L, "heapMax" to 9216L),
        row("graal", "gc/G1New", "kind" to "gc", "collector" to "G1New", "collections" to 7),
        row("panels", "preset-brain-mux/node/p1", "kind" to "node", "type" to "prompt.chat"),
    ) + (1..100).map { row("trikeshed", "projects/trikeshed/docs/unrelated-$it.md") }

    @Test
    fun termsDropStopwordsAndShortWords() {
        assertEquals(listOf("graalfactelement", "tick", "couch", "reconcile"), PlaneBrief.terms("name the one file to change so the GraalFactElement tick lands before the couch reconcile"))
    }

    @Test
    fun selectionScoresByTermsAndPrefersSourceOverBuild() {
        val picked = PlaneBrief.select(rows, "the GraalFactElement tick lands before the couch reconcile")
        assertEquals(3, picked.size, "only rows that mention a term: ${picked.map { it.id }}")
        assertTrue(picked[0].id.endsWith("GraalFactElement.kt"), "source first: ${picked[0].id}")
        assertTrue(picked[1].id.endsWith("CouchChangesFactElement.kt"), "the other source next: ${picked[1].id}")
        assertTrue(picked[2].id.endsWith("GraalFactElement\$1.class"), "build output last")
    }

    @Test
    fun renderIsBoundedAndCarriesTheTick() {
        val many = (1..200).map { row("trikeshed", "projects/trikeshed/src/graal-$it.kt") }
        val text = PlaneBrief.render("j1", "graal", PlaneBrief.select(many, "graal"), PlaneBrief.state(rows))
        assertEquals(PlaneBrief.MAX_ROWS, text.lines().count { it.startsWith("- trikeshed/") })
        assertTrue("graal/vitals/memory: kind=memory, heapUsed=775, heapMax=9216" in text, text)
        assertTrue("graal/gc/G1New: kind=gc, collector=G1New, collections=7" in text)
        assertTrue(text.lines().all { it.length <= PlaneBrief.MAX_LINE + 2 })
        assertTrue(text.startsWith("Card j1: graal\n"))
    }

    @Test
    fun noMentionsSaysSo() {
        val text = PlaneBrief.render("j2", "zzzz", PlaneBrief.select(rows, "zzzz"), emptyList())
        assertTrue("No fact on the daemon's plane mentions this card's terms." in text)
    }
}
