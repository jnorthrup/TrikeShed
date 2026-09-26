package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTableElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event

/** Futon: the CouchDB-1.x style document browser over `/trikeshed`. */
object FutonPage {
    private const val DB = "trikeshed"
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
            val i: dynamic = fetchResponse("/$DB").jsonValue()
            byId("info").textContent = (i.doc_count.toLocaleString() as String) + " docs · seq " + str(i.update_seq)
        } catch (_: Throwable) {
        }
    }

    private suspend fun loadPage(startkey: String?) {
        val p = input("prefix").value
        var url = "/$DB/_all_docs?limit=" + (LIMIT + 1)
        val sk = startkey ?: p.ifEmpty { null }
        if (!sk.isNullOrEmpty()) url += "&startkey=" + encodeUri("\"" + sk + "\"")
        if (p.isNotEmpty()) url += "&endkey=" + encodeUri("\"" + p + "\uFFF0\"")
        val d: dynamic = fetchResponse(url).jsonValue()
        val rows = if (truthy(d.rows)) jsArray(d.rows) else emptyArray()
        val t = byId("docs") as HTMLTableElement
        t.innerHTML = "<tr><th style=\"width:66%\">_id</th><th>_rev</th></tr>"
        for (r in rows.take(LIMIT)) {
            val tr = document.createElement("tr") as HTMLElement
            val rev = if (truthy(r.value) && truthy(r.value.rev)) str(r.value.rev) else ""
            tr.innerHTML = "<td>" + str(r.id).replace("&", "&amp;").replace("<", "&lt;") + "</td><td class=\"rev\">" + rev.take(18) + "…</td>"
            val id = str(r.id)
            tr.onclick = { launchPage { loadDoc(id) } }
            t.appendChild(tr)
        }
        (byId("nextB") as HTMLButtonElement).disabled = rows.size <= LIMIT
        (byId("prevB") as HTMLButtonElement).disabled = pageStack.size <= 1
        byId("pageinfo").textContent = if (rows.isNotEmpty()) str(rows[0].id).take(46) + " …" else "no documents"
        nextKey = if (rows.size > LIMIT) str(rows[LIMIT].id) else null
        window.asDynamic()._nextKey = nextKey
        launchPage { refreshInfo() }
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
            val r = fetchResponse("/$DB/" + enc(id))
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
                byId("att").innerHTML = "attachment: <a href=\"/$DB/" + enc(id) + "/content\" target=\"_blank\">content</a> · " +
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
            val r = fetchResponse("/$DB/" + enc(id), requestInit("PUT", jsonHeaders(), JSON.stringify(b)))
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
            val r = fetchResponse("/$DB/" + enc(id) + "?rev=" + encodeUri(rev), requestInit("DELETE"))
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
        firstPage()
    }
}
