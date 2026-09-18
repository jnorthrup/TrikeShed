package borg.trikeshed.board

import borg.trikeshed.forge.sheet.SheetSeed
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.asList
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event

suspend fun main() = BoardPage.mount()

/**
 * Kotlin/JS browser adapter for the blackboard surface (DOM mounting, fetch, the SSE stream,
 * clicks). Every string it paints comes from [BoardSurface] in commonMain; every JSON it reads
 * is parsed by the one commonMain parser; every decision about a delta is [BoardSurface.accept].
 *
 * The live table of key → [Fact] is the page's, as the original renderer's `board` object was.
 * A delta repaints only the territory it landed in (plus the receipts and document territories
 * when the key is theirs), by replacing that one `<section>`; a full render is for connection
 * and for the program filter alone.
 */
object BoardPage {
    private val facts = mutableMapOf<String, Fact>()
    private var revision = 0L
    private var epoch = ""
    private var stream: EventSource? = null
    private var generation = 0
    private var program: String? = null
    private val events = ArrayDeque<Delta>()
    private val flash = mutableMapOf<String, Double>()
    private var flashSweep: Int? = null
    private var reconnectTimer: Int? = null
    private var selected: String? = null
    private var sheetTicket = 0
    private var family: Map<String, SheetSeed> = emptyMap()
    private var currentSheet: String? = null
    private var planeHtml: String = ""
    private var hydrating = false
    private val buffer = mutableListOf<Delta>()
    private val scope = MainScope()

    private fun el(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

    suspend fun mount() {
        for (stub in BoardSurface.stubbed().view) {
            val e = el(stub.a) ?: continue
            e.setAttribute("disabled", "")
            e.title = stub.b
        }
        el("topologyLegend")?.setAttribute("hidden", "")
        el("viewport")?.classList?.add("board-scroll")
        el("territories")?.classList?.add("board-stack")
        el("territories")?.addEventListener("click", { e -> onClick(e) })
        el("events")?.addEventListener("click", { e -> onClick(e) })
        el("neighborResults")?.addEventListener("click", { e -> onClick(e) })
        el("boardnav")?.addEventListener("click", { e -> onClick(e) })
        el("factSheet")?.addEventListener("click", { e -> onSheetClick(e) })
        el("sheetRoots")?.addEventListener("click", { e -> onSheetClick(e) })
        (el("programSelect") as? HTMLSelectElement)?.addEventListener("change", { e ->
            program = (e.target as HTMLSelectElement).value.ifEmpty { null }
            renderTerritory(BoardSurface.RECEIPTS)
        })
        el("sheetsBtn")?.addEventListener("click", { scope.launch { openSheets(null) } })
        el("neighborsBtn")?.addEventListener("click", {
            val key = selected ?: facts.keys.sorted().firstOrNull()
            if (key != null) scope.launch { inspect(key) } else message("The blackboard is empty")
        })
        el("sheetRawBtn")?.addEventListener("click", {
            val dialog = el("factInspector") ?: return@addEventListener
            val on = !dialog.classList.contains("raw")
            dialog.classList.toggle("raw", on)
            el("sheetRawBtn")?.setAttribute("aria-pressed", on.toString())
        })
        (el("neighborKey") as? HTMLInputElement)?.addEventListener("change", { e ->
            val key = (e.target as HTMLInputElement).value
            if (key in facts) scope.launch { inspect(key) }
        })
        window.addEventListener("hashchange", {
            val key = BoardSurface.keyFromHash(window.location.hash)
            if (key != null && key != selected && key in facts) scope.launch { inspect(key) }
        })
        window.addEventListener("pagehide", { stream?.close() })
        js("window.forgeKotlin = Object.assign(window.forgeKotlin || {}, { boardSurface: true })")
        connect()
    }

    // ── connection: snapshot, then the stream from that revision ────────────

    suspend fun connect() {
        stream?.close()
        reconnectTimer?.let { window.clearTimeout(it) }
        reconnectTimer = null
        val gen = ++generation
        hydrating = true
        buffer.clear()
        status("Syncing")
        val snapshot = BoardSurface.snapshot(getJson("/blackboard/board"))
        if (gen != generation) return
        if (snapshot == null) { reconnect("Snapshot unavailable"); return }
        facts.clear(); facts.putAll(snapshot.facts)
        revision = snapshot.revision; epoch = snapshot.epoch
        val source = EventSource("/blackboard/facts?since=$revision&epoch=${encode(epoch)}")
        stream = source
        source.addEventListener("reset", { if (gen == generation) reconnect("Refreshing snapshot") })
        source.onmessage = { e ->
            if (gen == generation) {
                val delta = runCatching { BoardSurface.delta(JsonSupport.parse((e as MessageEvent).data.toString())) }.getOrNull()
                if (delta == null) reconnect("Invalid event")
                else if (hydrating) { buffer.add(delta); if (buffer.size > 2048) reconnect("Snapshot backlog") }
                else accept(delta)
            }
        }
        source.onerror = { if (gen == generation) reconnect("Reconnecting") }
        renderAll()
        hydrating = false
        for (d in buffer) accept(d)
        buffer.clear()
        status("Live")
        el("connection")?.classList?.add("live")
        val key = BoardSurface.keyFromHash(window.location.hash)
        if (key != null && key in facts && selected == null) inspect(key)
    }

    private fun reconnect(reason: String) {
        stream?.close(); stream = null
        generation++
        status(reason)
        el("connection")?.classList?.remove("live")
        reconnectTimer?.let { window.clearTimeout(it) }
        reconnectTimer = window.setTimeout({ scope.launch { connect() } }, 500)
    }

    private fun accept(d: Delta) {
        when (BoardSurface.accept(revision, epoch, d)) {
            Accept.STALE -> return
            Accept.GAP -> { reconnect("Revision gap"); return }
            Accept.APPLY -> Unit
        }
        revision = d.seq
        BoardSurface.apply(facts, d)
        events.addFirst(d)
        while (events.size > 100) events.removeLast()
        val prefix = BoardSurface.prefix(d.key)
        lit(d.key); lit(prefix)
        val affected = mutableSetOf(prefix)
        if (d.key.startsWith(BoardSurface.RUN_PREFIX)) { affected.add(BoardSurface.RECEIPTS); lit(BoardSurface.RECEIPTS) }
        if (d.key.startsWith(BoardSurface.DOCUMENT + "/") || d.key.startsWith(BoardSurface.RUN_PREFIX)) { affected.add(BoardSurface.DOCUMENT); lit(BoardSurface.DOCUMENT) }
        affected.add(BoardSurface.ACTORS)
        for (id in affected) renderTerritory(id)
        el("events")?.innerHTML = BoardSurface.eventsHtml(events.toList().toSeries())
        el("boardCount")?.textContent = BoardSurface.boardCount(facts.size)
        if (selected == d.key) scope.launch { inspect(d.key) }
    }

    private fun lit(key: String) {
        flash[key] = window.performance.now() + 1000
        if (flashSweep == null) flashSweep = window.setInterval({
            val now = window.performance.now()
            val expired = flash.filterValues { it <= now }.keys
            for (k in expired) {
                flash.remove(k)
                document.querySelectorAll("[data-key=\"${k.replace("\"", "\\\"")}\"],[data-territory=\"${k.replace("\"", "\\\"")}\"]")
                    .asList().forEach { (it as? Element)?.classList?.remove("flash") }
            }
            if (flash.isEmpty()) { flashSweep?.let { window.clearInterval(it) }; flashSweep = null }
        }, 250)
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private fun renderAll() {
        val territories = BoardSurface.territories(facts, program)
        val lit = flash.keys
        el("territories")?.innerHTML = territories.view.joinToString("") { BoardSurface.territoryHtml(it, facts, program, lit) }
        el("boardnav")?.innerHTML = BoardSurface.navHtml(territories)
        el("events")?.innerHTML = BoardSurface.eventsHtml(events.toList().toSeries())
        el("boardCount")?.textContent = BoardSurface.boardCount(facts.size)
        message(territories.size.toString() + " territories · " + BoardSurface.boardCount(facts.size))
        (el("programSelect") as? HTMLSelectElement)?.innerHTML = BoardSurface.programOptionsHtml(BoardSurface.programs(facts), program)
        val options = el("neighborKeys")
        if (options != null) options.innerHTML = facts.keys.sorted().joinToString("") { "<option value=\"" + BoardSurface.esc(it) + "\"></option>" }
    }

    private fun renderTerritory(id: String) {
        val territories = BoardSurface.territories(facts, program)
        val t = territories.view.firstOrNull { it.id == id }
        val existing = document.querySelector("section[data-territory=\"${id.replace("\"", "\\\"")}\"]")
        if (t == null) { existing?.remove(); return }
        val html = BoardSurface.territoryHtml(t, facts, program, flash.keys)
        if (existing != null) existing.outerHTML = html else renderAll()
        if (el("boardnav")?.childElementCount != territories.size) el("boardnav")?.innerHTML = BoardSurface.navHtml(territories)
    }

    private fun onClick(e: Event) {
        val target = (e.target as? Element)?.closest("[data-key],[data-sheets],[data-territory-nav]") ?: return
        e.preventDefault()
        target.getAttribute("data-key")?.let { key -> scope.launch { inspect(key) }; return }
        target.getAttribute("data-sheets")?.let { prefix -> scope.launch { openSheets(prefix) }; return }
        target.getAttribute("data-territory-nav")?.let { id ->
            document.querySelector("section[data-territory=\"$id\"]")?.scrollIntoView()
        }
    }

    private fun onSheetClick(e: Event) {
        val target = (e.target as? Element)?.closest("[data-sheet],[data-sheet-root],.leafcell") ?: return
        e.preventDefault()
        val id = target.getAttribute("data-sheet") ?: target.getAttribute("data-sheet-root")
        if (id != null) { currentSheet = id; renderSheets(); return }
        if (target.classList.contains("leafcell")) {
            val text = target.textContent.orEmpty()
            runCatching { window.navigator.clipboard.writeText(text) }
            target.classList.add("copied")
            window.setTimeout({ target.classList.remove("copied") }, 500)
        }
    }

    // ── the inspector ───────────────────────────────────────────────────────

    suspend fun inspect(key: String) {
        selected = key
        val fact = facts[key]
        val (actor, raw) = BoardSurface.inspectorText(fact)
        el("factKey")?.textContent = key
        el("factActor")?.textContent = actor
        el("factValue")?.textContent = raw
        (el("neighborKey") as? HTMLInputElement)?.value = key
        el("factNeighbors")?.removeAttribute("hidden")
        el("neighborStatus")?.textContent = "Finding neighbors across the blackboard…"
        el("neighborResults")?.innerHTML = ""
        val dialog = el("factInspector") as? HTMLDialogElement
        if (dialog != null && !dialog.open) dialog.showModal()
        if (window.location.hash != BoardSurface.hashFor(key)) window.location.hash = BoardSurface.hashFor(key)
        planeHtml = ""
        loadSheets(listOf("/blackboard/sheet?key=" + encode(key)), key)
        val summary = if (key.startsWith(BoardSurface.CURATION_PREFIX)) fact?.let(BoardSurface::curationSummary) else null
        if (summary != null) {
            val json = getJson("/api/rete/facts?partition=documents&field=curationCid&value=" + encode(summary.receiptCid))
            if (selected == key) { planeHtml = BoardSurface.factsHtml(BoardSurface.planeFacts(json)); renderSheets() }
        }
        loadNeighbors(key)
    }

    private suspend fun openSheets(prefix: String?) {
        val prefixes = if (prefix != null) listOf(prefix) else facts.keys.map(BoardSurface::prefix).distinct().sorted()
        selected = null
        el("factKey")?.textContent = (if (prefix != null) prefixes.joinToString(" · ") else "blackboard") + " as sheets"
        el("factActor")?.textContent = ""
        el("factValue")?.textContent = ""
        el("factNeighbors")?.setAttribute("hidden", "")
        val dialog = el("factInspector") as? HTMLDialogElement
        if (dialog != null && !dialog.open) dialog.showModal()
        planeHtml = ""
        loadSheets(prefixes.map { "/blackboard/sheet?prefix=" + encode(it) + "&max=1024" }, prefixes.firstOrNull())
    }

    private suspend fun loadSheets(urls: List<String>, current: String?) {
        val ticket = ++sheetTicket
        el("factSheet")?.innerHTML = "<p class=\"sheet-note\">Projecting sheets</p>"
        el("sheetRoots")?.innerHTML = ""
        val loaded = LinkedHashMap<String, SheetSeed>()
        val notes = mutableListOf<String>()
        for (url in urls) {
            val json = getJson(url)
            if (json == null) { notes.add("no answer on $url"); continue }
            for (s in BoardSurface.sheets(json).view) loaded[s.id] = s
        }
        if (ticket != sheetTicket) return
        family = loaded
        currentSheet = if (current != null && current in loaded) current else loaded.keys.firstOrNull()
        if (loaded.isEmpty()) {
            el("factSheet")?.innerHTML = "<p class=\"sheet-note error\">No sheets: " + BoardSurface.esc(notes.joinToString("; ").ifEmpty { "empty projection" }) + "</p>" + planeHtml
            return
        }
        renderSheets()
        if (notes.isNotEmpty()) el("factSheet")?.insertAdjacentHTML("beforeend", "<p class=\"sheet-note error\">" + BoardSurface.esc(notes.joinToString("; ")) + "</p>")
    }

    private fun renderSheets() {
        val current = currentSheet
        val host = el("factSheet") ?: return
        if (current == null || family.isEmpty()) { host.innerHTML = planeHtml; return }
        el("sheetRoots")?.innerHTML = BoardSurface.sheetRootsHtml(family, current)
        host.innerHTML = "<div class=\"csheet\">" + BoardSurface.sheetHtml(family, current) + "</div>" + planeHtml
    }

    private suspend fun loadNeighbors(key: String) {
        val json = getJson("/blackboard/neighbors?key=" + encode(key))
        if (selected != key) return
        if (json == null) { el("neighborStatus")?.textContent = "Neighbors unavailable"; return }
        el("neighborStatus")?.textContent = BoardSurface.neighborsStatus(json)
        el("neighborResults")?.innerHTML = BoardSurface.neighborsHtml(BoardSurface.neighbors(json))
    }

    // ── the bar ─────────────────────────────────────────────────────────────

    private fun status(value: String) {
        val c = el("connection")
        if (c != null && c.textContent != value) { c.textContent = value; c.title = value }
    }

    private fun message(value: String) { el("status")?.textContent = value }

    private suspend fun getJson(url: String): Any? = runCatching {
        val response = window.fetch(url).await()
        if (!response.ok) return@runCatching null
        JsonSupport.parse(response.text().await())
    }.getOrNull()
}

private fun encode(s: String): String = js("encodeURIComponent(s)") as String
