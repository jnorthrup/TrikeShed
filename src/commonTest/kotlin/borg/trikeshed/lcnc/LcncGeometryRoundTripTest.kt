package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W2.4 gate: geometry round-trips completely. `collapsed`, `view`, and `seq`
 * round-trip through LcncProgramConfix so Kotlin owns the whole document —
 * panels.html no longer re-attaches view/seq after every mate.
 */
class LcncGeometryRoundTripTest {

    @Test
    fun collapsedStateRoundTrips() {
        val node = LcncNode("n1", "timer", x = 10.0, y = 20.0, collapsed = true)
        val program = LcncProgram("test", listOf(node).toSeries(), emptySeriesOf())

        val json = LcncProgramConfix.toJson(program)
        val reparsed = LcncProgramConfix.fromJson("test", json)

        assertEquals(1, reparsed.nodes.size)
        assertTrue(reparsed.nodes[0].collapsed, "collapsed=true must survive round-trip")
    }

    @Test
    fun expandedStateIsDefaultWhenAbsent() {
        // A document without "collapsed" (legacy shape) parses to false, not a crash.
        val json = """{"nodes":[{"id":"n1","type":"timer","x":1,"y":2,"params":{}}],"wires":[]}"""
        val program = LcncProgramConfix.fromJson("legacy", json)
        assertEquals(false, program.nodes[0].collapsed)
    }

    @Test
    fun viewportRoundTripsExactly() {
        val node = LcncNode("n1", "timer")
        val program = LcncProgram(
            "viewtest",
            listOf(node).toSeries(),
            emptySeriesOf(),
            view = LcncView(x = -123.5, y = 480.25, zoom = 1.75),
            seq = 7,
        )

        val json = LcncProgramConfix.toJson(program)
        val reparsed = LcncProgramConfix.fromJson("viewtest", json)

        assertNotNull(reparsed.view, "view survives toJson/fromJson")
        assertEquals(-123.5, reparsed.view.x)
        assertEquals(480.25, reparsed.view.y)
        assertEquals(1.75, reparsed.view.zoom)
        assertEquals(7, reparsed.seq, "seq survives round-trip")
    }

    @Test
    fun absentViewParsesToNullWithDefaultSeq() {
        val json = """{"nodes":[],"wires":[]}"""
        val program = LcncProgramConfix.fromJson("noview", json)
        assertNull(program.view, "missing view is null, not a fabricated default camera")
        assertEquals(1, program.seq, "missing seq defaults to 1")
    }

    @Test
    fun fullDocumentShapeMatchesBrowserSerialize() {
        // The exact shape panels.html's serialize() produces today.
        val browserDoc = """
            {"version":5,
             "view":{"x":60,"y":60,"z":1},
             "nodes":[
               {"id":"n1","type":"timer","x":30,"y":60,"width":null,"height":null,
                "params":{"seconds":"5"},"collapsed":false},
               {"id":"n2","type":"http.get","x":250,"y":60,"width":null,"height":null,
                "params":{"path":"/api/board"},"collapsed":true}
             ],
             "wires":[{"from":["n1","tick"],"to":["n2","trigger?"]}],
             "controls":{"humanOversight":true,"matingPoints":[]},
             "seq":3}
        """.trimIndent()

        val program = LcncProgramConfix.fromJson("browser", browserDoc)
        assertEquals(2, program.nodes.size)
        assertEquals(1, program.wires.size)
        assertEquals(false, program.nodes[0].collapsed)
        assertTrue(program.nodes[1].collapsed, "browser-collapsed node parses true")
        assertEquals(3, program.seq)
        assertNotNull(program.view)
        assertEquals(60.0, program.view.x)
        assertEquals(1.0, program.view.zoom)

        // And Kotlin writes back a document the browser can load again.
        val rewritten = LcncProgramConfix.toJson(program)
        val reread = LcncProgramConfix.fromJson("browser", rewritten)
        assertEquals(program.nodes.size, reread.nodes.size)
        assertEquals(program.wires.size, reread.wires.size)
        assertEquals(program.seq, reread.seq)
        assertEquals(program.nodes[1].collapsed, reread.nodes[1].collapsed)
        assertEquals(program.view, reread.view)
    }
}
