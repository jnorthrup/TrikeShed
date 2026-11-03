package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.wiki.WikiSurface
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/** Explicit host inventory and selected collection; this component never fetches a PDF's URLs. */
internal object DocumentsPdfLinks {
    private var owner: Controller? = null
    fun clear() { owner?.close(); owner = null }
    fun mount(host: HTMLElement, source: DocRevision, generation: Int, current: () -> Boolean) {
        val previous = owner
        val controller = if (previous != null && previous.source == source && previous.generation == generation) previous
            else { clear(); Controller(source, generation).also { owner = it } }
        controller.host = host; controller.current = current; controller.render()
    }

    private class Controller(val source: DocRevision, val generation: Int) {
        lateinit var host: HTMLElement
        var current: () -> Boolean = { false }
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Main)
        private var abort: dynamic = null
        private var inventory: Map<*, *>? = null
        private var results: List<Map<*, *>> = emptyList()
        private val selected = mutableSetOf<String>()
        private var pending = false
        private var failure: String? = null
        fun close() { abort?.abort(); job.cancel() }
        private fun present() = owner === this && job.isActive && current() && host.asDynamic().isConnected == true
        private fun revision() = _m["project" j source.project, "id" j source.id, "cid" j source.cid, "rev" j source.rev, "seq" j source.seq]
        private fun matches(value: Any?): Boolean {
            val row = value as? Map<*, *> ?: return false
            return row["project"] == source.project && row["id"] == source.id && row["cid"] == source.cid &&
                row["rev"] == source.rev && (row["seq"] as? Number)?.toDouble() == source.seq.toDouble()
        }
        private fun cid(value: Any?) = value is String && Regex("^sha256:[0-9a-f]{64}$").matches(value)
        private fun followable(link: Map<*, *>): Boolean = link["followable"] == true && runCatching {
            val uri = link["uri"] as? String ?: return@runCatching false
            if (uri.length > 8192) return@runCatching false
            val url = URL(uri)
            url.protocol in _s["http:", "https:"] && url.hostname.isNotBlank() && url.username.isEmpty() && url.password.isEmpty()
        }.getOrDefault(false)
        private fun text(parent: HTMLElement, tag: String, value: String): HTMLElement =
            (document.createElement(tag) as HTMLElement).also { it.textContent = value; parent.appendChild(it) }
        private fun button(parent: HTMLElement, label: String, disabled: Boolean, action: () -> Unit) {
            val button = text(parent, "button", label)
            button.setAttribute("type", "button"); button.asDynamic().disabled = disabled
            button.addEventListener("click", { if (present() && !pending) action() })
        }
        private suspend fun post(path: String, body: Map<*, *>): Map<*, *> {
            abort = js("new AbortController()")
            val init = RequestInit(method = "POST", body = jsonOf(body), headers = Headers().apply { append("Content-Type", "application/json") })
            init.asDynamic().signal = abort.signal
            val response = window.fetch(path, init).await()
            val value = reifyStrict(response.text().await()) as? Map<*, *> ?: error("Unreadable PDF link response")
            require(response.ok && value["error"] == null) { (value["detail"] ?: value["error"] ?: "PDF link request failed (HTTP ${response.status})").toString() }
            require(matches(value["selection"])) { "PDF link response belongs to another source revision" }
            return value
        }
        private fun find() {
            pending = true; failure = null; render()
            scope.launch {
                try {
                    val value = post("/api/documents/pdf-links", revision())
                    require(cid(value["receiptCid"]) && value["sourceCid"] == source.cid && value["reviewStatus"] == "unreviewed") { "PDF inventory identity is unavailable" }
                    require(value["status"] in _s["AVAILABLE", "UNAVAILABLE", "LIMIT"]) { "Unknown PDF inventory status" }
                    val links = value["links"] as? List<*> ?: error("PDF inventory links are unavailable")
                    require(links.size <= 256 && links.all { it is Map<*, *> && it["id"] is String }) { "PDF inventory exceeds the supported display bound" }
                    val ids = links.map { (it as Map<*, *>)["id"] as String }
                    require(ids.distinct().size == ids.size && ids.all { it.length in 1..128 && it.none { ch -> ch < ' ' } }) { "PDF inventory link identity is ambiguous" }
                    if (!present()) return@launch
                    inventory = value; selected.clear(); results = emptyList()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (present()) failure = error.message ?: "PDF links unavailable" }
                finally { if (present()) { pending = false; render() } }
            }
        }
        private fun collect() {
            val retained = inventory ?: return
            if (selected.isEmpty() || selected.size > 8) return
            val ids = selected.toSet()
            pending = true; failure = null; render()
            scope.launch {
                try {
                    val value = post("/api/documents/pdf-links/collect", _m["inventoryCid" j retained["receiptCid"], "selectionIds" j ids.toList()])
                    require(value["action"] == "retained-unreviewed-not-model-curated") { "Unexpected PDF link collection response" }
                    val documents = (value["documents"] as? List<*>)?.map { it as? Map<*, *> ?: error("Invalid collection result") }
                        ?: error("Collection results unavailable")
                    require(documents.size == ids.size && documents.map { it["id"] }.toSet() == ids) { "Collection results differ from the selected links" }
                    if (!present()) return@launch
                    results = documents
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (present()) failure = error.message ?: "Link collection unavailable" }
                finally { if (present()) { pending = false; render() } }
            }
        }
        fun render() {
            if (!present()) return
            host.textContent = ""
            val section = document.createElement("section") as HTMLElement
            section.setAttribute("data-pdf-links", "1"); host.appendChild(section)
            section.style.cssText = "max-width:900px;padding:24px;overflow-wrap:anywhere"
            text(section, "h2", "PDF links · ${source.id}")
            text(section, "p", "Find link candidates in this exact PDF revision. Collection is explicit, limited to selected HTTP(S) links, and may be refused by host network policy. Retained content stays unreviewed; no model is called.")
            button(section, "Find PDF links", pending, ::find)
            if (pending) text(section, "p", "PDF link request in progress…").setAttribute("role", "status")
            failure?.let { text(section, "p", it).setAttribute("role", "status") }
            val value = inventory ?: return
            text(section, "p", "Inventory: ${value["status"]} · unreviewed · receipt ${value["receiptCid"]}")
            text(section, "p", "Coverage: ${value["coverage"].toString().take(2048)}")
            (value["diagnostic"] as? String)?.takeIf { it.isNotBlank() }?.let { text(section, "p", it.take(2048)) }
            text(section, "p", "Select up to 8 HTTP(S) links to collect. Other PDF actions are displayed as candidates only.")
            for (item in (value["links"] as List<*>)) {
                val link = item as Map<*, *>
                val id = link["id"] as String
                val label = text(section, "label", "")
                label.style.display = "block"
                val checkbox = document.createElement("input") as HTMLInputElement
                checkbox.type = "checkbox"; checkbox.checked = id in selected
                checkbox.disabled = pending || !followable(link) || selected.size >= 8 && id !in selected
                checkbox.setAttribute("data-pdf-link-id", id); label.appendChild(checkbox)
                label.appendChild(document.createTextNode(" Page ${link["page"]} · ${link["type"]} · candidate: ${link["uri"]}"))
                checkbox.addEventListener("change", { if (present() && !pending) { if (checkbox.checked) selected.add(id) else selected.remove(id); render() } })
                (link["reason"] as? String)?.let { text(section, "p", it.take(2048)) }
            }
            button(section, "Collect selected links", pending || selected.isEmpty(), ::collect)
            for (result in results) {
                if (result["status"] == "failed") {
                    text(section, "p", "${result["id"]}: not collected · ${result["error"]}")
                    continue
                }
                val project = result["project"] as? String
                val id = result["documentId"] as? String
                if (project.isNullOrBlank() || id.isNullOrBlank() || project.length > 1024 || id.length > 1024 || !cid(result["cid"]) || result["reviewStatus"] != "unreviewed") {
                    text(section, "p", "Collection result could not be verified."); continue
                }
                val row = text(section, "p", "${result["id"]}: retained, unreviewed · ")
                val link = text(row, "a", "$project / $id")
                link.setAttribute("href", "/documents?collectedPdfLink=1#${WikiSurface.encode(project)}/${WikiSurface.encode(id)}")
                link.setAttribute("rel", "noopener")
            }
        }
    }
}
