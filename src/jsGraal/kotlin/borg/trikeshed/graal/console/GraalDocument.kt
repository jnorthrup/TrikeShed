package borg.trikeshed.graal.console

import borg.trikeshed.web.graal.AllocationInspector
import borg.trikeshed.web.graal.encUri
import borg.trikeshed.web.graal.entries
import borg.trikeshed.web.graal.fetchResponse
import borg.trikeshed.web.graal.gzipBytes
import borg.trikeshed.web.graal.hasCompressionStream
import borg.trikeshed.web.graal.isArray
import borg.trikeshed.web.graal.jsonIndent
import borg.trikeshed.web.graal.jsonStringify
import borg.trikeshed.web.graal.newObject
import borg.trikeshed.web.graal.num
import borg.trikeshed.web.graal.orr
import borg.trikeshed.web.graal.s0
import borg.trikeshed.web.graal.str
import borg.trikeshed.web.graal.toFixed
import borg.trikeshed.web.graal.truthy
import borg.trikeshed.web.graal.typeOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import kotlin.js.Promise
import kotlin.math.min

/* ── atomic inspect, document projections, sheets, tree sheet, ledger, notion ── */

fun esc(x: dynamic): String = Projection.esc(s0(x))
fun attrEsc(x: dynamic): String = Projection.attrEsc(s0(x))
fun chip(hex: dynamic, label: String? = null): String = Projection.chip(if (hex == null) "" else str(hex), label)
fun codeView(text: String, maxLines: Int = 1200): String = Projection.codeView(text, maxLines)
fun mdView(text: String): String = Projection.mdView(text)
fun shapeStripHtml(text: String): String = Projection.shapeStripHtml(text, true, Projection::esc)
fun bytesOf(b: ArrayBuffer): ByteArray = Int8Array(b).unsafeCast<ByteArray>()
fun classCard(b: ArrayBuffer): String? = Projection.classCard(bytesOf(b), Terrain::fmtBytes)
fun hexHead(b: ArrayBuffer, n: Int): String = Projection.hexHead(bytesOf(b), n, Terrain::fmtBytes, Projection::esc)
private fun jsStringQuote(id: String): String = id.replace("'", "\\'")

/** productDocs: synthetic id → renderable payload (the forge PRODUCT, not the harness). */
val productDocs = HashMap<String, dynamic>()
/** heap continent leaves: id → {kind, cls, count?, bytes}. */
val heapLeaves = HashMap<String, dynamic>()

val EXCLUSIVE_PANELS = listOf("doc", "points", "vms", "sheet", "ledger", "aot", "gc", "zoom")

/* floating panels: doc/sheet/notion share ONE screen rectangle, vms/ledger/aot share ANOTHER —
   show()/hide() are the only entry points, so at most one of this concentric set is visible at once. */
fun GraalConsole.closeExclusivePanels() {
    for (id in EXCLUSIVE_PANELS) q(id).style.display = "none"
    if (mode == "notion") { mode = "map"; q("notion").style.display = "none" }
}
fun GraalConsole.show(id: String) { closeExclusivePanels(); q(id).style.display = "flex" }
fun GraalConsole.hide(id: String) { q(id).style.display = "none" }

/* ── notion flavor: the same cursor as a sortable, filterable database ── */
fun GraalConsole.buildNotion() {
    val scope0 = targetNode() ?: root ?: return
    q("nscope").textContent = (if (scope0 === root) "/" else pathOf(scope0)) + " · " + loc(scope0.docs) + " entries"
    val leaves = ArrayList<TerrainNode>()
    fun walk(x: TerrainNode) { if (x.leaf) leaves.add(x) else for (c in x.children.values) walk(c) }
    walk(scope0)
    val f = ((q("nfilter") as HTMLInputElement).value).lowercase()
    var rows: List<TerrainNode> = if (f.isNotEmpty()) leaves.filter { it.id!!.lowercase().contains(f) } else leaves
    fun kPct(l: TerrainNode): dynamic { val k = painter.kCache[l.id]; return if (k != null && k >= 0) borg.trikeshed.web.graal.jsRound(k * 100) else "" }
    val cols: List<Pair<String, (TerrainNode) -> dynamic>> = listOf(
        "entry" to { l -> l.id }, "plane" to { l -> l.id!!.split('/')[0] }, "bytes" to { l -> l.bytes },
        "seq" to { l -> l.seq.toDouble() }, "gen" to { l -> l.gen.toDouble() }, "K%" to { l -> kPct(l) })
    val nSort: dynamic = window.asDynamic().nSort
    val col = num(nSort.col).toInt(); val dir = num(nSort.dir).toInt()
    val cmp: (dynamic, dynamic) -> Int = { a, b -> if (js("a<b") as Boolean) -1 else if (js("a>b") as Boolean) 1 else 0 }
    rows = rows.sortedWith { a, b -> cmp(cols[col].second(a), cols[col].second(b)) * dir }
    val cap = rows.take(1500)
    q("nbody").innerHTML = "<table><tr>" + cols.mapIndexed { i, c ->
        "<th onclick=\"nSort={col:" + i + ",dir:nSort.col===" + i + "?-nSort.dir:-1};buildNotion()\">" + c.first + (if (col == i) (if (dir < 0) " ↓" else " ↑") else "") + "</th>"
    }.joinToString("") + "</tr>" +
        cap.joinToString("") { l ->
            "<tr onclick=\"openDoc('" + jsStringQuote(l.id!!) + "')\"><td>" + esc(l.id) + "</td><td>" + esc(l.id!!.split('/')[0]) + "</td><td style=\"text-align:right\">" + loc(l.bytes) +
                "</td><td style=\"text-align:right\">" + l.seq + "</td><td style=\"text-align:right\">" + l.gen + "</td><td style=\"text-align:right\">" + str(kPct(l)) + "</td></tr>"
        } + "</table>" +
        (if (rows.size > cap.size) "<div style=\"color:var(--dim);padding:6px 0\">…" + loc(rows.size - cap.size) + " more — filter or scope tighter</div>" else "")
}

fun GraalConsole.jumpLine(n: Int) {
    val cv = document.querySelector("#proj .codeview") ?: return
    val row = cv.children.item(n - 1) as HTMLElement? ?: return
    val o: dynamic = newObject(); o.block = "center"; row.asDynamic().scrollIntoView(o)
    row.style.outline = "1px solid var(--commit)"; window.setTimeout({ row.style.outline = "" }, 1200)
}

fun GraalConsole.openDoc(id: String) {
    val allocation: dynamic = heapLeaves[id]
    if (allocation != null) {
        val ctx0: dynamic = newObject(); ctx0.kind = allocation.kind; ctx0.bytes = allocation.bytes
        AllocationInspector.open(str(allocation.cls), ctx0); return
    }
    q("docTitle").textContent = id.substringAfterLast('/'); q("docBody").innerHTML = "…"; q("docBody").style.display = ""
    q("docSheetBody").style.display = "none"; q("docSheetBody").innerHTML = ""; sheetFamily = null; window.asDynamic().sheetCurrentId = null
    show("doc")
    sel = nodeFor(id); selEdges = emptyList(); window.asDynamic().selEdges = js("[]")
    // the forge PRODUCT projects from its own payload, not the couch harness
    val pd: dynamic = productDocs[id]
    if (pd != null) {
        if (pd.kind == "card") {
            val c: dynamic = pd.card
            q("docBody").innerHTML = "<div class=\"docmeta\"><b>" + esc(pd.title) + "</b><span class=\"hashchip\">" + esc(pd.column) + "</span></div>" +
                (if (truthy(c.priority)) "<div class=\"docmeta\">priority " + esc(c.priority) + "</div>" else "") +
                (if (truthy(c.description)) mdView(str(c.description)) else "<i style=\"color:var(--dim)\">no description</i>") +
                (if (isArray(c.dependencies) && truthy(c.dependencies.length)) "<div style=\"margin-top:6px;color:var(--dim)\">depends: " +
                    c.dependencies.unsafeCast<Array<dynamic>>().joinToString(", ") { esc(it) } + "</div>" else "")
        } else {
            q("docBody").innerHTML = "<div class=\"docmeta\"><b>" + esc(pd.title) + "</b></div><table class=\"kv\">" +
                entries(pd.session).filter { typeOf(it[1]) != "object" }.take(14)
                    .joinToString("") { "<tr><td>" + esc(it[0]) + "</td><td>" + esc(Sensitive.display(str(it[0]), if (it[1] == null) null else str(it[1])).take(120)) + "</td></tr>" } + "</table>"
        }
        return
    }
    scope.launch { openStoreDoc(id) }
}

private val HASHY = Regex("^(sha256:)?[0-9a-f]{40,64}$")

private suspend fun GraalConsole.openStoreDoc(id: String) {
    val doc: dynamic
    try {
        val response = fetchResponse(graalDocUrl(id))
        doc = response.json().await()
        if (!response.ok) { q("docBody").textContent = str(orr(doc.error, "document fetch failed")) + " (" + response.status + ")"; return }
    } catch (e: Throwable) { q("docBody").textContent = "fetch failed: " + errText(e); return }
    val att: dynamic = if (truthy(doc._attachments)) doc._attachments.content else null
    val previewBlocked = (truthy(doc._graal) && truthy(doc._graal.previewBlocked)) || (truthy(att) && truthy(att.previewBlocked)) || Sensitive.docId(id)
    // ── header: path, rev/cid as chips, bytes, kolmogorov gauge ──
    var html = "<div class=\"docmeta\"><span style=\"color:var(--dim)\">" + esc(id) + "</span></div>" +
        "<div class=\"docmeta\">" + chip(doc._rev, "rev") + (if (truthy(att) && truthy(att.cid)) chip(att.cid, "cid") else "") +
        (if (truthy(att)) "<span>" + fmtBytes(num(orr(att.length, 0))) + "</span><span class=\"kgauge\" id=\"kg\"><i></i></span><span style=\"color:var(--dim)\">…K</span>" else "") + "</div>"
    // ── field sheet (no underscore noise, hashes as chips) ──
    val fields = entries(doc).filter { val k = str(it[0]); !k.startsWith("_") && k != "contentId" && k != "length" && k != "contentType" }
    if (fields.isNotEmpty()) {
        html += "<table class=\"kv\">" + fields.joinToString("") { kv ->
            val v: dynamic = kv[1]
            val value = if (typeOf(v) == "object") esc(jsonIndent(v, 1)).take(400) else if (HASHY.containsMatchIn(s0(v))) chip(v) else esc(v)
            "<tr><td>" + esc(kv[0]) + "</td><td>" + value + "</td></tr>"
        } + "</table>"
    }
    q("docBody").innerHTML = html + "<div id=\"proj\" style=\"margin-top:8px;color:var(--dim)\">projecting…</div>"
    // ── content projection by type ──
    val proj = document.getElementById("proj") as HTMLElement
    if (truthy(att) && previewBlocked) proj.textContent = "content preview blocked for credential-bearing document"
    else if (truthy(att)) {
        val ct = s0(orr(att.content_type, "")).lowercase(); val ext = Projection.extOf(id); val big = num(orr(att.length, 0)) > 1500000
        if (big) proj.innerHTML = "<button style=\"background:none;border:none;padding:0;font:inherit;cursor:pointer;color:var(--commit)\" onclick=\"this.parentElement.textContent='…';projectBytes('" +
            jsStringQuote(id) + "')\">" + fmtBytes(num(att.length)) + " — project anyway</button>"
        else projectBytes(id, ct, ext)
    } else (document.getElementById("proj") as HTMLElement).textContent = if (previewBlocked) "content preview blocked for credential-bearing document" else ""
    // ── dag edges ──
    try {
        val d: dynamic = fetchResponse("/api/graal/dag?id=" + encUri(id)).json().await()
        val edges = orr(d.edges, js("[]")).unsafeCast<Array<dynamic>>()
        selEdges = edges.map { e -> SelEdge(nodeFor(str(e.to)), str(e.kind), str(e.to)) }
        window.asDynamic().selEdges = selEdges.map { e -> val o: dynamic = newObject(); o.id = e.id; o.kind = e.kind; o }.toTypedArray()
        if (selEdges.isNotEmpty()) {
            val div = document.createElement("div") as HTMLElement
            div.innerHTML = "<div style=\"margin-top:6px;color:var(--dim)\">dag edges (" + selEdges.size + "):</div>" +
                selEdges.take(20).mapIndexed { i, e -> "<span class=\"dagchip\" onclick=\"openDoc(selEdges[" + i + "].id)\">" + e.kind + " → …" + esc(e.id.takeLast(38)) + "</span>" }.joinToString("")
            q("docBody").appendChild(div)
        }
    } catch (_: Throwable) {}
}

/* ── concentric treesheet: /api/graal/sheet's family, grid-in-cell, live (CursorSheet) ── */
var sheetFamily: Array<dynamic>? = null

fun GraalConsole.toggleSheetView() {
    val db = q("docBody"); val sb = q("docSheetBody")
    if (sb.style.display != "none") { sb.style.display = "none"; db.style.display = ""; return }
    val id = sel?.id
    if (id == null) { sb.style.display = "block"; db.style.display = "none"; sb.innerHTML = "<div style=\"color:var(--dim)\">no document selected</div>"; return }
    db.style.display = "none"; sb.style.display = "block"; sb.innerHTML = "…loading sheet family…"
    scope.launch {
        try {
            val resp = fetchResponse("/api/graal/sheet?id=" + encUri(id))
            if (!resp.ok) { sb.innerHTML = "<div style=\"color:var(--deopt)\">sheet fetch failed: " + resp.status + "</div>"; return@launch }
            val fam = resp.json().await().unsafeCast<Array<dynamic>>()
            sheetFamily = fam
            window.asDynamic().sheetCurrentId = if (fam.isNotEmpty() && truthy(fam[0])) fam[0].id else null
            renderSheetFamily()
        } catch (e: Throwable) { sb.innerHTML = "<div style=\"color:var(--deopt)\">" + esc(errText(e)) + "</div>" }
    }
}

fun sheetById(id: dynamic): dynamic = (sheetFamily ?: emptyArray()).firstOrNull { it.id == id }

fun GraalConsole.renderSheetFamily() {
    val sb = q("docSheetBody"); val seed: dynamic = sheetById(window.asDynamic().sheetCurrentId)
    if (seed == null) { sb.textContent = "no sheet"; return }
    val crumbs = ArrayList<dynamic>(); var cur: dynamic = seed
    while (cur != null) { crumbs.add(0, cur); cur = if (truthy(cur.parent)) sheetById(cur.parent) else null }
    var html = "<div style=\"margin-bottom:6px;font-size:10px;color:var(--dim)\">" +
        crumbs.mapIndexed { i, s -> "<span style=\"cursor:pointer;color:" + (if (i == crumbs.size - 1) "var(--ink)" else "var(--commit)") + "\" onclick=\"sheetCurrentId=" + attrQuoted(s.id) + ";renderSheetFamily()\">" + esc(s.title) + "</span>" }
            .joinToString(" <span style=\"color:var(--dim)\">▸</span> ") + "</div>"
    val rows = seed.rows.unsafeCast<Array<Array<dynamic>>>()
    if (rows.isEmpty()) html += "<div style=\"color:var(--dim)\">empty sheet</div>"
    else html += "<table><tr>" + seed.columns.unsafeCast<Array<dynamic>>().joinToString("") { "<th>" + esc(it.name) + "</th>" } + "</tr>" +
        rows.joinToString("") { row ->
            "<tr>" + row.joinToString("") { cell ->
                if (truthy(cell) && typeOf(cell) == "object" && truthy(cell.sheet)) {
                    val child: dynamic = sheetById(cell.sheet)
                    "<td><span class=\"dagchip\" onclick=\"sheetCurrentId=" + attrQuoted(cell.sheet) + ";renderSheetFamily()\">▤ " + esc(if (child != null) child.title else cell.sheet) + "</span></td>"
                } else "<td>" + esc(if (cell == null) "" else str(cell)) + "</td>"
            } + "</tr>"
        } + "</table>"
    sb.innerHTML = html
}

/** JSON.stringify(x) as the original wrote it inside a double-quoted attribute. */
private fun attrQuoted(x: dynamic): String = jsonStringify(x)

suspend fun GraalConsole.projectBytes(id: String, ct0: String?, ext0: String?) {
    val proj = document.getElementById("proj") as HTMLElement? ?: return
    val ct = ct0 ?: ""; val ext = ext0 ?: Projection.extOf(id)
    if (Sensitive.docId(id)) { proj.textContent = "content preview blocked for credential-bearing document"; return }
    try {
        val r = fetchResponse(graalContentUrl(id))
        if (!r.ok) { proj.textContent = "no content (" + r.status + ")"; return }
        val buf = r.arrayBuffer().await()
        scope.launch { kolmogorov(buf, "kg") }
        val asText = { js("new TextDecoder()").decode(buf).unsafeCast<String>() }
        if (ct.contains("svg") || ext == "svg") {
            val t = asText()
            proj.innerHTML = "<img class=\"imgview\" src=\"data:image/svg+xml;base64," + str(js("btoa(unescape(encodeURIComponent(t)))")) + "\">"
        } else if (ct.startsWith("image/")) {
            val u: String = js("URL.createObjectURL(new Blob([buf],{type:ct}))")
            proj.innerHTML = "<img class=\"imgview\" src=\"$u\">"
        } else if (ext == "md" || ct.contains("markdown")) { val t = asText(); proj.innerHTML = shapeStripHtml(t) + mdView(t) }
        else if (ct.contains("html") || ext == "html") {
            proj.innerHTML = "<iframe sandbox srcdoc=\"" + asText().replace("&", "&amp;").replace("\"", "&quot;") + "\" style=\"width:100%;height:44vh;border:1px solid var(--line);border-radius:4px;background:#fff\"></iframe>"
        } else if (ext == "class" || ct.contains("java-vm")) { proj.innerHTML = classCard(buf) ?: hexHead(buf, 128); classfileProjection(id, proj) }
        else if (ct.startsWith("text/") || Regex("^(kt|kts|java|py|js|ts|json|yaml|yml|css|sh|gradle|xml|txt|toml|properties|sql|rs|c|h|cpp|go)$").matches(ext)) {
            val t = if (ext == "json") jsonIndent(JSON.parse<dynamic>(asText()), 2) else asText()
            proj.innerHTML = shapeStripHtml(t) + codeView(t)
            if (Regex("^(kt|kts|java)$").matches(ext)) sourceMates(id, proj)
        } else proj.innerHTML = hexHead(buf, 128)
    } catch (e: Throwable) { proj.textContent = "projection failed: " + errText(e) }
}

suspend fun GraalConsole.kolmogorov(bytes: ArrayBuffer, mount: String) {
    if (!hasCompressionStream() || bytes.byteLength == 0) return
    try {
        val compressed = gzipBytes(bytes).await()
        val ratio = min(1.0, compressed.byteLength.toDouble() / bytes.byteLength)
        val el = document.getElementById(mount) as HTMLElement? ?: return
        (el.querySelector("i") as HTMLElement).style.width = str(borg.trikeshed.web.graal.jsRound(ratio * 100)) + "%"
        el.title = "kolmogorov gauge: gzip " + fmtBytes(compressed.byteLength.toDouble()) + " / raw " + fmtBytes(bytes.byteLength.toDouble()) + " = " + toFixed(ratio * 100, 0) + "% (left=structured, right=incompressible)"
        (el.nextElementSibling as HTMLElement).textContent = toFixed(ratio * 100, 0) + "% K"
    } catch (_: Throwable) {}
}

private suspend fun GraalConsole.classfileProjection(id: String, proj: HTMLElement) {
    val mount = document.createElement("div") as HTMLElement
    mount.className = "classdetail"
    mount.innerHTML = "<div class=\"classnote\">JDK ClassFile API projection…</div>"
    proj.appendChild(mount)
    try {
        val r = fetchResponse("/api/graal/classfile?id=" + encUri(id))
        val x: dynamic = r.json().await()
        if (!r.ok || truthy(x.error)) {
            mount.innerHTML = "<div class=\"classnote\" style=\"color:var(--deopt)\">classfile projection: " + esc(orr(x.error, r.status)) + "</div>"
            return
        }
        renderClassfileProjection(x, mount)
    } catch (e: Throwable) {
        mount.innerHTML = "<div class=\"classnote\" style=\"color:var(--deopt)\">classfile projection failed: " + esc(errText(e)) + "</div>"
    }
}

fun lineText(line: dynamic): String = if (js("Number.isFinite(Number(line))&&Number(line)>=0") as Boolean) str(line) else "unavailable"
fun methodLabel(m: dynamic): String = (if (truthy(m) && truthy(m.name)) str(m.name) else "?") + (if (truthy(m) && truthy(m.descriptor)) str(m.descriptor) else "")
fun methodLineText(m: dynamic): String {
    val s: dynamic = orr(m.sourceLines, newObject())
    return if (truthy(s.available)) "lines " + str(s.min) + (if (s.max == s.min) "" else "-" + str(s.max)) else "source lines unavailable"
}

fun counterText(obs: dynamic, scope: String): String {
    if (!truthy(obs) || obs.available == false) return "runtime observations unavailable: " + esc(orr(if (truthy(obs)) obs.reason else null, "JvmVitals not attached"))
    if (scope == "class") {
        val c = orr(obs.classRecentCompiles, js("[]")).unsafeCast<Array<dynamic>>()
        if (c.isNotEmpty()) return c.size.toString() + " recent class-level JFR compile event" + (if (c.size == 1) "" else "s") + " observed; method and instruction counters remain unavailable at selected-class identity."
        return "no recent class-level JFR compile event observed; this is unobserved, not a zero lifetime count."
    }
    val key = if (scope == "instruction") "instructionCounters" else "methodCounters"
    val u: dynamic = orr(obs[key], newObject())
    return "runtime " + scope + " counters unavailable: " + esc(orr(u.reason, "no descriptor/loader/BCI keyed counter is exposed here"))
}

fun pointcutMatch(p: dynamic, m: dynamic, insn: dynamic): Boolean {
    val s: dynamic = orr(p.symbol, newObject())
    if (!truthy(m) || s.methodName != m.name || s.methodDescriptor != m.descriptor) return false
    return !truthy(insn) || num(p.bytecodeOffset) == num(insn.offset)
}

/** `(v ?? d)` */
private fun nn(v: dynamic, d: dynamic): dynamic = if (v == null) d else v

fun renderClassfileProjection(x: dynamic, mount: HTMLElement) {
    val cf: dynamic = orr(x.classFile, newObject()); val debug: dynamic = orr(cf.debug, newObject()); val obs: dynamic = orr(x.runtimeObservation, newObject())
    val methods = orr(x.methods, js("[]")).unsafeCast<Array<dynamic>>(); val fields = orr(x.fields, js("[]")).unsafeCast<Array<dynamic>>()
    val interfaces = orr(x.interfaces, js("[]")).unsafeCast<Array<dynamic>>(); val pointcuts = orr(x.pointcuts, js("[]")).unsafeCast<Array<dynamic>>()
    val status = if (truthy(x.exactRuntimeBlob)) "runtime bytes match selected blob" else if (truthy(x.onClasspath)) "same class resource is on classpath, bytes differ" else "not on runtime classpath"
    val statusClass = if (truthy(x.exactRuntimeBlob)) "ok" else if (truthy(x.onClasspath)) "warn" else ""
    val byKind = LinkedHashMap<String, Int>(); for (p in pointcuts) { val k = str(p.kind); byKind[k] = (byKind[k] ?: 0) + 1 }
    val kindRows = byKind.entries.sortedWith { a, b -> if (a.key < b.key) -1 else 1 }.joinToString("") { "<tr><td>" + esc(it.key) + "</td><td>" + it.value + "</td></tr>" }
    val stats = listOf<Pair<String, dynamic>>(
        "api" to orr(x.classfileApi, "java.lang.classfile"),
        "version" to (str(nn(cf.majorVersion, "?")) + "." + str(nn(cf.minorVersion, "?"))),
        "access" to orr(cf.access, "package"),
        "interfaces" to nn(cf.interfaceCount, interfaces.size),
        "fields" to nn(cf.fieldCount, fields.size),
        "methods" to nn(cf.methodCount, methods.size),
        "instructions" to nn(cf.instructionCount, "unavailable"),
        "constants" to nn(cf.constantCount, "unavailable"),
        "debug" to (if (truthy(debug.hasSourceFile)) (if (truthy(debug.hasSourceLines)) "source lines from " + str(debug.sourceFile) else "SourceFile only: " + str(debug.sourceFile)) else "debug metadata unavailable"),
    ).joinToString("") { (k, v) -> "<tr><td>" + esc(k) + "</td><td>" + esc(v) + "</td></tr>" }
    mount.innerHTML =
        "<div class=\"classhead\">" +
            "<div class=\"classname\">" + esc(orr(x.className, "(unknown class)")) + "</div>" +
            "<div class=\"classrow\">" +
            "<span class=\"classpill\">super " + esc(orr(x.superClass, "(none)")) + "</span> " +
            "<span class=\"classpill " + statusClass + "\">" + esc(status) + "</span> " +
            chip(x.blobCid, "blob") + " " + (if (truthy(x.runtimeCid)) chip(x.runtimeCid, "runtime") + " " else "") +
            "<span class=\"classpill\">" + esc(orr(x.blobBytes, 0)) + " bytes</span>" +
            "</div>" +
            "<div class=\"classrow\">" + (if (interfaces.isNotEmpty()) interfaces.joinToString(" ") { "<span class=\"classpill\">implements " + esc(it) + "</span>" } else "<span class=\"classpill\">no interfaces</span>") + "</div>" +
            "<div class=\"classnote\">" + counterText(obs, "class") + "</div>" +
            "</div>" +
            "<table class=\"kv\">" + stats + "</table>" +
            "<div class=\"classsplit\">" +
            "<div><div class=\"classnote\">methods use full JVM descriptors</div><div class=\"classlist\" data-class-methods>" +
            (if (methods.isNotEmpty()) methods.mapIndexed { i, m ->
                "<button class=\"classitem\" data-method-index=\"" + i + "\" title=\"" + attrEsc(methodLabel(m)) + "\">" +
                    esc(orr(m.name, "?")) + "<br><span style=\"color:var(--dim)\">" + esc(orr(m.descriptor, "")) + "</span><br><span style=\"color:var(--dim)\">" + esc(methodLineText(m)) + " · " + esc(orr(m.instructionCount, 0)) + " insn</span></button>"
            }.joinToString("") else "<div class=\"classitem\">no methods</div>") +
            "</div>" +
            (if (fields.isNotEmpty()) "<div class=\"classnote\" style=\"margin-top:8px\">fields</div><table>" + fields.take(80).joinToString("") { "<tr><td>" + esc(it.name) + "</td><td class=\"classmono\">" + esc(it.descriptor) + "</td></tr>" } + "</table>" else "") +
            (if (kindRows.isNotEmpty()) "<div class=\"classnote\" style=\"margin-top:8px\">pointcut coordinates</div><table>" + kindRows + "</table>" else "<div class=\"classnote\">no value-bearing pointcut coordinates</div>") +
            "</div><div class=\"classdetailbox\" data-class-detail></div>" +
            "</div>"
    val detail = mount.querySelector("[data-class-detail]") as HTMLElement

    fun renderInstruction(mi: Int, ii: Int) {
        val m: dynamic = methods.getOrNull(mi)
        val insns = (if (truthy(m) && truthy(m.instructions)) m.instructions else js("[]")).unsafeCast<Array<dynamic>>()
        val insn: dynamic = insns.getOrNull(ii)
        val box = detail.querySelector("[data-insn-detail]") as HTMLElement?
        if (box == null || !truthy(insn)) return
        for (row in detail.querySelectorAll("[data-insn-index]").asList()) {
            val r = row as HTMLElement; r.classList.toggle("active", num(r.asDynamic().dataset.insnIndex).toInt() == ii)
        }
        val pcs = pointcuts.filter { pointcutMatch(it, m, insn) }
        val pcRows = pcs.joinToString("") { p ->
            val s: dynamic = orr(p.source, newObject()); val sym: dynamic = orr(p.symbol, newObject())
            "<tr><td>" + esc(p.kind) + "</td><td>" + esc(p.jvmOpcode) + "</td><td>" + esc(lineText(s.line)) + "</td><td class=\"classmono\">" +
                esc((if (truthy(sym.owner)) str(sym.owner) + "." else "") + s0(orr(sym.name, "")) + (if (truthy(sym.descriptor)) " " + str(sym.descriptor) else "")) + "</td></tr>"
        }
        box.innerHTML =
            "<div class=\"classdetailbox\" style=\"background:var(--panel);margin-top:0\">" +
                "<div><b>instruction</b> <span class=\"classmono\">" + esc(orr(x.className, "")) + "." + esc(orr(m.name, "")) + esc(orr(m.descriptor, "")) + " @ bci " + esc(insn.offset) + "</span></div>" +
                "<table class=\"kv\"><tr><td>mnemonic</td><td>" + esc(insn.mnemonic) + "</td></tr><tr><td>opcode</td><td>" + esc(insn.opcode) + "</td></tr><tr><td>source line</td><td>" + esc(lineText(insn.sourceLine)) +
                "</td></tr><tr><td>owner</td><td class=\"classmono\">" + esc(orr(insn.owner, "")) + "</td></tr><tr><td>name</td><td>" + esc(orr(insn.name, "")) + "</td></tr><tr><td>descriptor</td><td class=\"classmono\">" + esc(orr(insn.descriptor, "")) + "</td></tr></table>" +
                "<div class=\"classnote\">" + counterText(obs, "instruction") + "</div>" +
                (if (pcRows.isNotEmpty()) "<div class=\"classnote\">static pointcut coordinate for this BCI</div><table><tr><th>kind</th><th>opcode</th><th>line</th><th>symbol</th></tr>" + pcRows + "</table>"
                else "<div class=\"classnote\">no value-bearing pointcut coordinate for this BCI</div>") +
                "</div>"
    }

    fun renderMethod(i: Int) {
        val m: dynamic = methods.getOrNull(i)
        if (!truthy(m)) { detail.innerHTML = "<div class=\"classnote\">no method selected</div>"; return }
        for (b in mount.querySelectorAll("[data-method-index]").asList()) {
            val e = b as HTMLElement; e.classList.toggle("active", num(e.asDynamic().dataset.methodIndex).toInt() == i)
        }
        val insns = orr(m.instructions, js("[]")).unsafeCast<Array<dynamic>>()
        val pcs = pointcuts.filter { pointcutMatch(it, m, null) }
        detail.innerHTML =
            "<div><b>" + esc(orr(m.name, "?")) + "</b> <span class=\"classmono\">" + esc(orr(m.descriptor, "")) + "</span></div>" +
                "<div class=\"classnote\">static class-file method · access " + esc(orr(m.access, "package")) + " · max stack " + esc(nn(m.maxStack, "?")) + " · locals " + esc(nn(m.maxLocals, "?")) + " · " + esc(methodLineText(m)) + "</div>" +
                "<div class=\"classnote\">" + counterText(obs, "method") + "</div>" +
                "<div class=\"classnote\">" + pcs.size + " static pointcut coordinate" + (if (pcs.size == 1) "" else "s") + " in this method</div>" +
                "<div class=\"classinsns\"><table><tr><th>bci</th><th>opcode</th><th>line</th><th>site</th></tr>" +
                insns.mapIndexed { ii, insn ->
                    "<tr data-insn-index=\"" + ii + "\"><td>" + esc(insn.offset) + "</td><td>" + esc(insn.mnemonic) + "</td><td>" + esc(lineText(insn.sourceLine)) + "</td><td class=\"classmono\">" +
                        esc((if (truthy(insn.owner)) str(insn.owner) + "." else "") + s0(orr(insn.name, "")) + (if (truthy(insn.pointcutKind)) " · " + str(insn.pointcutKind) else "")) + "</td></tr>"
                }.joinToString("") +
                "</table></div><div data-insn-detail style=\"margin-top:8px\"></div>"
        for (row in detail.querySelectorAll("[data-insn-index]").asList()) {
            val r = row as HTMLElement
            r.addEventListener("click", { renderInstruction(i, num(r.asDynamic().dataset.insnIndex).toInt()) })
        }
        if (insns.isNotEmpty()) renderInstruction(i, 0)
    }
    for (btn in mount.querySelectorAll("[data-method-index]").asList()) {
        val b = btn as HTMLElement
        b.addEventListener("click", { renderMethod(num(b.asDynamic().dataset.methodIndex).toInt()) })
    }
    renderMethod(0)
}

/* JDK 25 ClassFile API over the exact compiled blobs absorbed from build/live/classes. */
private suspend fun GraalConsole.sourceMates(id: String, proj: HTMLElement) {
    val mount = document.createElement("div") as HTMLElement
    mount.innerHTML = "<div style=\"margin-top:8px;color:var(--dim)\">finding SourceFile-mated classpath blobs…</div>"; proj.appendChild(mount)
    try {
        val r = fetchResponse("/api/graal/decompile?source=" + encUri(id))
        val x: dynamic = r.json().await()
        if (!r.ok) { mount.innerHTML = "<div style=\"margin-top:8px;color:var(--deopt)\">decompiler: " + esc(orr(x.error, r.status)) + "</div>"; return }
        val mates = orr(x.mates, js("[]")).unsafeCast<Array<dynamic>>()
        if (mates.isEmpty()) { mount.innerHTML = "<div style=\"margin-top:8px;color:var(--dim)\">JDK 25 ClassFile API: no SourceFile-mated build/live class blob yet</div>"; return }
        mount.innerHTML = "<div style=\"margin-top:8px;color:var(--dim)\">JDK 25 ClassFile API · " + mates.size + " mated blob" + (if (mates.size == 1) "" else "s") + " · " +
            (if (truthy(x.mated)) "byte-identical runtime classpath proven" else "build blob found; runtime identity differs") + "</div>" +
            mates.mapIndexed { i, m ->
                val d: dynamic = orr(m.decompiler, newObject()); val ms = orr(d.methods, js("[]")).unsafeCast<Array<dynamic>>()
                val status = if (truthy(m.exactRuntimeBlob)) "exact runtime mate" else if (truthy(m.onClasspath)) "classpath bytes differ" else "not on runtime classpath"
                "<details class=\"mate " + (if (truthy(m.exactRuntimeBlob)) "exact" else "") + "\" " + (if (i == 0) "open" else "") + "><summary>" + esc(m.className) + " · " + status + "</summary><div class=\"matebody\">" +
                    "<div class=\"docmeta\">" + chip(m.blobCid, "blob") + (if (truthy(m.runtimeCid)) chip(m.runtimeCid, "runtime") else "") + "<span>" + fmtBytes(num(orr(m.blobBytes, 0))) + "</span><span>" + ms.size + " methods</span></div>" +
                    codeView(s0(orr(d.pseudoSource, "no projection")), 2400) + "</div></details>"
            }.joinToString("")
    } catch (e: Throwable) { mount.innerHTML = "<div style=\"margin-top:8px;color:var(--deopt)\">decompiler failed: " + esc(errText(e)) + "</div>" }
}

/* ── drilldown adaptations: tree sheet + ledger ── */
fun GraalConsole.treeSheet() {
    val n = targetNode() ?: return
    q("sheetTitle").textContent = "tree sheet — " + (if (n === root) "/" else pathOf(n))
    class Row(val ind: String, val docs: Int, val bytes: Double, val node: TerrainNode)
    val rows = ArrayList<Row>()
    fun walk(x: TerrainNode, depth: Int) {
        if (depth > 4) return
        rows.add(Row("  ".repeat(depth) + (if (x.children.isNotEmpty()) "▸ " else "· ") + x.name, x.docs, x.bytes, x))
        if (depth < 4) x.children.values.sortedByDescending { it.bytes }.take(40).forEach { walk(it, depth + 1) }
    }
    walk(n, 0)
    window.asDynamic()._sheetRows = rows.map { r -> val o: dynamic = newObject(); o.node = r.node; o }.toTypedArray()
    q("sheetBody").innerHTML = "<table><tr><th>territory</th><th>docs</th><th>bytes</th><th>share</th></tr>" +
        rows.mapIndexed { i, r ->
            "<tr><td style=\"cursor:pointer\" onclick=\"flyTo(window._sheetRows[" + i + "].node);\">" + r.ind + "</td><td>" + loc(r.docs) + "</td><td>" + fmtBytes(r.bytes) + "</td><td>" +
                (if (n.bytes != 0.0) str(borg.trikeshed.web.graal.jsRound(100 * r.bytes / n.bytes)) + "%" else "") + "</td></tr>"
        }.joinToString("") + "</table>"
    show("sheet")
}

fun GraalConsole.ledger() {
    val n = targetNode() ?: return
    q("ledgerTitle").textContent = "ledger — " + (if (n === root) "/" else pathOf(n)) + " (" + loc(n.docs) + " entries)"
    val leaves = ArrayList<TerrainNode>()
    fun walk(x: TerrainNode) { if (x.leaf) leaves.add(x) else for (c in x.children.values) walk(c) }
    walk(n)
    leaves.sortWith { a, b -> if (a.id!! < b.id!!) -1 else 1 }
    var run = 0.0
    val cap = leaves.take(800)
    q("ledgerBody").innerHTML = "<table><tr><th>#</th><th>entry</th><th>bytes</th><th>balance</th></tr>" +
        cap.mapIndexed { i, x ->
            run += x.bytes
            "<tr><td>" + (i + 1) + "</td><td style=\"cursor:pointer\" onclick=\"openDoc('" + jsStringQuote(x.id!!) + "')\">" + x.id + "</td><td style=\"text-align:right\">" + loc(x.bytes) +
                "</td><td style=\"text-align:right\">" + loc(run) + "</td></tr>"
        }.joinToString("") +
        "</table>" + (if (leaves.size > cap.size) "<div style=\"color:var(--dim);margin-top:4px\">…" + loc(leaves.size - cap.size) + " more entries (zoom to a smaller territory)</div>" else "")
    show("ledger")
}
