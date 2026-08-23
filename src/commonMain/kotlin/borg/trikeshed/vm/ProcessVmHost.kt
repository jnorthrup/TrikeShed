package borg.trikeshed.vm

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Tier 2 as a host of its own: every guest is a child process behind a [ProcessPipe], speaking
 * [SubVmProtocol]. Common code — a target contributes only the [launcher] that turns a spec into a
 * pipe (`java … SubVmMain` on the JVM, a node launcher, a native guest). Separate address space is
 * the page protection the in-process tier cannot give.
 */
class ProcessVmHost(
    override val platform: String,
    override val languages: Set<VmFacet>,
    private val launcher: (VmSpec) -> ProcessPipe,
) : VmHost {
    private val handles = LinkedHashMap<String, ProcessIsolateHost>()
    private val specs = LinkedHashMap<String, VmSpec>()
    private val revoked = LinkedHashMap<String, String>()
    private var seq = 0L
    private val _events = MutableSharedFlow<VmEvent>(replay = 256, extraBufferCapacity = 1024)
    override val events: Flow<VmEvent> get() = _events

    override fun spawn(spec: VmSpec): VmHandle {
        require(spec.id !in handles) { "isolate '${spec.id}' exists" }
        val h = ProcessIsolateHost(spec.id, spec.facet, launcher(spec))
        handles[spec.id] = h; specs[spec.id] = spec
        _events.tryEmit(VmEvent.Spawned(spec.id, ++seq, spec))
        return Observed(h)
    }

    override fun get(id: String): VmHandle? = handles[id]?.let { Observed(it) }
    override fun ids(): List<String> = handles.keys.sorted()

    override fun revoke(id: String, reason: String) {
        val h = handles[id] ?: return
        revoked[id] = reason
        runCatching { h.interrupt() }
        h.close()
        _events.tryEmit(VmEvent.Revoked(id, ++seq, reason))
    }

    override fun rows(): Cursor = ids().map { id ->
        val h = handles.getValue(id); val spec = specs.getValue(id); val st = h.stats()
        VmRow(
            id = id, facet = spec.facet.id, trust = spec.trust.name, tier = "process",
            phase = when { id in revoked -> "revoked"; h.isAlive -> "fenced"; else -> "dead" },
            statements = spec.budget.statements, wallMs = spec.budget.wallMillis,
            calls = st.calls, heat = st.evals + st.calls, receipts = 0,
        )
    }.asCursor()

    override fun close() { handles.values.forEach { runCatching { it.close() } } }

    private inner class Observed(private val h: ProcessIsolateHost) : VmHandle by h {
        override fun eval(source: String, name: String): Teleported {
            val out = h.eval(source, name)
            _events.tryEmit(VmEvent.Evaluated(h.id, ++seq, out.cid.hex, 0))
            return out
        }
    }
}
