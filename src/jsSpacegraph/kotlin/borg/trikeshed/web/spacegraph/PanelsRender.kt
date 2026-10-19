package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.lcnc.LcncDomForm
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.parse.reify
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*

/** cursor-tree widget: collapsible tree rendering of any JSON doc; every value drags OUT of it */
fun Panels.renderTree(v: dynamic, key: String, depth: Int): dynamic {
    val d = document.createElement("div").asDynamic(); d.className = "ctree"
    if (depth > 0) d.style.marginLeft = "10px"
    val isObj = v != null && typeOf(v) == "object"
    fun asDrag(el2: dynamic, value: dynamic) {
        el2.draggable = true
        on(el2, "dragstart", { e -> e.stopPropagation(); e.dataTransfer.setData("text/x-lcnc-value", JSON.stringify(value)); e.dataTransfer.effectAllowed = "copy" })
    }
    if (!isObj) {
        val s = document.createElement("span").asDynamic(); s.className = "leaf"; asDrag(s, v)
        s.textContent = (if (key.isNotEmpty()) "$key: " else "") + (if (typeOf(v) == "string") "\"" + str(v) + "\"" else str(v))
        if (v === null) s.style.color = "#b07aff" else if (typeOf(v) == "number") s.style.color = "#ffb02e" else if (typeOf(v) == "string") s.style.color = "#3ddc84"
        s.title = "click to copy this value"
        on(s, "click", { e ->
            e.stopPropagation()
            copyText(str(v))
            s.classList.add("copied"); later(500) { s.classList.remove("copied") }
        })
        d.appendChild(s); return d
    }
    val isArr = isArray(v)
    val entries: List<Pair<String, dynamic>> = if (isArr) arr(v).mapIndexed { i, x -> i.toString() to x } else keysOf(v).map { it to v[it] }
    var open = depth < 2
    val tw = document.createElement("span").asDynamic(); tw.className = "ctree-tw"; tw.textContent = if (open) "▾" else "▸"
    val lb = document.createElement("span").asDynamic()
    lb.textContent = if (key.isNotEmpty()) key + ": " + (if (isArr) "[${entries.size}]" else "{${entries.size}}") else (if (isArr) "Array[${entries.size}]" else "Object{${entries.size}}")
    val hd = document.createElement("span").asDynamic(); hd.append(tw, lb); asDrag(hd, v)
    val kids = document.createElement("div").asDynamic()
    for ((k, value) in entries) kids.appendChild(renderTree(value, k, depth + 1))
    fun sync() { kids.style.display = if (open) "" else "none"; tw.textContent = if (open) "▾" else "▸" }
    sync()
    hd.style.cursor = "pointer"; hd.onclick = { open = !open; sync() }
    d.append(hd, kids); return d
}

fun Panels.copyText(s: String) {
    val settled = CompletableDeferred<Boolean>()
    if (page.submit {
        val invocation = currentCoroutineContext()[Job]
        val copied = withContext(NonCancellable) { settled.await() }
        invocation?.ensureActive()
        if (!copied && !page.closing) fallbackCopy(s)
    } == null) return
    // A cancelled worker can discard queued actions; this bounded child still joins native settlement.
    CoroutineScope(Dispatchers.Main + page.element.supervisor).launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) { settled.await() }
    }
    // Keep writeText and synchronous fallback inside the accepted DOM gesture.
    var pending = false
    try {
        val clip = window.navigator.asDynamic().clipboard
        if (clip != null && clip.writeText != null) {
            clip.writeText(s).then(
                { _: dynamic -> settled.complete(true); Unit },
                { _: dynamic -> settled.complete(false); Unit },
            )
            pending = true
        } else fallbackCopy(s)
    } finally {
        if (!pending) settled.complete(true)
    }
}

fun fallbackCopy(s: String) {
    val ta = document.createElement("textarea").asDynamic(); ta.value = s; ta.style.position = "fixed"; ta.style.opacity = "0"
    document.body!!.appendChild(ta); ta.select()
    try { document.asDynamic().execCommand("copy") } catch (e: dynamic) {}
    ta.remove()
}

fun Panels.setResult(n: dynamic, v: dynamic) {
    var el = n.el.querySelector(".result")
    if (el == null) {
        el = document.createElement("div").asDynamic(); el.className = "result"; n.el.appendChild(el)
        // resizing/selecting a result pane must not bleed into a canvas pan
        on(el, "pointerdown", { e -> e.stopPropagation() })
    }
    // JSON docs render as a cursor-tree; strings stay verbatim
    if (v != null && typeOf(v) == "object") { el.textContent = ""; el.appendChild(renderTree(v, "", 0)) }
    else el.textContent = if (typeOf(v) == "string") v else str(v)
}

/**
 * generic grouped/draggable board renderer — zero domain vocabulary. A drop only DESCRIBES the gesture
 * {itemId,item,from,to} on the node's `move` output; what it MEANS is the wired graph's business.
 */
fun Panels.renderGroupedBoard(n: dynamic) {
    val groups = if (truthy(n._groups)) n._groups else obj(); val meta = n._cols
    val idF = str(p(n, "idField")); val titleF = str(p(n, "titleField")); val subF = str(p(n, "subtitleField")); val badgeF = str(p(n, "badgeField"))
    val cols: List<dynamic> = if (truthy(meta) && num(meta.length) > 0) arr(meta).toList() else keysOf(groups).sorted().map { k -> objOf { it.id = k; it.name = k } }
    var wrap = n.el.querySelector(".kboard")
    if (wrap == null) { wrap = document.createElement("div").asDynamic(); wrap.className = "kboard"; n.el.appendChild(wrap) }
    var status = n.el.querySelector(".kboard-status")
    if (status == null) { status = document.createElement("div").asDynamic(); status.className = "kboard-status"; n.el.appendChild(status) }
    var actions = n.el.querySelector(".kboard-actions")
    if (actions == null) { actions = document.createElement("div").asDynamic(); actions.className = "kboard-actions"; n.el.appendChild(actions) }
    actions.textContent = ""
    if (truthy(n._lastOut?.fields) && truthy(n._lastOut?.events)) {
        val create = document.createElement("button").asDynamic(); create.textContent = "Create item"
        on(create, "pointerdown", { e -> e.stopPropagation() })
        on(create, "click", { e -> e.stopPropagation(); openBoardForm(n, "", null) })
        actions.appendChild(create)
    }
    wrap.textContent = ""
    val total = keysOf(groups).sumOf { num(groups[it].length).toInt() }
    status.textContent = "$total items across ${cols.size} groups"
    for (col in cols) {
        val items = arr(groups[col.id]).sortedBy { if (truthy(it) && it.order != null) num(it.order) else 0.0 }
        val over = truthy(col.wipLimit) && items.size > num(col.wipLimit)
        val c = document.createElement("div").asDynamic(); c.className = "kcol" + (if (over) " over" else "")
        c.dataset.group = col.id
        val h = document.createElement("h6").asDynamic()
        h.innerHTML = "<span>${esc(if (truthy(col.name)) col.name else col.id)}</span><b>${items.size}${if (truthy(col.wipLimit)) "/" + esc(col.wipLimit) else ""}</b>"
        c.appendChild(h)
        for (it in items) {
            val card = document.createElement("div").asDynamic()
            card.className = "kcard" + (if (truthy(it.contested)) " contested" else "")
            card.dataset.jobid = it[idF]
            card.style.touchAction = "none" // pointer drag streams moves on touch too
            val title = it[titleF] ?: it[idF] ?: "?"; val sub = it[subF]; val badge = it[badgeF]
            card.innerHTML = "<div>${esc(title)}</div><div class=\"kid\">${if (sub != null && sub != title) esc(sub) else ""}${if (badge != null) " · " + esc(badge) else ""}</div>"
            // Pointer-event drag, NOT HTML5 DnD: works for mouse/trackpad/touch/pen alike.
            on(card, "pointerdown", { e ->
                e.stopPropagation() // don't pan the canvas while grabbing a card
                if (e.button != 0) return@on
                val captured = JSON.parse<dynamic>(JSON.stringify(it))
                val definition = boardDefinition()
                val sourceId = str(n._lastOut?.events ?: "")
                val sx = num(e.clientX); val sy = num(e.clientY); var ghost: dynamic = null
                fun colUnder(ev: dynamic): dynamic {
                    val t = document.elementFromPoint(num(ev.clientX), num(ev.clientY)).asDynamic()?.closest(".kcol")
                    return if (t != null && truthy(wrap.contains(t))) t else null
                }
                lateinit var onMove: (dynamic) -> Unit
                lateinit var onUp: (dynamic) -> Unit
                var released: () -> Unit = {}
                fun clear() {
                    page.off(window, "pointermove", onMove); page.off(window, "pointerup", onUp); page.off(window, "pointercancel", onUp)
                    for (k in arr(wrap.querySelectorAll(".kcol"))) k.classList.remove("dragover")
                    card.style.opacity = ""
                    ghost?.remove()
                }
                onMove = { ev ->
                    var go = true
                    if (ghost == null) {
                        if (kotlin.math.hypot(num(ev.clientX) - sx, num(ev.clientY) - sy) < 6) go = false // click/dblclick stays intact
                        else {
                            val r = card.getBoundingClientRect()
                            ghost = card.cloneNode(true)
                            ghost.style.cssText = "position:fixed;pointer-events:none;z-index:999;opacity:.88;width:${r.width}px;margin:0"
                            document.body!!.appendChild(ghost)
                            card.style.opacity = ".35"
                        }
                    }
                    if (go) {
                        ghost.style.left = "${num(ev.clientX) + 8}px"; ghost.style.top = "${num(ev.clientY) + 8}px"
                        for (k in arr(wrap.querySelectorAll(".kcol"))) k.classList.remove("dragover")
                        val kc = colUnder(ev); if (kc != null) kc.classList.add("dragover")
                        ev.preventDefault()
                    }
                }
                onUp = { ev ->
                    released(); clear()
                    if (ghost != null) {
                        val kc = colUnder(ev); val to = kc?.dataset?.group
                        if (ev.type != "pointercancel" && truthy(to)) {
                            // the card whose top half the pointer is above becomes the insert-before target
                            var beforeJobId: dynamic = null
                            for (el2 in arr(kc.querySelectorAll(".kcard"))) {
                                if (el2 === card) continue
                                val r2 = el2.getBoundingClientRect()
                                if (num(ev.clientY) < num(r2.top) + num(r2.height) / 2) { beforeJobId = if (truthy(el2.dataset.jobid)) el2.dataset.jobid else null; break }
                            }
                            if (!(to == col.id && beforeJobId == null)) {
                                val st = n.el.querySelector(".kboard-status")
                                if (st != null) st.textContent = str(it[idF]) + ": " + str(col.id) + " → " + str(to) + (if (beforeJobId != null) " (before " + str(beforeJobId) + ")" else "") + " (downstream deciding…)"
                                val mv = obj(); mv.itemId = captured[idF]; mv.item = captured; mv.from = col.id; mv.to = to; mv.beforeJobId = beforeJobId
                                if (sourceId.isNotEmpty()) {
                                    mv.id = str(js("crypto.randomUUID()")); mv.target = n.id; mv.kind = "move"
                                    emitDomEvent(sourceId, mv, definition) { run ->
                                        if (page.closing) return@emitDomEvent
                                        val result = domVerdict(run, str(n.id), str(mv.id))
                                        val text = if (result?.verdict == "committed") "Move committed"
                                            else if (result?.verdict == "rejected") "Move rejected: " + str(result.reason)
                                            else str(run?.notAdmitted ?: "Move outcome unknown; inspect run records before retrying")
                                        n.el.querySelector(".kboard-status")?.textContent = text
                                    }
                                } else {
                                    n._lastMove = mv
                                    admitPulse(str(n.id))
                                }
                            }
                        }
                    }
                }
                page.on(window, "pointermove", onMove); page.on(window, "pointerup", onUp); page.on(window, "pointercancel", onUp)
                released = page.own { clear() }
            })
            on(card, "dblclick", { e -> e.stopPropagation(); openBoardForm(n, str(col.id), it) })
            c.appendChild(card)
        }
        wrap.appendChild(c)
    }
}

/** concentric treesheets: {id,title,parent,columns,rows} sheets; a {sheet:"id"} cell drills in; the crumb climbs out */
fun Panels.renderConcentric(n: dynamic) {
    var wrap = n.el.querySelector(".csheet")
    if (wrap == null) { wrap = document.createElement("div").asDynamic(); wrap.className = "csheet"; n.el.appendChild(wrap) }
    val idx = if (truthy(n._sheets)) n._sheets else obj(); val cur = idx[n._cur]
    if (!truthy(cur)) {
        wrap.innerHTML = if (truthy(n._sheetError)) "<div class=\"kboard-status\" role=\"alert\">" + escA(n._sheetError) + "</div>"
            else if (truthy(n._sheetRequest)) "<div class=\"kboard-status\" role=\"status\">reading retained sheet…</div>"
            else "<div class=\"kboard-status\">no sheets loaded — wire an inline sheet or retained sheet reference and run</div>"
        return
    }
    fun navigate(id: String, href: String, refresh: Boolean = false) {
        n._sheetRequest = null; n._sheetError = null
        if (truthy(idx[id]) && !refresh) { n._cur = id; renderConcentric(n) }
        else if (href.isEmpty()) { n._sheetError = "Sheet is not loaded: $id"; renderConcentric(n) }
        else {
            val request = obj(); n._sheetRequest = request; renderConcentric(n)
            // Navigation and indexes read only, never re-running the graph or document.curate.
            launchJs {
                try {
                    val family = readSheets(href)
                    require(family.any { it.id == id }) { "Referenced sheet is absent: $id" }
                    if (n._sheets !== idx || n._sheetRequest !== request || !truthy(n.el.isConnected)) return@launchJs
                    for (sheet in family) idx[sheet.id] = sheet
                    n._cur = id
                } catch (e: CancellationException) { throw e }
                catch (e: dynamic) {
                    if (n._sheets === idx && n._sheetRequest === request) n._sheetError = failureText(e)
                } finally {
                    if (n._sheets === idx && n._sheetRequest === request) {
                        n._sheetRequest = null
                        if (truthy(n.el.isConnected)) renderConcentric(n)
                    }
                }
            }
        }
    }
    val crumb = ArrayList<dynamic>(); var c: dynamic = cur; var guard = 0
    while (truthy(c) && guard++ < 12) { crumb.add(0, c); c = if (truthy(c.parent)) idx[c.parent] else null }
    var html = "<div class=\"crumbrow\">" + crumb.mapIndexed { i, s ->
        "<span class=\"seg" + (if (i == crumb.size - 1) " here" else "") + "\" data-sheet=\"" + escA(s.id) + "\">" + escA(if (truthy(s.title)) s.title else s.id) + "</span>"
    }.joinToString(" ▸ ") + "</div>"
    if (truthy(cur.parent) && !truthy(idx[cur.parent]) && truthy(cur.parentHref)) html +=
        "<button data-sheet=\"" + escA(cur.parent) + "\" data-href=\"" + escA(cur.parentHref) + "\">▴ document</button>"
    if (truthy(n._sheetError)) html += "<div class=\"kboard-status\" role=\"alert\">" + escA(n._sheetError) + "</div>"
    if (truthy(n._sheetRequest)) html += "<div class=\"kboard-status\" role=\"status\">reading retained sheet…</div>"
    if (truthy(cur.notice)) html += "<div class=\"kboard-status\">" + escA(cur.notice) + "</div>"
    for (view in arr(cur.views)) if (view.sheet != cur.id) html +=
        "<button data-sheet=\"" + escA(view.sheet) + "\" data-href=\"" + escA(view.href) + "\">" + escA(view.title) + "</button>"
    if (truthy(cur.truncated)) html += "<div class=\"kboard-status\">partial sheet · " + arr(cur.rows).size +
        " displayed rows" + (if (cur.offset != null) " · view offset " + escA(cur.offset) else "") +
        (if (cur.sourceRows != null) " of " + escA(cur.sourceRows) else "") +
        (if (truthy(cur.limit)) " · " + escA(cur.limit) else "") + "</div>"
    for ((field, label) in listOf("previousHref" to "Previous rows", "nextHref" to "Next rows",
        "previousGroupsHref" to "Previous index groups", "nextGroupsHref" to "Next index groups")) {
        if (truthy(cur[field])) html += "<button data-sheet=\"" + escA(cur.id) + "\" data-href=\"" +
            escA(cur[field]) + "\" data-page=\"true\">" + label + "</button>"
    }
    val kids = keysOf(idx).map { idx[it] }.filter { it.parent == cur.id }
    if (kids.isNotEmpty()) html += "<div class=\"ringrow\">" + kids.joinToString("") { s ->
        "<span class=\"dagchip\" data-sheet=\"" + escA(s.id) + "\">▤ " + escA(if (truthy(s.title)) s.title else s.id) + " <i>" + arr(s.rows).size + "</i></span>"
    } + "</div>"
    val columns = arr(cur.columns)
    if (arr(cur.rows).isEmpty()) html += "<div class=\"kboard-status\">empty sheet — " + columns.joinToString(" · ") { escA(it.name) } + "</div>"
    else html += "<div class=\"tablewrap\"><table><tr>" + (if (cur.sourceOrdinals != null) "<th>Source row</th>" else "") +
        columns.joinToString("") { col -> "<th title=\"" + escA(col.type ?: "") + "\">" + escA(col.name) + "</th>" } + "</tr>" +
        arr(cur.rows).mapIndexed { ordinal, row ->
            "<tr>" + (if (cur.sourceOrdinals != null) "<td>" + escA(cur.sourceOrdinals[ordinal]) + "</td>" else "") + arr(row).joinToString("") { cell ->
                if (truthy(cell) && typeOf(cell) == "object" && truthy(cell.sheet)) {
                    val child = idx[cell.sheet]
                    "<td><button class=\"dagchip\" data-sheet=\"" + escA(cell.sheet) + "\"" +
                        (if (truthy(cell.href)) " data-href=\"" + escA(cell.href) + "\"" else "") +
                        ">▤ " + escA(if (truthy(child)) (if (truthy(child.title)) child.title else child.id) else cell.sheet) + "</button></td>"
                } else "<td class=\"leafcell\" title=\"click to copy\">" + escA(if (cell == null) "" else str(cell)) + "</td>"
            } + "</tr>"
        }.joinToString("") + "</table></div>"
    val orch = n._orch
    if (truthy(orch) && truthy(orch.lanes)) {
        val odd = arr(orch.edges).filter { truthy(it.mode) && it.mode != "NORMAL" }
        html += "<div class=\"ringrow orch\">" + arr(orch.lanes).joinToString("→") { l -> "<span class=\"dagchip\" title=\"" + escA(l.role ?: "") + "\">" + escA(l.id) + "</span>" } +
            (if (odd.isNotEmpty()) " <i>" + odd.joinToString("·") { escA(str(it.mode).lowercase()) } + "</i>" else "") + "</div>"
    }
    wrap.innerHTML = html
    if (truthy(cur.href) && arr(cur.indexColumns).isNotEmpty()) {
        val controls = document.createElement("details").asDynamic()
        controls.setAttribute("aria-label", "Retained cursor indexes")
        controls.open = arr(cur.axes).isNotEmpty()
        val summary = document.createElement("summary").asDynamic()
        summary.textContent = "Y indexes · " + str(cur.selectedRows) + " / " + str(cur.sourceRows) + " rows"
        controls.appendChild(summary)
        val note = document.createElement("p").asDynamic()
        note.textContent = "Select up to three fields for a composite index. Independent indexes intersect (AND). Child sheets have separate row scopes. Reads only; no model call or approval."
        controls.appendChild(note)
        val axes = arr(cur.axes).toList()
        val selections = arr(cur.selections).toList()
        fun select(nextAxes: List<dynamic>, nextSelections: List<dynamic>) {
            val href = str(cur.href)
            val url: dynamic = js("new URL(href, window.location.href)")
            url.searchParams.set("offset", "0"); url.searchParams.set("groupOffset", "0")
            url.searchParams.set("axes", JSON.stringify(nextAxes.toTypedArray()))
            url.searchParams.set("selections", JSON.stringify(nextSelections.toTypedArray()))
            navigate(str(cur.id), str(url.pathname) + str(url.search), true)
        }
        fun button(label: String, action: () -> Unit) {
            val b = document.createElement("button").asDynamic(); b.textContent = label
            controls.appendChild(b); on(b, "click", { e -> e.stopPropagation(); action() })
        }
        if (axes.size < 4) {
            val fields = document.createElement("select").asDynamic(); fields.multiple = true; fields.size = 4
            fields.setAttribute("aria-label", "Fields for new cursor index")
            for (field in arr(cur.indexColumns)) {
                val option = document.createElement("option").asDynamic(); option.value = field; option.textContent = field
                fields.appendChild(option)
            }
            controls.appendChild(fields)
            button("Add field index") {
                val chosen = arr(fields.selectedOptions).map { str(it.value) }
                if (chosen.size !in 1..3) { n._sheetError = "Select one to three fields"; renderConcentric(n) }
                else select(axes + listOf(chosen.toTypedArray().asDynamic()), selections)
            }
        }
        arr(cur.indexes).forEachIndexed { a, descriptor ->
            val label = document.createElement("label").asDynamic(); label.style.display = "block"
            label.textContent = arr(descriptor.fields).joinToString(" + ") { str(it) } + " · " + str(descriptor.groupCount) + " groups "
            val groups = document.createElement("select").asDynamic()
            groups.setAttribute("aria-label", "Group for retained cursor index $a")
            fun option(value: Int, text: String) {
                val o = document.createElement("option").asDynamic(); o.value = value.toString(); o.textContent = text; groups.appendChild(o)
            }
            option(-1, "All groups")
            val selection = selections.firstOrNull { num(it[0]).toInt() == a }
            val current = if (selection == null) null else num(selection[1]).toInt()
            if (current != null && arr(descriptor.groups).none { num(it.group).toInt() == current }) option(current, "Group $current (another page)")
            for (group in arr(descriptor.groups)) option(num(group.group).toInt(), JSON.stringify(group.key) + " · " + str(group.count) + " rows")
            groups.value = (current ?: -1).toString()
            label.appendChild(groups); controls.appendChild(label)
            on(groups, "change", {
                val group = str(groups.value).toInt()
                val remaining = selections.filter { num(it[0]).toInt() != a }
                select(axes, if (group < 0) remaining else remaining + listOf(arrayOf(a, group).asDynamic()))
            })
        }
        if (axes.isNotEmpty()) button("Clear indexes") { select(emptyList(), emptyList()) }
        wrap.insertBefore(controls, wrap.querySelector(".tablewrap"))
    }
    documentRulePageActions(wrap, cur) {
        n._sheets === idx && idx[n._cur] === cur && n._sheetRequest == null && truthy(n.el.isConnected)
    }
    for (el in arr(wrap.querySelectorAll("[data-sheet]"))) on(el, "click", { e ->
        e.stopPropagation()
        navigate(str(el.dataset.sheet), str(el.dataset.href ?: ""), el.dataset.page == "true")
    })
    for (el in arr(wrap.querySelectorAll(".leafcell"))) on(el, "click", { e ->
        e.stopPropagation(); copyText(str(el.textContent))
        el.classList.add("copied"); later(500) { el.classList.remove("copied") }
    })
    for (el in arr(wrap.querySelectorAll("*"))) on(el, "pointerdown", { e -> e.stopPropagation() })
}

/** heap continent: bytes-by-class as a slice-and-dice treemap off /api/graal/heap */
fun Panels.renderHeap(n: dynamic) {
    var cv = n.el.querySelector("canvas.heapmap")
    if (cv == null) {
        cv = document.createElement("canvas").asDynamic(); cv.className = "heapmap"; cv.width = 664; cv.height = 150
        cv.style.cssText = "display:block;margin:0 8px 4px;border:1px solid var(--line);border-radius:3px"; n.el.appendChild(cv)
        cv.tabIndex = 0; cv.setAttribute("role", "button"); cv.setAttribute("aria-label", "Inspect allocation sites by class")
        val cvRef = cv
        fun inspect(tile: dynamic) {
            if (tile != null) {
                val ctx = obj(); ctx.bytes = tile.row.bytes; ctx.kind = if (n._heapLane == "histogram") "live" else "allocation"
                window.asDynamic().AllocationInspector.open(tile.row["class"], ctx)
            }
        }
        on(cv, "pointerdown", { e -> e.stopPropagation() })
        on(cv, "click", { e ->
            e.stopPropagation(); val r = cvRef.getBoundingClientRect()
            val x = (num(e.clientX) - num(r.left)) * num(cvRef.width) / num(r.width); val y = (num(e.clientY) - num(r.top)) * num(cvRef.height) / num(r.height)
            inspect(arr(n._heapTiles).firstOrNull { t -> x >= num(t.x) && x < num(t.x) + num(t.w) && y >= num(t.y) && y < num(t.y) + num(t.h) })
        })
        on(cv, "keydown", { e -> if (e.key == "Enter" || e.key == " ") { e.preventDefault(); e.stopPropagation(); inspect(arr(n._heapTiles).firstOrNull()) } })
        val st = document.createElement("div").asDynamic(); st.className = "kboard-status heapstat"; n.el.appendChild(st)
    }
    val h = if (truthy(n._heap)) n._heap else obj(); val lane = if (truthy(p(n, "lane"))) str(p(n, "lane")) else "allocation"
    val hasHist = arr(h.rows).isNotEmpty()
    val rows = arr(if (lane == "histogram" && hasHist) h.rows else h.allocation)
    n._heapTiles = jsArray(); n._heapLane = if (lane == "histogram" && hasHist) "histogram" else "allocation"
    fun bytes(r: dynamic) = if (truthy(r.bytes)) num(r.bytes) else 0.0
    val top = rows.sortedByDescending { bytes(it) }.take(24)
    val total = top.sumOf { bytes(it) }
    val x2 = cv.getContext("2d"); x2.clearRect(0, 0, cv.width, cv.height)
    val stat = n.el.querySelector(".heapstat")
    if (total == 0.0) { if (stat != null) stat.textContent = "heap: no $lane data yet"; return }
    fun hue(s: String): Int { var v = 0.0; for (ch in s) v = (v * 31 + ch.code) % 4294967296.0; return (v % 360).toInt() }
    // slice-and-dice: alternate axis each split, area ∝ bytes
    fun tile(items: List<dynamic>, x: Double, y: Double, w: Double, hgt: Double, horiz: Boolean) {
        if (items.isEmpty() || w < 1 || hgt < 1) return
        if (items.size == 1) {
            val r = items[0]
            val t = obj(); t.row = r; t.x = x; t.y = y; t.w = w; t.h = hgt; n._heapTiles.push(t)
            x2.fillStyle = "hsl(" + hue(str(r["class"])) + " 45% 32%)"; x2.fillRect(x + 0.5, y + 0.5, w - 1, hgt - 1)
            if (w > 60 && hgt > 12) {
                x2.fillStyle = "#d8dce6"; x2.font = "9px monospace"
                val short = str(r["class"]).replace(Regex("^.*\\."), "").take(kotlin.math.floor(w / 6).toInt())
                x2.fillText(short, x + 3, y + 10)
                if (hgt > 24) x2.fillText(fixed(num(r.bytes) / 1048576, 1) + "M", x + 3, y + 21)
            }
            return
        }
        val sum = items.sumOf { num(it.bytes) }; var acc = 0.0; var cut = 0
        for (i in items.indices) { acc += num(items[i].bytes); if (acc >= sum / 2) { cut = i + 1; break } }
        if (cut == 0 || cut == items.size) cut = maxOf(1, items.size shr 1)
        val a = items.subList(0, cut); val b = items.subList(cut, items.size); val fa = a.sumOf { num(it.bytes) } / sum
        val jsRound = { v: Double -> num(js("Math.round")(v)) }
        if (horiz) { tile(a, x, y, jsRound(w * fa), hgt, !horiz); tile(b, x + jsRound(w * fa), y, w - jsRound(w * fa), hgt, !horiz) }
        else { tile(a, x, y, w, jsRound(hgt * fa), !horiz); tile(b, x, y + jsRound(hgt * fa), w, hgt - jsRound(hgt * fa), !horiz) }
    }
    tile(top, 0.0, 0.0, num(cv.width), num(cv.height), true)
    if (stat != null) stat.textContent = lane + ": " + fixed(total / 1048576, 1) + "MB across top " + top.size + " classes" +
        (if (n._heapLane == "histogram") " · " + str(if (truthy(h.classes)) h.classes else 0) + " classes " + fixed((if (truthy(h.bytes)) num(h.bytes) else 0.0) / 1048576, 1) + "MB live" else " · sampled since start")
}

internal fun fixed(v: Double, digits: Int): String = js("v.toFixed(digits)").unsafeCast<String>()

/** quota legion standings: reactor roster × ledger, usable-first */
fun Panels.renderStandings(n: dynamic) {
    var wrap = n.el.querySelector(".csheet")
    if (wrap == null) { wrap = document.createElement("div").asDynamic(); wrap.className = "csheet"; n.el.appendChild(wrap) }
    val rows = arr(n._standings)
    if (rows.isEmpty()) { wrap.innerHTML = "<div class=\"kboard-status\">no standings — legion empty or reactor context absent</div>"; return }
    wrap.innerHTML = "<div class=\"tablewrap\"><table><tr><th>key</th><th>provider</th><th>spent</th><th>limit</th><th>util</th><th></th></tr>" +
        rows.joinToString("") { s ->
            "<tr><td>" + esc(s.keyId) + "</td><td>" + esc(s.provider ?: "") + "</td><td>" + esc(s.spent) + "</td>" +
                "<td>" + (if (num(s.limit) > 0) esc(s.limit) else "∞") + "</td>" +
                "<td><span class=\"utilbar\"><i style=\"width:" + str(js("Math.round")((if (truthy(s.utilization)) num(s.utilization) else 0.0) * 100)) + "%\"></i></span></td>" +
                "<td style=\"color:" + (if (truthy(s.usable)) "var(--ok)" else "var(--err)") + "\">" + (if (truthy(s.usable)) "usable" else if (truthy(s.exhausted)) "exhausted" else "benched") + "</td></tr>"
        } + "</table></div>"
    for (el in arr(wrap.querySelectorAll("*"))) on(el, "pointerdown", { e -> e.stopPropagation() })
}

fun Panels.renderPanelsList(n: dynamic, panels: dynamic) {
    var wrap = n.el.querySelector(".kboard")
    if (wrap == null) { wrap = document.createElement("div").asDynamic(); wrap.className = "kboard"; wrap.style.flexWrap = "wrap"; n.el.appendChild(wrap) }
    wrap.textContent = ""
    val list = arr(panels)
    if (list.isEmpty()) {
        val e = document.createElement("div").asDynamic(); e.className = "kboard-status"; e.textContent = "store has no programs yet — ⇪ store to save this one"; wrap.appendChild(e); return
    }
    for (pnl in list) {
        val chip = document.createElement("div").asDynamic(); chip.className = "ref-dive"; chip.style.margin = "4px"
        chip.textContent = "＋ " + str(pnl.name)
        chip.title = "click to add a program.ref(" + str(pnl.name) + ") node to this canvas"
        on(chip, "click", {
            addNode("program.ref", num(n.x) + num(n.el.offsetWidth) + 40, num(n.y))
            status("added program.ref(" + str(pnl.name) + ") — double-click its dive-in strip to descend into it")
            val added = nodes().last()
            added.params.name = pnl.name
            val inp = added.el.querySelector(".params input"); if (inp != null) inp.value = pnl.name
            save()
        })
        wrap.appendChild(chip)
    }
}

fun Panels.renderProgramRef(n: dynamic) {
    var dive = n.el.querySelector(".ref-dive")
    if (dive == null) { dive = document.createElement("div").asDynamic(); dive.className = "ref-dive"; n.el.appendChild(dive) }
    val name = p(n, "name")
    dive.textContent = if (truthy(name)) "⤵ dive into " + str(name) else "⤵ dive in (set a name first)"
    dive.onclick = { if (truthy(name)) launchJs { diveInto(str(name)) } }
}

/** generic controls: press fires the button's downstream chain */
fun Panels.renderButtonNode(n: dynamic) {
    var b = n.el.querySelector(".bigbtn")
    if (b == null) {
        b = document.createElement("button").asDynamic(); b.className = "bigbtn"
        on(b, "pointerdown", { e -> e.stopPropagation() })
        on(b, "click", { n._pressedAt = now(); admitPulse(str(n.id)) })
        n.el.appendChild(b)
    }
    b.textContent = "▸ " + str(if (truthy(p(n, "label"))) p(n, "label") else "press")
}

/** a slider drag streams its value down its wire live */
fun Panels.renderSliderNode(n: dynamic) {
    var w = n.el.querySelector(".sliderwrap")
    if (w == null) {
        w = document.createElement("div").asDynamic(); w.className = "sliderwrap"
        val r = document.createElement("input").asDynamic(); r.type = "range"
        val lab = document.createElement("span").asDynamic(); lab.className = "slval"
        on(r, "pointerdown", { e -> e.stopPropagation() })
        on(r, "input", { n._sval = r.value; lab.textContent = r.value; admitPulse(str(n.id)) })
        w.appendChild(r); w.appendChild(lab); n.el.appendChild(w); n._srange = r; n._slab = lab
    }
    val r = n._srange
    r.min = if (truthy(p(n, "min"))) p(n, "min") else 0; r.max = if (truthy(p(n, "max"))) p(n, "max") else 1; r.step = if (truthy(p(n, "step"))) p(n, "step") else 0.01
    if (n._sval === undefined) r.value = if (truthy(p(n, "value"))) p(n, "value") else 0
    n._slab.textContent = r.value
}

/** the media element rides the node; audio vs video follows the url; `ended` re-fires downstream */
fun Panels.ensureMedia(n: dynamic, url: String): dynamic {
    val isVideo = Regex("\\.(mp4|webm|mov|m4v|mkv)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
    val want = if (isVideo) "VIDEO" else "AUDIO"
    if (truthy(n._media) && n._media.tagName != want) { n._media.remove(); n._media = null }
    if (!truthy(n._media)) {
        val m = document.createElement(if (isVideo) "video" else "audio").asDynamic()
        m.controls = true; m.preload = "metadata"
        m.style.cssText = if (isVideo) "display:block;width:100%;max-width:640px;margin:0 8px 8px;border-radius:6px;background:#000"
        else "display:block;width:calc(100% - 16px);margin:0 8px 8px"
        on(m, "pointerdown", { e -> e.stopPropagation() })
        on(m, "ended", { n._endedAt = now(); admitPulse(str(n.id)) })
        for (event in arrayOf("play", "pause", "seeked")) on(m, event, {
            if (!programRunning) admitPulse(str(n.id))
        })
        n.el.appendChild(m); n._media = m
    }
    return n._media
}

/** Apply exactly the media operation in this receipt, never a graph or retained command. */
suspend fun Panels.renderMedia(n: dynamic, rendered: dynamic) {
    val url = str(rendered.url)
    val media = ensureMedia(n, url)
    if (media.getAttribute("data-src") != url) { media.setAttribute("data-src", url); media.src = url }
    if (rendered.volume != null) {
        val volume = num(rendered.volume)
        media.volume = if (volume.isFinite()) volume.coerceIn(0.0, 1.0) else 0.0
    }
    if (rendered.rewind != null) media.currentTime = 0
    if (rendered.stop != null) media.pause()
    if (rendered.play != null) try { awaitJs(media.play()) }
    catch (e: CancellationException) { throw e }
    catch (e: dynamic) { setResult(n, "Autoplay blocked — press the element's play control once") }
}

/** Browser observation, not an inferred execution result. Non-finite media metadata is absent. */
fun mediaState(media: dynamic): dynamic = objOf {
    it.url = media.getAttribute("data-src"); it.playing = !truthy(media.paused)
    val time = num(media.currentTime); val duration = num(media.duration)
    if (time.isFinite()) it.t = time
    if (duration.isFinite()) it.duration = duration
}

fun Panels.boardDefinition(): LcncProgram =
    LcncProgramConfix.fromJson(str(panelName().value).ifBlank { "canvas" }, JSON.stringify(toConfix()))

/** Capture at the DOM boundary, then use the ordinary retained-program admission. */
fun Panels.emitDomEvent(sourceId: String, event: dynamic, definition: LcncProgram, done: (dynamic) -> Unit) {
    fun refusal(): String? = when {
        page.closing -> "Page is closing; gesture was not admitted"
        programRunning || programAdmission != null -> "Gesture not admitted while awaiting another receipt"
        !LcncProgramConfix.sameDefinition(definition, boardDefinition()) -> "Program changed; reopen the form after Run"
        node(sourceId)?.type != "dom.events" || truthy(node(sourceId)?._parentScope) -> "Missing root DOM event source"
        pulseReceipt == null -> "Choose Run or Live first; gesture was not admitted"
        else -> null
    }
    val refused = refusal()
    if (refused != null) { done(objOf { it.notAdmitted = refused }); return }
    val pulse = objOf { it.nodeId = sourceId; it.outputs = objOf { out -> out.event = JSON.parse<dynamic>(JSON.stringify(event)) } }
    val admission = reserveProgram(pulse = pulse, source = node(sourceId))
    if (admission == null) done(objOf { it.notAdmitted = statusText() })
    else queueProgram(admission, done)
}

fun domVerdict(run: dynamic, target: String, eventId: String): dynamic {
    if (run?.ok != true || run.status != "completed") return null
    val outputs = run.outputs ?: return null
    val results = outputs[target]?.result
    if (!isArray(results)) return null
    val matches = arr(results).filter { it?.idempotencyKey == eventId && str(it.verdict) in setOf("committed", "rejected") }
    return if (matches.size == 1) matches.single() else null
}

/** The form edits the authored scalar parameter schema, not a second board API. */
fun Panels.openBoardForm(n: dynamic, group: String, item: dynamic) {
    if (page.closing || document.querySelector("[data-board-form]") != null) return
    val fields = n._lastOut?.fields ?: return
    val sourceId = str(n._lastOut?.events ?: "")
    if (sourceId.isEmpty()) return
    val specs = try { LcncDomForm.fields(reify(JSON.stringify(fields))) }
        catch (e: dynamic) { status("Form schema refused: " + failureText(e)); return }
    val definition = boardDefinition()
    val captured = if (item == null) null else JSON.parse<dynamic>(JSON.stringify(item))
    val d = document.createElement("div").asDynamic()
    d.setAttribute("data-board-form", str(n.id)); d.setAttribute("role", "dialog"); d.setAttribute("aria-modal", "true")
    d.setAttribute("aria-label", if (captured == null) "Create item" else "Edit item")
    d.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:99;display:flex;align-items:center;justify-content:center"
    val box = document.createElement("div").asDynamic()
    box.style.cssText = "background:var(--panel);border:1px solid var(--graal);border-radius:8px;padding:18px;width:380px;color:var(--ink);max-height:85vh;overflow:auto"
    val title = document.createElement("h3"); title.textContent = if (captured == null) "Create item" else "Edit item"
    box.appendChild(title)
    val inputs = mutableMapOf<String, dynamic>()
    val original = mutableMapOf<String, String>()
    for ((name, spec) in specs) {
        val label = document.createElement("label").asDynamic()
        label.textContent = spec.ph.ifBlank { name }; label.style.cssText = "display:block;margin:10px 0"
        val input = document.createElement(if (spec.opts.isNotEmpty()) "select" else if (spec.ta) "textarea" else "input").asDynamic()
        input.setAttribute("data-field", name); input.style.cssText = "display:block;width:100%;margin-top:4px"
        if (spec.opts.isNotEmpty()) for (value in spec.opts) {
            val option = document.createElement("option").asDynamic(); option.value = value; option.textContent = value
            input.appendChild(option)
        }
        val initial = if (captured == null) spec.v else str(captured[name] ?: "")
        input.value = initial; original[name] = initial; inputs[name] = input
        label.appendChild(input); box.appendChild(label)
    }
    val message = document.createElement("p").asDynamic(); message.setAttribute("role", "status"); box.appendChild(message)
    val cancel = document.createElement("button").asDynamic(); cancel.textContent = "Cancel"
    val apply = document.createElement("button").asDynamic(); apply.textContent = if (captured == null) "Create" else "Apply"
    box.appendChild(cancel); box.appendChild(apply); d.appendChild(box); document.body!!.appendChild(d)
    var busy = false
    var disposed = false
    val bindings = mutableListOf<() -> Unit>()
    var release: () -> Unit = {}
    fun dispose() {
        if (disposed) return
        disposed = true
        while (bindings.isNotEmpty()) bindings.removeAt(bindings.lastIndex).invoke()
        release()
        d.remove()
    }
    fun bind(target: dynamic, type: String, handler: (dynamic) -> Unit) {
        page.on(target, type, handler)
        bindings.add { page.off(target, type, handler) }
    }
    release = page.own { dispose() }
    fun close() { if (!busy) dispose() }
    bind(cancel, "click", { close() })
    bind(d, "click", { e -> if (e.target === d) close() })
    bind(d, "pointerdown", { e -> e.stopPropagation() })
    bind(d, "keydown", { e -> if (e.key == "Escape") close() })
    bind(apply, "click", {
        if (disposed || page.closing || busy || truthy(apply.disabled)) return@bind
        val values = obj()
        for ((name, spec) in specs) {
            val value = str(inputs.getValue(name).value)
            if ((!spec.optional && value.isBlank()) || (spec.opts.isNotEmpty() && value !in spec.opts)) {
                message.textContent = "Required or invalid value: " + spec.ph.ifBlank { name }
                inputs.getValue(name).focus(); return@bind
            }
            if (captured == null || value != original[name]) values[name] = value
        }
        if (keysOf(values).isEmpty()) { message.textContent = "No changes; nothing was submitted"; return@bind }
        // All mutable form values and the opening item/revision are captured before queue admission.
        val event = objOf {
            it.id = str(js("crypto.randomUUID()")); it.target = n.id; it.kind = if (captured == null) "create" else "edit"
            it.item = captured; it.group = group; it.fields = values
        }
        busy = true; apply.disabled = true; cancel.disabled = true
        message.textContent = "Awaiting a committed or rejected verdict…"
        emitDomEvent(sourceId, event, definition) { run ->
            if (disposed || page.closing) return@emitDomEvent
            busy = false; cancel.disabled = false
            val verdict = domVerdict(run, str(n.id), str(event.id))
            when {
                verdict?.verdict == "committed" -> dispose()
                verdict?.verdict == "rejected" -> message.textContent =
                    "Rejected: " + str(verdict.reason ?: "command refused") + ". Close and reopen before retrying."
                run?.notAdmitted != null -> { message.textContent = run.notAdmitted; apply.disabled = false }
                else -> message.textContent = "Outcome unknown. Inspect run records before retrying; no automatic retry."
            }
        }
    })
    inputs.values.firstOrNull()?.focus()
}

// ── fractal dive: a program.ref node is a window onto another graph at the SAME zoom/pan surface ──
fun Panels.crumbSync() {
    val el = byId("crumb")
    if (diveStack.isEmpty()) { el.style.display = "none"; el.textContent = ""; return }
    el.style.display = "flex"
    el.textContent = ""
    val root = document.createElement("span").asDynamic(); root.className = "seg"; root.textContent = "⌂ root"
    on(root, "click", { popTo(0) })
    el.appendChild(root)
    diveStack.forEachIndexed { i, entry ->
        val sep = document.createElement("span").asDynamic(); sep.className = "sep"; sep.textContent = " ▸ "; el.appendChild(sep)
        val seg = document.createElement("span").asDynamic(); seg.className = "seg"; seg.textContent = entry.first
        if (i < diveStack.size - 1) on(seg, "click", { popTo(i + 1) })
        else seg.style.color = "var(--ink)"
        el.appendChild(seg)
    }
}

suspend fun Panels.diveInto(name: String) {
    val resp = fetchJs("/api/panels/" + encodeURIComponent(name))
    if (!truthy(resp.ok)) { status("dive failed: no such program '$name'"); return }
    val data = awaitJs(resp.json())
    val previous = serialize()
    if (!load(data)) return
    diveStack.add(name to previous)
    crumbSync()
    panelName().value = name
    status("diving into $name — ⌂ root or a breadcrumb segment to climb back out")
    syncBoard(name)
}

fun Panels.popTo(depth: Int) {
    if (depth >= diveStack.size) return
    val target = diveStack[depth]
    if (!load(target.second)) return
    diveStack = diveStack.subList(0, depth).toMutableList()
    crumbSync()
    panelName().value = if (depth == 0) "" else diveStack[depth - 1].first
}
