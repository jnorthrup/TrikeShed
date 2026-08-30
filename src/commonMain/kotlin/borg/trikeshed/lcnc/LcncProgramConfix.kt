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
 * LcncProgram <-> Confix, no JavaScript anywhere in the loop. ALL DATA IS
 * CONFIX: a stored program is a plain Confix document —
 * `{nodes:[{id,type,params,x,y}], wires:[{from:[id,port],to:[id,port]}]}` —
 * read and written from pure Kotlin. [ProgramNavigator] dives into stored
 * programs; [LcncRunner] executes them; no browser anywhere in the loop
 * (the panel editor and its `panels/` occupancy were rooted out 2026-08-27).
 */
object LcncProgramConfix {

    private fun nodeMap(n: LcncNode): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to n.id,
        "type" to n.type,
        "params" to n.params,
        "x" to n.x,
        "y" to n.y,
        "width" to n.width,
        "height" to n.height,
        "collapsed" to n.collapsed,
        "subprogram" to n.subprogram,
        // Concentric containment: a ring's statements nest INSIDE it — one
        // document, rings inside rings, recursively.
        "children" to (0 until n.children.size).map { nodeMap(n.children[it]) },
    )

    fun toJson(program: LcncProgram): String {
        val nodes = (0 until program.nodes.size).map { i -> nodeMap(program.nodes[i]) }
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

    private fun nodeFrom(raw: Any?): LcncNode? {
        val m = raw as? Map<*, *> ?: return null
        val id = m["id"]?.toString() ?: return null
        val type = m["type"]?.toString() ?: return null
        @Suppress("UNCHECKED_CAST")
        val params = (m["params"] as? Map<String, Any?>)?.mapValues { it.value?.toString() ?: "" } ?: emptyMap()
        val children = childList(m["children"]).mapNotNull { nodeFrom(it) }
        return LcncNode(id, type, params, num(m["x"]), num(m["y"]), optionalNum(m["width"]), optionalNum(m["height"]),
            collapsed = m["collapsed"] as? Boolean ?: false,
            subprogram = m["subprogram"]?.toString(),
            children = children.toSeries())
    }

    /** JsonSupport backends reify arrays as List or Array — accept both. */
    private fun childList(v: Any?): List<*> = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        else -> emptyList<Any?>()
    }

    /** Tolerant of the shapes JSON parsing actually produces (Double vs Int, missing optional fields). */
    fun fromJson(name: String, json: String): LcncProgram {
        val parsed = JsonSupport.parse(json) as? Map<*, *> ?: error("not a program document: $name")
        val nodes = ((parsed["nodes"] as? List<*>).orEmpty()).mapNotNull { raw -> nodeFrom(raw) }
        // A malformed cable is CORRUPT DATA, not an absent one. Dropping it silently
        // meant a document could execute with wires the author wrote and the engine
        // never saw — the same class of lie the type checker exists to end. (Stale
        // cables that name a missing node or port are a different thing: those parse,
        // and LcncTypeCheck names them before the run.)
        val wires = ((parsed["wires"] as? List<*>).orEmpty()).mapIndexed { i, raw ->
            val m = raw as? Map<*, *>
                ?: throw IllegalArgumentException("$name: wire[$i] is not an object")
            val from = m["from"] as? List<*>
                ?: throw IllegalArgumentException("$name: wire[$i] has no from:[node,port]")
            val to = m["to"] as? List<*>
                ?: throw IllegalArgumentException("$name: wire[$i] has no to:[node,port]")
            if (from.size != 2 || to.size != 2) {
                throw IllegalArgumentException("$name: wire[$i] endpoints must be [node,port]")
            }
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
 * `lcnc/kanban` straight out of Oroboros' CAS-backed attachment store —
 * no HTTP, no serialization boundary crossed twice, no JS runtime anywhere
 * in the call path. (The prefix moved `panels/` → `lcnc/` when the panel
 * editor's occupancy was rooted out.)
 */
fun oroborosProgramLoader(gateway: CouchAttachmentGateway): suspend (String) -> LcncProgram? = { name ->
    gateway.getAttachment("lcnc/$name")?.let { (_, bytes) ->
        LcncProgramConfix.fromJson(name, bytes.decodeToString())
    }
}

/** Save a program back to the same store, addressed by its own name. */
fun saveProgramToOroboros(gateway: CouchAttachmentGateway, program: LcncProgram, agentId: String = "lcnc-kotlin") {
    val bytes = LcncProgramConfix.toJson(program).encodeToByteArray()
    val cid = ContentId.of(bytes)
    gateway.putAttachment(
        OroborosAttachmentRef(
            path = "lcnc/${program.name}",
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
