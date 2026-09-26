package borg.trikeshed.web.graal

import borg.trikeshed.web.MuxIcons
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.url.URL
import kotlin.js.Promise

/** Allocation Sites dialog (allocation-inspector.js): JFR-sampled stacks per class, frames resolved to classpath source + bytecode. */
object AllocationInspector {
    private val scope = MainScope()
    private var dialog: dynamic = null
    private var controller: dynamic = null
    private var frameController: dynamic = null
    private var selectedClass = ""
    private var origin: dynamic = null
    private var data: dynamic = null
    private var activeFrame: dynamic = null

    private fun bytes(n: dynamic): String = toFixed((if (num(n).isNaN()) 0.0 else num(n)) / 1048576, 2) + " MiB"

    private fun icon(name: String, title: String, action: () -> Unit): HTMLElement {
        val b = el("button", "alloc-icon"); b.asDynamic().type = "button"; b.title = title; b.setAttribute("aria-label", title)
        val i = el("i"); i.asDynamic().dataset.lucide = name; b.append(i); b.addEventListener("click", { action() }); return b
    }

    private fun q(s: String): HTMLElement = dialog.querySelector(s).unsafeCast<HTMLElement>()

    private fun mount() {
        if (dialog != null) return
        val d = el("dialog", "alloc-inspector"); d.id = "allocationInspector"; d.setAttribute("aria-labelledby", "allocationTitle")
        dialog = d
        val header = el("header", "alloc-header"); val title = el("h2", null, "Allocation Sites"); title.id = "allocationTitle"
        header.append(title, icon("refresh-cw", "Refresh allocation sites") { load() }, icon("download", "Download allocation evidence") { download() },
            icon("x", "Close allocation sites") { dialog.close() })
        val form = el("form", "alloc-toolbar"); val label = el("label", null, "Class"); val input = el("input").unsafeCast<HTMLInputElement>()
        input.name = "class"; input.id = "allocationClass"; input.setAttribute("list", "allocationClasses"); input.autocomplete = "off"; label.asDynamic().htmlFor = input.id
        val list = el("datalist"); list.id = "allocationClasses"
        form.append(label, input, list, icon("search", "Inspect allocated class") { selectedClass = input.value.trim(); load() })
        form.addEventListener("submit", { e -> e.preventDefault(); selectedClass = input.value.trim(); load() })
        val status = el("div", "alloc-status"); status.setAttribute("role", "status")
        val body = el("div", "alloc-layout"); val sites = el("nav", "alloc-sites"); sites.setAttribute("aria-label", "Allocation stacks")
        val detail = el("section", "alloc-detail"); body.append(sites, detail)
        d.append(header, form, status, body); document.body!!.append(d)
        d.addEventListener("close", { controller?.abort(); frameController?.abort() })
        for (type in listOf("keydown", "pointerdown", "pointermove", "wheel")) d.addEventListener(type, { e -> e.stopPropagation() })
        val icons: dynamic = window.asDynamic().MuxIcons
        if (icons != null) icons() else MuxIcons.render()
    }

    private suspend fun request(path: String, signal: dynamic): dynamic {
        val init: dynamic = newObject(); init.signal = js("AbortSignal.any([signal, AbortSignal.timeout(10000)])")
        val r: dynamic = window.fetch(path, init).await()
        if (!truthy(r.ok)) throw Error(if (r.status == 404) "Site expired; refresh the recent samples." else "HTTP " + str(r.status))
        return r.json().unsafeCast<Promise<dynamic>>().await()
    }

    private fun load() {
        controller?.abort(); frameController?.abort(); controller = js("new AbortController()"); val signal: dynamic = controller.signal
        q("input").unsafeCast<HTMLInputElement>().value = selectedClass
        q(".alloc-status").textContent = "Loading recent samples"; q(".alloc-sites").asDynamic().replaceChildren(); q(".alloc-detail").asDynamic().replaceChildren()
        activeFrame = null; data = null
        scope.launch {
            try {
                val result: dynamic = request("/api/graal/allocations?class=" + encUri(selectedClass), signal)
                if (truthy(signal.aborted)) return@launch
                data = result
                val classes = (orr(result.classes, js("[]"))).unsafeCast<Array<dynamic>>()
                val datalist = q("datalist"); datalist.asDynamic().replaceChildren()
                for (c in classes) { val o = el("option"); o.asDynamic().value = c["class"]; datalist.append(o) }
                var status = bytes(result.bytes) + " sampled / " + str(result.windowSeconds) + "s | " + str(result.samples) + " samples | " +
                    str(js("new Date(result.toMs).toLocaleTimeString()"))
                if (origin != null && origin["class"] == selectedClass && origin.bytes != null)
                    status += " | " + (if (origin.kind == "live") "Live histogram: " else "Since start: ") + bytes(origin.bytes)
                if (truthy(result.omittedSamples)) status += " | Capacity omitted " + str(result.omittedSamples) + " samples (" + bytes(result.omittedBytes) + ", all classes)"
                if (truthy(result.omittedSiteRows)) status += " | " + str(result.omittedSiteRows) + " additional sites"
                if (!truthy(result.jfr)) status += " | JFR unavailable: " + str(orr(result.jfrError, "not running"))
                q(".alloc-status").textContent = status
                val sites = orr(result.sites, js("[]")).unsafeCast<Array<dynamic>>()
                if (sites.isEmpty()) { q(".alloc-detail").textContent = "No samples for this class in the recent window."; return@launch }
                sites.forEachIndexed { i, site ->
                    val b = el("button", "alloc-site"); val top: dynamic = if (site.frames != null) site.frames[0] else null
                    b.asDynamic().type = "button"
                    b.append(el("strong", null, bytes(site.bytes)), el("span", null, if (truthy(top)) str(top["class"]) + "." + str(top.method) else "Stack unavailable"),
                        el("small", null, str(site.samples) + " samples" + (if (truthy(site.truncated)) " | truncated stack" else "")))
                    b.addEventListener("click", { showSite(site, b) }); q(".alloc-sites").append(b)
                    if (i == 0) showSite(site, b)
                }
            } catch (e: Throwable) {
                if (!truthy(signal.aborted)) q(".alloc-status").textContent = "Allocation sites unavailable: " + e.message
            }
        }
    }

    private fun showSite(site: dynamic, button: HTMLElement) {
        frameController?.abort(); activeFrame = null
        for (b in q(".alloc-sites").children.asList()) b.removeAttribute("aria-current")
        button.setAttribute("aria-current", "true")
        val host = q(".alloc-detail"); host.asDynamic().replaceChildren(); val frames = el("div", "alloc-frames")
        frames.append(el("h3", null, "Sampled Stack"))
        val fs = orr(site.frames, js("[]")).unsafeCast<Array<dynamic>>()
        fs.forEachIndexed { i, f ->
            val b = el("button", "alloc-frame"); b.asDynamic().type = "button"; b.title = str(f["class"]) + "." + str(f.method) + str(f.descriptor)
            b.append(el("span", "alloc-method", str(f["class"]) + "." + str(f.method)),
                el("small", null, "line " + str(f.line) + " | BCI " + str(f.bci) + " | " + str(f.execution)))
            b.addEventListener("click", { showFrame(site.id, i, b) }); frames.append(b)
        }
        val code = el("section", "alloc-code"); code.append(el("h3", null, "Classpath"))
        code.append(el("p", "alloc-muted", if (fs.isNotEmpty()) "No frame selected." else "No stack was recorded for these samples."))
        host.append(frames, code)
        val evidence = el("details", "alloc-evidence"); evidence.append(el("summary", null, "AOT / Measurement"))
        val aot: dynamic = (if (data != null) data.aot else null) ?: newObject()
        evidence.append(el("p", null, "AOT input: " + str(orr(aot.cacheInput, "no explicit cache input")) + " | mode: " + str(orr(aot.mode, "unknown"))),
            el("p", null, "Per-site AOT attribution unavailable. Sampled allocations are not retained objects or GC-root paths."))
        host.append(evidence)
    }

    private fun showFrame(site: dynamic, index: Int, button: HTMLElement) {
        frameController?.abort(); frameController = js("new AbortController()"); val signal: dynamic = frameController.signal
        for (b in q(".alloc-frames").querySelectorAll("button").asList()) b.unsafeCast<HTMLElement>().removeAttribute("aria-current")
        button.setAttribute("aria-current", "true")
        val code = q(".alloc-code"); code.textContent = "Resolving classpath frame"
        scope.launch {
            try {
                val f: dynamic = request("/api/graal/allocation-frame?site=" + encUri(str(site)) + "&frame=" + index, signal)
                if (truthy(signal.aborted)) return@launch
                activeFrame = f
                code.asDynamic().replaceChildren(el("h3", null, "Classpath / " + str(orr(f.sourceFile, f["class"]))))
                if (!truthy(f.available)) { code.append(el("p", null, str(orr(f.reason, "Class resource unavailable")))); return@launch }
                code.append(el("p", "alloc-origin", str(f.resource)), el("p", "alloc-muted", str(f.bytecodeEvidence)))
                if (truthy(f.source)) {
                    val pre = el("pre", "alloc-source")
                    f.source.lines.unsafeCast<Array<dynamic>>().forEachIndexed { i, line ->
                        val number = num(f.source.startLine).toInt() + i
                        val row = el("span", if (number.toDouble() == num(f.line)) "alloc-sampled" else "")
                        row.textContent = number.toString().padStart(5) + "  " + str(line); pre.append(row)
                    }
                    code.append(el("p", "alloc-origin", str(f.source.id)), pre)
                } else code.append(el("p", "alloc-muted", "Source unavailable or ambiguous."))
                val pre = el("pre", "alloc-bytecode")
                val instructions = orr(f.instructions, js("[]")).unsafeCast<Array<dynamic>>()
                for (ins in instructions) {
                    val row = el("span", (if (truthy(ins.sampled)) "alloc-sampled " else "") + (if (truthy(ins.boxing)) "alloc-boxing" else ""))
                    row.textContent = str(ins.bci).padStart(5) + "  " + str(ins.opcode) + " " + (if (truthy(ins.owner)) str(ins.owner) + "." else "") +
                        s0(orr(ins.name, "")) + " " + s0(orr(ins.descriptor, "")) + (if (truthy(ins.boxing)) "  [boxing]" else "")
                    pre.append(row)
                }
                if (instructions.isEmpty()) pre.textContent = if (truthy(f.methodFound)) "No bytecode for this method." else "Method not found in this class resource."
                code.append(el("h3", null, "Bytecode"), pre)
                if (!truthy(f.bciMatched)) code.append(el("p", "alloc-muted", "Recorded BCI unavailable in this class resource."))
                code.append(el("p", "alloc-muted", str(f.sourceEvidence)))
                val o: dynamic = newObject(); o.block = "nearest"; code.asDynamic().scrollIntoView(o)
            } catch (e: Throwable) {
                if (!truthy(signal.aborted)) code.textContent = e.message
            }
        }
    }

    private fun download() {
        if (data == null) return
        val payload: dynamic = newObject(); payload.allocation = data; payload.frame = activeFrame
        val json = jsonIndent(payload, 2)
        val url: String = js("URL.createObjectURL(new Blob([json],{type:'application/json'}))")
        val a = el("a"); a.asDynamic().href = url; a.asDynamic().download = "allocation-sites.json"; a.click()
        window.setTimeout({ js("URL.revokeObjectURL(url)") }, 1000)
    }

    /** Open the dialog on [name]; [context] `{kind, bytes}` annotates the status line. */
    fun open(name: String = "java.lang.Integer", context: dynamic = null) {
        mount(); selectedClass = name
        origin = if (context != null) { val o: dynamic = js("Object.assign({}, context)"); o["class"] = name; o } else null
        val blipLeave: dynamic = window.asDynamic().blipLeave
        if (typeOf(blipLeave) == "function") blipLeave()
        if (!truthy(dialog.open)) dialog.showModal()
        load()
    }

    /** Publish `window.AllocationInspector`; `?allocation=` opens it at load. */
    fun install() {
        val api: dynamic = newObject()
        api.open = { name: dynamic, context: dynamic -> open(if (name == undefined) "java.lang.Integer" else str(name), context ?: null) }
        window.asDynamic().AllocationInspector = api
        val params = URL(window.location.href).searchParams
        if (params.has("allocation")) open(params.get("allocation")?.ifEmpty { null } ?: "java.lang.Integer")
    }
}
