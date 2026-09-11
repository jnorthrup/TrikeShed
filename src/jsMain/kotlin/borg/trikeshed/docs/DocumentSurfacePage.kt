package borg.trikeshed.docs

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/**
 * The browser half of the document surface: the only hand-written-JS-shaped code
 * the page needs (DOM mounting, fetch, clicks). Every string it paints comes
 * from [DocumentSurface] in commonMain; every JSON it reads is parsed by the
 * one commonMain parser. Mounted by the bundle's entry point when the served
 * shell carries `#document-surface`.
 *
 * The run blocks (AutoTools, Cut B) add three fetches and no decisions: [RunBlock.scan] finds the
 * page's build targets, [RunBlock.headQuery] says what to ask about each one, [RunBlock.state]
 * turns the answer into the frame's state, and a press sends [RunBlock.buildRequest] or
 * [RunBlock.rebuildRequest]. A three-second poll while a document is open is what makes a Stale
 * transition visible without an event stream; a generation counter cancels it the moment the
 * reader navigates away.
 *
 * WHAT A POLL MAY TOUCH. `#ds-doc` is the scroll container, so re-rendering the pane every three
 * seconds would yank the reader back to the top of a long page, drop the text they were selecting,
 * and — for a tick that lands between mousedown and mouseup — replace the button under the cursor
 * so the click never fires. A poll therefore repaints ONE frame, in place, and only when its state
 * actually moved; a full [render] is for navigation alone.
 */
object DocumentSurfacePage {
    fun present(): Boolean = document.getElementById(DocumentSurface.ROOT_ID) != null

    private const val POLL_MS = 3_000L

    private var projects: List<ProjectRow> = emptyList()
    private var project: String? = null
    private var docs: List<DocRow> = emptyList()
    private var doc: DocView? = null
    private var blocks: List<RunBlock.Block> = emptyList()
    private var view: SurfaceView = SurfaceView.PAGE
    private var column: DocColumn = DocColumn.NAME
    private var ascending: Boolean = true
    private val runStates: MutableMap<Int, RunBlock.State> = mutableMapOf()

    /** Bumped on every navigation; a poll whose generation has moved on stops. */
    private var generation = 0

    suspend fun mount() {
        val root = document.getElementById(DocumentSurface.ROOT_ID) as HTMLElement
        root.innerHTML = DocumentSurface.layout()
        root.addEventListener("click", { e -> onClick(e) })
        projects = DocumentSurface.projects(getJson("/api/projects"))
        // Deep link: #project or #project/doc, the hrefs the panes render. READ BEFORE the first
        // render: nothing is open yet, so that render writes `window.location.hash = ""` and would
        // erase the very link it is about to be asked to follow (a cold load of
        // /documents#genesis-notes/digest.md landed on "Pick a document").
        val hash = decode(window.location.hash.removePrefix("#"))
        render()
        if (hash.isNotEmpty()) {
            openProject(hash.substringBefore('/'))
            val id = hash.substringAfter('/', "")
            if (id.isNotEmpty()) openDoc(id)
        }
        js("window.forgeKotlin = Object.assign(window.forgeKotlin || {}, { documentSurface: true })")
    }

    private fun onClick(e: Event) {
        val target = (e.target as? Element)
            ?.closest("[data-project],[data-doc],[data-run-build],[data-run-rebuild],[data-view],[data-sort]") ?: return
        e.preventDefault()
        val p = target.getAttribute("data-project")
        val d = target.getAttribute("data-doc")
        val build = target.getAttribute("data-run-build")
        val rebuild = target.getAttribute("data-run-rebuild")
        val wantView = target.getAttribute("data-view")
        val sort = target.getAttribute("data-sort")
        // A view switch and a sort are projections of what is already loaded: they repaint from
        // the rows in hand and never re-read the daemon.
        if (wantView != null) {
            view = if (wantView == "table") SurfaceView.TABLE else SurfaceView.PAGE
            render(); return
        }
        if (sort != null) {
            val next = DocColumn.of(sort)
            ascending = if (next == column) !ascending else true
            column = next
            render(); return
        }
        MainScope().launch {
            when {
                p != null -> openProject(p)
                // Opening a page from the table is how a reader leaves the table: the row they
                // picked is the page they meant, so the view follows the gesture.
                d != null -> { view = SurfaceView.PAGE; openDoc(d) }
                build != null -> press(build.toIntOrNull(), rebuild = false)
                rebuild != null -> press(rebuild.toIntOrNull(), rebuild = true)
            }
        }
    }

    suspend fun openProject(name: String) {
        generation++
        project = name; doc = null
        view = SurfaceView.TABLE
        blocks = emptyList(); runStates.clear()
        docs = DocumentSurface.docs(getJson("/api/projects/" + encode(name) + "/docs?limit=512"))
        render()
    }

    suspend fun openDoc(id: String) {
        val p = project ?: return
        val generationHere = ++generation
        doc = DocumentSurface.document(getJson("/api/projects/" + encode(p) + "/docs/" + encode(id)))
        blocks = doc?.takeIf { DocumentSurface.isMarkdown(it) }?.let { RunBlock.scan(it.text) } ?: emptyList()
        runStates.clear()
        render()
        if (blocks.none { it is RunBlock.Spec }) return
        refreshBlocks()
        MainScope().launch {
            while (generationHere == generation) {
                delay(POLL_MS)
                if (generationHere != generation) return@launch
                refreshBlocks()
            }
        }
    }

    /**
     * One read per block. A fetch that fails leaves the block's last known state alone, and a
     * quiet answer — the same [RunBlock.State] the frame already wears — repaints nothing at all:
     * `State` is a data class, so an unmoved head compares equal and the reader's page is left
     * exactly as they are using it.
     */
    private suspend fun refreshBlocks() {
        // Bolt: avoid intermediate List/Sequence allocation from filterIsInstance
        for (spec in blocks) {
            if (spec !is RunBlock.Spec) continue
            val state = RunBlock.state(getJson(headUrl(spec))) ?: continue
            if (runStates.put(spec.ordinal, state) != state) repaint(spec, state)
        }
    }

    /** The head read's URL: the query is [RunBlock.headQuery]'s, the encoding is the browser's. */
    private fun headUrl(spec: RunBlock.Spec): String =
        RunBlock.HEAD_PATH + "?" + RunBlock.headQuery(spec).joinToString("&") { (name, value) -> name + "=" + encode(value) }

    /** One frame, replaced where it stands. A frame that is somehow gone falls back to a render. */
    private fun repaint(spec: RunBlock.Spec, state: RunBlock.State) {
        val frame = document.getElementById(RunBlock.frameId(spec.ordinal))
        if (frame == null) render() else frame.outerHTML = RunBlock.frameHtml(spec, state)
    }

    /**
     * Build sends the block's body verbatim to the run route — timeoutMs, maxNodes and all — and
     * Rebuild sends the artifact's run id. Both hold the request open for the whole run (the route
     * answers with the terminal receipt), so the pressing tab wears an optimistic Running until it
     * resolves; another tab reads the same run off the board.
     */
    private suspend fun press(ordinal: Int?, rebuild: Boolean) {
        // Bolt: avoid intermediate List/Sequence allocation from filterIsInstance
        val spec = blocks.firstOrNull { it is RunBlock.Spec && it.ordinal == ordinal } as? RunBlock.Spec ?: return
        val prior = runStates[spec.ordinal]
        val (url, body) = if (rebuild) (RunBlock.rebuildRequest(prior) ?: return) else RunBlock.buildRequest(spec)
        val pending = RunBlock.pending(prior)
        runStates[spec.ordinal] = pending
        repaint(spec, pending)
        postJson(url, body)
        refreshBlocks()
    }

    private fun render() {
        setHtml(DocumentSurface.SIDE_ID, DocumentSurface.sidebarHtml(projects, project, docs, doc?.id))
        setHtml(DocumentSurface.CRUMB_ID, DocumentSurface.crumbHtml(project, doc?.id, view))
        setHtml(
            DocumentSurface.CONTENT_ID,
            if (view == SurfaceView.TABLE) DocumentSurface.tableHtml(project, docs, column, ascending, doc?.id)
            else DocumentSurface.documentHtml(doc, runStates.toMap()),
        )
        val p = project
        val wanted = if (p == null) "" else "#" + encode(p) + (doc?.let { "/" + encode(it.id) } ?: "")
        if (window.location.hash != wanted) window.location.hash = wanted
        document.title = listOfNotNull(doc?.id, project, "Documents").joinToString(" · ")
    }

    private fun setHtml(id: String, html: String) {
        (document.getElementById(id) as? HTMLElement)?.innerHTML = html
    }

    private suspend fun getJson(url: String): Any? = runCatching {
        val response = window.fetch(url).await()
        val text = response.text().await()
        JsonSupport.parse(text)
    }.getOrNull()

    private suspend fun postJson(url: String, body: String): Any? = runCatching {
        val init = RequestInit(
            method = "POST",
            headers = Headers().apply { append("Content-Type", "application/json") },
            body = body,
        )
        val response = window.fetch(url, init).await()
        val text = response.text().await()
        JsonSupport.parse(text)
    }.getOrNull()
}

private fun encode(s: String): String = js("encodeURIComponent(s)") as String
private fun decode(s: String): String = runCatching { js("decodeURIComponent(s)") as String }.getOrDefault(s)
