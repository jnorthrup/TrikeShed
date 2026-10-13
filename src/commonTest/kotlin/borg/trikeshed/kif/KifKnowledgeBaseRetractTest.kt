package borg.trikeshed.kif

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KifKnowledgeBaseRetractTest {

    private fun bindings(kb: KifKnowledgeBase, pattern: String) = kb.query(KifExpr.parse(pattern))

    @Test
    fun retractRemovesTheExactAssertionAndReportsWhetherItWasThere() {
        val kb = KifKnowledgeBase()
        kb.assertKif("(kind fact:panels/p1 cable)")
        kb.assertKif("(type fact:panels/p1 json)")
        assertEquals(2, kb.size())

        assertTrue(kb.retractKif("(kind fact:panels/p1 cable)"))
        assertEquals(1, kb.size())
        assertEquals(listOf(KifExpr.parse("(type fact:panels/p1 json)")), kb.asserts())
        assertTrue(bindings(kb, "(kind ?f cable)").isEmpty(), "retracted tuple must not unify")
        assertEquals(listOf(mapOf("?f" to "fact:panels/p1")), bindings(kb, "(type ?f json)"))

        assertFalse(kb.retractKif("(kind fact:panels/p1 cable)"), "second retract of the same tuple is not an op")
        assertFalse(kb.retractKif("(never told)"))
        assertEquals(1, kb.size())
    }

    @Test
    fun retractReopensTheDedupeSoTheTupleCanBeToldAgain() {
        val kb = KifKnowledgeBase()
        val e = KifExpr.parse("(kind fact:graal/gc%2FG1 gc)")
        kb.assert(e)
        kb.assert(e)
        assertEquals(1, kb.size(), "exact duplicates are dropped")
        assertTrue(kb.retract(e))
        assertEquals(0, kb.size())
        kb.assert(e)
        assertEquals(1, kb.size(), "after a retract the same tuple is new again")
        assertEquals("(kind fact:graal/gc%2FG1 gc)", kb.toKifFile())
    }

    @Test
    fun queryClosureIsRecomputedFromWhatRemains() {
        val kb = KifKnowledgeBase()
        kb.assertKif("(subclass Dog Mammal)")
        kb.assertKif("(subclass Mammal Animal)")
        val before = bindings(kb, "(subclass Dog ?p)").map { it.getValue("?p") }.toSet()
        assertEquals(setOf("Mammal", "Animal"), before, "transitive closure before the retract")

        assertTrue(kb.retractKif("(subclass Mammal Animal)"))
        val after = bindings(kb, "(subclass Dog ?p)").map { it.getValue("?p") }.toSet()
        assertEquals(setOf("Mammal"), after, "the inferred edge goes with its support")
    }

    @Test
    fun predicateSnapshotsFollowOnlyTheirOwnMutations() {
        val kb = KifKnowledgeBase()
        assertTrue(kb.asserts("inKind").isEmpty())
        kb.assertKif("(inKind node x TEXT)")
        val selected = kb.asserts("inKind")
        kb.assertKif("(heapUsed fact:graal/memory 123)")
        kb.assertKif("(inKind node x TEXT)")
        assertSame(selected, kb.asserts("inKind"), "unrelated writes and duplicate assertions retain the view")
        kb.replace(listOf(KifExpr.parse("(inKind node x TEXT)")), listOf(KifExpr.parse("(inKind node y JSON)")))
        assertEquals(listOf("(inKind node y JSON)"), kb.asserts("inKind").view.map { it.toKifString() })
        assertEquals("(inKind node x TEXT)", selected[0].toKifString(), "previous readers retain their immutable snapshot")
        assertTrue(kb.retractKif("(inKind node y JSON)"))
        assertTrue(kb.asserts("inKind").isEmpty())
    }

    @Test
    fun predicateQueriesPreserveOrderArityBindingsAndVariableHeads() {
        val kb = KifKnowledgeBase()
        listOf("(p a a)", "(q b b)", "(p a b)", "(p c c)", "(p short)").forEach(kb::assertKif)
        assertEquals(listOf(mapOf("?x" to "a"), mapOf("?x" to "c")), bindings(kb, "(p ?x ?x)"))
        assertEquals(listOf("p", "q", "p"), bindings(kb, "(?head ?x ?x)").map { it.getValue("?head") })
        assertTrue(kb.retractKif("(p a a)"))
        kb.assertKif("(p a a)")
        assertEquals(listOf(mapOf("?x" to "c"), mapOf("?x" to "a")), bindings(kb, "(p ?x ?x)"))
        assertEquals(5, kb.size(), "selection never removes other predicates")
    }

    @Test
    fun coldAndEvictedPredicateViewsDoNotReadUnrelatedExpressions() {
        val kb = KifKnowledgeBase()
        var unrelatedReads = 0
        val elements = listOf(KifExpr.Atom("q"), KifExpr.Atom("unrelated"))
        kb.assert(KifExpr.ListExpr(object : AbstractList<KifExpr>() {
            override val size: Int get() = elements.size
            override fun get(index: Int): KifExpr {
                unrelatedReads++
                return elements[index]
            }
        }))
        val first = KifExpr.parse("(p first)")
        val second = KifExpr.parse("(p second)")
        kb.assert(first)
        kb.assert(second)
        repeat(129) { kb.assertKif("(p$it value)") }
        unrelatedReads = 0

        val cold = kb.asserts("p")
        assertEquals(2, cold.size)
        assertSame(first, cold[0], "index shares the held expression")
        assertSame(second, cold[1])
        repeat(129) { assertEquals(1, kb.asserts("p$it").size) }
        assertEquals(listOf(first, second), kb.asserts("p").view.toList(), "eviction preserves telling order")
        kb.retract(first)
        kb.assert(first)
        assertEquals(listOf(second, first), kb.asserts("p").view.toList())
        assertEquals(0, unrelatedReads, "cold, evicted and invalidated views must use predicate membership")
        assertSame(first, cold[0], "retraction does not change a previously returned view")
    }

    @Test
    fun retractionUsesTheStoredExpressionForPredicateAndClosureInvalidation() {
        val kb = KifKnowledgeBase()
        kb.assertKif("(subclass Dog Mammal)")
        kb.assertKif("(subclass Mammal Animal)")
        assertEquals(2, bindings(kb, "(subclass Dog ?p)").size)
        // Exact serialization is the bank's key, even if the caller uses a different expression shape.
        assertTrue(kb.retract(KifExpr.Atom("(subclass Mammal Animal)")))
        assertEquals(listOf("(subclass Dog Mammal)"), kb.asserts("subclass").view.map { it.toKifString() })
        assertEquals(listOf(mapOf("?p" to "Mammal")), bindings(kb, "(subclass Dog ?p)"))
        kb.retractKif("(subclass Dog Mammal)")
        assertTrue(kb.asserts("subclass").isEmpty())
        kb.assertKif("(subclass Bird Animal)")
        assertEquals(listOf(mapOf("?x" to "Bird")), bindings(kb, "(subclass ?x Animal)"))
        assertEquals(1, kb.size())
    }
}

class KifKnowledgeBaseReplaceTest {
    @Test
    fun replaceIsOneStepAndCountsOnlyRealChanges() {
        val kb = KifKnowledgeBase()
        kb.assertKif("(a 1)"); kb.assertKif("(b 2)"); kb.assertKif("(c 3)")
        val changed = kb.replace(
            gone = listOf(KifExpr.parse("(b 2)"), KifExpr.parse("(never 0)")),
            told = listOf(KifExpr.parse("(c 3)"), KifExpr.parse("(d 4)")),
        )
        assertEquals(2, changed, "one real retract + one real assert")
        assertEquals("(a 1)\n(c 3)\n(d 4)", kb.toKifFile(), "telling order kept; the new tuple goes last")
    }

    @Test
    fun retractStaysFlatAtSize() {
        val kb = KifKnowledgeBase()
        val n = 20_000
        for (i in 0 until n) kb.assertKif("(fact f$i v$i)")
        assertEquals(n, kb.size())
        for (i in 0 until n step 2) assertTrue(kb.retractKif("(fact f$i v$i)"))
        assertEquals(n / 2, kb.size())
        assertEquals("(fact f1 v1)", kb.asserts().first().toKifString())
        assertEquals("(fact f${n - 1} v${n - 1})", kb.asserts().last().toKifString())
    }
}
