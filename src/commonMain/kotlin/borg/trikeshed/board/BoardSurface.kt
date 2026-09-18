package borg.trikeshed.board

import borg.trikeshed.docs.DocumentMarkdown
import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetSeed
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import kotlinx.datetime.Instant

/**
 * THE BLACKBOARD SURFACE: the page behind `/blackboard` and `/harness`, whose logic lives in
 * commonMain and reaches the browser through the Kotlin/JS bundle (the owner's 2026-09-06
 * ruling: emulate GWT, hand-written JS only for dire dependency access). It re-creates the
 * core of the renderer `f34c9c3b3` deleted: the board grouped into territories by the first
 * segment of each key, one fact row per key (a heat field past 24), the run receipts of
 * `lcnc/run/…`, the actors, and an inspector that opens a key as the grid-in-cell sheet
 * family `/blackboard/sheet` projects, with its SUMO neighbors beside it.
 *
 * What it adds is the `document` territory: curation IO drawn as the board holds it —
 * the `document/curation/<cid>` summaries, the `document/stage/<correlation>/<stage>`
 * receipts, and the latest curate run's inputs and returns — and, on inspection, the
 * `documents` Rete partition under that curation cid (proposals, stage receipts, rule
 * candidates, the model receipt) as one table per kind.
 *
 * Every function here is pure: JSON in (as the parser's maps and lists), HTML strings out.
 * The jsBoard page only fetches, subscribes, mounts and forwards clicks. `Map` appears at
 * the parser boundary and as the live key → fact table, whose whole job is keyed lookup;
 * everything projected from it is a [Series].
 */
data class Fact(val key: String, val value: Any?, val actor: String?, val atMs: Long?)

data class Snapshot(val facts: Map<String, Fact>, val revision: Long, val epoch: String)

data class Delta(
    val seq: Long, val epoch: String, val key: String, val value: Any?, val deleted: Boolean,
    val actor: String?, val atMs: Long?,
)

/** What a delta means against the revision in hand: apply it, ignore it, or resync. */
enum class Accept { APPLY, STALE, GAP }

enum class Kind { FACTS, RECEIPTS, DOCUMENT, ACTORS }

data class Territory(val id: String, val title: String, val count: String, val tone: String, val kind: Kind, val rows: Series<Fact>)

data class RunReceipt(
    val key: String, val runId: String, val program: String, val programCid: String?, val status: String,
    val ok: Boolean?, val startedAtMs: Long, val finishedAtMs: Long?, val inputs: Any?, val returns: Any?,
    val error: String?, val violations: Any?,
)

data class CurationSummary(
    val key: String, val receiptCid: String, val name: String, val model: String?, val correlation: String?,
    val proposals: Int, val submitted: Int, val quotationsSubmitted: Int, val stages: Int, val rules: Int,
    val stageReceiptCids: Series<String>, val modelReceiptId: String?, val reasons: Series<String>, val atMs: Long?,
)

/** One `document/stage/<correlation>/<stage>` key: a retained stage receipt, or the observer's boundary heartbeat. */
data class StageRow(
    val key: String, val correlation: String, val stage: String, val atMs: Long?,
    val status: String?, val tool: String?, val receiptCid: String?, val error: String?,
    val startedAt: Long?, val completedAt: Long?, val references: Series<String>,
)

data class Curation(val summaries: Series<CurationSummary>, val stages: Series<StageRow>, val runs: Series<RunReceipt>)

data class Neighbor(
    val key: String, val score: Double, val concepts: Series<String>, val terms: Series<String>,
    val references: Series<Twin<String>>, val actor: String?,
)

/** One row of `/api/rete/facts`. */
data class PlaneFact(val partition: String, val id: String, val versionCid: String?, val fields: Map<String, Any?>)

object BoardSurface {
    const val CURATE_PROGRAM = "document.curate"
    const val RECEIPTS = "receipts"
    const val DOCUMENT = "document"
    const val ACTORS = "actors"
    const val RUN_PREFIX = "lcnc/run/"
    const val CURATION_PREFIX = "document/curation/"
    const val STAGE_PREFIX = "document/stage/"
    /** Past this many keys a territory draws a heat field instead of rows, as the original did. */
    const val ROW_LIMIT = 24
    const val EVENT_LIMIT = 50
    const val HASH_KEY = "key="

    // ── parsing the routes' JSON (the boundary) ─────────────────────────────

    /** `/blackboard/board`. Null unless the snapshot is revision-linked: the stream cannot be joined without both. */
    fun snapshot(json: Any?): Snapshot? {
        val m = json as? Map<*, *> ?: return null
        val revision = (m["revision"] as? Number)?.toLong() ?: return null
        val epoch = m["epoch"]?.toString() ?: return null
        val provenance = m["provenance"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val board = m["board"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val facts = LinkedHashMap<String, Fact>(board.size)
        for ((k, v) in board) {
            val key = k?.toString() ?: continue
            val p = provenance[key] as? Map<*, *>
            facts[key] = Fact(key, v, p?.get("actor")?.toString() ?: actorOf(v), (p?.get("atMs") as? Number)?.toLong())
        }
        return Snapshot(facts, revision, epoch)
    }

    /** One `/blackboard/facts` event. A row without a key or a sequence is not a delta. */
    fun delta(json: Any?): Delta? {
        val m = json as? Map<*, *> ?: return null
        val key = m["key"]?.toString() ?: return null
        val seq = (m["seq"] as? Number)?.toLong() ?: return null
        return Delta(
            seq = seq, epoch = m["epoch"]?.toString().orEmpty(), key = key, value = m["value"],
            deleted = m["deleted"] as? Boolean ?: false,
            actor = m["actor"]?.toString() ?: actorOf(m["value"]), atMs = (m["atMs"] as? Number)?.toLong(),
        )
    }

    /** `/blackboard/sheet` answers a list of seeds; a `{sheet: id}` cell is a [SheetRef]. */
    fun sheets(json: Any?): Series<SheetSeed> {
        val rows = json as? List<*> ?: return emptySeriesOf()
        return rows.mapNotNull { sheet(it) }.toSeries()
    }

    fun sheet(json: Any?): SheetSeed? {
        val m = json as? Map<*, *> ?: return null
        val id = m["id"]?.toString() ?: return null
        return SheetSeed(
            id = id, title = m["title"]?.toString() ?: id,
            columns = (m["columns"] as? List<*>).orEmpty().mapNotNull { c ->
                (c as? Map<*, *>)?.let { SheetColumn(it["name"]?.toString().orEmpty(), it["type"]?.toString().orEmpty()) }
            },
            rows = (m["rows"] as? List<*>).orEmpty().map { row ->
                (row as? List<*>).orEmpty().map { cell -> (cell as? Map<*, *>)?.get("sheet")?.toString()?.let(::SheetRef) ?: cell }
            },
            parent = m["parent"]?.toString(), truncated = m["truncated"] as? Boolean ?: false, limit = m["limit"]?.toString(),
        )
    }

    fun neighbors(json: Any?): Series<Neighbor> {
        val rows = (json as? Map<*, *>)?.get("neighbors") as? List<*> ?: return emptySeriesOf()
        return rows.mapNotNull { row ->
            val n = row as? Map<*, *> ?: return@mapNotNull null
            Neighbor(
                key = n["key"]?.toString() ?: return@mapNotNull null,
                score = (n["score"] as? Number)?.toDouble() ?: 0.0,
                concepts = strings(n["concepts"]), terms = strings(n["terms"]),
                references = (n["references"] as? List<*>).orEmpty().mapNotNull { r ->
                    (r as? Map<*, *>)?.let { it["from"]?.toString().orEmpty() j it["to"]?.toString().orEmpty() }
                }.toSeries(),
                actor = (n["provenance"] as? Map<*, *>)?.get("actor")?.toString(),
            )
        }.toSeries()
    }

    /** The status line the original wrote over the neighbor list: what was indexed, and at which revision. */
    fun neighborsStatus(json: Any?): String {
        val m = json as? Map<*, *> ?: return "Neighbors unavailable"
        val opaque = (m["opaqueKeys"] as? List<*>).orEmpty().size
        return "${m["indexedKeys"]} nodes indexed · ${m["classifiedKeys"]} with SUMO concepts · ${m["corpus"]} corpus · revision ${m["revision"]}" +
            (if (opaque > 0) " · $opaque nodes have opaque values" else "")
    }

    /** `/api/rete/facts`. */
    fun planeFacts(json: Any?): Series<PlaneFact> {
        val rows = (json as? Map<*, *>)?.get("facts") as? List<*> ?: return emptySeriesOf()
        return rows.mapNotNull { row ->
            val f = row as? Map<*, *> ?: return@mapNotNull null
            val fields = (f["fields"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to v }
            PlaneFact(f["partition"]?.toString().orEmpty(), f["id"]?.toString() ?: return@mapNotNull null, f["versionCid"]?.toString(), fields)
        }.toSeries()
    }

    // ── state transitions: pure decisions, the page owns the table ──────────

    /** The original's rule: a seq at or below the revision is old news; anything but the next seq in the same epoch is a gap. */
    fun accept(revision: Long, epoch: String, d: Delta): Accept = when {
        d.seq <= revision -> Accept.STALE
        d.epoch != epoch || d.seq != revision + 1 -> Accept.GAP
        else -> Accept.APPLY
    }

    fun apply(facts: MutableMap<String, Fact>, d: Delta) {
        if (d.deleted) facts.remove(d.key) else facts[d.key] = Fact(d.key, d.value, d.actor, d.atMs)
    }

    fun prefix(key: String): String = key.substringBefore('/')

    // ── grouping ────────────────────────────────────────────────────────────

    /**
     * Receipts first, then the document territory, then every other prefix largest first, actors
     * last. Keys the receipts and document territories already draw are not drawn twice.
     */
    fun territories(facts: Map<String, Fact>, program: String? = null): Series<Territory> {
        val groups = LinkedHashMap<String, MutableList<Fact>>()
        for (f in facts.values) {
            if (f.key.startsWith(RUN_PREFIX) || f.key.startsWith("$DOCUMENT/")) continue
            groups.getOrPut(prefix(f.key)) { mutableListOf() }.add(f)
        }
        val runs = receipts(facts, program)
        val curation = curation(facts)
        val out = mutableListOf(
            Territory(RECEIPTS, "Run receipts", "${runs.size} runs" + (program?.let { " · $it" } ?: ""), "#91c49d", Kind.RECEIPTS, emptySeriesOf()),
            Territory(DOCUMENT, "Document curation", "${curation.summaries.size} records · ${curation.stages.size} stages", "#64ceca", Kind.DOCUMENT, emptySeriesOf()),
        )
        for ((prefix, rows) in groups.entries.sortedWith(compareByDescending<Map.Entry<String, List<Fact>>> { it.value.size }.thenBy { it.key })) {
            val sorted = rows.sortedBy { it.key }
            out.add(Territory(prefix, prefix, "${sorted.size} entries", tone(prefix), Kind.FACTS, sorted.toSeries()))
        }
        out.add(Territory(ACTORS, "Actors", "${actors(facts).size} observed", "#d59db1", Kind.ACTORS, emptySeriesOf()))
        return out.toSeries()
    }

    fun tone(prefix: String): String = when (prefix) {
        "narsese" -> "#c4b07c"
        "kanban" -> "#80b4d2"
        DOCUMENT -> "#64ceca"
        else -> "#a7a1c9"
    }

    /** `lcnc/run/…` newest first; [program] narrows to one program, null shows them all. */
    fun receipts(facts: Map<String, Fact>, program: String? = null): Series<RunReceipt> =
        facts.values.mapNotNull(::runReceipt)
            .filter { program == null || it.program == program }
            .sortedByDescending { it.startedAtMs }
            .toSeries()

    fun runReceipt(f: Fact): RunReceipt? {
        if (!f.key.startsWith(RUN_PREFIX)) return null
        val v = f.value as? Map<*, *> ?: return null
        return RunReceipt(
            key = f.key, runId = v["runId"]?.toString() ?: f.key.removePrefix(RUN_PREFIX),
            program = v["program"]?.toString().orEmpty(), programCid = v["programCid"]?.toString(),
            status = v["status"]?.toString().orEmpty(), ok = v["ok"] as? Boolean,
            startedAtMs = (v["startedAtMs"] as? Number)?.toLong() ?: 0L, finishedAtMs = (v["finishedAtMs"] as? Number)?.toLong(),
            inputs = v["inputs"], returns = v["returns"], error = v["error"]?.toString(), violations = v["violations"],
        )
    }

    /** Distinct programs with a receipt, sorted: what `#programSelect` offers as a filter. */
    fun programs(facts: Map<String, Fact>): Series<String> =
        facts.values.mapNotNull(::runReceipt).map { it.program }.filter { it.isNotEmpty() }.distinct().sorted().toSeries()

    fun curation(facts: Map<String, Fact>): Curation {
        val summaries = facts.values.filter { it.key.startsWith(CURATION_PREFIX) }.mapNotNull(::curationSummary)
            .sortedWith(compareByDescending<CurationSummary> { it.atMs ?: 0L }.thenBy { it.key }).toSeries()
        val stages = facts.values.filter { it.key.startsWith(STAGE_PREFIX) }.mapNotNull(::stageRow)
            .sortedWith(compareBy<StageRow> { it.correlation }.thenBy { it.startedAt ?: it.atMs ?: 0L }.thenBy { it.key }).toSeries()
        val runs = facts.values.mapNotNull(::runReceipt).filter { it.program == CURATE_PROGRAM || it.program.endsWith(".curate") }
            .sortedByDescending { it.startedAtMs }.toSeries()
        return Curation(summaries, stages, runs)
    }

    fun curationSummary(f: Fact): CurationSummary? {
        val v = f.value as? Map<*, *> ?: return null
        return CurationSummary(
            key = f.key, receiptCid = v["receiptCid"]?.toString() ?: return null,
            name = v["name"]?.toString().orEmpty(), model = v["model"]?.toString(), correlation = v["correlation"]?.toString(),
            proposals = int(v["proposals"]), submitted = int(v["submitted"]), quotationsSubmitted = int(v["quotationsSubmitted"]),
            stages = int(v["stages"]), rules = int(v["rules"]),
            stageReceiptCids = strings(v["stageReceiptCids"]), modelReceiptId = v["modelReceiptId"]?.toString(),
            reasons = strings(v["reasons"]), atMs = f.atMs,
        )
    }

    fun stageRow(f: Fact): StageRow? {
        val rest = f.key.removePrefix(STAGE_PREFIX)
        val correlation = rest.substringBefore('/')
        val stage = rest.substringAfter('/', "")
        if (correlation.isEmpty() || stage.isEmpty()) return null
        val v = f.value as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return StageRow(
            key = f.key, correlation = correlation, stage = v["stage"]?.toString() ?: stage,
            atMs = (v["atMs"] as? Number)?.toLong() ?: f.atMs,
            status = v["status"]?.toString(), tool = v["tool"]?.toString(), receiptCid = v["receiptCid"]?.toString(),
            error = v["error"]?.toString(),
            startedAt = (v["startedAt"] as? Number)?.toLong(), completedAt = (v["completedAt"] as? Number)?.toLong(),
            references = strings(v["references"]),
        )
    }

    /** Actor → (entries, prefixes), as the original counted them. */
    fun actors(facts: Map<String, Fact>): Series<Join<String, Join<Int, Series<String>>>> {
        val counts = LinkedHashMap<String, MutableList<String>>()
        for (f in facts.values) {
            val actor = f.actor ?: continue
            counts.getOrPut(actor) { mutableListOf() }.add(prefix(f.key))
        }
        return counts.entries.sortedBy { it.key }.map { (actor, prefixes) -> actor j (prefixes.size j prefixes.distinct().toSeries()) }.toSeries()
    }

    /** The original's four-entry gloss of a value, capped at 250 characters. */
    fun summary(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Map<*, *> -> value.entries.take(4).joinToString(" / ") { (k, v) ->
            "$k: " + (if (v is Map<*, *> || v is List<*>) compact(v) else scalar(v))
        }.take(250)
        else -> scalar(value)
    }

    /** The parser reifies every JSON number as a Double; a whole one reads back as the integer it was. */
    fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is Double -> if (v.isFinite() && v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> scalar(v.toDouble())
        else -> v.toString()
    }

    /** Single-line JSON, the parser's shape back out. */
    fun compact(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Number -> scalar(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + compact(v) }
        is List<*> -> value.joinToString(",", "[", "]") { compact(it) }
        else -> quote(value.toString())
    }

    /** Two-space JSON for a `<pre>`, since [compact] is one line. */
    fun pretty(value: Any?, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val inner = "  ".repeat(indent + 1)
        return when (value) {
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) ->
                inner + quote(k.toString()) + ": " + pretty(v, indent + 1)
            }
            is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(",\n", "[\n", "\n$pad]") { inner + pretty(it, indent + 1) }
            else -> compact(value)
        }
    }

    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    // ── html ────────────────────────────────────────────────────────────────

    /** One `<section class="territory">`; [flash] holds the keys and territory ids lit by the last second's deltas. */
    fun territoryHtml(t: Territory, facts: Map<String, Fact>, program: String?, flash: Set<String> = emptySet()): String = buildString {
        append("<section class=\"territory").append(if (t.id in flash) " flash" else "").append("\" data-territory=\"").append(esc(t.id))
            .append("\" style=\"--tone:").append(esc(t.tone)).append("\">")
        append("<header><h2>").append(esc(t.title)).append("</h2><span>").append(esc(t.count)).append("</span>")
        val sheetsOf = when (t.kind) { Kind.RECEIPTS -> "lcnc"; Kind.DOCUMENT -> DOCUMENT; Kind.ACTORS -> null; Kind.FACTS -> t.id }
        if (sheetsOf != null) append("<button class=\"sheets\" data-sheets=\"").append(esc(sheetsOf)).append("\" title=\"")
            .append(esc(sheetsOf)).append(" as sheets\" aria-label=\"").append(esc(sheetsOf)).append(" as sheets\">▤</button>")
        append("</header>")
        when (t.kind) {
            Kind.RECEIPTS -> append(receiptsHtml(receipts(facts, program), program, flash))
            Kind.DOCUMENT -> append(documentHtml(curation(facts), flash))
            Kind.ACTORS -> append(actorsHtml(facts))
            Kind.FACTS -> if (t.rows.size > ROW_LIMIT) {
                append("<div class=\"fact-field\">")
                for (f in t.rows.view) append(factCellHtml(f, f.key in flash))
                append("</div>")
            } else for (f in t.rows.view) append(factRowHtml(f, f.key in flash))
        }
        append("</section>")
    }

    fun factRowHtml(f: Fact, flash: Boolean = false): String =
        "<button class=\"fact-row" + (if (flash) " flash" else "") + "\" data-key=\"" + esc(f.key) + "\" title=\"" + esc(f.key) + "\">" +
            "<b>" + esc(f.key.substringAfter('/', f.key)) + "</b><span>" + esc(summary(f.value)) + "</span></button>"

    fun factCellHtml(f: Fact, flash: Boolean = false): String =
        "<button class=\"fact-cell" + (if (flash) " flash" else "") + "\" data-key=\"" + esc(f.key) + "\" title=\"" +
            esc(f.key + "\n" + summary(f.value)) + "\" aria-label=\"" + esc(f.key) + "\"></button>"

    /** Every run, newest first: a row to inspect and the `{inputs, returns, error, violations}` the original showed. */
    fun receiptsHtml(runs: Series<RunReceipt>, program: String?, flash: Set<String> = emptySet()): String = buildString {
        if (runs.size == 0) {
            append("<p>").append(if (program == null) "No recorded run on the board" else "No recorded run for " + esc(program)).append("</p>")
            return@buildString
        }
        for (r in runs.view) {
            append("<button class=\"fact-row").append(if (r.key in flash) " flash" else "").append("\" data-key=\"").append(esc(r.key))
                .append("\" title=\"").append(esc(r.key)).append("\"><b>").append(esc(r.status)).append(" / ").append(esc(r.runId))
                .append("</b><span>").append(esc(r.program)).append(" · ").append(esc(time(r.startedAtMs)))
            r.finishedAtMs?.let { append(" · ").append(it - r.startedAtMs).append(" ms") }
            append("</span></button>")
            append("<pre class=\"run-result").append(if (r.ok == false) " error" else "").append("\">")
                .append(esc(pretty(linkedMapOf("inputs" to r.inputs, "returns" to r.returns, "error" to r.error, "violations" to r.violations))))
                .append("</pre>")
        }
    }

    /**
     * Curation IO as the board holds it: each record's summary, its stage receipts grouped by
     * correlation in time order, and the latest curate run's inputs beside its returns.
     */
    fun documentHtml(c: Curation, flash: Set<String> = emptySet()): String = buildString {
        if (c.summaries.size == 0 && c.stages.size == 0 && c.runs.size == 0) {
            append("<p>No curation on the board yet.</p>")
            return@buildString
        }
        val stagesByCorrelation = c.stages.view.groupBy { it.correlation }
        for (s in c.summaries.view) {
            append("<button class=\"fact-row").append(if (s.key in flash) " flash" else "").append("\" data-key=\"").append(esc(s.key))
                .append("\" title=\"").append(esc(s.key)).append("\"><b>").append(esc(s.name.ifBlank { "Untitled" })).append(" · ")
                .append(esc(shortCid(s.receiptCid))).append("</b><span>")
                .append(s.proposals).append(" proposals · ").append(s.submitted).append(" submitted · ")
                .append(s.quotationsSubmitted).append(" quotations · ").append(s.stages).append(" stages · ").append(s.rules).append(" rules")
            s.model?.let { append(" · ").append(esc(it)) }
            if (s.reasons.size > 0) append(" · ").append(esc(s.reasons.view.joinToString("; ")))
            append("</span></button>")
            val stages = s.correlation?.let { stagesByCorrelation[it] }.orEmpty()
            for (st in stages) append(stageRowHtml(st, st.key in flash))
        }
        val orphan = stagesByCorrelation.filterKeys { corr -> c.summaries.view.none { it.correlation == corr } }
        for ((_, stages) in orphan) for (st in stages) append(stageRowHtml(st, st.key in flash))
        if (c.runs.size > 0) {
            val r = c.runs[0]
            append("<button class=\"fact-row").append(if (r.key in flash) " flash" else "").append("\" data-key=\"").append(esc(r.key))
                .append("\" title=\"").append(esc(r.key)).append("\"><b>latest run · ").append(esc(r.status)).append(" / ").append(esc(r.runId))
                .append("</b><span>").append(esc(r.program)).append(" · ").append(esc(time(r.startedAtMs))).append("</span></button>")
            append("<pre class=\"run-result").append(if (r.ok == false) " error" else "").append("\">")
                .append(esc(pretty(linkedMapOf("inputs" to r.inputs, "returns" to r.returns, "error" to r.error)))).append("</pre>")
        }
    }

    fun stageRowHtml(st: StageRow, flash: Boolean = false): String = buildString {
        append("<button class=\"fact-row stage-row").append(if (flash) " flash" else "").append("\" data-key=\"").append(esc(st.key))
            .append("\" title=\"").append(esc(st.key)).append("\"><b>").append(esc(st.stage))
        st.status?.let { append(" · ").append(esc(it)) }
        st.tool?.let { append(" · ").append(esc(it)) }
        append("</b><span>")
        val at = st.startedAt ?: st.atMs
        if (at != null) append(esc(time(at)))
        if (st.startedAt != null && st.completedAt != null) append(" · ").append(st.completedAt - st.startedAt).append(" ms")
        st.receiptCid?.let { append(" · receipt ").append(esc(shortCid(it))) }
        st.error?.let { append(" · ").append(esc(it)) }
        if (st.references.size > 0) append(" · ").append(esc(st.references.view.joinToString(" ") { shortCid(it) }))
        append("</span></button>")
    }

    /** The `documents` partition under one curation cid: one table per fact kind, columns chosen per kind. */
    fun factsHtml(facts: Series<PlaneFact>): String = buildString {
        if (facts.size == 0) { append("<p class=\"sheet-note\">No facts on the documents plane for this record.</p>"); return@buildString }
        val byKind = facts.view.groupBy { it.fields["kind"]?.toString() ?: "fact" }
        for ((kind, rows) in byKind.entries.sortedBy { kindOrder(it.key) }) {
            val columns = columnsFor(kind)
            append("<div class=\"csheet plane-facts\"><div class=\"crumbrow\"><span class=\"seg here\">").append(esc(kind))
                .append("</span> <i>").append(rows.size).append("</i></div><div class=\"tablewrap\"><table><tr>")
            for (c in columns.view) append("<th>").append(esc(c)).append("</th>")
            append("<th>id</th></tr>")
            for (f in rows.sortedBy { it.id }) {
                append("<tr>")
                for (c in columns.view) {
                    val v = f.fields[c]
                    append("<td class=\"leafcell\">").append(esc(if (v == null) "" else if (v is Map<*, *> || v is List<*>) compact(v) else scalar(v))).append("</td>")
                }
                append("<td class=\"leafcell\">").append(esc(f.id)).append("</td></tr>")
            }
            append("</table></div></div>")
        }
    }

    private fun kindOrder(kind: String): Int = when (kind) {
        "document-stage-receipt" -> 0
        "document-model-receipt" -> 1
        "document-proposal" -> 2
        "document-rule-candidate" -> 3
        else -> 4
    }

    fun columnsFor(kind: String): Series<String> = when (kind) {
        "document-stage-receipt" -> s_["stage", "tool", "implementation", "status", "startedAt", "completedAt", "receiptCid", "error", "inputCids", "outputCids"]
        "document-model-receipt" -> s_["receiptId", "modelId", "providerId", "httpStatus", "latencyMs", "inputTokens", "outputTokens", "requestHash", "errorClass"]
        "document-proposal" -> s_["subject", "predicate", "object", "modality", "polarity", "modelConfidence", "submitted", "quotationSubmitted", "quote", "stageReceiptCid"]
        "document-rule-candidate" -> s_["antecedent", "symbol", "consequent", "positiveEvidence", "negativeEvidence", "ruleCid", "provenanceCid", "quote", "admission"]
        else -> s_["key", "actor", "atMs"]
    }

    fun actorsHtml(facts: Map<String, Fact>): String = buildString {
        val rows = actors(facts)
        if (rows.size == 0) { append("<p>No actor recorded a key.</p>"); return@buildString }
        for (row in rows.view) {
            append("<div class=\"actor-row\">").append(esc(row.a)).append("<small>").append(row.b.a).append(" entries / ")
                .append(esc(row.b.b.view.joinToString(", "))).append("</small></div>")
        }
    }

    fun navHtml(ts: Series<Territory>): String =
        (ts α { "<button data-territory-nav=\"" + esc(it.id) + "\">" + esc(it.id) + "</button>" }).view.joinToString("")

    fun eventsHtml(events: Series<Delta>): String = buildString {
        for (e in events.view.take(EVENT_LIMIT)) {
            append("<button class=\"event-row\" data-key=\"").append(esc(e.key)).append("\"><b>").append(esc(e.key)).append("</b><small>")
                .append(esc(e.actor ?: "unknown actor")).append(" / #").append(e.seq)
            e.atMs?.let { append(" / ").append(esc(time(it))) }
            if (e.deleted) append(" / deleted")
            append("</small></button>")
        }
    }

    fun boardCount(n: Int): String = "$n entries"

    /** The program filter's options: an empty first entry for "all", then each program. */
    fun programOptionsHtml(programs: Series<String>, selected: String?): String = buildString {
        append("<option value=\"\"").append(if (selected == null) " selected" else "").append(">")
            .append(if (programs.size == 0) "No recorded runs" else "All programs").append("</option>")
        for (p in programs.view) append("<option value=\"").append(esc(p)).append("\"").append(if (p == selected) " selected" else "")
            .append(">").append(esc(p)).append("</option>")
    }

    /**
     * The grid-in-cell sheet renderer patch.js carried as `renderConcentric`: the crumb from the
     * roots down to [current], its children as ring chips, then the table; a [SheetRef] cell is a
     * drill-in chip. Zero vocabulary: the seeds are drawn exactly as the server projected them.
     */
    fun sheetHtml(family: Map<String, SheetSeed>, current: String): String = buildString {
        val cur = family[current] ?: return "<div class=\"kboard-status\">no sheets yet</div>"
        val crumb = ArrayDeque<SheetSeed>()
        var c: SheetSeed? = cur
        var guard = 0
        while (c != null && guard++ < 12) { crumb.addFirst(c); c = c.parent?.let { family[it] } }
        append("<div class=\"crumbrow\">")
        append(crumb.withIndex().joinToString(" ▸ ") { (i, s) ->
            "<span class=\"seg" + (if (i == crumb.size - 1) " here" else "") + "\" data-sheet=\"" + esc(s.id) + "\">" + esc(s.title) + "</span>"
        })
        append("</div>")
        if (cur.truncated) append("<div class=\"kboard-status\" role=\"status\">Partial projection: ").append(esc(cur.limit ?: "limit")).append("</div>")
        val kids = family.values.filter { it.parent == cur.id }
        if (kids.isNotEmpty()) {
            append("<div class=\"ringrow\">")
            for (k in kids) append("<span class=\"dagchip\" data-sheet=\"").append(esc(k.id)).append("\">▤ ").append(esc(k.title))
                .append(" <i>").append(k.rows.size).append("</i></span>")
            append("</div>")
        }
        if (cur.rows.isEmpty()) {
            append("<div class=\"kboard-status\">empty sheet — ").append(esc(cur.columns.joinToString(" · ") { it.name })).append("</div>")
            return@buildString
        }
        append("<div class=\"tablewrap\"><table><tr>")
        for (col in cur.columns) append("<th title=\"").append(esc(col.type)).append("\">").append(esc(col.name)).append("</th>")
        append("</tr>")
        for (row in cur.rows.take(1024)) {
            append("<tr>")
            for (cell in row.take(64)) {
                if (cell is SheetRef) {
                    val child = family[cell.sheet]
                    append("<td><span class=\"dagchip\" data-sheet=\"").append(esc(cell.sheet)).append("\">▤ ")
                        .append(esc(child?.title ?: cell.sheet)).append("</span></td>")
                } else append("<td class=\"leafcell\" title=\"click to copy\">")
                    .append(esc(if (cell == null) "" else if (cell is Map<*, *> || cell is List<*>) compact(cell) else scalar(cell))).append("</td>")
            }
            append("</tr>")
        }
        append("</table></div>")
    }

    /** The roots strip above the sheet when the family has more than one root; the lit one holds [current]. */
    fun sheetRootsHtml(family: Map<String, SheetSeed>, current: String): String {
        val roots = family.values.filter { it.parent == null || it.parent !in family }
        if (roots.size <= 1) return ""
        var top = family[current]
        var guard = 0
        while (top != null && top.parent != null && top.parent in family && guard++ < 24) top = family[top.parent!!]
        return roots.joinToString("") { r ->
            "<button" + (if (top?.id == r.id) " class=\"here\"" else "") + " data-sheet-root=\"" + esc(r.id) + "\">▤ " + esc(r.title) + "</button>"
        }
    }

    /** The inspector's two texts: the actor line and the value as pretty JSON. */
    fun inspectorText(f: Fact?): Twin<String> =
        (f?.actor?.let { "written by $it" + (f.atMs?.let { at -> " at " + time(at) } ?: "") } ?: "") j (f?.let { pretty(it.value) } ?: "")

    fun neighborsHtml(rows: Series<Neighbor>): String = buildString {
        if (rows.size == 0) { append("<p class=\"sheet-note\">No supported neighbors in this snapshot.</p>"); return@buildString }
        for (n in rows.view) {
            val evidence = mutableListOf<String>()
            if (n.concepts.size > 0) evidence.add("SUMO: " + n.concepts.view.joinToString(", "))
            if (n.terms.size > 0) evidence.add("Text: " + n.terms.view.joinToString(", "))
            for (r in n.references.view) evidence.add("Reference: " + r.a + " → " + r.b)
            append("<button class=\"neighbor-row\" data-key=\"").append(esc(n.key))
                .append("\" title=\"Shared concepts and text are associations; references retain their recorded direction.\"><b>")
                .append(esc(n.key)).append("</b><span>").append(esc(evidence.joinToString(" · "))).append("</span><span>")
                .append(esc(n.actor ?: "unattributed")).append(" · similarity ").append(esc(score(n.score))).append("</span></button>")
        }
    }

    private fun score(v: Double): String {
        val scaled = kotlin.math.round(v * 1000).toLong()
        return (scaled / 1000).toString() + "." + (scaled % 1000).toString().padStart(3, '0')
    }

    /** Controls the board renderer does not carry: id → the reason a reader sees on hover. */
    fun stubbed(): Series<Join<String, String>> {
        val canvas = "Not in the board renderer; LCNC editing lives on /panels"
        val terrain = "Not in the board renderer; the graal terrain is not drawn here"
        return s_[
            "runBtn" j canvas, "argumentsBtn" j canvas, "cancelRun" j canvas, "stopBtn" j canvas, "addBtn" j canvas,
            "panelName" j canvas, "storeSaveBtn" j canvas, "snapshotBtn" j canvas, "storeLoadBtn" j canvas, "presetsBtn" j canvas,
            "rdfBtn" j canvas, "paletteBtn" j canvas, "keysBtn" j canvas, "exportBtn" j canvas, "importBtn" j canvas,
            "undoBtn" j canvas, "redoBtn" j canvas, "clearBtn" j canvas, "qsBtn" j canvas, "fbBtn" j canvas,
            "archiveBtn" j terrain, "objectsBtn" j terrain, "terrainLayer" j terrain, "boardHome" j terrain,
            "viewBack" j terrain, "viewUp" j terrain, "fdBtn" j canvas, "shakeBtn" j canvas, "fitBtn" j canvas,
            "parentHandle" j canvas, "panelsBreakout" j "Open the LCNC canvas at /panels",
        ]
    }

    // ── the location hash ───────────────────────────────────────────────────

    fun hashFor(key: String?): String = if (key == null) "" else "#" + HASH_KEY + encode(key)

    fun keyFromHash(hash: String): String? {
        val body = hash.removePrefix("#")
        if (!body.startsWith(HASH_KEY)) return null
        return decode(body.removePrefix(HASH_KEY)).ifEmpty { null }
    }

    /** RFC 3986 unreserved kept; everything else percent-encoded byte-wise. */
    fun encode(value: String): String = buildString(value.length + 8) {
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') append(c)
            else append('%').append(HEX[b shr 4]).append(HEX[b and 15])
        }
    }

    fun decode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.add(hex.toByte()); i += 3; continue }
            }
            for (b in c.toString().encodeToByteArray()) bytes.add(b)
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"

    // ── small helpers ───────────────────────────────────────────────────────

    fun time(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString().let { iso ->
        iso.substringAfter('T').take(8) + " UTC"
    }

    fun shortCid(cid: String): String {
        val hex = cid.substringAfter(':', cid)
        return if (hex.length > 12) cid.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" } + hex.take(12) else cid
    }

    fun esc(s: String): String = DocumentMarkdown.escape(s)

    private fun actorOf(value: Any?): String? = (value as? Map<*, *>)?.get("actor")?.toString()

    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    private fun strings(v: Any?): Series<String> = (v as? List<*>)?.mapNotNull { it?.toString() }?.toSeries() ?: emptySeriesOf()
}
