package borg.trikeshed.docs

/**
 * THE DOCUMENT SURFACE: the first page whose logic lives in commonMain and reaches
 * the browser through the Kotlin/JS bundle (the owner's 2026-09-06 ruling: emulate
 * GWT, hand-written JS only for dire dependency access).
 *
 * The page is three panes over the daemon's project routes: the mounted projects,
 * one project's documents (the same listing `project.docs` gives a workflow), and
 * one document rendered. Every function here is pure: JSON in (as the parser's
 * maps and lists), HTML strings out. The jsMain page only fetches and mounts.
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

object DocumentSurface {
    const val ROOT_ID = "document-surface"
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

    // ── rendering ───────────────────────────────────────────────────────────

    /** The page's skeleton: three panes the jsMain page fills and re-fills. */
    fun layout(): String =
        "<nav class=\"ds-pane\" id=\"ds-projects\" aria-label=\"Projects\"></nav>" +
        "<nav class=\"ds-pane\" id=\"ds-docs\" aria-label=\"Documents\"></nav>" +
        "<main class=\"ds-pane ds-doc\" id=\"ds-doc\" aria-label=\"Document\"></main>"

    fun projectsHtml(rows: List<ProjectRow>, selected: String?): String = buildString {
        append("<h2>Projects</h2>")
        if (rows.isEmpty()) { append("<p class=\"ds-empty\">No project is mounted. Drop a folder on the harness, or POST its path to /api/projects.</p>"); return@buildString }
        append("<ul class=\"ds-list\">")
        for (r in rows) {
            append("<li><a href=\"#").append(esc(r.name)).append("\" data-project=\"").append(esc(r.name)).append('"')
            if (r.name == selected) append(" class=\"ds-selected\" aria-current=\"true\"")
            append("><b>").append(esc(r.name)).append("</b> <span class=\"ds-dim\">").append(esc(r.kind)).append(" · ").append(r.docs).append(" docs</span></a></li>")
        }
        append("</ul>")
    }

    fun docsHtml(project: String?, rows: List<DocRow>, selectedId: String?): String = buildString {
        if (project == null) { append("<h2>Documents</h2><p class=\"ds-empty\">Pick a project.</p>"); return@buildString }
        append("<h2>").append(esc(project)).append("</h2>")
        append("<p class=\"ds-actions\"><a href=\"").append(DIGEST_HREF).append("\">Digest this project in the harness</a></p>")
        if (rows.isEmpty()) { append("<p class=\"ds-empty\">No documents.</p>"); return@buildString }
        append("<ul class=\"ds-list\">")
        for (d in rows) {
            append("<li><a href=\"#").append(esc(project)).append('/').append(esc(d.id)).append("\" data-doc=\"").append(esc(d.id)).append('"')
            if (d.id == selectedId) append(" class=\"ds-selected\" aria-current=\"true\"")
            append(" title=\"").append(esc(d.cid)).append("\"><b>").append(esc(d.id)).append("</b> <span class=\"ds-dim\">")
            append(esc(d.contentType)).append(" · ").append(d.length).append(" B · seq ").append(d.seq).append("</span></a></li>")
        }
        append("</ul>")
    }

    fun documentHtml(view: DocView?): String {
        if (view == null) return "<p class=\"ds-empty\">Pick a document.</p>"
        val sb = StringBuilder()
        sb.append("<header class=\"ds-head\"><h1>").append(esc(view.id)).append("</h1>")
        sb.append("<dl class=\"ds-meta\">")
        sb.append("<dt>project</dt><dd>").append(esc(view.project)).append("</dd>")
        sb.append("<dt>cid</dt><dd><code title=\"").append(esc(view.cid)).append("\">").append(esc(shortCid(view.cid))).append("</code></dd>")
        sb.append("<dt>rev</dt><dd><code>").append(esc(view.rev)).append("</code></dd>")
        sb.append("<dt>seq</dt><dd>").append(view.seq).append("</dd>")
        sb.append("<dt>type</dt><dd>").append(esc(view.contentType)).append("</dd>")
        sb.append("</dl></header>")
        sb.append("<article class=\"ds-body\">")
        if (isMarkdown(view)) sb.append(DocumentMarkdown.render(view.text))
        else sb.append("<pre>").append(DocumentMarkdown.escape(view.text)).append("</pre>")
        sb.append("</article>")
        view.extract?.let { twin ->
            sb.append("<section class=\"ds-extract\"><h2>Extract (the miner's twin)</h2>").append(DocumentMarkdown.render(twin)).append("</section>")
        }
        return sb.toString()
    }

    fun isMarkdown(view: DocView): Boolean =
        view.contentType.startsWith("text/markdown") || view.id.endsWith(".md", ignoreCase = true) || view.id.endsWith(".markdown", ignoreCase = true)

    fun shortCid(cid: String): String {
        val hex = cid.substringAfter(':', cid)
        return if (hex.length > 12) cid.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" } + hex.take(12) else cid
    }

    fun esc(s: String): String = DocumentMarkdown.escape(s)
}
