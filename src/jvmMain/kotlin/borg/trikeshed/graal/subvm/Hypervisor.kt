package borg.trikeshed.graal.subvm

import borg.trikeshed.cursor.TypedefProductionSystem
import borg.trikeshed.dag.ReteFact
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.pointcut.PointcutEvent
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.pointcut.coord.SiteKey
import borg.trikeshed.pointcut.coord.confixPath
import borg.trikeshed.dag.ReteAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hypervisor: every isolate is a node on the blackboard, every crossing is a receipt, and the
 * decision to teleport a leaf is a Rete rule over [ReteFact.SiteHeat] — not a counter in a loop.
 *
 *  spawn ──▶ GuestIsolate (InProcess for OWN trust, Process for UNTRUSTED)
 *  lease ──▶ Budget per isolate; revoke = interrupt + tombstone landing
 *  observe ──▶ LeafTrainer profiles + SiteHeat facts ──▶ ReteAgent.runFacts(rules)
 *  rule "leaf-promotion" fires ──▶ trainer.promote(root)   (SELF_CONTAINED → SHADOWED)
 *  rule "lease-budget" fires ──▶ revoke(isolate)
 *  every receipt / transition ──▶ PointcutBlackboardAdapter.accept(PointcutEvent) [+ production system synapse]
 *
 * The blackboard, adapter, production system and Rete are the existing organs; this class only wires them.
 */
class Hypervisor(
    val blackboard: ConfixBlackboard = ConfixBlackboard.empty(),
    val adapter: PointcutBlackboardAdapter = PointcutBlackboardAdapter(blackboard),
    val promoteAfter: Long = 8,
    val trainCalls: Int = 8,
    val shadowCalls: Int = 4,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    data class Lease(val isolate: String, val budget: Budget, val issuedSeq: Int) { @Volatile var revoked = false }

    private val isolates = ConcurrentHashMap<String, GuestIsolate>()
    private val trainers = ConcurrentHashMap<String, LeafTrainer>()
    private val leases = ConcurrentHashMap<String, Lease>()
    private val heat = ConcurrentHashMap<String, Long>()
    private val seq = AtomicInteger()
    val fires = java.util.concurrent.CopyOnWriteArrayList<ReteAgent.Fire>()

    /**
     * The audit trail: every crossing, content-addressed, in order. A blackboard landing costs
     * ~300µs (ConfixBlackboard.put), so per-call receipts are logged here at full rate and LANDED at
     * the same sampled cadence as the Rete heat facts (first [promoteAfter], powers of two, every 256);
     * phase transitions, spawns and revocations always land.
     */
    private val receiptLog = ArrayDeque<DelegationReceipt>()
    private val receiptCounts = ConcurrentHashMap<String, Long>()
    val receipts: List<DelegationReceipt> get() = synchronized(receiptLog) { receiptLog.toList() }

    /**
     * Log a receipt under the hypervisor's single sequence (trainers and delegateTo would otherwise
     * interleave two counters) and decide whether it also lands. Returns the stamped receipt to land, or null.
     */
    private fun record(r0: DelegationReceipt): DelegationReceipt? {
        val r: DelegationReceipt
        synchronized(receiptLog) {
            r = r0.copy(seq = seq.incrementAndGet())
            receiptLog.addLast(r); if (receiptLog.size > RECEIPT_LOG_CAP) receiptLog.removeFirst()
        }
        // sampled per (isolate, root, served) so a flood of GUEST crossings never starves the first SHADOW/MEMO/HOST receipts
        val n = receiptCounts.merge("${r.isolate}/${r.root}/${r.served}", 1L, Long::plus)!!
        return if (r.refuted || n <= promoteAfter || (n and (n - 1)) == 0L || n % 256 == 0L) r else null
    }

    private val rules: List<ReteAgent.ReteFactRule> = listOf(
        ReteAgent.ReteFactRule(
            name = "leaf-promotion",
            predicate = { f ->
                f is ReteFact.SiteHeat && f.count >= promoteAfter &&
                    (trainers[f.site.owner]?.profiles?.get(f.site.methodName)?.let { it.phase == LeafTrainer.Phase.SELF_CONTAINED && !it.promotionQueued } ?: false)
            },
            transform = { f -> f as ReteFact.SiteHeat; ReteAgent.Fire("leaf-promotion", "${f.site.owner}/${f.site.methodName}", f.site.confixPath, f.site.methodName, "") },
        ),
        ReteAgent.ReteFactRule(
            name = "lease-budget",
            predicate = { f -> f is ReteFact.SiteHeat && (leases[f.site.owner]?.let { l -> l.budget.calls > 0 && f.count > l.budget.calls && !l.revoked } ?: false) },
            transform = { f -> f as ReteFact.SiteHeat; ReteAgent.Fire("lease-budget", f.site.owner, f.site.confixPath, "${f.count}", "") },
        ),
    )

    private val rete: ReteAgent.FactAgent = ReteAgent.runFacts(rules, scope, agentId = "hypervisor") { fire ->
        fires += fire
        // never let a rule action kill the agent: the fire is recorded, the action is best-effort
        runCatching {
            when (fire.ruleName) {
                "leaf-promotion" -> { val iso = fire.nodeId.substringBefore('/'); trainers[iso]?.promote(fire.payload) }
                "lease-budget" -> revoke(fire.nodeId, "budget: ${fire.payload} calls")
            }
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────
    fun spawn(id: String, facet: VmFacet, trust: Trust = Trust.OWN, budget: Budget = Budget()): GuestIsolate {
        require(!isolates.containsKey(id)) { "isolate '$id' exists" }
        val iso: GuestIsolate = when (trust) {
            Trust.OWN -> {
                lateinit var trainer: LeafTrainer
                val inproc = InProcessIsolate(id, facet, budget) { obs -> trainer.observe(obs); observed(id, facet, obs) }
                trainer = LeafTrainer(inproc, trainCalls, shadowCalls,
                    onTransition = { p, from, to -> land(id, facet, p.root, "phase", "$from→$to${p.demotedReason?.let { " ($it)" } ?: ""}", p.line, p.column, p.sourceName) },
                    onReceipt = { r -> record(r)?.let { land(id, facet, it.root, "delegate", it.toString(), -1, -1, null) } })
                trainers[id] = trainer
                inproc
            }
            Trust.UNTRUSTED -> ProcessIsolate(id, facet, budget)
        }
        isolates[id] = iso
        leases[id] = Lease(id, budget, seq.incrementAndGet())
        land(id, facet, "<isolate>", "spawn", "$trust ${facet.id} $budget", -1, -1, null)
        return iso
    }

    operator fun get(id: String): GuestIsolate = isolates[id] ?: throw IllegalArgumentException("no isolate '$id'")
    fun trainer(id: String): LeafTrainer? = trainers[id]
    fun lease(id: String): Lease? = leases[id]

    fun revoke(id: String, reason: String) {
        val l = leases[id] ?: return
        if (l.revoked) return
        l.revoked = true
        isolates[id]?.interrupt()
        land(id, isolates[id]?.facet ?: VmFacet.JVM, "<isolate>", "revoke", reason, -1, -1, null)
    }

    /** host → guest: call a guest root; the crossing is a receipt on the blackboard. */
    fun delegateTo(id: String, root: String, vararg args: Teleported): Teleported {
        val iso = get(id)
        val t0 = System.nanoTime()
        val result = iso.call(root, *args)
        val r = DelegationReceipt(id, root, Teleported.Arr(args.toList()).cid, result.cid, Served.GUEST, System.nanoTime() - t0, 0)
        record(r)?.let { land(id, iso.facet, root, "delegate-to", it.toString(), -1, -1, null) }
        return result
    }

    /** guest → host: register a host function; each guest call of it is a receipt on the blackboard. */
    fun delegateFrom(id: String, name: String, fn: (List<Teleported>) -> Teleported) {
        val iso = get(id)
        iso.delegate(name) { args ->
            val t0 = System.nanoTime()
            val result = fn(args)
            val r = DelegationReceipt(id, "host.$name", Teleported.Arr(args).cid, result.cid, Served.HOST, System.nanoTime() - t0, 0)
            record(r)?.let { land(id, iso.facet, "host.$name", "delegate-from", it.toString(), -1, -1, null) }
            result
        }
    }

    // ── observation → facts ───────────────────────────────────────────────
    private fun observed(id: String, facet: VmFacet, o: RootObservation) {
        val key = "$id/${o.root}"
        val n = heat.merge(key, 1L, Long::plus)!!
        // fact cadence: every call while training, then powers of two, then every 256 — the Rete sees the curve, not every tick
        if (n <= promoteAfter || (n and (n - 1)) == 0L || n % 256 == 0L) {
            val site = SiteKey(owner = id, methodName = o.root, methodDescriptor = "()", bytecodeOffset = 0, line = o.line, column = o.column)
            rete.sink.trySend(ReteFact.SiteHeat(site, n, System.nanoTime(), null))
        }
    }

    private fun land(id: String, facet: VmFacet, root: String, property: String, value: String, line: Int, column: Int, sourceName: String?) {
        val ev = PointcutEvent(
            vmFacet = facet, coordinate = "$id.$root", target = null, propertyName = property, newValue = value,
            seq = seq.incrementAndGet(), sourcePath = sourceName, line = line, column = column, isRoot = true,
        )
        runCatching { adapter.accept(ev, isWrite = true) }
        if (TypedefProductionSystem.active) runCatching { TypedefProductionSystem.publish(ev.toFieldSynapse()) }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "isolates" to isolates.keys.sorted(),
        "leases" to leases.values.map { "${it.isolate}:${if (it.revoked) "revoked" else "live"}" },
        "profiles" to trainers.mapValues { (_, t) -> t.profiles.values.map { it.toString() } },
        "fires" to fires.map { "${it.ruleName}:${it.nodeId}" },
        "landings" to adapter.size,
        "receipts" to synchronized(receiptLog) { receiptLog.size },
    )

    companion object { const val RECEIPT_LOG_CAP = 1 shl 16 }

    override fun close() {
        ReteAgent.stop(rete)
        trainers.values.forEach { runCatching { it.close() } }
        isolates.values.forEach { runCatching { it.close() } }
        scope.cancel()
    }
}
