package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReteAlphaMemoryTest {

    private val board = blackboardContext(id = "board-a")

    private fun fact(status: String, version: String = status): ReteStoredFact = ReteStoredFact(
        factId = FactId("board-a", "job-1"),
        fields = mapOf("status" to status, "kind" to "job"),
        versionCid = ContentId.of(version.encodeToByteArray()),
        board = board,
    )

    @Test
    fun equalPredicatesShareOneAlphaNodeAndOneEvaluation() {
        val alpha = ReteAlphaMemory()
        val predicate = AlphaPredicate("status", "ready")

        val firstRuleNode = alpha.register(predicate)
        val secondRuleNode = alpha.register(AlphaPredicate("status", "ready"))
        assertSame(firstRuleNode, secondRuleNode, "equal rule conditions must share an alpha node")

        alpha.accept(fact("ready"))

        assertEquals(1L, firstRuleNode.evaluationCount,
            "shared predicate must be evaluated once per asserted fact")
        assertEquals(listOf(FactId("board-a", "job-1")),
            firstRuleNode.facts().map { it.factId })
    }

    @Test
    fun modifiedFactMovesBetweenAlphaMemories() {
        val alpha = ReteAlphaMemory()
        val ready = alpha.register(AlphaPredicate("status", "ready"))
        val active = alpha.register(AlphaPredicate("status", "active"))

        alpha.accept(fact("ready", "v1"))
        alpha.accept(fact("active", "v2"))

        assertEquals(emptyList(), ready.facts())
        assertEquals(listOf(FactId("board-a", "job-1")), active.facts().map { it.factId })
    }

    @Test
    fun retractRemovesFactFromEveryMatchingAlphaNode() {
        val alpha = ReteAlphaMemory()
        val status = alpha.register(AlphaPredicate("status", "ready"))
        val kind = alpha.register(AlphaPredicate("kind", "job"))
        val asserted = fact("ready")
        alpha.accept(asserted)

        alpha.retract(asserted.factId)

        assertEquals(emptyList(), status.facts())
        assertEquals(emptyList(), kind.facts())
    }
}
