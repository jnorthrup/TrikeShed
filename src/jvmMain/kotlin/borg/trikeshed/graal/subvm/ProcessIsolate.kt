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
import borg.trikeshed.userspace.nio.channels.spi.processHostCall
import borg.trikeshed.terminal.TerminalInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.net.Socket
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

    private val lock = ReentrantLock()
    private val process: Process
    private val protocol: Socket?
    private val out: BufferedWriter
    private val inbox = LinkedBlockingQueue<Teleported.Obj>()
    private class HostDelegate(val timeoutMillis: Long?, val function: (List<Teleported>) -> Teleported)
    private val delegates = ConcurrentHashMap<String, HostDelegate>()
    private val hostDispatch: ProcessCalls
    private val reader: Thread
    private val errors: Thread?
    private val terminalReader: Thread?
    private val ids = AtomicInteger()
    private val evals = AtomicLong(); private val calls = AtomicLong(); private val hostCalls = AtomicLong(); private val interrupted = AtomicLong()
    @Volatile private var alive = true
    @Volatile private var accepting = true
    val processId: Long get() = process.pid()

    private companion object {
        val surveyed = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    init {
        require(pythonWorker == null || facet == VmFacet.GRAAL_PYTHON && nodeLauncher == null) {
            "PythonWorker requires the Python facet and its own child JVM"
        }
        val transport = pythonWorker?.let { PythonWorkerTransport(it.timeoutMillis) }
        val cmd: List<String> = if (nodeLauncher != null) {
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
                requireNotNull(transport).port.toString(), transport.token)
        }
        val builder = ProcessBuilder(cmd)
        // A bare ProcessBuilder's environment is a COPY of this daemon's own — every provider key
        // KeyMux holds, TRIKESHED_EGRESS_ALLOWLIST, whatever else lives in the shell — handed to an
        // UNTRUSTED child by default. Replace it with the same curated whitelist InProcessIsolate
        // uses; the child's `java`/`node` launch itself needs nothing from the host env to run.
        builder.environment().apply {
            clear(); putAll(GuestEnvironment.curated())
            pythonWorker?.environment?.let(::putAll)
        }
        pythonWorker?.workingDirectory?.let { builder.directory(File(it)) }
        deliberateHostEnvironmentOnce()
        if (terminalError == null) builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        if (pythonWorker != null && terminalOutput != null && terminalOutput === terminalError)
            builder.redirectErrorStream(true)
        process = try { builder.start() } catch (failure: Throwable) { transport?.close(); throw failure }
        protocol = try { transport?.accept(process) }
        catch (failure: Throwable) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            process.waitFor()
            throw failure
        }
        finally { transport?.close() }
        out = (protocol?.getOutputStream() ?: process.outputStream).bufferedWriter()
        hostDispatch = ProcessCalls(Dispatchers.Default, concurrency = if (pythonWorker == null) 1 else 4) { response ->
            processHostCall {
                val message = response.b.fold(
                    { Teleported.obj("id" to response.a, "value" to it) },
                    { failure -> Teleported.obj("id" to response.a, "error" to
                        if (failure is ProcessCallTimeout) "host delegate '${failure.operation}' exceeded ${failure.timeoutMillis}ms"
                        else (failure.message ?: failure.toString())) },
                )
                try { send(message) }
                catch (failure: Throwable) { if (alive && process.isAlive) throw failure }
            }
        }
        reader = Thread({ pump((protocol?.getInputStream() ?: process.inputStream).bufferedReader()) }, "subvm-reader-$id").apply { isDaemon = true; start() }
        terminalReader = if (pythonWorker == null) null else Thread({
            runCatching { process.inputStream.use { input -> input.copyTo(terminalOutput ?: OutputStream.nullOutputStream()) } }
            terminalOutput?.flush()
        }, "subvm-stdout-$id").apply { isDaemon = true; start() }
        val childError = terminalError
        errors = if (childError != null && !builder.redirectErrorStream()) {
            Thread({
                runCatching {
                    process.errorStream.use { input -> input.copyTo(childError) }
                    childError.flush()
                }
            }, "subvm-stderr-$id").apply { isDaemon = true; start() }
        } else null
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

    private fun pump(reader: BufferedReader) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = runCatching { SubVmProtocol.decode(line) }.getOrNull() ?: continue
                when (msg.str("op")) {
                    "host" -> answerHostCall(msg)
                    "terminal" -> forwardTerminal(msg)
                    else -> inbox.put(msg)
                }
            }
        } finally {
            alive = false
            inbox.offer(Teleported.obj("op" to "dead"))
        }
    }

    private fun forwardTerminal(msg: Teleported.Obj) {
        val bytes = runCatching { Base64.getDecoder().decode(msg.str("data") ?: "") }.getOrNull() ?: return
        val stream = if (msg.str("stream") == "stderr") terminalError else terminalOutput
        stream?.write(bytes)
        stream?.flush()
    }

    private fun answerHostCall(msg: Teleported.Obj) {
        hostCalls.incrementAndGet()
        val name = msg.str("name") ?: ""
        val args = (msg.field("args") as? Teleported.Arr)?.v ?: emptyList()
        val delegate = delegates[name]
        if (delegate == null) {
            send(Teleported.obj("id" to msg.int("id"), "error" to "no host delegate named '$name'"))
            return
        }
        val admitted = hostDispatch.trySubmit(msg.int("id") ?: -1, name, delegate.timeoutMillis ?: pythonWorker?.timeoutMillis) {
            processHostCall { delegate.function(args) }
        }
        if (admitted == null) {
            send(Teleported.obj("id" to msg.int("id"), "error" to
                "host delegate '$name' was not admitted: host capacity exhausted or draining"))
        }
    }

    @Synchronized private fun send(m: Teleported.Obj) { out.write(SubVmProtocol.encode(m)); out.newLine(); out.flush() }

    private fun roundTrip(vararg fields: Pair<String, Any?>): Teleported = lock.withLock {
        if (!accepting) throw GuestException(GuestFailure.DEAD, "isolate $id is draining")
        exchange(*fields)
    }

    private fun exchange(vararg fields: Pair<String, Any?>): Teleported {
        if (!alive) throw GuestException(GuestFailure.DEAD, "child of isolate $id is gone")
        val rid = ids.incrementAndGet()
        send(Teleported.obj("id" to rid, *fields))
        var reply: Teleported.Obj
        do {
            val timeout = pythonWorker?.timeoutMillis ?: replyTimeoutMillis
            reply = inbox.poll(timeout, TimeUnit.MILLISECONDS) ?: run {
                if (pythonWorker != null) revokeWorker()
                throw GuestException(if (!alive) GuestFailure.DEAD else GuestFailure.INTERRUPTED,
                    if (!alive) "child died or its deadline expired" else "no reply in ${timeout}ms")
            }
            if (reply.str("op") == "dead") throw GuestException(GuestFailure.DEAD, "child of isolate $id is gone")
        } while (reply.int("id") != rid)
        if (reply.bool("ok") == true) return reply.field("value") ?: Teleported.Null
        val kind = runCatching { GuestFailure.valueOf(reply.str("kind") ?: "") }.getOrDefault(GuestFailure.GUEST_ERROR)
        if (kind == GuestFailure.EXHAUSTED) alive = false
        throw GuestException(kind, reply.str("error") ?: "guest error")
    }

    override fun eval(source: String, name: String): Teleported { evals.incrementAndGet(); return roundTrip("op" to "eval", "source" to source, "name" to name) }

    override fun call(root: String, vararg args: Teleported): Teleported { calls.incrementAndGet(); return roundTrip("op" to "call", "root" to root, "args" to Teleported.Arr(args.toList())) }

    /** Register on both sides: the parent keeps the function, the child installs a bridge that asks for it. */
    override fun delegate(name: String, fn: (List<Teleported>) -> Teleported) {
        delegates[name] = HostDelegate(null, fn)
        roundTrip("op" to "delegate", "name" to name)
    }

    override fun delegate(name: String, hostWallMillis: Long, fn: (List<Teleported>) -> Teleported) {
        require(pythonWorker != null) { "bounded host delegation requires a Python worker" }
        require(hostWallMillis > 0 && hostWallMillis <= pythonWorker.timeoutMillis)
        delegates[name] = HostDelegate(hostWallMillis, fn)
        roundTrip("op" to "delegate", "name" to name, "timeout" to hostWallMillis)
    }

    /** Stock Python has physical stdin; restricted children multiplex input beside protocol frames. */
    fun pushInput(text: String) {
        check(accepting && isAlive) { "isolate $id is draining" }
        if (pythonWorker != null) {
            synchronized(process.outputStream) {
                process.outputStream.write(text.encodeToByteArray())
                process.outputStream.flush()
            }
            return
        }
        val encoded = Base64.getEncoder().encodeToString(text.encodeToByteArray())
        send(Teleported.obj("op" to "stdin", "data" to encoded))
    }

    /** Interrupt politely through the protocol; if the child does not answer, the wall does its job: kill it. */
    override fun interrupt(): Boolean {
        if (!alive) return false
        interrupted.incrementAndGet()
        runCatching { send(Teleported.obj("id" to ids.incrementAndGet(), "op" to "interrupt")) }
        inbox.poll(InProcessIsolate.INTERRUPT_GRACE_MS * 2, TimeUnit.MILLISECONDS)
        // Process-tier interrupt is revocation, not a reusable context reset: the wall must fall.
        process.destroyForcibly()
        process.waitFor()
        alive = false
        accepting = false
        return true
    }

    override fun stats() = IsolateStats(evals.get(), calls.get(), hostCalls.get(), 0, 0, 0, 0, interrupted.get())
    override val isAlive: Boolean get() = alive && process.isAlive

    override fun close() {
        try {
            accepting = false
            // Release terminal reads without closing the protocol needed by admitted host calls.
            if (pythonWorker != null) process.outputStream.close()
            else if (nodeLauncher == null && alive) send(Teleported.obj("op" to "stdin-close"))
            lock.withLock {
                try { if (alive && process.isAlive) exchange("op" to "close") }
                finally {
                    if (protocol != null && process.isAlive) {
                        out.flush()
                        if (!protocol.isOutputShutdown && !protocol.isClosed) protocol.shutdownOutput()
                    } else if (protocol == null) out.close()
                }
            }
            // Close acknowledgement follows admitted operations and context cleanup, never a kill timer.
            process.waitFor()
        } finally {
            accepting = false
            if (process.isAlive) revokeWorker() // A failed transport is revocation, not a successful drain.
            alive = false
            try { runBlocking { hostDispatch.drain() } }
            finally {
                protocol?.close()
                reader.join()
                terminalReader?.join()
                errors?.join()
                process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
            }
        }
    }

    private fun revokeWorker() {
        accepting = false
        process.destroy()
        if (!process.waitFor(InProcessIsolate.INTERRUPT_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        process.waitFor()
        alive = false
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
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val languageId = args.getOrElse(0) { "js" }
        val id = args.getOrElse(1) { "child" }
        val budget = Budget(statements = args.getOrNull(2)?.toLongOrNull() ?: GuestBounds.DEFAULT_STATEMENT_LIMIT, wallMillis = args.getOrNull(3)?.toLongOrNull() ?: GuestBounds.DEFAULT_WALL_MILLIS)
        val bounds = GuestBounds.ofLanguage(languageId)
        val out = System.out.bufferedWriter()
        val stdin = System.`in`.bufferedReader()
        fun send(m: Teleported.Obj) { synchronized(out) { out.write(SubVmProtocol.encode(m)); out.newLine(); out.flush() } }
        val guestInput = TerminalInputStream()
        val iso = InProcessIsolate(
            id, bounds.facet, budget,
            input = guestInput,
            output = ProtocolTerminalOutputStream("stdout", ::send),
            error = ProtocolTerminalOutputStream("stderr", ::send),
        )
        // The process main thread retains guest thread affinity; only pipe effects leave it.
        val operations = ProcessCalls(coroutineContext, concurrency = 1) { response ->
            processHostCall {
                send(response.b.fold(
                    { Teleported.obj("id" to response.a, "ok" to true, "value" to it) },
                    { failure -> Teleported.obj("id" to response.a, "ok" to false,
                        "kind" to ((failure as? GuestException)?.kind ?: GuestFailure.GUEST_ERROR).name,
                        "error" to (failure.message ?: failure.toString())) },
                ))
            }
        }
        val hostReplies = ConcurrentHashMap<Int, LinkedBlockingQueue<Teleported.Obj>>()
        val hostSeq = AtomicInteger()
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val hostBridge: (String) -> ((List<Teleported>) -> Teleported) = { name ->
            { targs ->
                val hid = hostSeq.incrementAndGet()
                val replies = hostReplies.computeIfAbsent(hid) { LinkedBlockingQueue() }
                try {
                    check(connected.get()) { "Process parent closed" }
                    send(Teleported.obj("op" to "host", "id" to hid, "name" to name, "args" to Teleported.Arr(targs)))
                    val reply = replies.take()
                    reply.str("error")?.let { error(it) }
                    reply.field("value") ?: Teleported.Null
                } finally { hostReplies.remove(hid) }
            }
        }

        fun asyncReply(m: Teleported.Obj, action: () -> Teleported) {
            if (operations.trySubmit(m.int("id") ?: -1, m.str("op") ?: "", null) { action() } == null) {
                send(Teleported.obj("id" to m.int("id"), "ok" to false, "kind" to "GUEST_ERROR",
                    "error" to "guest operation was not admitted: capacity exhausted or draining"))
            }
        }

        try {
            processHostCall {
                try {
                    while (true) {
                        val line = stdin.readLine() ?: break
                        if (line.isBlank()) continue
                        val m = runCatching { SubVmProtocol.decode(line) }.getOrNull() ?: continue
                        val op = m.str("op")
                        if (op == null) {
                            m.int("id")?.let { hostReplies[it]?.put(m) }
                            continue
                        }
                        when (op) {
                            "stdin" -> runCatching { Base64.getDecoder().decode(m.str("data") ?: "") }.getOrNull()?.let(guestInput::push)
                            "stdin-close" -> guestInput.close()
                            "eval" -> asyncReply(m) { iso.eval(m.str("source") ?: "", m.str("name") ?: "<eval>") }
                            "call" -> asyncReply(m) {
                                val targs = (m.field("args") as? Teleported.Arr)?.v ?: emptyList()
                                iso.call(m.str("root") ?: "", *targs.toTypedArray())
                            }
                            "delegate" -> asyncReply(m) {
                                val name = m.str("name") ?: ""
                                iso.delegate(name, hostBridge(name))
                                Teleported.Null
                            }
                            "interrupt" -> send(Teleported.obj("id" to m.int("id"), "ok" to true, "value" to iso.interrupt()))
                            "stats" -> send(Teleported.obj("id" to m.int("id"), "ok" to true, "value" to iso.stats().toString()))
                            "close" -> {
                                guestInput.close()
                                if (operations.finish(m.int("id") ?: -1, "close") { iso.close(); Teleported.Null } == null)
                                    send(Teleported.obj("id" to m.int("id"), "ok" to false, "kind" to "DEAD", "error" to "guest is draining"))
                            }
                            else -> send(Teleported.obj("id" to m.int("id"), "ok" to false, "kind" to "GUEST_ERROR", "error" to "unknown op $op"))
                        }
                    }
                } finally {
                    connected.set(false)
                    guestInput.close()
                    for (reply in hostReplies.values) reply.offer(Teleported.obj("error" to "Process parent closed"))
                }
            }
        } finally {
            try { operations.drain() }
            finally { iso.close() }
        }
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
