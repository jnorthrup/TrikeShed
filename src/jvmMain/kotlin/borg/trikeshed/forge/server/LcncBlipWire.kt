package borg.trikeshed.forge.server

import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.lcnc.PanelFacts
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport

/**
 * The hover blip: one node of the LCNC blackboard read across the planes it
 * lands on, with what was ASSERTED kept apart from what was INFERRED, and
 * the graal plane fused in beside them. This is the first place the LCNC
 * view and the graal view meet on one element.
 *
 *  - `GET /api/lcnc/blip?program=&node=&type=` →
 *    ```
 *    { program, node, type,
 *      asserted: { onPlane, facts:[{id, fields}] },          // panels/<program>/node|cable — actor lcnc, told by the publisher
 *      inferred: { violations:[{id, fields}],                // panels/<program>/violation — the type checker's verdicts
 *                  vocabulary:[{pattern, bindings}],         // the KIF bank's answers about the node's type
 *                  productions:[{ruleId, salience, interests, matched}] }, // productions whose interests hit this node's facts
 *      graal:    { keyed:[{id, fields}], jvm:[{id, fields}] } }         // graal facts naming program/node/type; the JVM-wide tick
 *    ```
 *
 * Every fact comes from one [ReteNetwork.snapshot] (write-lock serialized);
 * the KIF rows are the bank's own bindings; the production rows are the
 * registry. Nothing is authored here: an empty section is an honest empty.
 * `program` may be blank (an unsaved construction) — then `asserted.onPlane`
 * is false, `type` still drives the vocabulary rows, and the JVM tick still
 * shows, so the differentiator reads "not on the plane yet" rather than "no
 * facts".
 */
class LcncBlipWire(
    private val network: ReteNetwork,
    private val kif: (pattern: String) -> List<Map<String, String>>,
    private val productions: () -> List<ReteProduction>,
) {
    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        if (method != "GET" || path.substringBefore('?') != "/api/lcnc/blip") return null
        val q = query(path)
        val program = q["program"].orEmpty()
        val node = q["node"].orEmpty()
        val type = q["type"].orEmpty()
        if (node.isEmpty() && type.isEmpty()) return json(mapOf("error" to "node or type required"), 400)

        val snapshot = network.snapshot()
        val panels = snapshot.filter { it.factId.partitionId == PlaneFacts.PANELS && it.fields[PlaneFacts.KEY] == program }
        val nodeFacts = panels.filter { f ->
            when (f.fields[PlaneFacts.KIND]) {
                PanelFacts.KIND_NODE -> f.fields["node"] == node
                PanelFacts.KIND_CABLE -> f.fields["fromNode"] == node || f.fields["toNode"] == node
                else -> false
            }
        }
        val violations = panels.filter { f ->
            f.fields[PlaneFacts.KIND] == PanelFacts.KIND_VIOLATION && (f.fields["fromNode"] == node || f.fields["toNode"] == node)
        }
        val nodeType = type.ifEmpty { nodeFacts.firstOrNull { it.fields[PlaneFacts.KIND] == PanelFacts.KIND_NODE }?.fields?.get("type")?.toString().orEmpty() }

        val vocabulary = if (nodeType.isEmpty()) emptyList() else listOf(
            "(inKind $nodeType ?port ?kind)", "(outKind $nodeType ?port ?kind)", "(cardinality $nodeType ?port ?card)",
            "(function $nodeType ?port ?fn)", "(source $nodeType)", "(sink $nodeType)",
        ).mapNotNull { pattern ->
            val rows = runCatching { kif(pattern) }.getOrDefault(emptyList())
            if (rows.isEmpty()) null else mapOf("pattern" to pattern, "bindings" to rows)
        }

        val prods = productions().map { pr ->
            val interests = (0 until pr.interests.size).map { pr.interests[it] }
            val matched = nodeFacts.any { f -> interests.any { (field, value) -> f.fields[field] == value } }
            linkedMapOf(
                "ruleId" to pr.ruleId, "salience" to pr.salience,
                "interests" to interests.map { "${it.a}=${it.b}" }, "matched" to matched,
            )
        }

        val needles = listOf(program, node, nodeType).filter { it.isNotEmpty() }
        val graal = snapshot.filter { it.factId.partitionId == PlaneFacts.GRAAL }
        val keyed = graal.filter { f ->
            f.fields.values.any { v -> v is String && needles.any { n -> v == n || v.contains("/$n") || v.contains("$n/") || v.contains("#$n") } }
        }.take(24)
        val jvm = graal.filter { f ->
            val k = f.fields[PlaneFacts.KIND]
            k == "memory" || k == "gc" || k == "jit"
        }

        return json(
            linkedMapOf(
                "program" to program, "node" to node, "type" to nodeType,
                "asserted" to linkedMapOf("onPlane" to nodeFacts.isNotEmpty(), "facts" to nodeFacts.map(::row)),
                "inferred" to linkedMapOf("violations" to violations.map(::row), "vocabulary" to vocabulary, "productions" to prods),
                "graal" to linkedMapOf("keyed" to keyed.map(::row), "jvm" to jvm.map(::row)),
            ),
        )
    }

    private fun row(f: ReteStoredFact): Map<String, Any?> = linkedMapOf(
        "id" to f.factId.localId,
        "fields" to f.fields.mapValues { (_, v) -> if (v == null || v is String || v is Number || v is Boolean) v else v.toString() },
    )

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            decode(k) to decode(v)
        }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

    private fun json(value: Any?, status: Int = 200): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))
}
