package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.math.hypot

data class LcncTreeShakeOptions(
    val reach: Double = 340.0,
    val includeOptional: Boolean = false,
)

data class LcncTreeShakeVerdict(
    val nodeId: String,
    val dir: String, // "in" or "out"
    val port: String,
    val kind: String?,
    val status: String, // "ok", "open", "scope", "dead"
    val label: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "nodeId" to nodeId,
        "dir" to dir,
        "port" to port,
        "kind" to kind,
        "status" to status,
        "label" to label,
    )
}

data class LcncTreeShakeResult(
    val program: LcncProgram,
    val made: List<LcncWire>,
    val verdicts: List<LcncTreeShakeVerdict>,
    val starvedNodeIds: Set<String>,
    val outletBlockedCount: Int,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "ok" to true,
        "made" to made.map { mapOf("fromNode" to it.fromNode, "fromPort" to it.fromPort, "toNode" to it.toNode, "toPort" to it.toPort) },
        "verdicts" to verdicts.map { it.toMap() },
        "starved" to starvedNodeIds.toList(),
        "outletBlocked" to outletBlockedCount,
        "program" to LcncProgramConfix.toJson(program),
    )
}

/**
 * Server-authoritative tree-shaking for LCNC graphs.
 *
 * Scans open ports across the entire program graph, checks exact kind compatibility
 * and scope boundary containment (data flows lateral or inward), excludes effect nodes
 * from automatic wiring, applies greedy Euclidean proximity pairing (required inputs first),
 * and generates definitive verdicts (ok, open, scope, dead, outletBlocked, starved).
 */
object LcncTreeShake {

    private fun bare(port: String): String = port.removeSuffix("?")

    data class OpenIn(
        val nd: LcncNode,
        val port: String,
        val isRequired: Boolean,
        val isEffect: Boolean,
        val kind: LcncTypeCheck.PortKind,
        val sp: List<String>,
        val x: Double,
        val y: Double,
    )

    data class OpenOut(
        val nd: LcncNode,
        val port: String,
        val kind: LcncTypeCheck.PortKind,
        val sp: List<String>,
        val x: Double,
        val y: Double,
    )

    data class CandidatePair(
        val i: OpenIn,
        val o: OpenOut,
        val dist: Double,
    )

    fun shake(
        program: LcncProgram,
        options: LcncTreeShakeOptions = LcncTreeShakeOptions(),
        contracts: Map<String, LcncPortContract> = LcncContracts.all().associateBy { it.type },
        facts: LcncFacts = LcncFacts.of(contracts.values),
    ): LcncTreeShakeResult {
        val allNodes = ArrayList<LcncNode>()
        val byId = LinkedHashMap<String, LcncNode>()
        val pathOf = LinkedHashMap<String, List<String>>()

        fun walk(ns: Series<LcncNode>, path: List<String>) {
            for (i in 0 until ns.size) {
                val n = ns[i]
                allNodes.add(n)
                byId[n.id] = n
                pathOf[n.id] = path
                if (n.children.size > 0) walk(n.children, path + n.id)
            }
        }
        walk(program.nodes, emptyList())

        val existingWires = ArrayList<LcncWire>()
        for (i in 0 until program.wires.size) existingWires.add(program.wires[i])

        val fedIn = HashSet<Pair<String, String>>()
        val usedOut = HashSet<Pair<String, String>>()
        for (w in existingWires) {
            fedIn.add(w.toNode to bare(w.toPort))
            usedOut.add(w.fromNode to bare(w.fromPort))
        }

        val openIns = ArrayList<OpenIn>()
        val openOuts = ArrayList<OpenOut>()

        for (nd in allNodes) {
            val c = contracts[nd.type]
            val ins = LcncTypeCheck.inputsOf(nd, contracts)
            val outs = LcncTypeCheck.outputsOf(nd, contracts)
            val isEffect = c?.isEffect == true
            val sp = pathOf[nd.id] ?: emptyList()
            val nodeWidth = nd.width ?: 200.0

            for ((idx, ip) in ins.withIndex()) {
                val b = bare(ip)
                if (nd.id to b in fedIn) continue
                val req = !ip.endsWith("?")
                if (!req && !options.includeOptional) continue
                val kind = LcncTypeCheck.portKind(nd, "in", ip, contracts, facts)
                val px = nd.x
                val py = nd.y + 25.0 + idx * 20.0
                openIns.add(OpenIn(nd, ip, req, isEffect, kind, sp, px, py))
            }

            for ((idx, op) in outs.withIndex()) {
                val b = bare(op)
                if (nd.id to b in usedOut) continue
                val kind = LcncTypeCheck.portKind(nd, "out", op, contracts, facts)
                val px = nd.x + nodeWidth
                val py = nd.y + 25.0 + idx * 20.0
                openOuts.add(OpenOut(nd, op, kind, sp, px, py))
            }
        }

        val pairs = ArrayList<CandidatePair>()
        for (i in openIns) {
            for (o in openOuts) {
                if (o.nd.id == i.nd.id) continue
                if (!facts.accepts(o.kind, i.kind)) continue
                // Scope boundary: lateral or inward only
                val inScope = o.sp.size <= i.sp.size && o.sp.indices.all { idx -> i.sp[idx] == o.sp[idx] }
                if (!inScope) continue

                val d = hypot(o.x - i.x, o.y - i.y)
                if (d > options.reach) continue
                pairs.add(CandidatePair(i, o, d))
            }
        }

        // Required inputs first, then nearest
        pairs.sortWith(
            compareBy<CandidatePair> { if (it.i.isRequired) 0 else 1 }
                .thenBy { it.dist },
        )

        val tookIn = HashSet<Pair<String, String>>()
        val tookOut = HashSet<Pair<String, String>>()
        val made = ArrayList<LcncWire>()

        for (pr in pairs) {
            if (pr.i.isEffect) continue // never auto-wire into an effect node
            val ik = pr.i.nd.id to bare(pr.i.port)
            val ok = pr.o.nd.id to bare(pr.o.port)
            if (ik in tookIn || ok in tookOut) continue
            tookIn.add(ik)
            tookOut.add(ok)
            made.add(LcncWire(pr.o.nd.id, pr.o.port, pr.i.nd.id, pr.i.port))
        }

        val stillOpen = openIns.filter { (it.nd.id to bare(it.port)) !in tookIn }

        val verdicts = ArrayList<LcncTreeShakeVerdict>()

        for (pr in pairs.filter { (it.i.nd.id to bare(it.i.port)) in tookIn && (it.o.nd.id to bare(it.o.port)) in tookOut }) {
            if (made.any { it.fromNode == pr.o.nd.id && it.fromPort == pr.o.port && it.toNode == pr.i.nd.id && it.toPort == pr.i.port }) {
                verdicts.add(
                    LcncTreeShakeVerdict(
                        nodeId = pr.i.nd.id,
                        dir = "in",
                        port = pr.i.port,
                        kind = pr.i.kind.kind,
                        status = "ok",
                        label = "closed by ${pr.o.nd.type}.${pr.o.port}",
                    ),
                )
            }
        }

        fun kindMates(i: OpenIn) = openOuts.filter { it.nd.id != i.nd.id && facts.accepts(it.kind, i.kind) }
        fun inScope(o: OpenOut, i: OpenIn) = o.sp.size <= i.sp.size && o.sp.indices.all { idx -> i.sp[idx] == o.sp[idx] }

        val dead = stillOpen.filter { kindMates(it).isEmpty() }
        val scoped = stillOpen.filter {
            val km = kindMates(it)
            km.isNotEmpty() && km.none { o -> inScope(o, it) }
        }
        val reachable = stillOpen.filter {
            val km = kindMates(it)
            km.any { o -> inScope(o, it) }
        }

        for (i in reachable) {
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = i.nd.id,
                    dir = "in",
                    port = i.port,
                    kind = i.kind.kind,
                    status = "open",
                    label = "a legal mate exists, but not within ${options.reach.toInt()}px",
                ),
            )
        }

        for (i in scoped) {
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = i.nd.id,
                    dir = "in",
                    port = i.port,
                    kind = i.kind.kind,
                    status = "scope",
                    label = "kind-compatible producers exist, but outside ring boundary — data flows lateral or inward",
                ),
            )
        }

        for (i in dead) {
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = i.nd.id,
                    dir = "in",
                    port = i.port,
                    kind = i.kind.kind,
                    status = "dead",
                    label = "no kind-compatible mate anywhere on this board",
                ),
            )
        }

        // Outlet blocked
        val allIns = ArrayList<OpenIn>()
        for (nd in allNodes) {
            val ins = LcncTypeCheck.inputsOf(nd, contracts)
            val sp = pathOf[nd.id] ?: emptyList()
            for (ip in ins) {
                val kind = LcncTypeCheck.portKind(nd, "in", ip, contracts, facts)
                allIns.add(OpenIn(nd, ip, !ip.endsWith("?"), contracts[nd.type]?.isEffect == true, kind, sp, 0.0, 0.0))
            }
        }

        var outletBlocked = 0
        for (o in openOuts) {
            if (o.sp.isEmpty()) continue
            val consumers = allIns.filter { it.nd.id != o.nd.id && facts.accepts(o.kind, it.kind) }
            if (consumers.isEmpty()) continue
            if (consumers.any { inScope(o, it) }) continue
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = o.nd.id,
                    dir = "out",
                    port = o.port,
                    kind = o.kind.kind,
                    status = "scope",
                    label = "outlet blocked — every consumer of this yield is outside the ring",
                ),
            )
            outletBlocked++
        }

        // Starved reach: downstream from still-open REQUIRED holes
        val starvedSeed = stillOpen.filter { it.isRequired }.map { it.nd.id }.toSet()
        val allWires = existingWires + made
        val starved = HashSet<String>(starvedSeed)
        var grew = true
        while (grew) {
            grew = false
            for (w in allWires) {
                if (w.fromNode in starved && w.toNode !in starved) {
                    starved.add(w.toNode)
                    grew = true
                }
            }
        }

        val updatedWires = (existingWires + made).toSeries()
        val updatedProgram = program.copy(wires = updatedWires)

        return LcncTreeShakeResult(
            program = updatedProgram,
            made = made,
            verdicts = verdicts,
            starvedNodeIds = starved,
            outletBlockedCount = outletBlocked,
        )
    }
}
