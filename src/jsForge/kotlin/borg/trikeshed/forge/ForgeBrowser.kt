@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.board.ForgeCommandQueue
import borg.trikeshed.forge.board.SYNC_NOTE_OFFLINE
import borg.trikeshed.forge.board.SYNC_NOTE_ONLINE
import borg.trikeshed.forge.board.SYNC_NOTE_UNREACHABLE
import borg.trikeshed.forge.board.boardSequenceOf
import borg.trikeshed.forge.board.forgeBoardFromApi
import borg.trikeshed.forge.board.shouldAdoptBoard
import borg.trikeshed.forge.board.syncNoteQueued
import borg.trikeshed.forge.board.syncNoteSynced
import borg.trikeshed.forge.doc.ForgePage
import borg.trikeshed.forge.graph.ForgeGraphCamera
import borg.trikeshed.forge.graph.ForgeGraphLayout
import borg.trikeshed.forge.graph.ForgeGraphMode
import borg.trikeshed.forge.graph.forgeGraphLayoutsOf
import borg.trikeshed.forge.shape.ShapeDoc
import borg.trikeshed.forge.sheet.SheetSort
import borg.trikeshed.forge.sheet.SourceSheet
import borg.trikeshed.forge.sheet.sourceSheetsOf
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/**
 * The Forge browser adapter — the jsForge half of the retired `web/script.js`: element refs,
 * rendering, event wiring, localStorage IO, and `fetch` to `api/invoke` / `api/board`.
 * Every decision it executes lives in commonMain (`ForgeWorkspace`, `doc`, `sheet`, `board`,
 * `graph`, `shape` packages). Booted by `ForgeNodeMain` when `#forge-seed` is in the DOM.
 */
object ForgeBrowser {

    val scope = MainScope()

    // ── Seed + persisted state ──────────────────────────────────────────
    var seed: Map<String, Any?> = emptyMap()
    lateinit var workspace: ForgeWorkspace
    var sourceSheets: List<SourceSheet> = emptyList()
    val sourceSheetById: MutableMap<String, SourceSheet> = mutableMapOf()
    val sheetSort: MutableMap<String, SheetSort> = mutableMapOf()

    // ── Command queue ───────────────────────────────────────────────────
    val commandQueue = ForgeCommandQueue()
    var boardSequence: Long? = null
    var flushJob: Job? = null
    var flushedCount = 0

    // ── Graph state ─────────────────────────────────────────────────────
    var graphLayouts: Map<ForgeGraphMode, ForgeGraphLayout> = emptyMap()
    var graphMode: ForgeGraphMode = ForgeGraphMode.Causal
    var graphLayout: ForgeGraphLayout = ForgeGraphLayout(emptyList(), emptyList(), ForgeGraphCamera(0.0, 0.0, 1.0))
    var cam: ForgeGraphCamera = ForgeGraphCamera(0.0, 0.0, 1.0)
    var graphBuilt = false
    var graphViewport: org.w3c.dom.Element? = null

    // ── Slash menu state ────────────────────────────────────────────────
    var slashBlockId: String? = null
    var slashFilter = ""
    var activeSlashIndex = 0

    // ── Shape state ─────────────────────────────────────────────────────
    val shapeDocs = mutableListOf<ShapeDoc>()
    var shapeDepth = Int.MAX_VALUE
    var shapePick: Pair<Int, Int>? = null
    var shapePending = 0
    var shapeStoreLoaded = false
    var shapeFib: Set<Int>? = null

    // ── Host state ──────────────────────────────────────────────────────
    var hostLiveProbe: Boolean? = null
    var hostEventsStarted = false

    val seedUserId: String get() = seed["userId"].asStr("jim")

    fun el(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

    // ── Boot ────────────────────────────────────────────────────────────

    fun boot() {
        val seedEl = document.getElementById("forge-seed")
        seed = runCatching {
            JsonSupport.parse(seedEl?.textContent ?: "{}") as? Map<String, Any?> ?: emptyMap()
        }.getOrElse { emptyMap() }
        loadState()
        sourceSheets = sourceSheetsOf(seed)
        sourceSheets.forEach { sourceSheetById[it.id] = it }
        graphLayouts = forgeGraphLayoutsOf(seed)
        graphMode = workspace.graphMode?.takeIf { graphLayouts.containsKey(it) } ?: ForgeGraphMode.Causal
        graphLayout = graphLayouts[graphMode]!!
        resetCam()

        wireTitle()
        wireViewButtons()
        wireGlobalKeyboard()
        wireGraph()
        wireSheetKeys()
        wireDropZone()
        wireShapeIngest()
        wireHostForm()
        wireGallery()
        renderSeedNote()
        renderAll()
        mountCta()

        // Live board: hydrate from the WAL-backed store at load, then poll on the watermark
        // while the board is visible.
        hydrateBoard()
        scope.launch {
            while (true) {
                delay(15000)
                val hidden = js("document.hidden") as Boolean
                if (workspace.view == ForgeView.Board && !hidden) hydrateBoard()
            }
        }
        window.addEventListener("online", { noteSync(SYNC_NOTE_ONLINE); scheduleFlush() })
        window.addEventListener("offline", { noteSync(SYNC_NOTE_OFFLINE) })
    }

    // ── Persistence (localStorage is the store; the JS side of `loadState`/`saveState`) ──

    fun loadState() {
        workspace = forgeWorkspaceFromJson(
            runCatching { window.localStorage.getItem(ForgeStorageKeys.WORKSPACE) }.getOrNull(),
            seed,
        )
        workspace.overlayBoardCards(
            runCatching { window.localStorage.getItem(ForgeStorageKeys.BOARD_SEED) }.getOrNull()
        )
    }

    fun saveState() {
        runCatching {
            window.localStorage.setItem(ForgeStorageKeys.WORKSPACE, workspace.toJson())
            window.localStorage.setItem(
                ForgeStorageKeys.BOARD_SEED,
                JsonSupport.stringify(mapOf("cards" to workspace.board.cards.map { it.toMap() })),
            )
            seed["causalGraph"]?.let { causal ->
                window.localStorage.setItem(ForgeStorageKeys.CAUSAL_SEED, JsonSupport.stringify(causal))
            }
        }
    }

    /** Doc edits persist locally; only board actions are commands. */
    fun mutate(updater: (ForgeWorkspace) -> Unit) {
        updater(workspace)
        saveState()
    }

    // ── Command queue → reactor ingress (`api/invoke`) ──────────────────

    fun invokeUrl(): String = js("new URL('api/invoke', document.baseURI).toString()") as String
    fun boardUrl(): String = js("new URL('api/board', document.baseURI).toString()") as String

    fun noteSync(text: String) {
        el("sync-note")?.textContent = text
    }

    fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(400)
            flushCommands()
        }
    }

    fun flushCommands() {
        val body = commandQueue.drain(seedUserId) ?: return
        val batchSize = (body["commands"] as List<*>).size
        scope.launch {
            val response = runCatching {
                window.fetch(invokeUrl(), RequestInit(
                    method = "POST",
                    headers = Headers().also { it.append("Content-Type", "application/json") },
                    body = JsonSupport.stringify(body),
                )).await()
            }.getOrElse {
                noteSync(SYNC_NOTE_UNREACHABLE)
                return@launch
            }
            val res = runCatching { JsonSupport.parse(response.text().await()) as? Map<String, Any?> }
                .getOrNull() ?: emptyMap()
            if (res["status"] == "queued") {
                noteSync(syncNoteQueued(batchSize))
                return@launch
            }
            flushedCount += batchSize
            val rejected = res["rejected"].asInt()
            val sequence = boardSequenceOf(res)
            noteSync(syncNoteSynced(flushedCount, rejected, sequence))
            // The server verdict is the truth: reconcile optimistic board state.
            if (rejected > 0) hydrateBoard(force = true)
            else if (sequence != null && sequence != boardSequence) hydrateBoard()
        }
    }

    fun queueBoardCommand(command: Map<String, Any?>) {
        commandQueue.enqueue(command)
        scheduleFlush()
    }

    // ── Board hydration (`api/board`, watermark-polled) ─────────────────

    fun hydrateBoard(force: Boolean = false) {
        scope.launch {
            val body = runCatching {
                val response = window.fetch(boardUrl()).await()
                JsonSupport.parse(response.text().await()) as? Map<String, Any?>
            }.getOrNull() ?: return@launch
            val columns = body["columns"]
            val items = body["items"]
            if (columns !is List<*> && columns !is Array<*>) return@launch
            if (items !is List<*> && items !is Array<*>) return@launch
            val incomingSequence = boardSequenceOf(body)
            if (!shouldAdoptBoard(force, boardSequence, incomingSequence)) return@launch
            boardSequence = incomingSequence
            val board = forgeBoardFromApi(body) ?: return@launch
            workspace.board = board
            saveState()
            if (workspace.view == ForgeView.Board) renderBoard()
        }
    }

    // ── View switching ──────────────────────────────────────────────────

    /** The `VIEWS` table: view → (scroll element, toolbar/sidebar buttons). */
    fun viewElements(view: ForgeView): Pair<String, List<String>> = when (view) {
        ForgeView.Doc -> "doc-scroll" to listOf("btn-view-doc", "btn-home")
        ForgeView.Board -> "board-scroll" to listOf("btn-view-board", "btn-board")
        ForgeView.Graph -> "graph-scroll" to listOf("btn-view-graph", "btn-graph")
        ForgeView.Sheet -> "sheet-scroll" to listOf("btn-view-sheet", "btn-sheet")
        ForgeView.Shape -> "shape-scroll" to listOf("btn-view-shape")
        ForgeView.Host -> "host-scroll" to listOf("btn-view-host", "btn-host")
    }

    fun setView(view: ForgeView) {
        mutate { it.view = view }
        for (v in ForgeView.entries) {
            val (elId, btnIds) = viewElements(v)
            el(elId)?.hidden = v != view
            for (btnId in btnIds) {
                val btn = el(btnId) ?: continue
                btn.classList.toggle("active", v == view)
                if (v == view) btn.setAttribute("aria-current", "page")
                else btn.removeAttribute("aria-current")
            }
        }
        when (view) {
            ForgeView.Board -> { renderBoard(); hydrateBoard() }
            ForgeView.Graph -> setGraphMode(graphMode)
            ForgeView.Sheet -> renderSheet()
            ForgeView.Shape -> renderShape()
            ForgeView.Host -> renderHost()
            ForgeView.Doc -> {}
        }
    }

    fun wireViewButtons() {
        for (v in ForgeView.entries) {
            for (btnId in viewElements(v).second) {
                el(btnId)?.addEventListener("click", { setView(v) })
            }
        }
        el("btn-new-page")?.addEventListener("click", {
            newPage(null)
            renderAll()
            el("doc-title")?.focus()
        })
    }

    // ── Page helpers ────────────────────────────────────────────────────

    fun newPage(title: String?): ForgePage {
        val page = ForgePage(id = forgeUid(), title = title ?: "")
        mutate { s ->
            s.pages.add(page)
            s.activePageId = page.id
        }
        return page
    }

    // ── Render all ──────────────────────────────────────────────────────

    fun renderAll() {
        renderSidebar()
        renderTitle()
        renderBlocks()
        setView(workspace.view)
    }

    // ── Seed note ───────────────────────────────────────────────────────

    fun renderSeedNote() {
        val parts = forgeSeedNoteParts(seed)
        el("seed-note")?.textContent =
            if (parts.isNotEmpty()) "Seed: " + parts.joinToString(" · ") else "Local-first workspace"
    }

    // ── Global interactions ─────────────────────────────────────────────

    fun wireGlobalKeyboard() {
        // role="button" keyboard activation (two identical listeners in the script; one suffices).
        document.addEventListener("keydown", { e ->
            val key = (e as? KeyboardEvent)?.key
            if (key == "Enter" || key == " ") {
                val target = e.target as? HTMLElement
                if (target?.getAttribute("role") == "button") {
                    e.preventDefault()
                    target.click()
                }
            }
        })
        document.addEventListener("mousedown", { e ->
            val menu = el("slash-menu") ?: return@addEventListener
            if (!menu.hidden && !menu.contains(e.target as? org.w3c.dom.Node)) closeSlashMenu()
        })
    }

    // ── CTA links (sidebar footer, normal flow — never floated over the app's chrome) ──

    fun mountCta() {
        val anchor = el("sync-note")
        val host = document.createElement("div") as HTMLElement
        host.style.cssText = "display:flex;gap:12px;margin-top:6px;font-size:11px"

        fun link(text: String, title: String, onClick: () -> Unit) {
            val a = document.createElement("button") as HTMLElement
            a.textContent = text
            a.title = title
            a.style.cssText =
                "color:var(--text-faint);cursor:pointer;text-decoration:none;border-bottom:1px dotted var(--text-faint);background:none;border:none;padding:0;font:inherit;"
            a.addEventListener("click", { onClick() })
            host.appendChild(a)
        }

        link("⚡ install", "run the quickstart in anger") { mountQuickstartOverlay() }
        link("⚑ feedback", "open a GitHub issue prefilled with where you are right now") { openFeedbackIssue() }
        anchor?.parentElement?.appendChild(host) ?: document.body?.appendChild(host)
    }

    fun mountQuickstartOverlay() {
        val d = document.createElement("div") as HTMLElement
        d.asDynamic().dataset["qs"] = "1"
        d.style.cssText =
            "position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:99;display:flex;align-items:center;justify-content:center"
        d.innerHTML = "<div style=\"background:var(--bg,#fff);border:1px solid var(--border,#ccc);border-radius:8px;padding:18px 22px;max-width:580px;color:var(--text,#222);font:13px monospace;box-shadow:0 8px 30px rgba(0,0,0,.25)\">" +
            "<b>Run TrikeShed in anger — five minutes, one port</b>" +
            "<pre id=\"qsCmds\" style=\"background:rgba(127,127,127,.12);padding:10px;border-radius:4px;margin:10px 0;user-select:text;white-space:pre-wrap\">git clone git@github.com:jnorthrup/TrikeShed.git && cd TrikeShed\n./gradlew hotswapFeed\nbin/oroboros-daemon --watch</pre>" +
            "<button id=\"qsCopy\" style=\"font:inherit;padding:4px 12px;cursor:pointer\">copy commands</button>" +
            "<a href=\"https://github.com/jnorthrup/TrikeShed#run-it-in-anger--please\" target=\"_blank\" style=\"margin-left:10px\">README ↗</a>" +
            "<button id=\"qsClose\" style=\"background:none;border:none;padding:0;font:inherit;color:inherit;text-decoration:underline;margin-left:14px;cursor:pointer\">close</button></div>"
        d.addEventListener("click", { e -> if (e.target === d) d.remove() })
        document.body?.appendChild(d)
        document.getElementById("qsCopy")?.addEventListener("click", {
            val text = document.getElementById("qsCmds")?.textContent ?: ""
            js("navigator.clipboard.writeText(text)")
        })
        document.getElementById("qsClose")?.addEventListener("click", { d.remove() })
    }

    fun openFeedbackIssue() {
        val coords = mapOf(
            "view" to workspace.view.id,
            "page" to workspace.activePageId,
            "boardSequence" to boardSequence,
            "cards" to workspace.board.cards.size,
            "columns" to workspace.board.columns.map { it.id },
        )
        val body = "**Surface:** board\n**URL:** " + window.location.href + "\n**Coordinates:**\n```json\n" +
            prettyJson(coords) + "\n```\n\n**What I did:**\n\n**What happened:**\n\n**What I expected:**\n"
        window.open(
            "https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback" +
                "&title=" + js("encodeURIComponent('[board] ')") as String +
                "&body=" + js("encodeURIComponent(body)") as String,
            "_blank",
        )
    }

    /** `JSON.stringify(coords, null, 1)`. */
    fun prettyJson(value: Map<String, Any?>): String {
        val entries = value.entries.joinToString(",\n") { (k, v) ->
            " \"${k}\": " + when (v) {
                is List<*> -> "[${v.joinToString(", ") { "\"$it\"" }}]"
                else -> JsonSupport.stringify(v)
            }
        }
        return "{\n$entries\n}"
    }
}

/** Small DOM helpers shared by the adapter's renderers. */
fun HTMLElement.clearChildren() {
    while (firstChild != null) removeChild(firstChild!!)
}

fun el(tag: String, cls: String, text: String? = null): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    e.className = cls
    if (text != null) e.textContent = text
    return e
}

fun HTMLElement.onClick(handler: (MouseEvent) -> Unit) {
    addEventListener("click", { e -> handler(e as MouseEvent) })
}

fun HTMLElement.onKey(handler: (KeyboardEvent) -> Boolean) {
    addEventListener("keydown", { e -> if (handler(e as KeyboardEvent)) e.preventDefault() })
}
