package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.VmEvent
import borg.trikeshed.vm.VmSupervisor

/**
 * The concentric composition surface — the ORIGINAL low-code/no-code UI, projected
 * in Kotlin (the daemon is the one executor; the browser never runs the graph).
 *
 * Everything it renders is already live state: contracts are the vocabulary, the
 * stored programs are the rings, [VmSupervisor] is the module substrate, and
 * `LcncRunner.runAll` walk receipts (`LcncScopeFrame.outputs` chains) are the trace.
 * There is no bespoke frontend here and no shadow state — the surface is a pure
 * function of daemon state, served over `GET /api/lcnc/concentric`, and every panel
 * is an LCNC ring document a wizard can author via `/api/lcnc/run`.
 *
 * The trace IS the CCEK composition: ring entry/exit is LcncScopeFrame
 * (CoroutineContext element) nesting; node outputs are the warm base each ring
 * reads through the frame chain; the walk's authored order is the execution order.
 * A panel that traces this renders the CCEK composition directly.
 */
object ConcentricSurface {

    /**
     * The full surface: module drawer, ring inventory, wizard roster, and the
     * substrate provenance. Pure over daemon state.
     */
    fun render(
        contracts: List<LcncPortContract> = LcncContracts.all(),
        /** Ring inventory: program name j its raw Confix document. */
        programs: List<Pair<String, String>> = emptyList(),
        vmEvents: List<VmEvent> = emptyList(),
        logs: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ): Map<String, Any?> {
        val vmLegos = contracts.filter { it.type.startsWith(SubVm.LEGO_PREFIX) }
        return mapOf(
            // ── module drawer: sub-VM legos + their facet budget ──────────
            "modules" to mapOf(
                "legos" to vmLegos.map { it.toPanelMap() },
                "facets" to VmSupervisor.reports.flatMap { r ->
                    r.languages.distinct()
                },
                "available" to VmSupervisor.reports.any { it.available },
            ),
            // ── ring inventory: every stored program as a concentric panel ─
            "rings" to programs.map { (name, doc) -> ringPanel(name, doc, logs[name].orEmpty()) },
            // ── wizard roster: the guided-construction entries ─────────────
            "wizard" to wizardRoster(contracts),
            // ── lane assemblage: the canvas's CCEK element families ────────
            "lanes" to LANE_ASSEMBLAGE.map { l ->
                mapOf(
                    "id" to l.id,
                    "match" to l.matchPrefixes,
                    "band" to l.band.id,
                    "element" to l.band.element,
                    "key" to l.band.key,
                    "seed" to l.band.seedIn,
                    "emit" to l.band.emitOut,
                )
            },
            // ── substrate provenance: what the panels run on ───────────────
            "substrate" to mapOf(
                "executor" to "LcncRunner (authored order; CCEK assembly when bound)",
                "frame" to "LcncScopeFrame — CoroutineContext element; bindings + warm outputs",
                "assembly" to "LcncCcekAssembly — SupervisorJob child scope, reactor + frame in one context",
                "vmEvents" to vmEvents.size,
            ),
        )
    }

    /**
     * One ring panel: the program's nodes as concentric bands (children nest),
     * node outputs from the last walk as the warm base, wires as edges. The
     * raw Confix document is parsed here — the surface owns its own boundary.
     */
    fun ringPanel(name: String, confixDoc: String, trace: List<Map<String, Any?>>): Map<String, Any?> {
        val doc: Map<*, *> = runCatching { JsonSupport.parse(confixDoc) as? Map<*, *> }.getOrNull() ?: emptyMap<Any?, Any?>()
        val nodes = (doc["nodes"] as? List<*>) ?: emptyList<Any?>()
        return mapOf(
            "name" to name,
            "bands" to bandList(nodes, trace),
            "wires" to (doc["wires"] as? List<*>).orEmpty(),
            "trace" to trace,
        )
    }

    /** Bands recurse: a ring node's children are bands of their own, warm base included. */
    private fun bandList(nodes: List<*>, trace: List<Map<String, Any?>>): List<Map<String, Any?>> =
        nodes.map { node ->
            val n = node as? Map<*, *> ?: return@map mapOf<String, Any?>()
            val id = n["id"]?.toString() ?: ""
            val childNodes = n["children"] as? List<*>
            mapOf(
                "id" to id,
                "type" to n["type"],
                // concentric depth: ring nodes (with children) render as bands
                "ring" to (childNodes != null),
                "children" to (childNodes?.size ?: 0),
                "params" to n["params"],
                // warm base: this node's last outputs, if the walk recorded any
                "outputs" to (trace.lastOrNull { it["node"] == id }?.get("outputs")),
                // nested bands — the ring's inner machine
                "bands" to bandList(childNodes ?: emptyList<Any?>(), trace),
            )
        }.toList()

    /**
     * The wizard roster: every contract yields a complete construction schema
     * (ports already kind-checked by LcncContractParityTest). A wizard = an
     * ordered param walk; `params` map order IS the step order.
     */
    fun wizardRoster(contracts: List<LcncPortContract>): List<Map<String, Any?>> =
        contracts.map { c ->
            c.toPanelMap() + mapOf(
                "steps" to c.params.entries.mapIndexed { i, (k, spec) ->
                    mapOf(
                        "order" to i,
                        "param" to k,
                        "default" to spec.v,
                        "options" to spec.opts,
                        "placeholder" to spec.ph,
                        "multiline" to spec.ta,
                    )
                },
            )
        }

    private fun LcncPortContract.toPanelMap(): Map<String, Any?> = mapOf(
        "type" to type,
        "title" to title,
        "inputs" to inputs,
        "outputs" to outputs,
        "inputKinds" to inputKinds,
        "outputKinds" to outputKinds,
        "params" to params,
    )

    // ── /panels — swimlanes as an IBM grammar box diagram ──────────────
    //
    // The cancelled panel editor's LOOK, re-grammar'd: the main line is the
    // kanban FSM (boxes on a spine, read left→right like an SQL manual
    // diagram). Context nesting is CONTAINMENT — a box inside a box, squared,
    // depth tabbed D0…Dn. Downstream contexts (sub-VM, bot seat) leave the
    // innermost box on a ─▶ right branch and WRAP into free margin, so growth
    // never crowds the spine. Pure string projection; the browser runs nothing
    // beyond toggle/run.

    /**
     * One fractal IO box: the CCEK element a program runs under at that depth.
     * [key] is the FACTORY — the CoroutineContext.Key whose element resolves to
     * this box. [seedIn] names the upstream coroutine state the Key resolves
     * FROM (the enclosing context's seed); [emitOut] names what this element
     * hands downstream. The same grammar recurs at every scale: a column box
     * seeds from the previous column's emit, a card seeds from its column.
     */
    data class RingBand(
        val id: String,
        val element: String,
        val key: String,
        val state: String,
        val seedIn: String,
        val emitOut: String,
        val downstream: Boolean = false,
    )

    val RING_BANDS: List<RingBand> = listOf(
        RingBand("daemon-root", "SupervisorJob · LitebikeListenerElement", "acceptor", "CREATED — payload accepted",
            seedIn = "main runBlocking scope (one at main)", emitOut = "HtxKey · fanoutChannels"),
        RingBand("frame-r1-store", "LcncScopeFrame r1 · BoardStoreElement intake", "LcncScopeFrame.Key", "OPEN — WAL-backed, ordered",
            seedIn = "daemon-root emit (HtxKey)", emitOut = "BoardIntake receipts (WAL seq)"),
        RingBand("frame-r1-lease", "LcncScopeFrame r1 · MuxReactorElement.acquireLease", "LcncScopeFrame.Key", "OPEN — QuotaLegion admission",
            seedIn = "MuxReactor flowState (leases)", emitOut = "lease-granted context"),
        RingBand("frame-r2-fanout", "LcncScopeFrame r2 · ArticulatedNode fan-out", "LcncScopeFrame.Key", "ACTIVE — Semaphore(8)",
            seedIn = "r1 warm base (frame outputs)", emitOut = "agent signals → board projections"),
        RingBand("sub-vm", "VmSupervisor.current · VmHandle.eval", "VmSpec facet·trust", "ACTIVE — Teleported crossings",
            seedIn = "VmSpec (params · world seed)", emitOut = "Teleported cid → receipts", downstream = true),
        RingBand("bot-seat", "Seat(lane·role·policy) · ConstructionBotNode", "Seat.Key", "ACTIVE — the ONLY token spend",
            seedIn = "QuotaLegion admission (lease)", emitOut = "constructions → reviseInto", downstream = true),
        RingBand("draining", "CycleBody observation · REVIEW-BLOCK", "—", "DRAINING — settlement pending",
            seedIn = "review queue (contested)", emitOut = "settlement receipts"),
        RingBand("closed", "receipt → ConfixBlackboard.put", "receipt cid", "CLOSED — ancestry retained",
            seedIn = "receipt ancestry (never ages)", emitOut = "blackboard projection"),
        RingBand("sealed", "WAL segment sealed", "—", "CLOSED — compacted",
            seedIn = "segment tail (last-good)", emitOut = "compacted segment"),
    )

    private fun band(id: String): RingBand = RING_BANDS.first { it.id == id }

    /**
     * One canvas lane: which CCEK element family hosts a node type. The lane
     * IS its [RingBand] — element/seed/emit come from the band by reference,
     * never re-spelled — plus the type-prefix routing the canvas needs to
     * tint and caption nodes. Served in [render] as `lanes`; the panels
     * canvas hydrates this and authors nothing.
     */
    data class LaneBand(
        val id: String,
        /** Node-type prefixes this lane claims (startsWith matching). */
        val matchPrefixes: List<String>,
        val band: RingBand,
    )

    val LANE_ASSEMBLAGE: List<LaneBand> = listOf(
        LaneBand("accept", listOf("timer", "sse", "graal.events", "vm.events"), band("daemon-root")),
        // Monitoring had no lane at all: `graal.vitals`, `graal.heap`, `vms.list`
        // and the blackboard reads matched nothing and fell through laneOf's
        // default into `fanout`, so heap and vitals panels drew inside the
        // fan-out band beside unrelated work. They are readings of the runtime
        // terrain, so they belong to the sub-VM band. Placed AFTER `accept` on
        // purpose — `graal.events` is an acceptor signal and must keep matching
        // there, and laneOf takes the FIRST match.
        LaneBand("monitor", listOf("graal.", "vms.", "sub-vm", "blackboard."), band("sub-vm")),
        LaneBand("store", listOf("kanban.", "confix.", "sheet.", "panels.list"), band("frame-r1-store")),
        LaneBand("lease", listOf("mux.", "brain.", "llm"), band("frame-r1-lease")),
        LaneBand("fanout", listOf("vm.", "display", "gauge", "pick", "group", "board.", "js"), band("frame-r2-fanout")),
        LaneBand("bot", listOf("kg.ingest", "belief.", "read.construct"), band("bot-seat")),
    )

    /** The box stack per FSM column, OUTER box first; downstream boxes branch right. */
    val COLUMN_RINGS: Map<String, List<RingBand>> = mapOf(
        "triage" to listOf(band("daemon-root")),
        "todo" to listOf(band("daemon-root"), band("frame-r1-store")),
        "ready" to listOf(band("daemon-root"), band("frame-r1-lease")),
        "running" to listOf(band("daemon-root"), band("frame-r2-fanout"), band("sub-vm"), band("bot-seat")),
        "blocked" to listOf(band("daemon-root"), band("draining")),
        "done" to listOf(band("daemon-root"), band("closed")),
        "archived" to listOf(band("sealed")),
    )

    /** FSM loop edges drawn as the return annotation above the spine (IBM loop-back). */
    val FSM_LOOPS: Map<String, String> = mapOf(
        "blocked" to "todo",
        "done" to "todo",
    )

    /**
     * Column-scale seeds: each FSM column box is ITSELF a fractal IO box — its
     * seedIn is the previous column's emitOut, its emitOut feeds the next. The
     * spine is therefore a seed→emit chain at the outer scale, exactly like the
     * context chain inside each box.
     */
    val COLUMN_SEEDS: Map<String, Pair<String, String>> = mapOf(
        "triage" to ("socket accept (payload)" to "connection · method · path"),
        "todo" to ("prior column emit (connection)" to "ordered WAL card"),
        "ready" to ("ordered WAL card" to "admitted context (lease)"),
        "running" to ("admitted context (lease)" to "agent signals · receipts"),
        "blocked" to ("agent signals · receipts" to "settlement receipts"),
        "done" to ("settlement receipts" to "blackboard projection"),
        "archived" to ("blackboard projection" to "compacted segment"),
    )

    /** Where a card lands when its title names no band — the column's base ring. */
    private val DEFAULT_BAND: Map<String, String> = mapOf(
        "triage" to "daemon-root",
        "todo" to "frame-r1-store",
        "ready" to "frame-r1-lease",
        "running" to "frame-r2-fanout",
        "blocked" to "draining",
        "done" to "closed",
        "archived" to "sealed",
    )

    /**
     * Deterministic card→band rule (documented on the page): a running card whose
     * title spells a `vm.*` lego sits in the sub-VM band; a reader/bot card sits in
     * the bot seat; everything else sits in its column's base ring.
     */
    fun cardBand(column: String, title: String): String = when {
        column == "running" && title.startsWith("vm.") -> "sub-vm"
        column == "running" && (title.startsWith("read.") || title.contains("construct")) -> "bot-seat"
        else -> DEFAULT_BAND[column] ?: "daemon-root"
    }

    /**
     * The full /panels page. [boardView] is the `boardView` map from
     * LcncKanbanExperience.activeSheets (columns + items); [modules] is
     * [render]'s output (module drawer + wizard roster count).
     */
    fun panelsHtml(boardView: Map<String, Any?>, modules: Map<String, Any?>): String {
        val items = (boardView["items"] as? List<Any?>) ?: emptyList<Any?>()
        val sequence = boardView["sequence"]?.toString() ?: "0"
        val legos = ((modules["modules"] as? Map<*, *>)?.get("legos") as? List<Any?>) ?: emptyList<Any?>()
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><head><meta charset="utf-8"><title>/panels — FSM spine, IBM box grammar</title><style>
* { box-sizing:border-box; margin:0; padding:0 }
body { font-family:"SF Mono",ui-monospace,Menlo,monospace; background:#0a0e12; color:#c8d4dc; font-size:12px }
header { padding:12px 18px; border-bottom:1px solid #1c2630; display:flex; gap:14px; align-items:baseline }
header b { color:#e8eef2; font-size:13px; letter-spacing:.04em }
header span { color:#4d6070 }
#drawer { padding:10px 18px; border-bottom:1px solid #1c2630; display:flex; gap:8px; flex-wrap:wrap; align-items:center }
#drawer .ttl { color:#4d6070; font-size:10px; text-transform:uppercase; letter-spacing:.08em; margin-right:6px }
.lego { padding:4px 10px; border:1px solid #4a3f68; border-radius:0; background:#171323; color:#cbbdf0; cursor:pointer }
.lego:hover { border-color:#8a6fd4; color:#e6dcfa }
/* the spine: boxes flow left→right, margins absorb growth */
#spine { display:flex; align-items:flex-start; gap:0; padding:18px; overflow-x:auto; height:calc(100vh - 92px) }
.col { flex:0 0 auto; display:flex; align-items:center }
.arrow { color:#3f5468; font-size:11px; padding:0 4px; white-space:nowrap; text-align:center }
.arrow .loop { display:block; color:#8a6fd4; font-size:9px }
.box { border:1px solid #3f5468; background:#0d1319; padding:8px; position:relative; margin:14px 0 4px 0 }
.box .tab {
  position:absolute; top:-9px; left:8px; background:#0d1319; border:1px solid #3f5468;
  font-size:9px; color:#7fd4a8; padding:0 6px; line-height:16px; white-space:nowrap;
}
.box .el { color:#9fb4c2; font-size:10px; line-height:1.5 }
.box .el i { color:#7fd4a8; font-style:normal }
.box .st { color:#4d6070; font-size:9px; margin-top:2px }
.box .hd { font-size:12px; color:#e8eef2; font-weight:600; margin-bottom:2px }
.box .hd .n { float:right; color:#4d6070; font-weight:400 }
/* fractal IO ports: seed-in on the left edge, emit-out on the right (arrows from ::before) */
.io { font-size:9px; line-height:1.7 }
.io .sin { color:#6b8aa0; }
.io .sin::before { content:"◀ "; color:#3f5468; }
.io .sout { color:#7a95a8; text-align:right; display:block }
.io .sout::before { content:"▶ "; color:#3f5468; }
.box.dbox .io .sin { color:#a08fd0 }
.box.dbox .io .sout { color:#cbbdf0 }
/* containment: inner box nests with a hard offset tab, squared */
.box .box { margin-top:8px; border-color:#33475a }
/* downstream branch: ─▶ exits right into margin, wraps instead of crowding */
.branch { display:flex; align-items:center; margin-top:6px }
.branch .ex { color:#8a6fd4; font-size:10px; padding:0 3px; white-space:nowrap }
.branch .dbox { border:1px solid #4a3f68; background:#120f1c; padding:5px 7px; position:relative; margin-top:10px }
.branch .dbox .tab { border-color:#4a3f68; color:#cbbdf0 }
.branch .dbox .el i { color:#cbbdf0 }
.card { background:#101820; border:1px solid #2a3c4c; padding:5px 8px; margin-top:4px; cursor:pointer }
.card:hover { border-color:#7fd4a8 }
.card b { color:#e8eef2; font-weight:600; font-size:11px; display:block }
.card .rev { color:#4d6070; font-size:9px }
.card .chain { display:none; margin-top:5px; border-top:1px dashed #1c2630; padding-top:4px; color:#9fb4c2; font-size:9.5px; line-height:1.6 }
.card.open .chain { display:block }
.pri { display:inline-block; width:7px; height:7px; margin-right:5px }
.p0 { background:#d4a05f } .p1 { background:#7fd4a8 } .p2 { background:#4d6070 }
</style></head><body>
<header><b>/PANELS</b><span>kanban FSM on the main line — context containment by box-in-box (D0…Dn), downstream contexts branch ─▶ into margin</span><span style="margin-left:auto">seq ${sequence}</span></header>
<div id="drawer"><span class="ttl">module drawer — sub-vm legos</span>""")
        for (lego in legos) {
            val t = (lego as? Map<*, *>)?.get("type")?.toString() ?: continue
            sb.append("""<span class="lego" onclick="run('$t')">$t ▸</span> """)
        }
        sb.append("""</div><div id="spine">""")
        val cols = COLUMN_RINGS.entries.toList()
        for (ci in cols.indices) {
            val (wire, boxes) = cols[ci]
            if (ci > 0) {
                val loop = FSM_LOOPS[wire]?.let { src -> """<span class="loop">⟲ $src</span>""" } ?: ""
                sb.append("""<div class="arrow">▶$loop</div>""")
            }
            val count = items.count { (it as? Map<*, *>)?.get("status") == wire }
            val (colSeed, colEmit) = COLUMN_SEEDS[wire] ?: ("—" to "—")
            sb.append("""<div class="col"><div class="box"><span class="tab">D0 · ${wire.uppercase()}</span>
<div class="hd"><span class="n">$count</span>${wire.uppercase()}</div>
<div class="io"><span class="sin">seed: ${esc(colSeed)}</span></div>
<div class="el"><i>${esc(boxes[0].element)}</i><br>${esc(boxes[0].key)}</div><div class="st">${esc(boxes[0].state)}</div>""")
            renderBoxes(sb, boxes, 1, items, wire)
            sb.append("""<div class="io"><span class="sout">emit: ${esc(colEmit)}</span></div></div></div>""")
        }
        sb.append("""</div><script>
function run(t){ fetch('/api/lcnc/run',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:t,params:{}})}).then(r=>r.json()).then(j=>alert(JSON.stringify(j))).catch(e=>alert('run failed: '+e)) }
</script></body></html>""")
        return sb.toString()
    }

    /**
     * Depth-first box walk. Contained boxes (downstream=false) nest inside the
     * previous box; downstream boxes leave on a ─▶ branch and wrap — margin
     * absorbs them, the spine never crowds.
     */
    private fun renderBoxes(
        sb: StringBuilder,
        boxes: List<RingBand>,
        depth: Int,
        items: List<Any?>,
        column: String,
    ) {
        if (depth >= boxes.size) return
        val b = boxes[depth]
        val owned = items.filter {
            (it as? Map<*, *>)?.get("status") == column && cardBand(column, it["title"]?.toString() ?: "") == b.id
        }
        val label = "D$depth · ${b.id}"
        if (b.downstream) {
            sb.append("""<div class="branch"><span class="ex">─▶</span><div class="box dbox"><span class="tab">$label</span>""")
        } else {
            sb.append("""<div class="box"><span class="tab">$label</span>""")
        }
        sb.append("""<div class="io"><span class="sin">seed: ${esc(b.seedIn)}</span></div>""")
        sb.append("""<div class="el"><i>${esc(b.element)}</i><br>${esc(b.key)}</div><div class="st">${esc(b.state)}</div>""")
        renderBoxes(sb, boxes, depth + 1, items, column)
        for (itemAny in owned) {
            val item = itemAny as? Map<*, *> ?: continue
            val id = item["id"]?.toString() ?: continue
            val title = item["title"]?.toString() ?: id
            val pri = when (item["priority"]?.toString()?.toIntOrNull() ?: 2) { 0 -> "p0"; 1 -> "p1"; else -> "p2" }
            val rev = item["revision"]?.toString() ?: "-"
            val chain = boxes.joinToString(" ▸ ") { it.element }
            sb.append("""<div class="card" onclick="this.classList.toggle('open')">
<span class="pri $pri"></span><b>${esc(title)}</b><span class="rev">job $id · rev $rev · $label</span>
<div class="chain">assemblage: $chain<br>seed: ${esc(b.seedIn)}<br>state: ${esc(b.state.substringBefore(" —"))}</div>
</div>""")
        }
        sb.append("""<div class="io"><span class="sout">emit: ${esc(b.emitOut)}</span></div></div></div>""")
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
