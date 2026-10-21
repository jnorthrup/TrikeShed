package borg.trikeshed.ontology

import borg.trikeshed.job.*
import kotlin.test.*

class CorpusOntologyTest {
    private val kif = "(subclass Provider Participant) (subclass Recipient Participant) (disjoint Provider Recipient)"

    @Test fun customerVocabularyHasNoImplicitSumoFallbackAndKeepsContradictions() {
        val cas = CasStore.inMemory()
        val ontology = CorpusOntology.retain(cas, "clinic", "1", kif,
            mapOf("staff" to listOf("Provider"), "mixed" to listOf("Provider", "Recipient"),
                "missing" to listOf("ExternalClass")))
        assertEquals("resolved", ontology.mapping("staff")["status"])
        assertEquals(listOf("Provider"), ontology.mapping("staff")["classes"])
        assertEquals("unresolved", ontology.mapping("Human")["status"])
        assertEquals(emptyList<String>(), ontology.mapping("Human")["closure"])
        assertEquals("conflict", ontology.mapping("mixed")["status"])
        assertEquals(listOf("ExternalClass"), ontology.mapping("missing")["unresolved"])
        assertEquals(false, ontology.mapping("staff")["causalProof"])
        assertEquals(ontology.mapping("staff"), CorpusOntology.read(cas, ontology.cid).mapping("staff"))
    }

    @Test fun explicitAlignmentHasItsOwnRetainedVersionAndDoesNotResolveUnknownCustomerTerms() {
        val cas = CasStore.inMemory()
        val alignment = CorpusOntology.retain(cas, "SUMO", "fixture-2", "(subclass Human Organism)",
            mapOf("Provider" to listOf("Human")), kind = CorpusOntology.ALIGNMENT)
        val customer = CorpusOntology.retain(cas, "clinic", "3", kif,
            mapOf("staff" to listOf("Provider")), alignmentCid = alignment.cid)
        val projected = customer.mapping("staff")["alignment"] as Map<*, *>
        assertEquals(alignment.cid.value, projected["ontologyCid"])
        assertEquals("fixture-2", projected["version"])
        val mappings = projected["mappings"] as List<*>
        assertEquals(listOf("Human"), (mappings.single() as Map<*, *>)["classes"])
        assertEquals("unresolved", customer.mapping("Human")["status"])
        assertFailsWith<IllegalArgumentException> {
            CorpusOntology.retain(cas, "clinic", "4", "(subclass Broken", emptyMap())
        }
    }
}
