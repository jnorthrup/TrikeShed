package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchHtml
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** Page-owned presentation only. The commonMain registry is the sole graph executor. */
class Runner(var render: (suspend (n: dynamic, out: dynamic) -> Unit)? = null)

internal fun runners(p: Panels): MutableMap<String, Runner> = mutableMapOf(
    "program.ref" to Runner { n, _ -> p.renderProgramRef(n) },
    "button" to Runner { n, _ -> p.renderButtonNode(n) },
    "slider" to Runner { n, _ -> p.renderSliderNode(n) },
    "panels.list" to Runner { n, out -> p.renderPanelsList(n, out.panels ?: jsArray()) },
    "sheet.concentric" to Runner { n, out -> p.loadConcentric(n, out.rendered, out.byStatus, out.byPriority, out.orchestration); Unit },
    "graal.heap" to Runner { n, out -> n._heap = out.heap; p.renderHeap(n) },
    "mux.standings" to Runner { n, out -> n._standings = out.standings; p.renderStandings(n) },
)

/** Shared presentation setup. Retained references are read, never sent back through a runner. */
internal suspend fun Panels.loadConcentric(n: dynamic, board: dynamic, byStatus: dynamic,
                                         byPriority: dynamic, orchestration: dynamic,
                                         current: () -> Boolean = { true }): Boolean {
    val canvas = graph
    val request = obj()
    val previous = n._cur
    n._sheetRequest = request; n._sheetError = null
    n._sheets = null; n._cur = null; n._orch = null
    renderConcentric(n)
    fun active(): Boolean = graph === canvas && n._sheetRequest === request &&
        truthy(n.el.isConnected) && current()
    val idx = js("Object.create(null)")
    val fetched = HashSet<String>()
    suspend fun add(sh: dynamic) {
        if (!active() || !truthy(sh)) return
        if (truthy(sh.id)) { idx[sh.id] = sh; return }
        if (!truthy(sh.sheet)) return
        val href = str(sh.pageHref ?: sh.href)
        if (fetched.add(href)) for (sheet in readSheets(href)) idx[sheet.id] = sheet
        if (active()) require(truthy(idx[sh.sheet])) { "Referenced sheet is absent from its family: ${str(sh.sheet)}" }
    }
    try {
        add(board)
        for (sh in arr(byStatus)) add(sh)
        for (sh in arr(byPriority)) add(sh)
        if (!active()) return false
        n._sheets = idx; n._orch = orchestration
        n._cur = if (truthy(previous) && truthy(idx[previous])) previous
            else if (truthy(board)) board.id ?: board.sheet else board
        n.el.querySelector(".result")?.remove()
        return true
    } catch (e: CancellationException) { throw e }
    catch (e: dynamic) {
        if (active()) n._sheetError = failureText(e)
        throw e
    } finally {
        if (n._sheetRequest === request) {
            n._sheetRequest = null
            if (graph === canvas && truthy(n.el.isConnected) && current()) renderConcentric(n)
        }
    }
}

/** Shared read boundary for initial sheet references and navigation. No curation POST. */
internal suspend fun readSheets(href: String): Array<dynamic> {
    require(href.startsWith("/") && !href.startsWith("//") &&
        '\\' !in href && href.none { it.code <= 32 }) { "Sheet reference requires a local path" }
    val response = api("GET", href)
    val family = if (isArray(response)) arr(response) else arrayOf(response?.sheet)
    require(family.isNotEmpty() && family.all { truthy(it?.id) && isArray(it.columns) && isArray(it.rows) }) {
        str(response?.error ?: "Sheet reference did not return a sheet or family")
    }
    return family
}

internal fun esc(s: dynamic): String = PatchHtml.text(str(s))
internal fun escA(s: dynamic): String = PatchHtml.attr(str(s))
internal fun escNullable(s: dynamic): String = PatchHtml.attr(if (s == null) "" else str(s))

internal fun byId(id: String): dynamic = document.getElementById(id).asDynamic()
internal fun q(sel: String): dynamic = document.querySelector(sel).asDynamic()
internal fun now(): Double = js("Date.now()").unsafeCast<Double>()
