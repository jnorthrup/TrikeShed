package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import borg.trikeshed.job.ContentId

/**
 * LcncProgram <-> Confix, no JavaScript anywhere in the loop. A stored
 * program is a plain JSON document — `{nodes:[{id,type,params,x,y}],
 * wires:[{from:[id,port],to:[id,port]}]}` — the exact shape `panels.html`
 * already wrote to `panels/<name>` via `PatchWire`/`CouchAttachmentGateway`.
 * This reads and writes the SAME documents from pure Kotlin: [ProgramNavigator]
 * can dive into a program stored by the old JS canvas, or one that never
 * touched a browser at all.
 */
object LcncProgramConfix {

    fun toJson(program: LcncProgram): String {
        val nodes = (0 until program.nodes.size).map { i ->
            val n = program.nodes[i]
            linkedMapOf(
                "id" to n.id,
                "type" to n.type,
                "params" to n.params,
                "x" to n.x,
                "y" to n.y,
                "width" to n.width,
                "height" to n.height,
                "collapsed" to n.collapsed,
                "subprogram" to n.subprogram,
            )
        }
        val wires = (0 until program.wires.size).map { i ->
            val w = program.wires[i]
            linkedMapOf(
                "from" to listOf(w.fromNode, w.fromPort),
                "to" to listOf(w.toNode, w.toPort),
            )
        }
        val matingPoints = (0 until program.controls.matingPoints.size).map { i ->
            val p = program.controls.matingPoints[i]
            linkedMapOf("id" to p.id, "fromNode" to p.fromNode, "fromPort" to p.fromPort, "toNode" to p.toNode, "toPort" to p.toPort, "cardinality" to p.cardinality.name, "function" to p.function)
        }
        return JsonSupport.stringify(linkedMapOf(
            "nodes" to nodes,
            "wires" to wires,
            "controls" to linkedMapOf("humanOversight" to program.controls.humanOversight, "matingPoints" to matingPoints),
            "kanban" to program.kanban?.let { JsonSupport.parse(borg.trikeshed.kanban.KanbanGraphConfix.toJson(it)) },
            "view" to program.view?.let { linkedMapOf("x" to it.x, "y" to it.y, "z" to it.zoom) },
            "seq" to program.seq,
        ))
    }

    /** Tolerant of the shapes JSON parsing actually produces (Double vs Int, missing optional fields). */
    fun fromJson(name: String, json: String): LcncProgram {
        val parsed = JsonSupport.parse(json) as? Map<*, *> ?: error("not a program document: $name")
        val nodes = ((parsed["nodes"] as? List<*>).orEmpty()).mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"]?.toString() ?: return@mapNotNull null
            val type = m["type"]?.toString() ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val params = (m["params"] as? Map<String, Any?>)?.mapValues { it.value?.toString() ?: "" } ?: emptyMap()
            LcncNode(id, type, params, num(m["x"]), num(m["y"]), optionalNum(m["width"]), optionalNum(m["height"]),
                collapsed = m["collapsed"] as? Boolean ?: false,
                subprogram = m["subprogram"]?.toString())
        }
        val wires = ((parsed["wires"] as? List<*>).orEmpty()).mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val from = m["from"] as? List<*> ?: return@mapNotNull null
            val to = m["to"] as? List<*> ?: return@mapNotNull null
            if (from.size != 2 || to.size != 2) return@mapNotNull null
            LcncWire(from[0].toString(), from[1].toString(), to[0].toString(), to[1].toString())
        }
        val controls = (parsed["controls"] as? Map<*, *>)?.let { c ->
            val points = ((c["matingPoints"] as? List<*>).orEmpty()).mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                runCatching {
                    LcncPatchMatingPoint(
                        id = m["id"].toString(), fromNode = m["fromNode"].toString(), fromPort = m["fromPort"].toString(),
                        toNode = m["toNode"].toString(), toPort = m["toPort"].toString(),
                        cardinality = runCatching { LcncCardinality.valueOf(m["cardinality"]?.toString() ?: "ONE") }.getOrDefault(LcncCardinality.ONE),
                        function = m["function"]?.toString() ?: "identity",
                    ).validate()
                }.getOrNull()
            }.toSeries()
            LcncConfixControls(c["humanOversight"] as? Boolean ?: true, points)
        } ?: LcncConfixControls()
        val kanban = (parsed["kanban"] as? Map<*, *>)?.let { borg.trikeshed.kanban.KanbanGraphConfix.fromJson(JsonSupport.stringify(it)) }
        val view = (parsed["view"] as? Map<*, *>)?.let { v ->
            LcncView(num(v["x"]), num(v["y"]), num(v["z"]))
        }
        val seq = (parsed["seq"] as? Number)?.toInt() ?: 1
        return LcncProgram(name, nodes.toSeries(), wires.toSeries(), controls, kanban, view, seq)
    }

    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun optionalNum(v: Any?): Double? = when (v) {
        null -> null
        else -> v.toString().toDoubleOrNull()?.takeIf { it > 0.0 }
    }
}

/**
 * The real, live loader: `ProgramNavigator.diveInto("kanban")` reads
 * `panels/kanban` straight out of Oroboros' CAS-backed attachment store —
 * the SAME document this session's `/api/panels/kanban` already serves —
 * with no HTTP, no serialization boundary crossed twice, no JS runtime
 * anywhere in the call path.
 */
fun oroborosProgramLoader(gateway: CouchAttachmentGateway): suspend (String) -> LcncProgram? = { name ->
    gateway.getAttachment("panels/$name")?.let { (_, bytes) ->
        LcncProgramConfix.fromJson(name, bytes.decodeToString())
    }
}

/** Save a program back to the same store, addressed by its own name. */
fun saveProgramToOroboros(gateway: CouchAttachmentGateway, program: LcncProgram, agentId: String = "lcnc-kotlin") {
    val bytes = LcncProgramConfix.toJson(program).encodeToByteArray()
    val cid = ContentId.of(bytes)
    gateway.putAttachment(
        OroborosAttachmentRef(
            path = "panels/${program.name}",
            contentType = "application/json",
            length = bytes.size.toLong(),
            contentId = cid,
            agentId = agentId,
            revision = cid.hex.take(12),
            sequence = 0L,
        ),
        bytes,
    )
}
