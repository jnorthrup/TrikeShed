package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lib.*
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** CID-pinned text inspection in the existing camera world. Reading never executes a node. */
fun Panels.renderDocumentSource(n: dynamic, out: dynamic) {
    setResult(n, out)
    val selected = n.type == "confix.pickPath"
    val source = if (selected) out.value else out.source
    if (source == null || typeOf(source) != "object" || (selected && typeOf(source.quote) != "string")) return
    val original = str(source.originalCid)
    val extracted = str(source.extractedTextCid)
    val cidPattern = Regex("^sha256:[0-9a-f]{64}$")
    if (!cidPattern.matches(original) || !cidPattern.matches(extracted)) return
    val target = n.el.querySelector(".result") ?: return
    target.style.cssText = "width:360px;max-width:none;max-height:none;overflow:visible;white-space:normal;font:14px/1.5 var(--mono);color:var(--ink);resize:none"
    val fields = document.createElement("details").asDynamic()
    val summary = document.createElement("summary").asDynamic()
    summary.textContent = "Retained output fields"; fields.appendChild(summary)
    while (target.firstChild != null) fields.appendChild(target.firstChild)
    target.appendChild(fields)
    val canvas = graph
    val definition = LcncProgramConfix.fromJson(str(panelName().value).ifBlank { "canvas" }, JSON.stringify(toConfix()))
    val card = document.createElement("section").asDynamic()
    card.setAttribute("aria-label", if (selected) "Selected retained passage" else "Retained document")
    card.style.cssText = "padding:12px;white-space:normal;overflow-wrap:anywhere;border:1px solid var(--line);border-radius:8px;margin-bottom:10px"
    target.insertBefore(card, target.firstChild)
    page.on(card, "wheel", { event -> event.stopPropagation() }, objOf { it.passive = true })
    fun current(): Boolean = !page.closing && graph === canvas && n._lastOut === out && truthy(card.isConnected) &&
        LcncProgramConfix.sameDefinition(definition,
            LcncProgramConfix.fromJson(str(panelName().value).ifBlank { "canvas" }, JSON.stringify(toConfix())))
    fun text(tag: String, value: String): dynamic {
        val element = document.createElement(tag).asDynamic()
        element.textContent = value; element.style.margin = "6px 0"; card.appendChild(element)
        return element
    }
    text("strong", if (selected) "Selected passage · native JSON geometry" else str(source.name ?: "Retained document"))
    text("p", "Extracted text is a derivative. Original bytes and extracted text have separate content identities.")
    if (selected) {
        text("blockquote", str(source.quote)).style.cssText = "margin:10px 0;padding:10px;border-left:3px solid var(--wire);white-space:pre-wrap"
        val pages = arr(source.anchors).mapNotNull { anchor ->
            if (typeOf(anchor.page) == "number" && num(anchor.page).isFinite() && num(anchor.page) >= 1 && num(anchor.page) % 1.0 == 0.0)
                str(anchor.page) else null
        }.distinct()
        text("p", "UTF-16 [" + str(source.begin) + ", " + str(source.end) + ") · " +
            if (pages.isEmpty()) "physical page unresolved" else "physical PDF page " + pages.joinToString(", "))
        text("p", "Text selection only; no legal conclusion or source authentication is asserted.")
    }
    for ((label, cid) in s_["Original bytes" j original, "Extracted text" j extracted].view) {
        val link = document.createElement("a").asDynamic()
        link.textContent = "$label · ${cid.takeLast(12)}"; link.title = cid
        link.href = "/api/v0/block/get?arg=" + encodeURIComponent(cid)
        link.download = ""; link.rel = "noopener noreferrer"
        link.style.cssText = "display:block;margin:6px 0;color:var(--wire)"
        page.on(link, "click", { event -> if (!current()) { event.preventDefault(); status("Program changed; reopen the retained result.") } })
        card.appendChild(link)
    }
    val button = document.createElement("button").asDynamic()
    button.textContent = if (selected) "Verify quotation against retained text" else "Load retained text"
    card.appendChild(button)
    val message = text("p", "No source read requested.")
    message.setAttribute("role", "status")
    val body = text("pre", "")
    body.setAttribute("aria-label", if (selected) "Verified passage context" else "Retained extracted text")
    body.style.cssText = "white-space:pre-wrap;max-height:240px;overflow:auto;font:14px/1.6 var(--mono);margin:10px 0"
    page.on(button, "click", { event ->
        event.stopPropagation()
        if (current()) {
            button.disabled = true; message.textContent = "Reading retained text…"
            launchJs {
                try {
                    val response = fetchJs("/api/v0/block/get?arg=" + encodeURIComponent(extracted))
                    require(truthy(response.ok)) { "Retained text unavailable" }
                    val bytes = PatchRead.bytes(response, 4 * 1024 * 1024)
                    val digest = awaitJs(js("crypto.subtle.digest('SHA-256', bytes)"))
                    val actual: String = "sha256:" + js("Array.from(new Uint8Array(digest), function(b){return b.toString(16).padStart(2,'0')}).join('')")
                    require(actual == extracted) { "Retained text content identity mismatch" }
                    val retained: String = js("new TextDecoder('utf-8', {fatal:true}).decode(bytes)")
                    require(current()) { "Program or result changed during source read" }
                    if (selected) {
                        fun offset(value: dynamic): Int {
                            require(typeOf(value) == "number" && num(value).isFinite() && num(value) >= 0 &&
                                num(value) <= retained.length && num(value) % 1.0 == 0.0) { "Invalid retained quotation range" }
                            return num(value).toInt()
                        }
                        val begin = offset(source.begin); val end = offset(source.end)
                        require(end > begin && retained.substring(begin, end) == str(source.quote)) { "Quotation does not match retained text" }
                        body.textContent = retained.substring(maxOf(0, begin - 300), minOf(retained.length, end + 300))
                        message.textContent = "SHA-256 verified · quotation matches exact UTF-16 range."
                    } else {
                        body.textContent = retained.take(65536)
                        message.textContent = "SHA-256 verified · " + retained.length + " UTF-16 units" +
                            if (retained.length > 65536) " · display limited to first 65536; download retains the full text." else " · full extraction shown."
                    }
                    growRingWorldFor(n); resolveTopLevelOverlaps(); redraw()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: dynamic) { if (current()) message.textContent = "Source inspection refused: " + failureText(failure) }
                finally { if (current()) button.disabled = false }
            }
        } else status("Program changed; reopen the retained result.")
    })
}
