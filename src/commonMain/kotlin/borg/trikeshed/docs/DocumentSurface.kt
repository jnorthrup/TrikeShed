package borg.trikeshed.docs

/**
 * THE DOCUMENT SURFACE: the muggle half of Forge, and the first page whose logic lives in
 * commonMain and reaches the browser through the Kotlin/JS bundle (the owner's 2026-09-06
 * ruling: emulate GWT, hand-written JS only for dire dependency access).
 *
 * It is patterned after the workspace-and-page products a stranger already knows: a sidebar
 * that nests a workspace's pages under it, a breadcrumb, a page whose title is its own
 * heading, and a table view of the same rows for when the workspace IS the data. What is
 * underneath is not theirs: a page is a document in a project database with a cid, a rev and
 * a sequence, and a fenced `lcnc-run` block in it is a build target (see [RunBlock]).
 *
 * Every function here is pure: JSON in (as the parser's maps and lists), HTML strings out.
 * The jsMain page only fetches, mounts and forwards clicks.
 */
data class ProjectRow(val name: String, val kind: String, val docs: Int, val path: String)

data class DocRow(val id: String, val cid: String, val rev: String, val seq: Long, val length: Long, val contentType: String)

data class DocView(
    val project: String,
    val id: String,
    val cid: String,
    val rev: String,
    val seq: Long,
    val contentType: String,
    val text: String,
    /** The miner's `.extract.md` twin when one sits beside the document. */
    val extract: String? = null,
)

/** Page reads one document; Table reads the whole project as rows. Both are the same data. */
enum class SurfaceView { PAGE, TABLE }

// ── curation: the reader's own text, sent through the same curator the projects use ──────

data class CurationDestination(val model: String, val provider: String, val url: String)

data class CurationRecordRow(val receiptCid: String, val name: String)

data class CurationProposal(
    val subject: String?, val predicate: String?, val obj: String?,
    val quote: String?, val reasons: List<String>, val submitted: Boolean, val duplicate: Boolean,
)

data class CurationAxiom(
    val antecedent: String?, val predicate: String?, val consequent: String?,
    val quote: String?, val label: String?,
)

data class CurationResult(
    val receiptCid: String, val name: String, val text: String,
    val proposals: List<CurationProposal>, val reasons: List<String>, val axioms: List<CurationAxiom>,
)

/** The table's sortable columns, each with the header a reader sees. */
enum class DocColumn(val key: String, val label: String) {
    NAME("name", "Name"), TYPE("type", "Type"), SIZE("size", "Size"), SEQ("seq", "Sequence");

    companion object {
        fun of(key: String?): DocColumn = entries.firstOrNull { it.key == key } ?: NAME
    }
}

object DocumentSurface {
    const val ROOT_ID = "document-surface"
    const val SIDE_ID = "ds-side"
    const val CRUMB_ID = "ds-crumb"
    const val CONTENT_ID = "ds-content"
    const val DIGEST_HREF = "/harness?load=preset-corpus"
    const val CURATION_HASH = "curation"

    // ── parsing the routes' JSON ────────────────────────────────────────────

    fun projects(json: Any?): List<ProjectRow> {
        val scopes = (json as? Map<*, *>)?.get("scopes") as? List<*> ?: return emptyList()
        return scopes.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            ProjectRow(
                name = m["name"]?.toString() ?: return@mapNotNull null,
                kind = m["kind"]?.toString().orEmpty(),
                docs = (m["docs"] as? Number)?.toInt() ?: 0,
                path = m["path"]?.toString().orEmpty(),
            )
        }
    }

    fun docs(json: Any?): List<DocRow> {
        val docs = (json as? Map<*, *>)?.get("docs") as? List<*> ?: return emptyList()
        return docs.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            DocRow(
                id = m["id"]?.toString() ?: return@mapNotNull null,
                cid = m["cid"]?.toString().orEmpty(),
                rev = m["rev"]?.toString().orEmpty(),
                seq = (m["seq"] as? Number)?.toLong() ?: -1L,
                length = (m["length"] as? Number)?.toLong() ?: 0L,
                contentType = m["contentType"]?.toString().orEmpty(),
            )
        }
    }

    fun document(json: Any?): DocView? {
        val m = json as? Map<*, *> ?: return null
        val id = m["id"]?.toString() ?: return null
        return DocView(
            project = m["project"]?.toString().orEmpty(),
            id = id,
            cid = m["cid"]?.toString().orEmpty(),
            rev = m["rev"]?.toString().orEmpty(),
            seq = (m["seq"] as? Number)?.toLong() ?: -1L,
            contentType = m["contentType"]?.toString().orEmpty(),
            text = m["text"]?.toString().orEmpty(),
            extract = m["extract"]?.toString(),
        )
    }

    // ── curation: /api/documents and /api/documents/curate ─────────────────

    fun curationAvailable(json: Any?): Boolean = (json as? Map<*, *>)?.get("available") as? Boolean ?: false

    fun curationDestination(json: Any?): CurationDestination? {
        val m = (json as? Map<*, *>)?.get("destination") as? Map<*, *> ?: return null
        val model = m["model"]?.toString() ?: return null
        return CurationDestination(model, m["provider"]?.toString().orEmpty(), m["url"]?.toString().orEmpty())
    }

    fun curationList(json: Any?): List<CurationRecordRow> {
        val records = (json as? Map<*, *>)?.get("records") as? List<*> ?: return emptyList()
        return records.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            CurationRecordRow(
                receiptCid = m["receiptCid"]?.toString() ?: return@mapNotNull null,
                name = m["name"]?.toString().orEmpty(),
            )
        }
    }

    /** [json] is a full `/api/documents?cid=` (or curate) response: `record` plus top-level `nlpAxioms`. */
    fun curationResult(json: Any?): CurationResult? {
        val m = json as? Map<*, *> ?: return null
        val receiptCid = m["receiptCid"]?.toString() ?: return null
        val record = m["record"] as? Map<*, *> ?: return null
        val source = record["source"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val submittedCids = (record["submitted"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
        val duplicateCids = (record["duplicates"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
        val proposals = (record["proposals"] as? List<*> ?: emptyList<Any?>()).mapNotNull { row ->
            val p = row as? Map<*, *> ?: return@mapNotNull null
            CurationProposal(
                subject = p["subject"]?.toString(), predicate = p["predicate"]?.toString(), obj = p["object"]?.toString(),
                quote = p["quote"]?.toString(),
                reasons = (p["reasons"] as? List<*>)?.map { it.toString() }.orEmpty(),
                submitted = p["receiptCid"]?.toString()?.let { it in submittedCids } ?: false,
                duplicate = p["receiptCid"]?.toString()?.let { it in duplicateCids } ?: false,
            )
        }
        val axioms = (m["nlpAxioms"] as? List<*> ?: emptyList<Any?>()).mapNotNull { row ->
            val a = row as? Map<*, *> ?: return@mapNotNull null
            CurationAxiom(
                antecedent = a["antecedent"]?.toString(), predicate = a["predicate"]?.toString(),
                consequent = a["consequent"]?.toString(), quote = a["quote"]?.toString(), label = a["label"]?.toString(),
            )
        }
        return CurationResult(
            receiptCid = receiptCid, name = source["name"]?.toString().orEmpty(), text = source["text"]?.toString().orEmpty(),
            proposals = proposals, reasons = (record["reasons"] as? List<*>)?.map { it.toString() }.orEmpty(),
            axioms = axioms,
        )
    }

    /** Both the list read (`error` at the top) and the curate write (`error` on a 503) land the same way. */
    fun curationError(json: Any?): String? = (json as? Map<*, *>)?.get("error")?.toString()

    // ── curation: the form, the saved list, and a result ───────────────────

    fun curationHtml(
        available: Boolean,
        destination: CurationDestination?,
        records: List<CurationRecordRow>,
        pendingName: String,
        pendingText: String,
        formError: String?,
        listError: String?,
        busy: Boolean,
        result: CurationResult?,
        resultError: String?,
    ): String = buildString {
        append("<div class=\"ds-page ds-page-wide\">")
        append("<h1 class=\"ds-title\">Curate</h1>")
        append("<p class=\"ds-sub\">Paste text in and the curator reads out statements and conditional readings, ")
            .append("each tied to the quote it came from.</p>")
        if (!available) append("<p class=\"ds-curate-warn\">The curation engine is not available right now.</p>")
        if (listError != null) append("<p class=\"ds-curate-error\">").append(esc(listError)).append("</p>")
        append("<form class=\"ds-curate-form\" data-curate-form=\"1\">")
        append("<label class=\"ds-curate-label\" for=\"ds-curate-name\">Document name (optional)</label>")
        append("<input class=\"ds-curate-name\" id=\"ds-curate-name\" type=\"text\" value=\"")
            .append(esc(pendingName)).append("\" placeholder=\"Untitled\">")
        append("<label class=\"ds-curate-label\" for=\"ds-curate-text\">Text</label>")
        append("<textarea class=\"ds-curate-text\" id=\"ds-curate-text\" rows=\"10\" placeholder=\"Paste or write the text to curate…\">")
            .append(esc(pendingText)).append("</textarea>")
        append("<div class=\"ds-curate-dest\">")
        if (destination != null) append("Destination model: <strong>").append(esc(destination.model)).append("</strong>")
            .append(" <span class=\"ds-dim\">(").append(esc(destination.provider)).append(")</span>")
        else append("<span class=\"ds-dim\">No curation model is configured.</span>")
        append("</div>")
        if (formError != null) append("<p class=\"ds-curate-error\">").append(esc(formError)).append("</p>")
        append("<button type=\"submit\" class=\"ds-curate-submit\" data-curate-submit=\"1\"")
        if (busy || destination == null || !available) append(" disabled")
        append(">").append(if (busy) "Curating…" else "Curate").append("</button>")
        append("</form>")
        if (records.isNotEmpty()) {
            append("<h2 class=\"ds-curate-h2\">Saved results</h2><ul class=\"ds-curate-list\">")
            for (r in records) {
                val open = r.receiptCid == result?.receiptCid
                append("<li><a class=\"ds-curate-item").append(if (open) " ds-selected" else "").append('"')
                append(" href=\"#").append(CURATION_HASH).append('/').append(esc(r.receiptCid))
                    .append("\" data-curate-open=\"").append(esc(r.receiptCid)).append("\">")
                append(esc(r.name.ifBlank { "Untitled" })).append(" <span class=\"ds-dim\">")
                    .append(esc(shortCid(r.receiptCid))).append("</span></a></li>")
            }
            append("</ul>")
        }
        if (resultError != null) append("<p class=\"ds-curate-error\">").append(esc(resultError)).append("</p>")
        if (result != null) append(curationResultHtml(result))
        append("</div>")
    }

    fun curationResultHtml(result: CurationResult): String = buildString {
        append("<section class=\"ds-curate-result\">")
        append("<h2 class=\"ds-curate-h2\">").append(esc(result.name.ifBlank { "Untitled" })).append("</h2>")
        append("<p class=\"ds-sub\">saved result · <code class=\"ds-cid\">").append(esc(shortCid(result.receiptCid))).append("</code></p>")
        append("<details class=\"ds-curate-source\"><summary>Source text</summary><pre>")
            .append(esc(result.text)).append("</pre></details>")
        val statements = result.proposals.filter { it.subject != null && it.predicate != null && it.obj != null }
        if (statements.isNotEmpty()) {
            append("<h3 class=\"ds-curate-h3\">Proposed statements</h3><ul class=\"ds-curate-proposals\">")
            for (p in statements) {
                append("<li class=\"ds-curate-proposal").append(if (p.submitted) " ds-curate-admitted" else " ds-curate-review").append("\">")
                append("<div class=\"ds-curate-triple\">").append(esc(p.subject!!)).append(" <span class=\"ds-dim\">")
                    .append(esc(p.predicate!!)).append("</span> ").append(esc(p.obj!!)).append("</div>")
                if (!p.quote.isNullOrBlank()) append("<blockquote class=\"ds-curate-quote\">“").append(esc(p.quote)).append("”</blockquote>")
                append("<div class=\"ds-curate-status\">")
                    .append(when {
                        p.submitted -> "Submitted as source attribution"
                        p.duplicate -> "Previously recorded source attribution"
                        else -> "Needs review"
                    }).append("</div>")
                if (p.reasons.isNotEmpty()) append("<div class=\"ds-curate-reasons\">").append(esc(p.reasons.joinToString("; "))).append("</div>")
                append("</li>")
            }
            append("</ul>")
        }
        if (result.axioms.isNotEmpty()) {
            append("<h3 class=\"ds-curate-h3\">Conditional / causal readings</h3><ul class=\"ds-curate-axioms\">")
            for (a in result.axioms) {
                append("<li class=\"ds-curate-axiom\">")
                append("<div class=\"ds-curate-triple\">")
                if (a.predicate == "implies") append("If ").append(esc(a.antecedent.orEmpty()))
                    .append(" then ").append(esc(a.consequent.orEmpty()))
                else append(esc(a.antecedent.orEmpty())).append(" <span class=\"ds-dim\">")
                    .append(esc(a.predicate.orEmpty())).append("</span> ").append(esc(a.consequent.orEmpty()))
                append("</div>")
                if (!a.quote.isNullOrBlank()) append("<blockquote class=\"ds-curate-quote\">“").append(esc(a.quote)).append("”</blockquote>")
                append("<div class=\"ds-curate-status\">").append(esc(a.label ?: "admission candidate")).append("</div>")
                append("</li>")
            }
            append("</ul>")
        }
        val unparsed = result.proposals.filter { it.subject == null || it.predicate == null || it.obj == null }
        for (p in unparsed) {
            append("<p class=\"ds-curate-error\">Needs review: ")
                .append(esc(p.reasons.joinToString("; ").ifBlank { "The model response could not be read as a statement." }))
                .append("</p>")
        }
        if (result.proposals.isEmpty() && result.axioms.isEmpty())
            append("<p class=\"ds-empty\">No statements were proposed for this text.</p>")
        if (result.reasons.isNotEmpty()) {
            append("<h3 class=\"ds-curate-h3\">Notes on this document</h3><ul class=\"ds-curate-notes\">")
            for (r in result.reasons) append("<li>").append(esc(r)).append("</li>")
            append("</ul>")
        }
        append("</section>")
    }

    // ── the shell ───────────────────────────────────────────────────────────

    /** Sidebar and a main column of breadcrumb over content; the page fills the three by id. */
    fun layout(): String =
        "<aside class=\"ds-side\" id=\"$SIDE_ID\" aria-label=\"Workspace\"></aside>" +
        "<main class=\"ds-main\">" +
            "<div class=\"ds-crumb\" id=\"$CRUMB_ID\"></div>" +
            "<div class=\"ds-content\" id=\"$CONTENT_ID\"></div>" +
        "</main>"

    // ── the sidebar ─────────────────────────────────────────────────────────

    /**
     * One tree: every mounted project, with the selected project's pages nested under it.
     * Only the open project carries its children, which is what keeps a workspace of many
     * projects readable and is why [docs] is fetched per project rather than all at once.
     */
    fun sidebarHtml(
        projects: List<ProjectRow>,
        selected: String?,
        docs: List<DocRow> = emptyList(),
        selectedDoc: String? = null,
        curationActive: Boolean = false,
    ): String = buildString {
        append("<div class=\"ds-brand\">Forge</div>")
        append("<a class=\"ds-curate-link").append(if (curationActive) " ds-selected" else "").append('"')
        if (curationActive) append(" aria-current=\"true\"")
        append(" href=\"#").append(CURATION_HASH).append("\" data-curate=\"1\">Curate</a>")
        append("<div class=\"ds-side-label\">Workspaces</div>")
        if (projects.isEmpty()) {
            append("<p class=\"ds-empty\">No project is mounted. Drop a folder on the harness, or POST its path to /api/projects.</p>")
            return@buildString
        }
        append("<ul class=\"ds-tree\">")
        for (p in projects) {
            val open = p.name == selected
            append("<li><a class=\"ds-proj").append(if (open) " ds-selected" else "").append('"')
            if (open) append(" aria-current=\"true\"")
            append(" href=\"#").append(esc(p.name)).append("\" data-project=\"").append(esc(p.name)).append("\">")
            append("<span class=\"ds-caret\">").append(if (open) "▾" else "▸").append("</span>")
            append("<span class=\"ds-name\">").append(esc(p.name)).append("</span>")
            append("<span class=\"ds-count\">").append(p.docs).append("</span></a>")
            if (open) append(pagesHtml(p.name, docs, selectedDoc))
            append("</li>")
        }
        append("</ul>")
    }

    /** The open project's pages. A page is a document; the icon says which kind at a glance. */
    fun pagesHtml(project: String, rows: List<DocRow>, selectedId: String?): String = buildString {
        if (rows.isEmpty()) { append("<p class=\"ds-empty ds-empty-pages\">No pages yet.</p>"); return@buildString }
        append("<ul class=\"ds-pages\">")
        for (d in rows) {
            append("<li><a class=\"ds-page").append(if (d.id == selectedId) " ds-selected" else "").append('"')
            if (d.id == selectedId) append(" aria-current=\"true\"")
            append(" href=\"#").append(esc(project)).append('/').append(esc(d.id))
            append("\" data-doc=\"").append(esc(d.id)).append("\" title=\"").append(esc(d.cid)).append("\">")
            append("<span class=\"ds-ico\">").append(if (isMarkdownName(d.id)) "▤" else "▢").append("</span>")
            append("<span class=\"ds-name\">").append(esc(d.id)).append("</span></a></li>")
        }
        append("</ul>")
    }

    // ── the breadcrumb and the view switch ──────────────────────────────────

    fun curationCrumbHtml(result: CurationResult?): String = buildString {
        append("<div class=\"ds-crumbs\"><span class=\"ds-here\">Curate</span>")
        if (result != null) append("<span class=\"ds-sep\">/</span><span class=\"ds-here\">")
            .append(esc(result.name.ifBlank { "Untitled" })).append("</span>")
        append("</div>")
    }

    fun crumbHtml(project: String?, docId: String?, view: SurfaceView): String = buildString {
        append("<div class=\"ds-crumbs\">")
        if (project == null) append("<span class=\"ds-dim\">Pick a workspace</span>")
        else {
            append("<a href=\"#").append(esc(project)).append("\" data-project=\"").append(esc(project)).append("\">")
                .append(esc(project)).append("</a>")
            if (docId != null && view == SurfaceView.PAGE)
                append("<span class=\"ds-sep\">/</span><span class=\"ds-here\">").append(esc(docId)).append("</span>")
        }
        append("</div><div class=\"ds-views\">")
        for (v in SurfaceView.entries) {
            val on = v == view
            append("<button type=\"button\" class=\"ds-view").append(if (on) " ds-on" else "").append('"')
            if (on) append(" aria-pressed=\"true\"")
            append(" data-view=\"").append(v.name.lowercase()).append("\">")
                .append(if (v == SurfaceView.PAGE) "Page" else "Table").append("</button>")
        }
        append("</div>")
    }

    // ── the table view: the project read as its own database ────────────────

    /** Sorting is a projection, never an edit: the rows come back in the order asked for. */
    fun sortDocs(rows: List<DocRow>, column: DocColumn, ascending: Boolean): List<DocRow> {
        val ordered = when (column) {
            DocColumn.NAME -> rows.sortedBy { it.id.lowercase() }
            DocColumn.TYPE -> rows.sortedWith(compareBy({ it.contentType }, { it.id.lowercase() }))
            DocColumn.SIZE -> rows.sortedBy { it.length }
            DocColumn.SEQ -> rows.sortedBy { it.seq }
        }
        return if (ascending) ordered else ordered.reversed()
    }

    fun tableHtml(
        project: String?,
        rows: List<DocRow>,
        column: DocColumn = DocColumn.NAME,
        ascending: Boolean = true,
        selectedId: String? = null,
    ): String = buildString {
        if (project == null) { append("<p class=\"ds-empty\">Pick a workspace.</p>"); return@buildString }
        // A database reads wider than prose: the table view gets the room, the page keeps its column.
        append("<div class=\"ds-page ds-page-wide\"><h1 class=\"ds-title\">").append(esc(project)).append("</h1>")
        append("<p class=\"ds-sub\">").append(rows.size).append(if (rows.size == 1) " page" else " pages")
        append(" · <a href=\"").append(DIGEST_HREF).append("\">Digest this workspace in the harness</a></p>")
        if (rows.isEmpty()) { append("<p class=\"ds-empty\">No pages yet.</p></div>"); return@buildString }
        append("<table class=\"ds-table\"><thead><tr>")
        for (c in DocColumn.entries) {
            val on = c == column
            append("<th class=\"ds-th").append(if (on) if (ascending) " ds-asc" else " ds-desc" else "").append('"')
            append(" data-sort=\"").append(c.key).append("\">").append(c.label)
            if (on) append("<span class=\"ds-arrow\">").append(if (ascending) "↑" else "↓").append("</span>")
            append("</th>")
        }
        append("<th class=\"ds-th ds-th-cid\">Cid</th></tr></thead><tbody>")
        for (d in sortDocs(rows, column, ascending)) {
            append("<tr class=\"ds-row").append(if (d.id == selectedId) " ds-selected" else "").append('"')
            append(" data-doc=\"").append(esc(d.id)).append("\">")
            append("<td class=\"ds-cell-name\"><span class=\"ds-ico\">")
                .append(if (isMarkdownName(d.id)) "▤" else "▢").append("</span>")
                .append("<span class=\"ds-name\">").append(esc(d.id)).append("</span></td>")
            append("<td class=\"ds-dim\">").append(esc(d.contentType)).append("</td>")
            append("<td class=\"ds-num\">").append(bytes(d.length)).append("</td>")
            append("<td class=\"ds-num\">").append(d.seq).append("</td>")
            append("<td class=\"ds-cid\" title=\"").append(esc(d.cid)).append("\">").append(esc(shortCid(d.cid))).append("</td>")
            append("</tr>")
        }
        append("</tbody></table></div>")
    }

    // ── the page ────────────────────────────────────────────────────────────

    /**
     * [runs] is what the read route said about this page's `lcnc-run` blocks, keyed by the ordinal
     * [RunBlock.scan] hands out. An ordinal with no state renders its frame unread — the reader
     * sees the block exists before the daemon has answered, and never a lamp nobody was told.
     */
    fun documentHtml(view: DocView?, runs: Map<Int, RunBlock.State> = emptyMap()): String {
        if (view == null) return "<p class=\"ds-empty\">Pick a page.</p>"
        val sb = StringBuilder()
        sb.append("<div class=\"ds-page\">")
        sb.append("<h1 class=\"ds-title\">").append(esc(view.id)).append("</h1>")
        sb.append("<p class=\"ds-sub\">").append(esc(view.contentType.ifBlank { "document" }))
            .append(" · sequence ").append(view.seq)
            .append(" · <code class=\"ds-cid\" title=\"").append(esc(view.cid)).append("\">")
            .append(esc(shortCid(view.cid))).append("</code>")
            .append(" · rev <code title=\"").append(esc(view.rev)).append("\">")
            .append(esc(shortRev(view.rev))).append("</code></p>")
        sb.append("<article class=\"ds-body\">")
        if (isMarkdown(view)) {
            var ordinal = 0
            sb.append(DocumentMarkdown.render(view.text) { info, body ->
                if (info != RunBlock.INFO) null else RunBlock.blockHtml(RunBlock.parse(ordinal++, body), runs)
            })
        } else sb.append("<pre>").append(DocumentMarkdown.escape(view.text)).append("</pre>")
        sb.append("</article>")
        view.extract?.let { twin ->
            sb.append("<section class=\"ds-extract\"><h2>Extract (the miner's twin)</h2>").append(DocumentMarkdown.render(twin)).append("</section>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    fun isMarkdown(view: DocView): Boolean =
        view.contentType.startsWith("text/markdown") || isMarkdownName(view.id)

    fun isMarkdownName(id: String): Boolean =
        id.endsWith(".md", ignoreCase = true) || id.endsWith(".markdown", ignoreCase = true)

    /** Bytes as a reader says them, so a table column stays one glance wide. */
    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> ((n * 10 / 1024).toDouble() / 10).toString() + " KB"
        else -> ((n * 10 / (1024 * 1024)).toDouble() / 10).toString() + " MB"
    }

    /** A revision is a generation and a hash; the generation is the part a reader uses. */
    fun shortRev(rev: String): String {
        val dash = rev.indexOf('-')
        return if (dash <= 0) shortCid(rev) else rev.take(dash + 1) + shortCid(rev.substring(dash + 1))
    }

    fun shortCid(cid: String): String {
        val hex = cid.substringAfter(':', cid)
        return if (hex.length > 12) cid.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" } + hex.take(12) else cid
    }

    fun esc(s: String): String = DocumentMarkdown.escape(s)
}
