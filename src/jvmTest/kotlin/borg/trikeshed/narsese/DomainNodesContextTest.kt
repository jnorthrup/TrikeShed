package borg.trikeshed.narsese

import borg.trikeshed.context.ElementState
import borg.trikeshed.dag.JobDependencyProduction
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.BrainClientKey
import borg.trikeshed.lcnc.CasStoreKey
import borg.trikeshed.lcnc.DocumentCurationLegos
import borg.trikeshed.lcnc.DocumentCurationRunnerKey
import borg.trikeshed.lcnc.KifKnowledgeBaseKey
import borg.trikeshed.lcnc.KifSinkKey
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeKey
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncServiceBinding
import borg.trikeshed.lcnc.LegalCitationsKey
import borg.trikeshed.lcnc.MuxContextKey
import borg.trikeshed.lcnc.ReteNetworkKey
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DomainNodesContextTest {
    private class ModelRoute(val name: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ModelRoute>
    }

    private fun brain(answer: suspend () -> String) = object : BrainClient() {
        override suspend fun chat(
            messages: List<Pair<String, String>>,
            maxTokens: Int,
            temperature: Double,
            contextId: String?,
        ): String = answer()
    }

    private fun keys(runner: LcncNodeRunner, vararg expected: CoroutineContext.Key<*>) {
        val binding = runner as LcncServiceBinding
        assertEquals(expected.toSet(), binding.requiredKeys.view.toSet())
        assertEquals(expected.toSet(), binding.providedKeys.view.toSet())
    }

    @Test
    fun constructionAndMintResolveAllProvidersAndKeepCallerContext() = runBlocking {
        val originalBag = BeliefBagElement(capacity = 32)
        val bag = BeliefBagElement(capacity = 32)
        originalBag.open()
        bag.open()
        val muxJob = Job()
        try {
            val originalCas = CasStore.inMemory()
            val cas = CasStore.inMemory()
            val originalRete = ReteNetwork()
            val rete = ReteNetwork()
            // A network starts with the job production. Provider isolation means
            // preserving that baseline, not requiring an empty registry.
            val originalProductions = originalRete.productions.all()
            val selectedProductions = rete.productions.all()
            assertIs<JobDependencyProduction>(originalProductions.single())
            assertIs<JobDependencyProduction>(selectedProductions.single())
            val originalKif = mutableListOf<String>()
            val kif = mutableListOf<String>()
            val line = "If fire starts then sprinklers activate."
            val cid = ContentId.of(line.encodeToByteArray())
            val selectedRoute = ModelRoute("selected")
            val callerRoute = ModelRoute("caller")
            var expectedRoute = selectedRoute
            var type = "read.construct"
            var calls = 0
            val selectedBrain = brain {
                calls++
                val context = currentCoroutineContext()
                assertSame(bag, context[BeliefBagElement.Key])
                assertSame(cas, context[CasStoreKey]!!.value)
                assertSame(rete, context[ReteNetworkKey]!!.value)
                assertSame(expectedRoute, context[ModelRoute])
                assertNotSame(muxJob, context[Job])
                assertEquals(ElementState.ACTIVE, context[LcncNodeKey.of(type)!!]!!.state)
                assertEquals(line, cas.get(cid)!!.decodeToString())
                """{"constructions":[{"subject":"fire starts","relation":"if_then","object":"sprinklers activate","polarity":true,"evidenceCid":"${cid.value}","dependency":"mark:if"}]}"""
            }
            val originalBrain = brain { error("captured model used") }
            val construct = ConstructionBotNode.runner(originalBrain, ModelRoute("default"), originalCas, originalBag, originalRete) { originalKif.add(it) }
            val mint = NalNodes.mintRunner(originalBrain, ModelRoute("default"), originalCas, originalBag, originalRete) { originalKif.add(it) }
            val providers = bag + BrainClientKey(selectedBrain) + MuxContextKey(muxJob + selectedRoute) +
                CasStoreKey(cas) + ReteNetworkKey(rete) + KifSinkKey { kif.add(it) }
            for (runner in arrayOf(construct, mint)) {
                keys(runner, BrainClientKey, MuxContextKey, CasStoreKey, ReteNetworkKey, KifSinkKey, BeliefBagElement.Key)
                val context = if (type == "nal.mint") providers + callerRoute else providers
                val output = withContext(context) { runner.run(LcncNode("n", type), mapOf("lines" to listOf(line))) }
                assertEquals(1, (output["accepted"] as List<*>).size)
                assertEquals(originalProductions, originalRete.productions.all(), "Captured provider must remain unchanged")
                val added = rete.productions.all().filter { it !in selectedProductions }
                val construction = assertIs<ConstructionReteProduction>(added.single())
                val identity = ContentId.of("if_then\u0000fire starts\u0000sprinklers activate".encodeToByteArray())
                assertEquals("construction:${identity.hex}", construction.ruleId)
                assertEquals(selectedProductions + construction, rete.productions.all(), "Keep boot rules and register exactly one construction across rereads")
                type = "nal.mint"
                expectedRoute = callerRoute
            }
            withTimeout(5_000) { while (bag.size != 1) delay(1) }
            assertEquals(2, calls)
            assertEquals(0, originalBag.size)
            assertNull(originalCas.get(cid))
            assertTrue(originalKif.isEmpty())
            assertEquals(listOf("(causes fire_starts sprinklers_activate)", "(causes fire_starts sprinklers_activate)"), kif)
            assertEquals(ElementState.ACTIVE, bag.state)
            assertTrue(muxJob.isActive)
        } finally {
            muxJob.cancel()
            bag.drain()
            originalBag.drain()
        }
    }

    @Test
    fun legalProvidersPreserveGroundingDurabilityOrderAndEvidenceReadback() = runBlocking {
        val events = mutableListOf<String>()
        val originalCas = CasStore.inMemory()
        var receiptCid: ContentId? = null
        var returned = false
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId {
                events.add("put:${ContentId.of(bytes).value}")
                return super.put(bytes)
            }
            override fun get(cid: ContentId): ByteArray? {
                if (returned && cid == receiptCid) events.add("receipt-read")
                return super.get(cid)
            }
        }
        val originalKif = KifKnowledgeBase()
        val kif = KifKnowledgeBase()
        val text = "Acme pays Beta."
        val nlp = NlpDocument(text, s_[NlpSentence(0, 0, text.length,
            s_[NlpToken(1, 0, 4, "Acme", "Acme", "NNP", "O"),
                NlpToken(2, 5, 9, "pays", "pay", "VBZ", "O"),
                NlpToken(3, 10, 14, "Beta", "Beta", "NNP", "O"),
                NlpToken(4, 14, 15, ".", ".", ".", "O")],
            s_[NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
                NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct")])],
            NlpMetadata("provider-fixture", "retained NNV fixture", emptyMap(), emptyMap()))
        val selectedRoute = ModelRoute("legal")
        lateinit var record: DocumentCurationRecord
        val canonical = LcncNodeRunner { node, inputs ->
            events.add("curate")
            assertEquals("i.curate", node.id)
            assertEquals(DocumentCurationLegos.CURATE, node.type)
            assertSame(selectedRoute, currentCoroutineContext()[ModelRoute])
            assertSame(cas, currentCoroutineContext()[CasStoreKey]!!.value)
            assertEquals(ElementState.ACTIVE, currentCoroutineContext()[LcncNodeKey.LEGAL_INGEST]!!.state)
            val source = DocumentCurationLegos.retainSource(cas, assertIs<Map<*, *>>(inputs["source"]))
            assertEquals(text, source.text)
            assertEquals("legal/i", source.name)
            assertEquals(source.name, source.correlation)
            assertEquals(text, assertNotNull(cas.get(source.extractedTextCid)).decodeToString())
            record = DocumentCurationRecord(source, nlp, null, "provider-fixture", s_[
                DocumentProposal(raw = "{}", subject = "Acme", predicate = "pay", obj = "Beta",
                    quote = text, begin = 0, end = text.length, polarity = true, modality = "asserted"),
                DocumentProposal(raw = "{}", quote = "Invented holding",
                    reasons = s_["quote does not match UTF-16 span"]),
            ], s_["source attribution only"])
            receiptCid = cas.put(DocumentCuratorCodec.encode(record))
            events.add("receipt-retained")
            DocumentCurationLegos.reference(receiptCid!!, record).also {
                returned = true
                events.add("curate-return")
            }
        }
        val ingest = LegalNodes.ingestRunner({ error("captured curator used") }, originalCas)
        keys(ingest, DocumentCurationRunnerKey, CasStoreKey, LegalCitationsKey)
        val providers = selectedRoute + DocumentCurationRunnerKey {
            events.add("resolve")
            canonical
        } + CasStoreKey(cas) + BrainClientKey(brain { error("legacy legal extraction used") }) +
            KifSinkKey { events.add("kif"); kif.assertKif(it) } + LegalCitationsKey { source ->
                events.add("extract")
                assertEquals(text, source)
                listOf(mapOf("text" to "Acme", "case" to "Acme"))
            }
        val output = withContext(providers) {
            ingest.run(LcncNode("i", "legal.ingest"), mapOf("text" to text))
        }
        assertEquals(listOf("resolve", "curate", "receipt-retained", "curate-return", "receipt-read", "extract"),
            events.filter { !it.startsWith("put:") })
        assertEquals(1, events.count { it == "extract" })
        assertFalse("kif" in events)
        val cid = ContentId(output["documentCid"] as String)
        val retainedCid = assertNotNull(receiptCid)
        assertEquals(retainedCid.value, output["receiptCid"])
        assertTrue(events.indexOf("put:${cid.value}") < events.indexOf("put:${retainedCid.value}"))
        assertEquals(text, assertNotNull(cas.get(cid)).decodeToString())
        assertContentEquals(DocumentCuratorCodec.encode(record), cas.get(retainedCid))
        assertNull(originalCas.get(cid))
        assertNull(originalCas.get(retainedCid))
        assertTrue(originalKif.toKifFile().isBlank())
        assertTrue(kif.toKifFile().isBlank())
        for ((key, value) in DocumentCurationLegos.reference(retainedCid, record)) assertEquals(value, output[key], key)
        assertEquals(listOf(mapOf("text" to "Acme", "case" to "Acme")), output["citations"])
        val proposals = record.proposals.view.map(DocumentCuratorCodec::proposal)
        val elements = assertIs<Map<*, *>>(output["elements"])
        assertEquals(proposals, elements["proposals"])
        assertEquals(listOf(proposals[1]), elements["refused"])
        assertEquals(record.reasons.view.toList(), elements["reasons"])
        assertFalse("holdings" in elements || "parties" in elements)
        val brief = assertIs<String>(output["brief"])
        assertTrue(text in brief)
        assertTrue(retainedCid.value in brief)
        assertTrue("proposal" in brief.lowercase() && "refus" in brief.lowercase())
        for (proposal in record.proposals.view) for (reason in proposal.reasons.view) assertTrue(reason in brief)

        val evidence = LegalNodes.evidenceRunner(originalKif)
        keys(evidence, KifKnowledgeBaseKey)
        val input = mapOf("documentCid" to cid.value, "brief" to brief)
        assertEquals(brief, evidence.run(LcncNode("e", "legal.evidence"), input)["brief"])
        kif.assertKif("(ruling case_prior doc_${cid.hex} verdict_prior)")
        val enriched = withContext(KifKnowledgeBaseKey(kif)) {
            evidence.run(LcncNode("e", "legal.evidence"), input)["brief"] as String
        }
        assertTrue(brief in enriched)
        assertTrue("prior ruling: case_prior -> verdict_prior" in enriched)
        assertFalse("- holding:" in enriched || "- party:" in enriched)
    }
}
