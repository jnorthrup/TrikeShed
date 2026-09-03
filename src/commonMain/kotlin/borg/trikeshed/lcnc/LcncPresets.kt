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
/**
 * What a person needs before a prefab is adoptable — authored in plain words,
 * beside the document it describes.
 *
 * The note nodes inside the presets are written for whoever wrote them
 * (`brief → argue ⇄ rebut (LOOP ≤3)`, `teach via POST /api/beliefs/teach`), and
 * two presets carried no note at all. A person opening the gallery needs four
 * things instead: what this does, what it needs before it will work, what
 * they will see, and the one knob to turn first. No arrows, no route paths,
 * no phase numbers — [LcncPresetCatalogTest] holds that line.
 */
data class LcncPresetInfo(
    val name: String,
    /** Plain-language name — what a person would call it. */
    val title: String,
    /** One sentence: what it does. */
    val does: String,
    /** What must already be true, or "nothing — it runs as it is". */
    val needs: String,
    /** What appears when it runs. */
    val see: String,
    /** The first thing worth changing, named as it appears on the canvas. */
    val tweakFirst: String,
)

object LcncPresets {

    /** The offered prefabs, described for the person adopting them. */
    fun catalog(): List<LcncPresetInfo> = listOf(
        LcncPresetInfo(
            "preset-hermes", "Board at a glance",
            does = "Reads the kanban board every 15 seconds and lays its cards out in columns by status.",
            needs = "Nothing — it runs as it is.",
            see = "Your board's cards, grouped into draggable columns.",
            tweakFirst = "The timer's seconds, to poll faster or slower.",
        ),
        LcncPresetInfo(
            "preset-tribunal", "Three-model debate",
            does = "One model argues a motion, a second rebuts it, a third weighs the record and rules.",
            needs = "A model provider key.",
            see = "Each model's turn in order, then the ruling as structured facts.",
            tweakFirst = "The system prompt on the first chat node — that sets what is being argued.",
        ),
        LcncPresetInfo(
            "preset-curator", "Belief review loop",
            does = "Every minute it reads what the belief store currently holds and runs a review pass over it.",
            needs = "Nothing — it runs as it is.",
            see = "The belief store's current field, and what the review pass landed.",
            tweakFirst = "The timer's seconds.",
        ),
        LcncPresetInfo(
            "preset-context", "Context assembly",
            does = "Folds a list of notes into one stable playbook, then builds the reusable context chain from it.",
            needs = "Nothing — it runs as it is.",
            see = "The folded playbook text and the chain identity it produced.",
            tweakFirst = "The bullets input on the fold node.",
        ),
        LcncPresetInfo(
            "preset-kanban", "The board, built from parts",
            does = "Rebuilds the whole board experience out of generic parts, so every step is visible and changeable.",
            needs = "Nothing — it runs as it is.",
            see = "The same board as the built-in one, with every step of its assembly on the canvas.",
            tweakFirst = "The group-by key, to column the cards by something other than status.",
        ),
        LcncPresetInfo(
            "preset-ccek", "The engine, driven",
            does = "Starts a live engine node, sends it a message, listens to it as an agent, and replays what it recorded.",
            needs = "Nothing — it runs as it is.",
            see = "The message reaching the engine, the agent receiving it, and the running record.",
            tweakFirst = "The signal node's verb and text.",
        ),
        LcncPresetInfo(
            "preset-scope", "Rings inside rings",
            does = "Shows how a value handed to an outer ring is used by a part nested two rings deep.",
            needs = "Nothing — it runs as it is.",
            see = "The value passing inward through each ring and the result coming back out.",
            tweakFirst = "The default value on the outer ring's parameter.",
        ),
        LcncPresetInfo(
            "preset-scope-inner", "A ring body to reuse",
            does = "A small named body other programs can call as a ring, taking one value and returning one.",
            needs = "Nothing — it runs as it is.",
            see = "Little on its own; it is meant to be called from another program.",
            tweakFirst = "The parameter name, which is how callers address it.",
        ),
        LcncPresetInfo(
            "preset-pairs", "Editable list of pairs",
            does = "Keeps rows of name-and-model pairs you can add to, remove and edit in place, and sends one to a model.",
            needs = "A model provider key for the chat node.",
            see = "An editable table of rows and the model's answer.",
            tweakFirst = "The rows themselves — add one with the plus button.",
        ),
        LcncPresetInfo(
            "preset-brain-mux", "Bring your own key",
            does = "Finds which provider keys are already available to this machine, sends a prompt with the one that resolves, and shows the modelmux's strategy, last selection and quota standings.",
            needs = "At least one provider key present in the environment, hermes .env, or saved credentials — nothing needs to be entered when one resolves.",
            see = "Which providers are reachable, the answer to your prompt, and the mux's live meta (strategy, last selection, quota).",
            tweakFirst = "The prompt text.",
        ),
        LcncPresetInfo(
            "preset-media", "Media player from parts",
            does = "Drives a player from separate buttons and a slider, so the controls are ordinary parts on wires.",
            needs = "A browser — media.player is a real <video> element, so this one " +
                "cannot run headless. A media address to play.",
            see = "A player responding to the buttons and slider beside it.",
            tweakFirst = "The address in the text node feeding the player.",
        ),
        LcncPresetInfo(
            "preset-hermes-train", "Learning from past sessions",
            does = "On a timer, feeds recorded sessions through review and lets a proposer suggest what to keep.",
            needs = "A model provider key — read.construct asks a model to propose — " +
                "and a recorded session profile on this machine.",
            see = "What each pass reviewed and which proposals passed the gate.",
            tweakFirst = "The timer's seconds.",
        ),
        LcncPresetInfo(
            "preset-legal-tribunal", "Grounded legal review",
            does = "Takes in a document, checks its claims against stored evidence, then argues and rules on it.",
            needs = "A model provider key and a document to review.",
            see = "The evidence gathered for the document, the argument, and the ruling.",
            tweakFirst = "The document text on the intake node.",
        ),
        LcncPresetInfo(
            "preset-state-freeze", "Snapshot everything",
            does = "Every five minutes it writes the current beliefs and knowledge to permanent storage and reports the receipt.",
            needs = "Nothing — it runs as it is.",
            see = "A receipt naming exactly what was stored.",
            tweakFirst = "The timer's seconds, to snapshot more or less often.",
        ),
        LcncPresetInfo(
            "preset-council", "Full council",
            does = "Runs three panels of five experts over two rounds, then records one ruling with the whole transcript.",
            needs = "A model provider key — this one makes many calls.",
            see = "Each panel's round, the final ruling, and a stored record of both.",
            tweakFirst = "The convene settings, to change how many panels, experts and rounds.",
        ),
        LcncPresetInfo(
            "preset-bughunter", "Bug hunt squad",
            does = "Seats all sixteen worker personas — eight hunt lenses and eight review lenses — across three panels (failures, surface, record) over two rounds, then rules.",
            needs = "A model provider key — this one makes many calls, one per seat.",
            see = "Each panel's rounds with the persona named on every seat, the ruling, and the full record.",
            tweakFirst = "The panels' persona lists, to seat a smaller wave of the squad.",
        ),
        LcncPresetInfo(
            "preset-turbohaul", "Turbohaul, as an org chart (STRAWMAN)",
            does = "Draws the turbohaul manager's pipeline as rings — requests are admitted, staged by " +
                "role, then handed to a resident — with modelmux standing in for the engine processes " +
                "and keymux as the gate that admits them.",
            needs = "Nothing to look at it. A provider key only if you run the resident seat at the end.",
            see = "A wave of requests admitted, grouped by role into staging lanes, and the resident set " +
                "modelmux will actually route to — the org chart and the real roster side by side.",
            tweakFirst = "The request list: add a role, or a model nobody has a key for, and watch where it stalls.",
        ),
        LcncPresetInfo(
            "preset-subvm-audit", "Supply-chain audit",
            does = "Re-checks every library on the mounted sandboxes against what was recorded, and reports anything that differs.",
            needs = "At least one mounted sandbox module.",
            see = "A per-module verdict, and the mismatches if there are any.",
            tweakFirst = "Nothing — read it first; it only reports.",
        ),
    )

    /**
     * TURBOHAUL, AS AN ORG CHART — a STRAWMAN, not a release.
     *
     * `../turbohaul-manager` is a Python/asyncio admission controller in front
     * of supervised llama.cpp sidecars: an acceptance buffer, a staging queue
     * with role affinity, a resident dispatcher doing VRAM admission and LRU,
     * and a KV cache that migrates VRAM → tmpfs → SSD. The organisational
     * cosplay is the interesting part — admission, grace, tenancy, eviction,
     * quarantine, three strikes — and it is all schedule, none of it model.
     *
     * So this draws the org chart in LCNC and puts the REAL roster beside it:
     * keymux says who may be admitted, modelmux says which residents can
     * actually be routed to. No llama.cpp, no GraalPy, no sidecar supervision —
     * where turbohaul spawns a process, this asks modelmux. What the two have
     * in common is the shape of the queue, and that is the whole claim.
     *
     * NOT a port, NOT a release, and it schedules nothing: it is a drawing you
     * can run, so the shape can be argued with.
     */
    private fun turbohaul(): String {
        val program = LcncProgram(
            name = "preset-turbohaul",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "30"), x = 30.0, y = 60.0),
                // the acceptance buffer, hand-authored: thread_id + role + model
                LcncNode("n2", "json.value", params = mapOf("value" to
                    """[{"id":"t-1","role":"planner","model":"glm-5.2","status":"admitted"}, """ +
                    """{"id":"t-2","role":"worker","model":"glm-5.2","status":"admitted"}, """ +
                    """{"id":"t-3","role":"worker","model":"deepseek-v4-pro","status":"staged"}, """ +
                    """{"id":"t-4","role":"scribe","model":"glm-5.2","status":"staged"}, """ +
                    """{"id":"t-5","role":"planner","model":"a-model-nobody-has","status":"stalled"}]"""),
                    x = 30.0, y = 220.0),
                // ADMISSION ring: the buffer enters, affinity grouping leaves.
                LcncNode("r1", LcncContracts.SCOPE, x = 300.0, y = 60.0,
                    children = listOf(
                        LcncNode("in1", LcncContracts.SCOPE_IN,
                            params = mapOf("name" to "requests", "kind" to "json"), x = 40.0, y = 40.0),
                        LcncNode("g1", "list.groupBy", params = mapOf("key" to "role"), x = 260.0, y = 40.0),
                        LcncNode("out1", LcncContracts.SCOPE_OUT,
                            params = mapOf("name" to "staged", "kind" to "json"), x = 500.0, y = 40.0),
                    ).toSeries()),
                LcncNode("n4", "json.value", params = mapOf("value" to
                    """[{"id":"planner","name":"Planner"}, {"id":"worker","name":"Worker"}, """ +
                    """{"id":"scribe","name":"Scribe"}]"""),
                    x = 700.0, y = 300.0),
                // the staging board — the org chart, literally
                LcncNode("n3", "dom.board",
                    params = mapOf("idField" to "id", "titleField" to "role", "subtitleField" to "model", "badgeField" to "status"),
                    x = 700.0, y = 60.0),
                LcncNode("n5", "display", x = 980.0, y = 60.0),
                // THE SIGNAL SPLITTER. turbohaul's dispatcher takes one admitted
                // wave and fans it to per-model residents; here one grouped wave
                // splits into one tap per role. It is not a node — it is three
                // picks off the same output, which is what a splitter IS in a
                // patch panel. Add a role to the request list, add a tap here,
                // and the lane exists; nothing else changes.
                LcncNode("s1", "pick", params = mapOf("path" to "planner"), x = 980.0, y = 200.0),
                LcncNode("s2", "pick", params = mapOf("path" to "worker"), x = 980.0, y = 300.0),
                LcncNode("s3", "pick", params = mapOf("path" to "scribe"), x = 980.0, y = 400.0),
                LcncNode("d1", "display", x = 1220.0, y = 200.0),
                LcncNode("d2", "display", x = 1220.0, y = 300.0),
                LcncNode("d3", "display", x = 1220.0, y = 400.0),
                // THE ROSTER, beside the drawing: who may be admitted (keymux)
                // and who can actually be routed to (modelmux). Where turbohaul
                // would probe VRAM and spawn a sidecar, this asks the mux.
                LcncNode("n6", "keys.status", x = 300.0, y = 420.0),
                LcncNode("n7", "display", x = 560.0, y = 420.0),
                LcncNode("n8", "mux.models", x = 300.0, y = 560.0),
                LcncNode("n9", "display", x = 560.0, y = 560.0),
                LcncNode("n10", "mux.meta", x = 300.0, y = 700.0),
                LcncNode("n11", "display", x = 560.0, y = 700.0),
                // one resident actually doing work — the only node that needs a key
                LcncNode("n12", "text.value",
                    params = mapOf("value" to "You are the resident for role=worker. Acknowledge the wave in one line."),
                    x = 840.0, y = 700.0),
                LcncNode("n13", "mux.chat",
                    params = mapOf("system" to "You are a turbohaul resident.", "maxTokens" to "120"),
                    x = 1080.0, y = 700.0),
                LcncNode("n14", "result.confirm", x = 1340.0, y = 700.0),
                LcncNode("n15", "note", params = mapOf("text" to
                    "TURBOHAUL AS AN ORG CHART — STRAWMAN, not a release.\n\n" +
                    "../turbohaul-manager admits requests, buffers them,\nstages them by role affinity, and dispatches to\nresident llama.cpp sidecars under VRAM admission.\n\n" +
                    "Here the shape is the same and the engine is not:\nkeymux is the admission gate, modelmux is the\nresident set. No sidecar, no VRAM probe, no GraalPy.\n\n" +
                    "It schedules NOTHING. It is a drawing you can run,\nso the shape can be argued with."),
                    x = 980.0, y = 300.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n2", "value", "r1", "requests"),
                LcncWire("in1", "value", "g1", "x"),
                LcncWire("g1", "groups", "out1", "value"),
                LcncWire("r1", "staged", "n3", "groups"),
                LcncWire("n4", "value", "n3", "columns?"),
                LcncWire("n3", "move", "n5", "x"),
                LcncWire("r1", "staged", "s1", "x"),
                LcncWire("r1", "staged", "s2", "x"),
                LcncWire("r1", "staged", "s3", "x"),
                LcncWire("s1", "y", "d1", "x"),
                LcncWire("s2", "y", "d2", "x"),
                LcncWire("s3", "y", "d3", "x"),
                LcncWire("n1", "tick", "n6", "trigger?"),
                LcncWire("n6", "roster", "n7", "x"),
                LcncWire("n1", "tick", "n8", "trigger?"),
                LcncWire("n8", "models", "n9", "x"),
                LcncWire("n1", "tick", "n10", "trigger?"),
                LcncWire("n10", "meta", "n11", "x"),
                LcncWire("n12", "value", "n13", "prompt?"),
                LcncWire("n13", "content", "n14", "content"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.7),
            seq = 15,
        )
        return LcncProgramConfix.toJson(program)
    }

    /** The description for [name], or null when the prefab carries none. */
    fun info(name: String): LcncPresetInfo? = catalog().firstOrNull { it.name == name }


    /** name → Confix JSON document, the exact shape LcncProgramConfix parses. */
    fun all(): Map<String, String> = linkedMapOf(
        "preset-hermes" to hermes(),
        "preset-tribunal" to tribunal(),
        "preset-curator" to curator(),
        "preset-context" to context(),
        "preset-kanban" to kanban(),
        "preset-ccek" to ccek(),
        "preset-scope" to scopeDemo(),
        "preset-scope-inner" to scopeInner(),
        "preset-pairs" to pairsDemo(),
        "preset-brain-mux" to brainMux(),
        "preset-media" to mediaDemo(),
        "preset-hermes-train" to hermesTrain(),
        "preset-legal-tribunal" to legalTribunal(),
        "preset-state-freeze" to stateFreeze(),
        "preset-council" to council(),
        "preset-bughunter" to bughunter(),
        "preset-subvm-audit" to subvmAudit(),
        "preset-turbohaul" to turbohaul(),
    )

    // ── legal council: the default 3x5 convening, fully drawn ────────────
    // The can and the atoms are the same substance: this preset IS
    // CouncilProgram.build(DEFAULT_3x5) verbatim — byte-identical to what
    // the pure council.convene node emits for an empty config
    // (CouncilPresetIdentityTest pins the identity in both directions).
    private fun council(): String =
        LcncProgramConfix.toJson(CouncilProgram.build(CouncilConfig.DEFAULT_3x5))

    // ── bug hunt squad: all 16 worker cosplays seated ────────────────────
    // Same builder, different data: the convening IS
    // BughunterSquad.convening() verbatim, so the panel persona lists and
    // the council.seat system prompts carry the cosplay text on every seat.
    private fun bughunter(): String =
        LcncProgramConfix.toJson(CouncilProgram.build(BughunterSquad.convening()))

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
                // scope.args? and scope.when? stay EMPTY here on purpose. This preset
                // is the one that runs through the default loader with ZERO
                // registered runners — pure ring machinery, nothing to execute.
                // Wiring a literal into args? adds a node type that needs a
                // runner and destroys exactly the property the preset exists to
                // prove. The caller's binding is demonstrated in preset-turbohaul
                // instead, which has runners anyway.
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
                // The seat had a system prompt and no user prompt: nothing to send.
                LcncNode("n5", "text.value",
                    params = mapOf("value" to "Summarise the difference between the two models above in one sentence."),
                    x = 340.0, y = 300.0),
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
                LcncWire("n5", "value", "n3", "prompt?"),
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
                // The submit lane shipped with every param blank, so "fill params,
                // run — a real card" was an instruction, not a demonstration. It is
                // filled now, and the idempotencyKey is STABLE: the first run lands
                // the card, every run after is refused as a duplicate. That refusal
                // is half the lesson. Change the key to land another.
                LcncNode("n10", "kanban.submit",
                    params = mapOf("jobId" to "", "title" to "preset-kanban demo card (safe to archive)",
                        "priority" to "2", "idempotencyKey" to "preset-kanban-demo-1"),
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
                // The lanes. Without this the board grouped cards by status and
                // then had no column order to lay them out in — edit the list to
                // re-order the board, or drop a column to hide it.
                LcncNode("n7", "json.value", params = mapOf("value" to
                    """[{"id":"triage","name":"Triage"}, {"id":"todo","name":"Todo"}, """ +
                    """{"id":"ready","name":"Ready"}, {"id":"running","name":"Running"}, """ +
                    """{"id":"blocked","name":"Blocked"}, {"id":"done","name":"Done"}]"""),
                    x = 690.0, y = 240.0),
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
                LcncWire("n7", "value", "n5", "columns?"),
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
                // `brief="brief"` reads a frame binding a caller supplies; with no
                // caller and an empty prompt, counsel had nothing to argue and the
                // whole tribunal refused. The prompt is the motion it argues when
                // nobody binds one — replace it, or bind `brief` at run time.
                LcncNode("n2", "mux.chat", params = mapOf("job" to "argue",
                    "prompt" to "Motion: the limitation of liability clause in section 7.2 bars " +
                        "consequential damages. Argue for the motion in under 150 words.",
                    "brief" to "brief", "system" to "You are counsel for the motion. Argue briefly.",
                    "maxTokens" to "400"), x = 250.0, y = 80.0),
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
                // The bullets the fold folds. This socket shipped EMPTY, so the
                // "folded playbook text" the card promises never existed — the
                // chain downstream was assembled over nothing. Edit these and
                // re-run to watch the playbook and the chainHead both change.
                LcncNode("n0", "json.value", params = mapOf("value" to
                    """[{"id":1,"content":"Prefer the smallest change that closes the gap."}, """ +
                    """{"id":2,"content":"Name the file and the line, never a vague area."}, """ +
                    """{"id":3,"content":"If a claim was not run, mark it unverified."}]"""),
                    x = 30.0, y = 60.0),
                LcncNode("n1", "context.fold", x = 300.0, y = 60.0),
                // The three frames in change-frequency order: tools change least,
                // the tail most. Filled so the chain identity is built from a
                // real prefix rather than three empty strings.
                LcncNode("n5", "text.value", params = mapOf("value" to
                    "You have: read_file, write_file, run_tests."), x = 300.0, y = 300.0),
                LcncNode("n6", "text.value", params = mapOf("value" to
                    "Repository: TrikeShed. Branch: master."), x = 300.0, y = 400.0),
                LcncNode("n7", "text.value", params = mapOf("value" to
                    "Current turn: the operator asked why the fold was empty."), x = 300.0, y = 500.0),
                LcncNode("n2", "context.assemble",
                    params = mapOf("model" to "", "effort" to "medium", "tools" to ""),
                    x = 620.0, y = 60.0),
                LcncNode("n3", "display", x = 900.0, y = 60.0),
                // …and the folded playbook itself, which nothing ever showed.
                // result.confirm, not display: playbook is TEXT and display.x
                // wants json — the daemon's type check refuses that wire, which
                // is how this got caught rather than shipped.
                LcncNode("n8", "result.confirm", x = 620.0, y = 620.0),
                LcncNode("n4", "note", params = mapOf("text" to "adaptive context preset\nbullets → fold (deterministic merge)\n→ assemble (rolling cache-identity chain)\nchainHead = provable cache prefix.\ncounters live in the belief bag, never in frame bytes.\n\nEdit the json.value bullets, or any frame,\nand the chainHead changes: that is the point."), x = 900.0, y = 300.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n0", "value", "n1", "bullets"),
                LcncWire("n1", "playbook", "n2", "playbook?"),
                LcncWire("n1", "playbook", "n8", "content"),
                LcncWire("n5", "value", "n2", "toolsSystem?"),
                LcncWire("n6", "value", "n2", "envelope?"),
                LcncWire("n7", "value", "n2", "tail?"),
                LcncWire("n2", "chain", "n3", "x"),
            ).toSeries(),
            view = LcncView(x = 30.0, y = 20.0, zoom = 0.9),
            seq = 5,
        )
        return LcncProgramConfix.toJson(program)
    }

    // ── CCEK itself, programmed: the substrate as a first-class program ──
    // Every other preset drives a PROJECTION of CCEK (a board, a council, a
    // case). This one drives the engine: incarnate a node, signal it, host an
    // agent on its bounded fan-out, read its live projection, replay its
    // recording, watch its status, and fork the context lineage beside it.

    private fun ccek(): String {
        val program = LcncProgram(
            name = "preset-ccek",
            nodes = listOf(
                LcncNode("n1", "timer", params = mapOf("seconds" to "5"), x = 30.0, y = 200.0),
                LcncNode("n2", "ccek.incarnate",
                    params = mapOf("title" to "showcase", "record" to "true", "maxConcurrency" to "4"),
                    x = 250.0, y = 200.0),
                // fields? is the signal's free-form payload; empty, the signal
                // carried a verb and nothing about itself.
                LcncNode("f1", "json.value",
                    params = mapOf("value" to """{"origin":"preset-ccek","wave":"showcase"}"""),
                    x = 300.0, y = 30.0),
                LcncNode("n3", "ccek.signal",
                    params = mapOf("verb" to "append", "blockKind" to "TEXT", "text" to "a tick reached the engine"),
                    x = 520.0, y = 30.0),
                LcncNode("n4", "ccek.agent", params = mapOf("name" to "watcher"), x = 520.0, y = 200.0),
                LcncNode("n5", "ccek.projection", params = mapOf("kind" to "markdown"), x = 520.0, y = 370.0),
                LcncNode("n6", "ccek.recording", x = 520.0, y = 540.0),
                LcncNode("n7", "ccek.status", x = 520.0, y = 700.0),
                LcncNode("n8", "display", x = 800.0, y = 200.0),
                LcncNode("n9", "display", x = 800.0, y = 370.0),
                LcncNode("n10", "display", x = 800.0, y = 540.0),
                LcncNode("n11", "display", x = 800.0, y = 700.0),
                LcncNode("n12", "ccek.context", params = mapOf("role" to "operator"), x = 250.0, y = 880.0),
                LcncNode("f2", "json.value",
                    params = mapOf("value" to """{"observed":"a tick reached the engine","by":"preset-ccek"}"""),
                    x = 300.0, y = 1000.0),
                LcncNode("n13", "ccek.fact", params = mapOf("kind" to "observation"), x = 520.0, y = 880.0),
                LcncNode("n14", "display", x = 800.0, y = 880.0),
                LcncNode("n15", "note", params = mapOf("text" to "CCEK, programmed.\nincarnate (idempotent by title) → signal\n(all ten ForgeSignal verbs) → this program\nsubscribes as an AGENT on the bounded\nfan-out, reads the live projection, replays\nthe recording, and watches Started/Completed.\nBelow: the context lineage the facts land in."), x = 250.0, y = 480.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "handle", "n3", "handle"),
                LcncWire("n2", "handle", "n4", "handle"),
                LcncWire("n2", "handle", "n5", "handle"),
                LcncWire("n2", "handle", "n6", "handle"),
                LcncWire("n2", "handle", "n7", "handle"),
                LcncWire("n4", "signals", "n8", "x"),
                LcncWire("n5", "projection", "n9", "x"),
                LcncWire("n6", "signals", "n10", "x"),
                LcncWire("n7", "events", "n11", "x"),
                LcncWire("f1", "value", "n3", "fields?"),
                LcncWire("f2", "value", "n13", "fields?"),
                LcncWire("n12", "contextId", "n13", "contextId"),
                LcncWire("n13", "factCount", "n14", "x"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.75),
            seq = 16,
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
                // The field is a SUMMARY — size, crux bits, concepts. It was
                // wired into review.facts, which parses turn facts, so the
                // curator parsed zero and landed zero on every tick while every
                // kind matched and every test stayed green. The field belongs on
                // a display; the review needs facts.
                LcncNode("n7", "display", x = 470.0, y = 300.0),
                // Turn facts: {verb, ok, context, object}. Two observations in
                // one context are enough for the induction to fire and land a
                // third belief — edit a verb, or flip an `ok`, and watch what
                // lands change.
                LcncNode("n0", "json.value", params = mapOf("value" to
                    """[{"verb":"bash","ok":true,"context":"build","object":"gradle"},
 {"verb":"edit","ok":true,"context":"build","object":"kotlin"},
 {"verb":"bash","ok":false,"context":"deploy","object":"ssh"}]"""),
                    x = 250.0, y = 60.0),
                LcncNode("n3", "beliefs.review", x = 690.0, y = 60.0),
                LcncNode("n5", "display", x = 950.0, y = 60.0),
                LcncNode("n6", "note", params = mapOf("text" to "curator preset — quota-free branch:\nmint → review → tick → render → revise.\nteach via POST /api/beliefs/teach (W5.3).\n\nfacts are {verb, ok, context, object}.\nTwo in one context induce a third belief;\nthe display beside it is the live bag field."), x = 690.0, y = 300.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("n1", "tick", "n2", "trigger?"),
                LcncWire("n2", "field", "n7", "x"),
                LcncWire("n0", "value", "n3", "facts"),
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
                LcncNode("bplay", "button", params = mapOf("label" to "play"), x = 30.0, y = 220.0),
                LcncNode("bstop", "button", params = mapOf("label" to "stop"), x = 190.0, y = 220.0),
                LcncNode("brew", "button", params = mapOf("label" to "rewind"), x = 350.0, y = 220.0),
                LcncNode("svol", "slider",
                    params = mapOf("label" to "volume", "min" to "0", "max" to "1", "step" to "0.05", "value" to "0.8"),
                    x = 30.0, y = 340.0),
                LcncNode("n2", "media.player", x = 320.0, y = 40.0),
                LcncNode("n3", "display", x = 1080.0, y = 60.0),
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
        // The BrainMux family for a person who has never seen a patch panel:
        // three numbered steps top to bottom, the answer to the right of the
        // question, the router's account at the bottom. x starts at 320 because
        // the palette (216px, fixed) covers the canvas's left edge — a note at
        // x=30 is a note nobody sees. Rows are spaced by the tallest node above
        // them (ask a model runs ~700 canvas px): the loader pushes an
        // overlapping node sideways, which scattered a tighter grid.
        val program = LcncProgram(
            name = "preset-brain-mux",
            nodes = listOf(
                LcncNode("note-keys", "note",
                    params = mapOf("text" to "1 · keys this machine already has —\nany model from one of these providers\nanswers with nothing typed."),
                    x = 320.0, y = 60.0),
                LcncNode("k1", "keys.status", x = 620.0, y = 60.0),
                LcncNode("kd", "display", x = 1000.0, y = 60.0),
                LcncNode("note-ask", "note",
                    params = mapOf("text" to "2 · ask —\npick a model (the list is what runs here,\nnewest first), type a question, press ▶ run.\nThe answer lands in the green card;\na red card says what went wrong."),
                    x = 320.0, y = 440.0),
                LcncNode("p1", "prompt.chat",
                    params = mapOf(
                        "prompt" to "Say hello in one sentence.",
                        // Blank model/prefill/url/key: the live list leads with the newest
                        // model Hermes ran here and the router finds the key.
                        "model" to "",
                        // 256, not 128: a thinking model spends its budget on reasoning
                        // first, and a small cap returned an empty answer.
                        "maxTokens" to "256",
                        "temperature" to "0.3",
                        "prefill" to "",
                        "url" to "",
                        "key" to "",
                        "headers" to "[]",
                    ), x = 620.0, y = 440.0),
                LcncNode("d1", "result.confirm", x = 1000.0, y = 440.0),
                LcncNode("note-save", "note",
                    params = mapOf("text" to "3 · optional: save your own key —\nfor a provider step 1 does not list.\nIt then shows up under \"prefill\" in step 2."),
                    x = 320.0, y = 1180.0),
                LcncNode("c1", "credential.enter",
                    params = mapOf(
                        "key_type" to "nvidia",
                        "url" to "https://integrate.api.nvidia.com/v1",
                        "api_type" to "openai",
                        "key" to "",
                    ), x = 620.0, y = 1180.0),
                LcncNode("note-router", "note",
                    params = mapOf("text" to "what the router did —\nthe last answer: which model and provider,\nwhether it succeeded, how long it took,\nand the tokens spent. Refreshes every 30 s."),
                    x = 320.0, y = 1640.0),
                LcncNode("t1", "timer", params = mapOf("seconds" to "30"), x = 620.0, y = 1640.0),
                LcncNode("me1", "mux.meta", x = 850.0, y = 1640.0),
                LcncNode("dm1", "display", x = 1130.0, y = 1640.0),
            ).toSeries(),
            wires = listOf(
                LcncWire("k1", "have", "kd", "x"),
                LcncWire("p1", "content", "d1", "content"),
                LcncWire("p1", "ok", "d1", "ok"),
                LcncWire("p1", "error", "d1", "error"),
                LcncWire("p1", "cached", "d1", "cached?"),
                LcncWire("t1", "tick", "me1", "trigger?"),
                LcncWire("me1", "lastAnswer", "dm1", "x"),
            ).toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.65),
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
                // Same category error preset-curator carried: the field is a
                // SUMMARY, review wants turn facts. Outcome markers are the
                // evidence this preset's own note describes — here they are.
                LcncNode("n0", "json.value", params = mapOf("value" to
                    """[{"verb":"CONSOLIDATE","ok":true,"context":"wiki","object":"skill-draft"}, """ +
                    """{"verb":"PATCH","ok":true,"context":"wiki","object":"skill-draft"}, """ +
                    """{"verb":"PRUNE","ok":false,"context":"wiki","object":"stale-trace"}]"""),
                    x = 250.0, y = 240.0),
                // read.construct reads LINES — the outcome markers this preset's
                // note describes, as text. Fact objects are not lines.
                LcncNode("n0b", "json.value", params = mapOf("value" to
                    """["CONSOLIDATE wiki/skill-draft: merged two overlapping traces", """ +
                    """"PATCH wiki/skill-draft: corrected the mount path", """ +
                    """"PRUNE wiki/stale-trace: FAIL, no longer reachable"]"""),
                    x = 250.0, y = 380.0),
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
                LcncWire("n0", "value", "n3", "facts"),
                // landed is empty whenever the beliefs already exist (a revise, not
                // a land), which killed this lane on every run but the first. The
                // facts themselves are the stable input; landed still displays.
                LcncWire("n0b", "value", "n4", "lines"),
                LcncWire("n4", "aggregates", "n5", "after?"),
                LcncWire("n1", "tick", "n5", "trigger?"),
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
                // The matter itself. Empty, "grounded legal review" reviewed
                // nothing — replace this text with a real filing.
                LcncNode("t0", "text.value", params = mapOf("value" to
                    "In re Wilkins v. Datacorp, 412 F.3d 118 (9th Cir. 2021). " +
                    "Movant seeks summary judgment on the breach claim, arguing the " +
                    "limitation clause in section 7.2 bars consequential damages. " +
                    "Respondent contends section 7.2 is unconscionable under Cal. Civ. Code 1670.5."),
                    x = 30.0, y = 260.0),
                LcncNode("n1", "legal.ingest",
                    params = mapOf("maxTokens" to "2048", "brief" to "brief"),
                    x = 300.0, y = 60.0),
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
                LcncWire("t0", "value", "n1", "text?"),
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
