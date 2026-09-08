package borg.trikeshed.narsese

import borg.trikeshed.context.ElementState
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.BrainClientKey
import borg.trikeshed.lcnc.CasStoreKey
import borg.trikeshed.lcnc.KifKnowledgeBaseKey
import borg.trikeshed.lcnc.KifSinkKey
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeKey
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncServiceBinding
import borg.trikeshed.lcnc.LegalCitationsKey
import borg.trikeshed.lcnc.MuxContextKey
import borg.trikeshed.lcnc.ReteNetworkKey
import borg.trikeshed.lib.view
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
                type = "nal.mint"
                expectedRoute = callerRoute
            }
            withTimeout(5_000) { while (bag.size != 1) delay(1) }
            assertEquals(2, calls)
            assertEquals(0, originalBag.size)
            assertNull(originalCas.get(cid))
            assertTrue(originalRete.productions.all().isEmpty())
            assertEquals(1, rete.productions.all().size)
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
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId {
                events.add("cas")
                return super.put(bytes)
            }
        }
        val originalKif = KifKnowledgeBase()
        val kif = KifKnowledgeBase()
        val text = "Smith v. Jones. The agreement is enforceable."
        val selectedRoute = ModelRoute("legal")
        val selectedBrain = brain {
            events.add("model")
            assertSame(selectedRoute, currentCoroutineContext()[ModelRoute])
            assertSame(cas, currentCoroutineContext()[CasStoreKey]!!.value)
            assertEquals(ElementState.ACTIVE, currentCoroutineContext()[LcncNodeKey.LEGAL_INGEST]!!.state)
            """{"citations":[],"holdings":[{"text":"The agreement is enforceable.","type":"holding"},{"text":"Invented holding","type":"holding"}],"parties":[]}"""
        }
        val ingest = LegalNodes.ingestRunner(brain { error("captured model used") }, EmptyCoroutineContext, originalCas, originalKif::assertKif)
        keys(ingest, BrainClientKey, MuxContextKey, CasStoreKey, KifSinkKey, LegalCitationsKey)
        val providers = BrainClientKey(selectedBrain) + MuxContextKey(selectedRoute) + CasStoreKey(cas) +
            KifSinkKey { events.add("kif"); kif.assertKif(it) } + LegalCitationsKey { source ->
                events.add("extract")
                assertEquals(text, source)
                listOf(mapOf("text" to "Smith v. Jones", "case" to "Smith v. Jones"))
            }
        val output = withContext(providers) {
            ingest.run(LcncNode("i", "legal.ingest"), mapOf("text" to text))
        }
        assertEquals(listOf("extract", "model", "cas"), events.take(3))
        assertTrue(events.drop(3).isNotEmpty() && events.drop(3).all { it == "kif" })
        val cid = ContentId(output["documentCid"] as String)
        assertEquals(text, assertNotNull(cas.get(cid)).decodeToString())
        assertNull(originalCas.get(cid))
        assertTrue(originalKif.toKifFile().isBlank())
        assertTrue("Invented" !in kif.toKifFile())
        assertEquals(1, ((output["elements"] as Map<*, *>)["refused"] as List<*>).size)

        val evidence = LegalNodes.evidenceRunner(originalKif)
        keys(evidence, KifKnowledgeBaseKey)
        val input = mapOf("documentCid" to cid.value, "brief" to "Brief")
        assertEquals("Brief", evidence.run(LcncNode("e", "legal.evidence"), input)["brief"])
        val enriched = withContext(KifKnowledgeBaseKey(kif)) {
            evidence.run(LcncNode("e", "legal.evidence"), input)["brief"] as String
        }
        assertTrue("The agreement is enforceable." in enriched)
        assertTrue("Smith_v__Jones" in enriched)
        assertTrue("Invented" !in enriched)
    }
}
