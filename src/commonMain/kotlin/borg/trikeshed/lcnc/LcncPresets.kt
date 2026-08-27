package borg.trikeshed.lcnc

import borg.trikeshed.lib.j
import borg.trikeshed.kanban.KanbanEdge
import borg.trikeshed.kanban.KanbanEdgeMode
import borg.trikeshed.kanban.KanbanGraph
import borg.trikeshed.kanban.KanbanLane
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * W6.2: a composition IS a stored program. These are the three pre-assembled
 * orchestrations, authored once in Kotlin and served as offered documents —
 * PRESETS ARE NEVER INSTALLED. The live forge home (`~/.local/forge`) is
 * production; installing is always an explicit client act (POST to /api/panels/<name>).
 *
 * Every node type here exists in [LcncContracts.all()] — the parity test
 * guards this file's vocabulary exactly like the browser's.
 */
object LcncPresets {

    /** name → Confix JSON document, the exact shape LcncProgramConfix parses. */
    fun all(): Map<String, String> = linkedMapOf(
        "preset-hermes" to hermes(),
        "preset-tribunal" to tribunal(),
        "preset-curator" to curator(),
    )

    // ── W6.4 hermes: bone stock, pure lanes ──────────────────────────────

    private fun hermes(): String {
        val program = LcncProgram(
            name = "preset-hermes",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "15"), x = 30.0, y = 60.0),
                LcncNode("n2", "board.get", x = 250.0, y = 60.0),
                LcncNode("n3", "pick", params = mapOf("path" to "items"), x = 470.0, y = 60.0),
                LcncNode("n4", "list.groupBy", params = mapOf("key" to "status"), x = 690.0, y = 60.0),
                LcncNode("n5", "dom.board",
                    params = mapOf("idField" to "id", "titleField" to "title", "subtitleField" to "id", "badgeField" to "priority"),
                    x = 910.0, y = 60.0),
                LcncNode("n6", "display", x = 1170.0, y = 60.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "json", "n3", "x"),
                LcncWire("n3", "y", "n4", "x"),
                LcncWire("n4", "groups", "n5", "groups"),
                LcncWire("n5", "move", "n6", "x"),
            ).toSeries(),
            view = LcncView(x = 40.0, y = 20.0, zoom = 0.85),
            seq = 7,
        )
        return LcncProgramConfix.toJson(program)
    }

    // ── W6.5 tribunal: proof the substrate is general ────────────────────

    private fun tribunal(): String {
        val kanban = KanbanGraph(
            boardId = "tribunal",
            lanes = listOf(
                KanbanLane("brief", "Brief of the matter", 0, "legal", outputs = mapOf("brief" to "work")),
                KanbanLane("argue", "Argue for the motion", 1, "legal", inputs = mapOf("brief" to "work", "work" to "work", "result" to "result"), outputs = mapOf("result" to "result")),
                KanbanLane("rebut", "Rebut the argument", 2, "opposing", inputs = mapOf("result" to "result"), outputs = mapOf("result" to "result")),
                KanbanLane("deliberate", "Deliberate on the record", 3, "judge", inputs = mapOf("result" to "result")),
                KanbanLane("mistrial", "Terminal — proceedings void", 4, "judge"),
            ).toSeries(),
            edges = listOf(
                KanbanEdge("brief-argue", "brief", "argue"),
                // The argue⇄rebut LOOP: counsel advances the argument, opposing
                // counsel advances the rebuttal, and the LOOP edge closes the
                // cycle (bounded, opt-in — Phase 4).
                KanbanEdge("argue-rebut", "argue", "rebut"),
                KanbanEdge("rebut-argue", "rebut", "argue", mode = KanbanEdgeMode.LOOP, maxIterations = 3),
                KanbanEdge("join-deliberate", "rebut", "deliberate", mode = KanbanEdgeMode.JOIN, group = "record", requiredBranches = 2),
                KanbanEdge("argue-joins-record", "argue", "deliberate", mode = KanbanEdgeMode.JOIN, group = "record", requiredBranches = 2),
                // The unguarded escape: any state → mistrial.
                KanbanEdge("abort-mistrial", "argue", "mistrial", mode = KanbanEdgeMode.ABORT),
            ).toSeries(),
        )
        val program = LcncProgram(
            name = "preset-tribunal",
            nodes = listOf(
                LcncNode("n2", "mux.chat", params = mapOf("prompt" to "", "system" to "You are counsel for the motion. Argue briefly.", "maxTokens" to "400"), x = 250.0, y = 80.0),
                LcncNode("n3", "mux.chat", params = mapOf("prompt" to "", "system" to "You are opposing counsel. Rebut point by point.", "maxTokens" to "400"), x = 490.0, y = 200.0),
                LcncNode("n4", "mux.chat", params = mapOf("prompt" to "", "system" to "You are the judge. Weigh the record and rule.", "maxTokens" to "600"), x = 730.0, y = 120.0),
                LcncNode("n5", "kg.ingest", x = 910.0, y = 120.0),
                LcncNode("n6", "display", x = 1130.0, y = 120.0),
                LcncNode("n7", "note", params = mapOf("text" to "tribunal preset\nbrief → argue ⇄ rebut (LOOP ≤3)\n→ deliberate (JOIN) → rule.\nABORT edge = mistrial."), x = 490.0, y = 380.0),
            ).toSeries(),
            wires = listOf(
                // Human oversight starts the brief; no timer drives counsel.
                LcncWire("n2", "content", "n3", "prompt?"),
                LcncWire("n3", "content", "n4", "prompt?"),
                // The verdict crosses kinds honestly via the ingest seam:
                // chat text → kg.ingest(text? text → report json) → display.
                LcncWire("n4", "content", "n5", "text?"),
                LcncWire("n5", "report", "n6", "x"),
            ).toSeries(),
            controls = LcncConfixControls(humanOversight = true),
            kanban = kanban,
            view = LcncView(x = 20.0, y = 10.0, zoom = 0.8),
            seq = 8,
        )
        return LcncProgramConfix.toJson(program)
    }

    // ── W6.3 curator: the pre-assembled curation loop ────────────────────

    private fun curator(): String {
        val program = LcncProgram(
            name = "preset-curator",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "60"), x = 30.0, y = 60.0),
                LcncNode("n2", "beliefs.introspect", x = 250.0, y = 60.0),
                LcncNode("n3", "beliefs.review", x = 470.0, y = 60.0),
                LcncNode("n5", "display", x = 910.0, y = 60.0),
                LcncNode("n6", "note", params = mapOf("text" to "curator preset — quota-free branch:\nmint → review → tick → render → revise.\nteach via POST /api/beliefs/teach (W5.3)."), x = 470.0, y = 260.0),
            ).toSeries(),
            wires = listOf(
                // Every edge kind-clean: field(json)→facts(json), landed(json)→x(json).
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "field", "n3", "facts"),
                LcncWire("n3", "landed", "n5", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 0.9),
            seq = 7,
        )
        return LcncProgramConfix.toJson(program)
    }
}
