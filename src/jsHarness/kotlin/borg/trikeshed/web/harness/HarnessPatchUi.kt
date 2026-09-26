package borg.trikeshed.web.harness

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

private fun idDyn(id: String): dynamic = document.getElementById(id).asDynamic()

/** qs dialog: run the quickstart in anger */
fun HarnessPatch.openQuickstart() {
    val d = document.createElement("div").asDynamic(); d.dataset.qs = "1"
    d.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.65);z-index:99;display:flex;align-items:center;justify-content:center"
    d.innerHTML = "<div style=\"background:#11151e;border:1px solid #f29111;border-radius:8px;padding:18px 22px;max-width:580px;color:#d8dce6;font:13px monospace\">" +
        "<b style=\"color:#f29111\">Run TrikeShed in anger — five minutes, one port</b>" +
        "<pre id=\"qsCmds\" style=\"background:#0b0e14;padding:10px;border-radius:4px;margin:10px 0;user-select:text;white-space:pre-wrap\">git clone git@github.com:jnorthrup/TrikeShed.git && cd TrikeShed\n./gradlew hotswapFeed\nbin/oroboros-daemon --watch</pre>" +
        "<button onclick=\"navigator.clipboard.writeText(document.getElementById('qsCmds').textContent)\" style=\"background:#161b26;border:1px solid #3ddc84;color:#3ddc84;border-radius:4px;padding:4px 12px;cursor:pointer;font:inherit\">copy commands</button>" +
        "<a href=\"https://github.com/jnorthrup/TrikeShed#run-it-in-anger--please\" target=\"_blank\" style=\"color:#3fd0ff;margin-left:10px\">README ↗</a>" +
        "<button onclick=\"document.querySelector('div[data-qs]').remove()\" style=\"background:none;border:none;padding:0;font:inherit;color:#7b8496;margin-left:14px;cursor:pointer\">close</button></div>"
    d.onclick = { e: dynamic -> if (e.target === d) d.remove() }
    document.body!!.appendChild(d)
}

/** fb: a GitHub issue prefilled with this construction's coordinates */
fun HarnessPatch.openFeedback() {
    val coords = obj()
    coords.panel = str(idDyn("panelName").value).ifEmpty { "(unsaved)" }
    coords.nodes = nodes().size; coords.types = nodes().map { str(it.type) }.distinct().toTypedArray()
    coords.wires = arr(graph.wires).size; coords.status = str(idDyn("status").textContent ?: "").take(120)
    coords.view = objOf { it.x = js("Math.round")(view.x); it.y = js("Math.round")(view.y); it.z = num(js("(+view.z.toFixed(2))")) }
    val body = "**Surface:** panels\n**URL:** " + window.location.href + "\n**Coordinates:**\n```json\n" +
        js("JSON.stringify(coords,null,1)").unsafeCast<String>() + "\n```\n\n**What I did:**\n\n**What happened:**\n\n**What I expected:**\n"
    window.open("https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback" +
        "&title=" + encodeURIComponent("[panels] ") + "&body=" + encodeURIComponent(body), "_blank")
}

private val projectMarkers = Regex("^(settings\\.gradle(\\.kts)?|build\\.gradle(\\.kts)?|package\\.json|Cargo\\.toml|CMakeLists\\.txt|Makefile|pom\\.xml|go\\.mod|pyproject\\.toml|setup\\.py|mix\\.exs|Package\\.swift|.*\\.xcodeproj/.*)$")
private val skipDirs = setOf("node_modules", "build", "target", "dist", "out", "__pycache__", "venv", ".venv", ".git", ".gradle", ".idea", "DerivedData")

private suspend fun readEntries(reader: dynamic): Array<dynamic> {
    val batch = awaitJs(js("new Promise(function(res,rej){reader.readEntries(res,rej);})"))
    return arr(batch)
}

private suspend fun entryFile(en: dynamic): dynamic = awaitJs(js("new Promise(function(res,rej){en.file(res,rej);})"))

suspend fun panelsWalkEntries(dirEntry: dynamic, prefix: String): List<Pair<String, dynamic>> {
    val out = ArrayList<Pair<String, dynamic>>(); val reader = dirEntry.createReader()
    while (true) {
        val batch = readEntries(reader)
        if (batch.isEmpty()) break
        for (en in batch) {
            if (truthy(en.isFile)) out.add(prefix + str(en.name) to entryFile(en))
            else if (truthy(en.isDirectory)) { if (str(en.name) in skipDirs) continue; out.addAll(panelsWalkEntries(en, prefix + str(en.name) + "/")) }
        }
    }
    return out
}

/** drag a directory from Finder: mount as side-by-side project scope; palette drops land nodes */
suspend fun HarnessPatch.onDrop(e: dynamic) {
    // palette drops first: a type lands as a node (into a ring if dropped on its square), a program as a program.ref
    val ptype = str(e.dataTransfer.getData("text/x-lcnc-type")); val pprog = str(e.dataTransfer.getData("text/x-lcnc-program"))
    if (ptype.isNotEmpty() || pprog.isNotEmpty()) {
        val w = world.getBoundingClientRect()
        val wx = (num(e.clientX) - w.left) / num(view.z); val wy = (num(e.clientY) - w.top) / num(view.z)
        val scope = scopeAtTarget(e.target)
        if (ptype.isNotEmpty()) {
            val nn = if (scope != null) addChildNode(scope, ptype) else addNode(ptype, wx - 8, wy - 8)
            if (nn != null) status("placed " + ptype + (if (scope != null) " into ring " + str(scope.id) else ""))
        } else {
            val nn = addNode("program.ref", wx - 8, wy - 8)
            if (nn != null) {
                nn.params.name = pprog; val inp = nn.el.querySelector(".params input"); if (inp != null) inp.value = pprog
                renderProgramRef(nn); save(); status("program.ref($pprog) placed — the dive strip descends")
            }
        }
        return
    }
    val dt = e.dataTransfer; val paths = ArrayList<String>()
    fun say(t: String) { val s = idDyn("status"); if (s != null) s.textContent = t }
    // PRIORITY 1: file:// URIs — sources that expose absolute host paths
    val rawText = str(dt.getData("text/uri-list")).ifEmpty { str(dt.getData("text/plain")) }
    for (u in rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
        if (u.startsWith("file://")) {
            try { paths.add(decodeURIComponent(str(jsNew(js("URL"), u).pathname))) } catch (x: dynamic) { paths.add(decodeURIComponent(u.replace(Regex("^file://"), ""))) }
        } else if (u.startsWith("/")) paths.add(u)
    }
    // PRIORITY 2: Chrome hides host paths — WALK the dropped hierarchy and UPLOAD the bytes
    if (paths.isEmpty() && truthy(dt.items)) {
        val entries = arr(js("Array.from(dt.items)")).map { it -> if (truthy(it.webkitGetAsEntry)) it.webkitGetAsEntry() else null }.filter { truthy(it) }
        for (entry in entries) {
            if (!truthy(entry.isDirectory)) { say("drop a FOLDER to mount a project db"); continue }
            say("uploading " + str(entry.name) + "/ …")
            try {
                val files = panelsWalkEntries(entry, "")
                val kind = if (files.any { projectMarkers.matches(it.first) }) "project" else "assets"
                val b = fetchJson("/_project/" + encodeURIComponent(str(entry.name).lowercase()) + "/begin?kind=" + kind, objOf { it.method = "POST" })
                if (b.verdict != "ok") { say("upload refused: " + str(b.detail ?: "?")); continue }
                var sent = 0; var skipped = 0; var failed = 0
                var frames = jsArray(); var packed = 0.0
                suspend fun flushBatch() {
                    if (num(frames.length) == 0.0) return
                    val framesNow = frames
                    val blob = js("new Blob(framesNow)"); frames = jsArray(); packed = 0.0
                    val init = jsonInit("POST", blob, "application/octet-stream")
                    val r = fetchJs("/_project/" + encodeURIComponent(str(b.name)) + "/putBatch", init)
                    if (truthy(r.ok)) {
                        val j = awaitJs(r.json()); sent += num(j.stored ?: 0).toInt(); failed += num(j.failed ?: 0).toInt(); say(str(b.name) + ": " + sent + "/" + files.size)
                    } else failed++
                }
                fun frame(rel: String, buf: dynamic) {
                    val pb = js("new TextEncoder()").encode(rel)
                    val h = js("new DataView(new ArrayBuffer(4))"); h.setInt32(0, pb.length)
                    val l = js("new DataView(new ArrayBuffer(8))"); l.setBigInt64(0, js("BigInt")(buf.byteLength))
                    frames.push(h.buffer, pb, l.buffer, buf); packed += 12 + num(pb.length) + num(buf.byteLength)
                }
                for ((rel, file) in files) {
                    frame(rel, awaitJs(file.arrayBuffer()))
                    if (packed > 3000000 || num(frames.length) >= 256) flushBatch()
                }
                flushBatch()
                say("project db /" + str(b.name) + " — " + sent + " docs" + (if (skipped > 0) " ($skipped >3.5MB skipped)" else "") + (if (failed > 0) " · $failed FAILED" else ""))
                addNode("project.list", (num(e.clientX) - num(view.x) - viewport.getBoundingClientRect().left) / num(view.z), (num(e.clientY) - 40 - num(view.y)) / num(view.z))
            } catch (x: CancellationException) { throw x } catch (x: dynamic) { say("upload failed: " + str(x)) }
        }
        return
    }
    if (paths.isEmpty()) { say("nothing mountable in that drop"); return }
    // place side-by-side: column to the right of existing project.mount nodes
    var baseX = (num(e.clientX) - num(view.x) - viewport.getBoundingClientRect().left) / num(view.z)
    var baseY = (num(e.clientY) - 40 - num(view.y)) / num(view.z)
    val mounts = nodes().filter { it.type == "project.mount" }
    if (mounts.isNotEmpty()) { baseX = mounts.maxOf { num(it.x) } + 260; baseY = mounts.minOf { num(it.y) } }
    var wx = baseX; var wy = baseY
    for (p0 in paths) {
        val path = if (p0.startsWith("file://")) decodeURIComponent(p0.replace(Regex("^file://"), "")) else p0
        val n = addNode("project.mount", wx, wy, objOf { it.path = path })
        if (n != null) { launchJs { runAll(str(n.id)) }; wx += 24; wy += 24 }
    }
}

/** the PALETTE: docked drag-source for the served vocabulary + programs and examples */
suspend fun HarnessPatch.buildPalette() {
    val pal = idDyn("palette"); pal.innerHTML = ""
    val qIn = document.createElement("input").asDynamic(); qIn.placeholder = "filter palette…"; pal.appendChild(qIn)
    val body = document.createElement("div").asDynamic(); pal.appendChild(body)
    val progs = ArrayList<dynamic>()
    try {
        val pr = try { fetchJson("/api/panels/presets") } catch (x: CancellationException) { throw x } catch (x: dynamic) { objOf { it.presets = jsArray() } }
        val st = try { fetchJson("/api/panels") } catch (x: CancellationException) { throw x } catch (x: dynamic) { objOf { it.panels = jsArray() } }
        val saved = arr(st.panels).map { str(it.name) }.toSet()
        progs.addAll(arr(st.panels).map { x -> val c = spread(x); c.example = false; c })
        progs.addAll(arr(pr.presets).filter { str(it.name) !in saved }.map { x -> val c = spread(x); c.example = true; c })
    } catch (x: CancellationException) { throw x } catch (x: dynamic) {}
    fun render(f: String) {
        body.innerHTML = ""
        val groups = LinkedHashMap<String, ArrayList<String>>()
        for (t in keysOf(contracts).sorted()) {
            if (f.isNotEmpty() && !t.contains(f) && !str(contracts[t].title).lowercase().contains(f)) continue
            val g = if (t.contains(".")) t.split(".")[0] else "core"
            groups.getOrPut(g) { ArrayList() }.add(t)
        }
        for (g in groups.keys.sorted()) {
            val h = document.createElement("h5").asDynamic(); h.textContent = g; body.appendChild(h)
            for (t in groups.getValue(g)) {
                val c = contracts[t]
                val d = document.createElement("div").asDynamic(); d.className = "pitem"; d.draggable = true
                val kin = arr(c.ins).joinToString("  ") { p2 -> str(p2) + ":" + (kindOf(t, "in", str(p2)) ?: "?") }
                val kout = arr(c.outs).joinToString("  ") { p2 -> str(p2) + ":" + (kindOf(t, "out", str(p2)) ?: "?") }
                d.title = str(c.title) + "\nin   " + kin.ifEmpty { "—" } + "\nout  " + kout.ifEmpty { "—" }
                d.innerHTML = t + "<i>" + str(c.title) + "</i>"
                on(d, "dragstart", { e -> e.dataTransfer.setData("text/x-lcnc-type", t); e.dataTransfer.effectAllowed = "copy" })
                on(d, "click", { addNode(t, (num(viewport.clientWidth) / 2 - num(view.x)) / num(view.z), (num(viewport.clientHeight) / 2 - num(view.y)) / num(view.z)) })
                body.appendChild(d)
            }
        }
        if (progs.isNotEmpty()) {
            val h = document.createElement("h5").asDynamic(); h.textContent = "Programs and examples"; body.appendChild(h)
            for (program in progs) {
                val nm = str(program.name)
                if (f.isNotEmpty() && !nm.lowercase().contains(f)) continue
                val d = document.createElement("div").asDynamic(); d.className = "pitem"; d.draggable = true
                d.textContent = (if (program.example == true) "Example: " else "Program: ") + nm
                val hint = document.createElement("i").asDynamic(); hint.textContent = "drag → program.ref · click → open"; d.append(hint)
                on(d, "dragstart", { e -> e.dataTransfer.setData("text/x-lcnc-program", nm); e.dataTransfer.effectAllowed = "copy" })
                on(d, "click", {
                    val h0 = window.asDynamic().Harness
                    if (program.example == true) h0.openExample(nm, program.document) else h0.select(nm)
                })
                body.appendChild(d)
            }
        }
    }
    on(qIn, "input", { render(str(qIn.value).trim().lowercase()) })
    render("")
}

/** the KEYMUX dialog: name · api address · api link · flags. KEY VALUES NEVER CROSS — env var NAMES point at them. */
suspend fun HarnessPatch.openKeyMuxDlg() {
    var data: dynamic = objOf { it.builtin = jsArray(); it.user = jsArray() }
    try { data = api("GET", "/api/mux/endpoints") } catch (x: CancellationException) { throw x } catch (x: dynamic) {}
    val d = document.createElement("div").asDynamic()
    d.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:99;display:flex;align-items:center;justify-content:center"
    fun flagBox(label: String, on: Boolean, disabled: Boolean, attr: String = "") =
        "<label style=\"display:inline-flex;align-items:center;gap:4px;margin-right:10px;font-size:10px;color:var(--dim)\"><input type=\"checkbox\" " +
            (if (on) "checked " else "") + (if (disabled) "disabled " else "") + attr + ">" + label + "</label>"
    fun s(v: dynamic) = if (truthy(v)) str(v) else ""
    val rows = ArrayList<String>()
    for (b in arr(data.builtin)) {
        rows.add("<div style=\"display:flex;gap:8px;align-items:baseline;padding:5px 0;border-bottom:1px solid var(--line)\">" +
            "<b style=\"font-family:var(--mono);font-size:11px;flex:0 0 175px\">" + str(b.name) + "</b>" +
            "<a href=\"" + s(b.base) + "\" target=\"_blank\" style=\"color:var(--wire);font-size:10px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap\">" + s(b.base) + "/" + s(b.model) + "</a>" +
            flagBox("key", truthy(b.keyPresent), true) + flagBox("discovered", truthy(b.discovered), true) +
            "<span style=\"color:var(--dim);font-size:9px\">" + s(b.envVar) + "</span></div>")
    }
    for (u in arr(data.user)) {
        val f = if (truthy(u.flags)) u.flags else obj()
        rows.add("<div data-uname=\"" + str(u.name) + "\" style=\"display:flex;gap:8px;align-items:baseline;padding:5px 0;border-bottom:1px solid var(--line)\">" +
            "<b style=\"font-family:var(--mono);font-size:11px;flex:0 0 175px;color:var(--graal)\">" + str(u.name) + "</b>" +
            "<a href=\"" + s(u.base) + "\" target=\"_blank\" style=\"color:var(--wire);font-size:10px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap\">" + s(u.base) + "/" + s(u.model) + "</a>" +
            flagBox("enabled", f.enabled != false, false, "data-flag=\"enabled\"") + flagBox("preferred", truthy(f.preferred), false, "data-flag=\"preferred\"") +
            "<span style=\"color:var(--dim);font-size:9px\">" + s(u.envVar) + "</span>" +
            "<s data-del=\"" + str(u.name) + "\" style=\"cursor:pointer;color:var(--err);text-decoration:none\">✕</s></div>")
    }
    d.innerHTML = "<div style=\"background:var(--panel);border:1px solid var(--graal);border-radius:10px;padding:16px 18px;width:660px;max-height:76vh;overflow:auto;color:var(--ink)\">" +
        "<b style=\"color:var(--graal);letter-spacing:.08em\">KEYMUX ENDPOINTS</b> <span style=\"color:var(--dim);font-size:10px\">— key VALUES never cross; env var names point at them</span>" +
        "<div style=\"margin:10px 0\">" + rows.joinToString("") + "</div>" +
        "<div style=\"border-top:1px solid var(--line);padding-top:10px;color:var(--dim);font-size:10px;letter-spacing:.06em\">NEW ENDPOINT</div>" +
        "<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:6px\">" +
        "<span><div style=\"color:var(--dim);font-size:10px\">NAME</div><input data-k=\"name\" placeholder=\"my-provider\" style=\"width:100%\"></span>" +
        "<span><div style=\"color:var(--dim);font-size:10px\">ENV VAR (the key's NAME, never its value)</div><input data-k=\"envVar\" placeholder=\"MY_PROVIDER_API_KEY\" style=\"width:100%\"></span>" +
        "<span><div style=\"color:var(--dim);font-size:10px\">API ADDRESS</div><input data-k=\"base\" placeholder=\"https://api.provider.com/v1\" style=\"width:100%\"></span>" +
        "<span><div style=\"color:var(--dim);font-size:10px\">API LINK · MODEL PATH</div><input data-k=\"model\" placeholder=\"vendor/model-id\" style=\"width:100%\"></span>" +
        "</div><div style=\"margin-top:10px\">" + flagBox("enabled", true, false, "data-k=\"fEnabled\"") + flagBox("preferred", false, false, "data-k=\"fPreferred\"") +
        "<span style=\"float:right\"><button data-k=\"close\">close</button> <button data-k=\"add\" style=\"border-color:var(--ok);color:var(--ok)\">add</button></span></div></div>"
    document.body!!.appendChild(d)
    for (x in arr(d.querySelectorAll("input,select,button,a,s"))) on(x, "pointerdown", { e -> e.stopPropagation() })
    val close = { d.remove() }
    d.onclick = { e: dynamic -> if (e.target === d) close() }
    d.querySelector("[data-k=\"close\"]").onclick = close
    for (el in arr(d.querySelectorAll("[data-del]"))) on(el, "click", {
        launchJs { api("DELETE", "/api/mux/endpoints/" + encodeURIComponent(str(el.dataset.del))); close(); openKeyMuxDlg() }
    })
    for (row in arr(d.querySelectorAll("[data-uname]"))) {
        for (cb in arr(row.querySelectorAll("[data-flag]"))) on(cb, "change", {
            launchJs {
                val u = arr(data.user).firstOrNull { it.name == row.dataset.uname }
                if (u != null) {
                    val flags = spread(if (truthy(u.flags)) u.flags else obj()); flags[cb.dataset.flag] = cb.checked
                    val body = spread(u); body.flags = flags
                    api("POST", "/api/mux/endpoints", body)
                    status("keymux: " + str(u.name) + " flags saved")
                }
            }
        })
    }
    d.querySelector("[data-k=\"add\"]").onclick = {
        launchJs {
            fun g(k: String): dynamic = d.querySelector("[data-k=\"$k\"]")
            val body = obj()
            body.name = str(g("name").value).trim(); body.base = str(g("base").value).trim(); body.model = str(g("model").value).trim(); body.envVar = str(g("envVar").value).trim()
            body.flags = objOf { it.enabled = g("fEnabled").checked; it.preferred = g("fPreferred").checked }
            if (str(body.name).isEmpty()) { g("name").focus() }
            else {
                val r = api("POST", "/api/mux/endpoints", body)
                if (r.verdict == "ok") { close(); openKeyMuxDlg() } else status("keymux: " + str(r.error ?: "refused"))
            }
        }
    }
}

/** preset gallery: presets are OFFERED — loading one puts it on the canvas; installing stays ⇪ store */
suspend fun HarnessPatch.openGallery() {
    val existing = idDyn("gallery")
    if (existing != null) { existing.remove(); return }
    val g: dynamic = document.createElement("div"); g.id = "gallery"
    g.style.cssText = "position:fixed;top:52px;right:12px;width:340px;max-height:70vh;overflow:auto;background:var(--panel,#101620);border:1px solid var(--line,#263348);border-radius:8px;padding:10px;z-index:50;font-size:12px"
    g.innerHTML = "<div style=\"display:flex;justify-content:space-between;margin-bottom:8px\"><b style=\"color:var(--cyan,#57d3ff)\">GALLERY</b><span style=\"cursor:pointer\" onclick=\"this.closest('#gallery').remove()\">✕</span></div><div id=\"galBody\">loading…</div>"
    document.body!!.appendChild(g)
    val body = g.querySelector("#galBody")
    try {
        val pr = try { fetchJson("/api/panels/presets") } catch (x: CancellationException) { throw x } catch (x: dynamic) { objOf { it.presets = jsArray() } }
        val st = try { fetchJson("/api/panels") } catch (x: CancellationException) { throw x } catch (x: dynamic) { objOf { it.panels = jsArray() } }
        val rows = ArrayList<String>()
        /* A preset that needs a provider key is a DIFFERENT PROMISE: the ones that run on nothing go first. */
        fun needsKey(p: dynamic) = Regex("provider key|model provider", RegexOption.IGNORE_CASE).containsMatchIn(str(p.needs ?: ""))
        val all = arr(pr.presets)
        fun card(p: dynamic): String {
            val nn = arr((if (truthy(p.document)) p.document else obj()).nodes).size
            return "<div class=\"galRow\" data-kind=\"preset\" data-name=\"" + str(p.name) + "\" style=\"padding:7px 8px;border:1px solid var(--line,#263348);border-radius:6px;margin:5px 0;cursor:pointer\">" +
                "<div style=\"font-weight:600\">" + escNullable(if (truthy(p.title)) p.title else p.name) + " <span style=\"color:var(--dim,#7f91a9);font-weight:400\">· " + nn + " parts</span></div>" +
                (if (truthy(p.does)) "<div style=\"margin-top:3px\">" + escNullable(p.does) + "</div>" else "") +
                (if (truthy(p.needs)) "<div style=\"margin-top:3px;color:var(--dim,#7f91a9)\"><b>needs</b> " + escNullable(p.needs) + "</div>" else "") +
                (if (truthy(p.see)) "<div style=\"color:var(--dim,#7f91a9)\"><b>you see</b> " + escNullable(p.see) + "</div>" else "") +
                (if (truthy(p.tweakFirst)) "<div style=\"color:var(--dim,#7f91a9)\"><b>change first</b> " + escNullable(p.tweakFirst) + "</div>" else "") +
                "</div>"
        }
        val free = all.filter { !needsKey(it) }; val keyed = all.filter { needsKey(it) }
        rows.add("<div style=\"color:var(--ok,#3ddc84);margin:4px 0;font-weight:600\">runs now · nothing to set up</div>")
        for (p in free) rows.add(card(p))
        if (keyed.isNotEmpty()) {
            rows.add("<div style=\"color:var(--warn,#ffb02e);margin:12px 0 2px;font-weight:600\">needs a provider key</div>")
            rows.add("<div style=\"color:var(--dim,#7f91a9);margin:0 0 4px\">These call a model. If none of your keys answer, that is the provider, " +
                "not the canvas — the daemon records every attempt in <code>brain-errors.jsonl</code>.</div>")
            for (p in keyed) rows.add(card(p))
        }
        rows.add("<div style=\"color:var(--dim,#7f91a9);margin:8px 0 4px\">store — your saved programs</div>")
        val panels = arr(st.panels)
        if (panels.isEmpty()) rows.add("<div style=\"color:var(--dim,#7f91a9)\">store has no programs yet — ⇪ store saves the canvas</div>")
        for (sp in panels) rows.add("<div class=\"galRow\" data-kind=\"store\" data-name=\"" + str(sp.name) + "\" style=\"padding:5px 6px;border:1px solid var(--line,#263348);border-radius:5px;margin:3px 0;cursor:pointer\">" +
            str(sp.name) + " <span style=\"color:var(--dim,#7f91a9)\">· " + str(sp.bytes ?: 0) + " B</span></div>")
        body.innerHTML = rows.joinToString("")
        val gal = g
        for (el in arr(body.querySelectorAll(".galRow"))) on(el, "click", {
            launchJs {
                val name = str(el.dataset.name)
                val h0 = window.asDynamic().Harness
                if (h0 != null) {
                    val example = all.firstOrNull { it.name == name }
                    if (el.dataset.kind == "preset" && example != null) h0.openExample(name, example.document)
                    else h0.select(name)
                    gal.remove(); return@launchJs
                }
                if (el.dataset.kind == "preset") {
                    val hit = all.firstOrNull { it.name == name }; val doc = hit?.document
                    if (truthy(doc)) {
                        load(fromConfix(doc)); panelName().value = name.replace(Regex("^preset-"), ""); syncBoard(name)
                        // Land on the on-ramp: say what to change, and that backing out is safe.
                        status(str(if (truthy(hit.title)) hit.title else name) + " loaded — change first: " + str(if (truthy(hit.tweakFirst)) hit.tweakFirst else "anything") + " · ⌘Z undoes · ⇪ store keeps it")
                    }
                } else {
                    val resp = fetchJs("/api/panels/$name")
                    if (truthy(resp.ok)) { load(awaitJs(resp.json())); panelName().value = name; status("loaded panels/$name"); syncBoard(name) }
                }
                gal.remove(); armSources(); runAll(null)
            }
        })
    } catch (x: CancellationException) { throw x } catch (x: dynamic) { body.textContent = "gallery failed: " + str(x) }
}

// ── RDF mapping interface: the canvas as triples, the vocabulary as triples, and the ALIGNMENT ──
private var rdfTimer = 0
private var rdfNote = ""

fun HarnessPatch.refreshRdf() {
    if (idDyn("rdfPanel") == null) return
    window.clearTimeout(rdfTimer)
    rdfTimer = window.setTimeout({ val t = str(idDyn("rdfPanel")?.dataset?.tab ?: "graph").ifEmpty { "graph" }; launchJs { rdfTab(t) } }, 400)
}

private fun jsonPost(body: dynamic): dynamic = jsonInit("POST", JSON.stringify(body))

suspend fun HarnessPatch.rdfTab(tab: String) {
    val g = idDyn("rdfPanel") ?: return; g.dataset.tab = tab
    for (b in arr(g.querySelectorAll("[data-rt]"))) b.style.borderColor = if (b.dataset.rt == tab) "var(--cyan,#57d3ff)" else "var(--line,#263348)"
    val body = g.querySelector("#rdfBody")
    try {
        if (tab == "graph" || tab == "ontology") {
            val r = if (tab == "graph") fetchJs("/api/lcnc/rdf", jsonPost(toConfix())) else fetchJs("/api/lcnc/rdf")
            val ttl = str(awaitJs(r.text()))
            val n = Regex(" \\.\\s*$", RegexOption.MULTILINE).findAll(ttl).count()
            body.innerHTML = "<div style=\"color:var(--dim,#7f91a9);margin:0 0 6px\">" + n + " triples · " +
                (if (tab == "graph") "this canvas, ports as resources, one <code>lcnc:feeds</code> per cable" else "the vocabulary: node types, kinds, rdfs:subClassOf, in/out ports") + "</div>" +
                "<textarea id=\"rdfText\" spellcheck=\"false\" style=\"width:100%;height:46vh;font:11px/1.35 var(--mono);background:var(--bg);color:var(--ink);border:1px solid var(--line);border-radius:4px;padding:6px;white-space:pre\">" + escNullable(ttl) + "</textarea>" +
                "<div style=\"display:flex;gap:6px;margin-top:6px;flex-wrap:wrap\">" +
                (if (tab == "graph") "<button data-ra=\"apply\" title=\"map the Turtle in the box back onto the canvas\">⇲ apply to canvas</button><button data-ra=\"nal\" title=\"ingest the causal projection: every cable becomes an implication belief\">→ NAL beliefs</button>" else "") +
                "<button data-ra=\"copy\">copy</button></div><div id=\"rdfMsg\" style=\"color:var(--dim,#7f91a9);margin-top:6px\">" + escNullable(rdfNote) + "</div>"
            rdfNote = ""
            for (b in arr(body.querySelectorAll("[data-ra]"))) on(b, "click", {
                launchJs {
                    val msg = body.querySelector("#rdfMsg"); val ttl2 = str(body.querySelector("#rdfText").value)
                    when (str(b.dataset.ra)) {
                        "copy" -> try { awaitJs(window.navigator.asDynamic().clipboard.writeText(ttl2)); msg.textContent = "copied" } catch (x: CancellationException) { throw x } catch (x: dynamic) { msg.textContent = "copy failed: " + str(x) }
                        "apply" -> {
                            val rr = fetchJs("/api/lcnc/rdf/program", jsonInit("POST", ttl2, "text/turtle"))
                            val doc = awaitJs(rr.json())
                            if (!truthy(rr.ok) || truthy(doc.error)) msg.textContent = "apply refused: " + str(doc.error ?: rr.status)
                            else {
                                // load() re-renders this tab (debounced), which would erase the message; park it for that render.
                                rdfNote = "canvas rebuilt from " + arr(doc.nodes).size + " nodes, " + arr(doc.wires).size + " cables"
                                load(fromConfix(doc)); save(); armSources(); msg.textContent = rdfNote
                            }
                        }
                        "nal" -> {
                            val al = fetchJson("/api/lcnc/rdf/align", jsonPost(toConfix()))
                            val rr = fetchJs("/api/beliefs/kg", jsonInit("POST", al.nal ?: "", "text/turtle"))
                            val res = awaitJs(rr.json())
                            msg.textContent = if (truthy(rr.ok)) "minted " + str(res.minted) + " beliefs from " + str(res.statements) + " statements · copulas " + JSON.stringify(res.copulas ?: obj())
                            else "NAL refused: " + str(res.error ?: rr.status)
                        }
                    }
                }
            })
        } else {
            val al = fetchJson("/api/lcnc/rdf/align", jsonPost(toConfix()))
            if (truthy(al.error)) { body.textContent = "align failed: " + str(al.error); return }
            val rows = arrayListOf("<div style=\"color:var(--dim,#7f91a9);margin:0 0 6px\">" + str(al.summary) + "</div>")
            for (n in arr(al.nodes)) {
                val watched = arr(n.watchedBy); val causal = arr(n.causalRules); val facts = arr(n.facts); val feeds = arr(n.feeds)
                rows.add("<div class=\"rdfNode\" data-id=\"" + escNullable(n.id) + "\" style=\"padding:6px 8px;border:1px solid var(--line,#263348);border-radius:6px;margin:5px 0;cursor:pointer\">" +
                    "<div><b>" + escNullable(n.id) + "</b> <span style=\"color:var(--dim)\">a</span> " + escNullable(n.type) + "</div>" +
                    (if (watched.isNotEmpty()) "<div style=\"margin-top:3px\"><span style=\"color:var(--warn,#ffb02e)\">productions</span> " + watched.joinToString(", ") { escNullable(it) } + "</div>" else "<div style=\"margin-top:3px;color:var(--dim)\">no production watches it</div>") +
                    (if (causal.isNotEmpty()) "<div style=\"margin-top:3px\"><span style=\"color:var(--ok,#3ddc84)\">causal rules</span><br>" + causal.joinToString("<br>") { escNullable(it) } + "</div>" else "") +
                    (if (facts.isNotEmpty()) "<div style=\"margin-top:3px\"><span style=\"color:var(--cyan,#57d3ff)\">facts</span><br>" + facts.joinToString("<br>") { escNullable(it) } + "</div>" else "") +
                    (if (feeds.isNotEmpty()) "<div style=\"margin-top:3px;color:var(--dim)\">causes → " + feeds.joinToString(", ") { escNullable(it) } + "</div>" else "") +
                    "</div>")
            }
            val firings = arr(al.firings)
            if (firings.isNotEmpty()) { rows.add("<div style=\"color:var(--dim,#7f91a9);margin:8px 0 2px\">recent causal firings</div>"); for (f in firings) rows.add("<div style=\"font-size:11px\">" + escNullable(f) + "</div>") }
            body.innerHTML = rows.joinToString("")
            for (el in arr(body.querySelectorAll(".rdfNode"))) on(el, "click", {
                val n = node(el.dataset.id)
                if (n != null) { n.el.style.outline = "2px solid var(--cyan,#57d3ff)"; later(1200) { n.el.style.outline = "" } }
            })
        }
    } catch (x: CancellationException) { throw x } catch (x: dynamic) { body.textContent = "rdf failed: " + str(x) }
}

fun HarnessPatch.openRdf() {
    val existing = idDyn("rdfPanel"); if (existing != null) { existing.remove(); return }
    val g: dynamic = document.createElement("div"); g.id = "rdfPanel"
    g.style.cssText = "position:fixed;top:52px;right:12px;width:520px;max-height:80vh;overflow:auto;background:var(--panel,#101620);border:1px solid var(--line,#263348);border-radius:8px;padding:10px;z-index:30;font-size:12px"
    g.innerHTML = "<div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:8px\"><b style=\"color:var(--cyan,#57d3ff)\">RDF</b>" +
        "<span><button data-rt=\"graph\">graph</button> <button data-rt=\"ontology\">ontology</button> <button data-rt=\"align\">align</button></span>" +
        "<span style=\"cursor:pointer\" onclick=\"this.closest('#rdfPanel').remove()\">✕</span></div><div id=\"rdfBody\">loading…</div>"
    document.body!!.appendChild(g)
    for (b in arr(g.querySelectorAll("[data-rt]"))) on(b, "click", { launchJs { rdfTab(str(b.dataset.rt)) } })
    launchJs { rdfTab("graph") }
}
