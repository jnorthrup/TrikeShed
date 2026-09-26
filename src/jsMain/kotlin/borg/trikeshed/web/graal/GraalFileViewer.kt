package borg.trikeshed.web.graal

import borg.trikeshed.graal.console.Projection
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.js.Promise

/**
 * Graal's byte projections (graal-file-viewer.js), shared by terrain, sheets, results and immutable refs.
 * Published as `window.GraalFileViewer` with the same api surface.
 */
object GraalFileViewer {
    const val LIMIT = 1500000
    private val scope = MainScope()
    private val states: dynamic = js("new WeakMap()")
    private val CID = Regex("^sha256:[0-9a-f]{64}$")

    fun isCid(value: dynamic): Boolean = typeOf(value) == "string" && CID.matches(value.unsafeCast<String>())
    fun fmtBytes(b: Double): String = Projection.fmtBytesViewer(b)
    fun esc(x: dynamic): String = Projection.escAll(s0(x))
    fun codeView(text: String, maxLines: Int = 1200): String = Projection.codeViewTokens(text, maxLines)
    fun mdView(text: String): String = Projection.mdViewSafe(text)
    fun bytesOf(b: dynamic): ByteArray {
        val u: dynamic = if (js("b instanceof Uint8Array")) b else Uint8Array(b.unsafeCast<org.khronos.webgl.ArrayBuffer>())
        return Int8Array(u.buffer, u.byteOffset, u.byteLength).unsafeCast<ByteArray>()
    }
    fun classCard(b: dynamic): String? = Projection.classCard(bytesOf(b), ::fmtBytes)
    fun hexHead(b: dynamic, n: Int = 128): String = Projection.hexHead(bytesOf(b), n, ::fmtBytes, Projection::escAll)
    fun shapeStripHtml(text: String): String = Projection.shapeStripHtml(text, false, Projection::escAll)

    fun localUrl(value: String): String {
        val url: dynamic = js("new URL(value, location.href)")
        if (url.origin != window.location.origin || (url.protocol != "http:" && url.protocol != "https:")) throw Error("Non-local blob URL")
        return str(url.pathname) + str(url.search)
    }

    /** Stream a response body into one Uint8Array, refusing past [limit]. */
    suspend fun readBytes(response: dynamic, limit: Int = LIMIT): dynamic {
        val reader: dynamic = if (response.body != null) response.body.getReader() else null
        if (reader == null) throw Error("Response stream unavailable")
        var size = 0
        val chunks = ArrayList<dynamic>()
        try {
            if (num(response.headers.get("content-length")) > limit) throw Error("Preview exceeds " + fmtBytes(limit.toDouble()))
            while (true) {
                val r: dynamic = reader.read().unsafeCast<Promise<dynamic>>().await()
                if (truthy(r.done)) break
                size += (r.value.byteLength as Number).toInt()
                if (size > limit) throw Error("Preview exceeds " + fmtBytes(limit.toDouble()))
                chunks.add(r.value)
            }
        } catch (error: Throwable) {
            try { reader.cancel().unsafeCast<Promise<dynamic>>().await() } catch (_: Throwable) {}
            throw error
        } finally {
            reader.releaseLock()
        }
        val bytes: dynamic = Uint8Array(size)
        var offset = 0
        for (chunk in chunks) { bytes.set(chunk, offset); offset += (chunk.byteLength as Number).toInt() }
        return bytes
    }

    /** gzip ratio of [bytes]: {raw, gzip, ratio}, null without CompressionStream. */
    suspend fun measure(bytes: dynamic): dynamic {
        val o: dynamic = newObject()
        if ((bytes.byteLength as Number).toInt() == 0) { o.raw = 0; o.gzip = 0; o.ratio = 0; return o }
        if (!hasCompressionStream()) return null
        val resp: dynamic = js("new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip')))")
        val compressed: dynamic = readBytes(resp, LIMIT + 65536)
        o.raw = bytes.byteLength; o.gzip = compressed.byteLength; o.ratio = num(compressed.byteLength) / num(bytes.byteLength)
        return o
    }

    private val CTRL = Regex("[\\x00-\\x08\\x0e-\\x1f]")
    private val IMG_EXT = Regex("^(svg|png|jpg|jpeg|gif|webp|avif)$")
    private val HTML_EXT = Regex("^(html|htm)$")
    private val TEXT_CT = Regex("json|xml|javascript")
    private val TEXT_EXT = Regex("^(kt|kts|java|py|js|ts|json|yaml|yml|css|sh|gradle|xml|txt|toml|properties|sql|rs|c|h|cpp|go|jsonl)$")

    fun kind(bytes: dynamic, type: String = "", id: String = ""): String {
        val ext = Projection.extOf(id); val ct = type.lowercase()
        val len = (bytes.length as Number).toInt()
        if (ct.contains("java-vm") || ext == "class" || (len >= 4 && bytes[0] == 202 && bytes[1] == 254 && bytes[2] == 186 && bytes[3] == 190)) return "class"
        if (ct.startsWith("image/") || IMG_EXT.matches(ext)) return "image"
        if (ext == "md" || ct.contains("markdown")) return "markdown"
        if (ct.contains("html") || HTML_EXT.matches(ext)) return "html"
        if (ct.startsWith("text/") || TEXT_CT.containsMatchIn(ct) || TEXT_EXT.matches(ext)) return "text"
        try {
            val text: String = js("new TextDecoder('utf-8',{fatal:true}).decode(bytes)")
            if (!CTRL.containsMatchIn(text)) return "text"
        } catch (_: Throwable) {}
        return "binary"
    }

    fun chip(cid: dynamic, label: String = "blob"): String {
        if (!isCid(cid)) return esc(if (truthy(cid)) cid else "")
        val c = cid.unsafeCast<String>()
        return "<button class=\"blob-ref\" data-blob-cid=\"$c\" title=\"Open blob $c\">" + esc(label) + " " + c.substring(7, 19) + "</button>"
    }

    fun bindRefs(mount: HTMLElement, reference: dynamic = null) {
        for (button in mount.querySelectorAll("[data-blob-cid]").asList()) {
            val b = button.unsafeCast<HTMLElement>()
            b.addEventListener("click", { e ->
                e.stopPropagation()
                // Framed archive content has a document CID but is restored through its manifest.
                val cid = b.asDynamic().dataset.blobCid
                val ref: dynamic = if (reference != null && reference.cid == cid) {
                    val r: dynamic = js("Object.assign({}, reference)"); r.signal = undefined; r
                } else { val r: dynamic = newObject(); r.cid = cid; r }
                open(ref)
            })
        }
    }

    fun dispose(mount: dynamic) {
        val state: dynamic = states.get(mount) ?: return
        state.controller.abort()
        for (url in state.urls.unsafeCast<Array<String>>()) js("URL.revokeObjectURL(url)")
        states.delete(mount)
    }

    private suspend fun sourceMates(id: String, mount: HTMLElement, state: dynamic) {
        val region = el("div", "file-mates"); mount.append(region)
        try {
            val init: dynamic = newObject(); init.signal = state.controller.signal
            val response: dynamic = window.fetch("/api/graal/decompile?source=" + encUri(id), init).await()
            val data: dynamic = JSON.parse<dynamic>(js("new TextDecoder()").decode(readBytes(response)) as String)
            if (truthy(state.controller.signal.aborted)) return
            if (!truthy(response.ok)) { region.textContent = "Class projection unavailable: " + str(orr(data.error, response.status)); return }
            val mates = (if (truthy(data.mates)) data.mates else js("[]")).slice(0, 24).unsafeCast<Array<dynamic>>()
            region.append(el("p", "file-note", if (mates.isNotEmpty()) "SourceFile mates: " + mates.size else "No SourceFile-mated class blob"))
            for (mate in mates) {
                val detail = el("details", "mate")
                val title = el("summary", null, str(mate.className) + " / " + (if (truthy(mate.exactRuntimeBlob)) "exact runtime mate" else if (truthy(mate.onClasspath)) "classpath bytes differ" else "not on runtime classpath"))
                detail.append(title)
                val body = el("div", "matebody")
                val pseudo: dynamic = if (mate.decompiler != null) mate.decompiler.pseudoSource else null
                body.innerHTML = chip(mate.blobCid) + (if (truthy(mate.runtimeCid)) chip(mate.runtimeCid, "runtime") else "") +
                    codeView(if (truthy(pseudo)) str(pseudo) else "No class projection", 2400)
                detail.append(body); region.append(detail)
            }
            bindRefs(region)
        } catch (error: Throwable) {
            if (!truthy(state.controller.signal.aborted)) region.textContent = "Class projection unavailable: " + error.message
        }
    }

    private fun projectionTable(rows: List<Pair<String, dynamic>>): String =
        "<table>" + rows.joinToString("") { (k, v) -> "<tr><td>" + esc(k) + "</td><td>" + esc(v) + "</td></tr>" } + "</table>"

    private suspend fun classProjection(id: String, mount: HTMLElement, state: dynamic) {
        val region = el("section", "classdetail")
        region.append(el("p", "file-note", "Loading class projection")); mount.append(region)
        try {
            val init: dynamic = newObject(); init.signal = state.controller.signal
            val response: dynamic = window.fetch("/api/graal/classfile?id=" + encUri(id), init).await()
            val data: dynamic = JSON.parse<dynamic>(js("new TextDecoder()").decode(readBytes(response, 2097152)) as String)
            if (truthy(state.controller.signal.aborted)) return
            if (!truthy(response.ok) || truthy(data.error)) {
                region.asDynamic().replaceChildren(el("p", "file-error", "Class projection unavailable: " + str(orr(data.error, response.status)))); return
            }
            val methods = (if (isArray(data.methods)) data.methods.slice(0, 80) else js("[]")).unsafeCast<Array<dynamic>>()
            val fields = (if (isArray(data.fields)) data.fields.slice(0, 40) else js("[]")).unsafeCast<Array<dynamic>>()
            val interfaces: String = if (isArray(data.interfaces)) data.interfaces.join(", ") else ""
            region.innerHTML = "<h3>" + esc(orr(orr(data.className, data.id), id)) + "</h3>" + projectionTable(listOf(
                "super" to orr(data.superClass, ""),
                "interfaces" to interfaces,
                "source" to orr(data.sourceFile, ""),
                "classpath" to (if (data.onClasspath == true) "present" else if (data.onClasspath == false) "absent" else "unknown"),
                "exact runtime blob" to (if (data.exactRuntimeBlob == true) "true" else if (data.exactRuntimeBlob == false) "false" else "unknown"),
                "api" to orr(data.classfileApi, "java.lang.classfile"),
            ))
            if (fields.isNotEmpty()) {
                val table = el("table", "class-members")
                table.innerHTML = "<tr><th>field</th><th>descriptor</th></tr>" +
                    fields.joinToString("") { f -> "<tr><td>" + esc(orr(f.name, "")) + "</td><td>" + esc(orr(f.descriptor, "")) + "</td></tr>" }
                region.append(table)
            }
            if (methods.isNotEmpty()) {
                val table = el("table", "class-members")
                table.innerHTML = "<tr><th>method</th><th>descriptor</th><th>instructions</th></tr>" +
                    methods.joinToString("") { m ->
                        "<tr><td>" + esc(orr(m.name, "")) + "</td><td>" + esc(orr(m.descriptor, "")) + "</td><td>" +
                            esc(if (isArray(m.instructions)) m.instructions.length else orr(m.instructionCount, "")) + "</td></tr>"
                    }
                region.append(table)
            }
        } catch (error: Throwable) {
            if (!truthy(state.controller.signal.aborted)) region.asDynamic().replaceChildren(el("p", "file-error", "Class projection unavailable: " + error.message))
        }
    }

    /** Render the bytes named by [options] (`{bytes|url|cid, type, id, sourceId, signal}`) into [mount]. */
    fun render(mount: HTMLElement, options: dynamic): Promise<Unit> = Promise { resolve, _ ->
        scope.launch { try { renderNow(mount, options) } finally { resolve(Unit) } }
    }

    private suspend fun renderNow(mount: HTMLElement, options: dynamic) {
        dispose(mount); mount.asDynamic().replaceChildren(); mount.classList.add("graal-file")
        val state: dynamic = newObject(); state.controller = js("new AbortController()"); state.urls = js("[]")
        states.set(mount, state)
        val signal: dynamic = state.controller.signal
        if (options.signal != null && truthy(options.signal.aborted)) { state.controller.abort(); return }
        if (options.signal != null) {
            val once: dynamic = newObject(); once.once = true
            options.signal.addEventListener("abort", { dispose(mount) }, once)
        }
        val status = el("p", "file-note", "Loading bytes"); mount.append(status)
        try {
            var bytes: dynamic = options.bytes
            var type: String = if (truthy(options.type)) str(options.type) else ""
            val url: String? = if (truthy(options.url)) localUrl(str(options.url)) else null
            if (!truthy(bytes)) {
                var response: dynamic = null
                val urls = if (url != null) listOf(url) else if (isCid(options.cid)) listOf("/trikeshed/_cas/" + str(options.cid), "/api/lcnc/content?cid=" + encUri(str(options.cid)) + "&view=bytes") else emptyList()
                if (urls.isEmpty()) throw Error("Blob identity required")
                for (candidate in urls) {
                    val init: dynamic = newObject(); init.signal = signal
                    response = window.fetch(candidate, init).await()
                    if (num(response.status) != 404.0) break
                }
                if (!truthy(response.ok)) throw Error("Blob read failed: " + str(response.status))
                bytes = readBytes(response)
                type = if (type.isNotEmpty()) type else s0(orr(response.headers.get("content-type"), ""))
            }
            bytes = if (js("bytes instanceof Uint8Array")) bytes else js("new Uint8Array(bytes)")
            if ((bytes.byteLength as Number).toInt() > LIMIT) throw Error("Preview exceeds " + fmtBytes(LIMIT.toDouble()))
            if (truthy(signal.aborted)) return
            if (isCid(options.cid)) {
                if (!truthy(js("crypto.subtle"))) throw Error("Content identity verification unavailable")
                val digest: dynamic = js("crypto.subtle.digest('SHA-256', bytes)").unsafeCast<Promise<dynamic>>().await()
                val actual: String = "sha256:" + js("Array.from(new Uint8Array(digest),function(b){return b.toString(16).padStart(2,'0')}).join('')")
                if (actual != options.cid) throw Error("Content identity mismatch")
            }
            if (truthy(signal.aborted)) return
            val meta = el("div", "file-meta")
            meta.innerHTML = if (truthy(options.cid)) chip(options.cid) else ""
            meta.append(el("span", null, fmtBytes(num(bytes.byteLength))))
            val gauge: dynamic = el("meter", "kgauge"); gauge.min = 0; gauge.max = 1; gauge.value = 0
            gauge.setAttribute("aria-label", "Gzip compression ratio")
            val k = el("span", "file-note", "Measuring K")
            meta.append(gauge.unsafeCast<HTMLElement>(), k); mount.asDynamic().replaceChildren(meta); bindRefs(meta, options)
            val measured = bytes
            scope.launch {
                try {
                    val result: dynamic = measure(measured)
                    if (truthy(signal.aborted)) return@launch
                    if (result == null) { gauge.hidden = true; k.textContent = "Gzip measurement unavailable"; return@launch }
                    gauge.value = kotlin.math.min(1.0, num(result.ratio))
                    k.textContent = toFixed(num(result.ratio) * 100, 0) + "% K"
                    meta.title = "Gzip " + fmtBytes(num(result.gzip)) + " / raw " + fmtBytes(num(result.raw)) + ". Compression proxy, not exact Kolmogorov complexity."
                } catch (_: Throwable) {
                    if (!truthy(signal.aborted)) { gauge.hidden = true; k.textContent = "Gzip measurement unavailable" }
                }
            }
            val body = el("div", "file-projection"); mount.append(body)
            val idStr = if (truthy(options.id)) str(options.id) else ""
            val mode = kind(bytes, type, idStr)
            val text = { js("new TextDecoder()").decode(measured).unsafeCast<String>() }
            if (mode == "image") {
                val ext = Projection.extOf(idStr)
                val mime = if (type.startsWith("image/")) type else if (ext == "svg") "image/svg+xml" else "image/" + (if (ext == "jpg") "jpeg" else ext)
                val objectUrl: String = js("URL.createObjectURL(new Blob([measured],{type:mime}))")
                state.urls.push(objectUrl)
                val img: dynamic = el("img", "imgview")
                img.alt = if (truthy(options.id)) options.id else if (truthy(options.cid)) options.cid else "Blob image"
                img.src = objectUrl; body.append(img.unsafeCast<HTMLElement>())
            } else if (mode == "html") {
                val frame: dynamic = el("iframe", "htmlview")
                frame.title = if (truthy(options.id)) options.id else "HTML blob"
                frame.setAttribute("sandbox", "")
                frame.srcdoc = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src &#39;none&#39;; img-src data: blob:; style-src &#39;unsafe-inline&#39;\">" + text()
                body.append(frame.unsafeCast<HTMLElement>())
            } else if (mode == "class") {
                body.innerHTML = classCard(measured) ?: hexHead(measured)
                if (truthy(options.id)) classProjection(idStr, body, state)
            } else if (mode == "binary") {
                body.innerHTML = hexHead(measured)
            } else {
                val value = text()
                val source = el("div")
                source.innerHTML = shapeStripHtml(value) + codeView(value)
                for (button in source.querySelectorAll("[data-line]").asList()) {
                    val b = button.unsafeCast<HTMLElement>()
                    b.addEventListener("click", {
                        val cv = source.querySelector(".codeview")
                        val row: dynamic = if (cv != null) cv.children.item(num(b.asDynamic().dataset.line).toInt() - 1) else null
                        if (row != null) { val o: dynamic = newObject(); o.block = "center"; row.scrollIntoView(o) }
                    })
                }
                if (mode == "markdown") {
                    val rendered = el("div"); rendered.innerHTML = mdView(value); body.append(rendered)
                    val details = el("details"); details.append(el("summary", null, "Source"), source); body.append(details)
                } else body.append(source)
                if (value.split("\n").size > 1200) body.append(el("p", "file-note", "First 1200 lines; compression measures all loaded bytes."))
                if (truthy(options.sourceId) && Regex("\\.(kt|kts|java)$", RegexOption.IGNORE_CASE).containsMatchIn(idStr)) sourceMates(str(options.sourceId), body, state)
            }
        } catch (error: Throwable) {
            if (!truthy(signal.aborted)) mount.asDynamic().replaceChildren(el("p", "file-error", error.message ?: ""))
        }
    }

    private var dialog: dynamic = null
    private var dialogMount: HTMLElement? = null

    /** Open [reference] in the shared file dialog. */
    fun open(reference: dynamic): Promise<Unit> {
        if (dialog == null) {
            val d: dynamic = el("dialog", "graal-file-dialog")
            val close = el("button", "file-close", "\u00d7")
            close.setAttribute("aria-label", "Close file viewer")
            close.addEventListener("click", { d.close() })
            d.append(close, el("h2"))
            val m = el("div"); dialogMount = m; d.append(m); document.body!!.append(d.unsafeCast<HTMLElement>())
            d.addEventListener("close", { dispose(m) })
            dialog = d
        }
        dialog.querySelector("h2").textContent = if (truthy(reference.id)) reference.id else if (truthy(reference.cid)) reference.cid else "File"
        if (!truthy(dialog.open)) dialog.showModal()
        return render(dialogMount!!, reference)
    }

    /** Publish `window.GraalFileViewer`. */
    fun install() {
        val api: dynamic = newObject()
        api.isCid = { v: dynamic -> isCid(v) }
        api.kind = { b: dynamic, t: dynamic, i: dynamic -> kind(b, if (truthy(t)) str(t) else "", if (truthy(i)) str(i) else "") }
        api.readBytes = { r: dynamic, l: dynamic -> Promise<dynamic> { res, rej -> scope.launch { try { res(readBytes(r, if (l == undefined) LIMIT else num(l).toInt())) } catch (e: Throwable) { rej(e) } } } }
        api.measure = { b: dynamic -> Promise<dynamic> { res, rej -> scope.launch { try { res(measure(b)) } catch (e: Throwable) { rej(e) } } } }
        api.codeView = { t: String, n: dynamic -> codeView(t, if (truthy(n)) num(n).toInt() else 1200) }
        api.mdView = { t: String -> mdView(t) }
        api.classCard = { b: dynamic -> classCard(b) }
        api.hexHead = { b: dynamic, n: dynamic -> hexHead(b, if (truthy(n)) num(n).toInt() else 128) }
        api.shapeRuns = { t: String ->
            val s = Projection.shapeRuns(t); val o: dynamic = newObject(); o.lines = s.lines
            o.runs = s.runs.map { r -> val x: dynamic = newObject(); x.c = r.c.toString(); x.n = r.n; x.start = r.start; x }.toTypedArray(); o
        }
        api.shapeStripHtml = { t: String -> shapeStripHtml(t) }
        api.render = { m: HTMLElement, o: dynamic -> render(m, o) }
        api.dispose = { m: dynamic -> dispose(m) }
        api.chip = { c: dynamic, l: dynamic -> chip(c, if (l == undefined) "blob" else str(l)) }
        api.bindRefs = { m: HTMLElement, r: dynamic -> bindRefs(m, r) }
        api.open = { r: dynamic -> open(r) }
        window.asDynamic().GraalFileViewer = api
    }
}
