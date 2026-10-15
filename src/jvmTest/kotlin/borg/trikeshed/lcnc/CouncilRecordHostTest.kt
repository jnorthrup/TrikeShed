package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.daemon.OroborosDaemon
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kif.KifKnowledgeBase
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CouncilRecordHostTest {
    @Test fun hostJournalPublishesTheActualCaseNexusReceiptOnce() = runTest {
        val cas = CasStore.inMemory()
        val couch = Couch("council", CouchStoreFactory.casBacked(cas), cas)
        val board = ConfixBlackboard.empty()
        val kif = KifKnowledgeBase()
        val cases = OroborosDaemon.councilCaseRegistry(this, cas, couch)
        try {
            val seams = OroborosDaemon.councilRecordSeams(cas, couch, board, kif, cases)
            val record = CouncilNodes.registry(CouncilDialog { error("record cannot request a model") }, seams)
                .getValue("council.record")
            val node = LcncNode("record", "council.record", params = mapOf("caseId" to "host"))
            val documentCid = cas.put("source".encodeToByteArray()).value
            val inputs = mapOf("documentCid" to documentCid, "verdict" to mapOf("disposition" to "granted"),
                "transcript" to "testimony", "turns" to listOf(mapOf("text" to "testimony", "status" to "ok",
                    "cid" to ContentId.of("testimony".encodeToByteArray()).value)))
            val result = record.run(node, inputs)
            val report = assertIs<Map<*, *>>(result["report"])
            assertEquals("closed", cases.lifecycle("host"))
            assertEquals(3L, cases.revision("host"))
            assertEquals(cases.recordedCid("host"), report["recorded"])
            val retained = assertNotNull(cas.get(ContentId(report["recorded"] as String)),
                "The daemon's shared CAS must retain the actual lifecycle snapshot")
            val snapshot = CanonicalCbor.decodeJobSnapshot(retained)
            assertEquals(JobId.of("case/host"), snapshot.jobId)
            assertEquals("closed", snapshot.lifecycle)
            assertEquals(3L, snapshot.revision)
            assertEquals(report["recorded"], couch.docJson("council-case/host")?.get("recorded"))
            assertEquals("PUBLISHED", couch.docJson("council-case/host")?.get("recordPhase"))
            val sequence = couch.updateSeq
            assertEquals(result, record.run(node, inputs))
            assertEquals(sequence, couch.updateSeq)
            assertEquals(3L, cases.revision("host"))
        } finally {
            cases.close()
        }
    }

    @Test fun rejectedHostCouchWritesCannotPublishKifOrBlackboardFacts() = runTest {
        val cas = CasStore.inMemory()
        val store = CouchStore(object : CouchIngress {
            override fun putIntent(doc: Document, expectedRev: String?) = false
            override fun deleteIntent(docId: String, expectedRev: String?) = false
        }, CouchHeadProjection(), CouchChangesProjection())
        val couch = Couch("council", store, cas)
        val board = ConfixBlackboard.empty()
        val kif = KifKnowledgeBase()
        val cases = OroborosDaemon.councilCaseRegistry(this, cas, couch)
        try {
            val seams = OroborosDaemon.councilRecordSeams(cas, couch, board, kif, cases)
            val conflict = assertFailsWith<IllegalStateException> {
                seams.couchPut("council-case/rejected", mapOf("status" to "recording"))
            }
            assertTrue("conflict" in conflict.message.orEmpty())
            val expression = "(ruling case_rejected doc_source verdict)"
            assertFailsWith<IllegalStateException> { seams.kifSink(expression) }
            assertTrue(kif.toKifFile().isBlank())
            val cid = ContentId.of(expression.encodeToByteArray())
            assertNull(board.get("legal-kif/${cid.hex}"))
            assertNull(couch.docJson("kif-ledger/${cid.hex}"))
            assertEquals(0, cases.liveCases())
        } finally {
            cases.close()
        }
    }

    @Test fun acknowledgedHostLedgerPrecedesQueryableKifAndSupportsRepeatedPublication() = runTest {
        val cas = CasStore.inMemory()
        val couch = Couch("council", CouchStoreFactory.casBacked(cas), cas)
        val board = ConfixBlackboard.empty()
        val kif = KifKnowledgeBase()
        val cases = CouncilCaseRegistry(this, TribunalInstance.schemaPlan())
        try {
            val seams = OroborosDaemon.councilRecordSeams(cas, couch, board, kif, cases)
            val expression = "(ruling case_retained doc_source verdict)"
            val cid = ContentId.of(expression.encodeToByteArray())
            seams.kifSink(expression)
            assertEquals(expression, couch.docJson("kif-ledger/${cid.hex}")?.get("kif"))
            assertEquals(expression, board.get("legal-kif/${cid.hex}"))
            val bank = kif.toKifFile()
            assertTrue(expression in bank)
            // The adapter must use the current Couch revision, not swallow a conflict.
            seams.kifSink(expression)
            assertEquals(bank, kif.toKifFile())
            assertEquals(expression, couch.docJson("kif-ledger/${cid.hex}")?.get("kif"))
        } finally {
            cases.close()
        }
    }
}
