package borg.trikeshed.lcnc

import borg.trikeshed.lib.j
import borg.trikeshed.kanban.KanbanEdge
import borg.trikeshed.kanban.KanbanEdgeMode
import borg.trikeshed.kanban.KanbanGraph
import borg.trikeshed.kanban.KanbanLane
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * W6.2: a composition IS a stored program. These are the pre-assembled
 * orchestrations, authored once in Kotlin and OFFERED as Confix documents —
 * PRESETS ARE NEVER INSTALLED. The live forge home (`~/.local/forge`) is
 * production; a preset reaches execution only through the stored-program
 * resolver (ModuleContext.programLoader → /api/lcnc/run).
 *
 * Every node type here exists in [LcncContracts.all()] — the presets gate
 * guards this file's vocabulary against the one author.
 */
object LcncPresets {

    /** name → Confix JSON document, the exact shape LcncProgramConfix parses. */
    fun all(): Map<String, String> = linkedMapOf(
        "preset-hermes" to hermes(),
        "preset-tribunal" to tribunal(),
        "preset-curator" to curator(),
        "preset-context" to context(),
        "preset-kanban" to kanban(),
        "preset-scope" to scopeDemo(),
        "preset-scope-inner" to scopeInner(),
    )

    // ── The concentric machine demo: three rings, ONE document. The root
    // scope.in's default binds; its value is consumed TWO rings deep with
    // zero re-plumbing (the wire crosses inward — the warm base); yields
    // climb out explicitly ring by ring through scope.out — the asymmetry
    // made visible. Runs through /api/lcnc/run with zero registered runners.

    private fun scopeDemo(): String {
        val program = LcncProgram(
            name = "preset-scope",
            nodes = listOf(
                LcncNode("n0", LcncContracts.SCOPE_IN,
                    params = mapOf("name" to "text", "default" to "hello"), x = 40.0, y = 60.0),
                LcncNode("r1", LcncContracts.SCOPE, x = 260.0, y = 40.0,
                    children = listOf(
                        LcncNode("r2", LcncContracts.SCOPE, x = 40.0, y = 40.0,
                            children = listOf(
                                LcncNode("p", LcncContracts.SCOPE_OUT,
                                    params = mapOf("name" to "result"), x = 40.0, y = 40.0),
                            ).toSeries()),
                        LcncNode("q", LcncContracts.SCOPE_OUT,
                            params = mapOf("name" to "result"), x = 260.0, y = 40.0),
                    ).toSeries()),
                LcncNode("out", LcncContracts.SCOPE_OUT,
                    params = mapOf("name" to "result"), x = 540.0, y = 60.0),
                LcncNode("n3", "note",
                    params = mapOf("text" to "the concentric machine, three rings, one document.\nn0 (scope.in, default=hello) is consumed TWO rings deep\nby a single wire — inner sees outer, zero re-plumbing.\nyields climb out ring by ring through scope.out —\nonly the yield crosses; locals die at their ring."),
                    x = 40.0, y = 260.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n0", "value", "p", "value"),
                LcncWire("r2", "result", "q", "value"),
                LcncWire("r1", "result", "out", "value"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 1.0),
            seq = 7,
        )
        return LcncProgramConfix.toJson(program)
    }

    private fun scopeInner(): String {
        val program = LcncProgram(
            name = "preset-scope-inner",
            nodes = listOf(
                LcncNode("p1", LcncContracts.SCOPE_IN,
                    params = mapOf("name" to "text", "default" to "hello"), x = 40.0, y = 60.0),
                LcncNode("p2", LcncContracts.SCOPE_OUT,
                    params = mapOf("name" to "result"), x = 320.0, y = 60.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("p1", "value", "p2", "value"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 1.0),
            seq = 3,
        )
        return LcncProgramConfix.toJson(program)
    }

    // ── Step 5: the kanban board AS an LCNC composition — dogfood proof ──
    // Every node is a generic primitive; the kanban-specific knowledge
    // (endpoints, field names, the move command's shape) lives entirely in
    // wiring + params. Drag a card on the dom.board: the drop only DESCRIBES
    // the gesture — the js + http.post nodes decide what it means and fire
    // the real /api/invoke move. The second lane is the step-5 data spine:
    // /api/lcnc/kanban — the concentric activeSheets projection, visible raw.

    private fun kanban(): String {
        val program = LcncProgram(
            name = "preset-kanban",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "5"), x = 30.0, y = 60.0),
                // ONE server projection feeds everything: the concentric sheet family
                // (kanban.activeSheets executes in the daemon via /api/lcnc/run).
                LcncNode("n2", "kanban.activeSheets", x = 230.0, y = 60.0),
                // The concentric treesheet view — board sheet at the center, byStatus/
                // byPriority partitions one ring out, orchestration lanes outermost;
                // SheetRef cells drill in, the crumb climbs back.
                LcncNode("n3", "sheet.concentric", x = 500.0, y = 30.0),
                // The gesture surface stays generic: boardView → items/columns →
                // grouped draggable board.
                LcncNode("n4", "pick", params = mapOf("path" to "items"), x = 500.0, y = 420.0),
                LcncNode("n5", "pick", params = mapOf("path" to "columns"), x = 500.0, y = 560.0),
                LcncNode("n6", "list.groupBy", params = mapOf("key" to "status"), x = 720.0, y = 420.0),
                LcncNode("n7", "dom.board",
                    params = mapOf("idField" to "id", "titleField" to "title", "subtitleField" to "id", "badgeField" to "priority"),
                    x = 940.0, y = 450.0),
                // The drop DESCRIBES the gesture; js shapes it into a command map and
                // kanban.move (the daemon's own runner — the same one webhook dispatch
                // and /api/lcnc/kanban/move resolve) lands it on the WAL.
                LcncNode("n8", "js",
                    params = mapOf("expr" to "{jobId:x.itemId,toColumn:x.to,expectedRevision:x.item.revision,idempotencyKey:'panel-'+x.itemId+'-'+x.item.revision+'-'+x.to}"),
                    x = 1660.0, y = 420.0),
                LcncNode("n9", "kanban.move", x = 1880.0, y = 420.0),
                // The no-wire submit lane: type a title, click run — a real card lands.
                LcncNode("n10", "kanban.submit",
                    params = mapOf("jobId" to "", "title" to "", "priority" to "2", "idempotencyKey" to ""),
                    x = 230.0, y = 640.0),
                LcncNode("n11", "note",
                    params = mapOf("text" to "kanban as LCNC — the board is a COMPOSITION.\ncenter: kanban.activeSheets → sheet.concentric,\nthe concentric treesheets (board · byStatus ·\nbyPriority · orchestration) with SheetRef drill-in.\nbelow: the draggable gesture surface — a drop only\nDESCRIBES the gesture; js + kanban.move decide what\nit means and land it on the WAL.\nkanban.submit: fill params, run — a real card."),
                    x = 30.0, y = 300.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "board", "n3", "board"),
                LcncWire("n2", "byStatus", "n3", "byStatus?"),
                LcncWire("n2", "byPriority", "n3", "byPriority?"),
                LcncWire("n2", "orchestration", "n3", "orchestration?"),
                LcncWire("n2", "boardView", "n4", "x"),
                LcncWire("n2", "boardView", "n5", "x"),
                LcncWire("n4", "y", "n6", "x"),
                LcncWire("n6", "groups", "n7", "groups"),
                LcncWire("n5", "y", "n7", "columns?"),
                LcncWire("n7", "move", "n8", "x"),
                LcncWire("n8", "y", "n9", "command?"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.75),
            seq = 12,
        )
        return LcncProgramConfix.toJson(program)
    }

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

    // ── Step K: the context policy as an auditable program ───────────────
    // Frames in change-frequency order; the fold is deterministic code (LLM
    // proposes bullets, never rewrites the base); running assemble mints
    // context-receipt/<chainHead> — the blackboard chunk pane's data.

    private fun context(): String {
        val program = LcncProgram(
            name = "preset-context",
            nodes = listOf(
                LcncNode("n1", "context.fold", x = 30.0, y = 60.0),
                LcncNode("n2", "context.assemble",
                    params = mapOf("model" to "", "effort" to "medium", "tools" to ""),
                    x = 300.0, y = 60.0),
                LcncNode("n3", "display", x = 570.0, y = 60.0),
                LcncNode("n4", "note", params = mapOf("text" to "adaptive context preset\nbullets → fold (deterministic merge)\n→ assemble (rolling cache-identity chain)\nchainHead = provable cache prefix.\ncounters live in the belief bag, never in frame bytes."), x = 300.0, y = 280.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "playbook", "n2", "playbook?"),
                LcncWire("n2", "chain", "n3", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 0.9),
            seq = 5,
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
