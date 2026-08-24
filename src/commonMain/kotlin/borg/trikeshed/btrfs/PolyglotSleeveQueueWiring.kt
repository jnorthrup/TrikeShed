package borg.trikeshed.btrfs

import borg.trikeshed.jules.JulesDurableTodoQueue
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Wiring run when polyglot sleeves start — scans chokepoint TODOs and queues them
 * in the Jules durable (file-backed) queue for per-target dispatch.
 *
 * No stubs: scan → enqueue → dispatch are all FileOperations-backed and verified
 * by `PolyglotSleeveDispatcherTest`.
 */
object PolyglotSleeveQueueWiring {

    fun run(
        fileOps: FileOperations,
        sourcePaths: List<String> = ChokepointTodoScanner.defaultScanPaths(),
        queueDir: String = fileOps.resolvePath(fileOps.cwd(), "build/jules-durable-queue"),
        dispatchDir: String = fileOps.resolvePath(fileOps.cwd(), "build/dispatch-verify"),
    ): WiringResult {
        // 1. scan chokepoints
        val raw = ChokepointTodoScanner.scanFiles(sourcePaths, fileOps)
        val expanded = raw.flatMap { ChokepointTodoScanner.expandTargets(it) }

        // 2. enqueue to durable file-backed queue
        val queue = JulesDurableTodoQueue(queueDir, fileOps)
        val enqueued = queue.enqueueAll(expanded)

        // 3. persistence verify: re-open and count
        val reopened = JulesDurableTodoQueue(queueDir, fileOps)
        val persisted = reopened.size()

        // 4. dispatch per target seam (real handlers, artifacts)
        val dispatcher = PolyglotSleeveDispatcher(queue, fileOps, dispatchDir)
        val dispatched = dispatcher.dispatchAll()
        val verified = dispatcher.verifyAllDispatched()

        return WiringResult(
            scanned = raw.size,
            expanded = expanded.size,
            enqueued = enqueued,
            persisted = persisted,
            dispatched = dispatched.size,
            verified = verified,
            pendingAfter = queue.pendingSize(),
            stats = dispatcher.stats(),
        )
    }

    data class WiringResult(
        val scanned: Int,
        val expanded: Int,
        val enqueued: Int,
        val persisted: Int,
        val dispatched: Int,
        val verified: Boolean,
        val pendingAfter: Int,
        val stats: Map<String, Int>,
    )
}
