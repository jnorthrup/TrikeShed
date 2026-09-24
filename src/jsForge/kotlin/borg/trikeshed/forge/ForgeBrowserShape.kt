@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.shape.ShapeDoc
import borg.trikeshed.forge.shape.ShapeRoute
import borg.trikeshed.forge.shape.SHAPE_NAMES
import borg.trikeshed.forge.shape.importedFormatOf
import borg.trikeshed.forge.shape.importedViewOf
import borg.trikeshed.forge.shape.shapeDocOf
import borg.trikeshed.forge.shape.shapeDocOfBoxes
import borg.trikeshed.forge.shape.shapeGroups
import borg.trikeshed.forge.shape.shapeLadderRungs
import borg.trikeshed.forge.shape.shapeRouteOf
import borg.trikeshed.forge.shape.storeShapeDocOf
import borg.trikeshed.forge.sheet.workbookColumnName
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.files.File
import org.w3c.files.FileList
import kotlin.js.Promise

/**
 * Shape strip adapter — the jsForge DOM half of script.js "Shape strip (ingest motif)" and the
 * "group_level ladder" sections. The alphabet/gate/box walker are commonMain published on
 * `window.forgeKotlin` by `ForgeNodeMain`; grouping and taxonomy are commonMain
 * (`shape/ForgeShape.kt`); this file draws and runs the ingest lanes.
 */

/** The Kotlin facet published by ForgeNodeMain (runs/isPlan/fib/boxes/office/prepass), or null. */
fun forgeKotlinFacet(): dynamic = js("window.forgeKotlin && window.forgeKotlin.runs ? window.forgeKotlin : null")

fun ForgeBrowser.fibSet(): Set<Int> {
    shapeFib?.let { return it }
    val arr = forgeKotlinFacet().fib(1024) as Array<Int>
    val set = arr.toSet()
    shapeFib = set
    return set
}

// ── Render ──────────────────────────────────────────────────────────────

fun ForgeBrowser.shapeStrip(doc: ShapeDoc, di: Int): HTMLElement {
    val strip = el("div", "shape-strip")
    strip.setAttribute("role", "group")
    strip.setAttribute("aria-label", "Shape of " + doc.name)
    val fib = fibSet()
    doc.runs.forEachIndexed { ri, r ->
        val cell = el("button", "shape-cell")
        cell.style.setProperty("--c", "var(--shape-" + r.c + ", var(--shape-P))")
        cell.style.asDynamic().flex = r.n.toString() + " 0 2px"
        cell.classList.toggle("fib", ri in fib)
        cell.classList.toggle("picked", shapePick?.let { it.first == di && it.second == ri } == true)
        cell.title = (r.path ?: SHAPE_NAMES[r.c] ?: r.c) + " · " + r.n + " " + doc.unit +
            (if (r.path != null) "" else " " + (r.start + 1) + "–" + (r.end + 1)) +
            (if (ri in fib) " · depth $ri" else "")
        cell.setAttribute("aria-label", cell.title)
        cell.addEventListener("click", { shapePick = di to ri; renderShape() })
        strip.appendChild(cell)
    }
    if (doc.runs.isEmpty()) {
        val c = el("button", "shape-cell")
        c.style.asDynamic().flex = "1 0 4px"
        c.title = if (doc.via == "store") "store row — click for bytes" else "empty"
        c.addEventListener("click", { shapePick = di to 0; renderShape() })
        strip.appendChild(c)
    }
    return strip
}

/** One group = one Couch group_level=depth row; ids[0] is the representative doc. */
fun ForgeBrowser.shapeDocEl(prefix: String, ids: List<Int>): HTMLElement {
    val di = ids[0]
    val doc = shapeDocs[di]
    val group = ids.size > 1
    val box = el("div", "shape-doc")
    val head = el("div", "shape-doc-head")
    val isStore = doc.via == "store"
    head.append(
        el("span", "shape-name", if (group) "×${ids.size}  $prefix" else doc.name),
        el("span", "shape-chip " + doc.kind.split(' ')[0],
            if (isStore) "store row · " + doc.key.joinToString("")
            else if (doc.kind == "plan") "kanban plan · persist"
            else doc.kind + (if (doc.kind == "box media") "" else " · rejected")),
        el("span", "shape-meta",
            if (isStore) jsNumberLocale(doc.bytes) + " bytes · code " + doc.key.joinToString("")
            else doc.lines.size.toString() + " " + (if (doc.unit == "bytes") "boxes" else "lines") +
                " · " + doc.runs.size + " runs" + (doc.via?.let { " · $it" } ?: "")),
    )
    box.append(head, shapeStrip(doc, di), el("code", "shape-key", doc.key.joinToString(doc.sep)))
    if (group) box.appendChild(el("div", "shape-members", ids.joinToString(", ") { shapeDocs[it].name }))
    val pick = shapePick
    if (pick != null && pick.first == di) {
        if (isStore) {
            val pre = el("pre", "block-code shape-src", "loading bytes…")
            val path = "/" + SHAPE_DB_NAME + "/" + doc.name.split('/').joinToString("/") { urlEncode(it) } + "/content"
            scope.launch {
                pre.textContent = runCatching {
                    val response = window.fetch(path).await()
                    if (!response.ok) throw IllegalStateException(response.status.toString())
                    val t = response.text().await()
                    t.take(20000) + (if (t.length > 20000) "\n…" else "")
                }.getOrElse { "content unavailable" }
            }
            box.appendChild(pre)
        } else {
            val r = doc.runs.getOrNull(pick.second)
            if (r != null && r.start < doc.lines.size) {
                val text = doc.lines.subList(r.start.coerceAtLeast(0), (r.end + 1).coerceAtMost(doc.lines.size))
                    .mapIndexed { k, l -> (r.start + k + 1).toString().padStart(4) + "  " + l }
                    .joinToString("\n")
                box.appendChild(el("pre", "block-code shape-src", text))
            }
        }
    }
    return box
}

const val SHAPE_DB_NAME = "trikeshed"

fun urlEncode(s: String): String = js("encodeURIComponent(s)") as String

/** JS `Number.toLocaleString()` — grouped thousands. */
fun jsNumberLocale(value: Long): String {
    val v = value
    return js("v.toLocaleString()") as String
}

// ── R1: the group_level=<k> ladder, repointed from dropped files to store rows ──

fun ForgeBrowser.loadStoreRows() {
    shapePending++
    setView(ForgeView.Shape)
    scope.launch {
        runCatching {
            val response = window.fetch("/api/graal/map").await()
            val m = JsonSupport.parse(response.text().await()) as? Map<String, Any?>
            for (row in m?.get("rows").asList()) {
                val doc = storeShapeDocOf(row) ?: continue
                if (shapeDocs.any { it.name == doc.name && it.via == "store" }) continue
                shapeDocs.add(doc)
            }
            shapeStoreLoaded = true
            shapePick = null
            setView(ForgeView.Shape)
        }.onFailure { e ->
            el("shape-scroll")?.appendChild(el("span", "shape-count", "store rows unavailable: $e — drop files instead"))
        }
        shapePending = maxOf(0, shapePending - 1)
    }
}

fun ForgeBrowser.renderShape() {
    val shapeScrollEl = el("shape-scroll") ?: return
    shapeScrollEl.textContent = ""
    if (forgeKotlinFacet() == null) {
        shapeScrollEl.appendChild(el("span", "shape-count", "Kotlin bundle not loaded — bake with -PforgePagesStages=jvm,js"))
        return
    }
    val hud = el("div", "shape-hud")
    hud.setAttribute("role", "group")
    hud.setAttribute("aria-label", "Shape depth (group level)")
    val maxKey = maxOf(0, shapeDocs.maxOfOrNull { it.key.size } ?: 0)
    for (f in shapeLadderRungs(fibSet().toList(), maxKey)) {
        val label = if (f == Int.MAX_VALUE) "∞" else f.toString()
        val b = el("button", "topbar-btn" + (if (shapeDepth == f) " active" else ""), label)
        b.title = "group_level=$label"
        b.setAttribute("aria-label", "Depth $label")
        b.addEventListener("click", { shapeDepth = f; renderShape() })
        hud.appendChild(b)
    }
    if (!shapeStoreLoaded) {
        val s = el("button", "topbar-btn", "⬇ store rows")
        s.title = "repoint the ladder: load the daemon store rows (grouped by code nibbles)"
        s.setAttribute("aria-label", "Load store rows")
        s.addEventListener("click", { loadStoreRows() })
        hud.appendChild(s)
    }
    hud.appendChild(el("span", "shape-count",
        shapeDocs.size.toString() + " files" + (if (shapePending > 0) " · $shapePending processing" else "") + " · drop files anywhere"))
    shapeScrollEl.appendChild(hud)
    for ((prefix, ids) in shapeGroups(shapeDocs, shapeDepth)) {
        shapeScrollEl.appendChild(shapeDocEl(prefix, ids))
    }
    val legend = el("div", "shape-legend")
    for ((c, name) in SHAPE_NAMES) {
        val s = el("span", "", "$c $name")
        val i = el("i", "")
        i.style.setProperty("--c", "var(--shape-$c)")
        s.insertBefore(i, s.firstChild)
        legend.appendChild(s)
    }
    shapeScrollEl.appendChild(legend)
}

// ── Ingest lanes ────────────────────────────────────────────────────────

/**
 * Everything that is not text or box media goes to the local ingester (Tika, ffmpeg+tesseract);
 * with no server the browser does it: office parts via commonMain, images and thin PDF pages via
 * the same pre-pass → tesseract.js.
 */
fun ForgeBrowser.importExtractedFile(fileName: String, text: String): ForgeView {
    val view = importedViewOf(fileName)
    if (view == ForgeView.Sheet) {
        importWorkbook(fileName, text)
    } else {
        importDocument(fileName, text, importedFormatOf(fileName))
    }
    return view
}

/** `importWorkbook`: parse delimited text into a workbook sheet and switch to the sheet view. */
fun ForgeBrowser.importWorkbook(name: String, text: String) {
    val sheet = borg.trikeshed.forge.doc.parseDelimitedSheet(name, text, ::workbookColumnName)
    mutate { s ->
        s.workbook.sheets.add(sheet)
        s.workbook.activeSheetId = sheet.id
        s.workbook.selected = borg.trikeshed.forge.sheet.CellRef(0, 0)
        s.sheetId = sheet.id
        s.view = ForgeView.Sheet
    }
    renderAll()
}

fun ForgeBrowser.shapeOfText(name: String, text: String): ShapeDoc {
    val k = forgeKotlinFacet()
    val runStrings = (k.runs(text) as Array<String>).toList()
    return shapeDocOf(name, text, runStrings, (k.isPlan(text) as Boolean))
}

fun ForgeBrowser.shapeOfBoxesDoc(name: String, buf: dynamic): ShapeDoc {
    val rows = (forgeKotlinFacet().boxes(Int8Array(buf as org.khronos.webgl.ArrayBuffer)) as Array<String>).toList()
    return shapeDocOfBoxes(name, rows)
}

fun ocrCanvas(canvas: HTMLElement): Promise<String> = js(
    """
    (function() {
        var ctx = canvas.getContext('2d');
        var img = ctx.getImageData(0, 0, canvas.width, canvas.height);
        window.forgeKotlin.prepass(new Int8Array(img.data.buffer));
        ctx.putImageData(img, 0, 0);
        var ensure = window.Tesseract ? Promise.resolve() : new Promise(function(ok, no) {
            var s = document.createElement('script');
            s.src = 'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js';
            s.onload = ok; s.onerror = no;
            document.head.appendChild(s);
        });
        return ensure.then(function() { return Tesseract.recognize(canvas, 'eng'); }).then(function(res) { return res.data.text; });
    })()
    """
)

fun imageText(f: File): Promise<String> = js(
    """
    (function() {
        return createImageBitmap(f).then(function(bmp) {
            var c = document.createElement('canvas');
            c.width = bmp.width; c.height = bmp.height;
            c.getContext('2d').drawImage(bmp, 0, 0);
            return ocrCanvas(c);
        });
    })()
    """
)

fun pdfText(f: File): Promise<String> = js(
    """
    (function() {
        // Hidden from webpack's parser (classic-script target rejects import()); the browser
        // still does a real dynamic import of the ESM build at call time.
        var dynImport = new Function('u', 'return import(u)');
        return Promise.all([
            dynImport('https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/pdf.min.mjs'),
            f.arrayBuffer()
        ]).then(function(pair) {
            var pdfjs = pair[0];
            pdfjs.GlobalWorkerOptions.workerSrc = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/pdf.worker.min.mjs';
            return pdfjs.getDocument({ data: pair[1] }).promise;
        }).then(function(doc) {
            var pages = [];
            var chain = Promise.resolve();
            var _loop = function(i) {
                chain = chain.then(function() {
                    return doc.getPage(i).then(function(page) {
                        return page.getTextContent().then(function(tc) {
                            var text = tc.items.map(function(t) { return t.str; }).join(' ');
                            // OCR_STRATEGY auto: thin text layer ⇒ rasterise
                            if (text.replace(/\s/g, '').length >= 10) { pages.push(text); return null; }
                            var vp = page.getViewport({ scale: 2 });
                            var c = document.createElement('canvas');
                            c.width = vp.width; c.height = vp.height;
                            return page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise
                                .then(function() { return ocrCanvas(c); })
                                .then(function(t) { pages.push(t); });
                        });
                    });
                });
            };
            for (var i = 1; i <= doc.numPages; i++) _loop(i);
            return chain.then(function() { return pages.join('\n\n'); });
        });
    })()
    """
)

fun ForgeBrowser.browserText(f: File): Promise<String> {
    val k = forgeKotlinFacet()
    return when (shapeRouteOf(f.name)) {
        ShapeRoute.Office -> js("f.arrayBuffer().then(function(b) { return k.office(new Int8Array(b)); })")
        ShapeRoute.Image -> imageText(f)
        ShapeRoute.Pdf -> pdfText(f)
        else -> js("Promise.reject('unsupported')")
    }
}

fun ForgeBrowser.tikaIngest(f: File, done: (ShapeDoc) -> Unit) {
    shapePending++
    setView(ForgeView.Shape)
    scope.launch {
        val doc: ShapeDoc = try {
            val response = window.fetch("/ingest", org.w3c.fetch.RequestInit(
                method = "POST",
                body = f,
                headers = org.w3c.fetch.Headers().also { it.append("X-Forge-Name", f.name) },
            )).await()
            if (!response.ok) throw IllegalStateException(response.status.toString())
            val j = JsonSupport.parse(response.text().await()) as? Map<String, Any?> ?: emptyMap()
            val text = j["markdown"].asStr()
            val importedView = importExtractedFile(f.name, text)
            shapeOfText(f.name, text)
                .withVia("tika" + (if (j["persisted"].asBool()) " · persisted" else ""))
                .also { it.importedView = importedView }
        } catch (tikaFailure: Throwable) {
            try {
                val text = browserText(f).await()
                val importedView = importExtractedFile(f.name, text)
                shapeOfText(f.name, "# " + f.name + "\n\n" + text + "\n")
                    .withVia("browser")
                    .also { it.importedView = importedView }
            } catch (browserFailure: Throwable) {
                ShapeDoc(
                    name = f.name, lines = emptyList(), runs = emptyList(), key = emptyList(), sep = "",
                    kind = "unsupported here", unit = "lines", via = "./gradlew serveForgePages",
                )
            }
        }
        shapePending--
        done(doc)
    }
}

fun ShapeDoc.withVia(v: String): ShapeDoc = ShapeDoc(
    name = name, lines = lines, runs = runs, key = key, sep = sep, kind = kind, unit = unit,
    via = v, bytes = bytes, importedView = importedView,
)

fun ForgeBrowser.shapeIngest(files: FileList) {
    if (forgeKotlinFacet() == null) {
        setView(ForgeView.Shape)
        return
    }
    val fileList = (0 until files.length).mapNotNull { files.item(it) }
    var importedView: ForgeView? = null
    var remaining = fileList.size
    if (remaining == 0) return
    val collected = mutableListOf<ShapeDoc>()
    fun finish() {
        remaining--
        if (remaining > 0) return
        for (doc in collected) {
            shapeDocs.add(doc)
            doc.importedView?.let { importedView = it }
        }
        shapePick = null
        setView(importedView ?: ForgeView.Shape)
    }
    for (f in fileList) {
        when (shapeRouteOf(f.name)) {
            ShapeRoute.Media -> scope.launch {
                val buf = js("f.arrayBuffer()").unsafeCast<Promise<org.khronos.webgl.ArrayBuffer>>().await()
                collected.add(shapeOfBoxesDoc(f.name, buf))
                finish()
            }
            ShapeRoute.Text -> scope.launch {
                val t = f.asDynamic().text().unsafeCast<Promise<String>>().await()
                val view = importExtractedFile(f.name, t)
                collected.add(shapeOfText(f.name, t).withVia("browser").also { it.importedView = view })
                finish()
            }
            else -> tikaIngest(f) { doc ->
                collected.add(doc)
                finish()
            }
        }
    }
}

// ── Drop zone + gallery wiring ──────────────────────────────────────────

fun ForgeBrowser.wireDropZone() {
    val dropZoneEl = el("drop-zone") ?: return
    val fileInputEl = document.getElementById("file-input") as? HTMLInputElement ?: return
    dropZoneEl.addEventListener("click", { fileInputEl.click() })
    dropZoneEl.addEventListener("keydown", { e ->
        val key = (e as KeyboardEvent).key
        if (key == "Enter" || key == " ") {
            e.preventDefault()
            fileInputEl.click()
        }
    })
}

fun ForgeBrowser.wireShapeIngest() {
    val dropZoneEl = el("drop-zone")
    val fileInputEl = document.getElementById("file-input") as? HTMLInputElement ?: return
    document.addEventListener("dragover", { e ->
        e.preventDefault()
        dropZoneEl?.classList?.add("drag-active")
    })
    document.addEventListener("dragleave", { e ->
        val de = e.asDynamic()
        if (de.relatedTarget == null) dropZoneEl?.classList?.remove("drag-active")
    })
    document.addEventListener("drop", { e ->
        e.preventDefault()
        dropZoneEl?.classList?.remove("drag-active")
        val files = e.asDynamic().dataTransfer?.files as? FileList
        if (files != null && files.length > 0) shapeIngest(files)
    })
    fileInputEl.addEventListener("change", {
        fileInputEl.files?.let { shapeIngest(it) }
        fileInputEl.value = ""
    })
}

/** Gallery cards for confix.* widgets open the sheet view. */
fun ForgeBrowser.wireGallery() {
    val galleryBody = document.querySelector(".sidebar-gallery-body") as? HTMLElement ?: return
    galleryBody.addEventListener("click", { e ->
        val target = e.target as? HTMLElement ?: return@addEventListener
        val card = target.closest(".gallery-card") as? HTMLElement ?: return@addEventListener
        val id = card.querySelector(".id")?.textContent ?: ""
        when {
            id.trim().startsWith("confix.") -> {
                setView(ForgeView.Sheet)
                openSheet("confix")
            }
            id.trim() == "forge.graph" -> setView(ForgeView.Graph)
            id.trim().startsWith("host.") -> setView(ForgeView.Host)
        }
    })
}
