package borg.trikeshed.forge.server

import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentCurationFacts
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.RdfGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualCandidateContextTest {
    @Test fun projectedLiveProposalIsContextNotReviewOrQueryWitness() {
        val proposal = PlaneFacts.fact(DocumentCurationFacts.PARTITION, "receipt/0", mapOf(
            PlaneFacts.KIND to DocumentCurationFacts.PROPOSAL, "quote" to "a retained excerpt",
            "originalCid" to "sha256:example", "begin" to 2, "sourceCurrent" to false,
            "submitted" to true))
        val iri = PlaneFacts.factIri(proposal).iri
        val unrelated = PlaneFacts.fact(DocumentCurationFacts.PARTITION, "other", mapOf(PlaneFacts.KIND to "other"))
        val live = QuadStore().load(RdfGraph(PlaneFacts.toTriples(proposal).view.toList() +
            PlaneFacts.toTriples(unrelated).view.toList())).scan()
        val missing = PlaneFacts.factIri(unrelated).iri
        val bindings = mapOf("results" to mapOf("bindings" to listOf(
            mapOf("fact" to mapOf("type" to "uri", "value" to iri)),
            mapOf("fact" to mapOf("type" to "uri", "value" to missing)),
            mapOf("fact" to mapOf("type" to "literal", "value" to iri)))))
        val out = VisualCandidateContext.extract("fact", bindings, live)
        val candidates = out["candidates"] as List<*>
        assertEquals(1, candidates.size)
        val candidate = candidates.single() as Map<*, *>
        assertEquals(iri, candidate["fact"])
        assertEquals("not-established", candidate["reviewStatus"])
        val fields = candidate["fields"] as Map<*, *>
        assertEquals(listOf("a retained excerpt"), fields["quote"])
        assertEquals(listOf("false"), fields["sourceCurrent"])
        assertFalse(fields.containsKey("submitted"), "model submission is not approval")
        assertEquals(listOf(missing), out["unresolved"])
        assertTrue((out["scope"] as String).contains("not independently verified"))
    }

    @Test fun oversizedFieldsAreNotMisrepresentedAsCompleteQuotes() {
        val proposal = PlaneFacts.fact(DocumentCurationFacts.PARTITION, "long", mapOf(
            PlaneFacts.KIND to DocumentCurationFacts.PROPOSAL, "quote" to "x".repeat(129)))
        val iri = PlaneFacts.factIri(proposal).iri
        val live = QuadStore().load(RdfGraph(PlaneFacts.toTriples(proposal).view.toList())).scan()
        val results = mapOf("results" to mapOf("bindings" to listOf(
            mapOf("fact" to mapOf("type" to "uri", "value" to iri)))))
        val c = (VisualCandidateContext.extract("fact", results, live)["candidates"] as List<*>).single() as Map<*, *>
        val f = c["fields"] as Map<*, *>
        assertEquals(emptyList<String>(), f["quote"])
        assertEquals(listOf("quote"), c["omittedFields"])
    }

    @Test fun candidateLimitAndConflictingKindRemainExplicit() {
        val facts = (0 until 5).map { i -> PlaneFacts.fact(DocumentCurationFacts.PARTITION, "candidate/$i", mapOf(
            PlaneFacts.KIND to DocumentCurationFacts.PROPOSAL, "quote" to "q$i")) }
        val store = QuadStore().load(RdfGraph(facts.flatMap { PlaneFacts.toTriples(it).view.toList() }))
        val first = PlaneFacts.factIri(facts[0]).iri
        store.add("<$first>", "<${PlaneFacts.FIELD_NS}kind>", "\"other\"")
        val second = PlaneFacts.factIri(facts[1]).iri
        store.add("<$second>", "<${PlaneFacts.FIELD_NS}quote>", "\"alternate\"")
        store.add("<$second>", "<${PlaneFacts.FIELD_NS}quote>", "\"third\"")
        val results = mapOf("results" to mapOf("bindings" to facts.map { fact ->
            mapOf("fact" to mapOf("type" to "uri", "value" to PlaneFacts.factIri(fact).iri)) }))
        val out = VisualCandidateContext.extract("fact", results, store.scan())
        assertEquals(true, out["truncated"])
        assertEquals(3, (out["candidates"] as List<*>).size)
        assertEquals(listOf(first), out["unresolved"])
        val conflict = (out["candidates"] as List<*>).first() as Map<*, *>
        assertEquals(listOf("quote"), conflict["omittedFields"])
        assertEquals(2, ((conflict["fields"] as Map<*, *>)["quote"] as List<*>).size)
    }
}
