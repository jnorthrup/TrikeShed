package borg.trikeshed.web.harness

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Worker
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** The decoder worker: this same bundle, entered without a document (archive-worker.js). */
object ArchiveWorker {
    fun serve(self: dynamic) {
        self.onmessage = { event: dynamic ->
            try {
                val bytes = if (truthy(event.data.demo)) ArchiveCore.demo() else Uint8Array(event.data.bytes.unsafeCast<ArrayBuffer>())
                val entries = ArchiveCore.unpack(bytes)
                val reply: dynamic = jsObject(); reply.request = ArchiveCore.request(entries)
                self.postMessage(reply)
            } catch (error: Throwable) {
                val reply: dynamic = jsObject(); reply.error = errorMessage(error).ifEmpty { error.toString() }
                self.postMessage(reply)
            }
        }
    }
}

/** archive-ui.js: import, browse, mount and restore stored archives. */
@OptIn(DelicateCoroutinesApi::class)
object ArchiveUI {
    const val WORKER_URL = "/kotlin/harness/harness.js"

    private var manifest: dynamic = null
    private var active: AbortController? = null
    private var worker: Worker? = null
    private var busy = false

    private fun status(text: String) { byId("archiveStatus").textContent = text }
    private fun contentURL(cid: String, ordinal: dynamic) = "/api/archives/content?cid=" + encodeURIComponent(cid) + "&entry=" + jsString(ordinal)

    private fun controls() {
        for (id in listOf("archiveUpload", "archiveDemo", "archiveOpen", "archiveDownload", "archiveLandscape"))
            byId(id).unsafeCast<HTMLButtonElement>().disabled = busy || (manifest == null && (id == "archiveDownload" || id == "archiveLandscape"))
        byId("archiveCancel").unsafeCast<HTMLButtonElement>().disabled = !busy
    }

    private suspend fun run(action: suspend (AbortSignal) -> Unit) {
        if (busy) return
        busy = true
        val controller = AbortController(); active = controller; controls()
        val timeout = window.setTimeout({ controller.abort() }, 30000)
        try { action(controller.signal) }
        catch (error: Throwable) { status(if (controller.signal.aborted) "Cancelled locally. An accepted import may already be stored." else errorMessage(error)) }
        finally { window.clearTimeout(timeout); worker?.terminate(); worker = null; busy = false; controls() }
    }

    private suspend fun json(url: String, init: org.w3c.fetch.RequestInit): dynamic {
        val response = fetchResponse(url, init)
        val value = response.jsonValue()
        if (!response.ok) throw Error(if (truthy(value.error)) value.error as String else "Archive request failed: " + response.status)
        return value
    }

    private suspend fun decode(data: dynamic, signal: AbortSignal): dynamic = suspendCancellableCoroutine { cont ->
        val decoder = Worker(WORKER_URL); worker = decoder
        var settled = false
        var timeout = 0
        lateinit var abort: (dynamic) -> Unit
        fun finish(error: Throwable?, value: dynamic) {
            if (settled) return; settled = true
            window.clearTimeout(timeout); signal.removeEventListener("abort", abort); decoder.terminate(); if (worker === decoder) worker = null
            if (error != null) cont.resumeWithException(error) else cont.resume(value)
        }
        abort = { finish(js("new DOMException('Cancelled','AbortError')").unsafeCast<Throwable>(), null) }
        timeout = window.setTimeout({ finish(Error("ZIP decoding exceeded 10 seconds"), null) }, ArchiveLimits.ELAPSED_MS)
        signal.addEventListener("abort", abort, js("({once:true})"))
        decoder.onmessage = { event ->
            val d: dynamic = event.data
            if (truthy(d.error)) finish(Error(d.error as String), null) else finish(null, d.request)
        }
        decoder.onerror = { event -> finish(Error(if (truthy(event.asDynamic().message)) event.asDynamic().message as String else "ZIP worker failed"), null) }
        decoder.postMessage(data, if (truthy(data.bytes)) arrayOf(data.bytes) else arrayOf())
        if (signal.aborted) abort(null)
    }

    private fun show(value: dynamic) {
        ArchiveCore.projection(value)
        manifest = value; byId("archiveCid").unsafeCast<HTMLInputElement>().value = value.cid as String
        val list = byId("archiveEntries"); list.replaceKids()
        for (entry in value.entries as Array<dynamic>) {
            val row = el("li"); val box = el("input").unsafeCast<HTMLInputElement>(); val button = el("button").unsafeCast<HTMLButtonElement>()
            val directory = (entry.path as String).endsWith("/")
            box.type = "checkbox"; box.value = jsString(entry.ordinal); box.checked = !directory; box.disabled = directory
            box.setAttribute("aria-label", "Select " + entry.path)
            button.textContent = entry.path as String; button.title = entry.cid as String; button.disabled = box.disabled
            button.addEventListener("click", { GlobalScope.launch { preview(entry) } })
            row.append(box, button); list.append(row)
        }
        GraalFileViewer.dispose(byId("archivePreview")); byId("archivePreview").replaceKids()
        byId("archivePreviewTitle").textContent = ""
        status((value.entries as Array<dynamic>).size.toString() + " entries stored · " + value.cid); controls()
    }

    private fun mount() {
        val harness = Harness
        if (manifest == null || !harness.ready) { status("Waiting for the blackboard vocabulary"); return }
        val cid = manifest.cid as String
        val name = "archive-" + cid.substring(7)
        val fresh = !truthy(harness.board[HarnessKeys.PROGRAM + name]) && !harness.drafts.has(name)
        if (fresh) harness.drafts.set(name, ArchiveCore.projection(manifest))
        harness.select(name, false)
        if (fresh) {
            for (node in Patch.nodes().filter { it._program == name && !truthy(it._parentScope) }) Patch.layoutRing(node)
            harness.changed()
        }
        harness.fit(false); harness.render(); byId("archiveInspector").asDynamic().close()
        harness.message("Archive mounted · $cid")
    }

    private suspend fun preview(entry: dynamic) {
        if (busy || manifest == null) return
        val cid = manifest.cid as String
        byId("archivePreviewTitle").textContent = entry.path as String
        val options: dynamic = jsObject()
        options.id = entry.path; options.cid = entry.cid; options.type = entry.mediaType; options.url = contentURL(cid, entry.ordinal)
        GraalFileViewer.render(byId("archivePreview"), options)
    }

    private suspend fun importArchive(file: File?) {
        run { signal ->
            if (file != null && file.size.toDouble() > ArchiveLimits.ZIP_BYTES) throw Error("ZIP compressed byte limit is 4 MiB")
            status("Decoding ZIP")
            val bytes: ArrayBuffer? = if (file != null) (file.asDynamic().arrayBuffer() as Promise<ArrayBuffer>).await() else null
            signal.throwIfAborted()
            val data: dynamic = jsObject()
            if (bytes != null) data.bytes = bytes else data.demo = true
            val request = decode(data, signal)
            status("Storing archive")
            show(json("/api/archives/import", requestInit("POST", JSON.stringify(request), signal)))
        }
    }

    suspend fun open(cid: String, ordinal: dynamic = null) {
        val inspector = byId("archiveInspector").asDynamic()
        if (inspector.open != true) inspector.showModal()
        var loaded = false
        run { signal ->
            status("Opening archive")
            show(json("/api/archives/manifest?cid=" + encodeURIComponent(cid), requestInit(signal = signal))); loaded = true
        }
        if (loaded && ordinal != null) {
            val wanted = jsNumber(ordinal)
            val entry = (manifest.entries as Array<dynamic>).firstOrNull { it.ordinal == wanted }
            if (entry != null) preview(entry)
        }
    }

    private fun download(bytes: Uint8Array, name: String, type: String) {
        val url = URL.createObjectURL(Blob(arrayOf(bytes), BlobPropertyBag(type = type)))
        val link = el("a").unsafeCast<HTMLAnchorElement>()
        link.href = url; link.download = name; document.body!!.append(link); link.click(); link.remove()
        window.setTimeout({ URL.revokeObjectURL(url) }, 30000)
    }

    private suspend fun restore() {
        if (manifest == null) return
        val boxes = byId("archiveEntries").querySelectorAll("input:checked")
        val selected = HashSet<Double>()
        for (i in 0 until boxes.length) selected.add(jsNumber(boxes.item(i).unsafeCast<HTMLInputElement>().value))
        val entries = (manifest.entries as Array<dynamic>).filter { selected.contains((it.ordinal as Number).toDouble()) }
        val cid = manifest.cid as String
        if (entries.isEmpty()) { status("Select at least one file"); return }
        run { signal ->
            val files: dynamic = js("Object.create(null)")
            var total = 0
            for (entry in entries) {
                status("Restoring " + entry.path)
                val response = fetchResponse(contentURL(cid, entry.ordinal), requestInit(signal = signal))
                if (!response.ok) { val body = response.jsonValue(); throw Error(if (truthy(body.error)) body.error as String else "Restore failed") }
                val bytes = GraalFileViewer.readBytes(response, ArchiveLimits.FILE_BYTES)
                total += bytes.length; if (total > ArchiveLimits.TOTAL_BYTES) throw Error("Restore byte limit exceeded")
                if (sha256(bytes) != entry.cid) throw Error("Restored CID mismatch: " + entry.path)
                files[entry.path] = bytes
            }
            signal.throwIfAborted()
            if (entries.size == 1) download(files[entries[0].path].unsafeCast<Uint8Array>(), (entries[0].path as String).split("/").last(), "application/octet-stream")
            else { val o: dynamic = jsObject(); o.level = 0; download(borg.trikeshed.web.harness.fflate.zipSync(files, o), "archive-selection.zip", "application/zip") }
            status("Verified and downloaded " + entries.size + " file(s)")
        }
    }

    fun decorate(node: dynamic) {
        if (!truthy(node.params?.archiveCid)) return
        val element = node.el.unsafeCast<HTMLElement>()
        element.classList.add("archive-node")
        val path = node.params.archivePath as String
        val title = element.querySelector(".hd b").unsafeCast<HTMLElement>()
        title.textContent = archiveNodeTitle(path)
        title.title = path
        val label = element.querySelector(".ringlbl").unsafeCast<HTMLElement?>()
        if (label != null) { label.textContent = "Directory"; label.title = path }
        if (truthy(node._childHost) && !truthy(node.children?.length)) {
            node._ringWorld.style.width = "160px"; node._ringWorld.style.height = "64px"; Patch.syncRingFrame(node)
        }
        val button = el("button", "archive-inspect", "↗")
        button.title = "Inspect archive bytes"; button.setAttribute("aria-label", "Inspect $path")
        button.addEventListener("pointerdown", { event -> event.stopPropagation() })
        button.addEventListener("click", { event ->
            event.stopPropagation()
            GlobalScope.launch { open(node.params.archiveCid as String, node.params.archiveEntry) }
        })
        element.append(button)
    }

    fun install() {
        byId("archiveBtn").addEventListener("click", { byId("archiveInspector").asDynamic().showModal() })
        byId("archiveUpload").addEventListener("click", { byId("archiveFile").click() })
        byId("archiveFile").addEventListener("change", { event ->
            val input = event.target.unsafeCast<HTMLInputElement>()
            val file = input.files?.item(0); input.value = ""
            if (file != null) GlobalScope.launch { importArchive(file) }
        })
        byId("archiveDemo").addEventListener("click", { GlobalScope.launch { importArchive(null) } })
        byId("archiveOpen").addEventListener("click", { GlobalScope.launch { open(byId("archiveCid").unsafeCast<HTMLInputElement>().value.trim()) } })
        byId("archiveLandscape").addEventListener("click", { mount() })
        byId("archiveDownload").addEventListener("click", { GlobalScope.launch { restore() } })
        byId("archiveCancel").addEventListener("click", { active?.abort() })
        byId("archiveInspector").addEventListener("close", { active?.abort(); GraalFileViewer.dispose(byId("archivePreview")) })
        controls()
        val api: dynamic = jsObject()
        api.open = { cid: String, ordinal: dynamic -> GlobalScope.launch { open(cid, ordinal) }; Unit }
        api.decorate = { node: dynamic -> decorate(node) }
        window.asDynamic().ArchiveUI = api
    }
}
