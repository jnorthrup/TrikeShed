package borg.trikeshed.btrfs

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.couch.replicate.HttpExchange
import borg.trikeshed.couch.replicate.HttpReply
import borg.trikeshed.job.CasStore
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

/**
 * A file-based btrfs VM world moving between nodes on the couch transport.
 *
 * Every piece of this existed and none of it was joined: worlds are content-addressed,
 * [UserspaceBtrfs.send]/`receive` produce and verify a self-contained stream, and the replicator
 * ships any blob a document's `contentId` names. [VmWorldTeleport] is the join, so a VM world
 * replicates as an ordinary attachment document rather than needing a lane of its own.
 */
class VmWorldTeleportTest {

    /** One node: a couch database over its own CAS, plus a btrfs root for guest worlds. */
    private class Node(val name: String = "trikeshed") {
        val cas: CasStore = CasStore.inMemory()
        val db = CouchDatabase(name, CouchStoreFactory.casBacked(cas), cas)
        // A file-shaped store over an in-memory backing: the teleport does not care which, and
        // this keeps the test off the real filesystem while exercising the durable code path.
        val worlds = BtrfsWorldStore.ofFiles(InMemoryFileOperations(cwd = "/"), "/vm-worlds")
        val teleport = VmWorldTeleport(db, worlds)
        val router = CouchWireRouter(db, PREFIX)

        fun mount(guest: String) = UserspaceBtrfs(worlds.root, worlds.fileOpsFor(guest))

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

        // the couch CAS lane, the IPFS alias, and the attachment route all serve the same stream
        val viaCas = a.router.handle("GET", "/trikeshed/_cas/$cid", ByteArray(0))!!
        val viaIpfs = a.router.handle("GET", "/api/v0/block/get?arg=$cid", ByteArray(0))!!
        val viaAttachment = a.router.handle("GET", "/trikeshed/vm-worlds/vm.small/content", ByteArray(0))!!
        assertContentEquals(viaCas.bytes, viaIpfs.bytes)
        assertContentEquals(viaCas.bytes, viaAttachment.bytes)

        // and a RequestFactory client pulls it with block_get, like any other block
        val proxy = RequestFactoryProxy(RelaxTransport.local(a.db))
        assertContentEquals(viaCas.bytes, proxy.blockGet(cid))
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
}
