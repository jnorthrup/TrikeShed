package borg.trikeshed.pgwire

import borg.trikeshed.cursor.query.PostgrestSurface
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.SocketProtocol
import borg.trikeshed.userspace.nio.channels.SocketType
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.channels.sockaddr
import borg.trikeshed.userspace.nio.channels.sockaddrText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * The pg wire listener: one scoped ring under an owning SupervisorJob, an accept stage that admits at
 * most [maxConnections] sessions, and per connection a reader stage feeding a bounded channel of
 * decoded frames to a session stage that answers them. Readiness is POLL_ADD on the shared ring, so
 * an idle connection holds one SQE; ACCEPT / READ / WRITE that would block answer -11 on the emulated
 * ring and suspend on the kernel ring, and the loops are written for both. [drain] stops admission,
 * cancels the readiness every stage waits on, joins every connection (each closes its descriptor),
 * closes the listener and drains the ring.
 */
class PgServerElement(
    parent: CoroutineScope,
    val surface: PostgrestSurface,
    val address: String = "0.0.0.0:$DEFAULT_PORT",
    val maxConnections: Int = 64,
    entries: Int = 2 * maxConnections + 16,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PgServerElement> {
        const val DEFAULT_PORT = 5433
        const val EAGAIN = -11
        const val EINTR = -4
        const val ECANCELED = -125
        /** accept(2) errnos that are the peer's or the network's, not the listener's: the loop goes on. */
        val ACCEPT_TRANSIENT = setOf(-1, -64, -71, -92, -95, -100, -101, -103, -112, -113)
        /** accept(2) errnos of exhausted resources (ENOMEM, ENFILE, EMFILE, ENOBUFS): the loop pauses, then goes on. */
        val ACCEPT_EXHAUSTED = setOf(-12, -23, -24, -105)
        const val FRAME_BUFFER = 8192
        /** Cancel passes in [release], a millisecond apart, before the stages are joined regardless. */
        const val CANCEL_PASSES = 2000
    }

    override val key: CoroutineContext.Key<*> get() = Key
    private val owner = SupervisorJob(requireNotNull(parent.coroutineContext[Job]) { "pg wire requires an owning Job" })
    private val scope = CoroutineScope(parent.coroutineContext + Dispatchers.Default + owner + this)
    private val ring: FunctionalUringFacade = UringChannels.open(scope, entries)
    private val tokens = Mutex()
    private var token = 0L
    /** Guards [waiting], [connections] and the listener. */
    private val registry = Mutex()
    /** Completes when [drain] begins; SQE registration, session admission and [open] refuse after it. */
    val closed: CompletableJob = Job()
    /** Token of a readiness or accept SQE a stage waits on → its descriptor; cancelled when the session ends or at drain. */
    private val waiting = HashMap<Long, Int>()
    private val connections = LinkedHashSet<Job>()
    private var accepts: Job? = null
    private var listenFd = -1
    private var pids = 0

    /** The bound address, `a.b.c.d:port`, once [open] has listened (the port is read back, so `:0` works). */
    var localAddress: String? = null
        private set

    /** Sessions still running. */
    suspend fun connected(): Int = registry.withLock { connections.count { it.isActive } }

    private suspend fun tok(): Long = tokens.withLock { ++token }

    private suspend fun submit(submission: UringSubmission): Int {
        val done = ring.batchEnqueue(s_[submission])
        check(done.size == 1 && done[0].userData == submission.userData) { "pg wire CQE correlation failed" }
        return done[0].res
    }

    private suspend fun expect(op: UringOp, result: Int): Int = result.also { if (it < 0) throw UringIOException(op, it) }

    /** A SQE that may stay pending until the peer acts, registered so [release] and [drain] can cancel it. */
    private suspend fun blocking(fd: Int, make: (Long) -> UringSubmission): Int {
        val id = registry.withLock { if (closed.isCompleted) return ECANCELED; tok().also { waiting[it] = fd } }
        try { return submit(make(id)) } finally { registry.withLock { waiting.remove(id) } }
    }

    /**
     * Settles the pending SQEs of [fd] (every descriptor when null) with ASYNC_CANCEL, so the stages waiting on
     * them return -125: a cancelled caller of the ring is not released until its SQE settles. A cancel that
     * finds nothing (-2) met an SQE registered but not yet submitted; it is found on a later pass.
     */
    private suspend fun release(fd: Int?) {
        var passes = 0
        while (passes++ < CANCEL_PASSES) {
            val targets = registry.withLock { waiting.filterValues { fd == null || it == fd }.keys.toList() }
            if (targets.isEmpty()) return
            for (target in targets) submit(UringSubmission(UringOp.ASYNC_CANCEL, -1, target, 0, 0, userData = tok()))
            delay(1)
        }
    }

    /** SOCKET / ACCEPT allocate a descriptor the caller owns: a cancelled caller still closes it. */
    private suspend fun allocate(op: UringOp, make: suspend () -> Int): Int {
        val caller = currentCoroutineContext()
        val fd = withContext(NonCancellable) { make() }
        if (fd >= 0 && !caller.isActive) {
            withContext(NonCancellable) { submit(Submissions.close(fd, tok())) }
            caller.ensureActive()
        }
        return expect(op, fd)
    }

    suspend fun open(): PgServerElement {
        registry.withLock { check(!closed.isCompleted) { "pg wire closed" }; check(listenFd < 0) { "pg wire already open" } }
        val domain = if (address.startsWith("[")) SocketDomain.AF_INET6 else SocketDomain.AF_INET
        val fd = allocate(UringOp.SOCKET) {
            submit(Submissions.socket(domain.posix, SocketType.withFlags(SocketType.SOCK_STREAM, SocketType.SOCK_NONBLOCK, SocketType.SOCK_CLOEXEC), SocketProtocol.IPPROTO_TCP.posix, tok()))
        }
        try {
            // SO_REUSEADDR through the SETSOCKOPT socket command; a backend without the command answers -95 and the bind
            // then waits out TIME_WAIT like any plain listener, so the result is recorded, not fatal.
            reuseAddress = submit(Submissions.setsockopt(fd, UringOp.SOL_SOCKET, UringOp.SO_REUSEADDR, ByteBuffer(4).order(ByteOrder.nativeOrder()).putInt(0, 1).array(), tok()))
            expect(UringOp.BIND, submit(Submissions.bind(fd, sockaddr(address), tok())))
            expect(UringOp.LISTEN, submit(Submissions.listen(fd, 128, tok())))
            val named = ByteBuffer(128)
            expect(UringOp.URING_CMD, submit(Submissions.getsockname(fd, named, false, tok())))
            localAddress = sockaddrText(named.array())
            registry.withLock {
                check(!closed.isCompleted) { "pg wire drained while opening" }
                listenFd = fd
                accepts = scope.launch { acceptLoop(fd) }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { submit(Submissions.close(fd, tok())) }
            throw failure
        }
        return this
    }

    /** The SETSOCKOPT result for SO_REUSEADDR: 0 when set, -95 on a backend without socket commands. */
    var reuseAddress: Int = 0
        private set

    private suspend fun acceptLoop(listen: Int) {
        val caller = currentCoroutineContext()
        while (caller.isActive) {
            val ready = blocking(listen) { Submissions.pollAdd(listen, UringOp.POLLIN, it) }
            if (ready == ECANCELED) return
            expect(UringOp.POLL_ADD, ready)
            // The accepted descriptor is owned here from its CQE on: a cancellation in flight still closes it.
            val fd = withContext(NonCancellable) {
                blocking(listen) { Submissions.accept(listen, SocketType.withFlags(SocketType.SOCK_NONBLOCK, SocketType.SOCK_CLOEXEC), it) }
            }
            when {
                fd == EAGAIN || fd == EINTR || fd in ACCEPT_TRANSIENT -> continue
                fd in ACCEPT_EXHAUSTED -> delay(50)
                fd == ECANCELED -> return
                fd < 0 -> throw UringIOException(UringOp.ACCEPT, fd)
                !caller.isActive -> { withContext(NonCancellable) { submit(Submissions.close(fd, tok())) }; return }
                else -> admit(fd)
            }
        }
    }

    private suspend fun admit(fd: Int) {
        var transferred = false
        try {
            registry.withLock {
                connections.removeAll { it.isCompleted }
                if (!closed.isCompleted && connections.size < maxConnections) {
                    val pid = ++pids
                    // Enter try/finally before handing the descriptor to a child: a child cancelled before its body never cleans up.
                    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { serve(fd, pid) }
                    connections += job
                    transferred = true
                }
            }
        } finally {
            if (!transferred) withContext(NonCancellable) { submit(Submissions.close(fd, tok())) }
        }
    }

    /** One connection: the reader stage decodes frames into a bounded channel; the session stage answers them. */
    private suspend fun serve(fd: Int, pid: Int) {
        val frames = Channel<PgFrontend>(16)
        val session = PgSession(surface, pid, secret = (pid * 2654435761L).toInt())
        try {
            coroutineScope {
                val reader = launch {
                    try { readFrames(fd, frames) }
                    catch (failure: PgProtocolError) { frames.send(PgFrontend.Malformed(failure.message ?: "protocol violation")) }
                    finally { frames.close() }
                }
                for (frame in frames) {
                    val reply = session.receive(frame)
                    if (reply.isNotEmpty()) write(fd, reply)
                    if (session.closed) {
                        // The reader is parked on this descriptor's readiness; cancellation alone would wait for the peer.
                        reader.cancel()
                        release(fd)
                        reader.join()
                        break
                    }
                }
            }
        } catch (gone: UringIOException) {
            // The peer reset or closed its end mid-session: the connection ends, the element does not.
        } finally {
            withContext(NonCancellable) { submit(Submissions.close(fd, tok())) }
        }
    }

    private suspend fun readFrames(fd: Int, frames: Channel<PgFrontend>) {
        var pending = ByteArray(FRAME_BUFFER)
        var size = 0
        var startup = true
        while (currentCoroutineContext().isActive) {
            if (size == pending.size) {
                if (size >= PgWire.MAX_FRAME) throw PgProtocolError("message exceeds the largest frame")
                pending = pending.copyOf(minOf(pending.size * 2L, PgWire.MAX_FRAME + 5L).toInt())
            }
            val ready = blocking(fd) { Submissions.pollAdd(fd, UringOp.POLLIN, it) }
            if (ready == ECANCELED) return
            expect(UringOp.POLL_ADD, ready)
            val window = ByteBuffer(pending, size, pending.size - size)
            // The kernel ring parks a READ in the kernel rather than answering -11: registered, so drain can settle it.
            val count = blocking(fd) { Submissions.read(fd, 0, window.remaining(), -1, it).copy(buffer = window) }
            if (count == 0) return
            if (count == EAGAIN || count == EINTR) continue
            if (count == ECANCELED) return
            expect(UringOp.READ, count)
            size += count
            var at = 0
            while (true) {
                val (message, used) = PgWire.decode(pending, at, size, startup) ?: break
                at += used
                if (message is PgFrontend.Startup) startup = false
                frames.send(message)
            }
            if (at > 0) { pending.copyInto(pending, 0, at, size); size -= at }
        }
    }

    private suspend fun write(fd: Int, bytes: ByteArray) {
        val out = ByteBuffer(bytes)
        while (out.hasRemaining()) {
            val count = blocking(fd) { Submissions.write(fd, 0, out.remaining(), -1, it).copy(buffer = out) }
            if (count == ECANCELED) return
            if (count == EAGAIN || count == EINTR) {
                val ready = blocking(fd) { Submissions.pollAdd(fd, UringOp.POLLOUT, it) }
                if (ready == ECANCELED) return
                expect(UringOp.POLL_ADD, ready)
                continue
            }
            expect(UringOp.WRITE, count)
        }
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        if (!closed.complete()) return@withContext owner.join()
        release(null)
        accepts?.cancelAndJoin()
        val open = registry.withLock { connections.toList() }
        for (job in open) job.cancel()
        open.joinAll()
        val listen = registry.withLock { listenFd.also { listenFd = -1 } }
        if (listen >= 0) submit(Submissions.close(listen, tok()))
        ring.drain()
        owner.complete()
        owner.join()
    }

    suspend fun close() = drain()
}
