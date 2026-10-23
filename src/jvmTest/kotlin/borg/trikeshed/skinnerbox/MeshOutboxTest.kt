package borg.trikeshed.skinnerbox

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class MeshOutboxTest {
    @Test
    fun custodyPrefixSurvivesRestartAndCannotSkipAnEarlierMutation() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-outbox-").toString()
        val grants = mapOf("owner" to setOf("tenant"))
        val epoch = ContentId.of("pinned-cell".encodeToByteArray()).value
        fun task(verb: String, fields: Map<String, Any?> = emptyMap()) =
            MeshTask("tenant", verb, CanonicalCbor.encodeMap(fields))
        fun put(id: String) = task("row.put", mapOf("requestId" to id, "collection" to "rows", "key" to id,
            "expectedRevision" to null, "value" to "{}".encodeToByteArray()))
        suspend fun open() = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
        try {
            val first = open()
            val a: Map<String, Any?>
            val b: Map<String, Any?>
            try {
                first.execute("owner", task("cell.bind", mapOf("epoch" to epoch)))
                a = MeshPersistenceCodec.decode(first.execute("owner", put("a")))
                b = MeshPersistenceCodec.decode(first.execute("owner", put("b")))
                val waiting = MeshPersistenceCodec.decode(first.execute("owner", task("custody.pending")))
                assertEquals(true, waiting["more"])
                assertContentEquals(a["event"] as ByteArray, (waiting["events"] as List<*>).single() as ByteArray)
                assertEquals(MeshPersistenceError.CONFLICT, assertFailsWith<MeshPersistenceException> {
                    first.execute("owner", task("custody.ack", mapOf("epoch" to epoch, "revision" to b["revision"])))
                }.reason)
            } finally { first.drain() }
            val second = open()
            try {
                val waiting = MeshPersistenceCodec.decode(second.execute("owner", task("custody.pending")))
                assertContentEquals(a["event"] as ByteArray, (waiting["events"] as List<*>).single() as ByteArray)
                val ack = task("custody.ack", mapOf("epoch" to epoch, "revision" to a["revision"]))
                val receipt = second.execute("owner", ack)
                assertEquals(true, MeshPersistenceCodec.decode(receipt)["durable"])
                assertContentEquals(receipt, second.execute("owner", ack))
            } finally { second.drain() }
            val third = open()
            try {
                val waiting = MeshPersistenceCodec.decode(third.execute("owner", task("custody.pending")))
                assertEquals(false, waiting["more"])
                assertContentEquals(b["event"] as ByteArray, (waiting["events"] as List<*>).single() as ByteArray)
                third.execute("owner", task("custody.ack", mapOf("epoch" to epoch, "revision" to b["revision"])))
                assertEquals(emptyList<Any?>(), MeshPersistenceCodec.decode(third.execute("owner", task("custody.pending")))["events"])
                // Acknowledgment records cannot change original mutation receipts or their identity.
                assertContentEquals(a["event"] as ByteArray, MeshPersistenceCodec.decode(third.execute("owner", put("a")))["event"] as ByteArray)
            } finally { third.drain() }
        } finally { files.deleteRecursively(root) }
    }
}
