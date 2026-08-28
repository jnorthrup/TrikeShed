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
) {
    /** One editable parameter: default value, optional dropdown options, multi-line flag, placeholder. */
    data class LcncParamSpec(
        val v: String = "",
        val opts: List<String> = emptyList(),
        val ta: Boolean = false,
        val ph: String = "",
    )
}

object LcncContracts {
    /** Full vocabulary — ONE author for node types, ports, titles, param defaults.
     *  inputKinds/outputKinds drive the mating filter; omit a kind and the type
     *  is invisible to drag-to-empty-space. */
    fun all(): List<LcncPortContract> = listOf(
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
        LcncPortContract("project.kill", "project db kill",
            listOf("trigger?"), listOf("verdict"),
            inputKinds = mapOf("trigger" to "trigger"),
            outputKinds = mapOf("verdict" to "json"),
            params = mapOf("name" to LcncPortContract.LcncParamSpec(ph = "project db name (hierarchy kill)"))),
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
                "facet" to LcncPortContract.LcncParamSpec(v = "python", opts = listOf("python", "js")),
                "wallMillis" to LcncPortContract.LcncParamSpec(v = "1800000"),
                "world" to LcncPortContract.LcncParamSpec(ph = "host dirs, comma-sep"),
            )),
        LcncPortContract("vm.eval", "vm eval",
            listOf("vmId", "source?"), listOf("value", "cid"),
            inputKinds = mapOf("vmId" to "id", "source" to "text"),
            outputKinds = mapOf("value" to "json", "cid" to "id"),
            params = mapOf("source" to LcncPortContract.LcncParamSpec(v = "1+1", ta = true))),
        LcncPortContract("vm.revoke", "vm revoke",
            listOf("vmId"), listOf("ok"),
            inputKinds = mapOf("vmId" to "id"),
            outputKinds = mapOf("ok" to "json")),
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
            params = mapOf("path" to LcncPortContract.LcncParamSpec(v = "/api/submit"))),

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
            )),

        // ── project / scope ──────────────────────────────────────────
        LcncPortContract("project.mount", "mount scope (git|assets)",
            listOf("path?"), listOf("scope"),
            inputKinds = mapOf("path" to "text"),
            outputKinds = mapOf("scope" to "id"),
            params = mapOf("path" to LcncPortContract.LcncParamSpec(ph = "/absolute/dir — or drop a folder on the canvas"))),

        // ── knowledge / beliefs ──────────────────────────────────────
        LcncPortContract("kg.ingest", "kg → nal (turtle/kif)",
            listOf("text?"), listOf("report"),
            inputKinds = mapOf("text" to "text"),
            outputKinds = mapOf("report" to "json"),
            params = mapOf("kg" to LcncPortContract.LcncParamSpec(ta = true, ph = "@prefix ex: <...> .\nex:a ex:causes ex:b ."))),
        LcncPortContract("beliefs.review", "turn review (induction)",
            listOf("facts"), listOf("landed"),
            inputKinds = mapOf("facts" to "json"),
            outputKinds = mapOf("landed" to "json"),
            params = mapOf("turnSucceeded" to LcncPortContract.LcncParamSpec(v = "true", opts = listOf("true", "false")))),
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
            outputKinds = mapOf("value" to "json")),
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
                "jobId" to LcncPortContract.LcncParamSpec(ph = "card-…"),
                "title" to LcncPortContract.LcncParamSpec(ph = "card title"),
                "priority" to LcncPortContract.LcncParamSpec(v = "2"),
                "idempotencyKey" to LcncPortContract.LcncParamSpec(ph = "unique per submit"),
            )),
        LcncPortContract("kanban.move", "kanban move (WAL command)",
            listOf("command?"), listOf("accepted", "jobId", "revision", "sheets"),
            inputKinds = mapOf("command" to "json"),
            outputKinds = mapOf("accepted" to "json", "jobId" to "id", "revision" to "json", "sheets" to "json"),
            params = mapOf(
                "jobId" to LcncPortContract.LcncParamSpec(ph = "wire a command in, or type one"),
                "toColumn" to LcncPortContract.LcncParamSpec(ph = "target column id"),
                "expectedRevision" to LcncPortContract.LcncParamSpec(ph = "card revision"),
                "idempotencyKey" to LcncPortContract.LcncParamSpec(ph = "unique per gesture"),
            )),
        LcncPortContract("confix.sheets", "json → confix sheet family",
            listOf("json?"), listOf("sheets", "sheet"),
            inputKinds = mapOf("json" to "json"),
            outputKinds = mapOf("sheets" to "json", "sheet" to "json"),
            params = mapOf(
                "json" to LcncPortContract.LcncParamSpec(ta = true, ph = "JSON (or wire it in)"),
                "id" to LcncPortContract.LcncParamSpec(v = "confix"),
                "title" to LcncPortContract.LcncParamSpec(ph = "sheet title"),
            )),
        LcncPortContract("confix.pickPath", "json path → sheet",
            listOf("json"), listOf("found", "path", "sheets", "sheet"),
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
    )

    fun compatible(sourceType: String, sourcePort: String): List<LcncPortContract> =
        all().filter { it.inputs.isNotEmpty() && it.inputs.any { p -> p.removeSuffix("?") == sourcePort.removeSuffix("?") } }

    fun find(type: String): LcncPortContract? = all().firstOrNull { it.type == type }
}
