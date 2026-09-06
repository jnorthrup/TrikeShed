package borg.trikeshed.kanban.module

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.treedoc.TreeDocK
import borg.trikeshed.treedoc.TreeDocPipeline
import borg.trikeshed.treedoc.TreeDocument
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ArchiveServiceTest {
    private fun request(path: String = "nested/quoted \"name\".txt", bytes: String = "aGVsbG8=") =
        JsonSupport.stringify(mapOf("entries" to listOf(mapOf("path" to path, "mediaType" to "text/plain", "base64" to bytes))))

    @Test fun importReopensWithNewServiceAndRestoresBytes(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val first = ArchiveService(cas).route("POST", "/api/archives/import", request())
        assertEquals(201, first.status, first.body)
        val manifest = JsonSupport.parse(first.body) as Map<*, *>
        val cid = manifest["cid"] as String
        val reopened = ArchiveService(cas).route("GET", "/api/archives/manifest?cid=$cid", "")
        assertEquals(200, reopened.status, reopened.body)
        assertEquals(first.body, reopened.body)
        val restored = ArchiveService(cas).route("GET", "/api/archives/content?cid=$cid&entry=0", "")
        assertEquals(200, restored.status, restored.body)
        assertContentEquals("hello".encodeToByteArray(), restored.bytes)
        assertEquals(first.body, ArchiveService(cas).route("POST", "/api/archives/import", request()).body)
    }

    @Test fun serverRejectsUnsafePathsEncodingsBudgetsAndMethods(): Unit = runBlocking {
        val service = ArchiveService(CasStore.inMemory())
        for (path in listOf("../escape", "/absolute", "C:/drive", "a\\b", "a//b", "a/./b", "__proto__", "a/".repeat(13))) {
            assertEquals(400, service.route("POST", "/api/archives/import", request(path)).status, path)
        }
        assertEquals(400, service.route("POST", "/api/archives/import", request(bytes = "not base64!")).status)
        assertEquals(400, service.route("POST", "/api/archives/import", request("directory/")).status)
        assertEquals(413, service.route("POST", "/api/archives/import", " ".repeat(ArchiveService.MAX_BODY + 1)).status)
        assertEquals(405, service.route("GET", "/api/archives/import", "").status)
        assertEquals(400, service.route("GET", "/api/archives/manifest?cid=bad", "").status)
        val missing = ContentId.of(byteArrayOf(99)).value
        assertEquals(404, service.route("GET", "/api/archives/manifest?cid=$missing", "").status)
    }

    @Test fun invalidBatchPerformsNoCasWrites(): Unit = runBlocking {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
        }
        for (paths in listOf(listOf("a", "a/b"), listOf("a/b", "a"), listOf("a", "a/"), listOf("a", "a"))) {
            val body = JsonSupport.stringify(mapOf("entries" to paths.map { mapOf("path" to it, "base64" to "") }))
            assertEquals(400, ArchiveService(cas).route("POST", "/api/archives/import", body).status)
        }
        assertEquals(0, writes)
    }

    @Test fun serverEnforcesEntryAndExpandedByteLimitsWithoutTrustingBrowser(): Unit = runBlocking {
        val service = ArchiveService(CasStore.inMemory())
        val tooMany = JsonSupport.stringify(mapOf("entries" to (0..ArchiveService.MAX_ENTRIES).map { mapOf("path" to "file$it", "base64" to "") }))
        assertEquals(400, service.route("POST", "/api/archives/import", tooMany).status)
        val oversized = "AAAA".repeat(ArchiveService.MAX_FILE / 3 + 2)
        assertEquals(400, service.route("POST", "/api/archives/import", request(bytes = oversized)).status)
        val full = "AAAA".repeat(ArchiveService.MAX_FILE / 3)
        val batch = JsonSupport.stringify(mapOf("entries" to (0..2).map { mapOf("path" to "file$it", "base64" to full) }))
        assertTrue(service.route("POST", "/api/archives/import", batch).status in listOf(400, 413))
        assertTrue(service.route("POST", "/api/archives/import", "{").status in listOf(400, 422))
    }

    @Test fun persistedFramesAreBoundedAndVerifiedOnRestore(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val pipeline = TreeDocPipeline(cas, 2)
        val bytes = "hello".encodeToByteArray()
        val archive = pipeline.store(listOf(TreeDocument("a.txt", "text/plain", bytes)).toSeries())
        val cid = archive.b(TreeDocK.ArchiveId.ordinal) as ContentId
        val reopened = pipeline.open(cid, 1, 3, 4096)
        assertContentEquals(bytes, pipeline.restoreDocument(reopened, 0, 5))
        assertFailsWith<IllegalArgumentException> { pipeline.restoreDocument(reopened, 0, 4) }
        assertFailsWith<IllegalArgumentException> { pipeline.open(cid, 1, 2, 4096) }
        assertFailsWith<IllegalArgumentException> { pipeline.open(cid, 0, 3, 4096) }
        assertFailsWith<IllegalArgumentException> { pipeline.open(cid, 1, 3, 1) }
        assertEquals(400, ArchiveService(cas).route("GET", "/api/archives/content?cid=${cid.value}&entry=1", "").status)
        cas.corrupt(ContentId.of("he".encodeToByteArray()))
        assertEquals(422, ArchiveService(cas).route("GET", "/api/archives/content?cid=${cid.value}&entry=0", "").status)
    }
}
