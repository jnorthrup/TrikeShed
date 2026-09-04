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
 * replaces 24 field rows.
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
    ) {
        val musts: List<Criterion> get() = criteria.filter { it.level == "MUST" }
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

    /**
     * Parse a card's spec. Recognised line heads (case-insensitive, colon optional):
     * `GOAL`, `MUST`, `SHOULD`, `MAY`, `OUT-OF-SCOPE`, `REVIEW: human`, `MODEL: <id>`,
     * `TOKENS: <n>`. Anything else is prose and ignored. A spec without a MUST gets
     * [DEFAULT_MUST] as MUST-1 so every card has one verifiable line.
     */
    fun parseSpec(title: String, spec: String): Spec {
        var goal = title
        val criteria = ArrayList<Criterion>()
        val out = ArrayList<String>()
        var human = false
        var model = ""
        var tokens: Int? = null
        val counts = HashMap<String, Int>()
        for (raw in spec.lines()) {
            val line = raw.trim().trimStart('-', '*', ' ')
            val m = Regex("^([A-Za-z-]+)\\s*:?\\s*(.*)$").find(line) ?: continue
            val head = m.groupValues[1].uppercase()
            val rest = m.groupValues[2].trim()
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
            }
        }
        if (criteria.none { it.level == "MUST" }) criteria.add(0, Criterion("MUST", 1, DEFAULT_MUST))
        return Spec(goal, criteria, out, human, model, tokens)
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
