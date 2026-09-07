package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.narsese.KgNalBridge
import borg.trikeshed.narsese.NalCopula
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The RDF wire end to end, with no daemon: program → Turtle → program, the refusal, and the NAL crossing. */
class LcncRdfWireTest {

    private val wire = LcncRdfWire(productions = { emptyList() }, causalRules = { emptyList() }, facts = { emptyList() })

    private fun program(vararg nodes: LcncNode, wires: List<LcncWire> = emptyList()) =
        LcncProgram("t", nodes.toSeries() /* Bolt: Use .toSeries() directly to avoid intermediate List allocation */, wires.toSeries())

    private fun post(path: String, body: String) = runBlocking { wire.route("POST", path, body, null)!! }

    @Test
    fun turtleOutAndProgramBackAgree() {
        val p = program(LcncNode("a", "timer", mapOf("seconds" to "3")), LcncNode("b", "prompt.chat", mapOf("prompt" to "say \"hi\"\nplease")), wires = listOf(LcncWire("a", "tick", "b", "prompt")))
        val ttl = post("/api/lcnc/rdf", LcncProgramConfix.toJson(p))
        assertEquals(200, ttl.status)
        val back = post("/api/lcnc/rdf/program", ttl.body)
        assertEquals(200, back.status, back.body)
        val doc = LcncProgramConfix.fromJson("t", back.body)
        assertEquals(2, doc.nodes.size); assertEquals(1, doc.wires.size)
        assertEquals("say \"hi\"\nplease", (0 until doc.nodes.size).map { doc.nodes[it] }.first { it.id == "b" }.params["prompt"])
    }

    @Test
    fun duplicateNodeIdsAreRefusedNotMerged() {
        val p = program(LcncNode("n1", "note", mapOf("text" to "a note")), LcncNode("n1", "text.value", mapOf("value" to "a literal")))
        for (path in listOf("/api/lcnc/rdf", "/api/lcnc/rdf/align", "/api/lcnc/spacegraph")) {
            val r = post(path, LcncProgramConfix.toJson(p))
            assertEquals(400, r.status, "$path: ${r.body}")
            @Suppress("UNCHECKED_CAST")
            val m = JsonSupport.parse(r.body) as Map<String, Any?>
            assertEquals(listOf("n1"), m["duplicates"])
        }
    }

    @Test
    fun spatialProjectionPreservesTheDocumentAndRejectsInvalidPresentation() {
        val p = program(LcncNode("a", "text.value", mapOf("value" to "keep")))
        val document = JsonSupport.parse(LcncProgramConfix.toJson(p)) as Map<*, *>
        val geometry = mapOf("id" to "a", "x" to 12, "y" to 24, "width" to 200, "height" to 90)
        val result = post("/api/lcnc/spacegraph?alignment=0&width=390&height=600",
            JsonSupport.stringify(document + mapOf("geometry" to listOf(geometry))))
        assertEquals(200, result.status, result.body)
        val body = JsonSupport.parse(result.body) as Map<*, *>
        assertEquals(document, body["document"])
        assertEquals(null, body["alignment"])
        assertEquals(emptyList<Any>(), body["epistemic"])
        val scene = body["scene"] as Map<*, *>
        val measuredNode = (scene["nodes"] as List<*>).single() as Map<*, *>
        assertEquals(true, measuredNode["measured"])
        assertEquals(390.0, ((body["frame"] as Map<*, *>)["width"] as Number).toDouble())
        for (options in listOf(
            mapOf("geometry" to "not an array"),
            mapOf("geometry" to listOf(geometry + mapOf("width" to -1))),
            mapOf("geometry" to listOf(geometry + mapOf("id" to "foreign"))),
            mapOf("camera" to mapOf("position" to listOf(0, 0, 0), "center" to listOf(0, 0, 0))),
            mapOf("spacing" to -1),
        )) {
            val refused = post("/api/lcnc/spacegraph?alignment=0", JsonSupport.stringify(document + options))
            assertEquals(400, refused.status, refused.body)
            assertTrue((JsonSupport.parse(refused.body) as Map<*, *>).containsKey("error"))
        }
    }

    @Test
    fun theAlignmentsNalProjectionBridgesToImplications() {
        val p = program(LcncNode("a", "timer"), LcncNode("b", "prompt.chat"), LcncNode("c", "result.confirm"),
            wires = listOf(LcncWire("a", "tick", "b", "prompt"), LcncWire("b", "content", "c", "content")))
        val r = post("/api/lcnc/rdf/align", LcncProgramConfix.toJson(p))
        assertEquals(200, r.status, r.body)
        @Suppress("UNCHECKED_CAST")
        val nal = (JsonSupport.parse(r.body) as Map<String, Any?>)["nal"] as String
        val mapped = KgNalBridge.bridge(nal)
        assertEquals(5, mapped.size, "3 inheritances + 2 implications, not zero: $nal")
        assertEquals(2, mapped.count { it.copula == NalCopula.IMPLICATION })
        assertEquals(3, mapped.count { it.copula == NalCopula.INHERITANCE })
        assertTrue(mapped.filter { it.copula == NalCopula.IMPLICATION }.all { "%23" in it.triplet.subject && "%23" in it.triplet.obj }, "ports cause ports")
    }
}
