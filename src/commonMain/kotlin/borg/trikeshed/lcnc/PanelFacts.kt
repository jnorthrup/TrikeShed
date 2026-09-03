package borg.trikeshed.lcnc

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * THE PANELS PLANE — one LCNC program exploded into Rete facts.
 *
 * A canvas on the blackboard is ONE entry (`lcnc/program/<name>`,
 * [LcncBlackboard.programEntry]); a production cannot watch "a cable whose
 * type is json" inside it because the only gate a production has is
 * `field == value` per partition. So every program lands in the
 * [PlaneFacts.PANELS] partition as one fact per program, per node (rings
 * flattened, each node naming its parent ring), per cable (the EXACT type the
 * entry recorded — the cables-are-never-untyped rule becomes watchable) and
 * per violation:
 *
 *     FactId(panels, <name>)                  {kind:program,   key, sourceCid, nodes, cables, violations}
 *     FactId(panels, <name>/node/<id>)        {kind:node,      key, node, type, parent}
 *     FactId(panels, <name>/cable/<i>)        {kind:cable,     key, cable, fromNode, fromPort, toNode, toPort, type}
 *     FactId(panels, <name>/violation/<i>)    {kind:violation, key, violation, rule, fromNode, fromPort, toNode, toPort, detail}
 *
 * [PlaneFacts.KEY] is the program name on every fact — the inverse pointer:
 * `key` -> `LcncPublisher.load(key)` / `boardEntry(key)`. [PlaneFacts.ACTOR]
 * is the publisher's actor. There is deliberately NO [PlaneFacts.AT_MS]: the
 * facts are a pure function of the entry, so a board-identical republish
 * derives byte-identical fields and the bridge below emits nothing.
 *
 * All facts of one program share one versionCid — `ContentId(sourceCid)` when
 * the entry carries one, else the cid of the program's canonical Confix JSON
 * ([LcncBlackboard.cidOf]) — so a fact's version names the document it was
 * exploded from, and refraction keyed on support cids re-arms only when the
 * document moves.
 *
 * This object is pure: entry in, facts out. The store side is [PanelFactBridge].
 */
object PanelFacts {

    const val KIND_PROGRAM = "program"
    const val KIND_NODE = "node"
    const val KIND_CABLE = "cable"
    const val KIND_VIOLATION = "violation"

    /** The default provenance on every panels fact: the one LCNC writer. */
    const val ACTOR_LCNC = "lcnc"

    fun programLocalId(name: String): String = name
    fun nodeLocalId(name: String, nodeId: String): String = "$name/node/$nodeId"
    fun cableLocalId(name: String, index: Int): String = "$name/cable/$index"
    fun violationLocalId(name: String, index: Int): String = "$name/violation/$index"

    /**
     * The version every fact of [name] carries: the entry's `sourceCid` when it
     * is a well-formed content id, else the cid of [program]'s canonical JSON.
     */
    fun versionOf(program: LcncProgram, sourceCid: String?): ContentId =
        sourceCid?.let { runCatching { ContentId(it) }.getOrNull() } ?: ContentId(LcncBlackboard.cidOf(program))

    /**
     * Explode one program. [entry] is what [LcncBlackboard.programEntry]
     * returned for it (its `cables` carry the exact types, its `violations`
     * the checker's rows); [program] supplies the node tree, rings flattened
     * depth-first with each node's enclosing ring as `parent` (null at the
     * root). The program fact comes first, then nodes, cables, violations.
     */
    fun explode(
        name: String,
        program: LcncProgram,
        entry: Map<String, Any?>,
        versionCid: ContentId = versionOf(program, entry["sourceCid"]?.toString()),
        actor: String = ACTOR_LCNC,
    ): List<ReteStoredFact> {
        val board = BlackboardContext(PlaneFacts.PANELS)
        fun fact(localId: String, fields: Map<String, Any?>): ReteStoredFact =
            ReteStoredFact(FactId(PlaneFacts.PANELS, localId), fields, versionCid, board)

        val nodes = ArrayList<ReteStoredFact>()
        fun walk(series: borg.trikeshed.lib.Series<LcncNode>, parent: String?) {
            for (i in 0 until series.size) {
                val n = series[i]
                nodes.add(fact(nodeLocalId(name, n.id), linkedMapOf(
                    PlaneFacts.KIND to KIND_NODE,
                    PlaneFacts.KEY to name,
                    PlaneFacts.ACTOR to actor,
                    "node" to n.id,
                    "type" to n.type,
                    "parent" to parent,
                )))
                walk(n.children, n.id)
            }
        }
        walk(program.nodes, null)

        val cableRows = entry["cables"] as? List<*> ?: emptyList<Any?>()
        val cables = cableRows.mapIndexed { i, row ->
            val m = row as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val from = m["from"] as? List<*> ?: emptyList<Any?>()
            val to = m["to"] as? List<*> ?: emptyList<Any?>()
            fact(cableLocalId(name, i), linkedMapOf(
                PlaneFacts.KIND to KIND_CABLE,
                PlaneFacts.KEY to name,
                PlaneFacts.ACTOR to actor,
                "cable" to i,
                "fromNode" to from.getOrNull(0)?.toString(),
                "fromPort" to from.getOrNull(1)?.toString(),
                "toNode" to to.getOrNull(0)?.toString(),
                "toPort" to to.getOrNull(1)?.toString(),
                "type" to m["type"]?.toString(),
            ))
        }

        val violationRows = entry["violations"] as? List<*> ?: emptyList<Any?>()
        val violations = violationRows.mapIndexed { i, row ->
            val m = row as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val fields = LinkedHashMap<String, Any?>()
            fields[PlaneFacts.KIND] = KIND_VIOLATION
            fields[PlaneFacts.KEY] = name
            fields[PlaneFacts.ACTOR] = actor
            fields["violation"] = i
            // The checker's own columns (rule, fromNode, fromPort, toNode, toPort, detail), scalars as they are.
            for ((k, v) in m) {
                val field = k.toString()
                if (field in RESERVED_ON_VIOLATION) continue
                fields[field] = when (v) {
                    null, is String, is Number, is Boolean -> v
                    else -> PlaneFacts.canonicalJson(v)
                }
            }
            fact(violationLocalId(name, i), fields)
        }

        val programFact = fact(programLocalId(name), linkedMapOf(
            PlaneFacts.KIND to KIND_PROGRAM,
            PlaneFacts.KEY to name,
            PlaneFacts.ACTOR to actor,
            "sourceCid" to entry["sourceCid"]?.toString(),
            "nodes" to nodes.size,
            "cables" to cables.size,
            "violations" to violations.size,
        ))

        val out = ArrayList<ReteStoredFact>(1 + nodes.size + cables.size + violations.size)
        out.add(programFact)
        out.addAll(nodes)
        out.addAll(cables)
        out.addAll(violations)
        return out
    }

    private val RESERVED_ON_VIOLATION = setOf(PlaneFacts.KIND, PlaneFacts.KEY, PlaneFacts.ACTOR, "violation")

    /** Every node of [program], rings flattened depth-first — the count a program fact's `nodes` field reports. */
    fun flattenedNodeCount(program: LcncProgram): Int {
        var n = 0
        fun walk(series: borg.trikeshed.lib.Series<LcncNode>) {
            for (i in 0 until series.size) {
                n++
                walk(series[i].children)
            }
        }
        walk(program.nodes)
        return n
    }
}

/**
 * The store side of the panels plane: lands [PanelFacts.explode] output in a
 * [ReteNetwork] and keeps, per program, the set of localIds it currently holds
 * so a republish retracts what vanished (a dropped wire, a deleted node, a
 * violation that was fixed) — the `known`-set discipline of
 * `CouchChangesFactElement`.
 *
 * Idempotent by construction: a fact whose current version and fields already
 * match is skipped, so a board-identical republish is silent to the network's
 * observers; a fact that exists with other content is [ReteNetwork.modify]'d,
 * never re-asserted (working memory refuses a re-assert with different
 * content). The known set is seeded from the network's current `key == name`
 * facts the first time a name is seen, so a second bridge over the same
 * network (two [LcncPublisher]s exist over one board in the daemon) or a
 * bridge created after facts already landed still retracts correctly.
 *
 * Never called from inside a network observer (the write lock is not
 * reentrant); the publisher calls it after its own blackboard put.
 */
class PanelFactBridge(val network: ReteNetwork) {

    private val known = LinkedHashMap<String, Set<String>>()

    /** Ops this bridge applied (asserts + modifies + retracts); a silent republish leaves it unchanged. */
    var opsApplied: Long = 0L
        private set

    /** The localIds this bridge believes the network holds for [name]. */
    fun knownLocalIds(name: String): Set<String> = known[name] ?: emptySet()

    /** Land [facts] (all of one program, [name]) and retract the localIds of [name] that are no longer among them. */
    suspend fun publish(name: String, facts: List<ReteStoredFact>) {
        val previous = known[name] ?: seedKnown(name)
        val next = LinkedHashSet<String>(facts.size)
        for (f in facts) {
            require(f.factId.partitionId == PlaneFacts.PANELS) { "panels bridge given a ${f.factId.partitionId} fact" }
            next.add(f.factId.localId)
            val existing = network.workingMemory.facts(f.factId).firstOrNull()
            when {
                existing == null -> { network.assert(f.factId, f.fields, f.versionCid, f.board); opsApplied++ }
                existing.versionCid == f.versionCid && existing.fields == f.fields -> Unit
                else -> { network.modify(f.factId, f.fields, f.versionCid); opsApplied++ }
            }
        }
        for (gone in previous) if (gone !in next) {
            network.retract(FactId(PlaneFacts.PANELS, gone))
            opsApplied++
        }
        known[name] = next
    }

    /** Explode and land one program: [entry] is [LcncBlackboard.programEntry]'s output for it. */
    suspend fun publish(name: String, program: LcncProgram, entry: Map<String, Any?>, actor: String = PanelFacts.ACTOR_LCNC) =
        publish(name, PanelFacts.explode(name, program, entry, actor = actor))

    /** Retract every fact of [name] — a program removed from the board. */
    suspend fun retract(name: String) {
        val previous = known[name] ?: seedKnown(name)
        for (gone in previous) {
            network.retract(FactId(PlaneFacts.PANELS, gone))
            opsApplied++
        }
        known.remove(name)
    }

    private fun seedKnown(name: String): Set<String> {
        val current = network.workingMemory.query(BlackboardContext(PlaneFacts.PANELS), PlaneFacts.KEY to name)
        val ids = LinkedHashSet<String>(current.size)
        for (f in current) ids.add(f.factId.localId)
        return ids
    }
}
