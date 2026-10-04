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
        fun pc(x: Any?) = ((x as? Number)?.toDouble() ?: 0.0).let { (it * 100).toInt() }
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
        // Jev's table: the page judged against what was read from it.
        val jev = map(v["jev"])
        if (jev.isNotEmpty()) {
            val tuples = list(jev["tuples"]).map(::map); val dates = list(jev["dates"]).map(::map)
            val stated = tuples.count { ((it["noul"] as? Number)?.toDouble() ?: 0.0) >= 0.5 }
            append("<h3>Jev · the page against its tuples</h3><dl>")
            append("<dt>legible</dt><dd>").append(list(jev["legible"]).joinToString(" · ") { "${pc(it)}%" }).append("</dd>")
            jev["whole"]?.let { append("<dt>whole</dt><dd>").append(pc(it)).append("%</dd>") }
            append("<dt>stated</dt><dd>").append(stated).append(" of ").append(tuples.size).append(" tuples")
            ((jev["unasked"] as? Number)?.toInt() ?: 0).takeIf { it > 0 }?.let { append(" · ").append(it).append(" unasked") }
            append("</dd><dt>tokens</dt><dd>").append(jev["tokens"]).append("</dd></dl>")
            val establishes = list(jev["establishes"]).map(::map)
            if (establishes.isNotEmpty()) {
                append("<h3>premises this page states, now facts</h3><ol>")
                for (e in establishes) append("<li>").append(esc(e["question"])).append(" <span class='tv'>").append(pc(e["noul"])).append("%</span></li>")
                append("</ol>")
            }
            if (tuples.isNotEmpty()) {
                append("<ol>")
                for (t in tuples.sortedBy { (it["noul"] as? Number)?.toDouble() ?: 0.0 }) {
                    append("<li").append(if (((t["noul"] as? Number)?.toDouble() ?: 0.0) < 0.5) " class='opp'" else "").append(">")
                        .append(esc(t["sentence"])).append(" <span class='tv'>").append(pc(t["noul"])).append("%</span></li>")
                }
                append("</ol>")
            }
            if (dates.isNotEmpty()) {
                append("<h3>dates CoreNLP tagged</h3><ol>")
                for (d in dates) {
                    val out = d["after"] == true || (((d["date"] as? Number)?.toDouble() ?: 0.0) >= 0.5 && ((d["late"] as? Number)?.toDouble() ?: 0.0) >= 0.5)
                    append("<li").append(if (out) " class='opp'" else "").append(">")
                        .append(esc(d["tagged"])).append(" <span class='tv'>date ").append(pc(d["date"])).append("% · late ").append(pc(d["late"])).append("%")
                    if (d["after"] == true) append(" · after the work's date")
                    append("</span></li>")
                }
                append("</ol>")
            }
            // A noun's sense as its sentence uses it: Jev's choice among the lexicon's senses, the lexicon's own struck where Jev discounts it.
            val senses = list(jev["senses"]).map(::map)
            if (senses.isNotEmpty()) {
                val discounted = senses.filter { it["discounts"] == true }
                append("<h3>senses · ").append(senses.size).append(" asked, ").append(discounted.size).append(" discounting the lexicon</h3>")
                if (discounted.isNotEmpty()) {
                    append("<ol>")
                    for (s in discounted) append("<li><b>").append(esc(s["lemma"])).append("</b> <s>").append(esc(s["read"])).append("</s> ")
                        .append(pc(s["readP"])).append("% → <b>").append(esc(s["sense"])).append("</b> <span class='tv'>").append(pc(s["p"]))
                        .append("%</span><br><small>").append(esc(s["sentence"])).append("</small></li>")
                    append("</ol>")
                }
            }
            list(jev["errors"]).takeIf { it.isNotEmpty() }?.let { append("<p class='opp'>").append(esc(it.joinToString("; "))).append("</p>") }
        }
        // Jev over the constellation: the NARS state's own questions for this page, and a free one.
        append("<h3>ask Jev</h3><form class='ask' data-book='").append(esc(v["book"])).append("' data-section='").append(v["ordinal"]).append("'>")
            .append("<input name='q' type='search' placeholder='a question for the books this page sits among' aria-label='Ask Jev'></form>")
        val questions = list(v["questions"])
        if (questions.isNotEmpty()) {
            append("<p class='chips'>")
            for (q in questions) append("<span data-ask='").append(esc(q).replace("'", "&#39;")).append("'>").append(esc(q)).append("</span>")
            append("</p>")
        }
        append("<div class='oracle'></div>")
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
                    c["read"]?.let { append(" <s>").append(esc(it)).append("</s> <i>Jev ").append(pc(c["jev"])).append("%</i>") }
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
     * Jev's answer over a constellation (the value of `POST /api/curation/jev`): whether any passage answers, the
     * passages by Jev's choice, the norms by whether they bear (with NAL truth and the books stating them), the
     * premises the question establishes, and what the rete fires on them.
     */
    fun oracle(v: Map<*, *>): String = buildString {
        fun esc(x: Any?) = (x ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun list(x: Any?) = x as? List<*> ?: emptyList<Any?>()
        fun map(x: Any?) = x as? Map<*, *> ?: emptyMap<Any?, Any?>()
        fun pc(x: Any?) = ((x as? Number)?.toDouble() ?: 0.0).let { (it * 100).toInt() }
        v["error"]?.let { append("<p class='opp'>").append(esc(it)).append("</p>"); return@buildString }
        append("<dl><dt>answered</dt><dd>").append(pc(v["exists"])).append("% · ").append(esc(v["choice"])).append(" at ").append(pc(v["confidence"]))
            .append("%</dd><dt>classes</dt><dd>").append(esc(list(v["classes"]).joinToString(", "))).append("</dd><dt>tokens</dt><dd>")
            .append(esc(map(v["usage"])["input_tokens"])).append(" · ").append(esc(v["asked"])).append(" questions asked, the rest standing</dd></dl>")
        append("<h3>passages</h3><ol>")
        for (p0 in list(v["passages"])) {
            val p = map(p0)
            append("<li><b>").append(esc(p["book"])).append("</b> · ").append(esc(p["section"])).append(" <span class='tv'>").append(pc(p["p"]))
                .append("%</span><br>").append(esc(p["text"])).append("</li>")
        }
        append("</ol>")
        val norms = list(v["norms"]).map(::map)
        if (norms.isNotEmpty()) {
            append("<h3>norms</h3><ol>")
            for (n in norms) {
                append("<li>").append(esc(n["sentence"]))
                    .append(" <span class='tv'>bears ").append(pc(n["bears"])).append("% · ").append(pc(n["frequency"])).append("%/").append(pc(n["confidence"]))
                    .append("% · ").append(esc(list(n["books"]).joinToString(", "))).append("</span>")
                if (list(n["opposedBy"]).isNotEmpty()) append("<br><span class='opp'>opposed</span>")
                append("</li>")
            }
            append("</ol>")
        }
        val premises = list(v["premises"]).map(::map)
        if (premises.isNotEmpty()) {
            append("<h3>premises</h3><ol>")
            for (p in premises) append("<li>").append(esc(p["question"])).append(" <span class='tv'>").append(pc(p["holds"])).append("%</span></li>")
            append("</ol>")
        }
        val fires = list(v["fires"])
        if (fires.isNotEmpty()) append("<h3>fires</h3><ol>").append(fires.joinToString("") { "<li>" + esc(it) + "</li>" }).append("</ol>")
        val pending = map(v["pending"])
        if (pending.isNotEmpty()) append("<h3>waiting on</h3><ol>").append(pending.entries.joinToString("") { (q, ns) ->
            "<li>" + esc(q) + "<br><small>" + esc(list(ns).joinToString("; ")) + "</small></li>" }).append("</ol>")
    }

    /**
     * Finding-aid rows ([CuratorScope.find], [CuratorScope.contents], [CuratorScope.query]) as an ordered
     * list under [title]; each row carries `data-node` (goto) and, when it holds anything, `data-open` (its contents).
     * A row of `/api/curation/find` carries `data-ring` (its ring key) instead, and shows its kind, book and text; a
     * row Jev ordered shows its noul.
     */
    fun rows(title: String, rows: List<Map<*, *>>): String = buildString {
        fun esc(x: Any?) = (x ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        append("<h3>").append(esc(title)).append(" · ").append(rows.size).append("</h3><ol class='toc'>")
        for (r in rows) {
            val n = r["node"]; val count = (r["count"] as? Number)?.toInt() ?: 0
            val ring = r["ring"]
            if (ring != null) append("<li data-ring='").append(esc(ring).replace("'", "&#39;")).append("'>")
            else append("<li data-node='").append(n).append("'>")
            append("<b>").append(esc(r["name"] ?: r["heading"] ?: r["book"])).append("</b>")
            (r["in"] ?: listOfNotNull(r["kind"], r["book"].takeIf { r["heading"] != null }).joinToString(" · ").ifEmpty { null })
                ?.let { append(" <em>").append(esc(it)).append("</em>") }
            (r["jev"] as? Number)?.let { append(" <span class='jev'>Jev ").append((it.toDouble() * 100).toInt()).append("%</span>") }
            if (count > 0) append(" <a data-open='").append(n).append("'>").append(count).append(" ▸</a>")
            (r["note"]?.takeIf { r["type"] == "book.statement" } ?: r["text"]?.takeIf { r["kind"] != "book" })
                ?.let { append("<br><small>").append(esc(it)).append("</small>") }
            append("</li>")
        }
        append("</ol>")
    }
}
