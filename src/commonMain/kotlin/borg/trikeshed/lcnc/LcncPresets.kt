package borg.trikeshed.lcnc

import borg.trikeshed.lib.j
import borg.trikeshed.kanban.KanbanCondition
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
        "preset-pairs" to pairsDemo(),
        "preset-brain-mux" to brainMux(),
        "preset-media" to mediaDemo(),
        "preset-hermes-train" to hermesTrain(),
        "preset-legal-tribunal" to legalTribunal(),
        "preset-state-freeze" to stateFreeze(),
        "preset-council" to council(),
        "preset-subvm-audit" to subvmAudit(),
    )

    // ── legal council: the default 3x5 convening, fully drawn ────────────
    // The can and the atoms are the same substance: this preset IS
    // CouncilProgram.build(DEFAULT_3x5) verbatim — byte-identical to what
    // the pure council.convene node emits for an empty config
    // (CouncilPresetIdentityTest pins the identity in both directions).
    private fun council(): String =
        LcncProgramConfix.toJson(CouncilProgram.build(CouncilConfig.DEFAULT_3x5))

    // ── The sub-VM supply chain, made answerable on the surface.
    //
    // A daemon that mounts guest classpaths executes code from them. Until this preset the only
    // account of WHICH classpaths, and whether their bytes still matched what was resolved, lived
    // in a KDoc — an operator could read the comment, not the machine. Here the audit and the work
    // sit on one canvas: vm.modules reports the mounted classpaths and re-hashes them against
    // MANIFEST.tsv, and vm.corenlp.extract does real extraction out of the very module that was
    // just verified.
    //
    // The security property is the composition, not a check inside either node. vm.modules
    // declares NO inputs and cannot mount, unmount or install — there is no port through which
    // that could be expressed — so an audit cannot be steered into becoming an action. The
    // extractor reaches CoreNLP only through a mounted module whose loader is parented to the
    // platform loader, so it cannot name borg.trikeshed.* even at OWN trust. Neither property is a
    // promise in a comment; both are consequences of what LcncContracts declares.
    private fun subvmAudit(): String {
        val program = LcncProgram(
            name = "preset-subvm-audit",
            nodes = listOf(
                LcncNode("audit", SubVm.LEGO_PREFIX + "modules",
                    params = mapOf("verify" to "true"), x = 40.0, y = 60.0),
                LcncNode("report", LcncContracts.SCOPE_OUT,
                    params = mapOf("name" to "supply-chain"), x = 320.0, y = 60.0),
                LcncNode("extract", SubVm.LEGO_PREFIX + "corenlp.extract",
                    params = mapOf(
                        "text" to "Stanford CoreNLP runs in a mounted guest module. TrikeShed never links it.",
                    ), x = 40.0, y = 220.0),
                LcncNode("facts", LcncContracts.SCOPE_OUT,
                    params = mapOf("name" to "extracted"), x = 320.0, y = 220.0),
                LcncNode("note", "note",
                    params = mapOf("text" to
                        "the sub-VM supply chain, answerable.\n\n" +
                        "vm.modules (verify=true) re-hashes every jar on every mounted\n" +
                        "classpath against the MANIFEST.tsv it was resolved from, and\n" +
                        "reports root, mount lifecycle, drift. it declares NO inputs and\n" +
                        "no mutating output: an audit that cannot be steered into an action.\n\n" +
                        "vm.corenlp.extract then works out of that same verified module.\n" +
                        "CoreNLP is NOT on this daemon's classpath — 472MB of GPL v3 that\n" +
                        "src/ never calls. it is mounted per-guest, parented to the platform\n" +
                        "loader, so the guest can name edu.stanford.nlp.* and java.* and\n" +
                        "cannot name borg.trikeshed.* even at OWN trust.\n\n" +
                        "crack the can: both nodes are legos in the drawer, and the whole\n" +
                        "capability of each is its row in LcncContracts."),
                    x = 40.0, y = 400.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("audit", "modules", "report", "value"),
                LcncWire("extract", "sentences", "facts", "value"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 1.0),
            seq = 11,
        )
        return LcncProgramConfix.toJson(program)
    }

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

    // ── the LIST widget demo: key/model pairs authored in rows, emitted as
    // json; a mux.chat seat beside it carries the same widget on its own
    // `models` param — the lawyer/agent/worker/scribe/curator seats all do.
    private fun pairsDemo(): String {
        val program = LcncProgram(
            name = "preset-pairs",
            nodes = listOf(
                LcncNode("n1", "list.pairs",
                    params = mapOf("pairs" to
                        """[{"key":"nv-deepseek-v4-pro","model":"deepseek-ai/deepseek-v4-pro"},{"key":"zai","model":"glm-5.2"}]"""),
                    x = 40.0, y = 60.0),
                LcncNode("n2", "display", x = 340.0, y = 60.0),
                LcncNode("n3", "mux.chat",
                    params = mapOf("system" to "You are counsel.", "maxTokens" to "400",
                        "models" to """[{"key":"nv-glm-52","model":"z-ai/glm-5.2"}]"""),
                    x = 40.0, y = 420.0),
                LcncNode("n4", "note",
                    params = mapOf("text" to "list widgets: rows of key/model pairs.\n＋ row adds, ✕ removes, fields edit inline;\ndrag any value out of a result tree and\ndrop it on the list (or any param) to fill.\nthe mux.chat seat carries the same widget\non its models param — every seat does."),
                    x = 340.0, y = 420.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "pairs", "n2", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 1.0),
            seq = 5,
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
                    params = mapOf("expr" to "{jobId:x.itemId,toColumn:x.to,expectedRevision:x.item.revision,beforeJobId:x.beforeJobId,idempotencyKey:'panel-'+x.itemId+'-'+x.item.revision+'-'+x.to+'-'+(x.beforeJobId||'end')}"),
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
                // The judge's clarification escape: bounded at 1 (a single
                // extra round, distinct from the counsel-level rebut-argue
                // bound), guarded by TribunalPredicates.NEEDS_CLARIFICATION
                // so it only fires when the verdict says the record is
                // insufficient — silent no-op otherwise.
                KanbanEdge(
                    "deliberate-clarify", "deliberate", "argue",
                    mode = KanbanEdgeMode.LOOP, maxIterations = 1,
                    condition = KanbanCondition(TribunalPredicates.NEEDS_CLARIFICATION),
                ),
            ).toSeries(),
        )
        val program = LcncProgram(
            name = "preset-tribunal",
            nodes = listOf(
                LcncNode("n2", "mux.chat", params = mapOf("job" to "argue", "prompt" to "", "brief" to "brief", "system" to "You are counsel for the motion. Argue briefly.", "maxTokens" to "400"), x = 250.0, y = 80.0),
                LcncNode("n3", "mux.chat", params = mapOf("job" to "rebut", "prompt" to "", "system" to "You are opposing counsel. Rebut point by point.", "maxTokens" to "400"), x = 490.0, y = 200.0),
                LcncNode("n4", "mux.chat", params = mapOf("job" to "deliberate", "prompt" to "", "system" to "You are the judge. Weigh the record and rule.", "maxTokens" to "600"), x = 730.0, y = 120.0),
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

    // ── brain-mux: MVP keymux recall, modelmux recall, model action ──
    // Three lanes: keys.status → display (what keys exist),
    //   mux.models → display (what models are routable),
    //   credential.enter → prompt.chat → result.confirm (action demo).
    // The graph IS the BrainClient decomposition — no single god-class.
    // ── the patch-panel player: url arrives on a text WIRE (text.value →
    // media.player.url); play/stop/rewind are GENERIC BUTTONS patched in —
    // the button doesn't know it means "play", the wire does; volume is a
    // GENERIC SLIDER patched into `volume?`; `ended` fires downstream into
    // display. Nothing internal — every control is an external cable.
    private fun mediaDemo(): String {
        val program = LcncProgram(
            name = "preset-media",
            nodes = listOf(
                LcncNode("n1", "text.value",
                    params = mapOf("value" to "https://interactive-examples.mdn.mozilla.net/media/cc0-audio/t-rex-roar.mp3"),
                    x = 30.0, y = 60.0),
                LcncNode("n2", "media.player", x = 320.0, y = 40.0),
                LcncNode("n3", "display", x = 1080.0, y = 60.0),
                LcncNode("bplay", "button", params = mapOf("label" to "play"), x = 30.0, y = 220.0),
                LcncNode("bstop", "button", params = mapOf("label" to "stop"), x = 190.0, y = 220.0),
                LcncNode("brew", "button", params = mapOf("label" to "rewind"), x = 350.0, y = 220.0),
                LcncNode("svol", "slider",
                    params = mapOf("label" to "volume", "min" to "0", "max" to "1", "step" to "0.05", "value" to "0.8"),
                    x = 30.0, y = 340.0),
                LcncNode("n4", "note",
                    params = mapOf("text" to "the player is a PATCH PANEL: url rides a text wire;\nplay/stop/rewind are GENERIC buttons — the button\nknows nothing, the WIRE means \"play\"; volume is a\nGENERIC slider patched into volume?. state flows\nout as json, ended fires downstream. nothing internal."),
                    x = 30.0, y = 460.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "value", "n2", "url"),
                LcncWire("bplay", "press", "n2", "play?"),
                LcncWire("bstop", "press", "n2", "stop?"),
                LcncWire("brew", "press", "n2", "rewind?"),
                LcncWire("svol", "value", "n2", "volume?"),
                LcncWire("n2", "state", "n3", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 1.0),
            seq = 5,
        )
        return LcncProgramConfix.toJson(program)
    }

    private fun brainMux(): String {
        val program = LcncProgram(
            name = "preset-brain-mux",
            nodes = listOf(
                LcncNode("k3", "note",
                    params = mapOf("text" to "keymux recall —\nprefill dropdown resolves keys from\nenv, hermes .env, auth.json, harness.\nno key = no route for that provider."),
                    x = 30.0, y = 60.0),
                LcncNode("m3", "note",
                    params = mapOf("text" to "modelmux recall —\nfill model in prompt.chat.\neach model has caps (chat, conflict-resolve).\nrouting goes through KeyMux → provider."),
                    x = 30.0, y = 280.0),
                LcncNode("c1", "credential.enter",
                    params = mapOf(
                        "key_type" to "nvidia",
                        "url" to "https://integrate.api.nvidia.com/v1",
                        "api_type" to "openai",
                        "key" to "",
                    ), x = 400.0, y = 60.0),
                LcncNode("p1", "prompt.chat",
                    params = mapOf(
                        "prefill" to "nvidia",
                        "url" to "https://integrate.api.nvidia.com/v1",
                        "key" to "",
                        "headers" to "[]",
                        "model" to "nvidia/nemotron-3-super-120b-a12b",
                        "maxTokens" to "128",
                        "temperature" to "0.3",
                        "prompt" to "Say hello in one sentence.",
                    ), x = 400.0, y = 340.0),
                LcncNode("d1", "result.confirm", x = 400.0, y = 600.0),
                LcncNode("n1", "note",
                    params = mapOf("text" to "model action —\nprefill: select daemon-known provider\nor fill URL + key (password) manually.\nheaders: k-v pairs (name, value).\nOK patchcable = green card.\nERROR patchcable = red card.\nHTX: 200 → RESPONSE_OK,\nnon-200 → RESPONSE_ERROR."),
                    x = 700.0, y = 340.0),
            ).toSeries(),
            wires = listOf(
                // c1/p1 are independent showcases (credential entry vs. model
                // action) — no wire between them: credential.enter's output is
                // structured json, prompt.chat's prompt? port is text, and
                // prompt.chat already draws credentials from its own params.
                LcncWire("p1", "content", "d1", "content"),
                LcncWire("p1", "ok", "d1", "ok"),
                LcncWire("p1", "error", "d1", "error"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.75),
            seq = 10,
        )
        return LcncProgramConfix.toJson(program)
    }


    // ── hermes-train: the NARS bag training preset ────────────────────
    // timer → curator.feed → [corenlp span-hints] → construction.propose
    // → construction.gate → nal.decay → nal.recall → display.
    // Extends the curator loop with belief-bag minting and decay.

    private fun hermesTrain(): String {
        val program = LcncProgram(
            name = "preset-hermes-train",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "60"), x = 30.0, y = 60.0),
                LcncNode("n2", "beliefs.introspect", x = 250.0, y = 60.0),
                LcncNode("n3", "beliefs.review", x = 470.0, y = 60.0),
                LcncNode("n4", "read.construct",
                    params = mapOf("maxTokens" to "1024"), x = 700.0, y = 60.0),
                LcncNode("n5", "nal.decay", x = 940.0, y = 60.0),
                LcncNode("n6", "nal.recall",
                    params = mapOf("mode" to "top", "k" to "16"), x = 1160.0, y = 60.0),
                LcncNode("n7", "display", x = 1400.0, y = 60.0),
                LcncNode("n8", "note", params = mapOf("text" to
                    "preset-hermes-train\n" +
                    "timer(60s) -> curator.feed -> beliefs.review\n" +
                    "-> read.construct (bot proposes, gate disposes)\n" +
                    "-> nal.decay (AttentionEconomy pulse)\n" +
                    "-> nal.recall (top-k) -> display.\n" +
                    "Evidence comes from outcome markers:\n" +
                    "CONSOLIDATE/PRUNE/PATCH/DEPRECATE/PASS/FAIL."),
                    x = 470.0, y = 300.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "field", "n3", "facts"),
                LcncWire("n3", "landed", "n4", "lines"),
                LcncWire("n4", "aggregates", "n5", "after?"),
                LcncWire("n5", "decayed", "n6", "trigger?"),
                LcncWire("n6", "beliefs", "n7", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 0.65),
            seq = 8,
        )
        return LcncProgramConfix.toJson(program)
    }

    // ── legal-tribunal: extended preset-tribunal for legal review ─────
    // legal.ingest (LLM propose + grounding gate) → kif.assert
    // → sparql.query(evidence-bank) → tribunal LOOP/JOIN
    // → kg.ingest → display.
    // Extends preset-tribunal with legal system prompts and
    // evidence-bank injection via SparqlKifMcpServer tools.

    private fun legalTribunal(): String {
        val kanban = KanbanGraph(
            boardId = "legal-tribunal",
            lanes = listOf(
                KanbanLane("brief", "Brief of the legal matter", 0, "legal", outputs = mapOf("brief" to "work")),
                KanbanLane("argue", "Argue for the motion", 1, "legal", inputs = mapOf("brief" to "work", "work" to "work", "result" to "result"), outputs = mapOf("result" to "result")),
                KanbanLane("rebut", "Rebut the argument", 2, "opposing", inputs = mapOf("result" to "result"), outputs = mapOf("result" to "result")),
                KanbanLane("deliberate", "Deliberate on the record", 3, "judge", inputs = mapOf("result" to "result")),
                KanbanLane("mistrial", "Terminal — proceedings void", 4, "judge"),
            ).toSeries(),
            edges = listOf(
                KanbanEdge("brief-argue", "brief", "argue"),
                KanbanEdge("argue-rebut", "argue", "rebut"),
                KanbanEdge("rebut-argue", "rebut", "argue", mode = KanbanEdgeMode.LOOP, maxIterations = 3),
                KanbanEdge("join-deliberate", "rebut", "deliberate", mode = KanbanEdgeMode.JOIN, group = "record", requiredBranches = 2),
                KanbanEdge("argue-joins-record", "argue", "deliberate", mode = KanbanEdgeMode.JOIN, group = "record", requiredBranches = 2),
                KanbanEdge("abort-mistrial", "argue", "mistrial", mode = KanbanEdgeMode.ABORT),
                // The judge's clarification escape: bounded at 1 (a single
                // extra round, distinct from the counsel-level rebut-argue
                // bound), guarded by TribunalPredicates.NEEDS_CLARIFICATION
                // so it only fires when the verdict says the record is
                // insufficient — silent no-op otherwise.
                KanbanEdge(
                    "deliberate-clarify", "deliberate", "argue",
                    mode = KanbanEdgeMode.LOOP, maxIterations = 1,
                    condition = KanbanCondition(TribunalPredicates.NEEDS_CLARIFICATION),
                ),
            ).toSeries(),
        )
        val program = LcncProgram(
            name = "preset-legal-tribunal",
            nodes = listOf(
                LcncNode("n1", "legal.ingest",
                    params = mapOf("maxTokens" to "2048", "brief" to "brief"),
                    x = 30.0, y = 60.0),
                LcncNode("n1b", "legal.evidence", x = 190.0, y = 60.0),
                LcncNode("n2", "mux.chat", params = mapOf(
                    "job" to "argue",
                    "system" to "You are counsel for the motion in a legal proceeding. Argue based on the evidence and legal standards provided. Cite specific statutes and cases. Apply the relevant standard of proof (preponderance, clear and convincing, or beyond reasonable doubt as appropriate).",
                    "maxTokens" to "600"),
                    x = 350.0, y = 60.0),
                LcncNode("n3", "mux.chat", params = mapOf(
                    "job" to "rebut",
                    "system" to "You are opposing counsel. Rebut the argument point by point, citing contradictory evidence, distinguishable precedent, or statutory defenses. Challenge the applicability of cited authority.",
                    "maxTokens" to "600"),
                    x = 650.0, y = 200.0),
                LcncNode("n4", "mux.chat", params = mapOf(
                    "job" to "deliberate",
                    "system" to "You are the presiding judge. Weigh the record, apply the relevant legal standard, and rule. If the record is insufficient for a ruling, state what clarification is needed and route back to counsel.",
                    "maxTokens" to "800"),
                    x = 950.0, y = 60.0),
                LcncNode("n5", "kg.ingest", x = 1200.0, y = 60.0),
                LcncNode("n6", "display", x = 1440.0, y = 60.0),
                LcncNode("n7", "note", params = mapOf("text" to
                    "preset-legal-tribunal\n" +
                    "legal.ingest (LLM propose + grounding gate)\n" +
                    "-> legal.evidence (queries the shared KIF bank for\n" +
                    "   doc_<cid> facts, folds them into the brief)\n" +
                    "-> argue <-> rebut (LOOP <=3, MAD shape)\n" +
                    "-> deliberate (JOIN 2) -> kg.ingest -> display.\n" +
                    "ABORT edge = mistrial."),
                    x = 450.0, y = 380.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "documentCid", "n1b", "documentCid?"),
                LcncWire("n1", "brief", "n1b", "brief?"),
                LcncWire("n1b", "brief", "n2", "prompt?"),
                LcncWire("n2", "content", "n3", "prompt?"),
                LcncWire("n3", "content", "n4", "prompt?"),
                LcncWire("n4", "content", "n5", "text?"),
                LcncWire("n5", "report", "n6", "x"),
            ).toSeries(),
            controls = LcncConfixControls(humanOversight = true),
            kanban = kanban,
            view = LcncView(x = 20.0, y = 10.0, zoom = 0.5),
            seq = 9,
        )
        return LcncProgramConfix.toJson(program)
    }


    // ── state-freeze: persist bag + KIF + RdfGraph to CAS ────────────
    // trigger → state.freeze → display.  The freeze node snapshots the
    // belief bag, serializes the KIF knowledge base and RdfGraph to
    // Turtle, and stores everything in CAS with a receipt CID.

    private fun stateFreeze(): String {
        val program = LcncProgram(
            name = "preset-state-freeze",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "300"), x = 30.0, y = 60.0),
                LcncNode("n2", "state.freeze", x = 280.0, y = 60.0),
                LcncNode("n3", "display", x = 520.0, y = 60.0),
                LcncNode("n4", "note", params = mapOf("text" to
                    "preset-state-freeze\n" +
                    "timer(300s) -> state.freeze -> display.\n" +
                    "Snapshots belief bag, KIF KB, RdfGraph\n" +
                    "to CAS. Receipt CID returned."),
                    x = 280.0, y = 240.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "snapshot", "n3", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 1.0),
            seq = 5,
        )
        return LcncProgramConfix.toJson(program)
    }
}
