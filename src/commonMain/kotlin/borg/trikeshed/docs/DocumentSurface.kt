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
    ): String = buildString {
        append("<div class=\"ds-brand\">Forge</div>")
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
