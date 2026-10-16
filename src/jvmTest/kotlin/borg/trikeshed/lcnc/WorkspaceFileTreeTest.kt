package borg.trikeshed.lcnc

import borg.trikeshed.btrfs.*
import borg.trikeshed.cas.*
import borg.trikeshed.couch.isam.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.Syntax
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceFileTreeTest {
    val tests = WorkspaceStorageTest()
    fun source(rev: String, facet: Boolean = false) = mapOf(
        "kind" to "couch", "id" to "vm-worlds/review", "rev" to rev,
    ) + if (facet) mapOf("facet" to "file-tree") else emptyMap()

    @Test fun publishedWorldMetadataIsPinnedDenseAndIndependentOfLiveFiles() = runTest {
        tests.fixture(this, dictionaries = true) { f ->
            val world = BtrfsWorldStore.ofMemory(f.cas)
            val mount = world.mount("review")
            assertTrue(mount.createSubvolume("review"))
            assertTrue(mount.writeFile("review", "case/医.txt", "synthetic evidence".encodeToByteArray()))
            val teleport = VmWorldTeleport(f.couch, world)
            val first = teleport.publish("review")
            val rev1 = first["rev"] as String
            val cid1 = first["cid"] as String
            assertTrue(mount.writeFile("review", "case/医.txt", "changed live guest".encodeToByteArray()))
            val second = teleport.publish("review")
            val rev2 = second["rev"] as String
            val cid2 = second["cid"] as String
            assertNotEquals(cid1, cid2)
            val checkpoint = f.commits.localPut("replicator", mapOf("last_seq" to 41L))
            val couchHead = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            val boardRevision = f.board.snapshot().revision
            val liveRoot = mount.manifestId("review")
            f.storage.importCouch()
            val pointerHead = Files.readAllBytes(Path.of(f.location + ".store/HEAD"))
            val treeDirectory = Path.of(f.location + ".file-trees.store")
            var service = f.service()
            val pointer = tests.request(service, "POST", mapOf("source" to source(rev1)))
            assertEquals(false, (pointer["fileTree"] as Map<*, *>)["indexed"])
            assertFalse(Files.exists(treeDirectory), "Inspection must not create the metadata projection")
            assertEquals(404, service.route("POST", jsonOf(mapOf("source" to source(rev1, true)))).status)
            val dictionary = f.cas.put("paths contentId length clinical review metadata".encodeToByteArray())
            val receipt = tests.request(service, "PUT", mapOf("action" to "index-file-tree",
                "source" to source(rev1), "dictionaryCid" to dictionary.value))
            assertEquals(cid1, receipt["cid"])
            assertEquals(dictionary.value, (receipt["compression"] as Map<*, *>)["dictionaryCid"])
            val root = Files.readAllBytes(treeDirectory.resolve("HEAD"))
            val repeated = tests.request(service, "PUT", mapOf("action" to "index-file-tree", "source" to source(rev1)))
            assertEquals(0, (repeated["imported"] as Number).toInt())
            assertContentEquals(root, Files.readAllBytes(treeDirectory.resolve("HEAD")))
            assertContentEquals(couchHead, Files.readAllBytes(f.root.resolve("couch/HEAD")))
            assertContentEquals(pointerHead, Files.readAllBytes(Path.of(f.location + ".store/HEAD")))
            // A new Couch revision with the same tree must reuse the same dense root.
            val repeatedPublication = teleport.publish("review")
            f.storage.importFileTree("vm-worlds/review", rev2)
            f.storage.importCouch()
            assertEquals(0, f.storage.importFileTree("vm-worlds/review", repeatedPublication["rev"] as String)["imported"])
            f.reopen(); service = f.service()
            f.resolvedDictionaries.clear()
            val query = mapOf("source" to source(rev1, true), "path" to listOf("entries", "case/医.txt", "contentId"),
                "cursor" to mapOf("path" to listOf(0), "axes" to listOf(listOf(2), listOf(0))))
            val read = tests.request(service, "POST", query)
            val confix = read["confix"] as Map<*, *>
            assertEquals(cid1, confix["cid"])
            assertEquals(ContentId.of("synthetic evidence".encodeToByteArray()).value, confix["value"])
            assertEquals(2, ((confix["cursor"] as Map<*, *>)["indexes"] as List<*>).size)
            assertEquals(setOf(dictionary), f.resolvedDictionaries.toSet())
            val later = tests.request(service, "POST", query + ("source" to source(rev2, true)))
            assertEquals(cid2, (later["confix"] as Map<*, *>)["cid"])
            assertEquals(400, service.route("POST", jsonOf(query + ("cursor" to mapOf("sourceCid" to cid2)))).status)
            assertEquals(liveRoot, mount.manifestId("review"))
            assertEquals(checkpoint, f.commits.localGet("replicator"))
            // The only Couch change was the deliberate repeated publication, not indexing.
            val finalCouchHead = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            tests.request(service, "POST", query)
            assertContentEquals(finalCouchHead, Files.readAllBytes(f.root.resolve("couch/HEAD")))
            assertEquals(boardRevision, f.board.snapshot().revision)
        }
    }

    @Test fun invalidRetainedMetadataAndUnpinnedRequestsCannotCreateAProjection() = runTest {
        tests.fixture(this) { f ->
            val valid = FileTreeManifest.of(s_["case.txt" j (ContentId.of("absent payload".encodeToByteArray()) j 14L)]).encode()
            val invalid = CanonicalCbor.encodeMap(mapOf("format" to "file-tree-v1", "entries" to mapOf("../escape" to null)))
            val missing = ContentId.of(valid)
            val invalidCid = f.cas.put(invalid)
            f.couch.put("control", mapOf("status" to "unchanged"), null)
            // Durable Couch rejects these at ingress. Seed hostile retained pointer documents
            // directly through the generic Confix bridge to exercise the projection's own boundary.
            val revisions = ConfixIsamCursorBridge(f.location, f.files, f.cas).use { bridge ->
                listOf(missing to valid.size.toLong(), invalidCid to invalid.size.toLong(),
                    invalidCid to invalid.size + 1L, invalidCid to 1_048_577L, missing to valid.size + 1L)
                    .mapIndexed { i, (cid, length) ->
                        val rev = "retained-$i"
                        bridge.put(i.toLong(), "vm-worlds/review", rev, CanonicalCbor.encodeMap(mapOf(
                            "kind" to VmWorldTeleport.KIND, "guest" to "review", "contentType" to FileTreeManifest.CONTENT_TYPE,
                            "contentId" to cid.value, "length" to length,
                        )), Syntax.CBOR)
                        rev
                    }
            }
            val directory = Path.of(f.location + ".file-trees.store")
            val service = f.service()
            val head = Files.readAllBytes(f.root.resolve("couch/HEAD"))
            for (rev in revisions) {
                val result = service.route("PUT", jsonOf(mapOf("action" to "index-file-tree", "source" to source(rev))))
                assertEquals(400, result.status, result.body)
                assertFalse(Files.exists(directory), result.body)
            }
            for (method in listOf("POST", "PUT")) {
                val result = service.route(method, jsonOf(mapOf("action" to "index-file-tree",
                    "source" to (source(revisions.first(), true) - "rev"))))
                assertEquals(400, result.status, result.body)
                assertFalse(Files.exists(directory))
            }
            f.cas.put(valid)
            // Metadata indexing must succeed without any referenced file payload in CAS.
            assertEquals(1, f.storage.importFileTree("vm-worlds/review", revisions.first())["imported"])
            val prior = Files.readAllBytes(directory.resolve("HEAD"))
            assertFailsWith<IllegalArgumentException> { f.storage.importFileTree("vm-worlds/review", revisions[1]) }
            assertFailsWith<IllegalArgumentException> { f.storage.importFileTree("vm-worlds/review", revisions.last()) }
            assertFailsWith<IllegalArgumentException> { f.storage.fileTree("vm-worlds/review", revisions.last(), read = true) }
            assertContentEquals(prior, Files.readAllBytes(directory.resolve("HEAD")))
            assertContentEquals(head, Files.readAllBytes(f.root.resolve("couch/HEAD")))
            assertNotNull(f.storage.fileTree("vm-worlds/review", revisions.first(), read = true)?.b)
        }
    }

    @Test fun corruptRootsAndUnavailableDictionariesDoNotFallBack() = runTest {
        tests.fixture(this) { f ->
            val bytes = FileTreeManifest.of(s_["case" j null]).encode()
            val cid = f.cas.put(bytes)
            val publication = f.couch.put("vm-worlds/review", mapOf("kind" to VmWorldTeleport.KIND,
                "guest" to "review", "contentType" to FileTreeManifest.CONTENT_TYPE,
                "contentId" to cid.value, "length" to bytes.size.toLong()), null)
            val rev = publication["rev"] as String
            f.storage.importCouch()
            val directory = Path.of(f.location + ".file-trees.store")
            val dictionary = f.cas.put("dictionary".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { f.storage.importFileTree("vm-worlds/review", rev, dictionary) }
            assertFalse(Files.exists(directory))
            val path = f.root.resolve("cas").resolve(CasPaths.blob(cid))
            Files.write(path, bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            assertFailsWith<IllegalStateException> { f.storage.importFileTree("vm-worlds/review", rev) }
            assertFalse(Files.exists(directory))
            f.cas.put(bytes)
            assertEquals(1, f.storage.importFileTree("vm-worlds/review", rev)["imported"])
            f.storage.drain()
            assertTrue(f.storage.job.isCompleted)
            assertFailsWith<IllegalStateException> { f.storage.fileTree("vm-worlds/review", rev, read = true) }
            f.reopen()
            assertNotNull(f.storage.fileTree("vm-worlds/review", rev, read = true)?.b)
        }
    }
}
