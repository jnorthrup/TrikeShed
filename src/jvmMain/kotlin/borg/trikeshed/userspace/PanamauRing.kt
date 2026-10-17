package borg.trikeshed.userspace

import borg.trikeshed.collections.LongTable
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle

/** An io_uring system call that failed: [errno] in Linux numbering. */
internal class PanamaRingException(val errno: Int, message: String) : RuntimeException(message)

/** io_uring_setup/enter/register, mmap and munmap through [LibcDowncall]'s build-time shapes. Linux only. */
internal object PanamauRingSyscalls {
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private val errnoState = ThreadLocal.withInitial { Arena.ofAuto().allocate(stateLayout) }
    private object Syscall { @JvmField val at = libcSymbol("syscall") }
    private object Mmap { @JvmField val at = libcSymbol("mmap") }
    private object Munmap { @JvmField val at = libcSymbol("munmap") }

    /** The io_uring numbers are the same in the x86_64 table and the asm-generic one arm64 uses. */
    const val SETUP = 425L
    const val ENTER = 426L
    const val REGISTER = 427L

    /** The result, or -errno. */
    fun syscall(nr: Long, a: Long, b: Long = 0, c: Long = 0, d: Long = 0, e: Long = 0, f: Long = 0): Long {
        val state = errnoState.get()
        val result = LibcDowncall.SYSCALL6.invokeExact(Syscall.at, state, nr, a, b, c, d, e, f) as Long
        return if (result < 0) -state.get(ValueLayout.JAVA_INT, errnoOffset).toLong() else result
    }

    fun map(length: Long, fd: Int, offset: Long): MemorySegment {
        val state = errnoState.get()
        // PROT_READ|PROT_WRITE, MAP_SHARED|MAP_POPULATE: the rings are touched on every call; fault them in once.
        val mapped = LibcDowncall.MMAP.invokeExact(Mmap.at, state, MemorySegment.NULL, length, 3, 0x8001, fd, offset) as MemorySegment
        if (mapped.address() == -1L) {
            val errno = state.get(ValueLayout.JAVA_INT, errnoOffset)
            throw PanamaRingException(errno, "io_uring ring mmap failed: errno $errno")
        }
        return mapped.reinterpret(length)
    }

    fun unmap(segment: MemorySegment) {
        val state = errnoState.get()
        LibcDowncall.MUNMAP.invokeExact(Munmap.at, state, segment, segment.byteSize()) as Int
    }
}

/**
 * One kernel io_uring mapped into this process: SQEs written into the shared SQE array, the SQ tail published with a
 * release fence, CQEs read after an acquire fence, the CQ head published back. Offsets come from io_uring_params as
 * the kernel reports them; nothing about the layout is assumed beyond the SQE (64 bytes) and CQE (16 bytes) ABI.
 * One thread at a time (the backend's monitor) touches it.
 */
internal class PanamaRing private constructor(
    val fd: Int,
    val features: Int,
    val sqEntries: Int,
    val cqEntries: Int,
    private val sq: MemorySegment,
    private val cq: MemorySegment,
    private val sqes: MemorySegment,
    private val sqHead: Long, private val sqTail: Long, private val sqMask: Int, private val sqArray: Long,
    private val cqHead: Long, private val cqTail: Long, private val cqMask: Int, private val cqes: Long,
) : AutoCloseable {
    private var tail: Int = sq.get(ValueLayout.JAVA_INT, sqTail)
    private var published: Int = tail
    private val arena = Arena.ofShared()
    /** struct io_uring_getevents_arg then struct __kernel_timespec, for timed waits (IORING_FEAT_EXT_ARG). */
    private val wait: MemorySegment = arena.allocate(48, 8)
    private var closed = false
    /** Opcodes the running kernel reports supported (IORING_REGISTER_PROBE), as a bit per opcode number. */
    val supported: LongArray = probe()

    companion object {
        const val FEAT_SINGLE_MMAP = 1
        const val FEAT_NODROP = 1 shl 1
        const val FEAT_EXT_ARG = 1 shl 8
        const val ENTER_GETEVENTS = 1
        const val ENTER_EXT_ARG = 1 shl 3
        private const val SETUP_CQSIZE = 1 shl 3
        private const val SETUP_SUBMIT_ALL = 1 shl 7
        private const val SETUP_COOP_TASKRUN = 1 shl 8

        /** A ring of [entries] SQEs and [completions] CQEs; flags newer kernels accept are dropped on EINVAL. */
        fun open(entries: Int, completions: Int): PanamaRing {
            require(entries > 0 && completions >= entries)
            Arena.ofConfined().use { scratch ->
                val params = scratch.allocate(120, 8)
                var flags = SETUP_CQSIZE or SETUP_SUBMIT_ALL or SETUP_COOP_TASKRUN
                var fd: Long
                while (true) {
                    params.fill(0)
                    params.set(ValueLayout.JAVA_INT, 4, completions)
                    params.set(ValueLayout.JAVA_INT, 8, flags)
                    fd = PanamauRingSyscalls.syscall(PanamauRingSyscalls.SETUP, entries.toLong(), params.address())
                    if (fd != -22L || flags == SETUP_CQSIZE) break
                    flags = if (flags and SETUP_COOP_TASKRUN != 0) flags and SETUP_COOP_TASKRUN.inv() else SETUP_CQSIZE
                }
                if (fd < 0) throw PanamaRingException(-fd.toInt(), "io_uring_setup failed: errno ${-fd}")
                val ring = fd.toInt()
                try {
                    val sqEntries = params.get(ValueLayout.JAVA_INT, 0)
                    val cqEntries = params.get(ValueLayout.JAVA_INT, 4)
                    val features = params.get(ValueLayout.JAVA_INT, 20)
                    fun u32(at: Long): Long = params.get(ValueLayout.JAVA_INT, at).toLong() and 0xffffffffL
                    val sqRingSize = u32(64) + 4L * sqEntries
                    val cqRingSize = u32(100) + 16L * cqEntries
                    val sq: MemorySegment
                    val cq: MemorySegment
                    if (features and FEAT_SINGLE_MMAP != 0) {
                        sq = PanamauRingSyscalls.map(maxOf(sqRingSize, cqRingSize), ring, 0L)
                        cq = sq
                    } else {
                        sq = PanamauRingSyscalls.map(sqRingSize, ring, 0L)
                        cq = PanamauRingSyscalls.map(cqRingSize, ring, 0x8000000L)
                    }
                    val sqes = PanamauRingSyscalls.map(64L * sqEntries, ring, 0x10000000L)
                    return PanamaRing(ring, features, sqEntries, cqEntries, sq, cq, sqes,
                        u32(40), u32(44), sq.get(ValueLayout.JAVA_INT, u32(48)), u32(64),
                        u32(80), u32(84), cq.get(ValueLayout.JAVA_INT, u32(88)), u32(100))
                } catch (failure: Throwable) {
                    JvmSocketSyscalls.close(ring)
                    throw failure
                }
            }
        }
    }

    private fun probe(): LongArray {
        val bits = LongArray(4)
        Arena.ofConfined().use { scratch ->
            // struct io_uring_probe: last_op, ops_len, resv, resv2[3], then 256 struct io_uring_probe_op {op, resv, flags, resv2}.
            val probe = scratch.allocate(16 + 256 * 8L, 8)
            probe.fill(0)
            val result = PanamauRingSyscalls.syscall(PanamauRingSyscalls.REGISTER, fd.toLong(), 8L, probe.address(), 256L)
            if (result < 0) return bits
            val count = probe.get(ValueLayout.JAVA_BYTE, 1).toInt() and 0xff
            for (i in 0 until count) {
                val at = 16 + 8L * i
                val op = probe.get(ValueLayout.JAVA_BYTE, at).toInt() and 0xff
                val flags = probe.get(ValueLayout.JAVA_SHORT, at + 2).toInt()
                if (flags and 1 != 0) bits[op ushr 6] = bits[op ushr 6] or (1L shl (op and 63))
            }
        }
        return bits
    }

    fun supports(opcode: Int): Boolean = opcode in 0..255 && supported[opcode ushr 6] and (1L shl (opcode and 63)) != 0L

    /** Free SQ slots: the kernel consumes published entries at enter, so unpublished ones count against the depth. */
    fun space(): Int = sqEntries - (tail - sq.get(ValueLayout.JAVA_INT, sqHead))

    /** Writes one SQE; false when the SQ is full. */
    fun push(opcode: Int, osFd: Int, offset: Long, address: Long, length: Int, operationFlags: Int, userData: Long,
             sqeFlags: Int = 0): Boolean {
        if (space() <= 0) return false
        val index = tail and sqMask
        val at = 64L * index
        sqes.set(ValueLayout.JAVA_BYTE, at, opcode.toByte())
        sqes.set(ValueLayout.JAVA_BYTE, at + 1, sqeFlags.toByte())
        sqes.set(ValueLayout.JAVA_SHORT, at + 2, 0)
        sqes.set(ValueLayout.JAVA_INT, at + 4, osFd)
        sqes.set(ValueLayout.JAVA_LONG, at + 8, offset)
        sqes.set(ValueLayout.JAVA_LONG, at + 16, address)
        sqes.set(ValueLayout.JAVA_INT, at + 24, length)
        sqes.set(ValueLayout.JAVA_INT, at + 28, operationFlags)
        sqes.set(ValueLayout.JAVA_LONG, at + 32, userData)
        sqes.set(ValueLayout.JAVA_LONG, at + 40, 0L)
        sqes.set(ValueLayout.JAVA_LONG, at + 48, 0L)
        sqes.set(ValueLayout.JAVA_LONG, at + 56, 0L)
        sq.set(ValueLayout.JAVA_INT, sqArray + 4L * index, index)
        tail++
        return true
    }

    /** SQEs written but not yet handed to the kernel. */
    fun unsubmitted(): Int = tail - published

    /**
     * io_uring_enter: publishes the SQ tail, submits what was written and, with [minComplete] > 0, waits for that many
     * CQEs, at most [timeoutNanos] when it is not negative. The count submitted, or -errno (-ETIME on timeout).
     */
    fun enter(minComplete: Int, timeoutNanos: Long): Int {
        VarHandle.releaseFence()
        sq.set(ValueLayout.JAVA_INT, sqTail, tail)
        val submit = tail - published
        var flags = if (minComplete > 0) ENTER_GETEVENTS else 0
        var arg = 0L
        var size = 0L
        if (minComplete > 0 && timeoutNanos >= 0) {
            wait.set(ValueLayout.JAVA_LONG, 0, 0L)
            wait.set(ValueLayout.JAVA_INT, 8, 0)
            wait.set(ValueLayout.JAVA_INT, 12, 0)
            wait.set(ValueLayout.JAVA_LONG, 16, wait.address() + 24)
            wait.set(ValueLayout.JAVA_LONG, 24, timeoutNanos / 1_000_000_000L)
            wait.set(ValueLayout.JAVA_LONG, 32, timeoutNanos % 1_000_000_000L)
            flags = flags or ENTER_EXT_ARG
            arg = wait.address()
            size = 24
        }
        val result = PanamauRingSyscalls.syscall(PanamauRingSyscalls.ENTER, fd.toLong(), submit.toLong(), minComplete.toLong(),
            flags.toLong(), arg, size)
        if (result > 0) published += result.toInt()
        return result.toInt()
    }

    /** Hands each available CQE to [action] and releases its slot; the number read. */
    inline fun reap(action: (userData: Long, res: Int, flags: Int) -> Unit): Int {
        var head = cqHeadValue()
        val end = cqTailValue()
        var count = 0
        while (head != end) {
            val at = cqeAt(head)
            action(cqeUserData(at), cqeRes(at), cqeFlags(at))
            head++
            count++
        }
        if (count > 0) releaseCq(head)
        return count
    }

    @PublishedApi internal fun cqHeadValue(): Int = cq.get(ValueLayout.JAVA_INT, cqHead)
    @PublishedApi internal fun cqTailValue(): Int = cq.get(ValueLayout.JAVA_INT, cqTail).also { VarHandle.acquireFence() }
    @PublishedApi internal fun cqeAt(head: Int): Long = cqes + 16L * (head and cqMask)
    @PublishedApi internal fun cqeUserData(at: Long): Long = cq.get(ValueLayout.JAVA_LONG_UNALIGNED, at)
    @PublishedApi internal fun cqeRes(at: Long): Int = cq.get(ValueLayout.JAVA_INT, at + 8)
    @PublishedApi internal fun cqeFlags(at: Long): Int = cq.get(ValueLayout.JAVA_INT, at + 12)
    @PublishedApi internal fun releaseCq(head: Int) {
        VarHandle.releaseFence()
        cq.set(ValueLayout.JAVA_INT, cqHead, head)
    }

    override fun close() {
        if (closed) return
        closed = true
        PanamauRingSyscalls.unmap(sqes)
        if (cq !== sq) PanamauRingSyscalls.unmap(cq)
        PanamauRingSyscalls.unmap(sq)
        JvmSocketSyscalls.close(fd)
        arena.close()
    }
}

/** IORING_OP_* numbers this actual encodes for the kernel; everything else executes in [JvmUserspaceChannelBackend]. */
private const val OP_NOP = 0
private const val OP_POLL_ADD = 6
private const val OP_POLL_REMOVE = 7
private const val OP_ACCEPT = 13
private const val OP_ASYNC_CANCEL = 14
private const val OP_CONNECT = 16
private const val OP_READ = 22
private const val OP_WRITE = 23
private const val OP_SEND = 26
private const val OP_RECV = 27
private const val CANCEL_ALL = 1
private const val CANCEL_FD = 1 shl 1
private const val CANCEL_ANY = 1 shl 2

/**
 * [UserspaceChannelBackend] whose sockets and descriptors are served by a kernel io_uring through FFM. Descriptor
 * identity stays the JVM table's, as with emulation, so one fd may pass between both executors: SOCKET, BIND,
 * LISTEN, CLOSE and the path and metadata operations execute in the owned [emulated] backend; POLL_ADD,
 * POLL_REMOVE, ASYNC_CANCEL, ACCEPT, CONNECT, RECV, SEND, READ, WRITE and NOP are SQEs in the kernel ring, and
 * [nativeCapabilities] reports that set as the kernel probe confirms it. [capabilities] equals emulation's.
 */
internal class PanamauChannelBackend(
    private val ring: PanamaRing,
    private val emulated: JvmUserspaceChannelBackend = JvmUserspaceChannelBackend("emulated: delegate of the FFM io_uring ring"),
    /** How long [cancelPending] waits for the kernel's cancellation CQEs. */
    private val cancelWaitNanos: Long = 5_000_000_000L,
) : UserspaceChannelBackend {
    /** A kernel SQE between submission and CQE: what its CQE must still do. */
    private class Native(val submission: UringSubmission, val osFd: Int, val scratch: MemorySegment?, val epoch: Int)

    override val capabilities: Long get() = jvmUringOperations
    override val nativeCapabilities: Long = listOf(
        UringOp.NOP to OP_NOP, UringOp.POLL_ADD to OP_POLL_ADD, UringOp.POLL_REMOVE to OP_POLL_REMOVE,
        UringOp.ACCEPT to OP_ACCEPT, UringOp.ASYNC_CANCEL to OP_ASYNC_CANCEL, UringOp.CONNECT to OP_CONNECT,
        UringOp.READ to OP_READ, UringOp.WRITE to OP_WRITE, UringOp.SEND to OP_SEND, UringOp.RECV to OP_RECV,
    ).filter { ring.supports(it.second) }.fold(0L) { mask, (op, _) -> mask or op.mask }
    override val deferredCapabilities: Long get() = nativeCapabilities or emulated.deferredCapabilities
    override val availability: String =
        "io_uring: FFM ring ${ring.sqEntries}/${ring.cqEntries} entries, features 0x${ring.features.toString(16)}; " +
            "kernel executes ${UringOp.entries.filter { nativeCapabilities and it.mask != 0L }.joinToString(",")}; " +
            "other operations emulated"

    private val inFlight = LongTable<Native>()
    /** Kernel SQEs per OS descriptor: a CLOSE first cancels what the kernel still holds on it. */
    private var onFd = IntArray(1024)
    /** Bumped by each CLOSE of the descriptor: an SQE of an older epoch cancelled since then ends with EBADF. */
    private var fdEpoch = IntArray(1024)
    private val completions = ArrayDeque<SelectionResult>()
    private var internal = Long.MIN_VALUE
    private var internalInFlight = 0
    private val arena = Arena.ofShared()
    private var closed = false
    private var draining = false
    private var enters = 0L
    private var submitted = 0L
    private var reaped = 0L

    private fun osFd(fd: Int): Int = when (val descriptor = JvmFileTable.descriptor(fd)) {
        is JvmSocketDescriptor -> if (descriptor.isOpen()) descriptor.fd else -1
        is JvmNativeDescriptor -> if (descriptor.isOpen()) descriptor.fd else -1
        else -> -1
    }

    /** Batch tokens whose CQE submitBatch must return: file transfers and NOP, synchronous under every executor. */
    private var synchronous = LongArray(16)

    @Synchronized
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        val results = ArrayList<SelectionResult>(submissions.size)
        var waitFor = 0
        for (sub in submissions) {
            val immediate = stage(sub)
            if (immediate != null) results.add(SelectionResult(immediate, sub.userData))
            else if (sub.opcode == UringOp.NOP || (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE) &&
                JvmFileTable.descriptor(sub.fd) !is JvmSocketDescriptor) {
                if (waitFor == synchronous.size) synchronous = synchronous.copyOf(waitFor * 2)
                synchronous[waitFor++] = sub.userData
            }
        }
        flush(0, -1L)
        drainCq()
        // A buffered file write can be punted to io-wq; the batch still settles it, as emulation and JNI do.
        while ((0 until waitFor).any { synchronous[it] in inFlight }) {
            flush(1, -1L)
            drainCq()
        }
        while (completions.isNotEmpty()) results.add(completions.removeFirst())
        return results
    }

    /** Writes [sub] as an SQE, or executes it in emulation: null when its CQE comes from the kernel ring. */
    private fun stage(sub: UringSubmission): Int? {
        if (closed) return -9
        if (sub.flags != 0) return -95
        if (sub.userData in inFlight) return -17
        val code = when (sub.opcode) {
            UringOp.NOP -> OP_NOP
            UringOp.POLL_ADD -> OP_POLL_ADD
            UringOp.POLL_REMOVE -> OP_POLL_REMOVE
            UringOp.ASYNC_CANCEL -> OP_ASYNC_CANCEL
            UringOp.ACCEPT -> OP_ACCEPT
            UringOp.CONNECT -> OP_CONNECT
            UringOp.READ -> OP_READ
            UringOp.WRITE -> OP_WRITE
            UringOp.SEND -> OP_SEND
            UringOp.RECV -> OP_RECV
            else -> -1
        }
        if (code < 0 || nativeCapabilities and sub.opcode.mask == 0L) return delegate(sub)
        if (draining && code != OP_NOP) return -125
        var osFd = -1
        var offset = 0L
        var address = 0L
        var length = 0
        var operationFlags = 0
        var scratch: MemorySegment? = null
        when (code) {
            OP_NOP -> {}
            OP_POLL_REMOVE, OP_ASYNC_CANCEL -> {
                // Watches the emulation holds (JDK channels) are cancelled there.
                if (sub.addr !in inFlight) return delegate(sub)
                if (code == OP_ASYNC_CANCEL && (sub.operationFlags != 0 || sub.len != 0 || sub.offset != 0L)) return delegate(sub)
                address = sub.addr
            }
            else -> {
                osFd = osFd(sub.fd)
                if (osFd < 0) return delegate(sub)
                when (code) {
                    OP_POLL_ADD -> {
                        if (sub.len != 0 || sub.operationFlags and 0x3f.inv() != 0) return -95
                        operationFlags = sub.operationFlags
                    }
                    OP_ACCEPT -> operationFlags = 0x800 or 0x80000 // SOCK_NONBLOCK | SOCK_CLOEXEC, as emulation configures
                    OP_CONNECT -> {
                        if (sub.operationFlags != 0) return -95
                        if (JvmFileTable.descriptor(sub.fd) !is JvmSocketDescriptor) return -88
                        scratch = sockaddr(sub) ?: return -22
                        address = scratch.address()
                        offset = sub.len.toLong()
                    }
                    else -> {
                        val read = code == OP_READ || code == OP_RECV
                        if (sub.len < 0) return -22
                        if (code == OP_SEND || code == OP_RECV) {
                            if (sub.operationFlags != 0) return -95
                            if (sub.offset != -1L && sub.offset != 0L) return -29
                            if (code == OP_SEND) operationFlags = 0x4000 // MSG_NOSIGNAL
                        } else offset = if (JvmFileTable.descriptor(sub.fd) is JvmSocketDescriptor) -1L else sub.offset
                        val buffer = sub.buffer
                        if (buffer != null) {
                            if (sub.len > buffer.remaining() || read && buffer.isReadOnly()) return -22
                            scratch = scratch(maxOf(1, sub.len))
                            if (!read) MemorySegment.copy(MemorySegment.ofArray(buffer.array()),
                                (buffer.arrayOffset() + buffer.position()).toLong(), scratch, 0, sub.len.toLong())
                            address = scratch.address()
                        } else {
                            if (sub.addr == 0L && sub.len != 0) return -14
                            address = sub.addr
                        }
                        length = sub.len
                    }
                }
            }
        }
        if (ring.space() <= 0) flush(0, -1L)
        check(ring.push(code, osFd, offset, address, length, operationFlags, sub.userData)) { "io_uring SQ full after enter" }
        if (osFd >= onFd.size) {
            onFd = onFd.copyOf(maxOf(osFd + 1, onFd.size * 2))
            fdEpoch = fdEpoch.copyOf(onFd.size)
        }
        inFlight.put(sub.userData, Native(sub, osFd, scratch, if (osFd >= 0) fdEpoch[osFd] else 0))
        if (osFd >= 0) onFd[osFd]++
        return null
    }

    /** The submission's sockaddr in native memory the CQE outlives; Linux framing, as BIND and CONNECT carry it. */
    private fun sockaddr(sub: UringSubmission): MemorySegment? {
        if (sub.len < 2 || sub.len > 128) return null
        val buffer = sub.buffer
        if (buffer != null && sub.len > buffer.remaining()) return null
        if (buffer == null && sub.addr == 0L) return null
        val segment = scratch(sub.len)
        if (buffer != null) {
            MemorySegment.copy(MemorySegment.ofArray(buffer.array()), (buffer.arrayOffset() + buffer.position()).toLong(),
                segment, 0, sub.len.toLong())
        } else MemorySegment.copy(MemorySegment.ofAddress(sub.addr).reinterpret(sub.len.toLong()), 0,
            segment, 0, sub.len.toLong())
        return segment
    }

    /** Native staging for heap buffers and sockaddrs, by power-of-two size; recycled at the CQE, freed at close. */
    private val pool = HashMap<Long, ArrayDeque<MemorySegment>>()

    private fun scratch(length: Int): MemorySegment {
        var size = 64L
        while (size < length) size = size shl 1
        return pool[size]?.removeLastOrNull() ?: arena.allocate(size, 8)
    }

    private fun recycle(segment: MemorySegment) {
        pool.getOrPut(segment.byteSize()) { ArrayDeque() }.addLast(segment)
    }

    private fun delegate(sub: UringSubmission): Int? {
        if (sub.opcode == UringOp.CLOSE) {
            // The kernel holds a reference on every descriptor an SQE names: cancel those before the close, or the
            // close would not release the file until they complete.
            val osFd = osFd(sub.fd)
            if (osFd in onFd.indices && onFd[osFd] > 0) {
                fdEpoch[osFd]++
                internalOp(OP_ASYNC_CANCEL, osFd, 0L, CANCEL_FD or CANCEL_ALL)
                flush(0, -1L)
            }
        }
        val results = emulated.submitBatch(listOf(sub))
        var own: Int? = null
        for (result in results) if (result.userData == sub.userData && own == null) own = result.res else completions.addLast(result)
        return own
    }

    private fun internalOp(code: Int, osFd: Int, address: Long, operationFlags: Int) {
        var token = internal++
        while (token in inFlight) token = internal++
        if (ring.space() <= 0) flush(0, -1L)
        check(ring.push(code, osFd, 0L, address, 0, operationFlags, token)) { "io_uring SQ full after enter" }
        internalInFlight++
    }

    /** Submits what is written; with [minComplete] > 0 waits for CQEs, at most [timeoutNanos] when not negative. */
    private fun flush(minComplete: Int, timeoutNanos: Long) {
        if (ring.unsubmitted() == 0 && minComplete == 0) return
        enters++
        val before = ring.unsubmitted()
        val result = ring.enter(minComplete, timeoutNanos)
        if (result >= 0) submitted += before.coerceAtMost(result)
        else if (result != -62 && result != -4 && result != -16 && result != -11)
            throw PanamaRingException(-result, "io_uring_enter failed: errno ${-result}")
    }

    private fun drainCq() {
        ring.reap { userData, res, _ ->
            reaped++
            val native = inFlight.remove(userData)
            if (native == null) {
                if (internalInFlight > 0 && userData < 0) internalInFlight--
                return@reap
            }
            if (native.osFd >= 0) onFd[native.osFd]--
            // A watch the CLOSE of its descriptor cancelled reports EBADF, as the emulated executor does.
            val closedUnder = res == -125 && native.osFd >= 0 && native.epoch != fdEpoch[native.osFd]
            completions.addLast(SelectionResult(if (closedUnder) -9 else finish(native, res), userData))
            native.scratch?.let(::recycle)
        }
    }

    /** Completes what the kernel result implies: an accepted descriptor joins the table, read bytes reach the heap. */
    private fun finish(native: Native, res: Int): Int {
        val sub = native.submission
        return when (sub.opcode) {
            UringOp.ACCEPT -> if (res < 0) res else JvmFileTable.register(JvmSocketDescriptor(res))
            // The kernel always reports EPOLLRDHUP (IO_POLL_UNMASK); the portable vocabulary is the POLL bits 0x3f, and
            // the peer's shutdown reaches the next RECV as its zero-byte completion under either executor.
            UringOp.POLL_ADD -> if (res < 0) res else res and 0x3f
            UringOp.READ, UringOp.RECV, UringOp.WRITE, UringOp.SEND -> {
                val buffer = sub.buffer
                val scratch = native.scratch
                if (buffer != null && scratch != null && res > 0) {
                    if (sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV)
                        MemorySegment.copy(scratch, 0, MemorySegment.ofArray(buffer.array()),
                            (buffer.arrayOffset() + buffer.position()).toLong(), res.toLong())
                    buffer.position(buffer.position() + res)
                }
                // The kernel ring reports end of file as a zero-byte completion; the stream contract is the same.
                res
            }
            else -> res
        }
    }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> = reapCompletions(minComplete, -1L)

    override fun reapCompletions(minComplete: Int, timeoutNanos: Long): List<SelectionResult> {
        require(minComplete >= 0)
        val results = ArrayList<SelectionResult>()
        val deadline = if (timeoutNanos < 0L) Long.MAX_VALUE else System.nanoTime() + timeoutNanos
        while (true) {
            val more = synchronized(this) {
                drainCq()
                while (completions.isNotEmpty()) results.add(completions.removeFirst())
                results.addAll(emulated.reapCompletions(0))
                if (results.size >= minComplete || inFlight.size == 0) return@synchronized false
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@synchronized false
                // One bounded slice in the kernel per monitor hold, as the emulated backend waits.
                flush(1, minOf(remaining, 10_000_000L))
                drainCq()
                while (completions.isNotEmpty()) results.add(completions.removeFirst())
                results.size < minComplete && inFlight.size > 0 && System.nanoTime() < deadline
            }
            if (!more) break
        }
        return results
    }

    @Synchronized
    override fun cancelPending() {
        draining = true
        emulated.cancelPending()
        if (inFlight.size > 0) {
            internalOp(OP_ASYNC_CANCEL, -1, 0L, CANCEL_ANY or CANCEL_ALL)
            // Cancellation completes in the kernel; collect every terminal CQE so drain finds them reapable.
            val deadline = System.nanoTime() + cancelWaitNanos
            flush(0, -1L)
            drainCq()
            while (inFlight.size > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) break
                flush(1, minOf(remaining, 10_000_000L))
                drainCq()
            }
        }
    }

    @Synchronized
    override fun counters(): Map<String, Long> = linkedMapOf(
        "native.inFlight" to inFlight.size.toLong(), "native.enters" to enters, "native.submitted" to submitted,
        "native.reaped" to reaped, "native.features" to ring.features.toLong(),
    ) + emulated.counters()

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val order = List(submissions.size) { submissions[it].userData }
        val outstanding = order.toHashSet()
        val byToken = HashMap<Long, Int>()
        fun take(result: SelectionResult) {
            check(outstanding.remove(result.userData)) { "foreign or duplicate CQE for token ${result.userData}" }
            byToken[result.userData] = result.res
        }
        submitBatch(List(submissions.size) { submissions[it] }).forEach(::take)
        while (outstanding.isNotEmpty()) {
            reapCompletions(0).forEach(::take)
            if (outstanding.isNotEmpty()) kotlinx.coroutines.delay(1)
        }
        return order.size j { UringCompletion(order[it], byToken.getValue(order[it]), 0) }
    }

    /**
     * Ends the kernel ring once every SQE it holds has its terminal CQE. While the kernel may still write into a
     * staging segment or a caller's buffer, nothing is unmapped or freed: close throws, and may be called again.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        cancelPending()
        check(inFlight.size == 0) {
            "${inFlight.size} io_uring operations lack a terminal CQE after cancellation; ring and staging retained"
        }
        closed = true
        ring.close()
        emulated.close()
        arena.close()
    }
}

/** The kernel ring when [entries] can be set up here and the probe confirms the socket operations, else null. */
internal fun openPanamaRing(entries: Int): PanamaRing =
    PanamaRing.open(entries, maxOf(entries * 8, 4096).coerceAtMost(1 shl 16).coerceAtLeast(entries))
