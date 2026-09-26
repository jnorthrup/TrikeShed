package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.js.Date
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Oroboros Board (kanban.html). Talks only to the module's claimed routes:
 *   GET  /api/board, POST /api/invoke, POST /api/lcnc/run (kanban.move), POST /api/board/import,
 *   GET  /blackboard/facts?since=<seq> (SSE), GET /blackboard/board (epoch probe).
 */
object KanbanPage {
    private val LANES = Kanban.LANES
    private var lastSeq: dynamic = -1
    private var lastPayload = ""
    // A re-render mid-drag destroys the dragged element and kills the gesture.
    private var dragLive = false
    private var deferredBoard: dynamic = null

    private fun q(s: String): HTMLElement = document.querySelector(s) as HTMLElement
    private val boardEl: HTMLElement by lazy { q("#board") }
    private fun cssEscape(s: String): String = js("CSS.escape")(s) as String
    private fun esc(v: dynamic): String = Kanban.escapeHtml(str(v))
    private fun now(): Double = window.performance.now()

    private fun dragBegan() { dragLive = true }
    private fun dragEnded() {
        dragLive = false
        if (deferredBoard != null) { val b = deferredBoard; deferredBoard = null; render(b) }
    }

    private var toastTimer = 0
    private fun toast(msg: String, ms: Int = 2600) {
        val t = q("#toast")
        t.textContent = msg
        t.classList.add("show")
        window.clearTimeout(toastTimer)
        toastTimer = setTimeout(ms) { t.classList.remove("show") }
    }

    private fun randomId(): String {
        val b = js("new Uint8Array(6)")
        js("crypto").getRandomValues(b)
        val out = StringBuilder()
        for (i in 0 until 6) out.append((b[i] as Int).toString(16).padStart(2, '0'))
        return out.toString()
    }

    private class ApiResult(val status: Int, val body: dynamic)

    private suspend fun api(path: String, init: org.w3c.fetch.RequestInit? = null): ApiResult {
        val r = fetchResponse(path, init)
        var body: dynamic = null
        try { body = r.jsonValue() } catch (_: Throwable) { /* non-JSON error page */ }
        return ApiResult(r.status.toInt(), body)
    }

    private suspend fun refresh(force: Boolean = false) {
        try {
            val res = api("/api/board")
            val body = res.body
            if (res.status != 200 || !truthy(body)) return
            // An older board never replaces a newer one.
            if (isNumber(body.sequence) && (body.sequence as Double) < (lastSeq as Number).toDouble()) return
            // NARS attention scores drift every poll; rounding them in the compare key stops needless re-renders.
            val replacer: (String, dynamic) -> dynamic = { k, v -> if (k == "attention" && isNumber(v)) round((v as Double) * 20) / 20 else v }
            val payload = JSON.stringify(body, replacer)
            if (!force && payload == lastPayload) return
            lastPayload = payload
            lastSeq = body.sequence
            diffBoard(body)
            if (dragLive) { deferredBoard = body; return }
            render(body)
        } catch (_: Throwable) {
            // daemon momentarily away (restart / hotswap) — keep the last render
        }
    }

    // Leading + trailing edge at 150 ms with an in-flight guard.
    private var refreshTimer: Int? = null
    private var refreshInFlight = false
    private var refreshPending = false

    private fun scheduleRefresh() {
        if (refreshTimer != null) { refreshPending = true; return }
        launchPage { runRefresh() }
        refreshTimer = setTimeout(Kanban.REFRESH_DEBOUNCE_MS) {
            refreshTimer = null
            if (refreshPending) { refreshPending = false; scheduleRefresh() }
        }
    }

    private suspend fun runRefresh() {
        if (refreshInFlight) { refreshPending = true; return }
        refreshInFlight = true
        try { refresh() } finally {
            refreshInFlight = false
            if (refreshPending && refreshTimer == null) { refreshPending = false; scheduleRefresh() }
        }
    }

    private class Flash(val kind: String, val startedAt: Double, val until: Double) { var timer = 0 }
    private class Burst(val n: Int, val until: Double, val timer: Int)

    private val flashes = HashMap<String, Flash>()
    private val laneBurst = HashMap<String, Burst>()
    private var prevItems: Map<String, KanbanNode>? = null
    private val commitAt = HashMap<String, Double>()

    private fun node(i: dynamic): KanbanNode = KanbanNode(
        id = str(i.id),
        parent = if (truthy(i.parent)) str(i.parent) else null,
        dependencies = jsArray(i.dependencies).map { str(it) },
        order = if (i.order == null) 0.0 else (i.order as Number).toDouble(),
        status = strOrEmpty(i.status),
        owner = if (truthy(i.owner)) str(i.owner) else null,
        revision = strOrEmpty(i.revision),
    )

    private fun liveItems(board: dynamic): List<dynamic> = jsArray(board.items).filter { it.status != "archived" }

    private fun diffBoard(board: dynamic) {
        val items = liveItems(board)
        val next = LinkedHashMap<String, KanbanNode>()
        for (i in items) { val n = node(i); next[n.id] = n }
        val prev = prevItems
        if (prev == null) { prevItems = next; return }
        class Change(val id: String, val kind: String, val lane: String)
        val changed = ArrayList<Change>()
        for (n in next.values) {
            val kind = Kanban.changeKind(prev[n.id], n) ?: continue
            changed.add(Change(n.id, kind, n.status))
        }
        prevItems = next
        if (changed.isEmpty()) return
        // Stagger 120 ms apart; past 8 slots they share the last slot and the lane wears "+N".
        val overflow = LinkedHashMap<String, Int>()
        changed.forEachIndexed { i, c ->
            val slot = min(i, Kanban.STAGGER_SLOTS)
            if (i >= Kanban.STAGGER_SLOTS) overflow[c.lane] = (overflow[c.lane] ?: 0) + 1
            flash(c.id, c.kind, (slot * Kanban.STAGGER_MS).toDouble())
        }
        val t = now()
        for ((lane, n) in overflow) {
            val until = t + Kanban.STAGGER_SLOTS * Kanban.STAGGER_MS + Kanban.FLASH_MS
            laneBurst[lane]?.let { window.clearTimeout(it.timer) }
            val timer = window.setTimeout({
                laneBurst.remove(lane)
                boardEl.querySelector(".lane[data-lane=\"" + cssEscape(lane) + "\"] h2 .burst")?.remove()
            }, (until - t + 20).toInt())
            laneBurst[lane] = Burst(n, until, timer)
        }
        if (changed.size >= 2) frameRow(changed.size, changed.map { commitAt[it.id] })
    }

    private fun flash(id: String, kind: String, delayMs: Double) {
        val old = flashes[id]
        if (old != null) window.clearTimeout(old.timer)
        val startedAt = now() + delayMs
        val entry = Flash(kind, startedAt, startedAt + Kanban.FLASH_MS)
        entry.timer = window.setTimeout({
            flashes.remove(id)
            val el = cardById(id)
            if (el != null) { el.classList.remove("flash", "flash-$kind"); el.style.animationDelay = "" }
        }, (delayMs + Kanban.FLASH_MS + 20).toInt())
        flashes[id] = entry
        val el = cardById(id)
        if (el != null) {
            if (old != null) { el.classList.remove("flash", "flash-" + old.kind); el.offsetWidth } // restart, not resume
            applyFlash(el, entry)
        }
    }

    private fun applyFlash(el: HTMLElement, entry: Flash) {
        val elapsed = now() - entry.startedAt
        if (elapsed > Kanban.FLASH_MS) return
        el.classList.add("flash", "flash-" + entry.kind)
        el.style.animationDelay = (-elapsed).asDynamic().toFixed(0) as String + "ms"
    }

    private fun cardById(id: String): HTMLElement? = boardEl.querySelector(".card[data-id=\"" + cssEscape(id) + "\"]") as HTMLElement?

    private fun render(board: dynamic) {
        q("#seq").textContent = "seq " + str(board.sequence)
        val byCol = LinkedHashMap<String, MutableList<dynamic>>()
        for (lane in LANES) byCol[lane] = ArrayList()
        var archived = 0
        val byId = LinkedHashMap<String, KanbanNode>()
        val raw = HashMap<String, dynamic>()
        val all = jsArray(board.items)
        for (it in all) {
            if (it.status == "archived") { archived++; continue }
            val n = node(it)
            byId[n.id] = n
            raw[n.id] = it
            byCol.getOrPut(strOrEmpty(it.status)) { ArrayList() }.add(it)
        }
        fun ord(v: dynamic): Double = if (v.order == null) 0.0 else (v.order as Number).toDouble()
        for (lane in LANES) byCol.getValue(lane).sortWith { a, b -> ord(a).compareTo(ord(b)) }
        val colMeta = HashMap<String, dynamic>()
        for (c in jsArray(board.columns)) colMeta[str(c.id)] = c
        val localeCompare: (String, String) -> Int = { a, b -> (a.asDynamic().localeCompare(b) as Number).toInt() }
        val tree = KanbanTree(byId, localeCompare)

        boardEl.textContent = ""
        val total = all.size - archived
        if (total == 0) {
            val hint = document.createElement("div") as HTMLElement
            hint.className = "empty-board"
            hint.innerHTML = "The board is empty. Type into a lane below to add the first card, or use <b>Import a plan…</b> to turn a bullet list into cards."
            boardEl.appendChild(hint)
        }
        val t = now()
        for (lane in LANES) {
            val meta: dynamic = colMeta[lane]
            val name = if (meta != null) str(meta.name) else lane
            val wipLimit: dynamic = if (meta != null) meta.wipLimit else null
            val laneItems = byCol.getValue(lane)
            val laneEl = document.createElement("div") as HTMLElement
            laneEl.className = "lane"
            laneEl.setAttribute("data-lane", lane)

            val h = document.createElement("h2") as HTMLElement
            val wip = if (wipLimit != null)
                "<span class=\"wip ${if (laneItems.size >= (wipLimit as Number).toDouble()) "full" else ""}\">${laneItems.size}/${str(wipLimit)}</span>" else ""
            val burst = laneBurst[lane]
            if (burst != null && burst.until < t) laneBurst.remove(lane)
            val burstBadge = if (burst != null && burst.until >= t)
                "<span class=\"burst\" title=\"${burst.n} more cards changed in this frame than the 120 ms stagger can show one by one\">+${burst.n}</span>" else ""
            h.innerHTML = "$name <span class=\"count\">${laneItems.size}</span>$burstBadge$wip"
            laneEl.appendChild(h)

            val cards = document.createElement("div") as HTMLElement
            cards.className = "cards"
            for (n in tree.laneOrder(laneItems.map { byId.getValue(str(it.id)) })) cards.appendChild(cardEl(raw.getValue(n.id), tree, lane))
            laneEl.appendChild(cards)

            val addrow = document.createElement("div") as HTMLElement
            addrow.className = "addrow"
            val input = document.createElement("input") as HTMLInputElement
            input.placeholder = "Add a card…"
            val addBtn = document.createElement("button") as HTMLButtonElement
            addBtn.textContent = "Add"
            addBtn.title = "Add a card to $name"
            addBtn.disabled = true
            val submit = {
                val title = input.value.trim()
                if (title.length >= 3) launchPage { addCard(title, lane, input) }
            }
            input.addEventListener("input", { addBtn.disabled = input.value.trim().length < 3 })
            input.addEventListener("keydown", { e: Event -> if ((e as KeyboardEvent).key == "Enter") submit() })
            addBtn.addEventListener("click", { submit() })
            addrow.appendChild(input)
            addrow.appendChild(addBtn)
            laneEl.appendChild(addrow)

            laneEl.addEventListener("dragover", { e: Event -> e.preventDefault(); laneEl.classList.add("drop-ok") })
            laneEl.addEventListener("dragleave", { laneEl.classList.remove("drop-ok") })
            laneEl.addEventListener("drop", { e: Event ->
                e.preventDefault()
                laneEl.classList.remove("drop-ok")
                val data = e.asDynamic().dataTransfer.getData("text/plain") as String
                if (data.isNotEmpty()) {
                    val d: dynamic = JSON.parse<dynamic>(data)
                    launchPage { moveCard(str(d.id), d.revision, strOrEmpty(d.from), lane, null) }
                }
            })
            boardEl.appendChild(laneEl)
        }
        if (archived > 0) q("#seq").textContent = (q("#seq").textContent ?: "") + " · $archived archived"
    }

    private fun cardEl(it: dynamic, tree: KanbanTree, lane: String): HTMLElement {
        val id = str(it.id)
        val el = document.createElement("div") as HTMLElement
        el.className = "card"
        el.setAttribute("data-id", id)
        el.style.setProperty("touch-action", "none")
        val heat = if (isNumber(it.attention) && (it.attention as Double) > 0)
            "<span class=\"heat\" title=\"attention\">${if (truthy(it.contested)) "⚡" else "●"} ${((it.attention as Double) * 100).asDynamic().toFixed(0) as String}%</span>" else ""
        val owner = if (truthy(it.owner)) "<span class=\"owner\">${esc(it.owner)}</span>" else ""
        val tagList = jsArray(it.tags)
        val tags = if (tagList.isNotEmpty())
            "<div class=\"tags\">" + tagList.joinToString("") { t ->
                val s = str(t)
                val patch = s.startsWith("patch:")
                val label = if (patch) s.substring(6) else s
                "<span class=\"tag${if (patch) " patch" else ""}\"${if (patch) " title=\"the change that closed this card\"" else ""}>${Kanban.escapeHtml(label)}</span>"
            } + "</div>" else ""
        val depList = jsArray(it.dependencies)
        val deps = if (depList.isNotEmpty()) "<div class=\"deps\" title=\"waits on\">↳ ${depList.joinToString(", ") { d -> esc(d) }}</div>" else ""
        val parent = tree.parentOf[id]
        val kids = tree.childrenOf[id].orEmpty()
        var chip = ""
        var tally = ""
        if (parent != null || kids.isNotEmpty()) {
            el.classList.add("family")
            el.style.borderLeftColor = "hsl(${KanbanTree.hue(tree.rootOf(id))} 55% 55%)"
        }
        if (parent != null) {
            val parentItem = tree.byId[parent]
            val sameLane = parentItem != null && parentItem.status == lane
            if (sameLane) el.classList.add("child")
            chip = "<span class=\"parentchip\" title=\"${Kanban.escapeHtml(parent)}\">↑ parent ${Kanban.escapeHtml(Kanban.shortId(parent))}" +
                (if (sameLane) "" else " · " + Kanban.escapeHtml(parentItem?.status ?: "?")) + "</span>"
        }
        if (kids.isNotEmpty()) {
            val n = kids.size
            val done = kids.count { k -> k.status == "done" }
            val blocked = kids.count { k -> k.status == "blocked" }
            val blockedNote = if (blocked != 0) " · $blocked blocked" else ""
            tally = if (done < n) "<div class=\"kids${if (blocked != 0) " blocked" else ""}\">waiting on $done/$n children$blockedNote</div>"
            else if (it.status == "todo") "<div class=\"kids ready\">all $n done → ready pending</div>"
            else "<div class=\"kids\">$n/$n children done</div>"
        }
        el.innerHTML = "<div class=\"t\">${esc(if (truthy(it.title)) it.title else it.id)}</div>\n    $tags$deps$chip$tally\n" +
            "    <div class=\"meta\">$owner$heat<span>r${str(it.revision)}</span></div>\n" +
            "    <button class=\"archive\" title=\"Archive this card\" aria-label=\"Archive this card\">×</button>"
        val revision: dynamic = it.revision
        val from = strOrEmpty(it.status)
        el.querySelector(".archive")!!.addEventListener("click", {
            // there is no "archive" verb in the reducer — archiving IS a move to the archived column
            launchPage { moveCard(id, revision, from, "archived", null) }
        })
        flashes[id]?.let { applyFlash(el, it) }
        // Pointer-event drag, NOT HTML5 DnD: works identically for mouse, trackpad, touch, and pen.
        el.addEventListener("pointerdown", { ev: Event ->
            val e = ev as PointerEvent
            if (e.button.toInt() != 0 || (e.target as Element).closest(".archive") != null) return@addEventListener
            val sx = e.clientX
            val sy = e.clientY
            var ghost: HTMLElement? = null
            fun laneUnder(p: PointerEvent): HTMLElement? = document.elementFromPoint(p.clientX.toDouble(), p.clientY.toDouble())?.closest(".lane") as HTMLElement?
            lateinit var onMove: (Event) -> Unit
            lateinit var onUp: (Event) -> Unit
            onMove = { mv: Event ->
                val m = mv as PointerEvent
                var go = true
                if (ghost == null) {
                    if (hypot((m.clientX - sx).toDouble(), (m.clientY - sy).toDouble()) < 6) go = false // click stays a click
                    else {
                        dragBegan()
                        el.classList.add("dragging")
                        val g = el.cloneNode(true) as HTMLElement
                        val r = el.getBoundingClientRect()
                        g.style.cssText = "position:fixed;pointer-events:none;z-index:999;opacity:.88;width:${r.width}px;margin:0;"
                        g.className = "card"
                        document.body!!.appendChild(g)
                        ghost = g
                    }
                }
                if (go) {
                    ghost!!.style.left = "${m.clientX + 8}px"
                    ghost!!.style.top = "${m.clientY + 8}px"
                    val lanes = document.querySelectorAll(".lane")
                    for (i in 0 until lanes.length) (lanes.item(i) as Element).classList.remove("drop-ok")
                    laneUnder(m)?.classList?.add("drop-ok")
                    m.preventDefault()
                }
            }
            onUp = { up: Event ->
                val u = up as PointerEvent
                window.removeEventListener("pointermove", onMove)
                window.removeEventListener("pointerup", onUp)
                window.removeEventListener("pointercancel", onUp)
                val lanes = document.querySelectorAll(".lane")
                for (i in 0 until lanes.length) (lanes.item(i) as Element).classList.remove("drop-ok")
                el.classList.remove("dragging")
                val g = ghost
                if (g != null) {
                    g.remove()
                    val target = laneUnder(u)
                    dragEnded()
                    val targetLane = target?.getAttribute("data-lane")
                    if (target != null && !targetLane.isNullOrEmpty()) {
                        val before = beforeTargetIn(target, u.clientY.toDouble(), id)
                        launchPage { moveCard(id, revision, from, targetLane, before) }
                    }
                }
            }
            window.addEventListener("pointermove", onMove)
            window.addEventListener("pointerup", onUp)
            window.addEventListener("pointercancel", onUp)
        })
        return el
    }

    private suspend fun addCard(title: String, lane: String, input: HTMLInputElement) {
        val hex = randomId()
        val b: dynamic = obj()
        b.type = "submit"; b.jobId = "card-$hex"; b.title = title; b.idempotencyKey = "ui#$hex"
        val res = api("/api/invoke", requestInit("POST", jsonHeaders(), JSON.stringify(b)))
        val body = res.body
        if (res.status == 202 && truthy(body) && (body.accepted as? Number ?: 0).toDouble() >= 1) {
            input.value = ""
            refresh(true)
            // new cards land in triage; if the user typed into another lane, walk it over
            if (lane != "triage") {
                val fresh = jsArray(JSON.parse<dynamic>(lastPayload).items).firstOrNull { i -> i.id == "card-$hex" }
                if (fresh != null) moveCard(str(fresh.id), fresh.revision, strOrEmpty(fresh.status), lane, null)
            }
        } else {
            val first: dynamic = if (body != null && body.results != null) body.results[0] else null
            val reason: dynamic = first?.reason
            toast("Couldn't add the card: " + (if (truthy(reason)) str(reason) else "server said no"))
        }
    }

    private suspend fun moveCard(id: String, revision: dynamic, from: String, toLane: String, beforeJobId: String?) {
        if (from == toLane && beforeJobId.isNullOrEmpty()) return
        val command: dynamic = obj()
        command.jobId = id; command.toColumn = toLane; command.expectedRevision = revision
        command.idempotencyKey = "ui#" + id + "#" + str(revision) + "#" + toLane + "#" + (if (beforeJobId.isNullOrEmpty()) "end" else beforeJobId)
        if (!beforeJobId.isNullOrEmpty()) command.beforeJobId = beforeJobId
        val inputs: dynamic = obj()
        inputs.command = command
        inputs["command?"] = command
        val b: dynamic = obj()
        b.type = "kanban.move"; b.inputs = inputs
        // kanban.move via the runner dispatch — the one lane that carries beforeJobId.
        val res = api("/api/lcnc/run", requestInit("POST", jsonHeaders(), JSON.stringify(b)))
        val out: dynamic = if (truthy(res.body)) res.body.outputs else null
        if (res.status == 200 && truthy(out) && out.accepted != false) { refresh(true); return }
        val reason: dynamic = if (truthy(out) && truthy(out.reason)) out.reason else if (truthy(res.body?.error)) res.body.error else res.status
        toast("Move refused: " + str(reason))
        refresh(true)
    }

    // The card whose TOP HALF the pointer is above becomes the insert-before target; below every card = append.
    private fun beforeTargetIn(lane: HTMLElement, clientY: Double, draggedId: String): String? {
        val cards = lane.querySelectorAll(".card")
        for (i in 0 until cards.length) {
            val card = cards.item(i) as HTMLElement
            val cid = card.getAttribute("data-id")
            if (cid == draggedId) continue
            val r = card.getBoundingClientRect()
            if (clientY < r.top + r.height / 2) return cid
        }
        return null
    }

    // ── The interchange rail: timeline strip, one <li data-seq> per kanban fact, newest first.
    private val timelineEl: HTMLElement by lazy { q("#timeline") }
    private val seenSeq = LinkedHashSet<Double>()
    private var lastFactSeq = -1.0
    private var recentFacts = 0

    private class BurstBuf(var n: Int, var minAt: Double, var maxAt: Double, var missingAt: Boolean, val firstSeq: dynamic, var lastSeq: dynamic)

    private val burstBuf = LinkedHashMap<String, BurstBuf>()
    private var burstTimer: Int? = null

    private fun stampOf(ms: Double?): String {
        if (ms == null || !ms.isFinite()) return "<span class=\"ts unknown\">t=?</span>"
        val d = Date(ms)
        fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
        return "<span class=\"ts\">${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}</span>"
    }

    // orderSeq places the row among the others by #seq; no orderSeq = newest on top.
    private fun timelineRow(cls: String, seqKey: dynamic, html: String, orderSeq: dynamic = undefined) {
        if (seqKey != null && timelineEl.querySelector("li[data-seq=\"" + cssEscape(str(seqKey)) + "\"]") != null) return
        val li = document.createElement("li") as HTMLElement
        li.className = cls
        if (seqKey != null) li.setAttribute("data-seq", str(seqKey))
        val order: Double? = if (isNumber(orderSeq)) (orderSeq as Number).toDouble() else if (isNumber(seqKey)) (seqKey as Number).toDouble() else null
        if (order != null) li.setAttribute("data-order", str(order))
        li.innerHTML = html
        if (order != null) {
            var before: Element? = null
            val rows = timelineEl.children
            for (i in 0 until rows.length) {
                val row = rows.item(i)!!
                val o = js("Number")(row.getAttribute("data-order")) as Double
                if (o.isFinite() && o < order) { before = row; break }
            }
            if (before != null) timelineEl.insertBefore(li, before) else timelineEl.appendChild(li)
        } else {
            timelineEl.prepend(li)
        }
        while (timelineEl.children.length > Kanban.TIMELINE_CAP) timelineEl.removeChild(timelineEl.lastChild!!)
    }

    private fun valueObj(f: dynamic): dynamic = if (f.value != null && jsTypeOf(f.value) == "object") f.value else obj()

    // The server time of a fact: a commit's own store stamp leads; else the blackboard's provenance stamp.
    private fun factAt(f: dynamic): Double? {
        val v = valueObj(f)
        if (str(f.key).startsWith("kanban/committed/") && isNumber(v.atMs) && (v.atMs as Double) > 0) return v.atMs as Double
        if (isNumber(f.atMs)) return f.atMs as Double
        return if (isNumber(v.atMs) && (v.atMs as Double) > 0) v.atMs as Double else null
    }

    private class Described(val bucket: String, val cls: String, val html: String)

    private fun orStr(v: dynamic, fallback: String): String = if (truthy(v)) str(v) else fallback

    private fun describe(f: dynamic): Described {
        val key = str(f.key)
        val parts = key.split("/")
        val v = valueObj(f)
        val at = factAt(f)
        val actor = if (truthy(v.actor)) str(v.actor) else if (truthy(f.actor)) str(f.actor) else ""
        val tail = "<small>${Kanban.escapeHtml(actor)} · #${str(f.seq)}</small>"
        val p1 = parts.getOrNull(1)
        val p2 = parts.getOrNull(2)
        if (p1 == "committed") {
            val jobId = orStr(p2, "")
            val from = if (truthy(v.from)) esc(v.from) + " → " else "→ "
            val col = orStr(v.col, "?")
            val rev = if (v.revision == null) "?" else str(v.revision)
            return Described(
                "commits → $col", "committed",
                "${stampOf(at)}<span class=\"id\" title=\"${Kanban.escapeHtml(jobId)}\">${Kanban.escapeHtml(Kanban.shortId(jobId))}</span>" +
                    "<span>$from${Kanban.escapeHtml(col)}</span><span class=\"rev\">r${Kanban.escapeHtml(rev)}</span>" +
                    (if (truthy(v.op) && v.op != "move") "<span class=\"rev\">${esc(v.op)}</span>" else "") + tail,
            )
        }
        if (p1 == "rule") {
            val ruleId = orStr(p2, "?")
            val to = if (truthy(v.toColumn)) " → ${esc(v.toColumn)}" else ""
            val job = orStr(v.jobId, "")
            return Described(
                "rule $ruleId", "rule",
                "${stampOf(at)}<span>rule <b>${Kanban.escapeHtml(ruleId)}</b>$to</span>" +
                    "<span class=\"id\" title=\"${Kanban.escapeHtml(job)}\">${Kanban.escapeHtml(Kanban.shortId(job))}</span>" + tail,
            )
        }
        if (p1 == "claim") {
            val jobId = orStr(p2, "")
            val ok = if (v.ok == true) "<span class=\"good\">✓</span>" else if (v.ok == false) "<span class=\"bad\" title=\"${Kanban.escapeHtml(orStr(v.error, ""))}\">✗</span>" else ""
            // The receipt carries latencyMs only when the brain reported it; a 0 is not a measurement.
            val latency = if (isNumber(v.latencyMs) && (v.latencyMs as Double) > 0) "<span>${str(v.latencyMs)} ms</span>" else "<span class=\"rev\" title=\"no latency reported\">–</span>"
            val verdict = if (truthy(v.verdict)) "<span>${esc(v.verdict)}</span>" else ""
            val decision = if (truthy(v.decision)) "<span class=\"rev\">${esc(v.decision)}</span>" else ""
            val agent = if (truthy(v.agent)) "<span>agent <b>${esc(v.agent)}</b></span>" else ""
            val enc = js("encodeURIComponent")
            val patch = if (truthy(v.patchCid)) "<a href=\"/trikeshed/_cas/${enc(v.patchCid) as String}\" download=\"${Kanban.escapeHtml(jobId)}.diff\" title=\"${esc(v.patchCid)}\">patch</a>" else ""
            val transcript = if (truthy(v.transcriptCid)) "<a href=\"/trikeshed/_cas/${enc(v.transcriptCid) as String}\" title=\"${esc(v.transcriptCid)}\">transcript</a>" else ""
            val budget = if (v.killed == true) "<span class=\"bad\" title=\"the agent exceeded its budget\">budget</span>" else ""
            val cut = if (v.truncated == true) "<span class=\"rev\" title=\"output over the cap was dropped\">truncated</span>" else ""
            return Described(
                "claim receipts", "claim",
                "${stampOf(at)}<span class=\"id\" title=\"${Kanban.escapeHtml(jobId)}\">${Kanban.escapeHtml(Kanban.shortId(jobId))}</span><span>claim</span>" +
                    (if (truthy(v.owner)) "<span>${esc(v.owner)}</span>" else "") + (if (truthy(v.model)) "<span class=\"rev\">${esc(v.model)}</span>" else "") +
                    agent + ok + latency + verdict + decision + patch + transcript + budget + cut + tail,
            )
        }
        if (p1 == "fanout") {
            val jobId = orStr(p2, "")
            val isArray: (dynamic) -> Boolean = { a -> js("Array").isArray(a) as Boolean }
            val children: Int? = if (isArray(v.children)) (v.children.length as Int) else null
            val models = if (isArray(v.models)) (v.models.join(", ") as String) else ""
            val span = if (isNumber(v.startedAtMs) && isNumber(v.finishedAtMs)) "<span class=\"rev\">${str((v.finishedAtMs as Double) - (v.startedAtMs as Double))} ms</span>" else ""
            return Described(
                "fan-out receipts", "fanout",
                "${stampOf(at)}<span class=\"id\" title=\"${Kanban.escapeHtml(jobId)}\">${Kanban.escapeHtml(Kanban.shortId(jobId))}</span>" +
                    "<span>fan-out${if (children != null) " → $children children" else ""}</span>" +
                    (if (models.isNotEmpty()) "<span class=\"rev\">${Kanban.escapeHtml(models)}</span>" else "") + span + tail,
            )
        }
        val valueText = strOrEmpty(JSON.stringify(f.value))
        return Described(
            parts.take(2).joinToString("/"), "other",
            "${stampOf(at)}<span class=\"k\">${Kanban.escapeHtml(key)}</span><span>${Kanban.escapeHtml(valueText.take(100))}</span>" + tail,
        )
    }

    private fun flushBursts() {
        for ((bucket, b) in burstBuf) {
            val delta = if (b.missingAt) "Δ ?" else "Δ ${str(b.maxAt - b.minAt)} ms server"
            timelineRow(
                "burst", "burst-${str(b.firstSeq)}-${str(b.lastSeq)}",
                "${stampOf(if (b.missingAt) null else b.maxAt)}<span>⚡ <span class=\"n\">${b.n}</span> ${Kanban.escapeHtml(bucket)} ($delta)</span><small>#${str(b.firstSeq)}–#${str(b.lastSeq)}</small>",
                b.lastSeq,
            )
        }
        burstBuf.clear()
        burstTimer = null
    }

    private fun onKanbanFact(f: dynamic) {
        recentFacts++
        val d = describe(f)
        val at = factAt(f)
        val parts = str(f.key).split("/")
        val p2 = parts.getOrNull(2)
        if (parts.getOrNull(1) == "committed" && !p2.isNullOrEmpty() && at != null) commitAt[p2] = at
        if (recentFacts <= Kanban.BURST_PER_SECOND) { timelineRow(d.cls, f.seq, d.html); return }
        val b = burstBuf[d.bucket] ?: BurstBuf(0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, false, f.seq, f.seq)
        b.n++
        if (at != null) { b.minAt = min(b.minAt, at); b.maxAt = max(b.maxAt, at) } else b.missingAt = true
        b.lastSeq = f.seq
        burstBuf[d.bucket] = b
        if (burstTimer == null) burstTimer = setTimeout(Kanban.BURST_FLUSH_MS) { flushBursts() }
    }

    private fun frameRow(n: Int, atMs: List<Double?>) {
        val nums = atMs.filterNotNull()
        val delta = if (nums.size >= 2) "Δ ${str(nums.max() - nums.min())} ms server" else "Δ ?"
        timelineRow("frame", null, "<span class=\"ts\"> </span><span>$n in one frame ($delta) · flashes staggered ${Kanban.STAGGER_MS} ms apart on this display</span>")
    }

    private fun ticker() {
        val wire = q("#wire")
        suspend fun connect() {
            // The wire's seq is a per-process counter: probe it first; a seq behind ours (or no answer) is a new epoch.
            if (lastFactSeq >= 0) {
                var epochSeq: Double? = null
                try {
                    val b: dynamic = fetchResponse("/blackboard/board").jsonValue()
                    epochSeq = if (isNumber(b.seq)) b.seq as Double else null
                } catch (_: Throwable) { /* no answer = assume restart */ }
                if (epochSeq == null || epochSeq < lastFactSeq) {
                    lastFactSeq = -1.0
                    seenSeq.clear()
                    val rows = timelineEl.children
                    for (i in 0 until rows.length) {
                        val li = rows.item(i)!!
                        val s = li.getAttribute("data-seq")
                        if (s != null && !s.startsWith("prev:")) li.setAttribute("data-seq", "prev:$s")
                        li.setAttribute("data-order", "-1")
                    }
                }
            }
            val es = EventSource("/blackboard/facts" + (if (lastFactSeq >= 0) "?since=" + str(lastFactSeq + 1) else ""))
            es.onmessage = { m: MessageEvent ->
                var f: dynamic = null
                try { f = JSON.parse<dynamic>(m.data as String) } catch (_: Throwable) {}
                if (f != null && truthy(f.key)) {
                    var dup = false
                    if (isNumber(f.seq)) {
                        val s = f.seq as Double
                        if (!seenSeq.add(s)) dup = true
                        else {
                            if (seenSeq.size > 1024) {
                                val it = seenSeq.iterator()
                                while (it.hasNext()) { it.next(); it.remove(); if (seenSeq.size <= 512) break }
                            }
                            lastFactSeq = max(lastFactSeq, s)
                        }
                    }
                    if (!dup) {
                        val key = str(f.key)
                        if (key.startsWith("kanban/")) {
                            onKanbanFact(f)
                            wire.innerHTML = "<b>●</b> daemon wire live · last <span class=\"k\">${Kanban.escapeHtml(key)}</span> #${str(f.seq)}"
                            scheduleRefresh() // a fact means the board may have moved under us
                        } else if ((wire.textContent ?: "").startsWith("daemon wire")) {
                            wire.innerHTML = "<b>●</b> daemon wire live — last fact: <span class=\"k\">${Kanban.escapeHtml(key)}</span>"
                        }
                    }
                }
            }
            es.onerror = {
                es.close()
                wire.textContent = "daemon wire: reconnecting…"
                setTimeout(3000) { launchPage { connect() } }
            }
        }
        launchPage { connect() }
    }

    fun mount() {
        q("#importBtn").addEventListener("click", { q("#importDlg").asDynamic().showModal() })
        q("#importCancel").addEventListener("click", { q("#importDlg").asDynamic().close() })
        q("#importGo").addEventListener("click", {
            launchPage {
                val area = q("#importText") as HTMLTextAreaElement
                val text = area.value
                if (text.trim().isEmpty()) { q("#importDlg").asDynamic().close(); return@launchPage }
                val h: dynamic = obj(); h["Content-Type"] = "text/plain"
                val res = api("/api/board/import", requestInit("POST", h, text))
                q("#importDlg").asDynamic().close()
                val body = res.body
                if (res.status == 200 && truthy(body)) {
                    toast("Imported ${str(body.imported)} card${if (body.imported == 1) "" else "s"}" +
                        (if (truthy(body.duplicates)) ", ${str(body.duplicates)} already on the board" else ""))
                    area.value = ""
                } else {
                    toast("Import failed (${res.status})")
                }
                refresh(true)
            }
        })
        window.setInterval({ recentFacts = 0 }, 1000)
        ticker()
        launchPage { refresh(true) }
        window.setInterval({ scheduleRefresh() }, 2500)
    }
}
