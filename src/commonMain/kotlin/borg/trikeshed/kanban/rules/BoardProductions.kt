package borg.trikeshed.kanban.rules

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardStoreElement
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
 *
 * Delta (claim → work → review): two more, same discipline — [ClaimProduction]
 * (the board claims its own READY work) and [ReaperProduction] (a claim whose
 * worker died goes back to READY, thrice, then BLOCKED).
 *
 * Delta 2026-09-05 (fan-out): two more still — [FanOutProduction] (a card whose
 * spec names several models is split into child cards on the board, never
 * claimed whole) and [DependencyBlockedProduction] (a parent whose child struck
 * out follows it into BLOCKED, naming the child). The fan-IN needs no new rule:
 * [DependencyReadyProduction] already moves the parent to READY once every
 * child is Done, and the ordinary claim then merges the children's receipts.
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

    /** Delta (reaper): a claimed RUNNING card the worker never brought back goes to READY — thrice, then BLOCKED. */
    const val REAPER = "reaper"

    /** The strike on which the reaper parks the card in BLOCKED (owner cleared) instead of READY. */
    const val REAPER_BLOCK_STRIKE = 3

    /** Delta 2026-09-05 (fan-out): a `MODELS:`/`FANOUT:` card in TODO or READY is split into child cards. */
    const val FAN_OUT = "fan-out"

    /** The actor the fan-out worker signs its child moves and its BLOCKED parking with — never a person. */
    const val FAN_OUT_ACTOR = "fanout:plane"

    /** Delta 2026-09-05 (fan-out): a TODO card whose dependency parked in BLOCKED parks beside it, naming the child. */
    const val DEPENDENCY_BLOCKED = "dependency-blocked"

    internal fun cardInterest(): Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("card" as Any?) }

    internal fun cards(net: ReteNetwork, partitionId: String): List<ReteStoredFact> =
        net.workingMemory.query(BlackboardContext(partitionId), "kind" to "card")

    @Suppress("UNCHECKED_CAST")
    internal fun deps(fact: ReteStoredFact): List<String> =
        (fact.fields["dependencies"] as? List<String>) ?: emptyList()

    internal fun cid(tag: String): ContentId = ContentId.of(tag.encodeToByteArray())

    /** The card fact's explicit fan-out targets (`MODELS:`), parsed by [borg.trikeshed.kanban.BoardFactElement]; empty = none. */
    internal fun models(fact: ReteStoredFact): List<String> =
        (fact.fields["models"] as? List<*>)?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } } ?: emptyList()

    /** The card fact's `FANOUT: n` count; 0 = none asked. */
    internal fun fanout(fact: ReteStoredFact): Int = (fact.fields["fanout"] as? Number)?.toInt() ?: 0

    /**
     * Is [jobId] a child the fan-out worker minted for [parent]? The worker's naming is
     * `<parent>-m<i>` (`BoardFanOutWorker.childJobId`), and THAT — not the `parent`
     * field alone — is what the split predicate counts: a sub-card a person submits
     * with `parent` set is part of the tree on the board but not of the split, so it
     * can never keep the split pending. A card is never its own minted child.
     */
    fun isMintedChild(parent: String, jobId: String): Boolean {
        val prefix = "$parent-m"
        if (jobId.length <= prefix.length || !jobId.startsWith(prefix)) return false
        for (i in prefix.length until jobId.length) if (!jobId[i].isDigit()) return false
        return true
    }

    /** The minted children of [jobId] on the fact plane: card facts whose `parent` names it and whose id the worker minted. */
    internal fun children(jobId: String, cards: List<ReteStoredFact>): List<ReteStoredFact> =
        cards.filter { it.fields["parent"] == jobId && isMintedChild(jobId, it.fields["jobId"] as? String ?: "") }

    /** The join has landed: the card's own dependencies name at least one minted child. */
    internal fun joined(jobId: String, dependencies: List<String>): Boolean =
        dependencies.any { isMintedChild(jobId, it) }

    /**
     * Delta 2026-09-05 (fan-out): the ONE predicate both [FanOutProduction] and
     * [ClaimProduction] read, so a card is never both split and claimed whole.
     * True when [card] declares ≥ 2 targets (`MODELS:` ids, or `FANOUT: n` with
     * n ≥ 2 — the same rule as `PlaneBrief.Spec.fansOut`, read off the fact) and
     * the split is not finished. "Finished" is read off the card's OWN fact first:
     * once its dependencies name a minted child (`<jobId>-m<i>`) the join has
     * landed, and that stays true when the child facts are gone (archived,
     * cancelled, not yet seeded on a cold start) — the parent is then claimed or
     * parked, never re-split and never withheld. With no such dependency the split
     * is pending; with minted children on the plane that the dependencies do not
     * all name, it is pending too (the worker's join is what adds them, as the
     * union of what was there and what it minted). Termination is this predicate,
     * not refraction — a landing child un-refracts the rule and it simply finds
     * nothing pending once the join has landed. The worker reads the same predicate
     * over the store's rows ([borg.trikeshed.kanban.BoardFanOutWorker]).
     */
    fun fanOutPending(card: ReteStoredFact, cards: List<ReteStoredFact>): Boolean {
        if (models(card).size < 2 && fanout(card) < 2) return false
        val jobId = card.fields["jobId"] as? String ?: return false
        return fanOutPending(jobId, deps(card), children(jobId, cards).mapNotNull { it.fields["jobId"] as? String })
    }

    /** The predicate itself, over ids: [dependencies] of the parent and the ids of its minted [children] as some reader sees them. */
    fun fanOutPending(jobId: String, dependencies: List<String>, children: List<String>): Boolean {
        if (children.isEmpty()) return !joined(jobId, dependencies)
        val deps = dependencies.toHashSet()
        return children.any { it !in deps }
    }
}

/**
 * Delta 2026-09-05 (fan-out): the Todo/Ready lane process for a `MODELS:` card.
 * A card in TODO or READY with [BoardRules.fanOutPending] proposes its own
 * split; the sink hands it to the module's [borg.trikeshed.kanban.BoardFanOutWorker],
 * which submits one child per model, readies each, and re-submits the parent
 * with the children as dependencies (the join). From there the fan-IN is the
 * existing causal rule: [DependencyReadyProduction] moves the parent to READY
 * when every child is Done, and [ClaimProduction] claims it with the children's
 * receipts in its brief.
 *
 * Salience 95 sits above dependency-ready (90) and claim (85): a split is
 * decided before anything else looks at the card. Support = the card's cid plus
 * its children's cids, so a landing child un-refracts and re-proposes; the
 * sink's in-flight set (keyed by activationId, which carries the revision)
 * makes the re-proposal a no-op while the worker runs, and the predicate is
 * false once the join has landed. A join the store refused (someone moved the
 * parent meanwhile) leaves the predicate true at a NEW revision → a new
 * activation id → the worker runs again; the child submits and readies carry
 * parent-revision-independent keys, so that re-run is a duplicate, never a double.
 */
class FanOutProduction : ReteProduction {
    override val ruleId: String = BoardRules.FAN_OUT
    override val salience: Int = 95
    override val interests: Series<Join<String, Any?>> = BoardRules.cardInterest()

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val cards = BoardRules.cards(net, partitionId)
        for (card in cards) {
            val column = card.fields["column"]
            if (column != BoardCol.TODO.wire && column != BoardCol.READY.wire) continue
            if (!BoardRules.fanOutPending(card, cards)) continue
            val jobId = card.fields["jobId"] as? String ?: continue
            val revision = (card.fields["revision"] as? Long) ?: 0L
            val kids = BoardRules.children(jobId, cards)
            fire(
                Activation(
                    activationId = "fan-out-$jobId-r$revision",
                    ruleId = ruleId,
                    ruleVersionCid = BoardRules.cid("rule-fan-out-v1"),
                    salience = salience,
                    sequence = revision,
                    // The children ARE support: a child landing (or leaving) re-evaluates the split.
                    supportCids = listOf(card.versionCid) + kids.map { it.versionCid },
                    bindings = mapOf(
                        "jobId" to jobId,
                        "expectedRevision" to "$revision",
                        "models" to BoardRules.models(card).joinToString(","),
                        "fanout" to "${BoardRules.fanout(card)}",
                    ),
                ),
            )
        }
    }
}

/**
 * Delta 2026-09-05 (fan-out, "children that park"): a TODO card one of whose
 * dependencies sits in BLOCKED — a fan-out child that struck out, or any
 * dependency a reaper or judge parked — can never become READY by itself, and
 * without this rule it would wait in TODO forever showing nothing. Propose
 * Move(BLOCKED) naming the child (`blockedBy`), signed by the plane judge; the
 * sink lowers it with the key `<jobId>#dependency-blocked#<rev>`. Recovery is
 * human: whoever unblocks the child moves the parent back too.
 *
 * Salience 88: below dependency-ready (90), which cannot fire for the same card
 * (a BLOCKED dependency is not settled), above claim (85). One activation per
 * (parent, blocked child, revision); several blocked children lower to the same
 * store key and dedupe. Support = the parent's cid and the child's, so the child
 * leaving BLOCKED un-refracts the rule for a later recurrence.
 */
class DependencyBlockedProduction : ReteProduction {
    override val ruleId: String = BoardRules.DEPENDENCY_BLOCKED
    override val salience: Int = 88
    override val interests: Series<Join<String, Any?>> = BoardRules.cardInterest()

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val cards = BoardRules.cards(net, partitionId)
        val factByJob = HashMap<String, ReteStoredFact>(cards.size)
        for (c in cards) factByJob[c.fields["jobId"] as? String ?: continue] = c
        for (card in cards) {
            if (card.fields["column"] != BoardCol.TODO.wire) continue
            val deps = BoardRules.deps(card)
            if (deps.isEmpty()) continue
            val jobId = card.fields["jobId"] as? String ?: continue
            val revision = (card.fields["revision"] as? Long) ?: 0L
            for (childId in deps) {
                val child = factByJob[childId] ?: continue
                if (child.fields["column"] != BoardCol.BLOCKED.wire) continue
                fire(
                    Activation(
                        activationId = "dependency-blocked-$jobId-$childId-r$revision",
                        ruleId = ruleId,
                        ruleVersionCid = BoardRules.cid("rule-dependency-blocked-v1"),
                        salience = salience,
                        sequence = revision,
                        supportCids = listOf(card.versionCid, child.versionCid),
                        bindings = mapOf(
                            "jobId" to jobId,
                            "expectedRevision" to "$revision",
                            "toColumn" to BoardCol.BLOCKED.wire,
                            "blockedBy" to childId,
                        ),
                    ),
                )
            }
        }
    }
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
 *
 * Delta 2026-09-05 (fan-out): a READY card with [BoardRules.fanOutPending] is
 * skipped — a `MODELS:` card dragged straight to READY is split by
 * [FanOutProduction] (salience 95, decided first), never claimed whole. Once its
 * join has landed the predicate is false and the parent is claimed like any card.
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
            if (BoardRules.fanOutPending(card, cards)) continue
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

/**
 * The reaper: a RUNNING card owned by `claim:*` with no transition for
 * [thresholdMs] (lastMoveMs against the now-FACT) is a claim whose worker
 * died — a daemon restart mid-brain-call, a hung provider — and would sit
 * there forever (the dead tree unsupervised splitting leaves). Propose
 * Move(READY, expectedRevision) with `strike` = prior reaper firings for the
 * job + 1; the sink moves it to READY (the claim production then claims the
 * new revision afresh) or, on [BoardRules.REAPER_BLOCK_STRIKE], to BLOCKED
 * with the owner cleared so a human sees it.
 *
 * The production reads working memory only; the prior strikes live on the
 * blackboard as `kanban/rule/reaper/<activationId>` receipts, so the count comes in
 * through [priorStrikes] — the module hands in [countPriorStrikes] over its
 * blackboard; a bare production counts nothing and every firing is strike 1.
 * Refraction as StallProduction: one firing per (job, lastMoveMs), the card
 * alone is support (the now-fact would re-arm every tick), a real move → new id.
 */
class ReaperProduction(
    private val thresholdMs: Long = 15 * 60 * 1000L,
    private val priorStrikes: (jobId: String) -> Int = { 0 },
) : ReteProduction {
    override val ruleId: String = BoardRules.REAPER
    override val salience: Int = 45
    override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("now" as Any?) }

    companion object {
        const val RECEIPT_PREFIX: String = "kanban/rule/${BoardRules.REAPER}/"

        /**
         * Prior reaper strikes for [jobId]: the `kanban/rule/reaper/<activationId>` receipts
         * whose bindings name the job (the receipt VALUE is the activation's
         * bindings, so a jobId that is a prefix of another never miscounts).
         */
        fun countPriorStrikes(blackboard: borg.trikeshed.graal.ConfixBlackboard, jobId: String): Int =
            blackboard.keys().count { key ->
                key.startsWith(RECEIPT_PREFIX) && (blackboard.get(key) as? Map<*, *>)?.get("jobId") == jobId
            }
    }

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val now = net.workingMemory.query(BlackboardContext(partitionId), "kind" to "now").firstOrNull() ?: return
        val nowMs = (now.fields["ms"] as? Long) ?: return
        for (card in BoardRules.cards(net, partitionId)) {
            if (card.fields["column"] != BoardCol.RUNNING.wire) continue
            val owner = card.fields["owner"] as? String ?: continue
            if (!owner.startsWith(BoardStoreElement.CLAIM_OWNER_PREFIX)) continue
            val lastMove = (card.fields["lastMoveMs"] as? Long) ?: continue
            val idle = nowMs - lastMove
            if (idle <= thresholdMs) continue
            val jobId = card.fields["jobId"] as? String ?: continue
            val revision = (card.fields["revision"] as? Long) ?: 0L
            val strike = priorStrikes(jobId) + 1
            fire(
                Activation(
                    activationId = "reaper-$jobId-$lastMove",
                    ruleId = ruleId,
                    ruleVersionCid = BoardRules.cid("rule-reaper-v1"),
                    salience = salience,
                    sequence = revision,
                    supportCids = listOf(card.versionCid),
                    bindings = mapOf(
                        "jobId" to jobId,
                        "toColumn" to BoardCol.READY.wire,
                        "expectedRevision" to "$revision",
                        "strike" to "$strike",
                        "idleMs" to "$idle",
                        "owner" to owner,
                    ),
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
