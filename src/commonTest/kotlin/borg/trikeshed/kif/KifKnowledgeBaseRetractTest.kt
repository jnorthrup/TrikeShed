package borg.trikeshed.kif

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
