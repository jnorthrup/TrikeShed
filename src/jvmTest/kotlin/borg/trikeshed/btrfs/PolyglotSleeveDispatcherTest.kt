package borg.trikeshed.btrfs

import borg.trikeshed.jules.JulesDurableTodoQueue
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolyglotSleeveDispatcherTest {

    @Test
    fun durableQueuePersistsAndDispatchesPerTargetWithoutStubs() {
        val fileOps = JvmFileOperations()
        val tmpRoot = fileOps.createTempDir("jules-polyglot-test-")
        val queueDir = fileOps.resolvePath(tmpRoot, "queue")
        val dispatchDir = fileOps.resolvePath(tmpRoot, "dispatch")

        // 1. scan + enqueue — file-backed durable, then dispatch via wiring (which already dispatches)
        val result = PolyglotSleeveQueueWiring.run(fileOps, queueDir = queueDir, dispatchDir = dispatchDir)

        assertTrue(result.scanned >= 7, "scanned ${result.scanned} < 7")
        assertTrue(result.expanded >= result.scanned, "expanded ${result.expanded} < scanned ${result.scanned}")
        assertTrue(result.enqueued > 0, "enqueued 0")
        assertEquals(result.expanded, result.enqueued, "enqueued != expanded (queue was not empty)")
        assertEquals(result.persisted, result.expanded, "persisted ${result.persisted} != expanded ${result.expanded} — persistence failed")

        // 2. persistence verified by reopening — wiring already dispatched, so pending 0, total == expanded
        val reopened = JulesDurableTodoQueue(queueDir, fileOps)
        assertEquals(result.expanded, reopened.size(), "reopened size mismatch — not durable")
        assertEquals(0, reopened.pendingSize(), "reopened pending should be 0 after dispatch")
        assertEquals(result.expanded, reopened.listAll().count { it.status == "dispatched" }, "dispatched count mismatch")

        // 3. dispatch per target seam — real handlers, artifacts, no stubs (already verified inside wiring)
        assertTrue(result.verified, "dispatch verification failed")
        assertEquals(0, result.pendingAfter, "pendingAfter ${result.pendingAfter} != 0 — not fully dispatched")
        assertEquals(result.expanded, result.dispatched, "dispatched ${result.dispatched} != expanded ${result.expanded}")

        // artifacts exist per target
        for (target in listOf("jvmMain", "posixMain", "linuxMain", "js", "wasm", "common")) {
            if ((result.stats[target] ?: 0) > 0) {
                val dir = fileOps.resolvePath(dispatchDir, target)
                assertTrue(fileOps.exists(dir), "dispatch dir missing for $target")
                val files = fileOps.listDir(dir)
                assertTrue(files.isNotEmpty(), "no artifacts for $target")
                for (f in files) {
                    val txt = fileOps.readString(fileOps.resolvePath(dir, f))
                    assertTrue(txt.contains("dispatched=1"), "artifact $f missing dispatched=1")
                    assertTrue(txt.contains("handler="), "artifact $f missing handler detail — stub?")
                }
            }
        }

        // 4. idempotence: re-running enqueue should add 0 (already queued)
        val second = PolyglotSleeveQueueWiring.run(fileOps, queueDir = queueDir, dispatchDir = dispatchDir)
        assertEquals(0, second.enqueued, "second run should enqueue 0 (already exists), got ${second.enqueued}")
        assertEquals(0, second.pendingAfter)

        fileOps.deleteRecursively(tmpRoot)
    }

    @Test
    fun queueEnqueueIsDurableAcrossReopen() {
        val fileOps = JvmFileOperations()
        val tmpRoot = fileOps.createTempDir("jules-queue-durable-")
        val queueDir = fileOps.resolvePath(tmpRoot, "q")
        val q1 = JulesDurableTodoQueue(queueDir, fileOps)
        val item = TodoQueueItem(id = "test:1", target = "jvmMain", description = "test durable", source = "test.kt:1", status = "pending")
        assertTrue(q1.enqueue(item))
        assertEquals(1, q1.size())
        // reopen
        val q2 = JulesDurableTodoQueue(queueDir, fileOps)
        assertEquals(1, q2.size())
        assertEquals("test durable", q2.listAll().first().description)
        // mark dispatched persists
        assertTrue(q2.markDispatched("test:1"))
        val q3 = JulesDurableTodoQueue(queueDir, fileOps)
        assertEquals(0, q3.pendingSize())
        assertEquals("dispatched", q3.listAll().first().status)
        assertTrue((q3.listAll().first().dispatchedAt ?: 0L) > 0L, "dispatchedAt should be >0")
        fileOps.deleteRecursively(tmpRoot)
    }
}
