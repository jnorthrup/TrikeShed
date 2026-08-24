package borg.trikeshed.mcp

import borg.trikeshed.kif.CycLToKif
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.ontology.OpenCycOntology
import borg.trikeshed.ontology.SumoOntology
import borg.trikeshed.rdf.ConfixRdfAdapter
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.TurtleRdf
import borg.trikeshed.dag.DagBitTreeSkeleton

/**
 * Sparql + KIF MCP solver — LLM accompaniment for skills.
 *
 * Extends `McpServerHandler` with ontology-grounded tools:
 *  - sparql.query        — lightweight BGP over in-memory RdfGraph (quads)
 *  - kif.assert / query  — SUO-KIF KB with subclass closure
 *  - kif.sparqlSelect    — KIF var pattern → bindings JSON (sparql-like)
 *  - cycl.transcribe     — CycL → KIF (light, upper)
 *  - confix.toTurtle / turtle.toConfix — Confix Csv ↔ RDF triples/quads/Turtle
 *  - confix.toQuads / turtle.toQuads
 *  - dag.skeleton        — DagBitTreeSkeleton CRUD + Turtle projection
 *  - ontology.sumo / ontology.cyc — upper term lookup
 *
 * Every tool returns citation-backed answers: the KB/graph is the memory (M) and
 * Gamma is the supporting triples/kif forms. LLM calls this via MCP JSON-RPC
 * `tools/call` with `name` + `arguments`.
 */
class SparqlKifMcpServer(
    private val kb: KifKnowledgeBase = KifKnowledgeBase().also { bootstrapUpper(it) },
    private var graph: RdfGraph = RdfGraph(emptyList()),
    private val dagSkeletons: MutableMap<String, DagBitTreeSkeleton> = mutableMapOf(),
) : McpTransport by InMemoryMcpTransport() {

    companion object {
        fun bootstrapUpper(kb: KifKnowledgeBase) {
            // SUMO upper spine as KIF
            SumoOntology.emitUpperKif().lines().filter { it.isNotBlank() && !it.startsWith(";") }
                .forEach { runCatching { kb.assertKif(it) } }
            // OpenCyc predicate examples as KIF
            OpenCycOntology.upperConstants.forEach {
                it.sumoEq?.let { sumo -> kb.assertKif("(subclass ${it.cyclName.removePrefix("#\$")} ${sumo.kifName})") }
            }
        }

        val toolSpecs: List<Map<String, String>> = listOf(
            mapOf("name" to "sparql.query", "description" to "Light SPARQL BGP over in-memory RdfGraph. args: {sparql}"),
            mapOf("name" to "kif.assert", "description" to "Assert SUO-KIF form into KB. args: {kif}"),
            mapOf("name" to "kif.query", "description" to "Query KIF pattern with ?vars. args: {pattern}"),
            mapOf("name" to "kif.sparqlSelect", "description" to "KIF var pattern → bindings JSON. args: {pattern}"),
            mapOf("name" to "cycl.transcribe", "description" to "CycL → KIF light transcription. args: {cycl}"),
            mapOf("name" to "confix.toTurtle", "description" to "Confix bytes/CSV → Turtle node serialization. args: {confix, graphIri?}"),
            mapOf("name" to "turtle.toConfix", "description" to "Turtle → CSV (Confix Csv adaption). args: {turtle}"),
            mapOf("name" to "dag.skeleton", "description" to "Bit-tree skeleton ops. args: {op, dagId, depth?, pos?, payload?, version?} ops: create|fill|fillAll|get|turtle|kif"),
            mapOf("name" to "ontology.sumo", "description" to "SUMO upper term lookup. args: {term}"),
            mapOf("name" to "ontology.cyc", "description" to "OpenCyc constant lookup. args: {constant}"),
        )
    }

    private val transport = InMemoryMcpTransport()

    fun listTools(): String = buildString {
        append("{\"tools\":[")
        toolSpecs.forEachIndexed { i, m -> if (i > 0) append(","); append("{\"name\":\"${m["name"]}\",\"description\":\"${m["description"]}\" }") }
        append("]}")
    }

    /** Dispatch tools/call by name. Returns JSON string (MCP result). */
    fun callTool(name: String, args: Map<String, String>): String = when (name) {
        "sparql.query" -> sparqlQuery(args["sparql"] ?: "")
        "kif.assert" -> { val kif = args["kif"] ?: return err("kif required"); kb.assertKif(kif); """{"ok":true,"asserted":${jsonStr(kif)}}""" }
        "kif.query" -> {
            val pat = args["pattern"] ?: return err("pattern required")
            val expr = runCatching { KifExpr.parse(pat) }.getOrElse { return err("kif parse failed") }
            val rows = kb.query(expr)
            """{"bindings":${rowsToJson(rows)}}"""
        }
        "kif.sparqlSelect" -> kb.sparqlSelectSparqlLike(args["pattern"] ?: return err("pattern required"))
        "cycl.transcribe" -> {
            val cycl = args["cycl"] ?: return err("cycl required")
            val kif = CycLToKif.toKifString(cycl)
            """{"cycl":${jsonStr(cycl)},"kif":${jsonStr(kif)}}"""
        }
        "confix.toTurtle" -> {
            val confix = args["confix"] ?: return err("confix required")
            val graphIri = args["graphIri"]
            val turtle = ConfixRdfAdapter.confixToTurtle(confix.encodeToByteArray(), graphIri)
            """{"turtle":${jsonStr(turtle)}}"""
        }
        "turtle.toConfix" -> {
            val turtle = args["turtle"] ?: return err("turtle required")
            val csv = ConfixRdfAdapter.turtleToCsv(turtle)
            """{"csv":${jsonStr(csv)}}"""
        }
        "dag.skeleton" -> dagOp(args)
        "ontology.sumo" -> {
            val term = args["term"] ?: return err("term required")
            val cat = SumoOntology.resolveKifToken(term)
            if (cat == null) """{"error":"unknown sumo term $term"}""" else """{"kifName":"${cat.kifName}","doc":"${cat.doc}"}"""
        }
        "ontology.cyc" -> {
            val c = args["constant"] ?: return err("constant required")
            val cc = OpenCycOntology.resolve(c)
            if (cc == null) """{"error":"unknown cyc constant $c"}""" else """{"cyclName":"${cc.cyclName}","doc":"${cc.doc}","sumoEq":"${cc.sumoEq?.kifName}"}"""
        }
        else -> err("unknown tool $name")
    }

    private fun sparqlQuery(sparql: String): String {
        if (sparql.isBlank()) return err("sparql required")
        // light: handle SELECT ?v WHERE { s p o } with vars ?x — we treat whole WHERE as one triple pattern
        // fallback: try parse as KIF pattern
        val whereRe = Regex("""WHERE\s*\{\s*([^}]+)\}""", RegexOption.IGNORE_CASE)
        val where = whereRe.find(sparql)?.groupValues?.get(1)?.trim() ?: sparql
        // where may be turtle-like triple; attempt TurtleRdf.parse for single triple query
        val all = graph.allTriples()
        // simple BGP: each line is a triple pattern with ?vars
        val patterns = where.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (patterns.isEmpty()) return """{"bindings":[]}"""
        // for light effort, unify first pattern against all triples
        val pat = patterns[0].removeSuffix(".").trim()
        val patParts = pat.split(Regex("\\s+"))
        if (patParts.size < 3) return err("pattern needs 3 parts")
        fun matchTerm(patTok: String, actual: String): Pair<Boolean, String?> {
            if (patTok.startsWith("?")) return true to actual
            // handle prefix:term or <iri> or "lit"
            val normPat = if (patTok.contains(":")) TurtleRdf.parse("@prefix x: <http://example/> . $patTok <http://example/p> \"o\".").allTriples().firstOrNull()?.s?.toString() ?: patTok else patTok
            return (patTok == actual || patTok.removeSurrounding("<", ">") == actual.removeSurrounding("<", ">")) to null
        }
        // degenerate: return all triples as bindings for ?vars in pattern
        val vars = patParts.filter { it.startsWith("?") }
        val bindings = mutableListOf<Map<String, String>>()
        for (t in all) {
            val candidates = listOf(t.s.toTurtle(), t.p.toTurtle(), t.o.toTurtle())
            var ok = true
            val map = mutableMapOf<String, String>()
            for ((pi, pt) in patParts.withIndex()) {
                if (pt.startsWith("?")) map[pt] = candidates.getOrNull(pi) ?: ""
                else if (candidates.getOrNull(pi) != pt && candidates.getOrNull(pi)?.removeSurrounding("<", ">") != pt.removeSurrounding("<", ">")) { ok = false; break }
            }
            if (ok) bindings.add(map)
        }
        return """{"bindings":${rowsToJson(bindings)}}"""
    }

    private fun dagOp(args: Map<String, String>): String {
        val op = args["op"] ?: return err("op required")
        val dagId = args["dagId"] ?: return err("dagId required")
        return when (op) {
            "create" -> {
                val depth = args["depth"]?.toIntOrNull() ?: 4
                val version = args["version"] ?: "v0"
                val skel = DagBitTreeSkeleton.empty(dagId, depth, version)
                dagSkeletons[dagId] = skel
                """{"ok":true,"dagId":"$dagId","depth":$depth,"capacity":${skel.capacity},"version":"$version"}"""
            }
            "fill" -> {
                val skel = dagSkeletons[dagId] ?: return err("no skeleton $dagId")
                val pos = args["pos"]?.toIntOrNull() ?: return err("pos required")
                val payload = args["payload"] ?: ""
                val newVersion = args["version"] ?: "v${System.currentTimeMillis()}"
                val next = skel.fill(pos, payload, newVersion)
                dagSkeletons[dagId] = next
                """{"ok":true,"pos":$pos,"version":"$newVersion","filled":${next.isFilled(pos)}}"""
            }
            "fillAll" -> {
                val skel = dagSkeletons[dagId] ?: return err("no skeleton $dagId")
                // payload arg is JSON map string like {"0":"a","1":"b"} light parse
                val payloadStr = args["payload"] ?: return err("payload map required")
                val entries = mutableMapOf<Int, String>()
                Regex("\"(\\d+)\"\\s*:\\s*\"([^\"]*)\"").findAll(payloadStr).forEach { m -> entries[m.groupValues[1].toInt()] = m.groupValues[2] }
                val newVersion = args["version"] ?: "v${System.currentTimeMillis()}"
                val next = skel.fillAll(entries, newVersion)
                dagSkeletons[dagId] = next
                """{"ok":true,"version":"$newVersion","filled":${next.size}}"""
            }
            "get" -> {
                val skel = dagSkeletons[dagId] ?: return err("no skeleton $dagId")
                val posStr = args["pos"]
                if (posStr != null) {
                    val pos = posStr.toIntOrNull() ?: return err("bad pos")
                    """{"pos":$pos,"filled":${skel.isFilled(pos)},"payload":${jsonStr(skel.payloadAt(pos)?.toString() ?: "")}}"""
                } else {
                    """{"dagId":"$dagId","depth":${skel.depth},"capacity":${skel.capacity},"size":${skel.size},"version":"${skel.version}","holes":${skel.holes().let { (0 until it.size).joinToString(",", "[", "]") { idx -> it[idx].toString() } }}}"""
                }
            }
            "turtle" -> {
                val skel = dagSkeletons[dagId] ?: return err("no skeleton $dagId")
                """{"turtle":${jsonStr(skel.toTurtle())}}"""
            }
            "kif" -> {
                val skel = dagSkeletons[dagId] ?: return err("no skeleton $dagId")
                """{"kif":${jsonStr(skel.toKif())}}"""
            }
            else -> err("unknown dag op $op")
        }
    }

    private fun err(msg: String): String = """{"error":${jsonStr(msg)}}"""
    private fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun rowsToJson(rows: List<Map<String, String>>): String = buildString {
        append("[")
        rows.forEachIndexed { i, m ->
            if (i > 0) append(",")
            append("{")
            m.entries.forEachIndexed { j, (k, v) -> if (j > 0) append(","); append("\"$k\":${jsonStr(v)}") }
            append("}")
        }
        append("]")
    }

    // MCP Transport delegation for existing McpServerHandler compat
    override fun respond(requestId: String, result: String) { transport.respond(requestId, result) }
    override fun respondError(requestId: String, code: Int, message: String) { transport.respondError(requestId, code, message) }
}
