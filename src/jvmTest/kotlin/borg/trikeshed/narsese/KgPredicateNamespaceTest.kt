package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*
import kotlin.test.*

/** Exact predicate identity controls interpretation; synthetic inputs do not assert world truth. */
class KgPredicateNamespaceTest {
    private val context = ContentId.of("synthetic namespace boundary".encodeToByteArray())
    private fun mapped(predicate: String) = KgNalBridge.map(KgTriplet("Grant", predicate, "Payment"))
    private fun opaque(predicate: String) {
        val value = mapped(predicate)
        assertEquals(NalCopula.PRODUCT, value.copula, predicate)
        assertEquals(RelationKind.MATCH, value.relation, predicate)
        assertTrue(value.gloss().endsWith(" --> $predicate"), "Opaque predicate remains in its product frame")
        val signal = value.signal(context.value)
        assertTrue(signal.evidence.positive > 0)
        assertEquals(0L, signal.evidence.negative, "An opaque 'not' is not a negative assertion")
    }

    @Test fun genuineExpandedRdfRdfsAndOwlPredicatesKeepTheirDeclaredNalProjection() {
        for (predicate in s_[RdfVocab.RDF + "type", RdfVocab.RDFS + "subClassOf", RdfVocab.RDFS + "subPropertyOf"].view) {
            assertEquals(NalCopula.INHERITANCE, mapped(predicate).copula)
            assertEquals(RelationKind.MATCH, mapped(predicate).relation)
        }
        for (predicate in s_[RdfVocab.OWL + "sameAs", RdfVocab.OWL + "equivalentClass"].view) {
            assertEquals(NalCopula.SIMILARITY, mapped(predicate).copula)
            assertEquals(RelationKind.MATCH, mapped(predicate).relation)
        }
        val disjoint = mapped(RdfVocab.OWL + "disjointWith")
        assertEquals(NalCopula.SIMILARITY, disjoint.copula)
        assertEquals(RelationKind.CONTRADICTION, disjoint.relation)
        assertEquals(0L, disjoint.signal(context.value).evidence.positive)
        assertTrue(disjoint.signal(context.value).evidence.negative > 0)
    }

    @Test fun arbitraryNamespacesAndCaseLookalikesCannotSupplyTypeCausalityOrNegation() {
        for (namespace in s_["https://legal.example/vocabulary#", "https://legal.example/vocabulary/", "urn:legal:"].view)
            for (suffix in s_["type", "TYPE", "subClassOf", "sameAs", "equivalentClass", "causes", "not", "disjointWith"].view)
                opaque(namespace + suffix)
        for (predicate in s_[RdfVocab.RDF + "TYPE", RdfVocab.RDFS + "subclassof", RdfVocab.OWL + "sameas",
            RdfVocab.OWL + "disjointwith", "https://www.w3.org/1999/02/22-rdf-syntax-ns#type",
            "http://www.w3.org/1999/02/22-RDF-syntax-ns#type", "rdf:type", "rdfs:subClassOf", "owl:sameAs"].view)
            opaque(predicate)
    }

    @Test fun strictRdfExpansionUsesTheNamespaceInsteadOfThePrefixName() {
        val fake = KgNalBridge.bridge("@prefix rdf: <https://legal.example/vocabulary#> .\n<urn:s> rdf:type <urn:C> .").single()
        assertEquals("https://legal.example/vocabulary#type", fake.triplet.predicate)
        assertEquals(NalCopula.PRODUCT, fake.copula)
        val actual = KgNalBridge.bridge("@prefix arbitrary: <${RdfVocab.RDF}> .\n<urn:s> arbitrary:type <urn:C> .").single()
        assertEquals(RdfVocab.RDF + "type", actual.triplet.predicate)
        assertEquals(NalCopula.INHERITANCE, actual.copula)
        assertEquals(NalCopula.INHERITANCE, KgNalBridge.bridge("<urn:s> a <urn:C> .").single().copula)
    }

    @Test fun customCausalAndNegativeRdfCannotBecomeEternalRulesOrNegativeEvidence() {
        for (predicate in s_["https://legal.example/vocabulary#causes", "https://legal.example/vocabulary#not"].view) {
            val text = "<urn:s> <$predicate> <urn:o> ."
            val observed = KgNalBridge.bridge(text).single()
            assertEquals(NalCopula.PRODUCT, observed.copula)
            assertEquals(RelationKind.MATCH, observed.relation)
            assertEquals(0L, observed.signal(context.value).evidence.negative)
            val rules = KgNalBridge.bridgeToRules(text)
            assertEquals(1, rules.size)
            assertFalse(rules[0].isEternal, "Unknown RDF predicates must not bypass the existing causal rule gate")
        }
        assertTrue(KgNalBridge.bridgeToRules("(causes Rain Flood)")[0].isEternal, "Bare KIF cause remains an explicit policy alias")
        val logical = KgNalBridge.tripletsToEternalRules(_l[KgTriplet("Rain", "implies", "Flood")])
        assertFalse(logical[0].isEternal, "Logical implication still cannot enter the causal-only rete")
        assertFailsWith<IllegalArgumentException> { KgNalBridge.bridgeToRules("(implies Rain Flood)") }
    }

    @Test fun barePolicyAliasesRemainWholeTokensWithoutIriOrWhitespaceRepair() {
        val groups = s_[
            s_["a", "type", "instance", "instance-of", "isa", "is-a", "subclassof", "subclass", "subclass-of", "subpropertyof", "INSTANCE_OF"] j
                (NalCopula.INHERITANCE j RelationKind.MATCH),
            s_["sameas", "same-as", "equivalent", "equivalentclass", "equal", "<=>"] j (NalCopula.SIMILARITY j RelationKind.MATCH),
            s_["causes", "cause", "caused", "leadsto", "leads-to", "results-in", "CAUSES"] j (NalCopula.IMPLICATION j RelationKind.CAUSALITY),
            s_["implies", "entails", "=>"] j (NalCopula.IMPLICATION j RelationKind.MATCH),
            s_["contradicts", "disjointwith", "disjoint-with", "not"] j (NalCopula.SIMILARITY j RelationKind.CONTRADICTION)]
        for ((aliases, expected) in groups.view) for (alias in aliases.view) {
            assertEquals(expected.a, mapped(alias).copula, alias)
            assertEquals(expected.b, mapped(alias).relation, alias)
        }
        for (predicate in s_[" type", "type ", "\tcauses", "not\n", "\u00a0not", "<${RdfVocab.RDF}type>",
            " ${RdfVocab.RDF}type", RdfVocab.OWL + "sameAs "].view) opaque(predicate)
    }

    @Test fun ownedLcncCausalProjectionKeepsOnlyItsExactDeclaredPredicate() {
        val program = LcncProgram("synthetic causal projection", s_[LcncNode("a", "text.value"), LcncNode("b", "display")],
            s_[LcncWire("a", "value", "b", "x")])
        val triples = LcncRdf.causalProjection(program)
        val cable = triples.single { it.p.iri == LcncRdf.NS + "causes" }
        val value = KgNalBridge.map(KgTriplet(cable.s.toTurtle(), cable.p.iri, cable.o.toTurtle()))
        assertEquals(NalCopula.IMPLICATION, value.copula)
        assertEquals(RelationKind.CAUSALITY, value.relation)
        assertTrue(KgNalBridge.tripletsToEternalRules(_l[value.triplet])[0].isEternal)
        opaque(LcncRdf.NS + "CAUSES")
        opaque(LcncRdf.NS + "not")
        opaque(LcncRdf.KIND_NS + "causes")
    }
}
