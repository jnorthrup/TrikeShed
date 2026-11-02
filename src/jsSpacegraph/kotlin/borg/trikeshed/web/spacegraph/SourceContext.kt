package borg.trikeshed.web.spacegraph

import borg.trikeshed.docs.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.web.*
import borg.trikeshed.wiki.WikiSurface
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import org.w3c.dom.HTMLElement

internal fun sourceContextValues(): Map<String, List<String>> {
    return try { SourceSelection.queryValues(window.location.search) }
    catch (_: Exception) { mapOf("sourceReceipt" to emptyList()) }
}

/** Only a navigation context; this header does not select a curator book or assert evidence. */
internal fun mountCuratorSourceContext(): HTMLElement? {
    val values = sourceContextValues()
    if (!SourceSelection.requested(values)) return null
    val header = document.createElement("aside") as HTMLElement
    header.setAttribute("aria-label", "Selected source context")
    header.style.cssText = "position:fixed;bottom:12px;left:12px;max-width:620px;padding:12px;background:var(--panel,#101620);color:var(--ink,#ddd);z-index:60;border:1px solid var(--line,#263348)"
    header.innerHTML = try { SourceSelection.parse(values).html() }
        catch (_: Exception) { "<p>Selected source context unavailable: invalid navigation reference.</p>" }
    document.body!!.appendChild(header)
    return header
}

/** A card in the existing camera world, outside program bindings and execution state. */
internal suspend fun Panels.inspectSourceContext(values: Map<String, List<String>>) {
    val lifetime = page
    val card = document.createElement("section") as HTMLElement
    card.className = "document-source-context"
    card.setAttribute("data-source-inspection", "1")
    card.style.cssText = "position:absolute;left:0;top:0;width:740px;max-height:760px;overflow:auto;padding:24px;background:var(--panel,#101620);color:var(--ink,#ddd);border:1px solid var(--line,#263348);border-radius:10px;white-space:normal"
    world.appendChild(card)
    lifetime.own { card.remove() }
    lifetime.on(card, "wheel", { event -> event.stopPropagation() }, objOf { it.passive = true })
    fun present() = !lifetime.closing && truthy(card.asDynamic().isConnected)
    val selected = try { SourceSelection.parse(values) } catch (_: Exception) {
        card.innerHTML = "<p>Source context unavailable: invalid navigation reference. No source was substituted.</p><a href=\"/documents\">Return to Documents</a>"
        status("Invalid source context — inspection only")
        return
    }
    val context = selected.html()
    card.innerHTML = context + "<p role=\"status\">Reading retained passage and current source…</p>"
    suspend fun get(path: String): Any? {
        val response = fetchJs(path)
        require(truthy(response.ok)) { "Source read unavailable (HTTP ${str(response.status)})" }
        return reifyStrict(PatchRead.text(response))
    }
    try {
        val retained = DocumentSurface.curationResult(get("/api/documents?cid=" + WikiSurface.encode(selected.receiptCid)))
        val passage = retained?.takeIf { it.receiptCid == selected.receiptCid }?.let { DocumentSurface.passage(it, selected.proposal) }
        require(passage != null && selected.matches(passage)) { "Retained passage does not match the navigation identity" }
        val current = DocumentSurface.document(get("/api/projects/" + WikiSurface.encode(selected.project) + "/docs/" + WikiSurface.encode(selected.document)))
        if (!present()) return
        card.innerHTML = DocumentSurface.passageHtml(current, passage)
        status("Selected passage inspection — no construction loaded or executed")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Throwable) {
        if (present()) {
            card.innerHTML = context + "<p role=\"status\">Source context unavailable: " +
                DocumentMarkdown.escape(failure.message ?: "Source could not be resolved") + ". No source was substituted.</p>"
            status("Source context unavailable — inspection only")
        }
    }
}
