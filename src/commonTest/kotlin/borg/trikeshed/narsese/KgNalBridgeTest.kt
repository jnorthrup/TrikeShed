package borg.trikeshed.narsese

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The gap, measured: Turtle and KIF statements must cross with their copulas intact. */
class KgNalBridgeTest {

    @Test
    fun turtleTypeAndSubclassBecomeInheritance() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:tweety a ex:Bird .
            ex:Bird rdfs:subClassOf ex:Animal .
        """.trimIndent()
        val mapped = KgNalBridge.bridge(turtle)
        assertEquals(2, mapped.size)
        assertTrue(mapped.all { it.copula == NalCopula.INHERITANCE })
        assertEquals("http://example.org/tweety", mapped[0].triplet.subject)
        assertEquals("http://example.org/Bird", mapped[0].triplet.obj)
        assertTrue("-->" in mapped[0].gloss())
    }

    @Test
    fun causalPredicatesBecomeImplicationWithCausality() {
        val turtle = """
            ex:smoking ex:causes ex:cancer .
            ex:lightning ex:precedes ex:thunder .
            ex:rain ex:during ex:storm .
        """.trimIndent()
        val mapped = KgNalBridge.bridge(turtle)
        assertEquals(3, mapped.size)
        assertEquals(NalCopula.IMPLICATION, mapped[0].copula)
        assertEquals(NalCopula.PREDICTIVE_IMPLICATION, mapped[1].copula)
        assertEquals(NalCopula.CONCURRENT_IMPLICATION, mapped[2].copula)
        assertTrue(mapped.all { it.relation == RelationKind.CAUSALITY })
        // temporal copulas carry temporal signals into the belief
        val predictive = mapped[1].signal(sourceCid = "sha256:" + "0".repeat(64))
        assertTrue(predictive.temporal != null, "=/> must carry a temporal signal")
        assertEquals(TemporalGrade.RELATIVE, predictive.temporal!!.grade)
    }

    @Test
    fun turtleSemicolonAndCommaShareSubjectAndPredicate() {
        val turtle = "ex:fire ex:causes ex:smoke , ex:heat ; a ex:Process ."
        val mapped = KgNalBridge.bridge(turtle)
        assertEquals(3, mapped.size)
        assertTrue(mapped.count { it.copula == NalCopula.IMPLICATION } == 2)
        assertTrue(mapped.any { it.copula == NalCopula.INHERITANCE && it.triplet.obj == "ex:Process" })
    }

    @Test
    fun kifCrossesWithCopulasIntact() {
        val kif = """
            (instance Tweety Bird)
            (subclass Bird Animal)
            (=> (instance ?x Bird) (capableOf ?x Flight))
            (<=> Bachelor UnmarriedMan)
        """.trimIndent()
        val mapped = KgNalBridge.bridge(kif)
        assertEquals(4, mapped.size)
        assertEquals(NalCopula.INHERITANCE, mapped[0].copula)
        assertEquals(NalCopula.INHERITANCE, mapped[1].copula)
        assertEquals(NalCopula.IMPLICATION, mapped[2].copula)
        assertEquals(RelationKind.CAUSALITY, mapped[2].relation)
        assertEquals(NalCopula.SIMILARITY, mapped[3].copula)
        // nested antecedent survives as its own term text
        assertEquals("(instance ?x Bird)", mapped[2].triplet.subject)
    }

    @Test
    fun unknownPredicatesFallToProductFraming() {
        val mapped = KgNalBridge.bridge("ex:alice ex:knows ex:bob .")
        assertEquals(1, mapped.size)
        assertEquals(NalCopula.PRODUCT, mapped[0].copula)
        assertTrue(mapped[0].gloss().startsWith("(*,"), "product framing: ${mapped[0].gloss()}")
    }

    @Test
    fun contradictionPredicatesFlagTheRelation() {
        val mapped = KgNalBridge.bridge("ex:a owl:disjointWith ex:b .")
        assertEquals(RelationKind.CONTRADICTION, mapped[0].relation)
    }

    @Test
    fun contradictionMintsNegativeEvidence() {
        // disjointness is a negative assertion about the association: polarity
        // must be honest at the mint, or the refutation front (f < 0.5) never sees it
        val signal = KgNalBridge.bridge("ex:a owl:disjointWith ex:b .")[0]
            .signal(sourceCid = "sha256:" + "0".repeat(64))
        assertTrue(signal.evidence.negative > 0, "contradiction weight must land as w−")
        assertEquals(0L, signal.evidence.positive, "contradiction must mint no positive evidence")
        assertTrue(
            Nal.truthOf(signal.evidence).frequency < 0.5f,
            "contradiction belief must sit on the refutation front",
        )
    }

    @Test
    fun copulaAwareCoordinatesSeparateCausalFromTaxonomic() {
        val causal = KgNalBridge.bridge("ex:x ex:causes ex:y .")[0].signal("sha256:" + "0".repeat(64))
        val taxo = KgNalBridge.bridge("ex:x a ex:y .")[0].signal("sha256:" + "0".repeat(64))
        assertTrue(
            hamming(causal.angular, taxo.angular) >= 2,
            "same terms, different copulas must land apart in angular space",
        )
    }

    @Test
    fun hashIrisAreNotComments() {
        // Every LCNC IRI carries a fragment, and so does rdf:type itself. A `#`
        // inside <…> or "…" must survive; one outside still starts a comment.
        val turtle = """
            <https://trikeshed.borg/lcnc#n1%23value> <https://trikeshed.borg/lcnc#causes> <https://trikeshed.borg/lcnc#p1%23prompt> .
            <https://trikeshed.borg/lcnc#n1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://trikeshed.borg/lcnc/type#text.value> . # a trailing comment
            <https://trikeshed.borg/lcnc#n1> <https://trikeshed.borg/lcnc#param_text> "issue #12 is \"open\"" .
        """.trimIndent()
        val mapped = KgNalBridge.bridge(turtle)
        assertEquals(3, mapped.size, mapped.toString())
        assertEquals(NalCopula.IMPLICATION, mapped[0].copula)
        assertEquals(RelationKind.CAUSALITY, mapped[0].relation)
        assertEquals("https://trikeshed.borg/lcnc#p1%23prompt", mapped[0].triplet.obj)
        assertEquals(NalCopula.INHERITANCE, mapped[1].copula)
        assertEquals("https://trikeshed.borg/lcnc/type#text.value", mapped[1].triplet.obj)
        assertTrue("#12" in mapped[2].triplet.obj, mapped[2].triplet.obj)
    }
}
