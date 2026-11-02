package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.vm.bool
import borg.trikeshed.vm.field
import borg.trikeshed.vm.int
import borg.trikeshed.vm.str
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.process.ProcessCapability
import borg.trikeshed.userspace.nio.process.SecurityException
import borg.trikeshed.userspace.nio.process.ProcessCalls
import borg.trikeshed.userspace.nio.process.ProcessCallTimeout
import borg.trikeshed.userspace.nio.process.ProcessProtocolChannel
import borg.trikeshed.userspace.nio.process.ProcessFrameChannel
import borg.trikeshed.userspace.nio.channels.spi.processHostCall
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolStream
import borg.trikeshed.userspace.nio.channels.spi.ProcessOutput
import borg.trikeshed.terminal.TerminalInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The wire protocol is common code now; this alias keeps jvm call sites and tests unchanged. */
typealias SubVmProtocol = borg.trikeshed.vm.SubVmProtocol

/**
 * A guest behind a process wall. The child is either a JVM running [SubVmMain] (the same
 * [InProcessIsolate] bounds apply inside it) or — for JS with `nodeLauncher` — the GraalJS `node`
 * launcher, which is the only way to get Node.js APIs (`require`, `process`, the event loop): the
 * in-process JS engine deliberately does not have them. [pythonWorker] instead starts one stock
 * GraalPy context with native Python facilities. It is explicitly OWN-trust and never changes
 * the permissions of the default restricted tiers.
 *
 * Crash isolation is the point: a guest that kills its JVM (OOM, native crash, a GraalPy GIL
 * assertion) costs the parent one [isAlive]=false, never the parent itself.
 */
class ProcessIsolate(
    override val id: String,
    override val facet: VmFacet,
    val budget: Budget = Budget(),
    private val nodeLauncher: File? = null,
    private val capability: ProcessCapability = ProcessCapability("subvm-$id", setOf("java", "node")),
    private val replyTimeoutMillis: Long = 30_000,
    private val terminalOutput: OutputStream? = null,
    private val terminalError: OutputStream? = terminalOutput,
    val pythonWorker: PythonWorker? = null,
) : GuestIsolate {
    override val trust = if (pythonWorker == null) Trust.UNTRUSTED else Trust.OWN
    override val bounds: FacetBounds = GuestBounds.of(facet)

    /**
     * Eligible for bounded host leases in the restricted child JS engine, never Node.
     * JS uses its wall watchdog only with statements disabled; this policy therefore
     * does not claim a statement or memory limit. Protocol frame size is separately
     * unbounded and must not be treated as resource containment.
     * This is not the ordinary duplex ProcessIsolate lifecycle: while a crossing is
     * active, other eval/call/delegate/interrupt/close/pushInput calls fail fast rather
     * than waiting or delivering out-of-band cancellation/input. The owner must await
     * the crossing before close. A callback which ignores interruption may indefinitely
     * delay that crossing's cleanup even after the child has been revoked.
     */
    val restrictedJsLeaseCapable: Boolean get() = pythonWorker == null && nodeLauncher == null &&
        facet == VmFacet.GRAAL_JS && budget.statements == 0L && budget.wallMillis > 0 &&
        replyTimeoutMillis in 1..MAX_RESTRICTED_JS_REPLY_MILLIS &&
        budget.wallMillis < replyTimeoutMillis - RESTRICTED_JS_LEASE_MARGIN_MILLIS

    private val lock = ReentrantLock()
    private val transport: ProcessProtocolChannel
    private val inbox = LinkedBlockingQueue<Teleported.Obj>()
    private class HostDelegate(val timeoutMillis: Long?, val function: (List<Teleported>) -> Teleported)
    private val delegates = ConcurrentHashMap<String, HostDelegate>()
    private val hostDispatch: ProcessCalls
    private val ids = AtomicInteger()
    private val evals = AtomicLong(); private val calls = AtomicLong(); private val hostCalls = AtomicLong(); private val interrupted = AtomicLong()
    @Volatile private var alive = true
    @Volatile private var accepting = true
    @Volatile private var revoked = false
    @Volatile private var restrictedJsLeased = false
    private val restrictedHostCall = ThreadLocal.withInitial { false }
    val processId: Long get() = transport.processId

    private companion object {
        val surveyed = java.util.concurrent.atomic.AtomicBoolean(false)
        const val MAX_RESTRICTED_JS_REPLY_MILLIS = 3_600_000L
        const val RESTRICTED_JS_LEASE_MARGIN_MILLIS = 1_000L
    }

    init {
        require(pythonWorker == null || facet == VmFacet.GRAAL_PYTHON && nodeLauncher == null) {
            "PythonWorker requires the Python facet and its own child JVM"
        }
        // Filesystem preconditions hold at launch, not configuration: a daemon configures an unprovisioned worker at boot.
        pythonWorker?.run {
            require(workingDirectory == null || File(workingDirectory).isDirectory) { "Python worker directory is missing" }
            require(pythonPath.all { File(it).exists() }) { "Python worker import path is missing" }
            require(executable == null || File(executable).isFile) { "Python worker executable is missing" }
        }
        deliberateHostEnvironmentOnce()
        val pipes = runBlocking { JvmProcessProtocolPipes.open(command = { port, token ->
            if (nodeLauncher != null) {
                require(facet == VmFacet.GRAAL_JS) { "nodeLauncher only serves JS" }
                guard(nodeLauncher.path)
                listOf(nodeLauncher.path, "-e", NodeSide.SCRIPT)
            } else {
                val java = File(System.getProperty("java.home"), "bin/java").path
                guard(java)
                if (pythonWorker == null)
                    listOf(java, "-Xss4m", "-cp", System.getProperty("java.class.path"), SubVmMain::class.java.name, bounds.languageId, id, budget.statements.toString(), budget.wallMillis.toString())
                else listOf(java, "--enable-native-access=ALL-UNNAMED", "-Xss4m", "-cp", System.getProperty("java.class.path"),
                    PythonWorkerMain::class.java.name, id, pythonWorker.encode(),
                    requireNotNull(port).toString(), requireNotNull(token))
            }
        }, environment = GuestEnvironment.curated() + pythonWorker?.environment.orEmpty(),
            workingDirectory = pythonWorker?.workingDirectory,
            protocolTimeoutMillis = pythonWorker?.timeoutMillis,
            inheritError = terminalError == null,
            mergeError = pythonWorker != null && terminalOutput != null && terminalOutput === terminalError) }
        hostDispatch = ProcessCalls(Dispatchers.Default, concurrency = if (pythonWorker == null) 1 else 4) { response ->
            val message = response.b.fold(
                { Teleported.obj("id" to response.a, "value" to it) },
                { failure -> Teleported.obj("id" to response.a, "error" to
                    if (failure is ProcessCallTimeout) "host delegate '${failure.operation}' exceeded ${failure.timeoutMillis}ms"
                    else (failure.message ?: failure.toString())) },
            )
            try { sendFrame(message) }
            catch (failure: Throwable) { if (isAlive) throw failure }
        }
        transport = ProcessProtocolChannel(Dispatchers.Default, pipes,
            onLine = { line ->
                val msg = if (line.isBlank()) null else runCatching { SubVmProtocol.decode(line) }.getOrNull()
                if (msg != null) when (msg.str("op")) {
                    "host" -> answerHostCall(msg, this)
                    "terminal" -> forwardTerminal(msg)
                    else -> inbox.put(msg)
                }
            },
            onTerminal = { output, bytes -> processHostCall {
                val stream = if (output == ProcessOutput.Stderr) terminalError else terminalOutput
                stream?.write(bytes); stream?.flush(); Unit
            } },
            onEnd = { alive = false; inbox.offer(Teleported.obj("op" to "dead")) })
    }

    private fun deliberateHostEnvironmentOnce() {
        if (!surveyed.compareAndSet(false, true)) return
        val survey = GuestEnvironment.surveyHostEnvironment()
        val blocked = survey[GuestEnvironment.Disposition.BLOCKED].orEmpty()
        val deferred = survey[GuestEnvironment.Disposition.DEFERRED].orEmpty()
        System.err.println(
            "[subvm] guest env whitelist=${GuestEnvironment.curated().keys} — a naive full-inherit " +
                "would have exposed ${blocked.size} secret-shaped var(s) (${blocked.take(5)}${if (blocked.size > 5) "…" else ""}) " +
                "and ${deferred.size} other unreviewed var(s); none of it reaches the guest."
        )
    }

    private fun guard(command: String) {
        if (command.substringAfterLast('/') !in capability.allowedCommands) throw SecurityException("command '$command' not in allowedCommands")
    }

    private suspend fun forwardTerminal(msg: Teleported.Obj) {
        val bytes = runCatching { Base64.getDecoder().decode(msg.str("data") ?: "") }.getOrNull() ?: return
        val stream = if (msg.str("stream") == "stderr") terminalError else terminalOutput
        processHostCall { stream?.write(bytes); stream?.flush() }
    }

    private suspend fun answerHostCall(msg: Teleported.Obj, channel: ProcessProtocolChannel) {
        hostCalls.incrementAndGet()
        val name = msg.str("name") ?: ""
        val args = (msg.field("args") as? Teleported.Arr)?.v ?: emptyList()
        val delegate = delegates[name]
        if (delegate == null) {
            channel.send(SubVmProtocol.encode(Teleported.obj("id" to msg.int("id"), "error" to "no host delegate named '$name'")))
            return
        }
        val deadline = delegate.timeoutMillis ?: pythonWorker?.timeoutMillis ?: if (restrictedJsLeased) budget.wallMillis else null
        val admitted = hostDispatch.trySubmit(msg.int("id") ?: -1, name, deadline) {
            processHostCall {
                if (!restrictedJsLeased) delegate.function(args)
                else {
                    restrictedHostCall.set(true)
                    try { delegate.function(args) } finally { restrictedHostCall.remove() }
                }
            }
        }
        if (admitted == null) {
            channel.send(SubVmProtocol.encode(Teleported.obj("id" to msg.int("id"), "error" to
                "host delegate '$name' was not admitted: host capacity exhausted or draining")))
        }
    }

    private suspend fun sendFrame(m: Teleported.Obj) = transport.send(SubVmProtocol.encode(m))
    private fun send(m: Teleported.Obj) = runBlocking { sendFrame(m) }

    /** Leased JS rejects concurrent crossings instead of waiting behind a host callback. */
    private inline fun <T> crossingLock(block: () -> T): T {
        if (!restrictedJsLeased) return lock.withLock(block)
        check(lock.tryLock()) { "Restricted JS isolate is busy" }
        try { return block() } finally { lock.unlock() }
    }

    private fun roundTrip(vararg fields: Pair<String, Any?>): Teleported {
        check(!restrictedHostCall.get()) { "A restricted JS host callback cannot re-enter its isolate" }
        return crossingLock {
            if (!accepting) throw GuestException(GuestFailure.DEAD, "isolate $id is draining")
            exchange(*fields)
        }
    }

    private fun exchange(vararg fields: Pair<String, Any?>): Teleported {
        if (!alive) throw GuestException(GuestFailure.DEAD, "child of isolate $id is gone")
        val rid = ids.incrementAndGet()
        // For the restricted leased-JS lane, a single absolute outer deadline
        // bounds all replies for this crossing, including repeated host calls.
        // A callback which ignores interruption can outlive revocation; close()
        // still joins admitted host work and is not promised to finish on time.
        val deadline = if (restrictedJsLeased) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(replyTimeoutMillis) else null
        val request = Teleported.obj("id" to rid, *fields)
        if (deadline == null) send(request)
        else {
            val sent = try {
                val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
                runBlocking { withTimeoutOrNull(remaining) { sendFrame(request); true } } == true
            } catch (failure: Throwable) {
                val interrupted = failure is InterruptedException || Thread.currentThread().isInterrupted
                if (interrupted) Thread.interrupted() // cleanup uses runBlocking; restore after it finishes
                try { stopRestrictedJs() } finally { if (interrupted) Thread.currentThread().interrupt() }
                throw GuestException(GuestFailure.DEAD, "restricted JS transport failed", failure)
            }
            if (!sent) {
                stopRestrictedJs()
                throw GuestException(GuestFailure.DEAD, "restricted JS send deadline expired")
            }
        }
        var reply: Teleported.Obj
        do {
            val timeout = pythonWorker?.timeoutMillis ?: replyTimeoutMillis
            val received = try {
                if (deadline != null) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) null else inbox.poll(remaining, TimeUnit.NANOSECONDS)
                } else inbox.poll(timeout, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                if (restrictedJsLeased) {
                    // poll clears the interrupt flag. Do not restore it until our
                    // non-cancellable revocation and admitted-effect drain finish.
                    try { stopRestrictedJs() } finally { Thread.currentThread().interrupt() }
                }
                throw interrupted
            }
            reply = received ?: run {
                if (restrictedJsLeased) stopRestrictedJs() else if (pythonWorker != null) revokeWorker()
                throw GuestException(if (!alive) GuestFailure.DEAD else GuestFailure.INTERRUPTED,
                    if (!alive) "child died or its deadline expired" else "no reply in ${timeout}ms")
            }
            if (reply.str("op") == "dead") {
                if (restrictedJsLeased) stopRestrictedJs()
                throw GuestException(GuestFailure.DEAD, "child of isolate $id is gone")
            }
        } while (reply.int("id") != rid)
        if (reply.bool("ok") == true) return reply.field("value") ?: Teleported.Null
        val kind = runCatching { GuestFailure.valueOf(reply.str("kind") ?: "") }.getOrDefault(GuestFailure.GUEST_ERROR)
        // A guest error may race the completion/cleanup of an admitted host call.
        // The restricted leased lane therefore fails closed and drains it before
        // reporting any failed crossing; the child is not offered for reuse.
        if (restrictedJsLeased) stopRestrictedJs()
        if (kind == GuestFailure.EXHAUSTED) alive = false
        throw GuestException(kind, reply.str("error") ?: "guest error")
    }

    override fun eval(source: String, name: String): Teleported { evals.incrementAndGet(); return roundTrip("op" to "eval", "source" to source, "name" to name) }

    override fun call(root: String, vararg args: Teleported): Teleported { calls.incrementAndGet(); return roundTrip("op" to "call", "root" to root, "args" to Teleported.Arr(args.toList())) }

    /** Register on both sides: the parent keeps the function, the child installs a bridge that asks for it. */
    override fun delegate(name: String, fn: (List<Teleported>) -> Teleported) {
        check(!restrictedHostCall.get()) { "A restricted JS host callback cannot re-enter its isolate" }
        if (!restrictedJsLeaseCapable) {
            delegates[name] = HostDelegate(null, fn)
            roundTrip("op" to "delegate", "name" to name)
        } else crossingLock {
            require(!restrictedJsLeased || delegates[name]?.timeoutMillis == null) { "Cannot replace a bounded JS delegate with an unbounded delegate" }
            val previous = delegates.put(name, HostDelegate(null, fn))
            try { exchange("op" to "delegate", "name" to name) }
            catch (failure: Throwable) {
                if (previous == null) delegates.remove(name) else delegates[name] = previous
                throw failure
            }
        }
    }

    override fun delegate(name: String, hostWallMillis: Long, fn: (List<Teleported>) -> Teleported) {
        check(!restrictedHostCall.get()) { "A restricted JS host callback cannot re-enter its isolate" }
        if (pythonWorker != null) {
            require(hostWallMillis > 0 && hostWallMillis <= pythonWorker.timeoutMillis)
            delegates[name] = HostDelegate(hostWallMillis, fn)
            roundTrip("op" to "delegate", "name" to name, "timeout" to hostWallMillis)
            return
        }
        require(restrictedJsLeaseCapable) { "bounded JS delegation requires a restricted JS child with a finite wall-only budget and outer deadline" }
        require(hostWallMillis > 0 && hostWallMillis <= replyTimeoutMillis - budget.wallMillis - RESTRICTED_JS_LEASE_MARGIN_MILLIS) {
            "host lease exceeds the restricted JS crossing deadline"
        }
        crossingLock {
            check(accepting && isAlive) { "isolate $id is unavailable" }
            val previous = delegates.put(name, HostDelegate(hostWallMillis, fn))
            try {
                // The registration itself also needs the finite send/reply deadline.
                restrictedJsLeased = true
                exchange("op" to "delegate", "name" to name, "timeout" to hostWallMillis)
            } catch (failure: Throwable) {
                if (previous == null) delegates.remove(name) else delegates[name] = previous
                // A failure may leave the child partially registered. Never reuse it.
                runCatching { stopRestrictedJs() }
                throw failure
            }
        }
    }

    /** Stock Python has physical stdin; restricted children multiplex input beside protocol frames. */
    fun pushInput(text: String) {
        check(!restrictedHostCall.get()) { "A restricted JS host callback cannot re-enter its isolate" }
        if (restrictedJsLeased) {
            crossingLock {
                check(accepting && isAlive) { "isolate $id is draining" }
                val encoded = Base64.getEncoder().encodeToString(text.encodeToByteArray())
                send(Teleported.obj("op" to "stdin", "data" to encoded))
            }
            return
        }
        check(accepting && isAlive) { "isolate $id is draining" }
        if (pythonWorker != null) {
            runBlocking { transport.writeInput(text.encodeToByteArray()) }
            return
        }
        val encoded = Base64.getEncoder().encodeToString(text.encodeToByteArray())
        send(Teleported.obj("op" to "stdin", "data" to encoded))
    }

    /** Interrupt politely through the protocol; if the child does not answer, the wall does its job: kill it. */
    override fun interrupt(): Boolean {
        check(!restrictedHostCall.get()) { "A restricted JS host callback cannot interrupt its isolate" }
        if (restrictedJsLeased) return crossingLock { interruptLeasedJs() }
        if (!alive) return false
        interrupted.incrementAndGet()
        runCatching { send(Teleported.obj("id" to ids.incrementAndGet(), "op" to "interrupt")) }
        inbox.poll(InProcessIsolate.INTERRUPT_GRACE_MS * 2, TimeUnit.MILLISECONDS)
        // Process-tier interrupt is revocation, not a reusable context reset: the wall must fall.
        revoked = true
        runBlocking { transport.revoke(0) }
        alive = false
        accepting = false
        return true
    }

    /** Leased JS rejects concurrent interrupt callers so they cannot steal crossing replies. */
    private fun interruptLeasedJs(): Boolean {
        if (!alive) return false
        interrupted.incrementAndGet()
        stopRestrictedJs()
        return true
    }

    override fun stats() = IsolateStats(evals.get(), calls.get(), hostCalls.get(), 0, 0, 0, 0, interrupted.get())
    override val isAlive: Boolean get() = alive && transport.isAlive

    override fun close() {
        check(!restrictedHostCall.get()) { "A restricted JS host callback cannot close its isolate" }
        if (restrictedJsLeased) return crossingLock { closeOwned() }
        closeOwned()
    }

    private fun closeOwned() {
        try {
            accepting = false
            // Release terminal reads without closing the protocol needed by admitted host calls.
            if (!revoked) {
                if (pythonWorker != null) runBlocking { transport.closeInput() }
                else if (nodeLauncher == null && alive) send(Teleported.obj("op" to "stdin-close"))
                lock.withLock {
                    try { if (alive && transport.isAlive) exchange("op" to "close") }
                    finally { runBlocking { transport.closeProtocolInput() } }
                }
            }
            // Close acknowledgement follows admitted operations and context cleanup, never a kill timer.
            runBlocking { if (revoked) runCatching { transport.drain() } else transport.drain() }
        } finally {
            accepting = false
            if (transport.isAlive) revokeWorker() // A failed transport is revocation, not a successful drain.
            alive = false
            try { runBlocking { hostDispatch.drain() } }
            finally {
                runBlocking { if (revoked) runCatching { transport.drain() } else transport.drain() }
            }
        }
    }

    private fun revokeWorker() {
        accepting = false
        revoked = true
        runBlocking { transport.revoke(InProcessIsolate.INTERRUPT_GRACE_MS) }
        alive = false
    }

    /** Terminal restricted-JS failure: no success/failure is reported before admitted effects drain. */
    private fun stopRestrictedJs() {
        try { if (!revoked) revokeWorker() }
        finally { runBlocking { hostDispatch.drain() } }
    }

    /** The Node.js side of the protocol, for the GraalJS `node` launcher. Same envelope (canonical JSON is JSON). */
    object NodeSide {
        val SCRIPT: String = """
            const rl = require('readline').createInterface({ input: process.stdin });
            const canon = (v) => { if (v === null || v === undefined) return null; if (typeof v === 'function') return {'${'$'}opaque': 'fn'};
              if (typeof v === 'number' && !Number.isFinite(v)) return {'${'$'}opaque': 'number:' + String(v)};
              if (Array.isArray(v)) return v.map(canon); if (typeof v === 'object') { const o = {}; for (const k of Object.keys(v).sort()) o[k] = canon(v[k]); return o; } return v; };
            const wireOut = process.stdout.write.bind(process.stdout);
            const send = (m) => wireOut(JSON.stringify(m) + '\n');
            const term = (stream, chunk) => { const b = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)); send({op:'terminal', stream, data:b.toString('base64')}); return true; };
            process.stdout.write = (chunk) => term('stdout', chunk);
            process.stderr.write = (chunk) => term('stderr', chunk);
            console.log = (...a) => term('stdout', a.join(' ') + '\n');
            console.error = (...a) => term('stderr', a.join(' ') + '\n');
            const pending = new Map(); let hostSeq = 0;
            globalThis.host = { call: (name, ...args) => { throw new Error('host.call is asynchronous under node; use hostAsync(name, ...args)'); } };
            globalThis.hostAsync = (name, ...args) => new Promise((res, rej) => { const id = ++hostSeq; pending.set(id, {res, rej}); send({op:'host', id, name, args: args.map(canon)}); });
            rl.on('line', async (line) => { let m; try { m = JSON.parse(line); } catch { return; }
              if (m.op === undefined && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(m.error)) : p.res(m.value); return; }
              try {
                if (m.op === 'eval') { const v = (0, eval)(m.source); send({id: m.id, ok: true, value: canon(await v)}); }
                else if (m.op === 'call') { const f = globalThis[m.root]; if (typeof f !== 'function') throw new Error('no guest root ' + m.root); send({id: m.id, ok: true, value: canon(await f(...(m.args || [])))}); }
                else if (m.op === 'delegate') send({id: m.id, ok: true, value: null});
                else if (m.op === 'stats') send({id: m.id, ok: true, value: canon({node: process.version})});
                else if (m.op === 'interrupt') send({id: m.id, ok: true, value: null});
                else if (m.op === 'close') { send({id: m.id, ok: true, value: null}); process.exit(0); }
              } catch (e) { send({id: m.id, ok: false, kind: 'GUEST_ERROR', error: String(e && e.message || e)}); }
            });
        """.trimIndent()
    }
}

/** Child-JVM entry point: one [InProcessIsolate] speaking [SubVmProtocol] on stdin/stdout. */
object SubVmMain {
    private const val MAX_CHILD_HOST_LEASE_MILLIS = 3_600_000L
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val languageId = args.getOrElse(0) { "js" }
        val id = args.getOrElse(1) { "child" }
        val budget = Budget(statements = args.getOrNull(2)?.toLongOrNull() ?: GuestBounds.DEFAULT_STATEMENT_LIMIT, wallMillis = args.getOrNull(3)?.toLongOrNull() ?: GuestBounds.DEFAULT_WALL_MILLIS)
        val bounds = GuestBounds.ofLanguage(languageId)
        val guestInput = TerminalInputStream()
        val hostReplies = ConcurrentHashMap<Int, LinkedBlockingQueue<Teleported.Obj>>()
        val hostSeq = AtomicInteger()
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val handler = CompletableDeferred<suspend (String) -> Unit>()
        // Protocol progress is independent of the guest main thread, including during construction.
        val frames = ProcessFrameChannel(coroutineContext + Dispatchers.Default, JvmProcessProtocolStream.stdio(),
            onLine = { handler.await()(it) },
            onEnd = {
                connected.set(false)
                guestInput.close()
                for (reply in hostReplies.values) reply.offer(Teleported.obj("error" to "Process parent closed"))
            })
        suspend fun reply(message: Teleported.Obj) = frames.send(SubVmProtocol.encode(message))
        fun send(message: Teleported.Obj) = runBlocking { reply(message) }
        val iso = InProcessIsolate(
            id, bounds.facet, budget,
            input = guestInput,
            output = ProtocolTerminalOutputStream("stdout", ::send),
            error = ProtocolTerminalOutputStream("stderr", ::send),
        )
        try {
            // Only guest operations enter this main-thread composition.
            val operations = ProcessCalls(coroutineContext, concurrency = 1) { response ->
                reply(response.b.fold(
                    { Teleported.obj("id" to response.a, "ok" to true, "value" to it) },
                    { failure -> Teleported.obj("id" to response.a, "ok" to false,
                        "kind" to ((failure as? GuestException)?.kind ?: GuestFailure.GUEST_ERROR).name,
                        "error" to (failure.message ?: failure.toString())) },
                ))
            }
            val hostBridge: (String) -> ((List<Teleported>) -> Teleported) = { name ->
                { targs ->
                    val hid = hostSeq.incrementAndGet()
                    val replies = hostReplies.computeIfAbsent(hid) { LinkedBlockingQueue() }
                    try {
                        check(connected.get()) { "Process parent closed" }
                        send(Teleported.obj("op" to "host", "id" to hid, "name" to name, "args" to Teleported.Arr(targs)))
                        val response = replies.take()
                        response.str("error")?.let { error(it) }
                        response.field("value") ?: Teleported.Null
                    } finally { hostReplies.remove(hid) }
                }
            }

            suspend fun asyncReply(message: Teleported.Obj, action: () -> Teleported) {
                if (operations.trySubmit(message.int("id") ?: -1, message.str("op") ?: "", null) { action() } == null) {
                    reply(Teleported.obj("id" to message.int("id"), "ok" to false, "kind" to "GUEST_ERROR",
                        "error" to "guest operation was not admitted: capacity exhausted or draining"))
                }
            }

            try {
                handler.complete(dispatch@{ line ->
                    if (line.isBlank()) return@dispatch
                    val message = runCatching { SubVmProtocol.decode(line) }.getOrNull() ?: return@dispatch
                    val op = message.str("op")
                    if (op == null) {
                        message.int("id")?.let { hostReplies[it]?.offer(message) }
                        return@dispatch
                    }
                    when (op) {
                        "stdin" -> runCatching { Base64.getDecoder().decode(message.str("data") ?: "") }.getOrNull()?.let(guestInput::push)
                        "stdin-close" -> guestInput.close()
                        "eval" -> asyncReply(message) { iso.eval(message.str("source") ?: "", message.str("name") ?: "<eval>") }
                        "call" -> asyncReply(message) {
                            val targs = (message.field("args") as? Teleported.Arr)?.v ?: emptyList()
                            iso.call(message.str("root") ?: "", *targs.toTypedArray())
                        }
                        "delegate" -> asyncReply(message) {
                            val name = message.str("name") ?: ""
                            val timeout = message.field("timeout")
                            if (timeout == null) iso.delegate(name, hostBridge(name))
                            else {
                                val millis = (timeout as? Teleported.Num)?.v
                                    ?.takeIf { it > 0 && it <= MAX_CHILD_HOST_LEASE_MILLIS }
                                    ?: error("invalid child host lease")
                                iso.delegate(name, millis, hostBridge(name))
                            }
                            Teleported.Null
                        }
                        "interrupt" -> {
                            val interrupted = processHostCall { iso.interrupt() }
                            reply(Teleported.obj("id" to message.int("id"), "ok" to true, "value" to interrupted))
                        }
                        "stats" -> reply(Teleported.obj("id" to message.int("id"), "ok" to true, "value" to iso.stats().toString()))
                        "close" -> {
                            guestInput.close()
                            if (operations.finish(message.int("id") ?: -1, "close") { iso.close(); Teleported.Null } == null)
                                reply(Teleported.obj("id" to message.int("id"), "ok" to false, "kind" to "DEAD", "error" to "guest is draining"))
                        }
                        else -> reply(Teleported.obj("id" to message.int("id"), "ok" to false, "kind" to "GUEST_ERROR", "error" to "unknown op $op"))
                    }
                })
                // Parent EOF follows the close acknowledgement, so output remains live through guest drain.
                frames.awaitClosed()
            } finally { operations.drain() }
        } finally { iso.close() }
    }
}

private class ProtocolTerminalOutputStream(
    private val stream: String,
    private val send: (Teleported.Obj) -> Unit,
) : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val encoded = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, offset + length))
        send(Teleported.obj("op" to "terminal", "stream" to stream, "data" to encoded))
    }
}
