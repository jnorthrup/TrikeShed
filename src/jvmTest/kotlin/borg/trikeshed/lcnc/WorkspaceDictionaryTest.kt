package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.ConfixIsamElement
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceDictionaryTest {
    val storageTests = WorkspaceStorageTest()

    @Test fun batchesResolveTheirOwnDictionariesAfterReopenWithoutChangingCouch() = runTest {
        storageTests.fixture(this, dictionaries = true) { f ->
            val dictionaries = listOf("synthetic clinical evidence", "synthetic legal parties").map {
                f.couch.blockPut(it.encodeToByteArray())
            }
            val checkpoint = f.commits.localPut("replicator", mapOf("last_seq" to 37L))
            val sources = (0..2).map { i -> mapOf("review" to i, "terms" to listOf("evidence", "reviewed", "医")) }
            sources.forEachIndexed { i, source -> f.couch.put("case-$i", source, null) }
            val head = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            val boardRevision = f.board.snapshot().revision
            var executions = 0
            val service = f.service()
            service.context.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
            val compression = (storageTests.request(service, "GET")["storage"] as Map<*, *>)["compression"] as Map<*, *>
            assertEquals(true, compression["dictionaryAvailable"])
            assertNull(compression["defaultDictionaryCid"])
            for (cid in dictionaries + listOf(null)) {
                val receipt = storageTests.request(service, "PUT", mapOf("action" to "index-couch", "limit" to 1,
                    "dictionaryCid" to cid?.value))
                assertEquals(1, (receipt["imported"] as Number).toInt())
                assertEquals(cid?.value, (receipt["compression"] as Map<*, *>)["dictionaryCid"])
            }
            f.reopen()
            f.resolvedDictionaries.clear()
            for (i in sources.indices) {
                val response = storageTests.request(f.service(), "POST", mapOf("source" to mapOf("kind" to "couch", "id" to "case-$i")))
                val value = (response["confix"] as Map<*, *>)["value"] as Map<*, *>
                assertEquals(i, (value["review"] as Number).toInt())
                assertEquals(sources[i]["terms"], value["terms"])
            }
            // The decoder was selected from immutable leaf headers, not a remembered batch choice.
            assertEquals(dictionaries.toSet(), f.resolvedDictionaries.toSet())
            assertContentEquals(head, Files.readAllBytes(f.root.resolve("couch/HEAD")))
            assertEquals(checkpoint, f.commits.localGet("replicator"))
            assertEquals(boardRevision, f.board.snapshot().revision)
            assertEquals(0, executions)

            f.storage.drain()
            val plain = ConfixIsamElement(this, f.couch.store.changes, f.location, f.files, f.cas)
            try {
                assertFailsWith<IllegalArgumentException> { plain.document("case-0") }
                assertNotNull(plain.document("case-2"), "Dictionary-free history remains independently readable")
            } finally { plain.drain() }
        }
    }

    @Test fun invalidSelectionsLeaveTheDestinationAndItsPriorRootUnchanged() = runTest {
        storageTests.fixture(this, dictionaries = true) { f ->
            f.couch.put("first", mapOf("review" to "pending"), null)
            f.couch.put("second", mapOf("review" to "done"), null)
            val service = f.service()
            val missing = ContentId.of("not stored".encodeToByteArray()).value
            val empty = f.couch.blockPut(byteArrayOf()).value
            for (cid in listOf<Any>(missing, empty, "not-a-cid", 17)) {
                val response = service.route("PUT", jsonOf(mapOf("action" to "index-couch", "dictionaryCid" to cid)))
                assertEquals(400, response.status, response.body)
                assertFalse(Files.exists(Path.of(f.location + ".store")))
            }
            storageTests.request(service, "PUT", mapOf("action" to "index-couch", "limit" to 1))
            val headPath = Path.of(f.location + ".store/HEAD")
            val head = Files.readAllBytes(headPath)
            assertEquals(400, service.route("PUT", jsonOf(mapOf("action" to "index-couch", "dictionaryCid" to missing))).status)
            assertContentEquals(head, Files.readAllBytes(headPath))
            assertNotNull(f.storage.document("first"))
            assertNull(f.storage.document("second"))
        }
    }
}
