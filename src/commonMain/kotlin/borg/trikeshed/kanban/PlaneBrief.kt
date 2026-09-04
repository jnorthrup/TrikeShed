package borg.trikeshed.kanban

/**
 * The brief a claimed card hands the daemon's brain, grounded in the fact plane.
 *
 * A card's title alone drew "I have no access to your repo" (witness-claim-2,
 * 2026-09-04). The plane already holds the repo — every worktree file is a
 * `trikeshed` fact, every panel node a `panels` fact, the JVM tick a `graal`
 * fact — so the brief carries the facts whose id or text mentions the card's
 * own terms, plus the daemon's state, bounded so a 1024-token answer has room.
 *
 * Pure: rows in, text out. The module supplies the rows from one
 * `ReteNetwork.snapshot()` at claim time.
 */
object PlaneBrief {
    /** One plane fact as the brief sees it. */
    data class Row(val partition: String, val id: String, val fields: Map<String, Any?>)

    const val MAX_ROWS: Int = 24
    const val MAX_LINE: Int = 180

    private val STOP = setOf(
        "the", "and", "for", "that", "this", "with", "from", "into", "when", "then", "than", "them", "they",
        "before", "after", "make", "name", "file", "change", "card", "next", "step", "smallest", "every", "lands",
        "should", "would", "could", "propose", "which", "what", "where", "there", "their", "will", "does", "have",
    )

    /** The card's own terms: lowercase words of 4+ letters, stopwords out, in first-seen order. */
    fun terms(title: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in title.lowercase().split(Regex("[^a-z0-9]+"))) if (w.length >= 4 && w !in STOP) out.add(w)
        return out.toList()
    }

    /**
     * Facts that mention the terms, best first: score = distinct terms found in the
     * id or any string field; ties prefer source over `build/` output and shorter ids.
     * Rows scoring 0 are not context. Bounded to [limit].
     */
    fun select(rows: List<Row>, title: String, limit: Int = MAX_ROWS): List<Row> {
        val ts = terms(title)
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

    /** The daemon's state rows: the JVM tick facts, always worth a line. */
    fun state(rows: List<Row>): List<Row> = rows.filter { it.partition == "graal" && it.fields["kind"] in setOf("memory", "gc", "jit") }

    private val SHOW = listOf("kind", "key", "type", "column", "owner", "collector", "collections", "heapUsed", "heapMax", "compilations", "status", "title", "mark", "facet")

    fun line(r: Row): String {
        val bits = SHOW.mapNotNull { k -> r.fields[k]?.let { v -> "$k=${v.toString().take(40)}" } }
        val s = "${r.partition}/${r.id}" + if (bits.isEmpty()) "" else ": " + bits.joinToString(", ")
        return if (s.length > MAX_LINE) s.take(MAX_LINE - 1) + "…" else s
    }

    fun render(jobId: String, title: String, mentioned: List<Row>, state: List<Row>): String = buildString {
        append("Card ").append(jobId).append(": ").append(title).append('\n')
        if (mentioned.isNotEmpty()) {
            append("Facts on the daemon's plane that mention this card's terms (partition/id: fields):\n")
            for (r in mentioned) append("- ").append(line(r)).append('\n')
        } else {
            append("No fact on the daemon's plane mentions this card's terms.\n")
        }
        if (state.isNotEmpty()) {
            append("Daemon state now:\n")
            for (r in state) append("- ").append(line(r)).append('\n')
        }
        append("Propose the concrete next action in ≤ 3 sentences, naming a file or fact from the lists above where one applies; say so if none does.")
    }
}
