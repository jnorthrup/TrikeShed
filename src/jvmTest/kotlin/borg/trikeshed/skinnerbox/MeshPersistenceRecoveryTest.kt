package borg.trikeshed.skinnerbox

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MeshPersistenceRecoveryTest {
    private val grants = mapOf("peer" to setOf("tenant"))
    private fun put() = MeshTask("tenant", "row.put", CanonicalCbor.encodeMap(mapOf(
        "requestId" to "request", "collection" to "tasks", "key" to "key", "expectedRevision" to null,
        "value" to "{\"status\":\"ready\",\"counter\":9007199254740993}".encodeToByteArray(),
    )))

    @Test
    fun cellEpochIsDurablyBoundAndCannotBeSilentlyReplaced() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-cell-epoch-").toString()
        val epoch = ContentId.of("three-original-peers".encodeToByteArray()).value
        val other = ContentId.of("replacement-peer".encodeToByteArray()).value
        fun bind(value: String) = MeshTask("tenant", "cell.bind", CanonicalCbor.encodeMap(mapOf("epoch" to value)))
        try {
            val first = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
            val original: ByteArray
            try {
                original = first.execute("peer", bind(epoch))
                assertContentEquals(original, first.execute("peer", bind(epoch)))
                assertEquals(true, MeshPersistenceCodec.decode(original)["durable"])
                assertEquals(MeshPersistenceError.CONFLICT,
                    assertFailsWith<MeshPersistenceException> { first.execute("peer", bind(other)) }.reason)
            } finally { first.drain() }
            val reopened = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
            try {
                assertContentEquals(original, reopened.execute("peer", bind(epoch)))
                assertEquals(MeshPersistenceError.CONFLICT,
                    assertFailsWith<MeshPersistenceException> { reopened.execute("peer", bind(other)) }.reason)
                assertEquals(2L, MeshPersistenceCodec.decode(reopened.execute("peer", put()))["sequence"])
            } finally { reopened.drain() }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun durableMutationAndReceiptRecoverTogetherAndWriterLeaseIsExclusive() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-persistence-").toString()
        try {
            val cas = FileCasStore(files, "$root/cas")
            val first = MeshPersistence.open(this, cas, files, "$root/db", grants)
            val reply: ByteArray
            try {
                reply = first.execute("peer", put())
                assertEquals(true, MeshPersistenceCodec.decode(reply)["durable"])
                assertFails { MeshPersistence.open(this, cas, files, "$root/db", grants) }
            } finally { first.drain() }
            val reopened = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
            val retained = MeshPersistenceCodec.decode(reply)["event"] as ByteArray
            val retainedId = ContentId.of(retained).value
            try {
                assertContentEquals(reply, reopened.execute("peer", put()))
                val response = reopened.execute("peer", MeshTask("tenant", "row.get", CanonicalCbor.encodeMap(
                    mapOf("collection" to "tasks", "key" to "key"))))
                val value = MeshPersistenceCodec.decode(response)["value"] as ByteArray
                assertTrue(value.decodeToString().contains("9007199254740993"))
                reopened.execute("peer", MeshTask("tenant", "row.delete", CanonicalCbor.encodeMap(mapOf(
                    "requestId" to "delete", "collection" to "tasks", "key" to "key",
                    "expectedRevision" to MeshPersistenceCodec.decode(reply)["revision"],
                ))))
                reopened.execute("peer", MeshTask("tenant", "replica.retain", CanonicalCbor.encodeMap(mapOf(
                    "contentId" to retainedId, "value" to retained,
                ))))
            } finally { reopened.drain() }
            val third = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
            try {
                // A delayed retry of the old creation acknowledges its original outcome; it cannot resurrect the row.
                assertContentEquals(reply, third.execute("peer", put()))
                val absent = assertFailsWith<MeshPersistenceException> {
                    third.execute("peer", MeshTask("tenant", "row.get", CanonicalCbor.encodeMap(
                        mapOf("collection" to "tasks", "key" to "key"))))
                }
                assertEquals(MeshPersistenceError.NOT_FOUND, absent.reason)
                val read = third.execute("peer", MeshTask("tenant", "replica.read", CanonicalCbor.encodeMap(
                    mapOf("contentId" to retainedId))))
                assertContentEquals(retained, MeshPersistenceCodec.decode(read)["value"] as ByteArray)
            } finally { third.drain() }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun ambiguousHeadPublicationPoisonsThenReopenReturnsOriginalReceipt() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-ambiguous-").toString()
        val uncertain = object : FileOperations by files {
            var armed = false
            override fun writeAtomically(path: String, bytes: ByteArray) {
                files.writeAtomically(path, bytes)
                if (armed && path == "$root/db/HEAD") {
                    armed = false
                    error("Injected failure after durable HEAD publication")
                }
            }
        }
        try {
            val first = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), uncertain, "$root/db", grants)
            try {
                uncertain.armed = true
                assertEquals(MeshPersistenceError.IO,
                    assertFailsWith<MeshPersistenceException> { first.execute("peer", put()) }.reason)
                assertEquals(MeshPersistenceError.IO,
                    assertFailsWith<MeshPersistenceException> { first.execute("peer", put()) }.reason)
            } finally { first.drain() }
            val recovered = MeshPersistence.open(this, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
            try {
                val receipt = recovered.execute("peer", put())
                assertEquals(1L, MeshPersistenceCodec.decode(receipt)["sequence"])
                assertContentEquals(receipt, recovered.execute("peer", put()))
            } finally { recovered.drain() }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun corruptRetainedObjectRefusesReopenAndDoesNotLeakLease() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-corrupt-").toString()
        try {
            val cas = FileCasStore(files, "$root/cas")
            val first = MeshPersistence.open(this, cas, files, "$root/db", grants)
            val receipt = try { MeshPersistenceCodec.decode(first.execute("peer", put())) }
                finally { first.drain() }
            val event = receipt["event"] as ByteArray
            val content = ContentId(receipt["revision"] as String)
            val path = files.resolvePath("$root/cas", CasPaths.blob(content))
            files.writeAtomically(path, event + byteArrayOf(0))
            assertFails { MeshPersistence.open(this, cas, files, "$root/db", grants) }
            cas.put(event) // Repair from independently retained, content-addressed bytes.
            val recovered = MeshPersistence.open(this, cas, files, "$root/db", grants)
            try { assertContentEquals(event, MeshPersistenceCodec.decode(recovered.execute("peer", put()))["event"] as ByteArray) }
            finally { recovered.drain() }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun missingOlderUnrelatedCommitCannotSilentlyDiscardDedupeHistory() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-history-").toString()
        try {
            val cas = FileCasStore(files, "$root/cas")
            val first = MeshPersistence.open(this, cas, files, "$root/db", grants)
            val oldCommit: ContentId
            try {
                first.execute("peer", put())
                oldCommit = ContentId(CanonicalCbor.decodeMap(files.readAllBytes("$root/db/HEAD"))["head"] as String)
                first.execute("peer", MeshTask("tenant", "row.put", CanonicalCbor.encodeMap(mapOf(
                    "requestId" to "unrelated", "collection" to "tasks", "key" to "second", "expectedRevision" to null,
                    "value" to "{\"other\":true}".encodeToByteArray(),
                ))))
            } finally { first.drain() }
            val retained = requireNotNull(cas.get(oldCommit))
            files.deleteRecursively(files.resolvePath("$root/cas", CasPaths.blob(oldCommit)))
            assertFailsWith<IllegalArgumentException> { MeshPersistence.open(this, cas, files, "$root/db", grants) }
            cas.put(retained)
            val recovered = MeshPersistence.open(this, cas, files, "$root/db", grants)
            try { assertEquals(1L, MeshPersistenceCodec.decode(recovered.execute("peer", put()))["sequence"]) }
            finally { recovered.drain() }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun cancellationDuringOpenCannotOrphanWriterLeaseInLongLivedScope() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-open-cancel-").toString()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val paused = object : FileOperations by files {
            override fun writeAtomically(path: String, bytes: ByteArray) {
                files.writeAtomically(path, bytes)
                if (path == "$root/db/HEAD") {
                    reached.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            val opening = async(Dispatchers.Default) {
                MeshPersistence.open(scope, FileCasStore(files, "$root/cas"), paused, "$root/db", grants)
            }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            opening.cancel()
            release.countDown()
            withTimeout(5_000) { opening.join() }
            assertTrue(owner.isActive)
            val next = MeshPersistence.open(scope, FileCasStore(files, "$root/cas"), files, "$root/db", grants)
            next.drain()
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            files.deleteRecursively(root)
        }
    }

    @Test
    fun postPublicationProjectionReadFailureRequiresReopenBeforeServing() = runBlocking<Unit> {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("mesh-projection-failure-").toString()
        var armed = false
        var failRead = false
        val value = MeshPersistenceCodec.decode(put().payload)["value"] as ByteArray
        val valueId = ContentId.of(value)
        val observing = object : FileOperations by files {
            override fun writeAtomically(path: String, bytes: ByteArray) {
                files.writeAtomically(path, bytes)
                if (armed && path == "$root/db/HEAD") { armed = false; failRead = true }
            }
        }
        val delegate = FileCasStore(files, "$root/cas")
        val cas = object : CasStore() {
            override val durability get() = delegate.durability
            override fun put(bytes: ByteArray) = delegate.put(bytes)
            override fun sync() = delegate.sync()
            override fun get(cid: ContentId): ByteArray? {
                if (failRead && cid == valueId) { failRead = false; error("Injected post-commit read failure") }
                return delegate.get(cid)
            }
        }
        try {
            val first = MeshPersistence.open(this, cas, observing, "$root/db", grants)
            try {
                armed = true
                assertEquals(MeshPersistenceError.IO,
                    assertFailsWith<MeshPersistenceException> { first.execute("peer", put()) }.reason)
                assertEquals(MeshPersistenceError.IO,
                    assertFailsWith<MeshPersistenceException> { first.execute("peer", put()) }.reason)
            } finally { first.drain() }
            val next = MeshPersistence.open(this, delegate, files, "$root/db", grants)
            try { assertEquals(1L, MeshPersistenceCodec.decode(next.execute("peer", put()))["sequence"]) }
            finally { next.drain() }
        } finally { files.deleteRecursively(root) }
    }
}
