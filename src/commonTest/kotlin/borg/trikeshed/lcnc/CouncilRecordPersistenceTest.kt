package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CasStore
import borg.trikeshed.couch.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Retain before committing; publish after committing; partial publication remains explicit. */
class CouncilRecordPersistenceTest {
    val node = LcncNode("record", "council.record", params = mapOf("caseId" to "persistence"))
    val inputs = mapOf(
        "verdict" to mapOf("disposition" to "granted"),
        "documentCid" to ContentId.of("source".encodeToByteArray()).value,
        "transcript" to "testimony",
        "turns" to listOf(mapOf("text" to "testimony", "status" to "ok",
            "cid" to ContentId.of("testimony".encodeToByteArray()).value)),
    )

    @Test fun requiredSinkFailuresDoNotAdvanceTheCase() = runTest {
        for (failing in listOf("cas", "couch", "blackboard")) {
            val store = InMemoryRecordStore()
            val base = RecordSeams.inMemory(store)
            val calls = mutableListOf<String>()
            val failure = IllegalStateException("$failing unavailable")
            fun enter(stage: String) {
                calls.add(stage)
                if (stage == failing) throw failure
            }
            val seams = base.copy(
                casPut = { bytes -> enter("cas"); base.casPut(bytes) },
                couchPut = { id, body -> enter("couch"); base.couchPut(id, body) },
                blackboardPut = { id, body, source -> enter("blackboard"); base.blackboardPut(id, body, source) },
                kifSink = { kif -> enter("kif"); base.kifSink(kif) },
                recordRuling = { case, verdict, transcript -> enter("lifecycle"); base.recordRuling(case, verdict, transcript) },
            )
            val runner = CouncilNodes.registry(CouncilDialog { error("recording cannot request a model") }, seams)
                .getValue("council.record")
            assertSame(failure, assertFailsWith<IllegalStateException> { runner.run(node, inputs) })
            assertTrue(store.rulings.isEmpty(), failing)
            assertTrue(store.mistrials.isEmpty(), failing)
            val order = listOf("cas", "couch", "blackboard", "lifecycle")
            assertEquals(order.take(order.indexOf(failing) + 1), calls.distinct(), failing)
            if (failing == "couch") {
                assertTrue(store.cas.isNotEmpty(), "Retained CAS evidence must not be removed on downstream failure")
                assertTrue(store.blackboard.isEmpty())
                assertTrue(store.kif.isEmpty())
            }
        }
    }

    @Test fun lifecycleCommitsAfterPreparationAndBeforeKifPublication() = runTest {
        for (mistrial in listOf(false, true)) {
            val store = InMemoryRecordStore()
            val base = RecordSeams.inMemory(store)
            val calls = mutableListOf<String>()
            fun retained() {
                assertEquals("PREPARED", store.couch["council-case/persistence"]?.get("recordPhase"))
                assertEquals("recording", store.blackboard["council-case/persistence"]?.get("status"))
                assertTrue(store.kif.isEmpty(), "An uncommitted decision must never enter the evidence bank")
            }
            val seams = base.copy(
                couchPut = { id, body -> calls.add("couch:${body["recordPhase"]}"); base.couchPut(id, body) },
                blackboardPut = { id, body, source -> calls.add("blackboard:${body["status"]}"); base.blackboardPut(id, body, source) },
                kifSink = { kif ->
                    assertEquals(1, store.rulings.size)
                    assertEquals("RECORDED", store.couch["council-case/persistence"]?.get("recordPhase"))
                    calls.add("kif"); base.kifSink(kif)
                },
                recordRuling = { case, verdict, transcript ->
                    calls.add("lifecycle"); retained(); base.recordRuling(case, verdict, transcript)
                },
                recordMistrial = { case, reason ->
                    calls.add("lifecycle"); retained(); base.recordMistrial(case, reason)
                },
            )
            CouncilNodes.registry(CouncilDialog { error("model") }, seams).getValue("council.record")
                .run(node, inputs + ("verdict" to mapOf("mistrial" to mistrial)))
            assertEquals(listOf("couch:PREPARED", "blackboard:recording", "lifecycle", "couch:RECORDED") +
                (if (mistrial) emptyList() else listOf("kif")) +
                listOf("blackboard:${if (mistrial) "mistrial" else "ruled"}", "couch:PUBLISHED"), calls)
        }
    }

    @Test fun lifecycleFailurePropagatesWithoutDiscardingRetainedEvidence() = runTest {
        val store = InMemoryRecordStore()
        val failure = IllegalStateException("lifecycle refused")
        val seams = RecordSeams.inMemory(store).copy(recordRuling = { _, _, _ -> throw failure })
        val runner = CouncilNodes.registry(CouncilDialog { error("model") }, seams).getValue("council.record")
        assertSame(failure, assertFailsWith<IllegalStateException> { runner.run(node, inputs) })
        assertNotNull(store.couch["council-case/persistence"])
        assertTrue(store.cas.isNotEmpty())
        assertTrue(store.rulings.isEmpty())
        assertTrue(store.kif.isEmpty())
        assertEquals("recording", store.couch["council-case/persistence"]?.get("status"))
        assertEquals("recording", store.blackboard["council-case/persistence"]?.get("status"))
    }

    @Test fun failedPublicationResumesWithoutRepeatingTheRetainedLifecycleAdvance() = runTest {
        val store = InMemoryRecordStore()
        val base = RecordSeams.inMemory(store)
        var fail = true
        val seams = base.copy(kifSink = { kif ->
            if (fail) { fail = false; error("publication unavailable") }
            base.kifSink(kif)
        })
        fun registry() = CouncilNodes.registry(CouncilDialog { error("model") }, seams)
        assertFailsWith<IllegalStateException> { registry().getValue("council.record").run(node, inputs) }
        assertEquals(1, store.rulings.size)
        assertEquals("RECORDED", store.couch["council-case/persistence"]?.get("recordPhase"))
        val pending = registry().getValue("council.case").run(node, emptyMap())["case"] as Map<*, *>
        assertEquals("recording", (pending["index"] as Map<*, *>)["status"])
        // Recreate the runners: resumption comes from the retained journal, not a runner flag.
        val out = registry().getValue("council.record").run(node, inputs)
        assertEquals("ruled", (out["report"] as Map<*, *>)["status"])
        assertEquals(1, store.rulings.size)
        assertEquals(1, store.kif.size)
        assertEquals("PUBLISHED", store.couch["council-case/persistence"]?.get("recordPhase"))
        val readsOnly = seams.copy(casPut = { error("published replay wrote CAS") },
            couchPut = { _, _ -> error("published replay wrote Couch") },
            blackboardPut = { _, _, _ -> error("published replay wrote index") },
            kifSink = { error("published replay emitted KIF") },
            recordRuling = { _, _, _ -> error("published replay advanced lifecycle") })
        assertEquals(out, CouncilNodes.registry(CouncilDialog { error("model") }, readsOnly)
            .getValue("council.record").run(node, inputs))
    }

    @Test fun durableIncompletePhaseOverridesAnAlreadyUpdatedBlackboardIndex() = runTest {
        val store = InMemoryRecordStore()
        val base = RecordSeams.inMemory(store)
        val seams = base.copy(couchPut = { id, body ->
            if (body["recordPhase"] == "PUBLISHED") error("last journal write unavailable")
            base.couchPut(id, body)
        })
        val registry = CouncilNodes.registry(CouncilDialog { error("model") }, seams)
        assertFailsWith<IllegalStateException> { registry.getValue("council.record").run(node, inputs) }
        assertEquals("ruled", store.blackboard["council-case/persistence"]?.get("status"))
        val case = registry.getValue("council.case").run(node, emptyMap())["case"] as Map<*, *>
        assertEquals("recording", (case["index"] as Map<*, *>)["status"])
        assertEquals("RECORDED", (case["index"] as Map<*, *>)["recordPhase"])
    }

    @Test fun concurrentReplaysPublishOnceAndDifferentEvidenceCannotOverwriteTheCase() = runTest {
        val store = InMemoryRecordStore()
        val seams = RecordSeams.inMemory(store)
        val runner = CouncilNodes.registry(CouncilDialog { error("model") }, seams).getValue("council.record")
        val results = List(8) { async { runner.run(node, inputs) } }.awaitAll()
        assertTrue(results.all { it == results.first() })
        assertEquals(1, store.rulings.size)
        assertEquals(1, store.kif.size)
        val before = store.couch.toMap()
        val bytesBefore = store.cas.size
        assertFailsWith<IllegalStateException> {
            runner.run(node, inputs + ("verdict" to mapOf("disposition" to "denied")))
        }
        assertEquals(before, store.couch)
        assertEquals(bytesBefore, store.cas.size)
        assertEquals(1, store.rulings.size)
        assertEquals(1, store.kif.size)
    }

    @Test fun casAcknowledgementsCannotSubstituteForRetainedBytes() = runTest {
        for (mode in listOf("wrong-cid", "missing", "substituted")) {
            val store = InMemoryRecordStore()
            val base = RecordSeams.inMemory(store)
            val seams = when (mode) {
                "wrong-cid" -> base.copy(casPut = { ContentId.of("other".encodeToByteArray()).value })
                "missing" -> base.copy(casPut = { ContentId.of(it).value })
                else -> base.copy(casGet = { "substituted".encodeToByteArray() })
            }
            val runner = CouncilNodes.registry(CouncilDialog { error("model") }, seams).getValue("council.record")
            assertFailsWith<IllegalStateException>(mode) { runner.run(node, inputs) }
            assertTrue(store.couch.isEmpty(), mode)
            assertTrue(store.blackboard.isEmpty(), mode)
            assertTrue(store.rulings.isEmpty(), mode)
            assertTrue(store.kif.isEmpty(), mode)
        }
    }

    @Test fun couchAcknowledgementRejectsConflictAndMalformedSuccess() {
        val cas = CasStore.inMemory()
        val couch = Couch("record-test", CouchStoreFactory.casBacked(cas), cas)
        RecordSeams.requireCouchWrite("case", couch.put("case", mapOf("status" to "recording"), null))
        assertFailsWith<IllegalStateException> {
            RecordSeams.requireCouchWrite("case", couch.put("case", mapOf("status" to "ruled"), "wrong-revision"))
        }
        assertEquals("recording", couch.docJson("case")?.get("status"))
        RecordSeams.requireCouchWrite("case", mapOf("ok" to true, "id" to "case", "rev" to "1-a"))
        for (response in listOf(
            mapOf("error" to "conflict", "reason" to "Document update conflict."),
            mapOf("ok" to false, "id" to "case", "rev" to "1-a"),
            mapOf("ok" to true, "id" to "other", "rev" to "1-a"),
            mapOf("ok" to true, "id" to "case"),
        )) {
            val failure = assertFailsWith<IllegalStateException> { RecordSeams.requireCouchWrite("case", response) }
            assertTrue("case" in failure.message.orEmpty())
        }
    }
}
