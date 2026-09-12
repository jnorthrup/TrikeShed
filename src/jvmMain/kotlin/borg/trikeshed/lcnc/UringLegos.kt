package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.openUserspaceChannelBackend
import kotlinx.coroutines.sync.withLock

/**
 * uring legos: the ring on the palette. Each runner drives the userspace
 * facade's ONE sanctioned suspend path (`batchEnqueue` — SQEs in, one CQE per
 * SQE out) against the canonical probed backend; the backend underneath
 * (native ring or emulation) is never named here, per the one-IO-flavor law.
 *
 * File ops go OPENAT → READ/WRITE → CLOSE as staged SQEs on a fresh facade —
 * no descriptor outlives a node invocation, no handle is shared between
 * nodes, and every failure surfaces as the CQE's negative errno in `res`.
 */
object UringLegos {

    /** entries for the shared lego ring; small — a lego stages a handful of SQEs. */
    private const val RING_ENTRIES = 16

    /**
     * ONE ring for every lego invocation instead of constructed per call.
     * Admission/completion counters live across the daemon lifetime where they
     * belong; the ring dies with the process scope that owns it. (Debt note in
     * the reactor skill: the owner should be the daemon's module scope as a
     * CCEK element, drained at daemon drain — the volatile holder is the
     * interim.)
     */
    @Volatile private var shared: borg.trikeshed.userspace.FunctionalUringFacade? = null
    private val sharedMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun ring(): borg.trikeshed.userspace.FunctionalUringFacade {
        shared?.let { return it }
        return sharedMutex.withLock {
            shared ?: run {
                val backend = openUserspaceChannelBackend(RING_ENTRIES)
                val facade = borg.trikeshed.userspace.FunctionalUringFacade(RING_ENTRIES, backend)
                shared = facade
                facade
            }
        }
    }

    private suspend fun <T> onRing(block: suspend (borg.trikeshed.userspace.FunctionalUringFacade) -> T): T =
        block(ring())

    /** Capability report: what THIS machine's ring answers, straight from the probe. */
    fun probe() = LcncNodeRunner { _, _ ->
        val backend = openUserspaceChannelBackend(RING_ENTRIES)
        val report = backend.probeReport
        val caps = UringOp.entries.filter { backend.capabilities and it.mask != 0L }.map { it.name }
        val nativeMask = backend.nativeCapabilities
        val native = UringOp.entries.filter { nativeMask and it.mask != 0L }.map { it.name }
        linkedMapOf<String, Any?>(
            "report" to linkedMapOf(
                "availability" to backend.availability,
                "state" to (report?.state?.name ?: "UNKNOWN"),
                "available" to (report?.available ?: false),
                "module" to (report?.module ?: ""),
                "detail" to (report?.description ?: ""),
                "capabilities" to caps,
                "nativeCapabilities" to native,
            ),
        )
    }

    /** READ SQEs: OPENAT → READ → CLOSE. `bytes` is the decoded payload, `res` the CQE. */
    fun read() = LcncNodeRunner { node, _ ->
        val path = requireNotNull(node.params["path"]?.takeIf { it.isNotBlank() }) { "uring.read: path is required" }
        val offset = node.params["offset"]?.toLongOrNull() ?: 0L
        val len = (node.params["len"]?.toIntOrNull() ?: 65536).coerceIn(1, 8 * 1024 * 1024)
        val buffer = ByteArray(len)
        onRing { facade ->
            var token = 1L
            val open = Submissions.openat(path, userData = token++)
            val fd = settle(facade, listOf(open)).first { it.userData == open.userData }.res
            check(fd >= 0) { "uring.read: OPENAT failed res=$fd (${errno(fd)})" }
            try {
                val read = UringSubmission(
                    UringOp.READ, fd, 0, len, offset, userData = token++,
                    buffer = ByteBuffer(buffer),
                )
                val cqe = settle(facade, listOf(read)).first { it.userData == read.userData }
                check(cqe.res >= 0) { "uring.read: READ failed res=${cqe.res} (${errno(cqe.res)})" }
                mapOf<String, Any?>(
                    "bytes" to buffer.decodeToString(0, cqe.res),
                    "res" to linkedMapOf("opcode" to "READ", "res" to cqe.res, "offset" to offset),
                )
            } finally {
                settle(facade, listOf(Submissions.close(fd, userData = token)))
            }
        }
    }

    /** WRITE SQEs: OPENAT(creat 0600) → WRITE → CLOSE. `res` carries the written count. */
    fun write() = LcncNodeRunner { node, inputs ->
        val path = requireNotNull(node.params["path"]?.takeIf { it.isNotBlank() } ?: inputs["path"]?.toString()) {
            "uring.write: path is required"
        }
        val payload = (inputs["bytes"]?.toString() ?: "").encodeToByteArray()
        val offset = node.params["offset"]?.toLongOrNull() ?: 0L
        onRing { facade ->
            var token = 1L
            // O_WRONLY|O_CREAT|O_TRUNC = 0x241; mode 0600.
            val open = Submissions.openat(path, flags = 0x241, userData = token++)
            val fd = settle(facade, listOf(open)).first { it.userData == open.userData }.res
            check(fd >= 0) { "uring.write: OPENAT failed res=$fd (${errno(fd)})" }
            try {
                val write = UringSubmission(
                    UringOp.WRITE, fd, 0, payload.size, offset, userData = token++,
                    buffer = ByteBuffer(payload),
                )
                val cqe = settle(facade, listOf(write)).first { it.userData == write.userData }
                check(cqe.res >= 0) { "uring.write: WRITE failed res=${cqe.res} (${errno(cqe.res)})" }
                mapOf<String, Any?>(
                    "res" to linkedMapOf("opcode" to "WRITE", "res" to cqe.res, "offset" to offset),
                )
            } finally {
                settle(facade, listOf(Submissions.close(fd, userData = token)))
            }
        }
    }

    /** FSYNC SQE: OPENAT → FSYNC → CLOSE. One CQE on durability. */
    fun fsync() = LcncNodeRunner { node, _ ->
        val path = requireNotNull(node.params["path"]?.takeIf { it.isNotBlank() }) { "uring.fsync: path is required" }
        onRing { facade ->
            var token = 1L
            val open = Submissions.openat(path, userData = token++)
            val fd = settle(facade, listOf(open)).first { it.userData == open.userData }.res
            check(fd >= 0) { "uring.fsync: OPENAT failed res=$fd (${errno(fd)})" }
            try {
                val fsync = Submissions.fsync(fd, userData = token++)
                val cqe = settle(facade, listOf(fsync)).first { it.userData == fsync.userData }
                mapOf<String, Any?>(
                    "res" to linkedMapOf("opcode" to "FSYNC", "res" to cqe.res),
                )
            } finally {
                settle(facade, listOf(Submissions.close(fd, userData = token)))
            }
        }
    }

    /** Admit a batch and await its CQEs — the facade's one sanctioned suspend path. */
    private suspend fun settle(
        facade: borg.trikeshed.userspace.FunctionalUringFacade,
        submissions: List<UringSubmission>,
    ): List<borg.trikeshed.userspace.UringCompletion> {
        val settled = facade.batchEnqueue(submissions.size j { submissions[it] })
        return List(settled.size) { settled[it] }
    }

    private fun errno(res: Int): String = when (-res) {
        2 -> "ENOENT"; 9 -> "EBADF"; 13 -> "EACCES"; 17 -> "EEXIST"
        22 -> "EINVAL"; 95 -> "EOPNOTSUPP"; 11 -> "EAGAIN"
        else -> if (res < 0) "errno ${-res}" else "ok"
    }

    /**
     * Register every lego into the daemon's [ModuleContext.lcncRunners]. Called from
     * the daemon beside [SubVmLegos.register]; no other site registers uring.* runners.
     */
    fun register(ctx: borg.trikeshed.module.ModuleContext) {
        ctx.lcncRunners[LcncContracts.URING_PROBE] = probe()
        ctx.lcncRunners[LcncContracts.URING_READ] = read()
        ctx.lcncRunners[LcncContracts.URING_WRITE] = write()
        ctx.lcncRunners[LcncContracts.URING_FSYNC] = fsync()
    }
}
