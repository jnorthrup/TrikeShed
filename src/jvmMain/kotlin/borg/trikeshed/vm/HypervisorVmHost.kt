package borg.trikeshed.vm

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.GuestIsolate
import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.graal.subvm.ProcessIsolate
import borg.trikeshed.graal.subvm.Trust
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The JVM host: tier 1 (in-process Graal DAG leafs, `VmTrust.OWN`) and tier 2 (a `ProcessIsolate`
 * child — itself a Graal DAG launched as a process — for `VmTrust.UNTRUSTED`), both behind the one
 * [Hypervisor] so receipts, leases and heat land on the same blackboard. Trust is the promotion
 * lever: dropping it fences the guest behind a process wall.
 */
class HypervisorVmHost(
    val hypervisor: Hypervisor = Hypervisor(),
    override val languages: Set<VmFacet> = setOf(VmFacet.GRAAL_JS, VmFacet.GRAAL_PYTHON),
) : VmHost {
    override val platform: String get() = "jvm"
    private val seq = AtomicLong()
    private val specs = LinkedHashMap<String, VmSpec>()
    private val _events = MutableSharedFlow<VmEvent>(replay = 256, extraBufferCapacity = 1024)
    override val events: Flow<VmEvent> get() = _events

    private fun emit(e: VmEvent) { _events.tryEmit(e) }

    override fun spawn(spec: VmSpec): VmHandle {
        val trust = if (spec.trust == VmTrust.OWN) Trust.OWN else Trust.UNTRUSTED
        val budget = Budget(
            statements = if (spec.budget.statements > 0) spec.budget.statements else Budget().statements,
            wallMillis = if (spec.budget.wallMillis > 0) spec.budget.wallMillis else Budget().wallMillis,
            calls = spec.budget.calls,
        )
        val iso = hypervisor.spawn(spec.id, spec.facet, trust, budget)
        specs[spec.id] = spec
        emit(VmEvent.Spawned(spec.id, seq.incrementAndGet(), spec))
        return Handle(iso)
    }

    override fun get(id: String): VmHandle? = hypervisor.find(id)?.let { Handle(it) }
    override fun ids(): List<String> = hypervisor.ids()

    override fun revoke(id: String, reason: String) {
        hypervisor.revoke(id, reason)
        emit(VmEvent.Revoked(id, seq.incrementAndGet(), reason))
    }

    override fun rows(): Cursor = hypervisor.ids().map { id ->
        val iso = hypervisor[id]
        val lease = hypervisor.lease(id)
        val stats = runCatching { iso.stats() }.getOrNull()
        VmRow(
            id = id,
            facet = iso.facet.id,
            trust = iso.trust.name,
            tier = if (iso is ProcessIsolate) "process" else "in-process",
            phase = when {
                lease?.revoked == true -> "revoked"
                iso is ProcessIsolate && iso.isAlive -> "fenced"
                iso.isAlive -> "live"
                else -> "dead"
            },
            statements = lease?.budget?.statements ?: 0,
            wallMs = lease?.budget?.wallMillis ?: 0,
            calls = stats?.calls ?: 0,
            heat = hypervisor.heat(id),
            receipts = hypervisor.receipts.count { it.isolate == id }.toLong(),
        )
    }.asCursor()

    override fun close() = hypervisor.close()

    private inner class Handle(private val iso: GuestIsolate) : VmHandle {
        override val id: String get() = iso.id
        override val facet: VmFacet get() = iso.facet
        override val tier: String get() = if (iso is ProcessIsolate) "process" else "in-process"
        override val isAlive: Boolean get() = iso.isAlive

        override fun eval(source: String, name: String): Teleported {
            val t0 = System.nanoTime()
            val out = iso.eval(source, name)
            emit(VmEvent.Evaluated(id, seq.incrementAndGet(), out.cid.hex, System.nanoTime() - t0))
            return out
        }

        override fun call(root: String, vararg args: Teleported): Teleported = hypervisor.delegateTo(id, root, *args)

        override fun stats(): VmStats = iso.stats().let {
            VmStats(evals = it.evals, calls = it.calls, hostCalls = it.hostCalls, refutations = it.refutations, interrupted = it.interrupted)
        }

        override fun close() = hypervisor.revoke(id, "closed")
    }
}

/** Tier 1+2 on the JVM: available whenever the Graal polyglot engine can create a JS context. */
object GraalHypervisorProvider : VmProvider {
    override val id: String = "graal-hypervisor"
    override fun isAvailable(): Boolean = runCatching {
        Class.forName("org.graalvm.polyglot.Context")
        org.graalvm.polyglot.Engine.create().use { it.languages.containsKey("js") }
    }.getOrDefault(false)

    override fun report(): VmCapabilityReport {
        val langs = runCatching { org.graalvm.polyglot.Engine.create().use { it.languages.keys.sorted() } }.getOrDefault(emptyList())
        val ok = isAvailable()
        return VmCapabilityReport(id, ok, langs, "in-process", wallBudgetSupported = true, callSupported = true,
            note = if (ok) "Graal DAG leafs in-process; UNTRUSTED spawns a ProcessIsolate child" else "no Graal polyglot engine on the classpath")
    }

    override fun open(): VmHost = HypervisorVmHost()
}
