package borg.trikeshed.util.oroboros.element

import borg.trikeshed.cas.IpfsBridge
import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class WorktreeReconcileElementTest {
    @Test
    fun cancelledParentRejectsSyncBeforeWorkerStarts() = runBlocking {
        val root = Files.createTempDirectory("worktree-cancelled-")
        val parent = SupervisorJob(coroutineContext[Job]).also { it.cancel() }
        var scans = 0
        val (element, index) = element(root.toString(), parent) {
            scans++
            "head"
        }
        try {
            element.open()
            val request = async { runCatching { element.sync() } }
            val outcome = withTimeout(5_000) { request.await() }
            assertIs<CancellationException>(outcome.exceptionOrNull())
            withTimeout(5_000) { element.drain() }
            assertEquals(0, scans)
            assertEquals(ElementState.CLOSED, element.state)
            assertTrue(element.worktreeDirty.trySend(Unit).isFailure)
        } finally {
            element.drain()
            index.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun syncFailureDoesNotStopWorker() = runBlocking {
        val root = Files.createTempDirectory("worktree-sync-")
        var scans = 0
        val (element, index) = element(root.toString(), coroutineContext[Job]) {
            if (++scans == 1) error("head unavailable")
            "head"
        }
        try {
            element.open()
            withTimeout(5_000) {
                assertFailsWith<IllegalStateException> { element.sync() }
                assertEquals("head", element.sync().revision)
            }
            assertEquals(2, scans)
        } finally {
            element.drain()
            index.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun drainFinishesAcceptedRequestsAndDirty() = runBlocking {
        val root = Files.createTempDirectory("worktree-drain-")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var scans = 0
        val (element, index) = element(root.toString(), coroutineContext[Job]) {
            if (++scans == 1) {
                entered.complete(Unit)
                release.await()
            }
            "head"
        }
        try {
            element.open()
            withTimeout(5_000) {
                val first = async(start = CoroutineStart.UNDISPATCHED) { element.sync() }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { element.sync() }
                assertTrue(element.worktreeDirty.trySend(Unit).isSuccess)
                val closing = async(start = CoroutineStart.UNDISPATCHED) { element.close() }
                assertFalse(closing.isCompleted)
                assertFailsWith<IllegalStateException> { element.sync() }
                release.complete(Unit)
                assertEquals("head", first.await().revision)
                assertEquals("head", second.await().revision)
                closing.await()
                assertEquals(ElementState.CLOSED, element.state)
                assertEquals(3, scans)
                assertTrue(element.worktreeDirty.trySend(Unit).isFailure)
            }
        } finally {
            release.complete(Unit)
            element.drain()
            index.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun element(root: String, parent: Job?, head: suspend () -> String): Join<WorktreeReconcileElement, MemoryIndexLayer> {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.inMemory()
        val attachments = CouchAttachmentGateway(couch, cas)
        val memory = MemoryStore(cas, couch)
        val index = MemoryIndexLayer(memory)
        return WorktreeReconcileElement(
            root,
            WorktreeCouchGateway(JvmFileOperations(), attachments),
            CouchIndexBridge(attachments, index),
            MemoryBridge(memory, attachments, IpfsBridge(cas)),
            head,
            parent,
        ) j index
    }
}
