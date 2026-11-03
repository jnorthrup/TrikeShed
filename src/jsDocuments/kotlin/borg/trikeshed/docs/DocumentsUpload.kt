package borg.trikeshed.docs

import borg.trikeshed.graal.console.Ingest
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.wiki.WikiSurface
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlin.js.Promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/** Browser files go through the existing project upload boundary, never the selected document. */
internal object DocumentsUpload {
    private const val MAX_FILES = 1000
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128 * 1024 * 1024
    private class Entry(val path: String, val file: dynamic)

    fun mount() {
        val panel = document.getElementById("ds-upload") as? HTMLElement ?: return
        val status = document.getElementById("ds-upload-status") as HTMLElement
        val name = document.getElementById("ds-upload-project") as HTMLInputElement
        val files = document.getElementById("ds-upload-files") as HTMLInputElement
        val folder = document.getElementById("ds-upload-folder") as HTMLInputElement
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Main)
        val controls = panel.querySelectorAll("input,button")
        var pending = false
        var closed = false
        var uploadStarted = false
        var abort: dynamic = null
        fun present() = !closed && panel.asDynamic().isConnected == true
        fun message(value: String) { if (present()) status.textContent = value }
        fun disable(value: Boolean) {
            for (i in 0 until controls.length) controls.item(i)?.asDynamic()?.disabled = value
            panel.setAttribute("aria-busy", value.toString())
        }
        fun safe(path: String): String {
            require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path &&
                path.none { it < ' ' || it == '\u007f' } && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                path.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == path) { "Unsafe upload path: $path" }
            return path
        }
        suspend fun request(path: String, body: dynamic = null): Map<*, *> {
            val init = RequestInit(method = "POST", body = body,
                headers = Headers().apply { if (body != null) append("Content-Type", "application/octet-stream") })
            init.asDynamic().signal = abort.signal
            val response = window.fetch(path, init).await()
            val result = reifyStrict(response.text().await()) as? Map<*, *> ?: error("Upload returned an unreadable response")
            require(response.ok && result["verdict"] == "ok") { (result["detail"] ?: result["error"] ?: "Upload refused (HTTP ${response.status})").toString() }
            return result
        }
        suspend fun parse(project: String): String {
            val init = RequestInit(method = "POST")
            init.asDynamic().signal = abort.signal
            val response = window.fetch("/api/projects/${WikiSurface.encode(project)}/mine", init).await()
            val result = reifyStrict(response.text().await()) as? Map<*, *> ?: error("Parse response is unreadable")
            require(response.ok && (result["verdict"] == "mining" || result["verdict"] == "mined")) {
                (result["detail"] ?: result["error"] ?: "Parsing could not start (HTTP ${response.status})").toString()
            }
            return if (result["verdict"] == "mining") "Parsing started." else "Parsing finished: ${result["extracted"]} extracted, ${result["failed"]} failed."
        }
        suspend fun upload(entries: List<Entry>, suggested: String) {
            require(entries.isNotEmpty()) { "No files were selected" }
            require(entries.size <= MAX_FILES) { "Upload at most $MAX_FILES files at once" }
            val paths = mutableSetOf<String>()
            var total = 0.0
            for (entry in entries) {
                val path = safe(entry.path)
                val collision = path.asDynamic().normalize("NFC").toString().lowercase()
                require(paths.add(collision)) { "Duplicate upload path: $path" }
                val size = (entry.file.size as Number).toDouble()
                require(size.isFinite() && size >= 0 && size <= MAX_FILE_BYTES) { "$path exceeds the 32 MiB file limit" }
                total += size
                require(total <= MAX_TOTAL_BYTES) { "Upload exceeds the 128 MiB total limit" }
            }
            val project = name.value.trim().ifEmpty { suggested }
                .lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
            require(project.length in 1..100) { "Enter a new project name of at most 100 characters" }
            for (path in paths) {
                var slash = path.indexOf('/')
                while (slash >= 0) {
                    require(path.substring(0, slash) !in paths) { "A file path conflicts with a folder path" }
                    slash = path.indexOf('/', slash + 1)
                }
            }
            message("Creating new project $project; the current document will stay selected…")
            uploadStarted = true
            val begun = request("/_project/${WikiSurface.encode(project)}/begin?kind=assets&intake=manual")
            require(begun["intake"] == "manual") { "Server did not confirm storage-only intake; no file bytes were sent" }
            val actual = requireNotNull(begun["name"] as? String) { "Upload project identity is missing" }
            require(actual == project) { "Upload returned another project identity" }
            var sent = 0
            var parts = mutableListOf<dynamic>()
            var packed = 0
            suspend fun flush() {
                if (parts.isEmpty()) return
                val frameParts = parts.toTypedArray()
                val batchFiles = parts.size / 4
                val blob: dynamic = js("new Blob(frameParts)")
                parts = mutableListOf(); packed = 0
                val result = request("/_project/${WikiSurface.encode(actual)}/putBatch?intake=manual", blob)
                val stored = (result["stored"] as? Number)?.toInt() ?: error("Upload count is missing")
                val failed = (result["failed"] as? Number)?.toInt() ?: error("Upload failure count is missing")
                sent += stored
                require(failed == 0 && stored == batchFiles) { "Project $actual contains $sent uploaded files; this batch stored $stored of $batchFiles with $failed failures. No automatic retry was made." }
                message("Uploaded $sent / ${entries.size} files into project $actual…")
            }
            for (entry in entries) {
                if (!present()) throw CancellationException("Upload page closed")
                val buffer: dynamic = entry.file.arrayBuffer().unsafeCast<Promise<dynamic>>().await()
                require((buffer.byteLength as Number).toInt() == (entry.file.size as Number).toInt()) { "File size changed during upload" }
                val path = entry.path.encodeToByteArray()
                if (packed > 0 && packed + 12 + path.size + (buffer.byteLength as Number).toInt() > Ingest.BATCH_BYTES) flush()
                parts.add(Ingest.int32(path.size)); parts.add(path); parts.add(Ingest.int64((buffer.byteLength as Number).toLong())); parts.add(buffer)
                packed += 12 + path.size + (buffer.byteLength as Number).toInt()
                if (packed >= Ingest.BATCH_BYTES || parts.size >= Ingest.BATCH_PARTS) flush()
            }
            flush()
            require(sent == entries.size) { "Project $actual stored $sent of ${entries.size} files; inspect the project before retrying" }
            if (!present()) return
            message("Uploaded $sent files; starting parsing…")
            val parsing = try { parse(actual) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { "Files were saved, but parsing could not start: ${failure.message ?: "unknown error"}." }
            if (!present()) return
            status.textContent = "Added $sent files to $actual. $parsing Content remains unreviewed. "
            val link = document.createElement("a")
            link.textContent = "Open uploaded project"
            link.setAttribute("href", "/documents?uploadedProject=${WikiSurface.encode(actual)}#${WikiSurface.encode(actual)}")
            status.appendChild(link)
        }
        fun submit(read: suspend () -> Join<List<Entry>, String>) {
            if (pending || !present()) return
            pending = true; uploadStarted = false; disable(true); panel.asDynamic().open = true
            abort = js("new AbortController()")
            scope.launch {
                try { val (entries, suggested) = read(); upload(entries, suggested) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { message("Upload stopped: ${failure.message ?: "Unable to upload"}. " +
                    if (uploadStarted) "A partially uploaded project may already exist; the current document was not replaced." else "Nothing was sent; the current document was not replaced.") }
                finally { pending = false; if (present()) disable(false) }
            }
        }
        fun picked(input: HTMLInputElement) {
            if (pending || !present()) return
            val selected = input.files ?: return
            if (selected.length > MAX_FILES) { message("Upload at most $MAX_FILES files at once. Nothing was sent."); input.value = ""; return }
            val entries = mutableListOf<Entry>()
            val relativePaths = mutableListOf<String>()
            for (index in 0 until selected.length) {
                val file = requireNotNull(selected.item(index))
                val relative = file.asDynamic().webkitRelativePath as? String ?: ""
                relativePaths.add(relative)
                entries.add(Entry(if (relative.isEmpty()) file.name else relative.substringAfter('/'), file))
            }
            val first = selected.item(0) ?: return
            val relative = first.asDynamic().webkitRelativePath as? String ?: ""
            val suggested = if (relative.isEmpty()) first.name.substringBeforeLast('.', first.name) else relative.substringBefore('/')
            submit { for (relative in relativePaths) if (relative.isNotEmpty()) safe(relative); entries j suggested }
            input.value = ""
        }
        files.addEventListener("change", { picked(files) })
        folder.addEventListener("change", { picked(folder) })
        document.getElementById("ds-upload-choose")!!.addEventListener("click", { files.click() })
        document.getElementById("ds-upload-choose-folder")!!.addEventListener("click", { folder.click() })
        fun supportedDrag(event: Event): Boolean {
            val transfer = event.asDynamic().dataTransfer ?: return false
            val types: Array<dynamic> = js("Array.from(transfer.types || [])")
            if (types.any { it == "Files" }) return true
            // Keep ordinary text and internal drags available to the page's own editors.
            val target = event.target.asDynamic()
            if (target?.closest != null && target.closest("input,textarea,select,[contenteditable]:not([contenteditable='false'])") != null) return false
            if (types.any { it == "text/x-lcnc-value" }) return false
            return types.any { it == "text/uri-list" } ||
                (panel.asDynamic().contains(target) == true && types.any { it == "text/plain" })
        }
        fun dragOver(event: Event) {
            if (!supportedDrag(event) || !present()) return
            event.preventDefault()
            panel.classList.add("ds-drag")
        }
        fun dropped(event: Event) {
            panel.classList.remove("ds-drag")
            if (!supportedDrag(event) || !present()) return
            val transfer = event.asDynamic().dataTransfer ?: return
            val items: Array<dynamic> = js("Array.from(transfer.items || [])")
            val raw = ((transfer.getData("text/uri-list") as? String).orEmpty().ifBlank {
                (transfer.getData("text/plain") as? String).orEmpty()
            })
            val links = raw.lineSequence().map { it.trim() }
                .filter { it.startsWith("https://", true) || it.startsWith("http://", true) }.distinct().toList()
            val types: Array<dynamic> = js("Array.from(transfer.types || [])")
            val hasFiles = types.any { it == "Files" }
            if (!hasFiles && links.isEmpty()) return
            event.preventDefault(); event.stopPropagation()
            if (pending || !present()) return
            panel.asDynamic().open = true
            if (!hasFiles) {
                pending = true; disable(true); abort = js("new AbortController()")
                scope.launch {
                    try {
                        for (url in links) {
                            message("Fetching $url…")
                            val init = RequestInit(method = "POST", body = jsonOf(_m["url" j url, "manualIntake" j true]),
                                headers = Headers().apply { append("Content-Type", "application/json") })
                            init.asDynamic().signal = abort.signal
                            val response = window.fetch("/api/projects/link", init).await()
                            val value = reifyStrict(response.text().await()) as? Map<*, *> ?: error("Link response is unreadable")
                            require(response.ok && value["verdict"] == "ok") { (value["detail"] ?: value["error"] ?: "Link could not be fetched").toString() }
                            val project = value["name"] as? String ?: error("Link project identity is missing")
                            message("Fetched $url; starting parsing…")
                            val parsing = try { parse(project) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Throwable) { "Link was saved, but parsing could not start: ${failure.message ?: "unknown error"}." }
                            if (present()) {
                                status.textContent = "Added $url to $project. $parsing "
                                val link = document.createElement("a")
                                link.textContent = "Open project"
                                link.setAttribute("href", "/documents?uploadedProject=${WikiSurface.encode(project)}#${WikiSurface.encode(project)}")
                                status.appendChild(link)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { message("Link drop stopped: ${failure.message ?: "Unable to fetch"}") }
                    finally { pending = false; if (present()) disable(false) }
                }
                return
            }
            if (items.size > MAX_FILES) { message("Upload at most $MAX_FILES entries at once. Nothing was sent."); return }
            val roots = items.mapNotNull { item -> if (item.webkitGetAsEntry != null) item.webkitGetAsEntry() else null }
            val fileItems = items.count { it.kind == "file" }
            if (roots.isNotEmpty() && roots.size != fileItems) {
                message("Could not read every dropped entry. Choose files or a folder instead; nothing was sent.")
                return
            }
            val fallback = transfer.files
            if (roots.isEmpty()) {
                val fileCount = (fallback?.length as? Int) ?: 0
                if (fileItems > 0 && fileCount != fileItems) {
                    message("Could not read every dropped entry. Choose files or a folder instead; nothing was sent.")
                    return
                }
                val entries = mutableListOf<Entry>()
                for (i in 0 until fileCount) { val file = fallback.item(i); entries.add(Entry(file.name as String, file)) }
                submit { entries j (entries.firstOrNull()?.path?.substringBeforeLast('.') ?: "documents") }
            } else submit {
                val entries = mutableListOf<Entry>()
                var visited = 0
                suspend fun walk(entry: dynamic, prefix: String, depth: Int) {
                    visited++
                    require(depth <= 32 && visited <= 4096) { "Folder exceeds depth or entry-count limit" }
                    val path = safe(prefix + (entry.name as String))
                    if (entry.isFile == true) {
                        require(entries.size < MAX_FILES) { "Folder exceeds file-count limit" }
                        val file = Promise<dynamic> { resolve, reject -> entry.file({ value: dynamic -> resolve(value) }, { failure: dynamic -> reject(failure) }) }.await()
                        entries.add(Entry(path, file))
                    } else {
                        require(entry.isDirectory == true) { "Unsupported dropped entry" }
                        val reader = entry.createReader()
                        while (true) {
                            val batch = Promise<Array<dynamic>> { resolve, reject -> reader.readEntries({ value: dynamic -> resolve(value.unsafeCast<Array<dynamic>>()) }, { failure: dynamic -> reject(failure) }) }.await()
                            if (batch.isEmpty()) break
                            for (child in batch) walk(child, "$path/", depth + 1)
                        }
                    }
                }
                val singleFolder = roots.size == 1 && roots.first().isDirectory == true
                if (singleFolder) {
                    val root = roots.first()
                    val reader = root.createReader()
                    while (true) {
                        val batch = Promise<Array<dynamic>> { resolve, reject -> reader.readEntries({ value: dynamic -> resolve(value.unsafeCast<Array<dynamic>>()) }, { failure: dynamic -> reject(failure) }) }.await()
                        if (batch.isEmpty()) break
                        for (child in batch) walk(child, "", 0)
                    }
                } else for (root in roots) walk(root, "", 0)
                entries j if (singleFolder) roots.first().name as String else (entries.firstOrNull()?.path?.substringBeforeLast('.') ?: "documents")
            }
        }
        panel.addEventListener("dragover", { event -> dragOver(event) })
        panel.addEventListener("drop", { event -> dropped(event) })
        window.addEventListener("dragover", { event -> dragOver(event) })
        window.addEventListener("drop", { event -> dropped(event) })
        window.addEventListener("dragleave", { event ->
            if (event.asDynamic().relatedTarget == null) panel.classList.remove("ds-drag")
        })
        window.addEventListener("pagehide", { _: Event -> closed = true; abort?.abort(); owner.cancel() })
        // A cached page owns cancelled work. Rebuild its controls from the retained URL;
        // never retry an upload whose server may already have admitted some files.
        window.addEventListener("pageshow", { event: Event ->
            if (closed && event.asDynamic().persisted == true) window.location.reload()
        })
    }
}
