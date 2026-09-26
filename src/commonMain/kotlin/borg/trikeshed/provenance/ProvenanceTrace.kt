package borg.trikeshed.provenance

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.view
import borg.trikeshed.narsese.DocumentToolReceipt

/**
 * ProvenanceTrace — the bidirectional walk between CAS byte footings and the
 * semantic productions built over them.
 *
 * A [ProvenanceEdge] is one production transition: which tool ran, over which
 * exact CAS artifacts, into which exact CAS artifacts. The CAS holds the
 * footing of every concept — a cid is the static bytes view; the productions
 * that consumed or emitted those bytes are the middle steps. Backward from an
 * outcome walks input cids down to raw bytes; forward from a cid walks every
 * derived outcome. Cycles are impossible to rule out (a receipt's own
 * receiptCid enters later stages), so the walk carries visited guards and a
 * depth budget.
 *
 * Pure commonMain over parsed fact-plane shapes; every receipt type adapts to
 * an edge, and the diagram renders the same tree in either direction.
 */

data class ProvenanceEdge(
    val label: String,
    val detail: String,
    val receiptCid: ContentId? = null,
    val inputs: Series<ContentId>,
    val outputs: Series<ContentId>,
)

sealed interface ProvenanceNode {
    /** a CAS footing: the static bytes view named by the cid */
    data class Bytes(val cid: ContentId) : ProvenanceNode

    /** one production transition between footings */
    data class Production(val edge: ProvenanceEdge) : ProvenanceNode
}

enum class TraceDirection { BACKWARD, FORWARD }

fun DocumentToolReceipt.edge(): ProvenanceEdge = ProvenanceEdge(
    label = stage.name.lowercase(),
    detail = listOf(tool, implementation, status.name.lowercase(), error).filterNotNull().joinToString(" · "),
    receiptCid = receiptCid,
    inputs = inputCids,
    outputs = outputCids,
)

data object ProvenanceTrace {

    /**
     * Walk from [start] through [edges], level-ordered. BACKWARD answers
     * "what bytes and productions stand under this outcome"; FORWARD answers
     * "what was ever derived from these bytes". The walk alternates footing
     * and production nodes; a receipt's own receiptCid is treated as a
     * footing so a stage can be opened to its retained canonical bytes.
     */
    fun trace(
        start: ContentId,
        edges: List<ProvenanceEdge>,
        direction: TraceDirection,
        depth: Int = 32,
        budget: Int = 4096,
    ): List<ProvenanceNode> {
        val byInput = HashMap<String, MutableList<ProvenanceEdge>>()
        val byOutput = HashMap<String, MutableList<ProvenanceEdge>>()
        for (e in edges) {
            if (e.receiptCid != null) byOutput.getOrPut(e.receiptCid.value) { mutableListOf() }.add(e)
            for (i in e.inputs.view) byInput.getOrPut(i.value) { mutableListOf() }.add(e)
            for (o in e.outputs.view) byOutput.getOrPut(o.value) { mutableListOf() }.add(e)
        }
        val result = mutableListOf<ProvenanceNode>()
        val visitedBytes = HashSet<String>()
        val visitedProductions = HashSet<String>()
        var frontier = listOf(start)
        var level = 0
        while (frontier.isNotEmpty() && level < depth && result.size < budget) {
            val next = mutableListOf<ContentId>()
            for (cid in frontier) {
                if (!visitedBytes.add(cid.value)) continue
                result += ProvenanceNode.Bytes(cid)
                if (result.size >= budget) break
                val touched =
                    if (direction == TraceDirection.BACKWARD) byOutput[cid.value].orEmpty()
                    else byInput[cid.value].orEmpty()
                for (e in touched) {
                    val id = e.receiptCid?.value ?: (e.label + e.inputs.a + e.outputs.a)
                    if (!visitedProductions.add(id)) continue
                    result += ProvenanceNode.Production(e)
                    if (result.size >= budget) break
                    val others = if (direction == TraceDirection.BACKWARD) e.inputs else e.outputs
                    for (o in others.view) if (o.value !in visitedBytes) next += o
                }
            }
            frontier = next
            level++
        }
        return result
    }

    /** the cids at the walk's frontier: raw footings for BACKWARD, derived outcomes for FORWARD */
    fun frontier(nodes: List<ProvenanceNode>): List<ContentId> =
        // ⚡ Bolt: Prevent intermediate ArrayList allocation by using mapNotNull instead of filterIsInstance.map
        nodes.mapNotNull { (it as? ProvenanceNode.Bytes)?.cid }
}
