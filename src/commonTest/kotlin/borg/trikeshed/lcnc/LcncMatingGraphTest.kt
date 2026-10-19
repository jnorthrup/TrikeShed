package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlin.test.*

/** Current graph evidence, occupied sockets and ring ownership govern offered edits. */
class LcncMatingGraphTest {
    @Test fun aFedYieldOffersOnlyItsResolvedKindAndCanCreateAMate() {
        val ring = LcncNode("ring", "scope", children = s_[
            LcncNode("text", "text.value", mapOf("value" to "/workspace")),
            LcncNode("yield", "scope.out", mapOf("name" to "path")),
        ])
        val program = LcncProgram("yield", s_[ring, LcncNode("mount", "project.mount")],
            s_[LcncWire("text", "value", "yield", "value")])
        assertFalse(LcncMating.genericSource(program, "ring", "path"))
        val options = LcncMating.compatibleTypes(program, "ring", "path")
        assertTrue(options.any { it.type == "project.mount" && it.inputPort == "path?" })
        assertTrue(options.none { it.type == "list.format" })
        assertEquals(LcncWire("ring", "path", "mount", "path?"),
            LcncMating.autoWire(program, "ring", "mount").wire)
        val mated = LcncMating.mate(program, "ring", "path", "project.mount", 480.0, 20.0)
        assertEquals("path", mated.wire.fromPort)
        assertTrue(LcncTypeCheck.check(mated.program).isEmpty())
    }

    @Test fun aFedTypeVariableKeepsItsKindInTheCandidateQuery() {
        val program = LcncProgram("coalesce", s_[
            LcncNode("text", "text.value"), LcncNode("choice", "coalesce"),
            LcncNode("mount", "project.mount"),
        ], s_[LcncWire("text", "value", "choice", "a?")])
        assertFalse(LcncMating.genericSource(program, "choice", "value"))
        assertEquals(LcncWire("choice", "value", "mount", "path?"),
            LcncMating.autoWire(program, "choice", "mount").wire)
        assertTrue(LcncMating.compatibleTypes(program, "choice", "value").none { it.type == "list.format" })
    }

    @Test fun aNestedFormalReadsTheEnclosingBindingBeforeOfferingAnEdit() {
        val program = LcncProgram("formal", s_[
            LcncNode("text", "text.value"),
            LcncNode("ring", "scope", children = s_[
                LcncNode("formal", "scope.in", mapOf("name" to "path")),
                LcncNode("mount", "project.mount"),
            ]),
        ], s_[LcncWire("text", "value", "ring", "path")])
        assertFalse(LcncMating.genericSource(program, "formal", "value"))
        assertEquals(LcncWire("formal", "value", "mount", "path?"),
            LcncMating.autoWire(program, "formal", "mount").wire)
        assertTrue(LcncMating.compatibleTypes(program, "formal", "value").none { it.type == "list.format" })
    }

    @Test fun optionalPortSpellingCannotReopenAnOccupiedSocketOrDuplicateACable() {
        val program = LcncProgram("occupied", s_[
            LcncNode("timer", "timer"), LcncNode("http", "http.get"),
            LcncNode("other", "http.get"), LcncNode("pick", "pick"), LcncNode("display", "display"),
        ], s_[
            LcncWire("timer", "tick", "http", "trigger"),
            LcncWire("http", "json?", "display", "x"),
        ])
        assertTrue(LcncMating.autoWire(program, "timer", "http").candidates.isEmpty())
        assertTrue(LcncMating.autoWire(program, "http", "pick").candidates.isEmpty())
        assertTrue(LcncMating.autoWire(program, "http", "display").candidates.isEmpty())
        assertEquals(LcncWire("timer", "tick", "other", "trigger?"),
            LcncMating.autoWire(program, "timer", "other").wire)
    }

    @Test fun ambiguityStaysExplicitAndIterationIsNeverAnInferredConnection() {
        val program = LcncProgram("ambiguous", s_[
            LcncNode("json", "json.value"), LcncNode("board", "dom.board"),
            LcncNode("ring", "scope", children = s_[LcncNode("item", "scope.in", mapOf("name" to "item"))]),
        ], emptySeriesOf())
        val board = LcncMating.autoWire(program, "json", "board")
        assertNull(board.wire)
        assertTrue(board.candidates.size > 1)
        val ring = LcncMating.autoWire(program, "json", "ring")
        assertNull(ring.wire)
        assertTrue(ring.candidates.none { it.toPort.removeSuffix("?") == "each" })
        assertTrue(ring.candidates.any { it.toPort == "args?" })
    }

    @Test fun anInnerOutputCannotBeOfferedAcrossItsRingBoundary() {
        val program = LcncProgram("boundary", s_[
            LcncNode("ring", "scope", children = s_[LcncNode("text", "text.value")]),
            LcncNode("mount", "project.mount"),
        ], emptySeriesOf())
        val result = LcncMating.autoWire(program, "text", "mount")
        assertNull(result.wire)
        assertTrue(result.candidates.isEmpty())
        assertEquals(0, program.wires.size)
    }

    @Test fun aSecondTargetInstanceHasItsOwnInputCardinality() {
        val program = LcncProgram("instances", s_[
            LcncNode("timer", "timer"), LcncNode("http", "http.get"),
        ], s_[LcncWire("timer", "tick", "http", "trigger?")])
        val mated = LcncMating.mate(program, "timer", "tick", "http.get", 400.0, 0.0)
        assertNotEquals("http", mated.wire.toNode)
        assertEquals(2, mated.program.wires.size)
        assertEquals(2, mated.program.nodes.view.count { it.type == "http.get" })
    }

    @Test fun aNestedMateRemainsInItsRingAndRespectsAllAllocatedIds() {
        val program = LcncProgram("ids", s_[
            LcncNode("ring", "scope", children = s_[
                LcncNode("timer", "timer"), LcncNode("n9", "display"),
                LcncNode("deeper", "scope", children = s_[LcncNode("n10", "display")]),
            ]),
        ], emptySeriesOf(), seq = 9)
        val mated = LcncMating.mate(program, "timer", "tick", "http.get", 500.0, 20.0)
        val ring = mated.program.nodes[0]
        assertEquals(1, mated.program.nodes.size)
        assertEquals(4, ring.children.size)
        assertTrue(ring.children.view.any { it.id == mated.wire.toNode && it.type == "http.get" })
        assertTrue(mated.wire.toNode !in setOf("n9", "n10"))
        val number = mated.wire.toNode.removePrefix("n").toInt()
        assertTrue(number >= program.seq)
        assertTrue(mated.program.seq > number)
        assertTrue(LcncTypeCheck.check(mated.program).isEmpty())
        assertEquals(3, program.nodes[0].children.size)
    }
}
