package borg.trikeshed.web.pages

import borg.trikeshed.couch.FutonPaths
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTableElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event

/** Futon: the CouchDB-1.x style document browser over every database `/_all_dbs` names. */
object FutonPage {
    /** The selected database; every request below addresses it. */
    private var DB = "trikeshed"
    private const val LIMIT = 100
    private var pageStack: MutableList<String?> = mutableListOf(null)
    private var currentRev: String? = null
    private var nextKey: String? = null

    private fun enc(id: String): String = id.split("/").joinToString("/") { js("encodeURIComponent")(it) as String }
    private fun encodeUri(s: String): String = js("encodeURIComponent")(s) as String

    private fun status(msg: String, cls: String? = null) {
        val s = byId("status")
        s.textContent = msg
        s.className = cls ?: ""
    }

    private fun input(id: String) = byId(id) as HTMLInputElement
    private fun body() = byId("docbody") as HTMLTextAreaElement

    private suspend fun refreshInfo() {
        try {
            val i: dynamic = fetchResponse(FutonPaths.db(DB)).jsonValue()
            byId("info").textContent = (i.doc_count.toLocaleString() as String) + " docs · seq " + str(i.update_seq)
        } catch (_: Throwable) {
        }
    }

    /** The folder being browsed: an id prefix ending in `/`, walked one path segment at a time. */
    private var folder = ""

    private suspend fun loadPage(startkey: String?) {
        val p = input("prefix").value
        // The typed filter narrows within the folder; the folder is the hierarchy (db → path segments → docs).
        val within = folder + p
        var url = FutonPaths.db(DB) + "/_all_docs?limit=" + (LIMIT + 1) + "&delimiter=" + encodeUri("/")
        if (within.isNotEmpty()) url += "&prefix=" + encodeUri(within)
        if (!startkey.isNullOrEmpty()) url += "&startkey=" + encodeUri("\"" + startkey + "\"")
        val d: dynamic = fetchResponse(url).jsonValue()
        val rows = if (truthy(d.rows)) jsArray(d.rows) else emptyArray()
        val folders = if (startkey == null && truthy(d.prefixes)) jsArray(d.prefixes).map { str(it) } else emptyList()
        crumbs()
        val t = byId("docs") as HTMLTableElement
        t.innerHTML = "<tr><th style=\"width:66%\">_id</th><th>_rev</th></tr>"
        if (folder.isNotEmpty() && startkey == null) {
            val up = document.createElement("tr") as HTMLElement
            up.innerHTML = "<td>\u2191 ..</td><td class=\"rev\"></td>"
            up.onclick = { enter(folder.removeSuffix("/").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) }
            t.appendChild(up)
        }
        for (f in folders) {
            val tr = document.createElement("tr") as HTMLElement
            tr.innerHTML = "<td>\uD83D\uDCC1 " + f.removePrefix(folder).esc() + "</td><td class=\"rev\">folder</td>"
            tr.onclick = { enter(f) }
            t.appendChild(tr)
        }
        for (r in rows.take(LIMIT)) {
            val tr = document.createElement("tr") as HTMLElement
            val rev = if (truthy(r.value) && truthy(r.value.rev)) str(r.value.rev) else ""
            tr.innerHTML = "<td>" + str(r.id).removePrefix(folder).esc() + "</td><td class=\"rev\">" + rev.take(18) + "…</td>"
            val id = str(r.id)
            tr.onclick = { launchPage { loadDoc(id) } }
            t.appendChild(tr)
        }
        (byId("nextB") as HTMLButtonElement).disabled = rows.size <= LIMIT
        (byId("prevB") as HTMLButtonElement).disabled = pageStack.size <= 1
        byId("pageinfo").textContent = folders.size.toString() + " folders · " + str(d.documents ?: rows.size) + " documents here"
        nextKey = if (rows.size > LIMIT) str(rows[LIMIT].id) else null
        window.asDynamic()._nextKey = nextKey
        launchPage { refreshInfo() }
    }

    /** Descend into (or climb to) [prefix]: the listing restarts at that folder's first page. */
    private fun enter(prefix: String) {
        folder = prefix
        input("prefix").value = ""
        firstPage()
    }

    /** The path as links: the db, then each folder segment; a click climbs to it. */
    private fun crumbs() {
        val c = byId("crumbs")
        c.innerHTML = ""
        fun link(label: String, to: String) {
            val a = document.createElement("a") as HTMLElement
            a.textContent = label
            a.setAttribute("href", "#")
            a.onclick = { e: Event -> e.preventDefault(); enter(to) }
            c.appendChild(a)
        }
        link(DB, "")
        var acc = ""
        for (seg in folder.split('/').filter { it.isNotEmpty() }) {
            acc += "$seg/"
            c.appendChild(document.createTextNode(" / "))
            link(seg, acc)
        }
    }

    private fun firstPage() {
        pageStack = mutableListOf(null)
        launchPage { loadPage(null) }
    }

    private fun nextPage() {
        val k = nextKey ?: return
        pageStack.add(k)
        launchPage { loadPage(k) }
    }

    private fun prevPage() {
        if (pageStack.size <= 1) return
        pageStack.removeAt(pageStack.size - 1)
        launchPage { loadPage(pageStack.last()) }
    }

    private suspend fun loadDoc(id: String) {
        input("docid").value = id
        byId("att").innerHTML = ""
        currentRev = null
        try {
            val r = fetchResponse(FutonPaths.db(DB) + "/" + enc(id))
            if (!r.ok) { status("load failed: " + r.status, "err"); return }
            val d: dynamic = r.jsonValue()
            currentRev = if (d._rev == null) null else str(d._rev)
            val b: dynamic = obj()
            val keys = js("Object.keys")(d).unsafeCast<Array<String>>()
            for (k in keys) if (k != "_attachments") b[k] = d[k]
            body().value = JSON.stringify(b, null, 2)
            if (truthy(d._attachments) && truthy(d._attachments.content)) {
                val a: dynamic = d._attachments.content
                val length: dynamic = if (truthy(a.length)) a.length else 0
                byId("att").innerHTML = "attachment: <a href=\"" + FutonPaths.db(DB) + "/" + enc(id) + "/content\" target=\"_blank\">content</a> · " +
                    (if (truthy(a.content_type)) str(a.content_type) else "") + " · " + (length.toLocaleString() as String) +
                    " bytes · <span style=\"font-family:monospace\">" + (if (truthy(a.digest)) str(a.digest) else "").take(22) + "…</span>"
            }
            status("loaded " + (if (truthy(d._rev)) str(d._rev) else "").take(28) + "…", "ok")
        } catch (e: Throwable) {
            status("load failed: " + errorString(e), "err")
        }
    }

    private fun errorString(e: Throwable): String = str(e.asDynamic())

    private suspend fun saveDoc() {
        val id = input("docid").value.trim()
        if (id.isEmpty()) { status("an _id is required", "err"); return }
        val b: dynamic
        try {
            b = JSON.parse<dynamic>(body().value.ifEmpty { "{}" })
        } catch (e: Throwable) {
            status("not JSON: " + e.message, "err"); return
        }
        js("Reflect").deleteProperty(b, "_id")
        if (currentRev != null && !truthy(b._rev)) b._rev = currentRev
        try {
            val r = fetchResponse(FutonPaths.db(DB) + "/" + enc(id), requestInit("PUT", jsonHeaders(), JSON.stringify(b)))
            val d: dynamic = r.jsonValue()
            if (truthy(d.ok)) {
                currentRev = str(d.rev)
                status("saved · " + str(d.rev).take(28) + "…", "ok")
                launchPage { loadPage(pageStack.last()) }
            } else status((if (truthy(d.error)) str(d.error) else "error") + ": " + (if (truthy(d.reason)) str(d.reason) else ""), "err")
        } catch (e: Throwable) {
            status("save failed: " + errorString(e), "err")
        }
    }

    private suspend fun deleteDoc() {
        val id = input("docid").value.trim()
        val rev = currentRev
        if (id.isEmpty() || rev == null) { status("load the doc first (need its _rev)", "err"); return }
        if (!window.confirm("delete $id ?")) return
        try {
            val r = fetchResponse(FutonPaths.db(DB) + "/" + enc(id) + "?rev=" + encodeUri(rev), requestInit("DELETE"))
            val d: dynamic = r.jsonValue()
            if (truthy(d.ok)) {
                status("deleted", "ok")
                body().value = ""
                currentRev = null
                launchPage { loadPage(pageStack.last()) }
            } else status((if (truthy(d.error)) str(d.error) else "error") + ": " + (if (truthy(d.reason)) str(d.reason) else ""), "err")
        } catch (e: Throwable) {
            status("delete failed: " + errorString(e), "err")
        }
    }

    private fun newDoc() {
        input("docid").value = ""
        body().value = "{\n  \n}"
        currentRev = null
        byId("att").innerHTML = ""
        status("new document — set an _id and save")
    }

    private fun select(id: String) = byId(id) as HTMLSelectElement

    private fun option(sel: HTMLSelectElement, value: String, label: String = value) {
        val o = document.createElement("option") as HTMLOptionElement
        o.value = value
        o.textContent = label
        sel.appendChild(o)
    }

    /** Fill the database selector from `/_all_dbs`; the `?db=` query picks the initial one. */
    private suspend fun loadDbs() {
        val want = js("new URLSearchParams(window.location.search)").get("db") as String?
        val names = try { jsArray(fetchResponse(FutonPaths.ALL_DBS).jsonValue()).map { str(it) } } catch (_: Throwable) { listOf(DB) }
        val sel = select("db")
        sel.innerHTML = ""
        for (n in names.ifEmpty { listOf(DB) }) option(sel, n)
        DB = if (want != null && want in names) want else if (DB in names) DB else names.firstOrNull() ?: DB
        sel.value = DB
    }

    private fun selectDb(name: String) {
        DB = name
        folder = ""
        input("docid").value = ""
        body().value = ""
        currentRev = null
        byId("att").innerHTML = ""
        byId("viewrows").innerHTML = ""
        status("database $name")
        firstPage()
        launchPage { loadViews() }
    }

    /** The views panel: every `_design/` doc of the selected db, one option per named view. */
    private suspend fun loadViews() {
        val sel = select("viewsel")
        sel.innerHTML = ""
        val d: dynamic = try { fetchResponse(FutonPaths.designDocs(DB)).jsonValue() } catch (_: Throwable) { null }
        val rows = if (d != null && truthy(d.rows)) jsArray(d.rows) else emptyArray()
        var count = 0
        for (r in rows) {
            val doc: dynamic = r.doc
            if (!truthy(doc)) continue
            var views: dynamic = doc.views
            if (isString(views)) views = try { JSON.parse<dynamic>(str(views)) } catch (_: Throwable) { null }
            if (!truthy(views)) continue
            val lang = if (truthy(doc.language)) str(doc.language) else "javascript"
            for (v in js("Object.keys")(views).unsafeCast<Array<String>>()) {
                option(sel, str(r.id) + "\u0000" + v, str(r.id).removePrefix("_design/") + " / " + v + " · " + lang)
                count++
            }
        }
        if (count == 0) option(sel, "", "no views in $DB")
        byId("viewinfo").textContent = "$count view" + (if (count == 1) "" else "s")
    }

    private suspend fun runView() {
        val choice = select("viewsel").value
        if (choice.isEmpty()) return
        val ddoc = choice.substringBefore('\u0000')
        val view = choice.substringAfter('\u0000')
        val t = byId("viewrows") as HTMLTableElement
        t.innerHTML = "<tr><th>key</th><th>value</th><th>id</th></tr>"
        val started = window.performance.now()
        try {
            val r = fetchResponse(FutonPaths.view(DB, ddoc, view, input("viewq").value))
            val d: dynamic = r.jsonValue()
            if (!r.ok) { byId("viewinfo").textContent = "view " + r.status + ": " + (if (truthy(d.reason)) str(d.reason) else str(d.error)); return }
            val rows = if (truthy(d.rows)) jsArray(d.rows) else emptyArray()
            for (row in rows) {
                val tr = document.createElement("tr") as HTMLElement
                val id = if (row.id == null) "" else str(row.id)
                tr.innerHTML = "<td>" + JSON.stringify(row.key).esc() + "</td><td>" + JSON.stringify(row.value).esc() + "</td><td>" + id.esc() + "</td>"
                if (id.isNotEmpty()) tr.onclick = { launchPage { loadDoc(id) } }
                t.appendChild(tr)
            }
            byId("viewinfo").textContent = rows.size.toString() + " rows" + (if (d.total_rows != null) " of " + str(d.total_rows) else "") +
                " · " + (window.performance.now() - started).toInt() + " ms"
        } catch (e: Throwable) {
            byId("viewinfo").textContent = "view failed: " + errorString(e)
        }
    }

    private fun String?.esc(): String = (this ?: "undefined").replace("&", "&amp;").replace("<", "&lt;")

    private fun qsInstall() {
        val d = document.createElement("div") as HTMLElement
        d.setAttribute("data-qs", "1")
        d.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.65);z-index:99;display:flex;align-items:center;justify-content:center"
        d.innerHTML = "<div style=\"background:#11151e;border:1px solid #f29111;border-radius:8px;padding:18px 22px;max-width:580px;color:#d8dce6;font:13px monospace\">" +
            "<b style=\"color:#f29111\">Run TrikeShed in anger &mdash; five minutes, one port</b>" +
            "<pre id=\"qsCmds\" style=\"background:#0b0e14;padding:10px;border-radius:4px;margin:10px 0;user-select:text;white-space:pre-wrap\">git clone git@github.com:jnorthrup/TrikeShed.git &amp;&amp; cd TrikeShed\n./gradlew hotswapFeed\nbin/oroboros-daemon --watch</pre>" +
            "<div style=\"color:#7b8496;margin-bottom:10px\">then open http://localhost:8888 &mdash; drop a REAL folder on /graal, run a stored program via POST /api/lcnc/run, abuse the board. Every stumble is a bug we want.</div>" +
            "<button onclick=\"navigator.clipboard.writeText(document.getElementById('qsCmds').textContent)\" style=\"background:#161b26;border:1px solid #3ddc84;color:#3ddc84;border-radius:4px;padding:4px 12px;cursor:pointer;font:inherit\">copy commands</button> " +
            "<a href=\"https://github.com/jnorthrup/TrikeShed#run-it-in-anger--please\" target=\"_blank\" style=\"color:#3fd0ff;margin-left:10px\">README &#8599;</a>" +
            "<button onclick=\"document.querySelector('div[data-qs]').remove()\" style=\"background:none;border:none;padding:0;font:inherit;color:#7b8496;margin-left:14px;cursor:pointer\">close</button></div>"
        d.onclick = { e: Event -> if (e.target == d) d.remove() }
        document.body!!.appendChild(d)
    }

    private fun qsFeedback() {
        val coords: dynamic = obj()
        coords.prefix = (document.getElementById("prefix") as HTMLInputElement?)?.value ?: ""
        coords.doc = (document.getElementById("docid") as HTMLInputElement?)?.value ?: ""
        coords.db = DB
        val b = "**Surface:** futon\n**URL:** " + window.location.href + "\n**Coordinates:**\n```json\n" + JSON.stringify(coords, null, 1) +
            "\n```\n\n**What I did:**\n\n**What happened:**\n\n**What I expected:**\n"
        window.open("https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback&title=" + encodeUri("[futon] ") + "&body=" + encodeUri(b), "_blank")
    }

    fun mount() {
        val w = window.asDynamic()
        w.firstPage = { firstPage() }
        w.nextPage = { nextPage() }
        w.prevPage = { prevPage() }
        w.loadDoc = { id: String -> launchPage { loadDoc(id) } }
        w.saveDoc = { launchPage { saveDoc() } }
        w.deleteDoc = { launchPage { deleteDoc() } }
        w.newDoc = { newDoc() }
        w.qsInstall = { qsInstall() }
        w.qsFeedback = { qsFeedback() }
        w.selectDb = { name: String -> selectDb(name) }
        w.loadViews = { launchPage { loadViews() } }
        w.runView = { launchPage { runView() } }
        launchPage {
            loadDbs()
            firstPage()
            loadViews()
        }
    }
}
