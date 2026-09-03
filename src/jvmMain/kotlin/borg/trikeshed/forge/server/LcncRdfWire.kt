package borg.trikeshed.forge.server

import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncPresets
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lcnc.LcncRdf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.narsese.EternalRule
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.rdf.TurtleRdf

/**
 * LcncRdfWire — the canvas as RDF, both directions, and its ALIGNMENT.
 *
 *  - `GET  /api/lcnc/rdf`          the vocabulary (LcncRdf.ontology) as Turtle
 *  - `POST /api/lcnc/rdf`          a program document → its graph as Turtle
 *  - `POST /api/lcnc/rdf/program`  Turtle in the vocabulary → a program document
 *  - `POST /api/lcnc/rdf/align`    the program joined with the three systems
 *                                  that speak about the same terms:
 *      productions  — Rete rules whose alpha interests name a node's type, id,
 *                     port, or param key ([ReteProduction.interests]);
 *      causal rules — CausalityRete eternal rules whose antecedent/consequent
 *                     mention the term;
 *      facts        — KIF facts in the curator bank that mention the term;
 *    plus the program's causal projection (every cable as `lcnc:causes`), the
 *    Turtle that `/api/beliefs/kg` turns into implication beliefs.
 *
 * Nothing here is authored: the ontology is a projection of the contracts, the
 * graph a projection of the program, and the alignment a join on IRIs and
 * terms. The three suppliers are the daemon's live registries.
 */
class LcncRdfWire(
    private val productions: () -> List<ReteProduction>,
    private val causalRules: () -> List<EternalRule>,
    private val facts: (pattern: String) -> List<Map<String, String>>,
) {
    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && p == "/api/lcnc/rdf" -> {
                val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                val triples = ArrayList(LcncRdf.ontology())
                query["program"]?.let { name ->
                    LcncPresets.all()[name]?.let { doc -> triples.addAll(LcncRdf.graph(LcncProgramConfix.fromJson(name, doc))) }
                }
                turtle(LcncRdf.turtle(triples))
            }

            method == "POST" && p == "/api/lcnc/rdf" -> {
                val program = programFrom(text) ?: return json(mapOf("error" to "a program document (nodes, wires) is required"), 400)
                duplicateIds(program)?.let { return it }
                turtle(LcncRdf.turtle(LcncRdf.graph(program)))
            }

            method == "POST" && p == "/api/lcnc/rdf/program" -> {
                val body = rawBody(text)
                if (body.isBlank()) return json(mapOf("error" to "empty body"), 400)
                val graph = runCatching { TurtleRdf.parse(body) }.getOrElse { return json(mapOf("error" to "turtle did not parse: ${it.message}"), 400) }
                val program = LcncRdf.programOf(graph, "rdf")
                if (program.nodes.size == 0) return json(mapOf("error" to "no typed nodes in ${LcncRdf.TYPE_NS}"), 400)
                JvmKanbanServer.HttpResponse(200, LcncProgramConfix.toJson(program))
            }

            method == "POST" && p == "/api/lcnc/rdf/align" -> {
                val program = programFrom(text) ?: return json(mapOf("error" to "a program document (nodes, wires) is required"), 400)
                duplicateIds(program)?.let { return it }
                json(align(program))
            }

            else -> null
        }
    }

    // ── the join ────────────────────────────────────────────────────────

    private fun align(program: LcncProgram): Map<String, Any?> {
        val nodes = ArrayList<LcncNode>()
        fun walk(ns: List<LcncNode>) { for (n in ns) { nodes.add(n); val kids = ArrayList<LcncNode>(); for (j in 0 until n.children.size) kids.add(n.children[j]); walk(kids) } }
        val top = ArrayList<LcncNode>(); for (j in 0 until program.nodes.size) top.add(program.nodes[j]); walk(top)
        val prods = runCatching { productions() }.getOrDefault(emptyList())
        val rules = runCatching { causalRules() }.getOrDefault(emptyList())
        val feeds = HashMap<String, MutableList<String>>()
        for (j in 0 until program.wires.size) {
            val w = program.wires[j]
            feeds.getOrPut(w.fromNode) { ArrayList() }.add("${w.fromNode}#${w.fromPort} → ${w.toNode}#${w.toPort.removeSuffix("?")}")
        }
        var watched = 0; var causal = 0; var factual = 0
        val rows = nodes.map { n ->
            val terms = buildList {
                add(n.type); add(n.id)
                borg.trikeshed.lcnc.LcncContracts.find(n.type)?.let { c -> addAll(c.inputs.map { it.removeSuffix("?") }); addAll(c.outputs) }
                addAll(n.params.keys)
            }.filter { it.isNotBlank() }.distinct()
            val watchedBy = prods.filter { pr ->
                val ints = pr.interests
                (0 until ints.size).any { i ->
                    val f = ints[i].a; val v = ints[i].b?.toString()
                    f in terms || (v != null && v in terms) || f in n.params.keys && (v == null || n.params[f] == v)
                }
            }.map { "${it.ruleId} (salience ${it.salience})" }
            val mentions = { s: String -> terms.any { t -> t.length > 2 && s.contains(t) } }
            val causalHits = rules.filter { mentions(it.antecedent) || mentions(it.consequent) }.map { "${it.antecedent} ==> ${it.consequent}" }
            val factHits = terms.take(6).flatMap { t ->
                runCatching { facts("(?p $t ?o)") + facts("(?p ?s $t)") }.getOrDefault(emptyList())
            }.map { m -> m.entries.joinToString(" ") { "${it.key}=${it.value}" } }.distinct().take(12)
            if (watchedBy.isNotEmpty()) watched++
            if (causalHits.isNotEmpty()) causal++
            if (factHits.isNotEmpty()) factual++
            linkedMapOf(
                "id" to n.id, "type" to n.type,
                "watchedBy" to watchedBy,
                "causalRules" to causalHits.take(12),
                "facts" to factHits,
                "feeds" to feeds[n.id].orEmpty(),
            )
        }
        val nal = LcncRdf.turtle(LcncRdf.causalProjection(program))
        return linkedMapOf(
            "summary" to "${nodes.size} nodes · ${program.wires.size} cables · ${prods.size} productions registered, $watched nodes watched · ${rules.size} causal rules, $causal nodes mentioned · facts on $factual nodes",
            "nodes" to rows,
            "productions" to prods.map { pr ->
                val ints = pr.interests
                linkedMapOf("ruleId" to pr.ruleId, "salience" to pr.salience, "interests" to (0 until ints.size).map { "${ints[it].a}=${ints[it].b}" })
            },
            "causalRuleCount" to rules.size,
            "nal" to nal,
        )
    }

    // ── plumbing ────────────────────────────────────────────────────────

    /**
     * A node id IS its IRI, so two nodes sharing an id land on one subject and
     * the graph silently merges them — a note and a text literal both called
     * `n1` came back as one chimera carrying both params. Refused up front,
     * naming the ids, so the canvas shows the collision instead of losing a node.
     */
    private fun duplicateIds(program: LcncProgram): JvmKanbanServer.HttpResponse? {
        val dups = borg.trikeshed.lcnc.LcncTypeCheck.check(program, strict = false)
            .filter { it.rule == "duplicate-node-id" }.map { it.fromNode }.distinct()
        if (dups.isEmpty()) return null
        return json(mapOf("error" to "duplicate node ids — two nodes on one IRI would merge: ${dups.joinToString()}", "duplicates" to dups), 400)
    }

    private fun programFrom(text: String): LcncProgram? {
        val body = rawBody(text)
        if (body.isBlank()) return null
        val parsed = runCatching { JsonSupport.parse(body) as? Map<*, *> }.getOrNull() ?: return null
        val name = parsed["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "canvas"
        return runCatching { LcncProgramConfix.fromJson(name, body) }.getOrNull()
    }

    private fun turtle(body: String): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(200, body, contentType = "text/turtle; charset=utf-8")

    private fun json(value: Any?, status: Int = 200): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))

    private fun rawBody(text: String): String = when {
        "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
        "\n\n" in text -> text.substringAfter("\n\n")
        else -> text
    }
}
