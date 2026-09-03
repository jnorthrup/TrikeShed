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
        LcncProgram("t", nodes.toList().toSeries(), wires.toSeries())

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
        for (path in listOf("/api/lcnc/rdf", "/api/lcnc/rdf/align")) {
            val r = post(path, LcncProgramConfix.toJson(p))
            assertEquals(400, r.status, "$path: ${r.body}")
            @Suppress("UNCHECKED_CAST")
            val m = JsonSupport.parse(r.body) as Map<String, Any?>
            assertEquals(listOf("n1"), m["duplicates"])
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
