package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifKnowledgeBase
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KifNodesTest {
    @Test fun inspectExposesLexicalBindingsWithoutCapturingFreeNames() = runTest {
        val text = " ; source formatting retained\n(forall (?X) (and (p ?X ?Free) (exists (?X) (q ?X))))"
        val out = KifNodes.runner.run(LcncNode("inspect", KifNodes.INSPECT), mapOf("text?" to text))
        assertTrue(KifNodes.runner.isPure)
        assertEquals(text, out["text"])
        assertEquals(ContentId.of(text.encodeToByteArray()).value, out["sourceCid"])
        assertEquals(listOf("?Free"), out["freeVariables"])
        assertEquals("syntax-only-unreviewed", out["status"])
        val outer = assertIs<Map<*, *>>(out["formula"])
        assertEquals("forall", outer["kind"])
        val binder = assertIs<List<*>>(outer["binders"]).single()
        val conjunction = assertIs<Map<*, *>>(outer["body"])
        val bodies = assertIs<List<*>>(conjunction["bodies"])
        val first = assertIs<Map<*, *>>(bodies[0])
        val arguments = assertIs<List<*>>(first["arguments"])
        assertEquals(binder, assertIs<Map<*, *>>(arguments[0])["binder"])
        assertEquals("free", assertIs<Map<*, *>>(arguments[1])["kind"])
        val inner = assertIs<Map<*, *>>(bodies[1])
        val innerBinder = assertIs<List<*>>(inner["binders"]).single()
        assertNotEquals(binder, innerBinder)
        val innerArgs = assertIs<List<*>>(assertIs<Map<*, *>>(inner["body"])["arguments"])
        assertEquals(innerBinder, assertIs<Map<*, *>>(innerArgs.single())["binder"])
    }

    @Test fun unsupportedModalityAndQuotationRemainVisibleWithoutAssertingAnything() = runTest {
        val bank = KifKnowledgeBase()
        bank.assertKif("(subclass Existing Root)")
        val before = bank.toKifFile()
        val node = LcncNode("inspect", KifNodes.INSPECT, params = mapOf("kif" to
            "(and (believes Agent (p ?X)) (r '(subclass A B)))"))
        val out = KifNodes.runner.run(node, emptyMap())
        assertEquals(listOf("(believes Agent (p ?X))"), out["unsupportedForms"])
        val bodies = assertIs<List<*>>(assertIs<Map<*, *>>(out["formula"])["bodies"])
        assertEquals("unsupported-formula", assertIs<Map<*, *>>(bodies[0])["kind"])
        val quoted = assertIs<Map<*, *>>(assertIs<List<*>>(assertIs<Map<*, *>>(bodies[1])["arguments"]).single())
        assertEquals("quote", quoted["kind"])
        assertEquals("(subclass A B)", quoted["source"])
        assertEquals(before, bank.toKifFile())
        assertFalse(out.keys.any { it in setOf("proofs", "review", "approved", "entailed") })
        assertEquals("syntax-only-unreviewed", out["status"])
        fun safe(value: Any?): Boolean = when (value) {
            null, is String, is Int -> true
            is Map<*, *> -> value.all { it.key is String && safe(it.value) }
            is List<*> -> value.all(::safe)
            else -> false
        }
        assertTrue(safe(out))
    }

    @Test fun wiredTextHasPriorityAndInvalidInputDoesNotSilentlyFallBack() = runTest {
        val node = LcncNode("inspect", KifNodes.INSPECT, params = mapOf("kif" to "(p Fallback)"))
        val out = KifNodes.runner.run(node, mapOf("text?" to "(p Wired)"))
        assertEquals("(p Wired)", out["canonical"])
        assertFailsWith<IllegalArgumentException> { KifNodes.runner.run(node, mapOf("text?" to 7)) }
        assertFailsWith<IllegalArgumentException> { KifNodes.runner.run(node, mapOf("text?" to "(p A) (q B)")) }
        assertFailsWith<IllegalArgumentException> { KifNodes.runner.run(node, mapOf("text?" to "(".repeat(129))) }
    }
}
