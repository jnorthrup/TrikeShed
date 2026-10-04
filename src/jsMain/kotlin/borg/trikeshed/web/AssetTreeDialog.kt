package borg.trikeshed.web

import borg.trikeshed.lcnc.AssetTree
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Asset removal from a tree: every uploaded project db as a folder tree (twins folded under their document);
 * ✕ on a document takes it and its twins out, on a folder everything under it, on a db the whole db.
 * What was curated from a removed document leaves its constellation with it.
 */
object AssetTreeDialog {
    private val open = HashSet<String>()

    fun toggle() {
        document.getElementById("assetTree")?.let { it.asDynamic().remove(); return }
        val d: dynamic = document.createElement("div"); d.id = "assetTree"
        d.style.cssText = "position:fixed;top:52px;right:12px;width:440px;max-height:76vh;overflow:auto;background:var(--panel,#101620);" +
            "border:1px solid var(--line,#263348);border-radius:8px;padding:10px 12px;z-index:60;font-size:12px;color:var(--ink,#d8e0ea)"
        on(d, "pointerdown", { e -> e.stopPropagation() })
        on(d, "wheel", { e -> e.stopPropagation() })
        document.body!!.appendChild(d)
        MainScope().launch { render(d) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun row(depth: Int, key: String, label: String, note: String, toggle: Boolean, del: String): String =
        "<div style=\"display:flex;gap:6px;align-items:baseline;padding:2px 0 2px ${depth * 14}px;border-bottom:1px solid var(--line,#263348)\">" +
            (if (toggle) "<span data-open=\"${esc(key)}\" style=\"cursor:pointer;width:10px;color:var(--dim,#7d8ba0)\">${if (key in open) "▾" else "▸"}</span>"
             else "<span style=\"width:10px\"></span>") +
            "<span style=\"flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap\" title=\"${esc(label)}\">${esc(label)}</span>" +
            "<span style=\"color:var(--dim,#7d8ba0);font-size:10px\">${esc(note)}</span>" +
            "<s data-del=\"${esc(del)}\" title=\"remove\" style=\"cursor:pointer;color:var(--err,#ff6b6b);text-decoration:none\">✕</s></div>"

    private fun walk(out: StringBuilder, db: String, n: AssetTree.Node, depth: Int) {
        for (f in n.folders.values) {
            val key = "$db/${f.path}/"
            out.append(row(depth, key, f.name + "/", "${f.count}", true, "doc\t$db\t${f.path}"))
            if (key in open) walk(out, db, f, depth + 1)
        }
        for (doc in n.documents.values)
            out.append(row(depth, "$db/${doc.path}", doc.name, if (doc.twins.isEmpty()) "" else "+${doc.twins.size} twins", false, "doc\t$db\t${doc.path}"))
    }

    private suspend fun render(d: dynamic) {
        val scopes = arr(fetchJson("/api/projects").scopes).filter { str(it.path) == "@upload" }.map { str(it.name) }
        val out = StringBuilder()
        out.append("<div style=\"display:flex;align-items:baseline;margin-bottom:6px\"><b style=\"letter-spacing:.08em;flex:1\">ASSETS</b>" +
            "<button data-k=\"close\">close</button></div>")
        if (scopes.isEmpty()) out.append("<i style=\"color:var(--dim,#7d8ba0)\">no uploaded project dbs</i>")
        for (db in scopes) {
            val key = "$db/"
            val tree = if (key in open) AssetTree(arr(fetchJson(borg.trikeshed.couch.FutonPaths.db(db) + "/_all_docs").rows).map { str(it.id) }) else null
            out.append(row(0, key, "/$db", tree?.let { "${it.root.count} docs" } ?: "", true, "db\t$db"))
            if (tree != null) walk(out, db, tree.root, 1)
        }
        d.innerHTML = out.toString()
        d.querySelector("[data-k=\"close\"]").onclick = { d.remove() }
        for (el in arr(d.querySelectorAll("[data-open]"))) on(el, "click", {
            val k = str(el.dataset.open); if (!open.remove(k)) open.add(k)
            MainScope().launch { render(d) }
        })
        for (el in arr(d.querySelectorAll("[data-del]"))) on(el, "click", {
            val (kind, db, path) = (str(el.dataset.del).split('\t') + "").let { Triple(it[0], it[1], it[2]) }
            val what = if (kind == "db") "project db /$db (its ledger entry and files; CAS blobs stay)" else "/$db/$path with its twins and what was curated from it"
            if (window.confirm("Remove $what?")) MainScope().launch {
                val r = if (kind == "db") fetchJson("/api/projects/" + encodeURIComponent(db), objOf { it.method = "DELETE" })
                        else fetchJson("/_project/" + encodeURIComponent(db) + "/doc?path=" + encodeURIComponent(path), objOf { it.method = "DELETE" })
                if (str(r.verdict) != "removed" && str(r.verdict) != "unmounted") window.alert("refused: " + str(r.detail ?: r.error ?: "?"))
                render(d)
            }
        })
    }
}
