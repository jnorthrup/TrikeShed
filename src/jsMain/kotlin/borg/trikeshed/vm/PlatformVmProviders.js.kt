package borg.trikeshed.vm

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Two JS providers in one source set, because node and the browser cannot share implementations:
 * node has `vm.runInNewContext` (synchronous, with a wall timeout); the browser has `Worker`
 * (asynchronous, terminate-on-timeout). Only the sandbox differs; rows, events and the `Teleported`
 * ABI are the common code above.
 */
private fun isNode(): Boolean = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
private fun hasWorker(): Boolean = js("typeof Worker !== 'undefined' && typeof Blob !== 'undefined' && typeof URL !== 'undefined'") as Boolean

/** Shared bookkeeping for the two JS hosts; `spawn` is the only per-sandbox piece. */
abstract class JsVmHost(final override val platform: String, private val tierName: String) : VmHost {
    override val languages: Set<VmFacet> get() = setOf(VmFacet.GRAAL_JS)
    private val handles = LinkedHashMap<String, JsHandle>()
    private val specs = LinkedHashMap<String, VmSpec>()
    private val revoked = LinkedHashMap<String, String>()
    private var seq = 0L
    private val _events = MutableSharedFlow<VmEvent>(replay = 256, extraBufferCapacity = 1024)
    override val events: Flow<VmEvent> get() = _events

    protected abstract fun sandbox(spec: VmSpec): JsSandbox

    override fun spawn(spec: VmSpec): VmHandle {
        require(spec.id !in handles) { "isolate '${spec.id}' exists" }
        val h = JsHandle(spec, sandbox(spec))
        handles[spec.id] = h; specs[spec.id] = spec
        _events.tryEmit(VmEvent.Spawned(spec.id, ++seq, spec))
        return h
    }

    override fun get(id: String): VmHandle? = handles[id]
    override fun ids(): List<String> = handles.keys.sorted()
    override fun revoke(id: String, reason: String) {
        val h = handles[id] ?: return
        revoked[id] = reason; h.close()
        _events.tryEmit(VmEvent.Revoked(id, ++seq, reason))
    }

    override fun rows(): Cursor = ids().map { id ->
        val h = handles.getValue(id); val spec = specs.getValue(id)
        VmRow(id, spec.facet.id, spec.trust.name, tierName,
            phase = when { id in revoked -> "revoked"; h.isAlive -> "live"; else -> "dead" },
            statements = spec.budget.statements, wallMs = spec.budget.wallMillis,
            calls = 0, heat = h.evals, receipts = 0)
    }.asCursor()

    override fun close() { handles.values.forEach { it.close() } }

    inner class JsHandle(private val spec: VmSpec, private val box: JsSandbox) : VmHandle {
        override val id: String get() = spec.id
        override val facet: VmFacet get() = spec.facet
        override val tier: String get() = tierName
        var evals = 0L; private set
        private var closed = false
        override val isAlive: Boolean get() = !closed
        override fun eval(source: String, name: String): Teleported {
            check(!closed) { "isolate '$id' is closed" }
            evals++
            val out = box.eval(source, spec.budget.wallMillis)
            _events.tryEmit(VmEvent.Evaluated(id, ++seq, out.cid.hex, 0))
            return out
        }
        override fun stats(): VmStats = VmStats(evals = evals)
        override fun close() { if (!closed) { closed = true; box.close() } }
        // call(root, …) stays discontinued: a sandbox has no addressable roots — an honest dead path.
    }
}

/** The per-sandbox seam: evaluate source to a canonical string, within a wall budget (0 = none). */
interface JsSandbox : AutoCloseable {
    fun eval(source: String, wallMillis: Long): Teleported
    override fun close() {}
}

/** node: `vm.runInNewContext` — synchronous, `timeout` enforces the wall budget. */
object NodeVmProvider : VmProvider {
    override val id: String = "node-vm"
    override fun isAvailable(): Boolean = isNode()
    override fun report(): VmCapabilityReport = VmCapabilityReport(id, isAvailable(), listOf("js"), "node-vm",
        wallBudgetSupported = true, callSupported = false, note = "vm.runInNewContext with timeout; no roots")
    override fun open(): VmHost = object : JsVmHost("js-node", "node-vm") {
        override fun sandbox(spec: VmSpec): JsSandbox = object : JsSandbox {
            override fun eval(source: String, wallMillis: Long): Teleported {
                val json = js(
                    // errors raised inside the new context belong to another realm (not `instanceof Error` here),
                    // so they are re-thrown as host-realm Errors that Kotlin's Throwable catch can see.
                    "(function(src, ms){ var vm = require('vm'); try { var r = vm.runInNewContext(src, {}, ms > 0 ? {timeout: Number(ms)} : {});" +
                        " return JSON.stringify(r === undefined ? null : r); } catch (e) { throw new Error('GUEST_ERROR: ' + String(e && e.message ? e.message : e)); } })"
                )(source, wallMillis.toDouble()) as String
                return SubVmProtocol.teleportOf(json)
            }
        }
    }
}

/**
 * browser: the sandbox is a strict-mode `Function(src)` on the page thread this round; the wall
 * budget is therefore unenforced and reported as such. The Worker-backed variant (Blob-URL Worker,
 * `terminate` on budget, results via postMessage) needs an asynchronous `eval` on the common API —
 * a follow-up, tracked in the host view as `wallBudgetSupported=false` rather than hidden.
 */
object BrowserWorkerVmProvider : VmProvider {
    override val id: String = "browser-worker"
    override fun isAvailable(): Boolean = !isNode() && hasWorker()
    override fun report(): VmCapabilityReport = VmCapabilityReport(id, isAvailable(), listOf("js"), "worker",
        wallBudgetSupported = false, callSupported = false,
        note = "same-thread strict Function(src); Worker isolation + wall budget are the next ratchet")
    override fun open(): VmHost = object : JsVmHost("js-browser", "worker") {
        override fun sandbox(spec: VmSpec): JsSandbox = object : JsSandbox {
            override fun eval(source: String, wallMillis: Long): Teleported {
                val json = js(
                    "(function(src){ var r = Function('\"use strict\"; return (' + src + ')')(); return JSON.stringify(r === undefined ? null : r); })"
                )(source) as String
                return SubVmProtocol.teleportOf(json)
            }
        }
    }
}

actual fun platformVmProviders(): List<VmProvider> = listOf(NodeVmProvider, BrowserWorkerVmProvider)
