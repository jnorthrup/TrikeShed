package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.SequenceLayout
import java.lang.foreign.StructLayout
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/**
 * The Panama ring: io_uring with MemorySegment AS the SQE/CQE mapping.
 *
 * `io_uring_setup(425)` returns a file descriptor whose registered offsets
 * describe an mmap'd SQ/CQ ring. `MemorySegment` is the ideal mapping because
 * that ring IS fixed-layout kernel structs at known offsets: preparing an SQE
 * is writing fields into a 64-byte segment the kernel reads in place; reaping
 * a CQE is reading a 16-byte segment the kernel wrote in place. No marshalling
 * layer exists to be slower or wrong — the segment and the ring are the same
 * memory. This is the const-gate level-2 JVM actual behind
 * [UserspaceChannelBackend], byte-identical above the interface.
 *
 * syscall(425/426) via FFM downcalls; mmap via [JvmMemorySyscalls].
 * Linux-only by kernel contract; the const gate keeps other targets on
 * their own actuals.
 */
internal object PanamauRingSyscalls {
    private val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val linker: Linker = Linker.nativeLinker()
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private val int = ValueLayout.JAVA_INT
    private val long = ValueLayout.JAVA_LONG
    private val pointer = ValueLayout.ADDRESS

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(
            linker.defaultLookup().find(name).orElseThrow {
                UnsupportedOperationException("libc $name is unavailable")
            },
            descriptor, Linker.Option.captureCallState("errno"),
        )

    /** raw syscall(2): number, then varargs (longs/pointers). */
    private val syscallCall by lazy {
        linker.downcallHandle(
            linker.defaultLookup().find("syscall").orElseThrow(),
            FunctionDescriptor.of(long, long, long, long, long, long, long, long),
            Linker.Option.captureCallState("errno"),
            Linker.Option.firstVariadicArg(1),
        )
    }
    private val mmapCall by lazy { function("mmap", FunctionDescriptor.of(pointer,
        pointer, long, int, int, int, long)) }
    private val munmapCall by lazy { function("munmap", FunctionDescriptor.of(int, pointer, long)) }

    const val NR_IO_URING_SETUP = 425L
    const val NR_IO_URING_ENTER = 426L
    const val NR_IO_URING_REGISTER = 427L

    const val IORING_OFF_SQ_RING = 0L
    const val IORING_OFF_CQ_RING = 0x8000000L
    const val IORING_OFF_SQES = 0x10000000L

    const val PROT_READ = 0x1
    const val PROT_WRITE = 0x2
    const val MAP_SHARED = 0x01
    const val MAP_ANONYMOUS = 0x20

    /** io_uring_params, laid out as the kernel defines it (64 + 2×40 bytes). */
    val paramsLayout: StructLayout = MemoryLayout.structLayout(
        int, int, int, int, int, int, int,
        MemoryLayout.sequenceLayout(3, int),
        MemoryLayout.sequenceLayout(8, long),
        MemoryLayout.sequenceLayout(8, long),
    )
    val sqOff: Long by lazy { paramsLayout.byteOffset(groupElement("4")) }
    val cqOff: Long by lazy { paramsLayout.byteOffset(groupElement("5")) }

    private fun errno(state: MemorySegment): Int = state.get(int, errnoOffset)

    private fun syscall3(nr: Long, a: Long, b: Long, c: Long): Long =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(stateLayout)
            val result = syscallCall.invokeWithArguments(
                listOf(state, nr, a, b, c, 0L, 0L, 0L),
            ) as Long
            if (result < 0) -errno(state).toLong() else result
        }

    fun setup(entries: Int, params: MemorySegment): Long =
        syscall3(NR_IO_URING_SETUP, entries.toLong(), params.address(), 0L)

    fun enter(fd: Int, toSubmit: Int, minComplete: Int, flags: Int, arg: Long, argSize: Long): Long =
        syscall3(NR_IO_URING_ENTER, fd.toLong(), toSubmit.toLong(), minComplete.toLong())

    fun mapRing(fd: Int, offset: Long, length: Long): MemorySegment =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(stateLayout)
            val result = mmapCall.invokeWithArguments(
                state, MemorySegment.NULL, length,
                PROT_READ or PROT_WRITE, MAP_SHARED, fd, offset,
            ) as MemorySegment
            if (result.address() == -1L) throw IllegalStateException("ring mmap failed: ${-errno(state)}")
            result.reinterpret(length)
        }

    fun unmap(segment: MemorySegment) {
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(stateLayout)
            munmapCall.invokeWithArguments(state, MemorySegment.ofAddress(segment.address()), segment.byteSize())
        }
    }

    /** io_sqring_offsets field offsets (u32s): head, tail, ring_mask, ring_entries, flags, dropped, array, resv. */
    const val SQ_OFF_HEAD = 0
    const val SQ_OFF_TAIL = 4
    const val SQ_OFF_RING_MASK = 8
    const val SQ_OFF_RING_ENTRIES = 12
    const val SQ_OFF_FLAGS = 16
    const val SQ_OFF_DROPPED = 20
    const val SQ_OFF_ARRAY = 24

    /** io_cqring_offsets: head, tail, ring_mask, ring_entries, overflow, cqes, flags, resv. */
    const val CQ_OFF_HEAD = 0
    const val CQ_OFF_TAIL = 4
    const val CQ_OFF_RING_MASK = 8
    const val CQ_OFF_RING_ENTRIES = 12
    const val CQ_OFF_OVERFLOW = 16
    const val CQ_OFF_CQES = 24
}

/**
 * One live ring: SQEs written as MemorySegment fields, CQEs read the same way.
 * The two counters the contract requires are [submittedTail] (admission) and
 * [reapedTail] (completion) — drain is the equality of kernel heads and these.
 */
internal class PanamaRing(val entries: Int) : AutoCloseable {
    private var ringFd: Int = -1
    private var sq: MemorySegment? = null      // SQ ring (headers + array)
    private var sqes: MemorySegment? = null    // SQE table
    private var cq: MemorySegment? = null      // CQ ring (headers + CQE table)
    private var sqMask = 0
    private var cqMask = 0
    private var sqArrayOff = 0L
    private var sqeOff = 0L
    private var cqCqesOff = 0L

    /** Admission tail — what WE have written. Kernel head = what it has taken. */
    @Volatile var submittedTail = 0L; private set
    /** Completion tail — what WE have reaped. Kernel tail = what has completed. */
    @Volatile var reapedTail = 0L

    val isOpen: Boolean get() = ringFd >= 0

    fun open(): Result<Unit> = runCatching {
        check(ringFd < 0) { "ring already open" }
        val arena = Arena.ofConfined()
        val params = arena.allocate(PanamauRingSyscalls.paramsLayout)
        val fd = PanamauRingSyscalls.setup(entries, params).toInt()
        check(fd >= 0) { "io_uring_setup failed: $fd" }
        ringFd = fd
        // features flags at params offset 20 (int #5); SQ/CQ offsets follow.
        val sqOffBase = PanamauRingSyscalls.sqOff
        val cqOffBase = PanamauRingSyscalls.cqOff
        val sqRingSize = params.get(ValueLayout.JAVA_LONG, sqOffBase + PanamauRingSyscalls.SQ_OFF_ARRAY * 0 + 40).let {
            // layout-derived: full sizes come from offsets; use conservative mapping sizes
            0L // placeholder, replaced below
        }
        // Sizes: kernel guarantees SQ ring + array fits; map a page-bounded region per liburing:
        val sqSz = pageRound(64L * entries + 32L * entries)
        val cqSz = pageRound(16L * entries + 32L)
        val sse = PanamauRingSyscalls.mapRing(fd, PanamauRingSyscalls.IORING_OFF_SQ_RING, sqSz)
        val cq = PanamauRingSyscalls.mapRing(fd, PanamauRingSyscalls.IORING_OFF_CQ_RING, cqSz)
        val sqeSz = pageRound(64L * entries)
        val sqes = PanamauRingSyscalls.mapRing(fd, PanamauRingSyscalls.IORING_OFF_SQES, sqeSz)
        this.sq = sse; this.cq = cq; this.sqes = sqes
        sqMask = sse.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.SQ_OFF_RING_MASK.toLong())
        cqMask = cq.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.CQ_OFF_RING_MASK.toLong())
        sqArrayOff = sse.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.SQ_OFF_ARRAY.toLong()).toLong()
        cqCqesOff = cq.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.CQ_OFF_CQES.toLong()).toLong()
    }

    private fun pageRound(n: Long): Long = (n + 4095) / 4096 * 4096

    /** SQ kernel head: entries below it are consumed by the kernel. */
    fun sqHead(): Int = sq!!.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.SQ_OFF_HEAD.toLong())

    /** CQ kernel tail: entries below it are completed and reaped by us. */
    fun cqTail(): Long = cq!!.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.CQ_OFF_TAIL.toLong()).toLong() and 0xFFFFFFFFL

    /** CQ kernel head: what we have acknowledged. */
    fun cqHead(): Long = cq!!.get(ValueLayout.JAVA_INT, PanamauRingSyscalls.CQ_OFF_HEAD.toLong()).toLong() and 0xFFFFFFFFL

    /**
     * Write ONE SQE into the ring at the tail index. Field writes are segment
     * writes — the mapping IS the submission. Returns the ring slot index.
     */
    fun writeSqe(submission: UringSubmission): Int {
        val ring = sq ?: error("ring not open")
        val table = sqes ?: error("sqe table not mapped")
        val tail = submittedTail
        val idx = (tail and sqMask.toLong()).toInt()
        val sqe = table.asSlice(64L * idx, 64L)
        sqe.set(ValueLayout.JAVA_BYTE, 0, submission.opcode.code.toByte())       // opcode
        sqe.set(ValueLayout.JAVA_BYTE, 1, 0)                                     // flags
        sqe.set(ValueLayout.JAVA_SHORT, 2, 0)                                    // ioprio
        sqe.set(ValueLayout.JAVA_INT, 4, submission.fd)                          // fd
        sqe.set(ValueLayout.JAVA_LONG, 8, submission.offset)                     // off / addr2
        sqe.set(ValueLayout.JAVA_LONG, 16, submission.addr)                      // addr
        sqe.set(ValueLayout.JAVA_INT, 24, submission.len)                        // len
        sqe.set(ValueLayout.JAVA_INT, 28, submission.operationFlags)             // rw_flags union
        sqe.set(ValueLayout.JAVA_LONG, 40, submission.userData)                  // user_data
        // SQ array: kernel reads index from array[head..tail]
        ring.set(ValueLayout.JAVA_INT, sqArrayOff + 4L * idx, idx)
        submittedTail = tail + 1
        return idx
    }

    /**
     * io_uring_enter(426): submit up to the kernel. Returns submitted count.
     * [minComplete] > 0 parks in the kernel until that many CQEs settle —
     * the blocking wait the timer-poll scaffolding stands in for.
     */
    fun enter(toSubmit: Int, minComplete: Int): Int {
        val result = PanamauRingSyscalls.enter(ringFd, toSubmit, minComplete, if (minComplete > 0) 1 else 0, 0L, 0L)
        return result.toInt()
    }

    /**
     * Reap every completed CQE. Each CQE is a 16-byte segment: user_data +
     * res. The segment read IS the completion — no copy, no translation.
     */
    fun reap(): List<UringCompletion> {
        val ring = cq ?: return emptyList()
        val out = ArrayList<UringCompletion>()
        while (reapedTail != cqTail()) {
            val idx = (reapedTail and cqMask.toLong()).toInt()
            val cqe = ring.asSlice(cqCqesOff + 16L * idx, 16L)
            val userData = cqe.get(ValueLayout.JAVA_LONG, 0)
            val res = cqe.get(ValueLayout.JAVA_INT, 8)
            out.add(UringCompletion(userData, res, 0))
            reapedTail++
        }
        // publish cq head so the kernel may reuse slots
        ring.set(ValueLayout.JAVA_INT, PanamauRingSyscalls.CQ_OFF_HEAD.toLong(), reapedTail.toInt())
        return out
    }

    override fun close() {
        if (ringFd < 0) return
        sq?.let { runCatching { PanamauRingSyscalls.unmap(it) } }
        sqes?.let { runCatching { PanamauRingSyscalls.unmap(it) } }
        cq?.let { runCatching { PanamauRingSyscalls.unmap(it) } }
        sq = null; sqes = null; cq = null
        JvmSocketSyscalls.close(ringFd)
        ringFd = -1
    }
}

/**
 * [UserspaceChannelBackend] actual over [PanamaRing]: MemorySegment IS the
 * SQE/CQE. Batch admission writes segments; completion reaps segments; the
 * two counters are the ring tails themselves. Const-gate level 2.
 */
internal class PanamauChannelBackend(private val ring: PanamaRing) : UserspaceChannelBackend {
    override val capabilities: Long = UringOp.caps(
        UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.CLOSE,
        UringOp.FSYNC, UringOp.SOCKET, UringOp.CONNECT, UringOp.ACCEPT,
        UringOp.POLL_ADD, UringOp.POLL_REMOVE,
    )
    override val availability: String = "io_uring: Panama FFM ring (MemorySegment SQE/CQE mapping)"

    private val pending = HashMap<Long, Int>() // userData -> staged position

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        for (sub in submissions) ring.writeSqe(sub)
        val n = ring.enter(submissions.size, 0)
        check(n >= 0) { "io_uring_enter failed: $n" }
        val settled = ring.reap()
        return settled.map { SelectionResult(it.res, it.userData) }
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        for (i in 0 until submissions.size) ring.writeSqe(submissions[i])
        val n = ring.enter(submissions.size, 0)
        check(n >= 0) { "io_uring_enter failed: $n" }
        val settled = ring.reap()
        return settled.size j { settled[it] }
    }

    /** Park in the kernel until [minComplete] CQEs settle — the F2 correction surface. */
    fun enterBlocking(minComplete: Int): Int = ring.enter(0, minComplete)

    fun reap(): List<UringCompletion> = ring.reap()

    override fun close() = ring.close()
}
