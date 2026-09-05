package borg.trikeshed.kanban

/**
 * The brief a claimed card hands the daemon's brain, written the way an RFC
 * states expectations: a goal, RFC 2119 acceptance criteria, evidence from
 * the fact plane, the daemon's state, the lessons the board has already paid
 * for, and the exact shape of the reply the plane judge will read.
 *
 * Lineage: Hermes' kanban (an agent fore-runner, not a template) specifies a
 * card as Goal / Approach / Acceptance criteria and closes goal-mode work with
 * a judge. Here the criteria are RFC 2119 lines on the card's `spec`, and the
 * judge is [PlaneJudge] — the production system over the plane — not a
 * second model call. Deltas 2026-09-04: RFC form replaces the title-only
 * brief and the fixed-width fact dump; a compact evidence list (≤ 16 ids)
 * replaces 24 field rows. Delta 2026-09-05 (fan-out): a CHILDREN block and a
 * MERGE line follow EVIDENCE when the card is the fan-in of a `MODELS:` tree
 * ([ChildReceipt]); the reply shape is unchanged so the judge reads both.
 *
 * Pure: rows and receipts in, text out.
 */
object PlaneBrief {
    /** One plane fact as the brief sees it. */
    data class Row(val partition: String, val id: String, val fields: Map<String, Any?>)

    /** One RFC 2119 criterion from the card's spec. */
    data class Criterion(val level: String, val index: Int, val text: String) {
        /** The label the reply must echo: `MUST-1`, `SHOULD-2`, … */
        val label: String get() = "$level-$index"
    }

    /** What a card's spec says, parsed. Every field is optional on the spec; the defaults are here. */
    data class Spec(
        val goal: String,
        val criteria: List<Criterion>,
        val outOfScope: List<String>,
        val humanReview: Boolean,
        val model: String,
        val tokens: Int?,
        /**
         * Delta 2026-09-05 (fan-out): `MODELS: a, b, c` names the models a card fans
         * OUT to — one child card per id, each claimed by the ordinary loop — and
         * `FANOUT: n` asks for the first n ids of `mux.models` instead. On a card
         * that fans out, `MODEL:` keeps its meaning for the parent itself: it is
         * the model that MERGES the children's receipts. A single `MODELS:` entry
         * is not a fan-out; it degrades to `model` when `MODEL:` is blank.
         */
        val models: List<String> = emptyList(),
        val fanout: Int? = null,
    ) {
        val musts: List<Criterion> get() = criteria.filter { it.level == "MUST" }

        /** ≥ 2 explicit targets, or `FANOUT: n` with n ≥ 2: this card is split, never claimed whole. */
        val fansOut: Boolean get() = models.size >= 2 || (fanout ?: 0) >= 2
    }

    const val MAX_EVIDENCE: Int = 16
    const val MAX_LINE: Int = 120
    const val DEFAULT_MUST: String = "name the concrete next action, citing one evidence id from the plane, or state that none applies"

    private val STOP = setOf(
        "the", "and", "for", "that", "this", "with", "from", "into", "when", "then", "than", "them", "they",
        "before", "after", "make", "name", "file", "change", "card", "next", "step", "smallest", "every", "lands",
        "should", "would", "could", "propose", "which", "what", "where", "there", "their", "will", "does", "have",
        "must", "may", "goal", "spec",
    )

    /** One spec line's head as [specHead] reads it: the name (upper-cased, numeric suffix dropped), whether a colon followed it, the rest. */
    data class Head(val name: String, val colon: Boolean, val rest: String)

    private val HEAD_RE = Regex("^([A-Za-z]+(?:-[A-Za-z]+)*)(?:-\\d+)?\\s*(:?)\\s*(.*)$")

    /**
     * The head of one spec line, or null when the line has none. `MUST-1: …` reads as
     * MUST (the label form the REPLY block itself teaches; the number is renumbered in
     * first-seen order), `OUT-OF-SCOPE` keeps its hyphens, a leading list marker is
     * dropped. Shared with `BoardFanOutWorker.childSpec` so what the parser reads is
     * what a child does not inherit.
     */
    fun specHead(raw: String): Head? {
        val line = raw.trim().trimStart('-', '*', ' ')
        val m = HEAD_RE.find(line) ?: return null
        return Head(m.groupValues[1].uppercase(), m.groupValues[2] == ":", m.groupValues[3].trim())
    }

    /**
     * Parse a card's spec. Recognised line heads (case-insensitive, colon optional):
     * `GOAL`, `MUST`, `SHOULD`, `MAY` (a numeric suffix such as `MUST-1` is accepted),
     * `OUT-OF-SCOPE`, `REVIEW: human`, `MODEL: <id>`, `TOKENS: <n>`. The two heads that
     * change a card's lane process — `MODELS: <id>, <id>, …` (fan-out targets) and
     * `FANOUT: <n>` (first n of `mux.models`) — are read ONLY with their colon: a prose
     * line beginning "Models like …" is prose, never a split. Anything else is prose
     * and ignored. A spec without a MUST gets [DEFAULT_MUST] as MUST-1 so every card
     * has one verifiable line.
     */
    fun parseSpec(title: String, spec: String): Spec {
        var goal = title
        val criteria = ArrayList<Criterion>()
        val out = ArrayList<String>()
        var human = false
        var model = ""
        var tokens: Int? = null
        var models: List<String> = emptyList()
        var fanout: Int? = null
        val counts = HashMap<String, Int>()
        for (raw in spec.lines()) {
            val h = specHead(raw) ?: continue
            val head = h.name
            val rest = h.rest
            when (head) {
                "GOAL" -> if (rest.isNotEmpty()) goal = rest
                "MUST", "SHOULD", "MAY" -> if (rest.isNotEmpty()) {
                    val n = (counts[head] ?: 0) + 1
                    counts[head] = n
                    criteria.add(Criterion(head, n, rest))
                }
                "OUT-OF-SCOPE", "OUTOFSCOPE", "OUT" -> if (rest.isNotEmpty()) out.add(rest)
                "REVIEW" -> human = rest.lowercase().startsWith("human") || rest.lowercase() == "yes"
                "MODEL" -> model = rest
                "TOKENS" -> tokens = rest.toIntOrNull()
                "MODELS" -> if (h.colon) models = rest.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                "FANOUT" -> if (h.colon) fanout = rest.toIntOrNull()
            }
        }
        if (criteria.none { it.level == "MUST" }) criteria.add(0, Criterion("MUST", 1, DEFAULT_MUST))
        // one MODELS entry is a MODEL, not a fan-out
        if (models.size == 1 && model.isBlank()) model = models[0]
        return Spec(goal, criteria, out, human, model, tokens, models, fanout)
    }

    /** The card's own terms: lowercase words of 4+ letters, stopwords out, in first-seen order. */
    fun terms(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in text.lowercase().split(Regex("[^a-z0-9]+"))) if (w.length >= 4 && w !in STOP) out.add(w)
        return out.toList()
    }

    /**
     * Facts that mention the terms, best first: score = distinct terms found in the
     * id or any string field; ties prefer source over `build/` output and shorter ids.
     * Rows scoring 0 are not evidence. Bounded to [limit].
     */
    fun select(rows: List<Row>, text: String, limit: Int = MAX_EVIDENCE): List<Row> {
        val ts = terms(text)
        if (ts.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Row>>()
        for (r in rows) {
            val hay = buildString {
                append(r.id.lowercase())
                for (v in r.fields.values) if (v is String && v.length <= 400) { append(' '); append(v.lowercase()) }
            }
            var score = 0
            for (t in ts) if (hay.contains(t)) score++
            if (score > 0) scored.add(score to r)
        }
        scored.sortWith(
            compareByDescending<Pair<Int, Row>> { it.first }
                .thenBy { if (it.second.id.contains("/build/")) 1 else 0 }
                .thenBy { it.second.id.length }
                .thenBy { it.second.id },
        )
        return scored.take(limit).map { it.second }
    }

    /** The daemon's state rows: the JVM tick facts. */
    fun state(rows: List<Row>): List<Row> = rows.filter { it.partition == "graal" && it.fields["kind"] in setOf("memory", "gc", "jit") }

    /** `partition/id`, the evidence token the reply must echo. */
    fun evidenceId(r: Row): String = "${r.partition}/${r.id}"

    private fun clip(s: String): String = if (s.length > MAX_LINE) s.take(MAX_LINE - 1) + "…" else s

    /** One evidence line: the id, and its kind when it has one. Nothing wider. */
    fun evidenceLine(r: Row): String {
        val kind = r.fields["kind"]?.toString()
        return clip(evidenceId(r) + if (kind.isNullOrEmpty()) "" else "  ($kind)")
    }

    /** The daemon's state on one line: heap, gc collections, jit compilations. */
    fun stateLine(state: List<Row>): String {
        val bits = ArrayList<String>()
        for (r in state) when (r.fields["kind"]) {
            "memory" -> bits.add("heap ${mb(r.fields["heapUsed"])}/${mb(r.fields["heapMax"])} MB")
            "gc" -> bits.add("${r.fields["collector"]} ${r.fields["collections"]} gcs")
            "jit" -> bits.add("jit ${r.fields["compilations"]} compiles")
        }
        return bits.joinToString(" · ")
    }

    private fun mb(v: Any?): String = ((v as? Number)?.toLong() ?: 0L).let { it / 1_048_576 }.toString()

    /** One prior claim receipt, as the lessons fold sees it. */
    data class Receipt(val model: String, val ok: Boolean, val error: String)

    /**
     * One child card's claim receipt, as the fan-IN brief carries it. Delta 2026-09-05
     * (fan-out): a card that fanned out to `MODELS:` children is re-claimed once every
     * child is Done, and the merge model reads the children's answers here rather than
     * re-asking. [evidenceId] is the blackboard key as the plane names it
     * (`blackboard/kanban/claim/<child>`), so the reply can cite it verbatim and the
     * judge can accept it. [content] is the child's answer, or its error when it failed.
     */
    data class ChildReceipt(
        val evidenceId: String,
        val jobId: String,
        val model: String,
        val ok: Boolean,
        val decision: String,
        val content: String,
    )

    /** How much of one child's answer the merge brief carries; the receipt on the blackboard keeps the whole. */
    const val MAX_CHILD_CHARS: Int = 600

    /** The merge instruction, one line, verbatim in every fan-in brief. */
    const val MERGE_LINE: String = "MERGE: reconcile the children into ONE answer; where they disagree, say which you kept and why; cite every child receipt id you used."

    /**
     * `<evidenceId>  (model <id>, <decision>)` — the child's header line; a child without a
     * decision says `ok` or `failed`. The id is never clipped: the brief tells the merge
     * model to cite it verbatim, and a clipped id is not on the plane. Only the parenthetical is.
     */
    fun childLine(c: ChildReceipt): String {
        val decision = c.decision.ifBlank { if (c.ok) "ok" else "failed" }
        return c.evidenceId + "  " + clip("(model ${c.model.ifBlank { "?" }}, $decision)")
    }

    /** The child's content folded onto one line (newlines → spaces) and clipped to [MAX_CHILD_CHARS]. */
    fun childContent(c: ChildReceipt): String {
        val folded = c.content.replace('\r', ' ').replace('\n', ' ').replace(Regex(" {2,}"), " ").trim()
        return if (folded.length > MAX_CHILD_CHARS) folded.take(MAX_CHILD_CHARS - 1) + "…" else folded
    }

    /**
     * What the board already paid to learn, per model: how often it answered, how
     * often it failed and with what, folded from the `kanban/claim/<jobId>` receipts. A
     * model with failures gets an AVOID line naming the failure; one with only
     * answers gets a DO line. No receipts, no lessons — nothing invented.
     */
    fun lessons(receipts: List<Receipt>): List<String> {
        if (receipts.isEmpty()) return emptyList()
        val byModel = receipts.filter { it.model.isNotEmpty() }.groupBy { it.model }
        val out = ArrayList<String>()
        for ((model, rs) in byModel.entries.sortedByDescending { it.value.size }) {
            val ok = rs.count { it.ok }
            val bad = rs.size - ok
            if (bad > 0) {
                val last = rs.lastOrNull { !it.ok }?.error.orEmpty().take(70)
                out.add(clip("AVOID  $model: $bad of ${rs.size} claims failed — last: $last"))
            } else {
                out.add(clip("DO     $model: $ok of $ok claims answered"))
            }
        }
        return out.take(6)
    }

    /** The state machine the reply drives, as the RFC draws it. */
    const val FLOW: String = """
  READY --claim--> RUNNING --VERDICT MET + evidence on plane--> REVIEW --judge--> DONE
                     |------ NEEDS-HUMAN or REVIEW: human ------> REVIEW (a person decides)
                     '------ NOT-MET / no verdict ----------------> READY (strike; 3rd -> BLOCKED)"""

    fun render(
        jobId: String,
        title: String,
        spec: Spec,
        evidence: List<Row>,
        state: List<Row>,
        lessons: List<String>,
        /**
         * Delta 2026-09-05 (fan-out): the children's receipts when this card is the
         * fan-IN of a `MODELS:` tree. Empty for an ordinary card, and the brief is then
         * byte-identical to the 2026-09-04 shape. The reply shape does not change: the
         * merge is still judged by [PlaneJudge] on VERDICT + criteria lines, with the
         * child receipt ids as citable evidence.
         */
        children: List<ChildReceipt> = emptyList(),
    ): String = buildString {
        append("Card ").append(jobId).append(" — brief (RFC 2119: MUST, SHOULD, MAY)\n")
        append("GOAL: ").append(spec.goal).append('\n')
        append("ACCEPTANCE:\n")
        for (c in spec.criteria) append("  ").append(c.label).append(": ").append(c.text).append('\n')
        if (spec.outOfScope.isNotEmpty()) {
            append("OUT-OF-SCOPE:\n")
            for (o in spec.outOfScope) append("  - ").append(o).append('\n')
        }
        append("EVIDENCE on the daemon's plane (cite these ids verbatim):\n")
        if (evidence.isEmpty()) append("  (no fact mentions this card's terms)\n")
        for (r in evidence) append("  ").append(evidenceLine(r)).append('\n')
        if (children.isNotEmpty()) {
            append("CHILDREN (this card fanned out; each child's receipt id is citable evidence):\n")
            for (c in children) {
                append("  ").append(childLine(c)).append('\n')
                val body = childContent(c)
                if (body.isNotEmpty()) append("    ").append(body).append('\n')
            }
            append(MERGE_LINE).append('\n')
        }
        val st = stateLine(state)
        if (st.isNotEmpty()) append("DAEMON: ").append(st).append('\n')
        if (lessons.isNotEmpty()) {
            append("LESSONS (from this board's own receipts):\n")
            for (l in lessons) append("  ").append(l).append('\n')
        }
        append("FLOW:").append(FLOW).append('\n')
        append("REPLY (exactly this shape; the plane judge parses it, a person does not):\n")
        append("  VERDICT: MET | NOT-MET | NEEDS-HUMAN\n")
        for (c in spec.criteria) append("  ").append(c.label).append(": MET | NOT-MET — evidence: <one id from EVIDENCE, or none>\n")
        append("  ACTION: <= 3 sentences, plain text\n")
        append("A MUST marked MET with no evidence id counts as NOT-MET. Do not restate the brief.")
    }
}
