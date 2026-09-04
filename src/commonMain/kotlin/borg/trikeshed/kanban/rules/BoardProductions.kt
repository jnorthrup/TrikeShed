package borg.trikeshed.kanban.rules

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * The four board productions — all SHALLOW (≤2 hops), all receipted, all
 * routed through the network's productionSink (never the job command loop).
 * Derived commands carry deterministic idempotency keys
 * (`jobId#ruleId#revision`) so a popped-but-unprocessed activation is
 * compensated by store-level dedupe, and refraction stops re-fires while the
 * supporting facts stand.
 */
object BoardRules {
    const val WIP_BREACH = "wip-breach"
    const val DEPENDENCY_READY = "dependency-ready"
    const val STALL = "stall"
    const val CYCLE_GUARD = "cycle-guard"

    /** Delta (claim → work → review): the board claims its own READY work. */
    const val CLAIM = "claim"

    /** The owner a claim stamps on the card: the daemon's own brain, never a person, never Hermes. */
    const val CLAIM_OWNER = "claim:brain"

    internal fun cardInterest(): Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("card" as Any?) }

    internal fun cards(net: ReteNetwork, partitionId: String): List<ReteStoredFact> =
        net.workingMemory.query(BlackboardContext(partitionId), "kind" to "card")

    @Suppress("UNCHECKED_CAST")
    internal fun deps(fact: ReteStoredFact): List<String> =
        (fact.fields["dependencies"] as? List<String>) ?: emptyList()

    internal fun cid(tag: String): ContentId = ContentId.of(tag.encodeToByteArray())
}

/** A column over its WIP limit — attention receipt (the board already refuses NEW entries; this audits imports/replays/starts). */
class WipBreachProduction : ReteProduction {
    override val ruleId: String = BoardRules.WIP_BREACH
    override val salience: Int = 80
    override val interests: Series<Join<String, Any?>> = BoardRules.cardInterest()

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val byCol = BoardRules.cards(net, partitionId).groupBy { it.fields["column"] }
        for (col in BoardCol.entries) {
            val limit = col.wipLimit ?: continue
            val inCol = byCol[col.wire] ?: continue
            if (inCol.size > limit) {
                fire(
                    Activation(
                        activationId = "wip-breach-${col.wire}-${inCol.size}",
                        ruleId = ruleId,
                        ruleVersionCid = BoardRules.cid("rule-wip-breach-v1"),
                        salience = salience,
                        sequence = inCol.size.toLong(),
                        supportCids = inCol.map { it.versionCid },
                        bindings = mapOf("column" to col.wire, "count" to "${inCol.size}", "limit" to "$limit"),
                    ),
                )
            }
        }
    }
}

/** Every dependency of a TODO card is Settled (done/archived) → propose Move(READY). */
class DependencyReadyProduction : ReteProduction {
    override val ruleId: String = BoardRules.DEPENDENCY_READY
    override val salience: Int = 90
    override val interests: Series<Join<String, Any?>> = BoardRules.cardInterest()

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val cards = BoardRules.cards(net, partitionId)
        val factByJob = HashMap<String, ReteStoredFact>(cards.size)
        for (c in cards) factByJob[c.fields["jobId"] as? String ?: continue] = c
        for (card in cards) {
            if (card.fields["column"] != BoardCol.TODO.wire) continue
            val deps = BoardRules.deps(card)
            if (deps.isEmpty()) continue
            val allSettled = deps.all {
                val col = factByJob[it]?.fields?.get("column")
                col == BoardCol.DONE.wire || col == BoardCol.ARCHIVED.wire
            }
            if (!allSettled) continue
            val jobId = card.fields["jobId"] as? String ?: continue
            val revision = (card.fields["revision"] as? Long) ?: 0L
            fire(
                Activation(
                    activationId = "dependency-ready-$jobId-r$revision",
                    ruleId = ruleId,
                    ruleVersionCid = BoardRules.cid("rule-dependency-ready-v1"),
                    salience = salience,
                    sequence = revision,
                    // dependency facts ARE support: their retraction invalidates refraction,
                    // so the rule re-fires when the situation recurs (un-fire + re-fire).
                    supportCids = listOf(card.versionCid) + deps.mapNotNull { factByJob[it]?.versionCid },
                    bindings = mapOf("jobId" to jobId, "toColumn" to BoardCol.READY.wire, "expectedRevision" to "$revision"),
                ),
            )
        }
    }
}

/**
 * The board claims its own work: for each READY card, oldest-first by
 * lastMoveMs, while RUNNING (counted from the facts) + claims fired this pass
 * stays under RUNNING's WIP limit, propose Move(RUNNING, owner=claim:brain).
 * The sink lowers the activation to the store and runs the brain (the module's
 * claim worker); the card comes back through REVIEW, never DONE, by itself.
 *
 * Dedupe is refraction: one firing per card REVISION — the activation id
 * carries the revision and the card's own cid is support, so the same READY
 * card cannot be claimed twice, and a card the reaper sends back to READY is a
 * new revision and is claimed afresh. The RUNNING cards' cids ride as support
 * too (the DependencyReady precedent: the facts that decided the firing ARE its
 * support) so that when RUNNING drains a claim the store refused for WIP is
 * un-refracted and proposed again instead of leaving a READY card stranded.
 * The sink keys its worker by activationId, so a re-proposal of a claim already
 * in flight is a no-op there and a duplicate idempotency key at the store.
 */
class ClaimProduction(private val owner: String = BoardRules.CLAIM_OWNER) : ReteProduction {
    override val ruleId: String = BoardRules.CLAIM
    override val salience: Int = 85
    override val interests: Series<Join<String, Any?>> = BoardRules.cardInterest()

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val limit = BoardCol.RUNNING.wipLimit ?: return
        val cards = BoardRules.cards(net, partitionId)
        val running = cards.filter { it.fields["column"] == BoardCol.RUNNING.wire }
        if (running.size >= limit) return
        val ready = cards
            .filter { it.fields["column"] == BoardCol.READY.wire }
            .sortedWith(compareBy({ (it.fields["lastMoveMs"] as? Long) ?: 0L }, { it.fields["jobId"] as? String ?: "" }))
        var fired = 0
        for (card in ready) {
            if (running.size + fired >= limit) break
            val jobId = card.fields["jobId"] as? String ?: continue
            val revision = (card.fields["revision"] as? Long) ?: 0L
            fire(
                Activation(
                    activationId = "claim-$jobId-r$revision",
                    ruleId = ruleId,
                    ruleVersionCid = BoardRules.cid("rule-claim-v1"),
                    salience = salience,
                    sequence = revision,
                    supportCids = listOf(card.versionCid) + running.map { it.versionCid },
                    bindings = mapOf(
                        "jobId" to jobId,
                        "toColumn" to BoardCol.RUNNING.wire,
                        "expectedRevision" to "$revision",
                        "owner" to owner,
                    ),
                ),
            )
            fired++
        }
    }
}

/**
 * A RUNNING card with no transition for [thresholdMs] — surfaced when the
 * now-FACT ticks past it. Refraction holds one firing per (job, lastMove);
 * the next real move changes lastMoveMs and re-arms.
 */
class StallProduction(private val thresholdMs: Long = 30 * 60 * 1000L) : ReteProduction {
    override val ruleId: String = BoardRules.STALL
    override val salience: Int = 50
    override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("now" as Any?) }

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val now = net.workingMemory.query(BlackboardContext(partitionId), "kind" to "now").firstOrNull() ?: return
        val nowMs = (now.fields["ms"] as? Long) ?: return
        for (card in BoardRules.cards(net, partitionId)) {
            if (card.fields["column"] != BoardCol.RUNNING.wire) continue
            val lastMove = (card.fields["lastMoveMs"] as? Long) ?: continue
            val idle = nowMs - lastMove
            if (idle < thresholdMs) continue
            val jobId = card.fields["jobId"] as? String ?: continue
            fire(
                Activation(
                    activationId = "stall-$jobId-$lastMove",
                    ruleId = ruleId,
                    ruleVersionCid = BoardRules.cid("rule-stall-v1"),
                    salience = salience,
                    sequence = (card.fields["revision"] as? Long) ?: 0L,
                    // The now-fact is deliberately NOT support: every tick modifies it, and
                    // support-invalidation would re-arm refraction each pulse (nag storm).
                    // Support = the card alone; a real move changes lastMoveMs → new id.
                    supportCids = listOf(card.versionCid),
                    bindings = mapOf("jobId" to jobId, "idleMs" to "$idle"),
                ),
            )
        }
    }
}

/** Audit net over imported/replayed dependency cycles (the store refuses NEW ones at the door). */
class CycleGuardProduction : ReteProduction {
    override val ruleId: String = BoardRules.CYCLE_GUARD
    override val salience: Int = 40
    override val interests: Series<Join<String, Any?>> = BoardRules.cardInterest()

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val cards = BoardRules.cards(net, partitionId)
        val edges = HashMap<String, List<String>>(cards.size)
        val byJob = HashMap<String, ReteStoredFact>(cards.size)
        for (c in cards) {
            val id = c.fields["jobId"] as? String ?: continue
            edges[id] = BoardRules.deps(c)
            byJob[id] = c
        }
        // Iterative DFS, three-color; report each cycle once by its sorted membership.
        val state = HashMap<String, Int>() // 0 absent, 1 in-stack, 2 done
        for (root in edges.keys) {
            if (state[root] != null) continue
            val stack = ArrayDeque<Pair<String, Int>>()
            val path = ArrayList<String>()
            stack.addLast(root to 0)
            while (stack.isNotEmpty()) {
                val (node, idx) = stack.removeLast()
                if (idx == 0) {
                    state[node] = 1
                    path.add(node)
                }
                val ds = edges[node] ?: emptyList()
                if (idx < ds.size) {
                    stack.addLast(node to idx + 1)
                    val next = ds[idx]
                    when (state[next]) {
                        1 -> {
                            val cycle = path.subList(path.indexOf(next), path.size).sorted()
                            fire(
                                Activation(
                                    activationId = "cycle-guard-${cycle.joinToString("+")}",
                                    ruleId = ruleId,
                                    ruleVersionCid = BoardRules.cid("rule-cycle-guard-v1"),
                                    salience = salience,
                                    sequence = cycle.size.toLong(),
                                    supportCids = cycle.mapNotNull { byJob[it]?.versionCid },
                                    bindings = mapOf("cycle" to cycle.joinToString(",")),
                                ),
                            )
                        }
                        null -> if (edges.containsKey(next)) stack.addLast(next to 0)
                        else -> {}
                    }
                } else {
                    state[node] = 2
                    path.removeAt(path.size - 1)
                }
            }
        }
    }
}
