package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CouncilLifecycleReceiptTest {
    @Test fun memoryFixturesRetainSnapshotReceiptsInsteadOfVerdictAliases() = runTest {
        val store = InMemoryRecordStore()
        val seams = RecordSeams.inMemory(store)
        val verdict = ContentId.of("verdict".encodeToByteArray()).value
        val ruling = seams.recordRuling("ruling", verdict, ContentId.of("transcript".encodeToByteArray()).value)
        val mistrial = seams.recordMistrial("mistrial", "reason")
        assertNotEquals(verdict, ruling)
        for ((caseId, cid, lifecycle) in listOf(Triple("ruling", ruling, "closed"), Triple("mistrial", mistrial, "failed"))) {
            val bytes = assertNotNull(seams.casGet(cid))
            assertEquals(cid, ContentId.of(bytes).value)
            val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
            assertEquals(JobId.of("case/$caseId"), snapshot.jobId)
            assertEquals(lifecycle, snapshot.lifecycle)
            assertTrue(snapshot.revision > 0)
        }
    }

    @Test fun missingSnapshotCannotPublishOrReplay() = runTest { reject("missing") }
    @Test fun substitutedSnapshotCannotPublishOrReplay() = runTest { reject("digest") }
    @Test fun anotherCasesSnapshotCannotPublishOrReplay() = runTest { reject("case") }
    @Test fun wrongTerminalStateCannotPublishOrReplay() = runTest { reject("lifecycle") }
    @Test fun uncommittedSnapshotCannotPublishOrReplay() = runTest { reject("revision") }
    @Test fun nonSnapshotBytesCannotPublishOrReplay() = runTest { reject("encoding") }
    @Test fun malformedReceiptIdentityCannotPublishOrReplay() = runTest { reject("identity") }

    suspend fun reject(fault: String) {
        for (mistrial in listOf(false, true)) for (phase in listOf(null, "RECORDED", "PUBLISHED")) {
            val caseId = "receipt"
            val key = "council-case/$caseId"
            val node = LcncNode("record", "council.record", params = mapOf("caseId" to caseId))
            val inputs = mapOf("verdict" to mapOf("mistrial" to mistrial), "transcript" to "testimony",
                "documentCid" to ContentId.of("source".encodeToByteArray()).value)
            val snapshot = JobSnapshot(JobId.of("case/$caseId"), 3L, "", if (mistrial) "failed" else "closed")
            val validBytes = CanonicalCbor.encode(snapshot)
            val validCid = ContentId.of(validBytes).value
            val badBytes = when (fault) {
                "case" -> CanonicalCbor.encode(snapshot.copy(jobId = JobId.of("case/other")))
                "lifecycle" -> CanonicalCbor.encode(snapshot.copy(lifecycle = if (mistrial) "closed" else "failed"))
                "revision" -> CanonicalCbor.encode(snapshot.copy(revision = 0))
                "encoding", "digest" -> "not a job snapshot".encodeToByteArray()
                else -> validBytes
            }
            val badCid = when (fault) {
                "identity" -> "mistrial:receipt"
                "digest" -> validCid
                else -> ContentId.of(badBytes).value
            }
            val store = InMemoryRecordStore()
            val base = RecordSeams.inMemory(store)
            var failPublication = phase == "RECORDED"
            var lifecycleCalls = 0
            var indexWrites = 0
            var couchWrites = 0
            var casWrites = 0
            var publications = 0
            var returnInvalid = phase == null
            fun receipt(): String {
                lifecycleCalls++
                val cid = if (returnInvalid) badCid else validCid
                if (!(returnInvalid && fault == "missing")) store.cas[cid] = if (returnInvalid) badBytes else validBytes
                return cid
            }
            val seams = base.copy(
                recordRuling = { _, _, _ -> receipt() },
                recordMistrial = { _, _ -> receipt() },
                casPut = { casWrites++; base.casPut(it) },
                couchPut = { id, body ->
                    if (failPublication && body["recordPhase"] == "PUBLISHED") error("injected publication outage")
                    couchWrites++; base.couchPut(id, body)
                },
                blackboardPut = { id, body, source -> indexWrites++; base.blackboardPut(id, body, source) },
                kifSink = { publications++; base.kifSink(it) },
            )
            val runners = CouncilNodes.registry(CouncilDialog { error("record cannot call a model") }, seams)
            val record = runners.getValue("council.record")
            if (phase != null) {
                if (phase == "RECORDED") assertFailsWith<IllegalStateException> { record.run(node, inputs) }
                else record.run(node, inputs)
                assertEquals(phase, store.couch[key]?.get("recordPhase"))
                store.couch[key] = store.couch.getValue(key) + ("recorded" to badCid)
                if (fault == "missing") store.cas.remove(badCid) else store.cas[badCid] = badBytes
                failPublication = false
                returnInvalid = true
                lifecycleCalls = 0; indexWrites = 0; couchWrites = 0; casWrites = 0; publications = 0
            }
            val context = "$fault/$phase/mistrial=$mistrial"
            val failure = assertFailsWith<IllegalStateException>(context) { record.run(node, inputs) }
            assertTrue("lifecycle receipt" in failure.message.orEmpty(), "$context: ${failure.message}")
            assertEquals(0, publications, context)
            assertEquals(phase ?: "PREPARED", store.couch[key]?.get("recordPhase"), context)
            if (phase != null) {
                assertEquals(0, lifecycleCalls, context)
                assertEquals(0, indexWrites, context)
                assertEquals(0, couchWrites, context)
                assertEquals(0, casWrites, context)
                val readFailure = assertFailsWith<IllegalStateException>(context) {
                    runners.getValue("council.case").run(node.copy(type = "council.case"), emptyMap())
                }
                assertTrue("lifecycle receipt" in readFailure.message.orEmpty(), context)
            }
        }
    }
}
