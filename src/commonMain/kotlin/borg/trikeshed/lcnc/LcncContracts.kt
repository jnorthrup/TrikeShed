package borg.trikeshed.lcnc

/** Port contract shared by every LCNC surface. Browser code may render this value,
 *  but it must never invent compatibility rules. */
data class LcncPortContract(
    val type: String,
    val title: String = type,
    val inputs: List<String>,
    val outputs: List<String>,
    val cardinality: Map<String, LcncCardinality> = emptyMap(),
    val functions: Map<String, List<String>> = emptyMap(),
    val inputKinds: Map<String, String> = emptyMap(),
    val outputKinds: Map<String, String> = emptyMap(),
    /**
     * Server-owned parameter defaults for the surface's editor widgets.
     * The retired JS TYPES table carried these as `params:{k:{v,opts}}`;
     * Kotlin is now the ONE author — a type with widgets declares them HERE
     * or the surface renders a bare header.
     */
    val params: Map<String, LcncParamSpec> = emptyMap(),
    /** Sources re-fire on their own clock; sinks terminate a chain; wides render expanded. */
    val isSource: Boolean = false,
    val isSink: Boolean = false,
    val wide: Boolean = false,
    /**
     * This node CHANGES SOMETHING outside the graph — writes a card, freezes
     * state, mints a belief, spawns a VM, posts a request, stores a credential.
     *
     * [isSink] does not mean this: the seven sinks are all DISPLAYS. Every
     * genuine effect in the vocabulary — kanban.submit, state.freeze, nal.mint,
     * http.post, vm.spawn, credential.enter — is sink=false, so nothing could
     * tell an effect from a transaction. A treeshake that auto-wires a write is
     * not tidying a graph, it is performing one.
     */
    val isEffect: Boolean = false,
) {
    /** One editable parameter: default value, optional dropdown options, multi-line flag, placeholder.
     *  [cols] non-empty makes it a LIST widget: rows of {col: value}, the param
     *  VALUE is the JSON array text — daemon shape (Map<String,String>) unchanged. */
    data class LcncParamSpec(
        val v: String = "",
        val opts: List<String> = emptyList(),
        val ta: Boolean = false,
        val ph: String = "",
        val cols: List<String> = emptyList(),
        /**
         * LIVE options. [opts] is a dead list an author typed once; this names a
         * running source and the picklist is filled from it at open time.
         *
         * `"<runner>#<path>"` — the runner is executed through the ordinary
         * /api/lcnc/run lane and the path picks the field out of its result, so
         * a picklist is a one-node program and nothing new had to be invented to
         * evaluate it. `mux.models#models[].id` lists the models modelmux can
         * actually route to right now; `board.get#items[].id` lists the cards
         * that actually exist. A stale option is then impossible by
         * construction, because there is no list to go stale.
         */
        val optsFrom: String = "",
    )
}

object LcncContracts {
    private val PORT_KIND_OPTIONS = listOf("", "json", "text", "id", "trigger", "num")

    // ── picklists DERIVED from the enum that owns them ────────────────────
    // These were transcribed by hand at three sites and drifted in both
    // directions: VmFacet declares JVM("java") first and no picklist offered it,
    // while three facets that ARE offered (ruby, clojure, llvm) have no language
    // jar staged. A copied list is a list that goes stale silently; the enum is
    // the authority, so read it.
    /** Facet ENUM NAMES — the spelling vm.graalce takes. */
    val VM_FACET_NAMES: List<String> =
        borg.trikeshed.pointcut.VmFacet.entries.map { it.name }
    /** Facet WIRE IDS — the spelling vm.eval takes. */
    val VM_FACET_IDS: List<String> =
        borg.trikeshed.pointcut.VmFacet.entries.map { it.id }
    /** Guest trust levels, from [borg.trikeshed.vm.VmTrust]. */
    val VM_TRUST_OPTIONS: List<String> =
        borg.trikeshed.vm.VmTrust.entries.map { it.name }
    /** One spelling of a boolean picklist; it was written both ways, nine times. */
    val BOOLEAN_OPTIONS: List<String> = listOf("false", "true")

    /** Concentric-scope vocabulary (spec §4): the call, the binding, the return. */
    const val SCOPE = "scope"
    const val SCOPE_IN = "scope.in"
    const val SCOPE_OUT = "scope.out"
    /** P2 map-reduce vocabulary: declarative Confix/LCNC only, never JS eval. */
    const val VIEW_EMIT = "view.emit"
    const val VIEW_REDUCE = "view.reduce"
    /** WikiSkill consolidation vocabulary: one pass per invocation, loop outside. */
    const val WIKI_CONSOLIDATE = "wiki.consolidate"
    const val WIKI_PROPOSE = "wiki.propose"

    /** Full vocabulary — ONE author for node types, ports, titles, param defaults.
     *  inputKinds/outputKinds drive the mating filter; omit a kind and the type
     *  is invisible to drag-to-empty-space. */
    fun all(): List<LcncPortContract> = listOf(
        // ── §4: concentric scope — the call, the binding, the return ──
        // A scope's REAL ports are declared by its child (`scope.in`/`scope.out`
        // names); the generic args?/returns pair is the declared wire spelling —
        // args? merges UNDER per-name wires (per-name wins), returns carries the
        // composed map beside the per-name pass-through (spec §4).
        LcncPortContract(SCOPE, "scope (a ring — holds its children)",
            listOf("args?", "when?"), listOf("returns"),
            inputKinds = mapOf("args" to "json", "when" to "json"),
            outputKinds = mapOf("returns" to "json"),
            params = mapOf("program" to LcncPortContract.LcncParamSpec(ph = "named ring (stored program / preset) — empty for inline children"))),
        LcncPortContract(SCOPE_IN, "scope.in (formal parameter)",
            emptyList(), listOf("value"),
            outputKinds = mapOf("value" to "json"),
            params = mapOf(
                "name" to LcncPortContract.LcncParamSpec(ph = "parameter name (trailing ? = optional)"),
                "default" to LcncPortContract.LcncParamSpec(ph = "value when the caller omits it"),
                // Progressive typing: a ring parameter is GENERIC until it declares a
                // kind. Declared ⇒ LcncTypeCheck enforces it like any leaf port; absent
                // ⇒ the ring accepts any kind (a frame binding is Any?, and pretending
                // otherwise is what made the shipped council preset undrawable).
                "kind" to LcncPortContract.LcncParamSpec(
                    opts = PORT_KIND_OPTIONS,
                    ph = "declared parameter kind — empty = generic"),
            )),
        LcncPortContract(SCOPE_OUT, "scope.out (return value)",
            listOf("value"), emptyList(),
            inputKinds = mapOf("value" to "json"),
            params = mapOf(
                "name" to LcncPortContract.LcncParamSpec(ph = "return name"),
                "kind" to LcncPortContract.LcncParamSpec(
                    opts = PORT_KIND_OPTIONS,
                    ph = "declared yield kind — empty = generic"),
            ),
            isSink = true),

        // ── blackboard: the shared Confix surface as first-class nodes ──
        // (BlackboardWire's own GET routes; the canvas reads, never invents)
        LcncPortContract("blackboard.facts", "blackboard facts",
            listOf("trigger?"), listOf("facts"),
            inputKinds = mapOf("trigger" to "trigger"), outputKinds = mapOf("facts" to "json")),
        LcncPortContract("blackboard.board", "blackboard board view",
            listOf("trigger?"), listOf("board"),
            inputKinds = mapOf("trigger" to "trigger"), outputKinds = mapOf("board" to "json")),
        LcncPortContract("blackboard.sites", "blackboard sites",
            listOf("trigger?"), listOf("sites"),
            inputKinds = mapOf("trigger" to "trigger"), outputKinds = mapOf("sites" to "json")),

        // ── CCEK: the substrate every other plane rides, made programmable ──
        // Bounded fan-out agents over a ForgeDocument, ten control verbs, live
        // projections, the recorded signal log, and context lineage. Everything
        // above (kanban, council, legal, belief) is a projection OF this; these
        // nodes let a program drive the engine itself.
        LcncPortContract("ccek.incarnate", "ccek node (incarnate / attach)",
            listOf("trigger?"), listOf("handle", "node"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("handle" to "id", "node" to "json"),
            params = mapOf(
                "title" to LcncPortContract.LcncParamSpec(v = "lcnc-node", ph = "same title = same node (idempotent)"),
                "record" to LcncPortContract.LcncParamSpec(v = "true", opts = BOOLEAN_OPTIONS),
                "maxConcurrency" to LcncPortContract.LcncParamSpec(v = "8", ph = "bounded fan-out width"),
                "projections" to LcncPortContract.LcncParamSpec(ph = "DOCUMENT,BOARD,MARKDOWN — empty = all"),
            ), isEffect = true),
        LcncPortContract("ccek.signal", "ccek signal (every ForgeSignal verb)",
            listOf("handle", "fields?"), listOf("sent", "signal"),
            inputKinds = mapOf("handle" to "id", "fields" to "json"),
            outputKinds = mapOf("sent" to "json", "signal" to "json"),
            params = mapOf(
                "verb" to LcncPortContract.LcncParamSpec(v = "append", opts = CcekNodes.VERBS),
                "text" to LcncPortContract.LcncParamSpec(ta = true, ph = "append / update"),
                "blockKind" to LcncPortContract.LcncParamSpec(v = "TEXT", ph = "append: TEXT, HEADING_1, TODO…"),
                "blockId" to LcncPortContract.LcncParamSpec(ph = "update / delete"),
                "cardId" to LcncPortContract.LcncParamSpec(ph = "move / continue / repeat / abort / fork / join / vote"),
                "toColumnId" to LcncPortContract.LcncParamSpec(ph = "move"),
                "edgeId" to LcncPortContract.LcncParamSpec(ph = "repeat"),
                "reason" to LcncPortContract.LcncParamSpec(ph = "abort"),
                "targetLane" to LcncPortContract.LcncParamSpec(ph = "fork"),
                "group" to LcncPortContract.LcncParamSpec(ph = "join"),
                "requiredBranches" to LcncPortContract.LcncParamSpec(v = "2", ph = "join"),
                "verdict" to LcncPortContract.LcncParamSpec(ph = "vote"),
            ), isEffect = true),
        LcncPortContract("ccek.projection", "ccek projection (live view)",
            listOf("handle"), listOf("projection", "kind"),
            inputKinds = mapOf("handle" to "id"),
            outputKinds = mapOf("projection" to "json", "kind" to "text"),
            params = mapOf("kind" to LcncPortContract.LcncParamSpec(
                v = "markdown", opts = listOf("markdown", "board", "document")))),
        LcncPortContract("ccek.recording", "ccek recording (replay log)",
            listOf("handle"), listOf("signals", "count"),
            inputKinds = mapOf("handle" to "id"),
            outputKinds = mapOf("signals" to "json", "count" to "json")),
        LcncPortContract("ccek.agent", "ccek agent (this program subscribes)",
            listOf("handle"), listOf("agent", "signals", "count"),
            inputKinds = mapOf("handle" to "id"),
            outputKinds = mapOf("agent" to "text", "signals" to "json", "count" to "json"),
            params = mapOf("name" to LcncPortContract.LcncParamSpec(ph = "agent name — empty uses the node id"))),
        LcncPortContract("ccek.status", "ccek fan-out status",
            listOf("handle"), listOf("events", "started", "completed", "failed"),
            inputKinds = mapOf("handle" to "id"),
            outputKinds = mapOf("events" to "json", "started" to "json", "completed" to "json", "failed" to "json")),
        LcncPortContract("ccek.drain", "ccek drain (graceful cancel)",
            listOf("handle"), listOf("drained"),
            inputKinds = mapOf("handle" to "id"), outputKinds = mapOf("drained" to "json"), isEffect = true),
        LcncPortContract("ccek.context", "ccek user context (fork lineage)",
            listOf("parent?"), listOf("context", "contextId"),
            inputKinds = mapOf("parent" to "id"),
            outputKinds = mapOf("context" to "json", "contextId" to "id"),
            params = mapOf(
                "role" to LcncPortContract.LcncParamSpec(v = "root", ph = "context role"),
                "parent" to LcncPortContract.LcncParamSpec(ph = "parent contextId — empty = root"),
            )),
        LcncPortContract("ccek.fact", "ccek causal fact",
            listOf("contextId", "fields?"), listOf("factCount", "asserted"),
            inputKinds = mapOf("contextId" to "id", "fields" to "json"),
            outputKinds = mapOf("factCount" to "json", "asserted" to "json"),
            params = mapOf("kind" to LcncPortContract.LcncParamSpec(v = "observation", ph = "assertion kind")), isEffect = true),

        // ── sources (no inputs) ──────────────────────────────────────
        LcncPortContract("timer", "timer",
            emptyList(), listOf("tick"),
            mapOf("tick" to LcncCardinality.MANY),
            mapOf("tick" to listOf("identity")),
            outputKinds = mapOf("tick" to "trigger"),
            params = mapOf("seconds" to LcncPortContract.LcncParamSpec(v = "5")),
            isSource = true),
        LcncPortContract("graal.events", "graal event source (SSE)",
            emptyList(), listOf("event"),
            outputKinds = mapOf("event" to "json"),
            params = mapOf("filter" to LcncPortContract.LcncParamSpec(ph = "substring filter (gc, compile, deopt, commit…)")),
            isSource = true),
        LcncPortContract("vm.events", "vm event source (SSE)",
            emptyList(), listOf("event"),
            outputKinds = mapOf("event" to "json"),
            params = mapOf(
                "vmId" to LcncPortContract.LcncParamSpec(ph = "empty = all vms"),
                "kind" to LcncPortContract.LcncParamSpec(ph = "spawned|evaluated|revoked"),
            ),
            isSource = true),

        // ── trigger-driven data fetchers ─────────────────────────────
        LcncPortContract("http.get", "http get",
            listOf("trigger?"), listOf("json"),
            mapOf("json" to LcncCardinality.ONE),
            mapOf("json" to listOf("identity", "pick")),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("json" to "json"),
            params = mapOf("path" to LcncPortContract.LcncParamSpec(v = "/api/health"))),
        LcncPortContract("graal.vitals", "jvm vitals",
            listOf("trigger?"), listOf("json"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("json" to "json")),
        LcncPortContract("vms.list", "sub-vms",
            listOf("trigger?"), listOf("rows"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("rows" to "json")),
        LcncPortContract("keys.status", "keymux roster",
            listOf("trigger?"), listOf("roster"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("roster" to "json")),
        LcncPortContract("board.get", "kanban board",
            listOf("trigger?"), listOf("json"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("json" to "json")),
        LcncPortContract("mux.models", "mux models",
            listOf("trigger?"), listOf("models"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("models" to "json")),
        // mux.meta: modelmux presence — strategy, last selection, quota standings.
        LcncPortContract("mux.meta", "mux meta (modelmux presence)",
            listOf("trigger?"), listOf("meta"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("meta" to "json")),
        LcncPortContract("project.kill", "project db kill",
            listOf("trigger?"), listOf("verdict"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("verdict" to "json"),
            params = mapOf("name" to LcncPortContract.LcncParamSpec(ph = "project db name (hierarchy kill)")), isEffect = true),
        LcncPortContract("project.list", "mounted scopes",
            listOf("trigger?"), listOf("scopes"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("scopes" to "json")),
        LcncPortContract("beliefs.introspect", "NAL-9 introspection",
            listOf("trigger?"), listOf("field"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("field" to "json")),
        LcncPortContract("pointcut.routes", "pointcut routes",
            listOf("trigger?"), listOf("routes"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("routes" to "json")),
        LcncPortContract("panels.list", "stored programs (click → embed)",
            listOf("trigger?"), listOf("panels"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("panels" to "json"),
            wide = true),
        LcncPortContract("board.view", "kanban board (summary)",
            listOf("trigger?"), listOf("board", "alerts"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("board" to "json", "alerts" to "json")),

        // ── VM lifecycle ─────────────────────────────────────────────
        LcncPortContract("vm.spawn", "vm spawn",
            emptyList(), listOf("vmId"),
            outputKinds = mapOf("vmId" to "id"),
            params = mapOf(
                "id" to LcncPortContract.LcncParamSpec(v = "vm-panel"),
                "facet" to LcncPortContract.LcncParamSpec(v = "python", opts = VM_FACET_IDS),
                "wallMillis" to LcncPortContract.LcncParamSpec(v = "1800000"),
                "world" to LcncPortContract.LcncParamSpec(ph = "host dirs, comma-sep"),
            ), isEffect = true),
        LcncPortContract("vm.eval", "vm eval",
            listOf("vmId", "source?"), listOf("value", "cid"),
            inputKinds = mapOf("vmId" to "id", "source" to "text"),
            outputKinds = mapOf("value" to "json", "cid" to "id"),
            params = mapOf("source" to LcncPortContract.LcncParamSpec(v = "1+1", ta = true)), isEffect = true),
        LcncPortContract("vm.revoke", "vm revoke",
            listOf("vmId"), listOf("ok"),
            inputKinds = mapOf("vmId" to "id"),
            outputKinds = mapOf("ok" to "json"), isEffect = true),
        LcncPortContract("pytest.pure", "pytest (pure, in-vm)",
            listOf("vmId"), listOf("exit", "tail"),
            inputKinds = mapOf("vmId" to "id"),
            outputKinds = mapOf("exit" to "json", "tail" to "json"),
            params = mapOf(
                "path" to LcncPortContract.LcncParamSpec(v = "/workspace/computronium/tests"),
                "flags" to LcncPortContract.LcncParamSpec(
                    v = "-q -s -o addopts= -p no:cacheprovider -p no:cov --continue-on-collection-errors"),
            )),

        // ── transforms ───────────────────────────────────────────────
        LcncPortContract("pick", "pick path",
            listOf("x"), listOf("y"),
            mapOf("x" to LcncCardinality.ONE, "y" to LcncCardinality.ONE),
            mapOf("x" to listOf("identity", "pick")),
            inputKinds = mapOf("x" to "json"),
            outputKinds = mapOf("y" to "json"),
            params = mapOf("path" to LcncPortContract.LcncParamSpec(v = "memory.heapUsed", ph = "dot.path"))),
        LcncPortContract("js", "js transform",
            listOf("x"), listOf("y"),
            inputKinds = mapOf("x" to "json"),
            outputKinds = mapOf("y" to "json"),
            params = mapOf("expr" to LcncPortContract.LcncParamSpec(v = "x", ta = true, ph = "expression over x"))),
        // A LIST as a first-class value: rows of key/model pairs authored in
        // the widget (or dropped from any result tree), emitted as json.
        LcncPortContract("list.pairs", "key/model pairs",
            emptyList(), listOf("pairs"),
            outputKinds = mapOf("pairs" to "json"),
            params = mapOf("pairs" to LcncPortContract.LcncParamSpec(cols = listOf("key", "model")))),
        LcncPortContract("list.groupBy", "group by key (generic)",
            listOf("x"), listOf("groups"),
            mapOf("x" to LcncCardinality.ONE),
            mapOf("x" to listOf("groupBy")),
            inputKinds = mapOf("x" to "json"),
            outputKinds = mapOf("groups" to "json"),
            params = mapOf("key" to LcncPortContract.LcncParamSpec(v = "status", ph = "field name (dot.path ok)"))),
        LcncPortContract("http.post", "http post",
            listOf("body"), listOf("json"),
            inputKinds = mapOf("body" to "json"),
            outputKinds = mapOf("json" to "json"),
            params = mapOf("path" to LcncPortContract.LcncParamSpec(v = "/api/submit")), isEffect = true),
        LcncPortContract("rf.rpc", "requestfactory rpc",
            listOf("target?", "args?"), listOf("result", "receipt"),
            inputKinds = mapOf("target" to "text", "args" to "json"),
            outputKinds = mapOf("result" to "json", "receipt" to "json"),
            params = mapOf(
                "target" to LcncPortContract.LcncParamSpec(v = "session.info", ph = "Kotlin target name"),
                "args" to LcncPortContract.LcncParamSpec(v = "{}", ta = true, ph = "JSON object args"),
            ), isEffect = true),
        LcncPortContract("rf.batch", "requestfactory batch",
            listOf("operations"), listOf("ok", "receipts"),
            inputKinds = mapOf("operations" to "json"),
            outputKinds = mapOf("ok" to "json", "receipts" to "json"),
            params = mapOf(
                "operations" to LcncPortContract.LcncParamSpec(
                    v = """[{"op":"rpc","target":"session.info","args":{}}]""",
                    ta = true,
                    ph = "RequestFactory operation objects",
                ),
            ), isEffect = true),

        // ── media: an audio/video player as a PATCH PANEL citizen — every
        // control is a patch point: url arrives on a text wire, the
        // transports are trigger signals, `ended` fires downstream.
        // Nothing internal.
        LcncPortContract("media.player", "audio/video player",
            listOf("url", "play?", "stop?", "rewind?", "volume?"), listOf("state", "ended"),
            inputKinds = mapOf("url" to "text", "play" to "trigger", "stop" to "trigger", "rewind" to "trigger", "volume" to "num"),
            outputKinds = mapOf("state" to "json", "ended" to "trigger"),
            wide = true),
        // generic controls: a nameable button (its press is a trigger patch
        // point) and a slider (its value is a num patch point — volume, gain…)
        LcncPortContract("button", "button (nameable trigger)",
            emptyList(), listOf("press"),
            outputKinds = mapOf("press" to "trigger"),
            params = mapOf("label" to LcncPortContract.LcncParamSpec(v = "press", ph = "what this button says"))),
        LcncPortContract("slider", "slider (num patch point)",
            emptyList(), listOf("value"),
            outputKinds = mapOf("value" to "num"),
            params = mapOf(
                "label" to LcncPortContract.LcncParamSpec(ph = "what this slider sets"),
                "min" to LcncPortContract.LcncParamSpec(v = "0"),
                "max" to LcncPortContract.LcncParamSpec(v = "1"),
                "step" to LcncPortContract.LcncParamSpec(v = "0.01"),
                "value" to LcncPortContract.LcncParamSpec(v = "0.8"),
            )),
        // the text literal that feeds text patch points (a url, a prompt…)
        LcncPortContract("text.value", "text literal",
            emptyList(), listOf("value"),
            outputKinds = mapOf("value" to "text"),
            params = mapOf("value" to LcncPortContract.LcncParamSpec(ph = "the text this node emits"))),
        // …and its json twin. Mating is exact kind equality, so `text` could
        // never reach a json socket: bullets, fields, args, command, facts had
        // NO hand-authorable source at all, and every preset that needed one
        // shipped with the socket empty. This is that source.
        LcncPortContract("json.value", "json literal",
            emptyList(), listOf("value"),
            outputKinds = mapOf("value" to "json"),
            params = mapOf("value" to LcncPortContract.LcncParamSpec(
                v = "[]", ta = true, ph = "the json this node emits — edit me"))),

        // ── LCNC composition ─────────────────────────────────────────
        LcncPortContract("dom.board", "draggable grouped board (generic)",
            listOf("groups", "columns?"), listOf("move"),
            mapOf("groups" to LcncCardinality.ONE, "move" to LcncCardinality.MANY),
            mapOf("move" to listOf("kanban.move")),
            inputKinds = mapOf("groups" to "json", "columns" to "json"),
            outputKinds = mapOf("move" to "json"),
            params = mapOf(
                "idField" to LcncPortContract.LcncParamSpec(v = "id"),
                "titleField" to LcncPortContract.LcncParamSpec(v = "title"),
                "subtitleField" to LcncPortContract.LcncParamSpec(v = "id"),
                "badgeField" to LcncPortContract.LcncParamSpec(v = "priority"),
            ),
            wide = true),

        // ── sinks (no outputs) ───────────────────────────────────────
        LcncPortContract("display", "display",
            listOf("x"), emptyList(),
            mapOf("x" to LcncCardinality.MANY),
            mapOf("x" to listOf("identity")),
            inputKinds = mapOf("x" to "json"),
            isSink = true),
        LcncPortContract("gauge", "gauge",
            listOf("x"), emptyList(),
            mapOf("x" to LcncCardinality.MANY),
            inputKinds = mapOf("x" to "json"),
            params = mapOf("label" to LcncPortContract.LcncParamSpec(v = "value")),
            isSink = true),

        // ── mux / chat ───────────────────────────────────────────────
        LcncPortContract("mux.chat", "mux chat (provider-neutral)",
            listOf("prompt?"), listOf("content", "model"),
            inputKinds = mapOf("prompt" to "text"),
            outputKinds = mapOf("content" to "text", "model" to "id"),
            params = mapOf(
                "prompt" to LcncPortContract.LcncParamSpec(ta = true, ph = "prompt (or wire one in)"),
                "system" to LcncPortContract.LcncParamSpec(ph = "system (optional)"),
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "512"),
                "temperature" to LcncPortContract.LcncParamSpec(v = "0.2"),
                "brief" to LcncPortContract.LcncParamSpec(ph = "root frame binding to read when no prompt is wired (first-seat brief)"),
                // the seat's admissible endpoints: keymux key × model, a LIST —
                // lawyer/agent/worker/scribe/curator seats all author it here.
                // Sent as `models` on /api/mux/chat; routing consumption is the
                // model code's own seam.
                "models" to LcncPortContract.LcncParamSpec(cols = listOf("key", "model")),
            ), isEffect = true),

        // ── project / scope ──────────────────────────────────────────
        LcncPortContract("project.mount", "mount scope (git|assets)",
            listOf("path?"), listOf("scope"),
            inputKinds = mapOf("path" to "text"),
            outputKinds = mapOf("scope" to "id"),
            params = mapOf("path" to LcncPortContract.LcncParamSpec(ph = "/absolute/dir — or drop a folder on the canvas")), isEffect = true),

        // ── knowledge / beliefs ──────────────────────────────────────
        LcncPortContract("kg.ingest", "kg → nal (turtle/kif)",
            listOf("text?"), listOf("report"),
            inputKinds = mapOf("text" to "text"),
            outputKinds = mapOf("report" to "json"),
            params = mapOf("kg" to LcncPortContract.LcncParamSpec(ta = true, ph = "@prefix ex: <...> .\nex:a ex:causes ex:b .")), isEffect = true),
        LcncPortContract("beliefs.review", "turn review (induction)",
            listOf("facts"), listOf("landed"),
            inputKinds = mapOf("facts" to "json"),
            outputKinds = mapOf("landed" to "json"),
            params = mapOf("turnSucceeded" to LcncPortContract.LcncParamSpec(v = "true", opts = BOOLEAN_OPTIONS)), isEffect = true),
        LcncPortContract("beliefs.resonate", "resonance (support/refutation)",
            listOf("goal?"), listOf("synonyms", "antonyms"),
            inputKinds = mapOf("goal" to "text"),
            outputKinds = mapOf("synonyms" to "json", "antonyms" to "json"),
            params = mapOf(
                "goal" to LcncPortContract.LcncParamSpec(ph = "solver proposal"),
                "taxonomy" to LcncPortContract.LcncParamSpec(),
                "mode" to LcncPortContract.LcncParamSpec(v = "whitened", opts = listOf("whitened", "hamming")),
                "k" to LcncPortContract.LcncParamSpec(v = "4"),
            )),

        // ── navigation / UI ──────────────────────────────────────────
        LcncPortContract("program.ref", "program (dive in)",
            emptyList(), emptyList(),
            params = mapOf("name" to LcncPortContract.LcncParamSpec(ph = "stored program name")),
            isSink = true),
        LcncPortContract("note", "note",
            emptyList(), emptyList(),
            params = mapOf("text" to LcncPortContract.LcncParamSpec(ta = true, ph = "sticky note")),
            isSink = true),

        // ── W5.2: job control as first-class nodes ───────────────────
        // /api/invoke accepts the sealed JobCommand verbs (11 — Submit, Start,
        // Complete, Fail, Retry, Progress, Block, Cancel, Move, Acknowledge,
        // Retract); job.command composes the map so Cancel/Block/Retry/Retract
        // are reachable from a graph.
        LcncPortContract("job.command", "job command (verb + jobId + revision)",
            listOf("verb", "jobId", "expectedRevision?"), listOf("result"),
            inputKinds = mapOf("verb" to "text", "jobId" to "id", "expectedRevision" to "json"),
            outputKinds = mapOf("result" to "json")),
        LcncPortContract("job.batch", "job batch (multiple commands)",
            listOf("commands"), listOf("results"),
            inputKinds = mapOf("commands" to "json"),
            outputKinds = mapOf("results" to "json")),

        // ── W5.4: VM lifecycle completion ───────────────────────────
        // VmHandle.call and stats() exist but have no route; vm.call/vm.stats
        // make them reachable. vm.tiers surfaces VmSupervisor reports/bind/install.
        LcncPortContract("vm.call", "vm call (VmHandle.call)",
            listOf("vmId", "root", "args?"), listOf("value"),
            inputKinds = mapOf("vmId" to "id", "root" to "text", "args" to "json"),
            outputKinds = mapOf("value" to "json"), isEffect = true),
        LcncPortContract("vm.stats", "vm stats",
            listOf("vmId"), listOf("stats"),
            inputKinds = mapOf("vmId" to "id"),
            outputKinds = mapOf("stats" to "json")),
        LcncPortContract("vm.tiers", "vm provider tiers",
            emptyList(), listOf("tiers"),
            outputKinds = mapOf("tiers" to "json")),

        // ── Step 5: kanban/sheet/confix — the server runner family surfaced.
        // These execute IN THE DAEMON (LcncKanbanExperience/LcncSheetNodes via
        // POST /api/lcnc/run against the composed lcncRunners registry); the
        // browser posts params+inputs and renders outputs. One author of
        // execution semantics — the same runners webhook dispatch resolves.
        LcncPortContract("kanban.activeSheets", "kanban concentric sheets",
            listOf("trigger?"),
            listOf("board", "byStatus", "byPriority", "boardView", "orchestration", "laneOrder", "conditions"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf(
                "board" to "json", "byStatus" to "json", "byPriority" to "json",
                "boardView" to "json", "orchestration" to "json",
                "laneOrder" to "json", "conditions" to "json",
            )),
        LcncPortContract("kanban.submit", "kanban submit (new card)",
            listOf("command?"), listOf("accepted", "jobId", "revision", "sheets"),
            inputKinds = mapOf("command" to "json"),
            outputKinds = mapOf("accepted" to "json", "jobId" to "id", "revision" to "json", "sheets" to "json"),
            params = mapOf(
                "jobId" to LcncPortContract.LcncParamSpec(ph = "card-…", optsFrom = "board.get#json.items[].id"),
                "title" to LcncPortContract.LcncParamSpec(ph = "card title"),
                "priority" to LcncPortContract.LcncParamSpec(v = "2"),
                "idempotencyKey" to LcncPortContract.LcncParamSpec(ph = "unique per submit"),
            ), isEffect = true),
        LcncPortContract("kanban.move", "kanban move (WAL command)",
            listOf("command?"), listOf("accepted", "jobId", "revision", "sheets"),
            inputKinds = mapOf("command" to "json"),
            outputKinds = mapOf("accepted" to "json", "jobId" to "id", "revision" to "json", "sheets" to "json"),
            params = mapOf(
                "jobId" to LcncPortContract.LcncParamSpec(ph = "wire a command in, or type one", optsFrom = "board.get#json.items[].id"),
                "toColumn" to LcncPortContract.LcncParamSpec(ph = "target column id", optsFrom = "board.get#json.columns[].id"),
                "expectedRevision" to LcncPortContract.LcncParamSpec(ph = "card revision"),
                "idempotencyKey" to LcncPortContract.LcncParamSpec(ph = "unique per gesture"),
            ), isEffect = true),
        LcncPortContract("kanban.import", "kanban import (plan doc → cards)",
            listOf("text?"), listOf("parsed", "imported", "duplicates", "jobIds"),
            inputKinds = mapOf("text" to "text"),
            outputKinds = mapOf("parsed" to "json", "imported" to "json", "duplicates" to "json", "jobIds" to "json"),
            params = mapOf(
                "text" to LcncPortContract.LcncParamSpec(ta = true, ph = "- one bullet per card (dedupe by content hash)"),
            ), isEffect = true),
        LcncPortContract("kanban.attention", "NARS attention garnish (belief bag)",
            listOf("trigger?"), listOf("cards", "ordered"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("cards" to "json", "ordered" to "json")),
        LcncPortContract("kanban.drift", "NARS board drift (Hotelling T²)",
            listOf("trigger?"), listOf("t2", "alarm"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("t2" to "json", "alarm" to "json")),
        LcncPortContract("kanban.review", "TurnReview glosses (board window drain)",
            listOf("trigger?"), listOf("minted", "count"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("minted" to "json", "count" to "json")),
        LcncPortContract("list.format", "declarative list → lines projector",
            listOf("x"), listOf("lines"),
            inputKinds = mapOf("x" to "json"),
            outputKinds = mapOf("lines" to "json"),
            params = mapOf(
                "template" to LcncPortContract.LcncParamSpec(ph = "{field} substitution, e.g. {id}: attention={attention}"),
                "limit" to LcncPortContract.LcncParamSpec(ph = "max rows (empty = all)"),
            )),
        LcncPortContract("kanban.alerts", "board rule alerts (productions' live tail)",
            listOf("trigger?"), listOf("breaches", "stalls", "cycles", "ready"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("breaches" to "json", "stalls" to "json", "cycles" to "json", "ready" to "json")),
        LcncPortContract("confix.sheets", "json → confix sheet family",
            listOf("json?"), listOf("sheets", "sheet"),
            inputKinds = mapOf("json" to "json"),
            outputKinds = mapOf("sheets" to "json", "sheet" to "json"),
            params = mapOf(
                "json" to LcncPortContract.LcncParamSpec(ta = true, ph = "JSON (or wire it in)"),
                "id" to LcncPortContract.LcncParamSpec(v = "confix"),
                "title" to LcncPortContract.LcncParamSpec(ph = "sheet title"),
            )),
        // json is OPTIONAL: the runner falls back to the `json` param when the
        // port is unwired (the LcncOperationalSheets use case runs on params).
        LcncPortContract("confix.pickPath", "json path → sheet",
            listOf("json?"), listOf("found", "path", "sheets", "sheet"),
            inputKinds = mapOf("json" to "json"),
            outputKinds = mapOf("found" to "json", "path" to "json", "sheets" to "json", "sheet" to "json"),
            params = mapOf("path" to LcncPortContract.LcncParamSpec(ph = "dot.or/slash path"))),
        LcncPortContract("sheet.flatten", "sheet flatten (refs → ids)",
            listOf("sheet"), listOf("sheet"),
            inputKinds = mapOf("sheet" to "json"),
            outputKinds = mapOf("sheet" to "json")),
        LcncPortContract("sheet.filter", "sheet filter (column = value)",
            listOf("sheet"), listOf("sheet"),
            inputKinds = mapOf("sheet" to "json"),
            outputKinds = mapOf("sheet" to "json"),
            params = mapOf(
                "columnName" to LcncPortContract.LcncParamSpec(ph = "column"),
                "columnValue" to LcncPortContract.LcncParamSpec(ph = "value"),
            )),
        LcncPortContract("sheet.count", "sheet row count",
            listOf("sheet"), listOf("count"),
            inputKinds = mapOf("sheet" to "json"),
            outputKinds = mapOf("count" to "json")),
        LcncPortContract("sheet.columns", "sheet schema",
            listOf("sheet"), listOf("columns"),
            inputKinds = mapOf("sheet" to "json"),
            outputKinds = mapOf("columns" to "json")),
        LcncPortContract("sheet.cell", "sheet cell (row, column)",
            listOf("sheet"), listOf("value"),
            inputKinds = mapOf("sheet" to "json"),
            outputKinds = mapOf("value" to "json"),
            params = mapOf(
                "row" to LcncPortContract.LcncParamSpec(v = "0"),
                "column" to LcncPortContract.LcncParamSpec(v = "0"),
            )),
        // The concentric treesheet VIEW — the one sheet primitive that touches the
        // DOM (like dom.board): crumb from `parent` chains, SheetRef cells drill in.
        LcncPortContract("sheet.concentric", "concentric treesheets (drill-in)",
            listOf("board", "byStatus?", "byPriority?", "orchestration?"), emptyList(),
            inputKinds = mapOf(
                "board" to "json", "byStatus" to "json",
                "byPriority" to "json", "orchestration" to "json",
            ),
            isSink = true, wide = true),

        // ── continents: heap + legion standings on the same canvas ───
        LcncPortContract("graal.heap", "heap continent (bytes by class)",
            listOf("trigger?"), listOf("heap"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("heap" to "json"),
            params = mapOf("lane" to LcncPortContract.LcncParamSpec(
                v = "allocation", opts = listOf("allocation", "histogram"))),
            wide = true),
        LcncPortContract("mux.standings", "quota legion standings",
            listOf("trigger?"), listOf("standings"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("standings" to "json"),
            wide = true),

        // ── wise-micali step 11: the workgroup vote (Seats + quorum; ties signal) ──
        LcncPortContract("panel.vote", "panel vote (odd decides · even tie = research)",
            listOf("ballots"), listOf("verdict", "accepted", "tie", "triage", "tally", "dissent"),
            inputKinds = mapOf("ballots" to "json"),
            outputKinds = mapOf(
                "verdict" to "text", "accepted" to "json", "tie" to "json",
                "triage" to "text", "tally" to "json", "dissent" to "json",
            ),
            params = mapOf("quorum" to LcncPortContract.LcncParamSpec(ph = "weighted quorum — blank = strict majority of OK ballots"))),

        // ── Step K: adaptive context engineering as LCNC ─────────────────
        LcncPortContract("context.fold", "playbook fold (deterministic)",
            listOf("bullets"), listOf("playbook"),
            inputKinds = mapOf("bullets" to "json"),
            outputKinds = mapOf("playbook" to "text")),
        LcncPortContract("context.assemble", "context chain (cache identity)",
            listOf("toolsSystem?", "playbook?", "envelope?", "tail?"), listOf("chain", "chainHead"),
            inputKinds = mapOf(
                "toolsSystem" to "text", "playbook" to "text",
                "envelope" to "text", "tail" to "text",
            ),
            outputKinds = mapOf("chain" to "json", "chainHead" to "id"),
            params = mapOf(
                "model" to LcncPortContract.LcncParamSpec(v = ""),
                "effort" to LcncPortContract.LcncParamSpec(v = "medium", opts = listOf("low", "medium", "high")),
                "tools" to LcncPortContract.LcncParamSpec(v = "", ph = "comma-separated tool names"),
            )),

        // ── P2: map-reduce as LCNC (Confix DSL lowering; never JS) ───────
        LcncPortContract(VIEW_EMIT, "view emit (declarative map)",
            listOf("documents?"), listOf("rows"),
            inputKinds = mapOf("documents" to "json"),
            outputKinds = mapOf("rows" to "json"),
            params = mapOf(
                "ddoc" to LcncPortContract.LcncParamSpec(v = "_design/lcnc"),
                "view" to LcncPortContract.LcncParamSpec(ph = "view name; program name when blank"),
                "key" to LcncPortContract.LcncParamSpec(v = "_id", ph = "_id | doc.field | const:value"),
                "value" to LcncPortContract.LcncParamSpec(v = "const:1", ph = "_doc | doc.field | const:value"),
                "arrayField" to LcncPortContract.LcncParamSpec(ph = "non-empty → emit each array element"),
            )),
        LcncPortContract(VIEW_REDUCE, "view reduce (bounded monoid)",
            listOf("rows"), listOf("reduced"),
            inputKinds = mapOf("rows" to "json"),
            outputKinds = mapOf("reduced" to "json"),
            params = mapOf("reducer" to LcncPortContract.LcncParamSpec(
                v = "_count", opts = listOf("_count", "_sum", "_stats", "rollup-count")))),

        // ── P4: bot seat — only this node may spend tokens ─────────────
        // ConstructionBotNode.runner is the one implementation behind both
        // `read.construct` and `nal.mint` below (nal.mint is the NAL-domain
        // alias); both contracts must describe its REAL shape: in `lines`,
        // out `{accepted, refused, aggregates}` (ConstructionReadingReceipt).
        LcncPortContract("read.construct", "read causal constructions (bot proposes; gate disposes)",
            listOf("lines"), listOf("accepted", "refused", "aggregates"),
            inputKinds = mapOf("lines" to "json"),
            outputKinds = mapOf("accepted" to "json", "refused" to "json", "aggregates" to "json"),
            params = mapOf(
                "model" to LcncPortContract.LcncParamSpec(ph = "ModelMux route/model id"),
                "window" to LcncPortContract.LcncParamSpec(v = "16"),
            )),

        // ── NAL belief-bag as LCNC nodes ─────────────────────────────
        LcncPortContract("nal.mint", "mint beliefs from construction receipts",
            listOf("lines"), listOf("accepted", "refused", "aggregates"),
            inputKinds = mapOf("lines" to "json"),
            outputKinds = mapOf("accepted" to "json", "refused" to "json", "aggregates" to "json"),
            params = mapOf(
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "1024"),
            ), isEffect = true),
        LcncPortContract("nal.decay", "attention decay pulse (thin wrapper)",
            listOf("trigger?", "after?"), listOf("decayed"),
            // `after?` is the same pulse-only port under a second declared
            // kind: it sequences the decay pulse behind a data-bearing
            // upstream (e.g. read.construct's aggregates) without the
            // runner reading the payload — decayRunner ignores all inputs.
            inputKinds = mapOf("trigger" to "trigger", "after" to "json"),
            outputKinds = mapOf("decayed" to "trigger"), isEffect = true),
        LcncPortContract("nal.recall", "belief recall (top/sample/near)",
            listOf("trigger?"), listOf("beliefs"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("beliefs" to "json"),
            params = mapOf(
                "mode" to LcncPortContract.LcncParamSpec(v = "top", opts = listOf("top", "sample", "near")),
                "k" to LcncPortContract.LcncParamSpec(v = "16"),
            )),
        LcncPortContract("skill.decay", "skill budget decay (per-skill AttentionEconomy)",
            listOf("trigger?"), listOf("budgets"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("budgets" to "json")),
        LcncPortContract("nal.attend", "attend (budget rekey, evidence untouched)",
            listOf("angular?"), listOf("attended"),
            inputKinds = mapOf("angular" to "id"),
            outputKinds = mapOf("attended" to "json"),
            params = mapOf(
                "angular" to LcncPortContract.LcncParamSpec(ph = "angular (decimal string) when none is wired"),
                "p" to LcncPortContract.LcncParamSpec(ph = "priority 0..1 (resident value kept when blank)"),
                "d" to LcncPortContract.LcncParamSpec(ph = "durability 0..1"),
                "q" to LcncPortContract.LcncParamSpec(ph = "quality 0..1"),
            ), isEffect = true),
        LcncPortContract("nal.reinforce", "reinforce (evidence delta, budget untouched)",
            listOf("angular?"), listOf("revised"),
            inputKinds = mapOf("angular" to "id"),
            outputKinds = mapOf("revised" to "json"),
            params = mapOf(
                "angular" to LcncPortContract.LcncParamSpec(ph = "angular (decimal string) when none is wired"),
                "wPlus" to LcncPortContract.LcncParamSpec(v = "1"),
                "wMinus" to LcncPortContract.LcncParamSpec(v = "0"),
            ), isEffect = true),
        LcncPortContract("nal.encode", "encode 64-bit centroid (AngularCodec)",
            listOf("subject?"), listOf("centroid"),
            inputKinds = mapOf("subject" to "text"),
            outputKinds = mapOf("centroid" to "id"),
            params = mapOf(
                "subject" to LcncPortContract.LcncParamSpec(ph = "subject term when none is wired"),
                "object" to LcncPortContract.LcncParamSpec(ph = "object term (optional)"),
                "taxonomy" to LcncPortContract.LcncParamSpec(ph = "taxonomy key (optional)"),
                "relation" to LcncPortContract.LcncParamSpec(v = "CAUSALITY"),
                "grade" to LcncPortContract.LcncParamSpec(v = "NONE"),
            )),

        // ── Rule admission: eternal law into the LIVE rete ────────────
        LcncPortContract("nal.rule.admit", "admit eternal rules (live rete)",
            listOf("rules?"), listOf("admitted", "ruleCids"),
            inputKinds = mapOf("rules" to "json"),
            outputKinds = mapOf("admitted" to "json", "ruleCids" to "json"),
            params = mapOf(
                "antecedent" to LcncPortContract.LcncParamSpec(ph = "one rule's antecedent when no rules wired"),
                "consequent" to LcncPortContract.LcncParamSpec(ph = "one rule's consequent"),
                "copula" to LcncPortContract.LcncParamSpec(v = "==>", opts = listOf("==>", "<=>")),
                "discount" to LcncPortContract.LcncParamSpec(v = "1.0"),
            ), isEffect = true),
        LcncPortContract("nal.rules.fromKg", "kg → eternal rules (bridge + admit)",
            listOf("kgText?"), listOf("rules", "admitted", "rejectedTemporal"),
            inputKinds = mapOf("kgText" to "text"),
            outputKinds = mapOf("rules" to "json", "admitted" to "json", "rejectedTemporal" to "json"),
            params = mapOf(
                "kgText" to LcncPortContract.LcncParamSpec(ta = true, ph = "(=> fire smoke)\nex:a ex:causes ex:b ."),
                "confidence" to LcncPortContract.LcncParamSpec(v = "0.9"),
                "copula" to LcncPortContract.LcncParamSpec(ph = "force every bridged copula", opts = listOf("==>", "<=>")),
            ), isEffect = true),
        LcncPortContract("nars.reteFire", "nars rete fire (one rule, in-process)",
            listOf("assertions?"), listOf("firings", "ruleCid"),
            inputKinds = mapOf("assertions" to "json"),
            outputKinds = mapOf("firings" to "json", "ruleCid" to "id"),
            params = mapOf(
                "antecedent" to LcncPortContract.LcncParamSpec(ph = "rule antecedent (required)"),
                "consequent" to LcncPortContract.LcncParamSpec(ph = "rule consequent (required)"),
                "copula" to LcncPortContract.LcncParamSpec(v = "==>", opts = listOf("==>", "<=>")),
                "discount" to LcncPortContract.LcncParamSpec(v = "0.5"),
            )),

        // ── CoreNLP extract (NER + deps) ──────────────────────────
        LcncPortContract(SubVm.LEGO_PREFIX + "corenlp.extract", "corenlp extract (NER, deps)",
            listOf("text?"), listOf("sentences"),
            inputKinds = mapOf("text" to "text"),
            outputKinds = mapOf("sentences" to "json"),
            params = mapOf(
                "facet" to LcncPortContract.LcncParamSpec(v = "JVM"),
                // Guest module supplying this lego's classes (utils/subvm/<module>). Declared so the
                // surface can express the override; blank means the lego's own default.
                "module" to LcncPortContract.LcncParamSpec(v = ""),
                "text" to LcncPortContract.LcncParamSpec(ta = true, ph = "inline text when nothing wired"),
                "annotators" to LcncPortContract.LcncParamSpec(v = "tokenize,ssplit,pos,lemma,depparse,ner"),
                "world" to LcncPortContract.LcncParamSpec(ph = "comma-separated host dirs seeded to /workspace"),
                "trust" to LcncPortContract.LcncParamSpec(v = "OWN", opts = VM_TRUST_OPTIONS),
                "keep" to LcncPortContract.LcncParamSpec(v = "false", opts = BOOLEAN_OPTIONS),
            )),

        // ── State freeze / thaw (persistence seam) ────────────────
        LcncPortContract("state.freeze", "freeze bag+KB to CAS (snapshot)",
            listOf("trigger?"), listOf("snapshot"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("snapshot" to "json"), isEffect = true),
        LcncPortContract("state.thaw", "thaw bag+KB from CAS (restore)",
            listOf("trigger?"), listOf("restored"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("restored" to "json"), isEffect = true),

        // ── Legal domain nodes ────────────────────────────────────
        LcncPortContract("legal.ingest", "legal document ingest (eyecite + LLM propose/gate)",
            listOf("text?"), listOf("documentCid", "citations", "elements", "brief"),
            inputKinds = mapOf("text" to "text"),
            outputKinds = mapOf("documentCid" to "id", "citations" to "json", "elements" to "json", "brief" to "text"),
            params = mapOf(
                "text" to LcncPortContract.LcncParamSpec(ta = true, ph = "legal document text (or wire one in)"),
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "2048"),
                "brief" to LcncPortContract.LcncParamSpec(ph = "root frame binding to read when no text is wired (human-oversight brief)"),
            ), isEffect = true),
        LcncPortContract("legal.evidence", "evidence-bank query (shared KIF bank) → brief folded with prior facts",
            listOf("documentCid?", "brief?"), listOf("brief"),
            inputKinds = mapOf("documentCid" to "id", "brief" to "text"),
            outputKinds = mapOf("brief" to "text"),
            params = mapOf(
                "documentCid" to LcncPortContract.LcncParamSpec(ph = "doc CID to query when none is wired"),
                "brief" to LcncPortContract.LcncParamSpec(ta = true, ph = "brief text to query when none is wired"),
            )),
        // ── BrainClient decomposition as LCNC ──────────────────────
        // credential.enter: manual key-type/url/api-type/key entry →
        //   stored in CouchDB via CouchKeyStore, resolvable by KeyMux.
        //   The key field is a secret (password input) — the panel renders
        //   type=password when the placeholder carries the `secret:` prefix.
        LcncPortContract("credential.enter", "credential enter (manual → CouchDB)",
            emptyList(), listOf("credential"),
            outputKinds = mapOf("credential" to "json"),
            params = mapOf(
                "key_type" to LcncPortContract.LcncParamSpec(
                    ph = "provider id", v = "nvidia",
                    opts = listOf("nvidia", "openai", "deepseek", "groq", "zai",
                        "cerebras", "xai", "moonshot", "minimax", "openrouter", "perplexity")),
                "url" to LcncPortContract.LcncParamSpec(ph = "base URL", v = "https://integrate.api.nvidia.com/v1"),
                "api_type" to LcncPortContract.LcncParamSpec(v = "openai", opts = listOf("openai", "anthropic", "google")),
                "key" to LcncPortContract.LcncParamSpec(ph = "secret:API key"),
            ), isEffect = true),
        // prompt.chat: prompt → model → content. Credential precedence,
        //   highest first: (1) the ModelMux key chain — KeyMux resolves
        //   llm.<provider>.key from env → dotenv → harness stores, so a model
        //   with a resolvable key runs with NOTHING entered; (2) the prefill
        //   CouchKeyStore entry; (3) the manual url+key fields. The key field
        //   is a password input (secret: prefix).
        LcncPortContract("prompt.chat", "prompt chat (modelmux, env-first)",
            listOf("prompt?"), listOf("content", "model", "ok", "error"),
            inputKinds = mapOf("prompt" to "text"),
            outputKinds = mapOf("content" to "text", "model" to "id", "ok" to "json", "error" to "text"),
            params = mapOf(
                "prompt" to LcncPortContract.LcncParamSpec(ta = true, ph = "prompt text"),
                "prefill" to LcncPortContract.LcncParamSpec(
                    ph = "prefill from daemon keys",
                    opts = listOf("(none — use env/harness keys)", "nvidia", "openai", "deepseek",
                        "groq", "zai", "cerebras", "xai", "moonshot", "minimax", "openrouter", "perplexity")),
                "url" to LcncPortContract.LcncParamSpec(ph = "base URL (manual fallback)", v = "https://integrate.api.nvidia.com/v1"),
                "key" to LcncPortContract.LcncParamSpec(ph = "secret:API key (manual fallback)"),
                "headers" to LcncPortContract.LcncParamSpec(
                    cols = listOf("name", "value"),
                    ph = "extra headers (k-v pairs)"),
                "model" to LcncPortContract.LcncParamSpec(ph = "model id (mux.models lists what resolves)", v = "deepseek-ai/deepseek-v4-flash", optsFrom = "mux.models#models[].id"),
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "256"),
                "temperature" to LcncPortContract.LcncParamSpec(v = "0.2"),
            ), isEffect = true),
        // result.confirm: content is the completion signal. `ok` and `error`
        // refine that completion when the producer has an explicit verdict;
        // content-only producers are successful confirmations, not starved
        // nodes (and not blank ERROR cards).
        LcncPortContract("result.confirm", "result confirmation (OK/ERROR HTML)",
            listOf("content", "ok?", "error?"), emptyList(),
            inputKinds = mapOf("content" to "text", "ok" to "json", "error" to "text"),
            isSink = true),

        // ── Sub-VM module legos: tika / corenlp / camel / graalce ──────
        LcncPortContract(SubVm.LEGO_PREFIX + "tika", "tika: extract text+metadata in a sub-VM",
            listOf("files?"), listOf("text"),
            inputKinds = mapOf("files" to "json"),
            outputKinds = mapOf("text" to "text"),
            params = mapOf(
                "facet" to LcncPortContract.LcncParamSpec(v = "JVM", opts = listOf("JVM", "GRAAL_JS", "GRAAL_PYTHON", "GRAAL_RUBY", "GRAAL_CLOJURE", "GRAAL_LLVM")),
                // Guest module supplying this lego's classes (utils/subvm/<module>). Declared so the
                // surface can express the override; blank means the lego's own default.
                "module" to LcncPortContract.LcncParamSpec(v = ""),
                "in:files" to LcncPortContract.LcncParamSpec(ph = "comma-separated workspace paths (world-seeded)"),
                "in:text" to LcncPortContract.LcncParamSpec(ph = "fallback when no files wired"),
                "world" to LcncPortContract.LcncParamSpec(ph = "comma-separated host dirs seeded to /workspace"),
                "trust" to LcncPortContract.LcncParamSpec(v = "OWN", opts = VM_TRUST_OPTIONS),
                "keep" to LcncPortContract.LcncParamSpec(v = "false", opts = BOOLEAN_OPTIONS),
            )),
        LcncPortContract(SubVm.LEGO_PREFIX + "corenlp", "corenlp: Stanford pipeline in a sub-VM",
            listOf("text?"), listOf("tokens"),
            inputKinds = mapOf("text" to "text"),
            outputKinds = mapOf("tokens" to "text"),
            params = mapOf(
                "facet" to LcncPortContract.LcncParamSpec(v = "JVM"),
                // Guest module supplying this lego's classes (utils/subvm/<module>). Declared so the
                // surface can express the override; blank means the lego's own default.
                "module" to LcncPortContract.LcncParamSpec(v = ""),
                "annotators" to LcncPortContract.LcncParamSpec(v = "tokenize,ssplit,pos,lemma,depparse"),
                "text" to LcncPortContract.LcncParamSpec(ta = true, ph = "inline text when nothing wired"),
                "world" to LcncPortContract.LcncParamSpec(ph = "comma-separated host dirs seeded to /workspace"),
                "trust" to LcncPortContract.LcncParamSpec(v = "OWN", opts = VM_TRUST_OPTIONS),
                "keep" to LcncPortContract.LcncParamSpec(v = "false", opts = BOOLEAN_OPTIONS),
            )),
        // Read-only audit of the classpaths this daemon can execute guest code from. No inputs, so
        // nothing upstream can steer it; two params and two outputs, so its whole capability is
        // visible here. It cannot mount, unmount or install — not because the body refuses, but
        // because no such port is declared.
        LcncPortContract(SubVm.LEGO_PREFIX + "modules", "modules: audit mounted guest classpaths",
            emptyList(), listOf("modules", "count"),
            outputKinds = mapOf("modules" to "json", "count" to "json"),
            params = mapOf(
                "module" to LcncPortContract.LcncParamSpec(v = ""),
                "verify" to LcncPortContract.LcncParamSpec(v = "false", opts = BOOLEAN_OPTIONS),
            ),
        ),
        LcncPortContract(SubVm.LEGO_PREFIX + "camel", "camel: route DSL in a sub-VM",
            listOf("messages?"), listOf("routed"),
            inputKinds = mapOf("messages" to "json"),
            outputKinds = mapOf("routed" to "text"),
            params = mapOf(
                "facet" to LcncPortContract.LcncParamSpec(v = "JVM"),
                // Guest module supplying this lego's classes (utils/subvm/<module>). Declared so the
                // surface can express the override; blank means the lego's own default.
                "module" to LcncPortContract.LcncParamSpec(v = ""),
                "from" to LcncPortContract.LcncParamSpec(v = "direct:lcnc"),
                "to" to LcncPortContract.LcncParamSpec(v = "log:lcnc"),
                "world" to LcncPortContract.LcncParamSpec(ph = "comma-separated host dirs seeded to /workspace"),
                "trust" to LcncPortContract.LcncParamSpec(v = "OWN", opts = VM_TRUST_OPTIONS),
                "keep" to LcncPortContract.LcncParamSpec(v = "false", opts = BOOLEAN_OPTIONS),
            )),
        LcncPortContract(SubVm.LEGO_PREFIX + "graalce", "graalce: any Graal language, inline source",
            listOf("context?"), listOf("result"),
            inputKinds = mapOf("context" to "json"),
            outputKinds = mapOf("result" to "text"),
            params = mapOf(
                "facet" to LcncPortContract.LcncParamSpec(v = "GRAAL_JS", opts = VM_FACET_NAMES),
                "source" to LcncPortContract.LcncParamSpec(ta = true, ph = "guest source; print() lands in text"),
                "world" to LcncPortContract.LcncParamSpec(ph = "comma-separated host dirs seeded to /workspace"),
                "trust" to LcncPortContract.LcncParamSpec(v = "OWN", opts = VM_TRUST_OPTIONS),
                "keep" to LcncPortContract.LcncParamSpec(v = "false", opts = BOOLEAN_OPTIONS),
            )),

        // ── Legal council (design/legal-council-3x5.md) — the node family
        // behind CouncilProgram.build / preset-council. MANY cardinality on
        // the fold/record parts is LOAD-BEARING: LcncRunner.isManyInput
        // consults these declarations to collect multi-wire ports as lists.
        // Kinds: the council's assembly lane is json end to end (scope.in
        // values and scope.out yields are json-kinded, and every fold/prompt
        // /content port sits between them); the ONE text-kinded legacy feed
        // — legal.evidence's brief into the ruling fold — rides text.fold's
        // dedicated `brief?` port so every declared kind pair on every
        // preset wire agrees (the kind-parity gate checks each one).
        LcncPortContract("council.seat", "council seat (one model call, degrade-loudly)",
            listOf("prompt"), listOf("content", "labeled", "model", "record"),
            inputKinds = mapOf("prompt" to "json"),
            outputKinds = mapOf("content" to "json", "labeled" to "json", "model" to "id", "record" to "json"),
            params = mapOf(
                "system" to LcncPortContract.LcncParamSpec(ta = true, ph = "seat system charge"),
                "persona" to LcncPortContract.LcncParamSpec(ph = "seat persona (experts)"),
                "panel" to LcncPortContract.LcncParamSpec(ph = "panel token (p1…)"),
                "seat" to LcncPortContract.LcncParamSpec(ph = "seat name (e1, synth, ruling…)"),
                "role" to LcncPortContract.LcncParamSpec(ph = "expert|rebuttal|synthesis|ruling|clarify"),
                "round" to LcncPortContract.LcncParamSpec(v = "1"),
                "charge" to LcncPortContract.LcncParamSpec(ph = "panel charge"),
                "model" to LcncPortContract.LcncParamSpec(ph = "preferred model id (roster failover behind it)"),
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "512"),
                "temperature" to LcncPortContract.LcncParamSpec(v = "0.2"),
                "contextId" to LcncPortContract.LcncParamSpec(ph = "council/<caseId>/<panel>/<seat> spend receipt"),
                "caseId" to LcncPortContract.LcncParamSpec(ph = "convened case id"),
            )),
        LcncPortContract("text.fold", "text fold (dumb MANY-part concatenator)",
            listOf("parts", "brief?"), listOf("text"),
            mapOf("parts" to LcncCardinality.MANY),
            inputKinds = mapOf("parts" to "json", "brief" to "text"),
            outputKinds = mapOf("text" to "json"),
            params = mapOf(
                "label" to LcncPortContract.LcncParamSpec(ph = "fold header (== label ==)"),
                "separator" to LcncPortContract.LcncParamSpec(v = "\n\n---\n\n"),
                "numbered" to LcncPortContract.LcncParamSpec(v = "true", opts = BOOLEAN_OPTIONS),
            )),
        LcncPortContract("record.fold", "record fold (turn provenance gatherer)",
            listOf("parts"), listOf("turns"),
            mapOf("parts" to LcncCardinality.MANY),
            inputKinds = mapOf("parts" to "json"),
            outputKinds = mapOf("turns" to "json")),
        LcncPortContract("ruling.parse", "ruling parse (trailing JSON, strict-false booleans)",
            listOf("text"), listOf("verdict", "needsClarification", "clarificationQuestion", "mistrial", "text"),
            inputKinds = mapOf("text" to "json"),
            outputKinds = mapOf(
                "verdict" to "json", "needsClarification" to "json",
                "clarificationQuestion" to "text", "mistrial" to "json", "text" to "json",
            )),
        LcncPortContract("coalesce", "coalesce (a? over b — clarified wins, absent yields lose)",
            listOf("a?", "b"), listOf("value"),
            inputKinds = mapOf("a" to "json", "b" to "json"),
            outputKinds = mapOf("value" to "json")),
        LcncPortContract("council.convene", "council convene (config → drawn program)",
            listOf("config?"), listOf("program", "summary"),
            inputKinds = mapOf("config" to "json"),
            outputKinds = mapOf("program" to "json", "summary" to "json"),
            params = mapOf(
                "config" to LcncPortContract.LcncParamSpec(
                    ta = true, ph = "{\"panels\":[{name,charge,experts}],\"rounds\":2,…} — empty = DEFAULT_3x5"),
            ), isEffect = true),
        LcncPortContract("council.record", "council record (CAS + blackboard + couch + KIF + case lifecycle)",
            listOf("verdict", "transcript", "turns?", "caseId?", "documentCid?"), listOf("report"),
            mapOf("transcript" to LcncCardinality.MANY, "turns" to LcncCardinality.MANY),
            inputKinds = mapOf(
                "verdict" to "json", "transcript" to "json", "turns" to "json",
                "caseId" to "json", "documentCid" to "id",
            ),
            outputKinds = mapOf("report" to "json"),
            params = mapOf("caseId" to LcncPortContract.LcncParamSpec(v = "default")), isEffect = true),
        LcncPortContract("council.case", "council case read-back (index + transcript/verdict from CAS)",
            listOf("caseId?"), listOf("case"),
            inputKinds = mapOf("caseId" to "id"),
            outputKinds = mapOf("case" to "json"),
            params = mapOf("caseId" to LcncPortContract.LcncParamSpec(ph = "convened case id"))),

        // ── WikiSkill (arXiv 2608.27454) — the two consolidation legos.
        // ONE invocation is ONE pass: wiki.consolidate is a single Maintainer
        // iteration k, wiki.propose is a single Proposer pass. The iteration
        // LOOP is the caller's (a program document / successive runs), never
        // hidden inside a runner — cans and atoms. Wiki state lives under the
        // FORGE home the daemon wires, never the repo worktree.
        LcncPortContract(WIKI_CONSOLIDATE, "wiki consolidate (Wiki Maintainer: traces + prior wiki → PATCH edits)",
            listOf("cids?"), listOf("report"),
            inputKinds = mapOf("cids" to "json"),
            outputKinds = mapOf("report" to "json"),
            params = mapOf(
                "cids" to LcncPortContract.LcncParamSpec(
                    ta = true, ph = "sampled transcript cids (comma/newline separated) — success AND failure mix"),
                "iteration" to LcncPortContract.LcncParamSpec(v = "1"),
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "4096"),
                "temperature" to LcncPortContract.LcncParamSpec(v = "0.2"),
                "traceChars" to LcncPortContract.LcncParamSpec(v = "9000", ph = "per-trace context budget"),
                "model" to LcncPortContract.LcncParamSpec(ph = "preferred model id (roster failover behind it)"),
                "contextId" to LcncPortContract.LcncParamSpec(ph = "spend receipt id; also names the captured response file"),
            )),
        LcncPortContract(WIKI_PROPOSE, "wiki propose (Skill Proposer: multi-turn ReAct → ONE atomic proposal)",
            listOf("summary?"), listOf("report"),
            inputKinds = mapOf("summary" to "text"),
            outputKinds = mapOf("report" to "json"),
            params = mapOf(
                "summary" to LcncPortContract.LcncParamSpec(
                    ta = true, ph = "concise summary of training outcomes (the paper's third initial input)"),
                "maxTurns" to LcncPortContract.LcncParamSpec(v = "6"),
                "maxTokens" to LcncPortContract.LcncParamSpec(v = "4096"),
                "temperature" to LcncPortContract.LcncParamSpec(v = "0.2"),
                "readChars" to LcncPortContract.LcncParamSpec(v = "9000", ph = "per-read context budget"),
                "model" to LcncPortContract.LcncParamSpec(ph = "preferred model id"),
                "contextId" to LcncPortContract.LcncParamSpec(ph = "spend receipt id; also names the read log"),
            )),
    )

    fun compatible(sourceType: String, sourcePort: String): List<LcncPortContract> =
        all().filter { it.inputs.isNotEmpty() && it.inputs.any { p -> p.removeSuffix("?") == sourcePort.removeSuffix("?") } }

    fun find(type: String): LcncPortContract? = all().firstOrNull { it.type == type }
}
