package borg.trikeshed.vm

import borg.trikeshed.platform.discontinued
import borg.trikeshed.pointcut.VmFacet

/**
 * A line-oriented pipe to a child process. The only per-target piece of the process tier; the
 * protocol and the host-side state machine are common. A target without interactive process pipes
 * binds the bare interface (everything discontinued) and the tier is reported dead.
 */
interface ProcessPipe : AutoCloseable {
    fun writeLine(line: String): Unit = discontinued("vm.pipe.write")
    /** Blocks for the next line; null at EOF. */
    fun readLine(): String? = discontinued("vm.pipe.read")
    fun kill(): Unit = discontinued("vm.pipe.kill")
    val isAlive: Boolean get() = false
    override fun close() { runCatching { kill() } }
}

/**
 * Host half of [SubVmProtocol] over a [ProcessPipe]: strictly request/response; while a guest→host
 * call is outstanding the host answers it before sending anything else, so one reader and no
 * threads are the whole story. The child may be a JVM running a whole Graal DAG, a node launcher,
 * or a native guest — the host does not know which wall it is behind.
 */
class ProcessIsolateHost(
    override val id: String,
    override val facet: VmFacet,
    private val pipe: ProcessPipe,
) : VmHandle {
    override val tier: String get() = "process"
    private val delegates = LinkedHashMap<String, (List<Teleported>) -> Teleported>()
    private var nextId = 0
    private var evals = 0L; private var calls = 0L; private var hostCalls = 0L; private var interrupted = 0L
    private var closed = false

    override val isAlive: Boolean get() = !closed && pipe.isAlive

    fun delegate(name: String, fn: (List<Teleported>) -> Teleported) { delegates[name] = fn }

    override fun eval(source: String, name: String): Teleported {
        evals++
        return roundTrip(Teleported.obj("op" to "eval", "source" to source, "name" to name))
    }

    override fun call(root: String, vararg args: Teleported): Teleported {
        calls++
        return roundTrip(Teleported.obj("op" to "call", "root" to root, "args" to args.toList()))
    }

    fun interrupt(): Boolean {
        interrupted++
        return runCatching { roundTrip(Teleported.obj("op" to "interrupt")); true }.getOrDefault(false)
    }

    override fun stats(): VmStats = VmStats(evals = evals, calls = calls, hostCalls = hostCalls, interrupted = interrupted)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { pipe.writeLine(SubVmProtocol.encode(Teleported.obj("id" to nextId++, "op" to "close"))) }
        pipe.close()
    }

    private fun roundTrip(request: Teleported.Obj): Teleported {
        check(!closed) { "isolate '$id' is closed" }
        val reqId = nextId++
        pipe.writeLine(SubVmProtocol.encode(Teleported.Obj(request.v + ("id" to Teleported.Num(reqId.toLong())))))
        while (true) {
            val line = pipe.readLine() ?: throw IllegalStateException("isolate '$id' died (EOF)")
            if (line.isBlank()) continue
            val msg = runCatching { SubVmProtocol.decode(line) }.getOrNull() ?: continue
            if (msg.str("op") == "host") { answerHostCall(msg); continue }
            if (msg.int("id") != reqId) continue
            return if (msg.bool("ok") == true) msg.field("value") ?: Teleported.Null
            else throw VmGuestException(msg.str("kind") ?: "GUEST_ERROR", msg.str("error") ?: "guest error")
        }
    }

    private fun answerHostCall(msg: Teleported.Obj) {
        hostCalls++
        val callId = msg.int("id") ?: -1
        val name = msg.str("name") ?: ""
        val args = (msg.field("args") as? Teleported.Arr)?.v ?: emptyList()
        val reply = try {
            val fn = delegates[name] ?: throw IllegalArgumentException("no delegate '$name'")
            Teleported.obj("id" to callId, "value" to fn(args))
        } catch (e: Throwable) {
            Teleported.obj("id" to callId, "error" to (e.message ?: e.toString()))
        }
        pipe.writeLine(SubVmProtocol.encode(reply))
    }
}

class VmGuestException(val kind: String, message: String) : RuntimeException("$kind: $message")
