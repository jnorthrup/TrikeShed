package borg.trikeshed.web.spacegraph

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

/** An explicit Documents navigation loads only an offered construction, never a query or a run. */
internal suspend fun Panels.loadEnglishQuery(raw: String, revision: Int) {
    val canvas = graph
    val before = JSON.stringify(serialize())
    val location = window.location.href
    val controller = js("new AbortController()")
    val forget = page.own { controller.abort() }
    var navigationRevision = 0
    val navigated: (dynamic) -> Unit = { navigationRevision++; controller.abort() }
    page.on(window, "popstate", navigated)
    page.on(window, "hashchange", navigated)
    fun current() = !page.closing && graph === canvas && documentRevision == revision &&
        navigationRevision == 0 && window.location.href == location && JSON.stringify(serialize()) == before
    try {
        val selected = EnglishQueryNavigation.parse(raw)
        if (!current()) return
        status("Checking the selected retained English inspection — no construction loaded or executed")
        val init = jsonInit("POST", jsonOf(selected.request()))
        init.signal = controller.signal
        val response = fetchJs("/api/documents/english/retained", init)
        val body = PatchRead.text(response, 8 * 1024 * 1024)
        if (!current()) return
        val inspection = reifyStrict(body) as? Map<*, *> ?: error("Unreadable retained English inspection")
        require(truthy(response.ok) && inspection["error"] == null) {
            (inspection["detail"] ?: inspection["error"] ?: "Retained English inspection unavailable").toString()
        }
        require(EnglishQueryNavigation.from(inspection) == selected) { "Retained English inspection differs from the selected source or range" }
        val retrieval = inspection["retrieval"] as? Map<*, *> ?: error("Retained English inspection provenance missing")
        require(retrieval["kind"] == "retained-current-inspection" && retrieval["parserExecuted"] == false &&
            retrieval["modelCalled"] == false && retrieval["currentness"] == "checked-before-and-after-open") {
            "Retained English inspection currentness was not verified"
        }
        if (!syncContracts(announce = false)) error("Contract vocabulary is unavailable")
        if (!current()) return
        val catalogInit = objOf { it.signal = controller.signal }
        val catalogResponse = fetchJs("/api/panels/presets", catalogInit)
        require(truthy(catalogResponse.ok)) { "Offered English construction catalog is unavailable" }
        val catalog = JSON.parse<dynamic>(PatchRead.text(catalogResponse, 8 * 1024 * 1024))
        if (!current()) return
        val offered = arr(catalog.presets).singleOrNull { it.name == EnglishQueryNavigation.PRESET }
            ?: error("Offered copular English construction is unavailable")
        require(truthy(offered.document)) { "Offered copular English construction has no document" }
        val draft = fromConfix(JSON.parse<dynamic>(JSON.stringify(offered.document)))
        val receipt = arr(draft.nodes).singleOrNull { it.id == "receipt" }
        val mode = arr(draft.nodes).singleOrNull { it.id == "mode" }
        val query = arr(draft.nodes).singleOrNull { it.id == "query" }
        require(receipt?.type == "scope.in" && receipt.params?.name == "receiptCid" && receipt.params?.kind == "id" &&
            mode?.type == "text.value" && query?.type == "document.english.query") { "Offered copular construction inputs changed" }
        fun input(port: String, from: String): Boolean {
            val wires = arr(draft.wires).filter { it.to[0] == "query" && it.to[1] == port }
            return wires.size == 1 && wires[0].from[0] == from && wires[0].from[1] == "value"
        }
        require(input("receiptCid", "receipt") && input("mode", "mode") &&
            arr(draft.wires).none { it.to[0] == "receipt" || it.to[0] == "mode" }) { "Offered copular construction input wiring changed" }
        receipt.params.default = selected.receiptCid
        mode.params.value = EnglishQueryNavigation.MODE
        if (!current() || !load(draft)) return
        panelName().value = EnglishQueryNavigation.PRESET.removePrefix("preset-")
        mountEnglishQueryContext(selected)
        fit()
        status("Selected English inspection loaded in current mode — no execution; inspect the construction, then Run. Saved scratch is unchanged.")
        launchJs { buildPalette() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: dynamic) {
        if (current()) refuseLoad("English inspection handoff unavailable: " + failureText(failure))
    } finally {
        page.off(window, "popstate", navigated)
        page.off(window, "hashchange", navigated)
        forget()
    }
}

/** Selected-source context is outside the program and is never a result witness or review authority. */
private fun Panels.mountEnglishQueryContext(selected: EnglishQueryNavigation.Selection) {
    val canvas = graph
    val receipt = node("receipt")
    val mode = node("mode")
    val query = node("query")
    val wires = JSON.stringify(graph.wires)
    val card = document.createElement("aside").asDynamic()
    card.id = "englishQueryContext"; card.setAttribute("data-english-query-origin", "1")
    card.setAttribute("aria-label", "Selected Documents source context")
    card.style.cssText = "position:fixed;bottom:48px;right:16px;width:min(440px,85vw);max-height:40vh;overflow:auto;padding:14px;background:var(--panel,#101620);color:var(--ink,#ddd);border:1px solid var(--line,#263348);border-radius:8px;z-index:60;white-space:normal;overflow-wrap:anywhere"
    fun text(tag: String, value: String) {
        val item = document.createElement(tag).asDynamic(); item.textContent = value; card.appendChild(item)
    }
    text("strong", "Selected Documents source context")
    text("p", "${selected.project} / ${selected.document} · revision ${selected.revision} · sequence ${selected.sequence} · range [${selected.begin}, ${selected.end})")
    text("p", "Inspection I: ${selected.receiptCid}")
    text("p", "Original: ${selected.originalCid}")
    text("p", "Navigation context only. Currentness was checked when opened; Run checks it again. This card is not a query result or review authority. Syntax matches can include negation, modality and conditions.")
    val backlink = document.createElement("a").asDynamic()
    backlink.href = selected.documentsHref(); backlink.textContent = "Return to selected source in Documents"
    card.appendChild(backlink)
    document.body!!.appendChild(card)
    page.own { card.remove() }
    var bound = true
    page.on(window, "lcnc:shadow-update", {
        if (graph !== canvas) card.remove()
        else if (bound && (node("receipt") !== receipt || node("mode") !== mode || node("query") !== query ||
            receipt.type != "scope.in" || receipt.params?.name != "receiptCid" || receipt.params?.kind != "id" ||
            receipt.params?.default != selected.receiptCid || mode.type != "text.value" || mode.params?.value != EnglishQueryNavigation.MODE ||
            query.type != "document.english.query" || JSON.stringify(graph.wires) != wires)) {
            bound = false
            card.removeAttribute("data-english-query-origin")
            card.setAttribute("aria-label", "Previous Documents source context cleared")
            card.textContent = "Previous Documents source context cleared because query inputs changed. Inspect the edited receipt and source mode before running."
        }
    })
    page.on(window, "popstate", { card.remove() })
    page.on(window, "hashchange", { card.remove() })
}
