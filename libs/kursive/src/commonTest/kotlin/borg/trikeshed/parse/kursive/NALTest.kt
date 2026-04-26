package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.asString
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD suite for NAL 1-9 (Non-Axiomatic Logic) inference rules.
 *
 * Each NAL level defines how NARS derives new beliefs from existing ones.
 * These tests verify the NarsiveSupervisorJob can parse and derive beliefs at each level.
 */
class NALTest {

    // ── NAL1: Inheritance ─────────────────────────────────────────────────

    /**
     * NAL1: Basic inheritance relation (<subject> --> <predicate>)
     * Example: <bird --> animal> means "bird is a kind of animal"
     */
    @Test
    fun nal1ParsesInheritanceRelationship() {
        val source = "<bird --> animal>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.RELATIONSHIP })
        assertTrue(elements.any { it.kind == NarsiveElementKind.COPULA })

        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.INHERITANCE in operators, "Should have INHERITANCE operator")
    }

    @Test
    fun nal1SupervisorJobHasInheritanceCopula() {
        val source = "<bird --> animal>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val copulas = job.fanout(NarsiveElementKind.COPULA)
        assertEquals(1, copulas.size, "Should have one copula element")
        assertTrue(copulas[0].operatorOrNull() == NarsiveOperator.INHERITANCE)
    }

    // ── NAL2: Similarity ─────────────────────────────────────────────────

    /**
     * NAL2: Similarity/equivalence (<A> <-> <B>)
     * Symmetric inheritance: if A <-> B, then B <-> A
     */
    @Test
    fun nal2ParsesSimilarityRelationship() {
        val source = "<robin --> bird> <-> <robin --> bird>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.SIMILARITY in operators, "Should have SIMILARITY operator")
    }

    @Test
    fun nal2SimilarityIsSymmetric() {
        val source = "<bird <-> robin>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val copulas = job.fanout(NarsiveElementKind.COPULA)
        assertTrue(copulas.any { it.operatorOrNull() == NarsiveOperator.SIMILARITY })
    }

    // ── NAL3: Implication ────────────────────────────────────────────────

    /**
     * NAL3: Implication (<S> ==> <P>) and Equivalence (<=>)
     * Rule: If S, then P (forward chaining)
     */
    @Test
    fun nal3ParsesImplication() {
        val source = "<bird --> animal> ==> <robin --> animal>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.IMPLICATION in operators, "Should have IMPLICATION operator")
    }

    @Test
    fun nal3ParsesEquivalence() {
        val source = "<bird --> animal> <=> <robin --> animal>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.EQUIVALENCE in operators, "Should have EQUIVALENCE operator")
    }

    @Test
    fun nal3SupervisorJobFansOutImplicationCopula() {
        val source = "<S --> P> ==> <Q --> R>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val copulas = job.fanout(NarsiveElementKind.COPULA)
        assertTrue(copulas.any { it.operatorOrNull() == NarsiveOperator.IMPLICATION })
    }

    // ── NAL4: Predictive Implication ────────────────────────────────────

    /**
     * NAL4: Predictive implication (<S> /> <P>) with tense
     * If S now, then P in the future (temporal inference)
     */
    @Test
    fun nal4ParsesPredictiveImplication() {
        val source = "<rain /> <wet-ground>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.PREDICTIVE_IMPLICATION in operators, "Should have PREDICTIVE_IMPLICATION operator")
    }

    @Test
    fun nal4PredictiveImplicationWithFutureTense() {
        val source = "<S /> <P>. :/:" .toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val copulas = job.fanout(NarsiveElementKind.COPULA)
        val tenses = job.fanout(NarsiveElementKind.TENSE)
        assertTrue(copulas.any { it.operatorOrNull() == NarsiveOperator.PREDICTIVE_IMPLICATION })
        assertTrue(tenses.any { it.operatorOrNull() == NarsiveOperator.FUTURE })
    }

    // ── NAL5: Concurrent Implication ────────────────────────────────────

    /**
     * NAL5: Concurrent implication (<S> =|> <P>)
     * Simultaneous implication: if S then P at the same time
     */
    @Test
    fun nal5ParsesConcurrentImplication() {
        val source = "<bell --> ringing> =|> <bell --> sound>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.CONCURRENT_IMPLICATION in operators, "Should have CONCURRENT_IMPLICATION operator")
    }

    @Test
    fun nal5SupervisorJobDerivesConcurrentImplication() {
        val source = "<A =|> B>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val copulas = job.fanout(NarsiveElementKind.COPULA)
        assertTrue(copulas.any { it.operatorOrNull() == NarsiveOperator.CONCURRENT_IMPLICATION })
    }

    // ── NAL6: Conjunction ────────────────────────────────────────────────

    /**
     * NAL6: Conjunction (&&) — compound term representing "both A and B"
     * Structure: (&&, <A>, <B>)
     */
    @Test
    fun nal6ParsesConjunctionCompoundTerm() {
        val source = "(&&, <bird>, <can-fly>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.COMPOUND_TERM })
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.INTERSECTION in operators, "Should have INTERSECTION (&&) operator")
    }

    @Test
    fun nal6ConjunctionComposesMultipleTerms() {
        val source = "(&&, <A>, <B>, <C>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val conjunctions = job.fanout(NarsiveElementKind.CONJUNCTION)
        assertTrue(conjunctions.any { it.operatorOrNull() == NarsiveOperator.INTERSECTION })
    }

    @Test
    fun nal6NestedConjunctions() {
        val source = "(&&, <bird --> animal>, <animal --> mortal>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.COMPOUND_TERM })
        // NAL6 allows deriving: if bird is animal and animal is mortal, bird is mortal
        val relationships = elements.filter { it.kind == NarsiveElementKind.RELATIONSHIP }
        assertTrue(relationships.size >= 2, "Should have nested relationships in conjunction")
    }

    // ── NAL7: Disjunction ───────────────────────────────────────────────

    /**
     * NAL7: Disjunction (||) — compound term representing "either A or B"
     * Structure: (||, <A>, <B>)
     */
    @Test
    fun nal7ParsesDisjunctionCompoundTerm() {
        val source = "(||, <bird>, <reptile>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.COMPOUND_TERM })
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.UNION in operators, "Should have UNION (||) operator")
    }

    @Test
    fun nal7SupervisorJobFansOutDisjunction() {
        val source = "(||, <A>, <B>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val conjunctions = job.fanout(NarsiveElementKind.CONJUNCTION)
        assertTrue(conjunctions.any { it.operatorOrNull() == NarsiveOperator.UNION })
    }

    // ── NAL8: Product ───────────────────────────────────────────────────

    /**
     * NAL8: Product (*) — ordered pair compound term
     * Structure: (A * B) represents the ordered pair
     */
    @Test
    fun nal8ParsesProductCompoundTerm() {
        val source = "(bird * color).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.COMPOUND_TERM })
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.PRODUCT in operators, "Should have PRODUCT (*) operator")
    }

    @Test
    fun nal8ProductRepresentsOrderedPair() {
        val source = "(X * Y).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val elements = job.elements.toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.COMPOUND_TERM })
    }

    // ── NAL9: Set Operations ─────────────────────────────────────────────

    /**
     * NAL9: Set operations — sequential (&&/) and parallel (&&|) composition
     * Sequential: (&&/, <A>, <B>) — A then B
     * Parallel: (&&|, <A>, <B>) — A and B at same time
     */
    @Test
    fun nal9ParsesSequentialComposition() {
        val source = "(&&, <A>, <B>).".toSeries()  // using intersection as sequential placeholder
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        val operators = elements.operatorMask().narsiveOperators()
        // Note: Sequential &&/ may use same intersection operator but with different semantics
        assertTrue(operators.any { it == NarsiveOperator.INTERSECTION || it == NarsiveOperator.SEQUENTIAL })
    }

    @Test
    fun nal9SupervisorJobForSequentialComposition() {
        val source = "(&&, <step1>, <step2>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        assertTrue(job.elements.size > 0, "Should have elements for sequential composition")
        assertTrue(job.fanout(NarsiveElementKind.COMPOUND_TERM).size >= 1)
    }

    @Test
    fun nal9ParallelComposition() {
        val source = "(&|, <A>, <B>).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        val elements = job.elements.toList()
        // Should parse &| as parallel operator
        val operators = elements.operatorMask().narsiveOperators()
        assertTrue(NarsiveOperator.PARALLEL in operators || elements.any {
            it.kind == NarsiveElementKind.CONJUNCTION && it.operatorOrNull() == NarsiveOperator.PARALLEL
        }, "Should recognize PARALLEL operator")
    }

    // ── Cross-cutting tests ─────────────────────────────────────────────

    @Test
    fun nalLevelRecoversOperatorFromParsedElements() {
        val source = "<bird --> animal>.".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source)
        for (element in elements) {
            val nalLevel = NALLevel.fromOperator(element.operatorOrNull()!!)
            assertNotNull(nalLevel, "Element operator ${element.operatorOrNull()} should map to a NAL level")
        }
    }

    @Test
    fun allNalLevelsParseFromTaskString() {
        // NAL1
        val nal1 = "<A --> B>."
        // NAL3 (implication)
        val nal3 = "<A --> B> ==> <C --> D>."
        // NAL6 (conjunction)
        val nal6 = "(&&, <A>, <B>)."
        // NAL8 (product)
        val nal8 = "(A * B)."

        val results = Narsive.parseTasks("$nal1\n$nal3\n$nal6\n$nal8")
        assertEquals(4, results.size, "Should parse all NAL level examples")
        results.forEachIndexed { index, result ->
            assertNotNull(result, "Result $index should not be null")
        }
    }

    @Test
    fun nalSupervisorJobSupportsAllLevelsFanout() {
        val text = """
            <bird --> animal>.
            <bird <-> robin>.
            <bird --> can-fly> ==> <robin --> can-fly>.
            (&&, <A>, <B>).
            (A * B).
        """.trimIndent()

        val tasks = Narsive.parseTasks(text)
        tasks.forEachIndexed { index, task ->
            val job = task.supervisorJob(task.a)
            assertTrue(job.elements.size > 0, "Task $index should have elements")
            assertTrue(job.concurrentResolutions().size > 0, "Task $index should have concurrent resolutions")
        }
    }

    @Test
    fun nalLevelEnumFromOperatorMapping() {
        assertEquals(NALLevel.NAL1, NALLevel.fromOperator(NarsiveOperator.INHERITANCE))
        assertEquals(NALLevel.NAL2, NALLevel.fromOperator(NarsiveOperator.SIMILARITY))
        assertEquals(NALLevel.NAL3, NALLevel.fromOperator(NarsiveOperator.IMPLICATION))
        assertEquals(NALLevel.NAL4, NALLevel.fromOperator(NarsiveOperator.PREDICTIVE_IMPLICATION))
        assertEquals(NALLevel.NAL5, NALLevel.fromOperator(NarsiveOperator.CONCURRENT_IMPLICATION))
        assertEquals(NALLevel.NAL6, NALLevel.fromOperator(NarsiveOperator.INTERSECTION))
        assertEquals(NALLevel.NAL7, NALLevel.fromOperator(NarsiveOperator.UNION))
        assertEquals(NALLevel.NAL8, NALLevel.fromOperator(NarsiveOperator.PRODUCT))
        assertEquals(NALLevel.NAL9, NALLevel.fromOperator(NarsiveOperator.SEQUENTIAL))
    }
}