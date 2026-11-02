package borg.trikeshed.kif

import kotlin.test.*

class KifSemanticTest {
    @Test fun shadowedBindersAreDistinctAndAlphaSafe() {
        val a = KifSemantic.decode("(forall (?X) (and (p ?X ?Free) (exists (?X) (q ?X ?Free))))")
        val b = KifSemantic.decode("(forall (?A) (and (p ?A ?Free) (exists (?B) (q ?B ?Free))))")
        val captured = KifSemantic.decode("(forall (?A) (and (p ?A ?Free) (exists (?B) (q ?A ?Free))))")
        assertTrue(a.alphaEquivalent(b))
        assertFalse(a.alphaEquivalent(captured))
        assertEquals(setOf("?Free"), a.freeVariables)
        val outer = assertIs<KifFormula.ForAll>(a.formula)
        val bodies = assertIs<KifFormula.And>(outer.body).bodies
        val inner = assertIs<KifFormula.Exists>(bodies[1])
        assertNotEquals(outer.binders.single(), inner.binders.single())
    }

    @Test fun connectivesFunctionsAndQuotedSyntaxStayStructured() {
        val text = "(=> (or (p (MeasureFn ?X 2)) (not (q ?X))) (<=> (r '(p ?Quoted)) (s \"a;()\")))"
        val decoded = KifSemantic.decode(text)
        assertEquals(setOf("?X"), decoded.freeVariables)
        assertTrue(decoded.unsupportedForms.isEmpty())
        val implication = assertIs<KifFormula.Implies>(decoded.formula)
        assertTrue(decoded.alphaEquivalent(KifSemantic.decode(decoded.syntax.toKifString())))
        val consequent = assertIs<KifFormula.Equivalent>(implication.consequent)
        val quote = assertIs<KifTerm.Quote>(assertIs<KifFormula.Predicate>(consequent.left).arguments.single())
        assertEquals("(p ?Quoted)", quote.syntax.toKifString())
    }

    @Test fun modalityAndHigherOrderFormsAreRetainedWithoutExecution() {
        val text = "(forall (?X) (believes Agent (and (p ?X) (q ?Y))))"
        val decoded = KifSemantic.decode(text)
        assertEquals(setOf("?Y"), decoded.freeVariables)
        assertEquals(1, decoded.unsupportedForms.size)
        assertIs<KifFormula.Unsupported>(assertIs<KifFormula.ForAll>(decoded.formula).body)
        assertEquals(text, decoded.syntax.toKifString())
        assertIs<KifFormula.Unsupported>(KifSemantic.decode("(?Predicate A)").formula)
    }

    @Test fun malformedAndOversizedInputsFailBeforeRecursiveDecode() {
        for (text in listOf("(p A) (q B)", "(not (p A) (q B))", "(forall (?X ?X) (p ?X))",
            "(exists (A) (p A))", "(p \"unterminated)", "(p A"))
            assertFailsWith<IllegalArgumentException>(text) { KifSemantic.decode(text) }
        assertFailsWith<IllegalArgumentException> {
            KifSemantic.decode("(".repeat(129) + "p" + ")".repeat(129))
        }
        assertFailsWith<IllegalArgumentException> {
            KifSemantic.decode("(p A B)", KifDecodeLimits(maxNodes = 2))
        }
        assertFailsWith<IllegalArgumentException> { KifSemantic.decode("\' ".repeat(129) + "(p A)") }
        assertIs<KifTerm.Literal>(assertIs<KifFormula.Predicate>(KifSemantic.decode("(p 0.10)").formula).arguments.single())
        assertEquals("(p \"(((\")", KifSemantic.decode("(p \"(((\") ; (((").syntax.toKifString())
    }
}
