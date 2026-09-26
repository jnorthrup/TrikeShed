package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

/** cursor-tree widget: collapsible tree rendering of any JSON doc; every value drags OUT of it */
fun renderTree(v: dynamic, key: String, depth: Int): dynamic {
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

fun copyText(s: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null && clip.writeText != null) clip.writeText(s).catch { _: dynamic -> fallbackCopy(s) }
    else fallbackCopy(s)
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
    wrap.textContent = ""
    val total = keysOf(groups).sumOf { num(groups[it].length).toInt() }
    status.textContent = "$total items across ${cols.size} groups"
    for (col in cols) {
        val items = arr(groups[col.id]).sortedBy { if (truthy(it) && it.order != null) num(it.order) else 0.0 }
        val over = truthy(col.wipLimit) && items.size > num(col.wipLimit)
        val c = document.createElement("div").asDynamic(); c.className = "kcol" + (if (over) " over" else "")
        c.dataset.group = col.id
        val h = document.createElement("h6").asDynamic()
        h.innerHTML = "<span>${if (truthy(col.name)) col.name else col.id}</span><b>${items.size}${if (truthy(col.wipLimit)) "/" + str(col.wipLimit) else ""}</b>"
        val plus = document.createElement("s").asDynamic(); plus.textContent = "＋"; plus.title = "new card (modal — kanban.submit)"
        plus.style.cssText = "cursor:pointer;color:var(--ok);text-decoration:none;margin-left:6px"
        on(plus, "pointerdown", { e -> e.stopPropagation() })
        on(plus, "click", { e -> e.stopPropagation(); openCardModal(n, str(col.id), null) })
        h.appendChild(plus)
        c.appendChild(h)
        for (it in items) {
            val card = document.createElement("div").asDynamic()
            card.className = "kcard" + (if (truthy(it.contested)) " contested" else "")
            card.dataset.jobid = it[idF]
            card.style.touchAction = "none" // pointer drag streams moves on touch too
            val title = it[titleF] ?: it[idF] ?: "?"; val sub = it[subF]; val badge = it[badgeF]
            card.innerHTML = "<div>$title</div><div class=\"kid\">${if (sub != null && sub != title) sub else ""}${if (badge != null) " · " + str(badge) else ""}</div>"
            // Pointer-event drag, NOT HTML5 DnD: works for mouse/trackpad/touch/pen alike.
            on(card, "pointerdown", { e ->
                e.stopPropagation() // don't pan the canvas while grabbing a card
                if (e.button != 0) return@on
                val sx = num(e.clientX); val sy = num(e.clientY); var ghost: dynamic = null
                fun colUnder(ev: dynamic): dynamic { val t = document.elementFromPoint(num(ev.clientX), num(ev.clientY)).asDynamic(); return t?.closest(".kcol") }
                lateinit var onMove: (dynamic) -> Unit
                lateinit var onUp: (dynamic) -> Unit
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
                    off(window, "pointermove", onMove); off(window, "pointerup", onUp); off(window, "pointercancel", onUp)
                    for (k in arr(wrap.querySelectorAll(".kcol"))) k.classList.remove("dragover")
                    card.style.opacity = ""
                    if (ghost != null) {
                        ghost.remove()
                        val kc = colUnder(ev); val to = kc?.dataset?.group
                        if (truthy(to)) {
                            // the card whose top half the pointer is above becomes the insert-before target
                            var beforeJobId: dynamic = null
                            for (el2 in arr(kc.querySelectorAll(".kcard"))) {
                                if (el2 === card) continue
                                val r2 = el2.getBoundingClientRect()
                                if (num(ev.clientY) < num(r2.top) + num(r2.height) / 2) { beforeJobId = if (truthy(el2.dataset.jobid)) el2.dataset.jobid else null; break }
                            }
                            if (!(to == col.id && beforeJobId == null)) {
                                // Optimistic local move AT the pointed position; the next tick reconciles truth.
                                val fromArr = if (truthy(n._groups[col.id])) n._groups[col.id] else jsArray()
                                val idx = arr(fromArr).indexOfFirst { x -> x[idF] == it[idF] }
                                if (idx >= 0) {
                                    val moved = fromArr.splice(idx, 1)[0]
                                    if (!truthy(n._groups[to])) n._groups[to] = jsArray()
                                    val dst = n._groups[to]
                                    val at = if (beforeJobId != null) arr(dst).indexOfFirst { x -> x[idF] == beforeJobId } else -1
                                    if (at >= 0) dst.splice(at, 0, moved) else dst.push(moved)
                                    arr(dst).forEachIndexed { i, x -> if (truthy(x) && typeOf(x) == "object") x.order = i }
                                    renderGroupedBoard(n)
                                }
                                val st = n.el.querySelector(".kboard-status")
                                if (st != null) st.textContent = str(it[idF]) + ": " + str(col.id) + " → " + str(to) + (if (beforeJobId != null) " (before " + str(beforeJobId) + ")" else "") + " (downstream deciding…)"
                                val mv = obj(); mv.itemId = it[idF]; mv.item = it; mv.from = col.id; mv.to = to; mv.beforeJobId = beforeJobId
                                n._lastMove = mv
                                launchJs { runAll(str(n.id)) } // this node's own downstream chain sees the gesture
                            }
                        }
                    }
                }
                on(window, "pointermove", onMove); on(window, "pointerup", onUp); on(window, "pointercancel", onUp)
            })
            on(card, "dblclick", { e -> e.stopPropagation(); openCardModal(n, str(col.id), it) }) // modal CRUD
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
    if (!truthy(cur)) { wrap.innerHTML = "<div class=\"kboard-status\">no sheets yet — wire kanban.activeSheets in and run</div>"; return }
    val crumb = ArrayList<dynamic>(); var c: dynamic = cur; var guard = 0
    while (truthy(c) && guard++ < 12) { crumb.add(0, c); c = if (truthy(c.parent)) idx[c.parent] else null }
    var html = "<div class=\"crumbrow\">" + crumb.mapIndexed { i, s ->
        "<span class=\"seg" + (if (i == crumb.size - 1) " here" else "") + "\" data-sheet=\"" + escA(s.id) + "\">" + escA(if (truthy(s.title)) s.title else s.id) + "</span>"
    }.joinToString(" ▸ ") + "</div>"
    val kids = keysOf(idx).map { idx[it] }.filter { it.parent == cur.id }
    if (kids.isNotEmpty()) html += "<div class=\"ringrow\">" + kids.joinToString("") { s ->
        "<span class=\"dagchip\" data-sheet=\"" + escA(s.id) + "\">▤ " + escA(if (truthy(s.title)) s.title else s.id) + " <i>" + arr(s.rows).size + "</i></span>"
    } + "</div>"
    val columns = arr(cur.columns)
    if (arr(cur.rows).isEmpty()) html += "<div class=\"kboard-status\">empty sheet — " + columns.joinToString(" · ") { escA(it.name) } + "</div>"
    else html += "<div class=\"tablewrap\"><table><tr>" + columns.joinToString("") { col -> "<th title=\"" + escA(col.type ?: "") + "\">" + escA(col.name) + "</th>" } + "</tr>" +
        arr(cur.rows).joinToString("") { row ->
            "<tr>" + arr(row).joinToString("") { cell ->
                if (truthy(cell) && typeOf(cell) == "object" && truthy(cell.sheet)) {
                    val child = idx[cell.sheet]
                    "<td><span class=\"dagchip\" data-sheet=\"" + escA(cell.sheet) + "\">▤ " + escA(if (truthy(child)) (if (truthy(child.title)) child.title else child.id) else cell.sheet) + "</span></td>"
                } else "<td class=\"leafcell\" title=\"click to copy\">" + escA(if (cell == null) "" else str(cell)) + "</td>"
            } + "</tr>"
        } + "</table></div>"
    val orch = n._orch
    if (truthy(orch) && truthy(orch.lanes)) {
        val odd = arr(orch.edges).filter { truthy(it.mode) && it.mode != "NORMAL" }
        html += "<div class=\"ringrow orch\">" + arr(orch.lanes).joinToString("→") { l -> "<span class=\"dagchip\" title=\"" + escA(l.role ?: "") + "\">" + escA(l.id) + "</span>" } +
            (if (odd.isNotEmpty()) " <i>" + odd.joinToString("·") { escA(str(it.mode).lowercase()) } + "</i>" else "") + "</div>"
    }
    wrap.innerHTML = html
    for (el in arr(wrap.querySelectorAll("[data-sheet]"))) on(el, "click", { e -> e.stopPropagation(); n._cur = el.dataset.sheet; renderConcentric(n) })
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
        on(b, "click", { n._pressedAt = now(); launchJs { runAll(str(n.id)) } })
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
        on(r, "input", { n._sval = r.value; lab.textContent = r.value; launchJs { runAll(str(n.id)) } })
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
        on(m, "ended", { n._endedAt = now(); launchJs { runAll(str(n.id)) } })
        n.el.appendChild(m); n._media = m
    }
    return n._media
}

/**
 * The ring executes IN the daemon. A source OUTSIDE the ring is replicated into the posted document
 * as a scope.in carrying its already-computed value (the inward warm base).
 */
suspend fun Panels.runScope(n: dynamic, i: dynamic): dynamic {
    val guard = i["when?"]
    if (guard == false || guard == "false") return obj()
    val args = if (truthy(i["args?"]) && typeOf(i["args?"]) == "object") i["args?"] else obj()
    val named = p(n, "program")
    val descIds = HashSet<String>()
    fun walk(m: dynamic) { for (c in arr(m.children)) { descIds.add(str(c.id)); walk(c) } }
    walk(n)
    val intra = arr(graph.wires).filter { w -> str(w.from[0]) in descIds && str(w.to[0]) in descIds && wireKindOk(w) }
    val inbound = arr(graph.wires).filter { w -> str(w.from[0]) !in descIds && str(w.to[0]) in descIds && w.from[0] != n.id && wireKindOk(w) }
    val synth = ArrayList<dynamic>(); val synthWires = ArrayList<dynamic>(); val seen = HashSet<String>()
    for (w in inbound) {
        val key = str(w.from[0]) + "·" + str(w.from[1])
        if (seen.add(key)) {
            val src = node(w.from[0])
            var v: dynamic = if (src != null && truthy(src._lastOut)) src._lastOut[w.from[1]] else undefined
            if (v === undefined) continue
            if (typeOf(v) == "object") v = JSON.stringify(v)
            val params = obj(); params.name = "__in_$key"; params["default"] = str(v)
            val sn = obj(); sn.id = key; sn.type = "scope.in"; sn.params = params
            synth.add(sn)
        }
        synthWires.add(wire(key, "value", w.to[0], w.to[1]))
    }
    val body = obj()
    if (truthy(named)) { body.program = named; body.inputs = args }
    else {
        body.name = (if (truthy(panelName().value)) str(panelName().value) else "ring") + ":" + str(n.id)
        val doc = obj(); doc.nodes = (synth + arr(n.children).map { nodeDoc(it) }).toTypedArray(); doc.wires = (intra + synthWires).toTypedArray()
        body.document = doc; body.inputs = args
    }
    val r = api("POST", "/api/lcnc/run", body)
    if (r.ok != true) throw PatchRefusal(if (truthy(r.error)) r.error else "ring run failed")
    for (c in arr(n.children)) { // the warm base lands on the bands
        val out = if (truthy(r.outputs)) r.outputs[c.id] else undefined
        if (out !== undefined && truthy(c.el)) {
            c.el.classList.remove("err", "running"); c.el.classList.add("ok")
            if (truthy(out) && keysOf(out).isNotEmpty()) setResult(c, out)
        }
    }
    // daemon shape: a ring's outputs are its yields PLUS the composed map
    val returns = if (truthy(r.returns)) r.returns else obj()
    val out = spread(returns); out.returns = returns
    return out
}

/** modal CRUD for board cards — create via kanban.submit, move via kanban.move: the daemon's own verbs */
fun Panels.openCardModal(n: dynamic, colId: String, item: dynamic) {
    val isNew = item == null
    val cols = (if (truthy(n._cols) && num(n._cols.length) > 0) arr(n._cols).map { str(it.id) } else keysOf(if (truthy(n._groups)) n._groups else obj()).toList()).sorted()
    val d = document.createElement("div").asDynamic()
    d.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:99;display:flex;align-items:center;justify-content:center"
    d.innerHTML = "<div style=\"background:var(--panel);border:1px solid var(--graal);border-radius:8px;padding:16px 18px;width:380px;color:var(--ink)\">" +
        "<b style=\"color:var(--graal)\">" + (if (isNew) "new card" else "card " + str(item.id) + " · rev " + str(item.revision ?: "—")) + "</b>" +
        "<div style=\"margin:10px 0 3px;color:var(--dim);font-size:10px\">TITLE</div><input data-cm=\"title\" style=\"width:100%\">" +
        "<div style=\"margin:8px 0 3px;color:var(--dim);font-size:10px\">PRIORITY</div><select data-cm=\"pri\"><option>0</option><option>1</option><option>2</option></select>" +
        (if (isNew) "" else "<div style=\"margin:8px 0 3px;color:var(--dim);font-size:10px\">COLUMN</div><select data-cm=\"col\">" + cols.joinToString("") { c -> "<option" + (if (c == colId) " selected" else "") + ">" + c + "</option>" } + "</select>") +
        "<div style=\"margin-top:14px;display:flex;gap:8px;justify-content:flex-end\"><button data-cm=\"cancel\">cancel</button><button data-cm=\"ok\" style=\"border-color:var(--ok);color:var(--ok)\">" + (if (isNew) "create" else "apply") + "</button></div></div>"
    document.body!!.appendChild(d)
    fun el(s: String): dynamic = d.querySelector("[data-cm=\"$s\"]")
    el("title").value = if (!isNew) (item.title ?: "") else ""
    el("pri").value = str(if (!isNew) (item.priority ?: 2) else 2)
    val close = { d.remove() }
    el("cancel").onclick = close; d.onclick = { e: dynamic -> if (e.target === d) close() }
    for (x in arr(d.querySelectorAll("input,select,button"))) on(x, "pointerdown", { e -> e.stopPropagation() })
    el("ok").onclick = {
        launchJs {
            if (isNew) {
                val title = str(el("title").value).trim()
                if (title.isEmpty()) { el("title").focus(); return@launchJs }
                val params = obj(); params.jobId = ""; params.title = title; params.priority = el("pri").value; params.idempotencyKey = "panel-modal-" + str(now())
                val body = obj(); body.type = "kanban.submit"; body.inputs = obj(); body.params = params
                val r = api("POST", "/api/lcnc/run", body)
                // ok:true only means the RUNNER ran — the store's verdict is outputs.accepted.
                val acc = truthy(r.ok) && truthy(r.outputs) && r.outputs.accepted != false
                status(if (acc) "card landed: " + title + " (" + str(r.outputs.jobId ?: "") + ")"
                else "submit REFUSED: " + str(if (truthy(r.outputs) && truthy(r.outputs.reason)) r.outputs.reason else r.error ?: JSON.stringify(r).take(120)))
            } else {
                val to = str(el("col").value)
                if (to.isNotEmpty() && to != str(item.status ?: colId)) {
                    val cmd = obj(); cmd.jobId = item.id; cmd.toColumn = to; cmd.expectedRevision = item.revision
                    cmd.idempotencyKey = "panel-modal-" + str(item.id) + "-" + str(item.revision ?: 0) + "-" + to
                    val inputs = obj(); inputs.command = cmd; inputs["command?"] = cmd
                    val body = obj(); body.type = "kanban.move"; body.params = obj(); body.inputs = inputs
                    val r = api("POST", "/api/lcnc/run", body)
                    status(if (truthy(r.ok)) "moved " + str(item.id) + " → " + to else "move failed: " + str(r.error ?: JSON.stringify(r)))
                }
            }
            close(); runAll(null)
        }
    }
    later(0) { el("title").focus() }
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
    diveStack.add(name to serialize())
    crumbSync()
    panelName().value = name
    load(data)
    status("diving into $name — ⌂ root or a breadcrumb segment to climb back out")
    syncBoard(name)
}

fun Panels.popTo(depth: Int) {
    if (depth >= diveStack.size) return
    val target = diveStack[depth]
    diveStack = diveStack.subList(0, depth).toMutableList()
    crumbSync()
    panelName().value = if (depth == 0) "" else diveStack[depth - 1].first
    load(target.second)
}
