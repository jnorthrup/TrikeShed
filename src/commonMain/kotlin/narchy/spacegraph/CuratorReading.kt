package narchy.spacegraph

/**
 * A curated section read whole, as the curator panel shows it (the value of `/api/curation/section`):
 * the section's shape (lines, tokens, Shannon bits, LZ description length and K-complexity), the
 * SUMO classes its concepts fall under, and per sentence every concept with its class and nearest
 * ancestors, the named entities, the predicates, and the statements read from it; then the section's
 * statements with book-wide truth and the sections that oppose them, and its citations both ways.
 */
object CuratorReading {
    fun html(v: Map<*, *>): String = buildString {
        fun esc(x: Any?) = (x ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun list(x: Any?) = x as? List<*> ?: emptyList<Any?>()
        fun map(x: Any?) = x as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val shape = map(v["shape"])
        append("<h2>").append(esc(v["heading"])).append("<span>section ").append(v["ordinal"]).append(" of ").append(v["of"])
            .append(" · ").append(esc(v["book"])).append("</span></h2>")
        val k = (shape["k"] as? Number)?.toDouble() ?: 0.0
        append("<div class='k'><i style='width:").append((k * 100).toInt()).append("%'></i></div>")
        append("<dl>")
        for ((label, key) in listOf("chars" to "chars", "tokens" to "tokens", "unique" to "uniqueTokens", "bits/byte" to "shannonBitsPerByte",
            "LZ phrases" to "lzPhrases", "desc. bits" to "descriptionBits", "K" to "k", "line kinds" to "kinds")) {
            val x = shape[key] ?: continue
            append("<dt>").append(label).append("</dt><dd>").append(esc(if (x is Double) ((x * 1000).toInt() / 1000.0) else x)).append("</dd>")
        }
        append("</dl>")
        val classes = list(v["classes"])
        if (classes.isNotEmpty()) {
            append("<h3>concepts by SUMO class</h3><p class='chips'>")
            for (c in classes.take(24)) append("<span>").append(esc(map(c)["class"])).append(" <b>").append(map(c)["n"]).append("</b></span>")
            append("</p>")
        }
        val sentences = list(v["sentences"])
        append("<h3>sentences · ").append(sentences.size).append("</h3>")
        for ((i, s0) in sentences.withIndex()) {
            val s = map(s0)
            append("<div class='sent'><p class='said'><em>").append(i + 1).append("</em> ").append(esc(s["text"])).append("</p>")
            val concepts = list(s["concepts"])
            if (concepts.isNotEmpty()) {
                append("<p class='chips'>")
                for (c0 in concepts) {
                    val c = map(c0)
                    val lineage = list(c["is"]).joinToString(" ⊂ ") { it.toString() }
                    append("<span title='").append(esc(lineage)).append("'>").append(esc(c["lemma"]))
                    c["class"]?.let { append(" <b>").append(esc(it)).append("</b>") }
                    append("</span>")
                }
                append("</p>")
            }
            val entities = map(s["entities"])
            if (entities.isNotEmpty()) append("<p class='ent'>").append(entities.entries.joinToString(" · ") { (t, ws) -> esc(t) + ": " + esc(list(ws).joinToString(", ")) }).append("</p>")
            val preds = list(s["predicates"])
            if (preds.isNotEmpty()) append("<p class='pred'>").append(esc(preds.joinToString(" · "))).append("</p>")
            for (st0 in list(s["statements"])) {
                val st = map(st0)
                append("<p class='stmt'>").append(esc(st["bearer"]))
                st["class"]?.let { append("<sub>").append(esc(it)).append("</sub>") }
                append(" <b>").append(esc(st["force"])).append("</b> ").append(esc(st["action"]))
                st["object"]?.let { append(" · ").append(esc(it)) }
                st["condition"]?.let { append(" <i>").append(esc(it)).append("</i>") }
                append("</p>")
            }
            append("</div>")
        }
        val statements = list(v["statements"])
        if (statements.isNotEmpty()) {
            append("<h3>its statements across the book · ").append(statements.size).append("</h3><ol>")
            for (st0 in statements) {
                val st = map(st0)
                val f = (st["f"] as? Number)?.toDouble() ?: 0.0; val c = (st["c"] as? Number)?.toDouble() ?: 0.0
                append("<li>").append(esc(st["sentence"])).append(" <span class='tv'>").append((f * 100).toInt()).append("%/")
                    .append((c * 100).toInt()).append("% · ").append(st["sections"]).append(" §</span>")
                val opposed = list(st["opposed"])
                if (opposed.isNotEmpty()) append("<br><span class='opp'>opposed in ").append(esc(opposed.joinToString("; "))).append("</span>")
                append("</li>")
            }
            append("</ol>")
        }
        val cites = list(v["cites"]); val citedBy = list(v["citedBy"])
        if (cites.isNotEmpty()) append("<h3>cites</h3><p class='chips'>").append(cites.joinToString("") { "<span>" + esc(it) + "</span>" }).append("</p>")
        if (citedBy.isNotEmpty()) append("<h3>cited by</h3><p class='chips'>").append(citedBy.joinToString("") { "<span>" + esc(it) + "</span>" }).append("</p>")
    }

    /**
     * Finding-aid rows ([CuratorScope.find], [CuratorScope.contents], [CuratorScope.query]) as an ordered
     * list under [title]; each row carries `data-node` (goto) and, when it holds anything, `data-open` (its contents).
     */
    fun rows(title: String, rows: List<Map<String, Any?>>): String = buildString {
        fun esc(x: Any?) = (x ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        append("<h3>").append(esc(title)).append(" · ").append(rows.size).append("</h3><ol class='toc'>")
        for (r in rows) {
            val n = r["node"]; val count = (r["count"] as? Number)?.toInt() ?: 0
            append("<li data-node='").append(n).append("'>")
            append("<b>").append(esc(r["name"])).append("</b>")
            r["in"]?.let { append(" <em>").append(esc(it)).append("</em>") }
            if (count > 0) append(" <a data-open='").append(n).append("'>").append(count).append(" ▸</a>")
            r["note"]?.takeIf { r["type"] == "book.statement" }?.let { append("<br><small>").append(esc(it)).append("</small>") }
            append("</li>")
        }
        append("</ol>")
    }
}
