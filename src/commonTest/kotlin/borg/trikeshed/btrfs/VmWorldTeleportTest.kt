package borg.trikeshed.btrfs

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.couch.replicate.HttpExchange
import borg.trikeshed.couch.replicate.HttpReply
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.relaxfactory.RelaxTransport
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFails
import kotlin.test.assertSame

/**
 * A file-based btrfs VM world moving between nodes on the couch transport.
 *
 * A canonical root and its independently addressed file payloads traverse the
 * existing Couch replication exchange before the receiving world becomes visible.
 */
class VmWorldTeleportTest {

    private class WorldCas : CasStore() {
        var hidden: Set<ContentId> = emptySet()
        var reject: Set<ContentId> = emptySet()
        var discard: Set<ContentId> = emptySet()
        val puts = mutableMapOf<ContentId, Int>()
        override fun put(bytes: ByteArray): ContentId {
            val cid = ContentId.of(bytes)
            check(cid !in reject) { "Injected world CAS publication failure" }
            puts[cid] = (puts[cid] ?: 0) + 1
            if (cid in discard) return cid
            return super.put(bytes)
        }
        override fun get(cid: ContentId): ByteArray? = if (cid in hidden) null else super.get(cid)
    }

    /** One node: a couch database over its own CAS, plus a btrfs root for guest worlds. */
    private class Node(val name: String = "trikeshed", val cas: CasStore = CasStore.inMemory(), shared: Boolean = true) {
        val db = Couch(name, CouchStoreFactory.casBacked(cas), cas)
        // A file-shaped store over an in-memory backing: the teleport does not care which, and
        // this keeps the test off the real filesystem while exercising the durable code path.
        val worlds = BtrfsWorldStore.ofFiles(InMemoryFileOperations(cwd = "/"), "/vm-worlds", if (shared) cas else null)
        val teleport = VmWorldTeleport(db, worlds)
        val router = CouchWireRouter(db, PREFIX)

        fun mount(guest: String) = worlds.mount(guest)

        /** Give a guest a world with some content in it. */
        fun seedGuest(guest: String, files: Map<String, String>) {
            val fs = mount(guest)
            val subvol = worlds.subvolumeFor(guest)
            if (!fs.hasSubvolume(subvol)) check(fs.createSubvolume(subvol))
            for ((path, body) in files) check(fs.writeFile(subvol, path, body.encodeToByteArray()))
        }

        fun exchange(): HttpExchange = HttpExchange { method, url, body, _ ->
            val path = url.removePrefix("http://").substringAfter('/', "").let { "/$it" }
            val reply = router.handle(method, path, body ?: ByteArray(0))
            if (reply == null) HttpReply(404, ByteArray(0)) else HttpReply(reply.status, reply.bytes)
        }
    }

    companion object { const val PREFIX = "projects/trikeshed/" }

    @Test
    fun aPublishedWorldReplicatesToASecondNodeAndRestoresThere() = runTest {
        val a = Node()
        val b = Node()
        a.seedGuest("vm.worker", mapOf("workspace/model.txt" to "trained weights", "workspace/notes.md" to "# run 1"))

        val published = a.teleport.publish("vm.worker")
        assertEquals(true, published["ok"], "publish failed: $published")
        val cid = assertNotNull(published["cid"] as? String)
        assertTrue(a.teleport.isLocal("vm.worker"))

        // Node B knows nothing yet.
        assertTrue(b.teleport.published().isEmpty())
        assertFalse(b.teleport.restore("vm.worker"), "restore must fail before the world has replicated")

        // Ordinary replication — no VM-specific lane. The world is an attachment document.
        val report = CouchReplicator(b.db, a.exchange()).pull("http://a/trikeshed")
        assertTrue(report.docsWritten >= 1, "nothing replicated: $report")

        assertEquals(listOf("vm.worker"), b.teleport.published())
        assertTrue(b.teleport.isLocal("vm.worker"), "the document arrived but its blob did not")

        // And it reconstitutes into a real subvolume on B.
        assertTrue(b.teleport.restore("vm.worker"), "restore refused on the receiving node")
        val restored = b.mount("vm.worker")
        assertEquals(
            "trained weights",
            restored.fetchFile("vm.worker", "workspace/model.txt")?.decodeToString(),
            "the world crossed but its contents did not",
        )
        assertEquals("# run 1", restored.fetchFile("vm.worker", "workspace/notes.md")?.decodeToString())

        // Same bytes, same id on both sides — the world is content-addressed end to end.
        assertEquals(cid, b.db.docJson(b.teleport.docIdFor("vm.worker"))!!["contentId"])
        assertContentEquals(a.cas.get(borg.trikeshed.job.ContentId(cid)), b.cas.get(borg.trikeshed.job.ContentId(cid)))
    }

    @Test
    fun aPublishedWorldIsAnOrdinaryAttachmentOnEveryLaneTheServiceHas() = runTest {
        val a = Node()
        a.seedGuest("vm.small", mapOf("workspace/x.txt" to "hello"))
        val cid = a.teleport.publish("vm.small")["cid"] as String

        // the document renders a 1.x attachment stub
        val doc = a.db.docJson("vm-worlds/vm.small")!!
        val stub = (doc["_attachments"] as Map<*, *>)["content"] as Map<*, *>
        assertEquals(VmWorldTeleport.CONTENT_TYPE, stub["content_type"])
        assertEquals(true, stub["stub"])

        // All existing blob lanes serve the same canonical root; its references
        // name separate file objects instead of embedding an opaque world stream.
        val viaCas = a.router.handle("GET", "/trikeshed/_cas/$cid", ByteArray(0))!!
        val viaIpfs = a.router.handle("GET", "/api/v0/block/get?arg=$cid", ByteArray(0))!!
        val viaAttachment = a.router.handle("GET", "/trikeshed/vm-worlds/vm.small/content", ByteArray(0))!!
        assertContentEquals(viaCas.bytes, viaIpfs.bytes)
        assertContentEquals(viaCas.bytes, viaAttachment.bytes)

        // and a RequestFactory client pulls it with block_get, like any other block
        val proxy = RequestFactoryProxy(RelaxTransport.local(a.db))
        assertContentEquals(viaCas.bytes, proxy.blockGet(cid))
        val manifest = FileTreeManifest.decode(viaCas.bytes)
        assertEquals(1, manifest.references().size)
        assertContentEquals("hello".encodeToByteArray(), proxy.blockGet(manifest.references()[0].value))
    }

    @Test
    fun republishingAnUnchangedWorldStoresNoNewBytes() = runTest {
        val a = Node()
        a.seedGuest("vm.stable", mapOf("workspace/a.txt" to "one"))
        val first = a.teleport.publish("vm.stable")
        val second = a.teleport.publish("vm.stable")
        assertEquals(true, second["ok"], "republish failed: $second")
        assertEquals(first["cid"], second["cid"], "an unchanged world hashed to a different block")

        // a changed world does move the cid
        a.seedGuest("vm.stable", mapOf("workspace/a.txt" to "two"))
        val third = a.teleport.publish("vm.stable")
        assertTrue(third["cid"] != first["cid"], "a changed world kept its old block")
    }

    @Test
    fun publishingAGuestWithNoWorldSaysSoInsteadOfThrowing() = runTest {
        val a = Node()
        val r = a.teleport.publish("vm.never-ran")
        assertEquals(false, r["ok"])
        assertEquals("not_found", r["error"])
        assertNull(a.db.docJson("vm-worlds/vm.never-ran"))
    }

    @Test
    fun restoreRefusesToOverwriteALiveSubvolumeButWillUseAnotherName() = runTest {
        val a = Node()
        a.seedGuest("vm.live", mapOf("workspace/v.txt" to "v1"))
        a.teleport.publish("vm.live")
        // the guest keeps running and moves on
        a.seedGuest("vm.live", mapOf("workspace/v.txt" to "v2"))

        assertFalse(a.teleport.restore("vm.live"), "restore silently overwrote a live world")
        assertEquals("v2", a.mount("vm.live").fetchFile("vm.live", "workspace/v.txt")?.decodeToString())

        // an explicit name is the caller's decision, and it lands the published version beside it
        assertTrue(a.teleport.restore("vm.live", into = "vm.live.rollback"))
        val fs = a.mount("vm.live")
        assertEquals("v1", fs.fetchFile("vm.live.rollback", "workspace/v.txt")?.decodeToString())
        assertEquals("v2", fs.fetchFile("vm.live", "workspace/v.txt")?.decodeToString())
    }

    @Test
    fun canonicalIdentityAndSharedPayloadSurviveDifferentGuestNamesAndSnapshots() = runTest {
        val cas = WorldCas()
        val node = Node(cas = cas)
        val files = mapOf("workspace/library.jar" to "shared library", "workspace/config" to "one")
        node.seedGuest("vm.a", files)
        val payloadId = ContentId.of("shared library".encodeToByteArray())
        val writes = cas.puts[payloadId]
        val original = node.teleport.publish("vm.a")["cid"]
        assertEquals(node.mount("vm.a").manifestId("vm.a")?.value, original)
        node.seedGuest("vm.b", files)
        assertEquals(original, node.teleport.publish("vm.b")["cid"])
        assertSame(cas, node.worlds.cas)
        assertSame(cas, node.mount("vm.a").cas)
        assertEquals(writes, cas.puts[payloadId], "Shared file bytes were written to CAS again")
        val mount = node.mount("vm.a")
        assertTrue(mount.snapshot("vm.a", "vm.a.before"))
        assertEquals(original, mount.manifestId("vm.a.before")?.value)
        assertTrue(mount.writeFile("vm.a", "workspace/config", "two".encodeToByteArray()))
        assertTrue(node.teleport.publish("vm.a")["cid"] != original)
        assertEquals(writes, cas.puts[payloadId], "Changing one file republished an unrelated payload")
        assertContentEquals("one".encodeToByteArray(), node.mount("vm.a").fetchFile("vm.a.before", "workspace/config"))
    }

    @Test
    fun memoryWorldReopensTheSameGuestWithoutSharingGuestMetadata() = runTest {
        val world = BtrfsWorldStore.ofMemory()
        val mount = world.mount("vm.a")
        assertTrue(mount.createSubvolume("vm.a"))
        assertTrue(mount.writeFile("vm.a", "payload", "memory".encodeToByteArray()))
        assertSame(world.fileOpsFor("vm.a"), world.fileOpsFor("vm.a"))
        assertContentEquals("memory".encodeToByteArray(), world.mount("vm.a").fetchFile("vm.a", "payload"))
        assertFalse(world.mount("vm.b").hasSubvolume("vm.a"))
        val db = Couch("trikeshed", CouchStoreFactory.casBacked(world.cas), world.cas)
        val teleport = VmWorldTeleport(db, world)
        assertEquals(true, teleport.publish("vm.a")["ok"])
        assertTrue(teleport.restore("vm.a", "vm.a.copy"))
        assertContentEquals("memory".encodeToByteArray(), world.mount("vm.a").fetchFile("vm.a.copy", "payload"))
        assertFalse(BtrfsWorldStore.ofMemory().mount("vm.a").hasSubvolume("vm.a"))
    }

    @Test
    fun firstPublishMigratesPrivateCanonicalWorldWithoutPriorGuestReads() = runTest {
        val files = InMemoryFileOperations(cwd = "/")
        val original = UserspaceBtrfs("/worlds", files)
        assertTrue(original.createSubvolume("vm.upgrade"))
        val payload = "private extent".encodeToByteArray()
        assertTrue(original.writeFile("vm.upgrade", "workspace/file", payload))
        val rootId = original.manifestId("vm.upgrade")
        val cas = CasStore.inMemory()
        val db = Couch("trikeshed", CouchStoreFactory.casBacked(cas), cas)
        val world = BtrfsWorldStore.ofFiles(files, "/worlds", cas)
        val teleport = VmWorldTeleport(db, world)
        assertNull(cas.get(ContentId.of(payload)))
        assertEquals(rootId?.value, teleport.publish("vm.upgrade")["cid"])
        assertContentEquals(payload, cas.get(ContentId.of(payload)))
        assertTrue(teleport.isLocal("vm.upgrade"))
    }

    @Test
    fun missingOrCorruptChildCannotMakeAWorldLocalOrVisible() = runTest {
        val cas = WorldCas()
        val node = Node(cas = cas)
        node.seedGuest("vm.integrity", mapOf("workspace/file" to "intact"))
        val cid = ContentId.of("intact".encodeToByteArray())
        val root = ContentId(node.teleport.publish("vm.integrity")["cid"] as String)
        cas.hidden = setOf(cid)
        assertFalse(node.teleport.isLocal("vm.integrity"))
        assertFalse(node.teleport.restore("vm.integrity", "vm.missing"))
        assertFalse(node.mount("vm.integrity").hasSubvolume("vm.missing"))
        cas.hidden = emptySet()
        cas.corrupt(cid)
        assertFalse(node.teleport.isLocal("vm.integrity"))
        assertFalse(node.teleport.restore("vm.integrity", "vm.corrupt"))
        assertFalse(node.mount("vm.integrity").hasSubvolume("vm.corrupt"))
        cas.put("intact".encodeToByteArray())
        cas.hidden = setOf(root)
        assertFalse(node.teleport.isLocal("vm.integrity"))
        cas.hidden = emptySet()
        assertTrue(node.teleport.restore("vm.integrity", "vm.repaired"))
        assertContentEquals("intact".encodeToByteArray(), node.mount("vm.integrity").fetchFile("vm.repaired", "workspace/file"))
    }

    @Test
    fun missingChildPullWithholdsRevisionAndCheckpointUntilRetry() = runTest {
        val cas = WorldCas()
        val source = Node(cas = cas)
        val target = Node()
        source.seedGuest("vm.pull", mapOf("workspace/file" to "transfer"))
        source.teleport.publish("vm.pull")
        val cid = ContentId.of("transfer".encodeToByteArray())
        cas.hidden = setOf(cid)
        val replication = CouchReplicator(target.db, source.exchange())
        assertFails { replication.pull("http://source/trikeshed") }
        assertNull(target.db.docJson("vm-worlds/vm.pull"))
        val checkpoint = CouchReplicator.replicationId("pull", "http://source/trikeshed", "trikeshed")
        assertNull(target.db.localGet(checkpoint))
        assertFalse(target.teleport.restore("vm.pull"))
        cas.hidden = emptySet()
        assertEquals(1, replication.pull("http://source/trikeshed").docsWritten)
        assertTrue(target.teleport.isLocal("vm.pull"))
        assertTrue(target.teleport.restore("vm.pull"))
        assertContentEquals("transfer".encodeToByteArray(), target.mount("vm.pull").fetchFile("vm.pull", "workspace/file"))
    }

    @Test
    fun missingChildPushWithholdsRevisionAndCheckpointUntilRetry() = runTest {
        val cas = WorldCas()
        val source = Node(cas = cas)
        val target = Node()
        source.seedGuest("vm.push", mapOf("workspace/file" to "transfer"))
        source.teleport.publish("vm.push")
        cas.hidden = setOf(ContentId.of("transfer".encodeToByteArray()))
        val replication = CouchReplicator(source.db, target.exchange())
        assertFails { replication.push("http://target/trikeshed") }
        assertNull(target.db.docJson("vm-worlds/vm.push"))
        val checkpoint = CouchReplicator.replicationId("push", "trikeshed", "http://target/trikeshed")
        assertNull(source.db.localGet(checkpoint))
        cas.hidden = emptySet()
        assertEquals(1, replication.push("http://target/trikeshed").docsWritten)
        assertTrue(target.teleport.restore("vm.push"))
        assertContentEquals("transfer".encodeToByteArray(), target.mount("vm.push").fetchFile("vm.push", "workspace/file"))
    }

    @Test
    fun legacyOpaqueDocumentsStillReplicateAndRestore() = runTest {
        val source = Node()
        val target = Node()
        source.seedGuest("vm.legacy", mapOf("workspace/file" to "legacy\nbytes"))
        val original = source.mount("vm.legacy")
        val stream = assertNotNull(original.send("vm.legacy"))
        val cid = source.db.blockPut(stream)
        assertEquals(true, source.db.put("vm-worlds/vm.legacy", mapOf(
            "kind" to VmWorldTeleport.KIND, "guest" to "vm.legacy", "contentId" to cid.value,
            "contentType" to VmWorldTeleport.LEGACY_CONTENT_TYPE, "length" to stream.size.toLong(),
        ), null)["ok"])
        assertEquals(1, CouchReplicator(target.db, source.exchange()).pull("http://source/trikeshed").docsWritten)
        assertTrue(target.teleport.isLocal("vm.legacy"))
        assertTrue(target.teleport.restore("vm.legacy"))
        assertContentEquals("legacy\nbytes".encodeToByteArray(), target.mount("vm.legacy").fetchFile("vm.legacy", "workspace/file"))
        assertEquals(original.manifestId("vm.legacy"), target.mount("vm.legacy").manifestId("vm.legacy"))
        val canonical = target.teleport.publish("vm.legacy")
        assertEquals(original.manifestId("vm.legacy")?.value, canonical["cid"])
        assertTrue(canonical["cid"] != cid.value)
        assertEquals(FileTreeManifest.CONTENT_TYPE, target.db.docJson("vm-worlds/vm.legacy")?.get("contentType"))
    }

    @Test
    fun rejectedCasPublicationExposesNeitherDocumentNorRestoredSubvolume() = runTest {
        val rejecting = WorldCas()
        val node = Node(cas = rejecting, shared = false)
        node.seedGuest("vm.rejected", mapOf("workspace/file" to "required"))
        val payload = ContentId.of("required".encodeToByteArray())
        rejecting.reject = setOf(payload)
        assertFails { node.teleport.publish("vm.rejected") }
        assertNull(node.db.docJson("vm-worlds/vm.rejected"))
        rejecting.reject = emptySet()
        assertEquals(true, node.teleport.publish("vm.rejected")["ok"])
        val destinationCas = WorldCas().also { it.reject = setOf(payload) }
        val destination = BtrfsWorldStore.ofMemory(destinationCas)
        val restore = VmWorldTeleport(node.db, destination)
        assertFails { restore.restore("vm.rejected") }
        assertFalse(destination.mount("vm.rejected").hasSubvolume("vm.rejected"))
    }

    @Test
    fun singleBlobFallbackResolvesChildrenBeforePublishingTheRevision() = runTest {
        val cas = WorldCas()
        val source = Node(cas = cas)
        val target = Node()
        source.seedGuest("vm.fallback", mapOf("workspace/file" to "fallback bytes"))
        source.teleport.publish("vm.fallback")
        cas.hidden = setOf(ContentId.of("fallback bytes".encodeToByteArray()))
        val exchange = source.exchange()
        val replication = CouchReplicator(target.db, HttpExchange { method, url, body, type ->
            if (url.endsWith("/_cas/_bulk")) HttpReply(404, ByteArray(0))
            else exchange.call(method, url, body, type)
        })
        assertFails { replication.pull("http://source/trikeshed") }
        assertNull(target.db.docJson("vm-worlds/vm.fallback"))
        val checkpoint = CouchReplicator.replicationId("pull", "http://source/trikeshed", "trikeshed")
        assertNull(target.db.localGet(checkpoint))
        cas.hidden = emptySet()
        assertEquals(1, replication.pull("http://source/trikeshed").docsWritten)
        assertTrue(target.teleport.restore("vm.fallback"))
        assertContentEquals("fallback bytes".encodeToByteArray(), target.mount("vm.fallback").fetchFile("vm.fallback", "workspace/file"))
    }

    @Test
    fun unpersistedCasWriteFailsPullAndRetryRepairsTheWorld() = runTest {
        val source = Node()
        val cas = WorldCas()
        val target = Node(cas = cas)
        source.seedGuest("vm.discard", mapOf("workspace/file" to "required bytes"))
        source.teleport.publish("vm.discard")
        cas.discard = setOf(ContentId.of("required bytes".encodeToByteArray()))
        val replication = CouchReplicator(target.db, source.exchange())
        assertFails { replication.pull("http://source/trikeshed") }
        assertNull(target.db.docJson("vm-worlds/vm.discard"))
        assertNull(target.db.localGet(CouchReplicator.replicationId("pull", "http://source/trikeshed", "trikeshed")))
        cas.discard = emptySet()
        assertEquals(1, replication.pull("http://source/trikeshed").docsWritten)
        assertTrue(target.teleport.restore("vm.discard"))
        assertContentEquals("required bytes".encodeToByteArray(), target.mount("vm.discard").fetchFile("vm.discard", "workspace/file"))
    }

    @Test
    fun invalidManifestAndExtentLengthsCannotPublishAWorldRevision() = runTest {
        val payload = "extent".encodeToByteArray()
        val extentId = ContentId.of(payload)
        val valid = CanonicalCbor.encodeMap(mapOf("format" to FileTreeManifest.FORMAT,
            "entries" to mapOf("file" to mapOf("contentId" to extentId.value, "length" to payload.size.toLong()))))
        val roots = arrayOf(
            valid + byteArrayOf(0),
            CanonicalCbor.encodeMap(mapOf("format" to FileTreeManifest.FORMAT, "entries" to mapOf("../escape" to null))),
            CanonicalCbor.encodeMap(mapOf("format" to FileTreeManifest.FORMAT,
                "entries" to mapOf("file" to mapOf("contentId" to extentId.value, "length" to payload.size.toLong() + 1)))),
            valid,
        )
        for ((index, root) in roots.withIndex()) {
            val source = Node()
            val target = Node()
            source.db.blockPut(payload)
            val cid = source.db.blockPut(root)
            val body = mapOf("kind" to VmWorldTeleport.KIND, "guest" to "vm.invalid", "contentId" to cid.value,
                "contentType" to FileTreeManifest.CONTENT_TYPE, "length" to root.size.toLong() + if (index == 3) 1 else 0)
            assertEquals(true, source.db.put("vm-worlds/vm.invalid", body, null)["ok"])
            assertFalse(source.teleport.isLocal("vm.invalid"))
            assertFalse(source.teleport.restore("vm.invalid"))
            assertFalse(source.mount("vm.invalid").hasSubvolume("vm.invalid"))
            assertFails { CouchReplicator(target.db, source.exchange()).pull("http://source/trikeshed") }
            assertNull(target.db.docJson("vm-worlds/vm.invalid"))
            assertNull(target.db.localGet(CouchReplicator.replicationId("pull", "http://source/trikeshed", "trikeshed")))
        }
    }

    @Test
    fun receiverRejectsAWorldRevisionUntilItsChildrenArrive() = runTest {
        val source = Node()
        val target = Node()
        source.seedGuest("vm.receiver", mapOf("workspace/file" to "receiver bytes"))
        val rootId = ContentId(source.teleport.publish("vm.receiver")["cid"] as String)
        target.cas.put(assertNotNull(source.cas.get(rootId)))
        val doc = assertNotNull(source.db.docJson("vm-worlds/vm.receiver"))
        val rejected = target.db.bulkDocs(listOf(doc), newEdits = false).single()
        assertEquals("invalid_attachment", rejected["error"])
        assertNull(target.db.docJson("vm-worlds/vm.receiver"))
        target.cas.put("receiver bytes".encodeToByteArray())
        assertEquals(true, target.db.bulkDocs(listOf(doc), newEdits = false).single()["ok"])
        assertTrue(target.teleport.restore("vm.receiver"))
    }

    @Test
    fun failedPushAcknowledgementsNeverAdvanceTheCheckpoint() = runTest {
        for (failedPath in arrayOf("/_cas", "/_bulk_docs")) {
            val source = Node()
            val target = Node()
            source.seedGuest("vm.ack", mapOf("workspace/file" to "acknowledged bytes"))
            source.teleport.publish("vm.ack")
            val exchange = target.exchange()
            var reject = true
            val replication = CouchReplicator(source.db, HttpExchange { method, url, body, type ->
                if (reject && url.endsWith(failedPath)) {
                    val response = if (failedPath == "/_cas") """{"ok":true,"cid":"wrong"}"""
                        else """[{"id":"vm-worlds/vm.ack","error":"invalid_attachment"}]"""
                    HttpReply(200, response.encodeToByteArray())
                } else exchange.call(method, url, body, type)
            })
            assertFails { replication.push("http://target/trikeshed") }
            assertNull(target.db.docJson("vm-worlds/vm.ack"))
            assertNull(source.db.localGet(CouchReplicator.replicationId("push", "trikeshed", "http://target/trikeshed")))
            reject = false
            assertEquals(1, replication.push("http://target/trikeshed").docsWritten)
            assertTrue(target.teleport.restore("vm.ack"))
        }
    }
}
