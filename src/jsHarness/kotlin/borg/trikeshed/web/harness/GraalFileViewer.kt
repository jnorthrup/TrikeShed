package borg.trikeshed.web.harness

import borg.trikeshed.graal.console.Projection
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import org.w3c.dom.url.URL
import org.w3c.fetch.Response
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.Promise

/** graal-file-viewer.js: byte projections of a blob into a mount element. Published as `window.GraalFileViewer`. */
@OptIn(DelicateCoroutinesApi::class)
object GraalFileViewer {
    private class State(val controller: AbortController, val urls: ArrayList<String> = ArrayList())

    private val states: dynamic = js("new WeakMap()")

    private fun fmtBytes(b: Double) = Projection.fmtBytesViewer(b)

    private fun ByteArrayOf(bytes: Uint8Array): ByteArray = Int8Array(bytes.buffer, bytes.byteOffset, bytes.length).unsafeCast<ByteArray>()

    private fun localUrl(value: String): String {
        val url = URL(value, window.location.href)
        if (url.origin != window.location.origin || (url.protocol != "http:" && url.protocol != "https:")) throw Error("Non-local blob URL")
        return url.pathname + url.search
    }

    suspend fun readBytes(response: Response, limit: Int = FileViewerRules.LIMIT): Uint8Array {
        val reader: dynamic = response.body?.asDynamic()?.getReader() ?: throw Error("Response stream unavailable")
        var size = 0
        val chunks = ArrayList<Uint8Array>()
        try {
            if (jsNumber(response.headers.get("content-length")) > limit) throw Error("Preview exceeds " + fmtBytes(limit.toDouble()))
            while (true) {
                val part: dynamic = (reader.read() as Promise<dynamic>).await()
                if (part.done == true) break
                val value = part.value.unsafeCast<Uint8Array>()
                size += value.byteLength
                if (size > limit) throw Error("Preview exceeds " + fmtBytes(limit.toDouble()))
                chunks.add(value)
            }
        } catch (error: Throwable) {
            try { (reader.cancel() as Promise<dynamic>).await() } catch (_: Throwable) {}
            throw error
        } finally {
            reader.releaseLock()
        }
        val bytes = Uint8Array(size)
        var offset = 0
        for (chunk in chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
        return bytes
    }

    private class Measure(val raw: Int, val gzip: Int, val ratio: Double)

    private suspend fun measure(bytes: Uint8Array): Measure? {
        if (bytes.byteLength == 0) return Measure(0, 0, 0.0)
        if (jsTypeOf(window.asDynamic().CompressionStream) == "undefined") return null
        val stream: dynamic = Blob(arrayOf(bytes)).asDynamic().stream().pipeThrough(js("new CompressionStream('gzip')"))
        val compressed = readBytes(Response(stream), FileViewerRules.LIMIT + 65536)
        return Measure(bytes.byteLength, compressed.byteLength, compressed.byteLength.toDouble() / bytes.byteLength)
    }

    private fun decodeFatal(bytes: Uint8Array): String? = try {
        js("new TextDecoder('utf-8',{fatal:true})").decode(bytes) as String
    } catch (_: Throwable) { null }

    private fun decode(bytes: Uint8Array): String = js("new TextDecoder()").decode(bytes) as String

    private fun head(bytes: Uint8Array): IntArray = IntArray(minOf(4, bytes.length)) { bytes[it].toInt() and 0xFF }

    fun kind(bytes: Uint8Array, type: String = "", id: String = ""): String =
        FileViewerRules.kind(head(bytes), type, id) { decodeFatal(bytes) }

    fun chip(cid: String?, label: String = "blob"): String = FileViewerRules.chip(cid, label)

    fun bindRefs(mount: Element, reference: dynamic = null) {
        val buttons = mount.querySelectorAll("[data-blob-cid]")
        for (i in 0 until buttons.length) {
            val button = buttons.item(i).unsafeCast<HTMLElement>()
            button.addEventListener("click", { e ->
                e.stopPropagation()
                // Framed archive content has a document CID but is restored through its manifest.
                val cid = button.dataset["blobCid"]
                if (reference != null && reference.cid == cid) {
                    val copy: dynamic = js("Object.assign({}, reference)"); copy.signal = undefined; open(copy)
                } else { val r: dynamic = jsObject(); r.cid = cid; open(r) }
            })
        }
    }

    fun dispose(mount: Element) {
        val state = states.get(mount).unsafeCast<State?>() ?: return
        state.controller.abort()
        for (url in state.urls) URL.revokeObjectURL(url)
        states.delete(mount)
    }

    private suspend fun fetchLimited(url: String, state: State, limit: Int): Pair<Response, dynamic> {
        val response = fetchResponse(url, requestInit(signal = state.controller.signal))
        val data: dynamic = JSON.parse(decode(readBytes(response, limit)))
        return response to data
    }

    private suspend fun sourceMates(id: String, mount: Element, state: State) {
        val region = el("div", "file-mates"); mount.append(region)
        try {
            val (response, data) = fetchLimited("/api/graal/decompile?source=" + encodeURIComponent(id), state, FileViewerRules.LIMIT)
            if (state.controller.signal.aborted) return
            if (!response.ok) { region.textContent = "Class projection unavailable: " + (if (truthy(data.error)) data.error else response.status); return }
            val mates = ((data.mates ?: js("[]")) as Array<dynamic>).take(24)
            region.append(el("p", "file-note", if (mates.isNotEmpty()) "SourceFile mates: " + mates.size else "No SourceFile-mated class blob"))
            for (mate in mates) {
                val detail = el("details", "mate")
                val title = el("summary", null, FileViewerRules.mateTitle(jsString(mate.className), truthy(mate.exactRuntimeBlob), truthy(mate.onClasspath)))
                detail.append(title)
                val body = el("div", "matebody")
                body.innerHTML = chip(mate.blobCid as String?) + (if (truthy(mate.runtimeCid)) chip(mate.runtimeCid as String?, "runtime") else "") +
                    Projection.codeViewTokens(text(mate.decompiler?.pseudoSource).ifEmpty { "No class projection" }, 2400)
                detail.append(body); region.append(detail)
            }
            bindRefs(region)
        } catch (error: Throwable) {
            if (!state.controller.signal.aborted) region.textContent = "Class projection unavailable: " + errorMessage(error)
        }
    }

    private fun text(v: dynamic): String = if (truthy(v)) jsString(v) else ""

    private suspend fun classProjection(id: String, mount: Element, state: State) {
        val region = el("section", "classdetail"); region.append(el("p", "file-note", "Loading class projection")); mount.append(region)
        try {
            val (response, data) = fetchLimited("/api/graal/classfile?id=" + encodeURIComponent(id), state, 2097152)
            if (state.controller.signal.aborted) return
            if (!response.ok || truthy(data.error)) {
                region.replaceKids(el("p", "file-error", "Class projection unavailable: " + (if (truthy(data.error)) data.error else response.status))); return
            }
            val methods = if (js("Array").isArray(data.methods) as Boolean) (data.methods as Array<dynamic>).take(80) else emptyList()
            val fields = if (js("Array").isArray(data.fields) as Boolean) (data.fields as Array<dynamic>).take(40) else emptyList()
            val interfaces = if (js("Array").isArray(data.interfaces) as Boolean) (data.interfaces as Array<dynamic>).joinToString(", ") { jsString(it) } else ""
            region.innerHTML = FileViewerRules.classHeading(text(data.className).ifEmpty { text(data.id) }.ifEmpty { id }) + FileViewerRules.projectionTable(listOf(
                "super" to text(data.superClass),
                "interfaces" to interfaces,
                "source" to text(data.sourceFile),
                "classpath" to FileViewerRules.tristate(data.onClasspath, "present", "absent"),
                "exact runtime blob" to FileViewerRules.tristate(data.exactRuntimeBlob, "true", "false"),
                "api" to text(data.classfileApi).ifEmpty { "java.lang.classfile" },
            ))
            if (fields.isNotEmpty()) {
                val table = el("table", "class-members")
                table.innerHTML = FileViewerRules.fieldRows(fields.map { text(it.name) to text(it.descriptor) })
                region.append(table)
            }
            if (methods.isNotEmpty()) {
                val table = el("table", "class-members")
                table.innerHTML = FileViewerRules.methodRows(methods.map {
                    Triple(text(it.name), text(it.descriptor), if (js("Array").isArray(it.instructions) as Boolean) jsString(it.instructions.length) else text(it.instructionCount))
                })
                region.append(table)
            }
        } catch (error: Throwable) {
            if (!state.controller.signal.aborted) region.replaceKids(el("p", "file-error", "Class projection unavailable: " + errorMessage(error)))
        }
    }

    suspend fun render(mount: HTMLElement, options: dynamic) {
        dispose(mount); mount.replaceKids(); mount.classList.add("graal-file")
        val state = State(AbortController())
        states.set(mount, state)
        val signal = state.controller.signal
        val outer = options.signal.unsafeCast<AbortSignal?>()
        if (outer?.aborted == true) { state.controller.abort(); return }
        outer?.addEventListener("abort", { dispose(mount) }, js("({once:true})"))
        val status = el("p", "file-note", "Loading bytes"); mount.append(status)
        try {
            var bytes: dynamic = options.bytes
            var type: String = text(options.type)
            val url: String? = if (text(options.url).isNotEmpty()) localUrl(options.url as String) else null
            if (bytes == null) {
                var response: Response? = null
                val cid = options.cid as String?
                val urls = if (url != null) listOf(url) else if (FileViewerRules.isCid(cid)) listOf("/trikeshed/_cas/$cid", "/api/lcnc/content?cid=" + encodeURIComponent(cid!!) + "&view=bytes") else emptyList()
                if (urls.isEmpty()) throw Error("Blob identity required")
                for (candidate in urls) { response = fetchResponse(candidate, requestInit(signal = signal)); if (response.status.toInt() != 404) break }
                if (!response!!.ok) throw Error("Blob read failed: " + response.status)
                bytes = readBytes(response)
                if (type.isEmpty()) type = response.headers.get("content-type") ?: ""
            }
            val data: Uint8Array = if (bytes is Uint8Array) bytes else Uint8Array(bytes.unsafeCast<ArrayBuffer>())
            if (data.byteLength > FileViewerRules.LIMIT) throw Error("Preview exceeds " + fmtBytes(FileViewerRules.LIMIT.toDouble()))
            if (signal.aborted) return
            if (FileViewerRules.isCid(options.cid as String?)) {
                if (window.asDynamic().crypto?.subtle == null) throw Error("Content identity verification unavailable")
                val actual = sha256(data)
                if (actual != options.cid) throw Error("Content identity mismatch")
            }
            if (signal.aborted) return
            val meta = el("div", "file-meta")
            meta.innerHTML = if (text(options.cid).isNotEmpty()) chip(options.cid as String?) else ""
            meta.append(el("span", null, fmtBytes(data.byteLength.toDouble())))
            val gauge = el("meter", "kgauge"); val g: dynamic = gauge; g.min = 0; g.max = 1; g.value = 0
            gauge.setAttribute("aria-label", "Gzip compression ratio")
            val k = el("span", "file-note", "Measuring K")
            meta.append(gauge, k); mount.replaceKids(meta); bindRefs(meta, options)
            GlobalScope.promise {
                try {
                    val result = measure(data)
                    if (signal.aborted) return@promise
                    if (result == null) { gauge.hidden = true; k.textContent = "Gzip measurement unavailable"; return@promise }
                    g.value = minOf(1.0, result.ratio)
                    k.textContent = (js("(function(v){return (v*100).toFixed(0)})")(result.ratio) as String) + "% K"
                    meta.title = "Gzip " + fmtBytes(result.gzip.toDouble()) + " / raw " + fmtBytes(result.raw.toDouble()) + ". Compression proxy, not exact Kolmogorov complexity."
                } catch (_: Throwable) {
                    if (!signal.aborted) { gauge.hidden = true; k.textContent = "Gzip measurement unavailable" }
                }
            }
            val body = el("div", "file-projection"); mount.append(body)
            val id = text(options.id)
            val mode = kind(data, type, id)
            when (mode) {
                "image" -> {
                    val objectUrl = URL.createObjectURL(Blob(arrayOf(data), BlobPropertyBag(type = FileViewerRules.imageMime(type, id))))
                    state.urls.add(objectUrl)
                    val img = el("img", "imgview"); val i: dynamic = img
                    i.alt = id.ifEmpty { text(options.cid) }.ifEmpty { "Blob image" }; i.src = objectUrl; body.append(img)
                }
                "html" -> {
                    val frame = el("iframe", "htmlview"); frame.title = id.ifEmpty { "HTML blob" }; frame.setAttribute("sandbox", "")
                    frame.asDynamic().srcdoc = FileViewerRules.HTML_CSP + decode(data); body.append(frame)
                }
                "class" -> {
                    val raw = ByteArrayOf(data)
                    body.innerHTML = Projection.classCard(raw, ::fmtBytes) ?: Projection.hexHead(raw, 128, ::fmtBytes, Projection::escAll)
                    if (id.isNotEmpty()) classProjection(id, body, state)
                }
                "binary" -> body.innerHTML = Projection.hexHead(ByteArrayOf(data), 128, ::fmtBytes, Projection::escAll)
                else -> {
                    val value = decode(data)
                    val source = el("div")
                    source.innerHTML = Projection.shapeStripHtml(value, false, Projection::escAll) + Projection.codeViewTokens(value)
                    val cells = source.querySelectorAll("[data-line]")
                    for (c in 0 until cells.length) {
                        val button = cells.item(c).unsafeCast<HTMLElement>()
                        button.addEventListener("click", {
                            val row = source.querySelector(".codeview")?.children?.item(jsNumber(button.dataset["line"]).toInt() - 1)
                            row?.asDynamic()?.scrollIntoView(js("({block:'center'})"))
                        })
                    }
                    if (mode == "markdown") {
                        val rendered = el("div"); rendered.innerHTML = Projection.mdViewSafe(value); body.append(rendered)
                        val details = el("details"); details.append(el("summary", null, "Source"), source); body.append(details)
                    } else body.append(source)
                    if (value.split("\n").size > FileViewerRules.TEXT_LINES) body.append(el("p", "file-note", "First 1200 lines; compression measures all loaded bytes."))
                    if (text(options.sourceId).isNotEmpty() && FileViewerRules.jvmSource(id)) sourceMates(options.sourceId as String, body, state)
                }
            }
        } catch (error: Throwable) {
            if (!signal.aborted) mount.replaceKids(el("p", "file-error", errorMessage(error)))
        }
    }

    private var dialog: HTMLElement? = null
    private var dialogMount: HTMLElement? = null

    fun open(reference: dynamic): Promise<Unit> {
        if (dialog == null) {
            val d = el("dialog", "graal-file-dialog")
            val close = el("button", "file-close", "\u00d7"); close.setAttribute("aria-label", "Close file viewer")
            close.addEventListener("click", { d.asDynamic().close() })
            d.append(close, el("h2")); val m = el("div"); d.append(m); document.body!!.append(d)
            d.addEventListener("close", { dispose(m) })
            dialog = d; dialogMount = m
        }
        val d = dialog!!
        d.querySelector("h2")!!.textContent = text(reference.id).ifEmpty { text(reference.cid) }.ifEmpty { "File" }
        if (d.asDynamic().open != true) d.asDynamic().showModal()
        return GlobalScope.promise { render(dialogMount!!, reference) }
    }

    /** window.GraalFileViewer — the page-wide handle other bundles and the landscape call. */
    fun install() {
        if (window.asDynamic().GraalFileViewer != null) return
        val api: dynamic = jsObject()
        api.isCid = { v: dynamic -> jsTypeOf(v) == "string" && FileViewerRules.isCid(v as String) }
        api.kind = { b: Uint8Array, t: String?, i: String? -> kind(b, t ?: "", i ?: "") }
        api.readBytes = { r: Response, limit: Int? -> GlobalScope.promise { readBytes(r, limit ?: FileViewerRules.LIMIT) } }
        api.codeView = { t: String, n: Int? -> Projection.codeViewTokens(t, n ?: 1200) }
        api.mdView = { t: String -> Projection.mdViewSafe(t) }
        api.shapeStripHtml = { t: String -> Projection.shapeStripHtml(t, false, Projection::escAll) }
        api.render = { m: HTMLElement, o: dynamic -> GlobalScope.promise { render(m, o) } }
        api.dispose = { m: Element -> dispose(m) }
        api.chip = { c: String?, l: String? -> chip(c, l ?: "blob") }
        api.bindRefs = { m: Element, r: dynamic -> bindRefs(m, r) }
        api.open = { r: dynamic -> open(r) }
        window.asDynamic().GraalFileViewer = api
    }
}

external fun encodeURIComponent(s: String): String

@JsName("Int8Array")
private external class Int8Array(buffer: ArrayBuffer, byteOffset: Int, length: Int)

suspend fun sha256(bytes: Uint8Array): String {
    val digest = (window.asDynamic().crypto.subtle.digest("SHA-256", bytes) as Promise<ArrayBuffer>).await()
    val view = Uint8Array(digest)
    val sb = StringBuilder("sha256:")
    for (i in 0 until view.length) sb.append((view[i].toInt() and 0xFF).toString(16).padStart(2, '0'))
    return sb.toString()
}
