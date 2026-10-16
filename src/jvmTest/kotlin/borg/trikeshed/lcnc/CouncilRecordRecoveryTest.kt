package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.daemon.OroborosDaemon
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.job.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Controlled store reopen after a post-commit failure; no OS-crash or exactly-once claim. */
class CouncilRecordRecoveryTest {
    @TempDir
    lateinit var directory: Path

    @Test fun rulingCommitSurvivesLostJournalAcknowledgement() = lifecycleRecovery(false)
    @Test fun mistrialCommitSurvivesLostJournalAcknowledgement() = lifecycleRecovery(true)

    @Test fun partialLifecycleReopensWithoutRepeatingSubmission() = runTest {
        for (active in listOf(false, true)) {
            val caseId = if (active) "active" else "submitted"
            val jobId = JobId.of("case/$caseId")
            val logId = "council-log/${ContentId.of(caseId.encodeToByteArray()).hex}"
            stores { _, couch ->
                val log = CouchJobLog.open(couch, logId)
                log.append(1, CanonicalCbor.encode(JobCommand.Submit(jobId, "council-case-$caseId")))
                if (active) log.append(2, CanonicalCbor.encode(JobCommand.Start(jobId, "council-case-$caseId-start", 1)))
            }
            stores { cas, couch ->
                val cases = OroborosDaemon.councilCaseRegistry(this, cas, couch)
                try {
                    val verdict = cas.put("verdict".encodeToByteArray()).value
                    val transcript = cas.put("transcript".encodeToByteArray()).value
                    val receipt = cases.recordRuling(caseId, verdict, transcript)
                    assertEquals("closed", cases.lifecycle(caseId))
                    assertEquals(3L, cases.revision(caseId))
                    assertEquals(listOf("submit", "start", "complete"), CouchJobLog.open(couch, logId).replay()
                        .map { CanonicalCbor.decodeJobCommand(it.payload).operationName }.toList())
                    val revision = couch.docJson(logId)?.get("_rev")
                    assertEquals(receipt, cases.recordRuling(caseId, verdict, transcript))
                    assertEquals(revision, couch.docJson(logId)?.get("_rev"))
                    assertFailsWith<IllegalStateException> {
                        cases.recordRuling(caseId, verdict, cas.put("different transcript".encodeToByteArray()).value)
                    }
                } finally { cases.close() }
            }
        }
    }

    fun lifecycleRecovery(mistrial: Boolean) = runTest {
        val caseId = if (mistrial) "mistrial-reopen" else "ruling-reopen"
        val key = "council-case/$caseId"
        val logId = "council-log/${ContentId.of(caseId.encodeToByteArray()).hex}"
        val node = LcncNode("record", "council.record", params = mapOf("caseId" to caseId))
        val source = "source document".encodeToByteArray()
        val inputs = mapOf("documentCid" to ContentId.of(source).value, "transcript" to "testimony",
            "verdict" to mapOf("disposition" to "granted", "mistrial" to mistrial),
            "turns" to listOf(mapOf("text" to "testimony", "status" to "ok",
                "cid" to ContentId.of("testimony".encodeToByteArray()).value)))
        fun runner(seams: RecordSeams) = CouncilNodes.registry(CouncilDialog { fail("No model in record") }, seams)
            .getValue("council.record")
        var receipt: String? = null
        var logRevision: Any? = null
        val lifecycle = if (mistrial) "failed" else "closed"
        stores { cas, couch ->
            cas.put(source)
            val board = ConfixBlackboard.empty()
            val kif = KifKnowledgeBase()
            val cases = OroborosDaemon.councilCaseRegistry(this, cas, couch)
            try {
                val host = OroborosDaemon.councilRecordSeams(cas, couch, board, kif, cases)
                val interrupted = host.copy(couchPut = { id, body ->
                    if (id == key && body["recordPhase"] == "RECORDED") error("journal ack unavailable")
                    host.couchPut(id, body)
                })
                val beforeKif = kif.toKifFile()
                assertEquals("journal ack unavailable", assertFailsWith<IllegalStateException> {
                    runner(interrupted).run(node, inputs)
                }.message)
                assertEquals("PREPARED", couch.docJson(key)?.get("recordPhase"))
                assertNull(couch.docJson(key)?.get("recorded"))
                assertEquals(beforeKif, kif.toKifFile())
                receipt = assertNotNull(cases.recordedCid(caseId))
                val snapshot = CanonicalCbor.decodeJobSnapshot(assertNotNull(cas.get(ContentId(receipt!!))))
                assertEquals(JobId.of("case/$caseId"), snapshot.jobId)
                assertEquals(lifecycle, snapshot.lifecycle)
                assertEquals(3L, snapshot.revision)
                logRevision = assertNotNull(couch.docJson(logId))["_rev"]
                assertEquals(3, CouchJobLog.open(couch, logId).replay().count())
            } finally { cases.close() }
        }
        val published = stores { cas, couch ->
            // Fresh CAS/Couch owners and registry; verify retained state before retry.
            val snapshot = CanonicalCbor.decodeJobSnapshot(assertNotNull(cas.get(ContentId(receipt!!))))
            assertEquals(lifecycle, snapshot.lifecycle)
            assertEquals(3L, snapshot.revision)
            assertEquals("PREPARED", couch.docJson(key)?.get("recordPhase"))
            val board = ConfixBlackboard.empty()
            val kif = KifKnowledgeBase()
            val cases = OroborosDaemon.councilCaseRegistry(this, cas, couch)
            try {
                val host = OroborosDaemon.councilRecordSeams(cas, couch, board, kif, cases)
                var publications = 0
                val record = runner(host.copy(kifSink = { publications++; host.kifSink(it) }))
                val result = record.run(node, inputs)
                assertEquals(receipt, assertIs<Map<*, *>>(result["report"])["recorded"])
                assertEquals(lifecycle, cases.lifecycle(caseId))
                assertEquals(3L, cases.revision(caseId))
                assertEquals(logRevision, couch.docJson(logId)?.get("_rev"), "Retry must not append another terminal command")
                assertEquals(3, CouchJobLog.open(couch, logId).replay().count())
                assertEquals("PUBLISHED", couch.docJson(key)?.get("recordPhase"))
                assertEquals(if (mistrial) 0 else 1, publications)
                assertFailsWith<IllegalStateException> { cases.recordMistrial(caseId, "different reason") }
                val sequence = couch.updateSeq
                assertEquals(result, record.run(node, inputs))
                assertEquals(sequence, couch.updateSeq)
                assertEquals(if (mistrial) 0 else 1, publications)
                result
            } finally { cases.close() }
        }
        stores { cas, couch ->
            val cases = OroborosDaemon.councilCaseRegistry(this, cas, couch)
            try {
                val host = OroborosDaemon.councilRecordSeams(cas, couch, ConfixBlackboard.empty(), KifKnowledgeBase(), cases)
                val sequence = couch.updateSeq
                val readonly = host.copy(casPut = { fail("Published replay wrote CAS") },
                    couchPut = { _, _ -> fail("Published replay wrote Couch") },
                    recordRuling = { _, _, _ -> fail("Published replay repeated lifecycle") },
                    recordMistrial = { _, _ -> fail("Published replay repeated lifecycle") })
                assertEquals(published, runner(readonly).run(node, inputs))
                assertEquals(sequence, couch.updateSeq)
                assertEquals(logRevision, couch.docJson(logId)?.get("_rev"))
            } finally { cases.close() }
        }
    }

    suspend fun <T> stores(block: suspend (PackCasStore, Couch) -> T): T {
        val files = JvmFileOperations()
        return PackCasStore(files, directory.resolve("cas").toString()).use { cas ->
            CouchCommitStore(cas, StorageDurability.DURABLE, files, directory.resolve("couch").toString()).use { commits ->
                block(cas, Couch("council", CouchStoreFactory.durableCasBacked(cas, commits), cas))
            }
        }
    }

    fun seams(
        cas: PackCasStore,
        couch: Couch,
        blackboard: MutableMap<String, Map<String, String>>,
        ruling: suspend (String, String, String) -> String = { _, _, _ -> fail("Lifecycle repeated after reopen") },
        kif: (String) -> Unit = { fail("Published replay emitted KIF") },
    ) = RecordSeams(
        casPut = { cas.put(it).value },
        casGet = { cas.get(ContentId(it)) },
        couchPut = { id, body ->
            val rev = couch.docJson(id)?.get("_rev") as? String
            RecordSeams.requireCouchWrite(id, couch.put(id, body, rev))
        },
        couchGet = couch::docJson,
        blackboardPut = { key, fact, source ->
            assertEquals("council.record", source)
            blackboard[key] = fact
        },
        blackboardGet = { blackboard[it] },
        recordRuling = ruling,
        recordMistrial = { _, _ -> fail("Unexpected mistrial") },
        kifSink = kif,
    )

    @Test fun recordedPublicationResumesAcrossStoreReopen() = runTest {
        val caseId = "recovery"
        val key = "council-case/$caseId"
        val node = LcncNode("record", "council.record", params = mapOf("caseId" to caseId))
        val source = "Source document: the application is supported.".encodeToByteArray()
        val documentCid = ContentId.of(source).value
        val turn = "The source supports granting the application."
        val turns = listOf(mapOf("text" to turn, "status" to "ok",
            "cid" to ContentId.of(turn.encodeToByteArray()).value))
        val transcript = "[review]\n$turn"
        val verdict = mapOf("disposition" to "granted")
        val verdictBytes = jsonOf(verdict).encodeToByteArray()
        val verdictCid = ContentId.of(verdictBytes).value
        val transcriptCid = ContentId.of(transcript.encodeToByteArray()).value
        val caseBytes = jsonOf(linkedMapOf(
            "caseId" to caseId, "documentCid" to documentCid,
            "verdictCid" to verdictCid, "transcriptCid" to transcriptCid,
            "seatFailures" to 0, "seatCount" to 1, "cidMismatches" to 0,
            "status" to "ruled", "turns" to turns,
        )).encodeToByteArray()
        val caseCid = ContentId.of(caseBytes).value
        // A retained snapshot fixture, distinct from the verdict; real nexus reopen is tested above.
        val receipt = CanonicalCbor.encode(JobSnapshot(JobId.of("case/$caseId"), 3L, "", "closed", attemptCount = 1))
        val receiptCid = ContentId.of(receipt).value
        val inputs = mapOf("documentCid" to documentCid, "turns" to turns,
            "transcript" to transcript, "verdict" to verdict)
        val expectedKif = "(ruling case_$caseId doc_${ContentId(documentCid).hex} ${ContentId(verdictCid).hex})"
        fun registry(seams: RecordSeams) = CouncilNodes.registry(
            CouncilDialog { fail("Recording must not call a provider") }, seams)
        fun evidence(cas: PackCasStore) {
            for (bytes in arrayOf(source, turn.encodeToByteArray(), transcript.encodeToByteArray(), verdictBytes, caseBytes)) {
                val cid = ContentId.of(bytes)
                assertContentEquals(bytes, assertNotNull(cas.get(cid), cid.value), cid.value)
            }
        }
        suspend fun readback(runners: Map<String, LcncNodeRunner>, phase: CouncilRecordPhase) {
            val case = assertIs<Map<*, *>>(runners.getValue("council.case")
                .run(node.copy(type = "council.case"), emptyMap())["case"])
            val index = assertIs<Map<*, *>>(case["index"])
            assertEquals(phase.name, index["recordPhase"])
            assertEquals(if (phase == CouncilRecordPhase.PUBLISHED) "ruled" else "recording", index["status"])
            assertEquals(receiptCid, index["recorded"])
            assertEquals(documentCid, index["documentCid"])
            assertEquals(caseCid, index["caseCid"])
            assertEquals(transcript, case["transcript"])
            assertEquals(verdictBytes.decodeToString(), case["verdict"])
        }

        var lifecycleCalls = 0
        var publicationAttempts = 0
        val failure = IllegalStateException("KIF publication unavailable after lifecycle commit")
        val recorded = stores { cas, couch ->
            assertEquals(documentCid, cas.put(source).value)
            val blackboard = mutableMapOf<String, Map<String, String>>()
            val runners = registry(seams(cas, couch, blackboard,
                ruling = { id, verdictId, transcriptId ->
                    lifecycleCalls++
                    assertEquals(caseId, id)
                    assertEquals(verdictCid, verdictId)
                    assertEquals(transcriptCid, transcriptId)
                    assertEquals(CouncilRecordPhase.PREPARED.name, couch.docJson(key)?.get("recordPhase"))
                    assertEquals("recording", blackboard[key]?.get("status"))
                    evidence(cas)
                    cas.put(receipt).value
                },
                kif = { expression ->
                    publicationAttempts++
                    assertEquals(expectedKif, expression)
                    assertEquals(1, lifecycleCalls)
                    assertEquals(CouncilRecordPhase.RECORDED.name, couch.docJson(key)?.get("recordPhase"))
                    assertEquals(receiptCid, couch.docJson(key)?.get("recorded"))
                    throw failure
                }))
            assertSame(failure, assertFailsWith<IllegalStateException> {
                runners.getValue("council.record").run(node, inputs)
            })
            assertEquals(1, lifecycleCalls)
            assertEquals(1, publicationAttempts)
            assertEquals("recording", blackboard[key]?.get("status"))
            readback(runners, CouncilRecordPhase.RECORDED)
            evidence(cas)
            assertContentEquals(receipt, cas.get(ContentId(receiptCid)))
            assertNotNull(couch.docJson(key))
        }

        // Both owners closed; fresh CAS index, Couch projections, seams, mutex and runners.
        val published = stores { cas, couch ->
            val blackboard = mutableMapOf<String, Map<String, String>>()
            val kif = mutableListOf<String>()
            val runners = registry(seams(cas, couch, blackboard, kif = { expression ->
                assertEquals(CouncilRecordPhase.RECORDED.name, couch.docJson(key)?.get("recordPhase"))
                kif.add(expression)
            }))
            assertTrue(blackboard.isEmpty())
            assertEquals(recorded["_rev"], couch.docJson(key)?.get("_rev"))
            readback(runners, CouncilRecordPhase.RECORDED)
            evidence(cas) // Check before resumption can retain anything again.
            assertContentEquals(receipt, cas.get(ContentId(receiptCid)))
            val result = runners.getValue("council.record").run(node, inputs)
            val report = assertIs<Map<*, *>>(result["report"])
            assertEquals(recorded["recorded"], report["recorded"])
            assertEquals(caseCid, report["caseCid"])
            assertEquals("ruled", report["status"])
            assertEquals(1, lifecycleCalls)
            assertEquals(listOf(expectedKif), kif)
            assertEquals("ruled", blackboard[key]?.get("status"))
            readback(runners, CouncilRecordPhase.PUBLISHED)
            result
        }

        stores { cas, couch ->
            val blackboard = mutableMapOf<String, Map<String, String>>()
            val reads = seams(cas, couch, blackboard).copy(
                casPut = { fail("Published replay wrote CAS") },
                couchPut = { _, _ -> fail("Published replay wrote Couch") },
                blackboardPut = { _, _, _ -> fail("Published replay wrote the index") },
            )
            val runners = registry(reads)
            val files = JvmFileOperations()
            val head = directory.resolve("couch/HEAD").toString()
            val pack = directory.resolve("cas/pack").toString()
            val headBytes = files.readAllBytes(head)
            val packBytes = files.readAllBytes(pack)
            val revision = couch.docJson(key)?.get("_rev")
            readback(runners, CouncilRecordPhase.PUBLISHED)
            evidence(cas)
            assertContentEquals(receipt, cas.get(ContentId(receiptCid)))
            assertEquals(published, runners.getValue("council.record").run(node, inputs))
            val conflict = assertFailsWith<IllegalStateException> {
                runners.getValue("council.record").run(node,
                    inputs + ("verdict" to mapOf("disposition" to "denied")))
            }
            assertTrue("already names different" in conflict.message.orEmpty())
            assertEquals(revision, couch.docJson(key)?.get("_rev"))
            assertEquals(recorded["recorded"], couch.docJson(key)?.get("recorded"))
            assertContentEquals(headBytes, files.readAllBytes(head))
            assertContentEquals(packBytes, files.readAllBytes(pack))
            assertTrue(blackboard.isEmpty())
            assertEquals(1, lifecycleCalls)
        }
    }
}
