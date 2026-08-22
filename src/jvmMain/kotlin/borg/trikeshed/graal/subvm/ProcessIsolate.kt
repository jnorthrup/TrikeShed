package borg.trikeshed.graal.subvm

import borg.trikeshed.graal.subvm.Teleported.Companion.bool
import borg.trikeshed.graal.subvm.Teleported.Companion.field
import borg.trikeshed.graal.subvm.Teleported.Companion.int
import borg.trikeshed.graal.subvm.Teleported.Companion.obj
import borg.trikeshed.graal.subvm.Teleported.Companion.str
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.process.ProcessCapability
import borg.trikeshed.userspace.nio.process.SecurityException
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Line protocol between a [ProcessIsolate] and its child. One envelope per line, both directions,
 * and the envelope IS a [Teleported.Obj] in canonical form — one encoder, one exact parser, no
 * second JSON library on the wire (a generic parser collapses Num/Real and mangles escapes).
 *
 *   parent → child : {"id":n,"op":"eval","source":..,"name":..} | {"id":n,"op":"call","root":..,"args":[T..]}
 *                    | {"id":n,"op":"delegate","name":..} | {"id":n,"op":"interrupt"} | {"id":n,"op":"stats"} | {"id":n,"op":"close"}
 *                    | {"id":m,"value":T}  /  {"id":m,"error":".."}                          (reply to a host call)
 *   child → parent : {"id":n,"ok":true,"value":T} | {"id":n,"ok":false,"kind":"GUEST_ERROR","error":".."}
 *                    | {"op":"host","id":m,"name":..,"args":[T..]}                          (guest → host delegation)
 *
 * Strictly request/response on the parent side; while a host call is outstanding the parent
 * answers it before sending anything else, so one reader thread and one lock are the whole story.
 */
object SubVmProtocol {
    fun encode(envelope: Teleported.Obj): String = envelope.canonical()
    fun decode(line: String): Teleported.Obj = Teleported.parseCanonical(line) as? Teleported.Obj
        ?: throw IllegalArgumentException("envelope must be an object: $line")

    /** Lossy adapter for foreign speakers that send generic JSON; a canonical STRING is parsed exactly. */
    fun teleportOf(v: Any?): Teleported = when (v) {
        null -> Teleported.Null
        is Teleported -> v
        is String -> runCatching { Teleported.parseCanonical(v) }.getOrElse { Teleported.Str(v) }
        is Boolean -> Teleported.Bool(v)
        is Number -> if (v is Double || v is Float) Teleported.Real(v.toDouble()) else Teleported.Num(v.toLong())
        is List<*> -> Teleported.Arr(v.map { teleportOf(it) })
        is Map<*, *> -> Teleported.Obj(v.entries.associate { it.key.toString() to teleportOf(it.value) })
        else -> Teleported.Opaque(v.toString())
    }

    /** The canonical string form — what [teleportOf] parses back exactly. */
    fun jsonOf(t: Teleported): String = t.canonical()
}

/**
 * A guest behind a process wall. The child is either a JVM running [SubVmMain] (the same
 * [InProcessIsolate] bounds apply inside it) or — for JS with `nodeLauncher` — the GraalJS `node`
 * launcher, which is the only way to get Node.js APIs (`require`, `process`, the event loop): the
 * in-process JS engine deliberately does not have them.
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
) : GuestIsolate {
    override val trust = Trust.UNTRUSTED
    override val bounds: FacetBounds = GuestBounds.of(facet)

    private val lock = ReentrantLock()
    private val process: Process
    private val out: BufferedWriter
    private val inbox = LinkedBlockingQueue<Teleported.Obj>()
    private val delegates = java.util.concurrent.ConcurrentHashMap<String, (List<Teleported>) -> Teleported>()
    private val ids = AtomicInteger()
    private val evals = AtomicLong(); private val calls = AtomicLong(); private val hostCalls = AtomicLong(); private val interrupted = AtomicLong()
    @Volatile private var alive = true

    init {
        val cmd: List<String> = if (nodeLauncher != null) {
            require(facet == VmFacet.GRAAL_JS) { "nodeLauncher only serves JS" }
            guard(nodeLauncher.path)
            listOf(nodeLauncher.path, "-e", NodeSide.SCRIPT)
        } else {
            val java = File(System.getProperty("java.home"), "bin/java").path
            guard(java)
            listOf(java, "-Xss4m", "-cp", System.getProperty("java.class.path"), SubVmMain::class.java.name, bounds.languageId, id, budget.statements.toString(), budget.wallMillis.toString())
        }
        process = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        out = process.outputStream.bufferedWriter()
        Thread({ pump(process.inputStream.bufferedReader()) }, "subvm-reader-$id").apply { isDaemon = true }.start()
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
                if (msg.str("op") == "host") answerHostCall(msg) else inbox.put(msg)
            }
        } finally { alive = false }
    }

    private fun answerHostCall(msg: Teleported.Obj) {
        hostCalls.incrementAndGet()
        val name = msg.str("name") ?: ""
        val args = (msg.field("args") as? Teleported.Arr)?.v ?: emptyList()
        val reply = try {
            val fn = delegates[name] ?: error("no host delegate named '$name'")
            obj("id" to msg.int("id"), "value" to fn(args))
        } catch (t: Throwable) { obj("id" to msg.int("id"), "error" to (t.message ?: t.toString())) }
        send(reply)
    }

    @Synchronized private fun send(m: Teleported.Obj) { out.write(SubVmProtocol.encode(m)); out.newLine(); out.flush() }

    private fun roundTrip(vararg fields: Pair<String, Any?>): Teleported = lock.withLock {
        if (!alive) throw GuestException(GuestFailure.DEAD, "child of isolate $id is gone")
        val rid = ids.incrementAndGet()
        send(obj("id" to rid, *fields))
        var reply: Teleported.Obj
        do {
            reply = inbox.poll(replyTimeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw GuestException(if (!alive) GuestFailure.DEAD else GuestFailure.INTERRUPTED, if (!alive) "child died" else "no reply in ${replyTimeoutMillis}ms")
        } while (reply.int("id") != rid)
        if (reply.bool("ok") == true) return@withLock reply.field("value") ?: Teleported.Null
        val kind = runCatching { GuestFailure.valueOf(reply.str("kind") ?: "") }.getOrDefault(GuestFailure.GUEST_ERROR)
        if (kind == GuestFailure.EXHAUSTED) alive = false
        throw GuestException(kind, reply.str("error") ?: "guest error")
    }

    override fun eval(source: String, name: String): Teleported { evals.incrementAndGet(); return roundTrip("op" to "eval", "source" to source, "name" to name) }

    override fun call(root: String, vararg args: Teleported): Teleported { calls.incrementAndGet(); return roundTrip("op" to "call", "root" to root, "args" to Teleported.Arr(args.toList())) }

    /** Register on both sides: the parent keeps the function, the child installs a bridge that asks for it. */
    override fun delegate(name: String, fn: (List<Teleported>) -> Teleported) {
        delegates[name] = fn
        roundTrip("op" to "delegate", "name" to name)
    }

    /** Interrupt politely through the protocol; if the child does not answer, the wall does its job: kill it. */
    override fun interrupt(): Boolean {
        if (!alive) return false
        interrupted.incrementAndGet()
        runCatching { send(obj("id" to ids.incrementAndGet(), "op" to "interrupt")) }
        val answered = inbox.poll(InProcessIsolate.INTERRUPT_GRACE_MS * 2, TimeUnit.MILLISECONDS) != null
        if (!answered) { process.destroyForcibly(); alive = false }
        return true
    }

    override fun stats() = IsolateStats(evals.get(), calls.get(), hostCalls.get(), 0, 0, 0, 0, interrupted.get())
    override val isAlive: Boolean get() = alive && process.isAlive

    override fun close() {
        runCatching { send(obj("id" to ids.incrementAndGet(), "op" to "close")) }
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        alive = false
    }

    /** The Node.js side of the protocol, for the GraalJS `node` launcher. Same envelope (canonical JSON is JSON). */
    object NodeSide {
        val SCRIPT: String = """
            const rl = require('readline').createInterface({ input: process.stdin });
            const canon = (v) => { if (v === null || v === undefined) return null; if (typeof v === 'function') return {'${'$'}opaque': 'fn'};
              if (Array.isArray(v)) return v.map(canon); if (typeof v === 'object') { const o = {}; for (const k of Object.keys(v).sort()) o[k] = canon(v[k]); return o; } return v; };
            const send = (m) => process.stdout.write(JSON.stringify(m) + '\n');
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
    fun main(args: Array<String>) {
        val languageId = args.getOrElse(0) { "js" }
        val id = args.getOrElse(1) { "child" }
        val budget = Budget(statements = args.getOrNull(2)?.toLongOrNull() ?: GuestBounds.DEFAULT_STATEMENT_LIMIT, wallMillis = args.getOrNull(3)?.toLongOrNull() ?: GuestBounds.DEFAULT_WALL_MILLIS)
        val bounds = GuestBounds.ofLanguage(languageId)
        val out = System.out.bufferedWriter()
        val stdin = System.`in`.bufferedReader()
        fun send(m: Teleported.Obj) { synchronized(out) { out.write(SubVmProtocol.encode(m)); out.newLine(); out.flush() } }
        val iso = InProcessIsolate(id, bounds.facet, budget)
        // guest → host across the wall: ask the parent and block on its reply line
        var hostSeq = 0
        val hostBridge: (String) -> ((List<Teleported>) -> Teleported) = { name ->
            { targs ->
                val hid = ++hostSeq
                send(obj("op" to "host", "id" to hid, "name" to name, "args" to Teleported.Arr(targs)))
                val line = stdin.readLine() ?: error("parent closed")
                val reply = SubVmProtocol.decode(line)
                reply.str("error")?.let { error(it) }
                reply.field("value") ?: Teleported.Null
            }
        }
        while (true) {
            val line = stdin.readLine() ?: break
            if (line.isBlank()) continue
            val m = runCatching { SubVmProtocol.decode(line) }.getOrNull() ?: continue
            val rid = m.int("id")
            try {
                when (m.str("op")) {
                    "eval" -> send(obj("id" to rid, "ok" to true, "value" to iso.eval(m.str("source") ?: "", m.str("name") ?: "<eval>")))
                    "call" -> {
                        val targs = (m.field("args") as? Teleported.Arr)?.v ?: emptyList()
                        send(obj("id" to rid, "ok" to true, "value" to iso.call(m.str("root") ?: "", *targs.toTypedArray())))
                    }
                    "delegate" -> { val name = m.str("name") ?: ""; iso.delegate(name, hostBridge(name)); send(obj("id" to rid, "ok" to true, "value" to null)) }
                    "interrupt" -> send(obj("id" to rid, "ok" to true, "value" to iso.interrupt()))
                    "stats" -> send(obj("id" to rid, "ok" to true, "value" to iso.stats().toString()))
                    "close" -> { send(obj("id" to rid, "ok" to true, "value" to null)); iso.close(); return }
                    else -> send(obj("id" to rid, "ok" to false, "kind" to "GUEST_ERROR", "error" to "unknown op ${m.str("op")}"))
                }
            } catch (e: GuestException) {
                send(obj("id" to rid, "ok" to false, "kind" to e.kind.name, "error" to (e.message ?: e.kind.name)))
            } catch (t: Throwable) {
                send(obj("id" to rid, "ok" to false, "kind" to "GUEST_ERROR", "error" to (t.message ?: t.toString())))
            }
        }
    }
}
