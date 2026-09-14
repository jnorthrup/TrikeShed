package borg.trikeshed.lcnc

import borg.trikeshed.common.File
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentAppendLog
import borg.trikeshed.narsese.DocumentCasStore
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.*

class HeadhunterPersistenceTest {
    @Test fun closedStoresReopenEvidenceArtifactsReviewsAndSourceVersionsThroughUserspaceIo() = runBlocking {
        withContext(fileIoContext) {
            val parent = checkNotNull(java.lang.System.getProperty("java.io.tmpdir")).trimEnd('/')
            val prefix = "$parent/headhunter-${Random.nextLong().toULong()}"
            val casPath = "$prefix.cas"
            val logPath = "$prefix.wal"
            val original = "User-supplied professional document\n".encodeToByteArray()
            var evidenceId = ""
            var evidenceCid = ""
            var artifactId = ""
            var artifactCid = ""
            var originalCid = ""
            try {
                DocumentCasStore.open(casPath, parent).use { cas ->
                    DocumentAppendLog.open(logPath, parent).use { log ->
                        val store = HeadhunterStore(ConfixBlackboard.empty(), cas, log) { 1_000L }
                        assertEquals(0, store.restore())
                        originalCid = cas.put(original).value
                        val evidence = store.save("evidence", null, null, mapOf(
                            "title" to "Professional record", "text" to "Built Kotlin services", "category" to "resume",
                            "originalCid" to originalCid, "filename" to "resume.txt", "mediaType" to "text/plain",
                        ))
                        evidenceId = evidence["id"] as String
                        evidenceCid = evidence["cid"] as String
                        val listing = store.save("listing", null, null, mapOf("title" to "Engineer", "text" to "Kotlin services"))
                        val application = store.save("application", null, null, mapOf("listingId" to listing["id"], "evidenceIds" to listOf(evidenceId), "status" to "preparing"))
                        val artifact = store.save("artifact", null, null, mapOf(
                            "applicationId" to application["id"], "title" to "Resume", "type" to "resume", "format" to "markdown",
                            "content" to "Built Kotlin services", "sources" to listOf(HeadhunterStore.reference(evidence)),
                            "generation" to "assembled", "reviewStatus" to "proposed",
                        ))
                        artifactId = artifact["id"] as String
                        artifactCid = artifact["cid"] as String
                        store.save("review", null, null, mapOf("subjectId" to artifactId, "subjectCid" to artifactCid, "decision" to "approved", "notes" to "Checked original"))
                        store.save("evidence", evidenceId, evidenceCid, HeadhunterStore.fieldsOf(evidence) + ("text" to "Built and operated Kotlin services"))
                    }
                }
                DocumentCasStore.open(casPath, parent).use { cas ->
                    DocumentAppendLog.open(logPath, parent).use { log ->
                        val board = ConfixBlackboard.empty()
                        val store = HeadhunterStore(board, cas, log) { 2_000L }
                        assertEquals(5, store.restore())
                        assertContentEquals(original, cas.get(ContentId(originalCid)))
                        val artifact = store.record(artifactId)!!
                        assertEquals(artifactCid, artifact["cid"])
                        val source = HeadhunterStore.references(HeadhunterStore.fieldsOf(artifact))[0]
                        assertEquals(evidenceId, source.a)
                        assertEquals(evidenceCid, source.b)
                        assertEquals("Built Kotlin services", HeadhunterStore.fieldsOf(store.version(source.a, source.b)!!)["text"])
                        assertEquals("Built and operated Kotlin services", HeadhunterStore.fieldsOf(store.record(evidenceId)!!)["text"])
                        assertEquals(2, store.history(evidenceId).size)
                        val review = store.records().view.single { it["kind"] == "review" }
                        assertEquals(artifactCid, HeadhunterStore.fieldsOf(review)["subjectCid"])
                        assertEquals(5, board.keys().size)
                        val revision = board.snapshot().revision
                        store.save("artifact", artifactId, artifactCid, HeadhunterStore.fieldsOf(artifact))
                        assertEquals(revision, board.snapshot().revision)
                        assertEquals(1, store.history(artifactId).size)
                    }
                }
            } finally {
                File(casPath).delete()
                File(logPath).delete()
            }
        }
    }
}
