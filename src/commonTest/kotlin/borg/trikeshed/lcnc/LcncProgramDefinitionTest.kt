package borg.trikeshed.lcnc

import borg.trikeshed.kanban.KanbanGraph
import borg.trikeshed.lib.*
import kotlin.test.*

class LcncProgramDefinitionTest {
    val leaf = LcncNode("source", "text.value", mapOf("value" to "雪", "x" to "domain parameter"))
    val ring = LcncNode("ring", "scope", children = s_[leaf, LcncNode("out", "scope.out")])
    val program = LcncProgram("review", s_[ring, LcncNode("display", "display")],
        s_[LcncWire("source", "value", "out", "value")], view = LcncView(1.0, 2.0, 0.5))

    @Test fun recursivePresentationChangesDoNotChangeTheDefinitionOrTheOriginalDocument() {
        val before = LcncProgramConfix.toJson(program)
        val moved = program.copy(view = LcncView(80.0, 90.0, 2.0), nodes = s_[
            ring.copy(x = 300.0, y = 400.0, width = 500.0, height = 600.0, collapsed = true,
                children = s_[leaf.copy(x = 70.0, y = 90.0, width = 120.0, height = 130.0, collapsed = true), ring.children[1]]),
            program.nodes[1].copy(x = 1354.0),
        ])
        assertTrue(LcncProgramConfix.sameDefinition(program, moved))
        assertTrue(LcncProgramConfix.sameDefinition(moved, program))
        assertNotEquals(LcncBlackboard.cidOf(program), LcncBlackboard.cidOf(moved), "receipt identity must retain layout")
        assertEquals(before, LcncProgramConfix.toJson(program))
    }

    @Test fun executableAndControlEditsAreNeverClassifiedAsPresentation() {
        val edits = listOf(
            program.copy(nodes = s_[ring.copy(children = s_[leaf.copy(params = leaf.params + ("value" to "changed")), ring.children[1]]), program.nodes[1]]),
            program.copy(nodes = s_[ring.copy(children = s_[leaf.copy(params = leaf.params + ("x" to "changed")), ring.children[1]]), program.nodes[1]]),
            program.copy(nodes = s_[ring.copy(type = "loop"), program.nodes[1]]),
            program.copy(nodes = s_[ring.copy(subprogram = "other"), program.nodes[1]]),
            program.copy(nodes = s_[ring.copy(id = "other"), program.nodes[1]]),
            program.copy(nodes = s_[ring.copy(children = s_[ring.children[1], leaf]), program.nodes[1]]),
            program.copy(nodes = s_[program.nodes[1], ring]),
            program.copy(wires = s_[program.wires[0].copy(toPort = "other")]),
            program.copy(controls = program.controls.copy(humanOversight = false)),
            program.copy(controls = program.controls.copy(inspectionOnly = true)),
            program.copy(controls = program.controls.copy(matingPoints = s_[LcncPatchMatingPoint("m", "source", "value", "out", "value")])),
            program.copy(kanban = KanbanGraph.hermesDefault()),
            program.copy(seq = 2),
        )
        for ((index, edited) in edits.withIndex())
            assertFalse(LcncProgramConfix.sameDefinition(program, edited), "non-presentation edit $index")
    }

    @Test fun wireOrderAndKanbanStateRemainPartOfTheDefinition() {
        val wired = program.copy(wires = s_[program.wires[0], LcncWire("source", "value", "display", "x")],
            kanban = KanbanGraph.hermesDefault())
        assertFalse(LcncProgramConfix.sameDefinition(wired, wired.copy(wires = s_[wired.wires[1], wired.wires[0]])))
        assertFalse(LcncProgramConfix.sameDefinition(wired, wired.copy(kanban = wired.kanban!!.copy(boardId = "other"))))
    }

    @Test fun objectKeyOrderDoesNotClaimADefinitionEdit() {
        val reordered = program.copy(nodes = s_[ring.copy(children = s_[
            leaf.copy(params = linkedMapOf("x" to "domain parameter", "value" to "雪")), ring.children[1],
        ]), program.nodes[1]])
        assertTrue(LcncProgramConfix.sameDefinition(program, reordered))
    }
}
