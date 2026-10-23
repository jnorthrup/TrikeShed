package borg.trikeshed.skinnerbox

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class MeshSnapshotRecoveryTest {
    private val peers = s_[meshPeer(1), meshPeer(2), meshPeer(3)]
    private val cell = MeshCell("tenant", peers, CanonicalCbor.encodeMap(mapOf("fixture" to "retained-snapshot")))
    private val owner = cell.owner.id
    private val reader = cell.replicas.a.id
    private val grants = peers.view.associate { it.id to setOf(cell.namespace) }
    private fun task(verb: String, body: Map<String, Any?>) = MeshTask(cell.namespace, verb, CanonicalCbor.encodeMap(body))
    private fun put(request: String, value: String, previous: String? = null) = task("row.put", mapOf(
        "requestId" to request, "collection" to "tasks", "key" to "one", "expectedRevision" to previous,
        "value" to value.encodeToByteArray()))
    private suspend fun retain(store: MeshPersistence, bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        store.execute(reader, task("replica.retain", mapOf("contentId" to cid.value, "value" to bytes)))
        return cid
    }
    private fun packet(receipt: ByteArray): ByteArray {
        val event = MeshPersistenceCodec.decode(receipt)["event"] as ByteArray
        val fields = MeshPersistenceCodec.decode(event)
        return CanonicalCbor.encodeMap(mapOf("format" to "skinnerbox.retention/v1", "epoch" to cell.epoch.value,
            "namespace" to cell.namespace, "actor" to fields["peer"], "task" to fields["requestDigest"], "event" to event))
    }

    @Test
    fun originalOwnerSnapshotRestoresBinaryValuesDeletesAndIdenticalRetryReceipts() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-snapshot-").toString()
        suspend fun open(name: String) = MeshPersistence.open(this, FileCasStore(files, "$root/$name/cas"), files, "$root/$name/db", grants)
        val primary = open("primary")
        val source = open("source")
        var destination = open("destination")
        try {
            primary.execute(owner, task("cell.bind", mapOf("epoch" to cell.epoch.value)))
            retain(source, cell.bytes())
            val mutations = mutableListOf<Pair<MeshTask, ByteArray>>()
            suspend fun commit(operation: MeshTask): Map<String, Any?> {
                val receipt = primary.execute(owner, operation)
                mutations.add(operation to receipt)
                retain(source, packet(receipt))
                return MeshPersistenceCodec.decode(receipt)
            }
            val first = commit(put("first", "{\"counter\":9007199254740993}"))
            // A metadata commit is absent from the retained event stream. Recovery must preserve its sequence gap explicitly.
            retain(primary, cell.bytes())
            val bytes = ByteArray(4097) { (it % 251).toByte() }
            val objectReply = commit(task("object.put", mapOf("requestId" to "binary", "collection" to "assets", "key" to "blob",
                "expectedRevision" to null, "value" to bytes)))
            val second = commit(put("second", "{\"counter\":2}", first["revision"] as String))
            commit(task("row.delete", mapOf("requestId" to "deleted", "collection" to "tasks", "key" to "one",
                "expectedRevision" to second["revision"])))
            val snapshot = MeshRecovery.export(cell, source, reader)
            assertEquals(4, snapshot.packets.size)
            assertEquals(6L, snapshot.cutoffSequence)
            assertEquals(snapshot.id, MeshSnapshot.decode(snapshot.bytes(), cell).id)
            val result = MeshRecovery.restore(snapshot, cell, source, reader, destination, owner)
            assertEquals(MeshRecoveryResult(snapshot.id, 4, 6), result)
            destination.drain()
            destination = open("destination")
            for ((operation, receipt) in mutations) assertContentEquals(receipt, destination.execute(owner, operation))
            val loaded = MeshPersistenceCodec.decode(destination.execute(owner, task("object.get", mapOf("collection" to "assets", "key" to "blob"))))
            assertContentEquals(bytes, loaded["value"] as ByteArray)
            assertEquals(objectReply["revision"], loaded["revision"])
            assertEquals(MeshPersistenceError.NOT_FOUND, assertFailsWith<MeshPersistenceException> {
                destination.execute(owner, task("row.get", mapOf("collection" to "tasks", "key" to "one")))
            }.reason)
            assertFails { MeshRecovery.restore(snapshot, cell, source, reader, destination, owner) }
            assertFails { MeshRecovery.restore(snapshot, cell, source, reader, destination, reader) }
        } finally {
            destination.drain(); source.drain(); primary.drain(); files.deleteRecursively(root)
        }
    }

    @Test
    fun missingPredecessorAndWrongPinnedEpochFailBeforeDestinationAdmission() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-snapshot-gap-").toString()
        suspend fun open(name: String) = MeshPersistence.open(this, FileCasStore(files, "$root/$name/cas"), files, "$root/$name/db", grants)
        val primary = open("primary")
        val source = open("source")
        val destination = open("destination")
        try {
            primary.execute(owner, task("cell.bind", mapOf("epoch" to cell.epoch.value)))
            retain(source, cell.bytes())
            val first = MeshPersistenceCodec.decode(primary.execute(owner, put("first", "{\"value\":1}")))
            val second = primary.execute(owner, put("second", "{\"value\":2}", first["revision"] as String))
            val cid = retain(source, packet(second))
            assertFails { MeshRecovery.export(cell, source, reader) }
            val invalid = MeshSnapshot.create(cell, s_[cid], 3)
            assertFails { MeshRecovery.restore(invalid, cell, source, reader, destination, owner) }
            val changed = MeshCell(cell.namespace, peers, CanonicalCbor.encodeMap(mapOf("fixture" to "different-decision")))
            assertFails { MeshSnapshot.decode(invalid.bytes(), changed) }
            val untouched = MeshPersistenceCodec.decode(destination.execute(owner, put("fresh", "{\"value\":0}")))
            assertEquals(1L, untouched["sequence"])
        } finally {
            destination.drain(); source.drain(); primary.drain(); files.deleteRecursively(root)
        }
    }

    @Test
    fun interruptedCopyAndEarlyCompletionCannotExposePartialRecoveredState() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-snapshot-interrupt-").toString()
        val primary = MeshPersistence.open(this, FileCasStore(files, "$root/primary/cas"), files, "$root/primary/db", grants)
        val delegate = FileCasStore(files, "$root/source/cas")
        var armed: ContentId? = null
        var reads = 0
        val cas = object : CasStore() {
            override val durability get() = delegate.durability
            override fun put(bytes: ByteArray) = delegate.put(bytes)
            override fun sync() = delegate.sync()
            override fun get(cid: ContentId): ByteArray? {
                if (cid == armed && ++reads == 2) error("Injected loss after preflight")
                return delegate.get(cid)
            }
        }
        val source = MeshPersistence.open(this, cas, files, "$root/source/db", grants)
        suspend fun openDestination() = MeshPersistence.open(this, FileCasStore(files, "$root/destination/cas"), files, "$root/destination/db", grants)
        var destination = openDestination()
        try {
            primary.execute(owner, task("cell.bind", mapOf("epoch" to cell.epoch.value)))
            retain(source, cell.bytes())
            val receipt = primary.execute(owner, put("first", "{\"value\":1}"))
            val cid = retain(source, packet(receipt))
            val snapshot = MeshRecovery.export(cell, source, reader)
            armed = cid
            assertFails { MeshRecovery.restore(snapshot, cell, source, reader, destination, owner) }
            assertEquals(MeshPersistenceError.CONFLICT, assertFailsWith<MeshPersistenceException> {
                destination.execute(owner, task("recovery.end", mapOf("epoch" to cell.epoch.value, "manifest" to snapshot.id.value)))
            }.reason)
            destination.drain()
            destination = openDestination()
            assertEquals(MeshPersistenceError.IO, assertFailsWith<MeshPersistenceException> {
                destination.execute(owner, put("must-not-admit", "{\"value\":2}"))
            }.reason)
        } finally {
            destination.drain(); source.drain(); primary.drain(); files.deleteRecursively(root)
        }
    }
}
