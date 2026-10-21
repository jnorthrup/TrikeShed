package borg.trikeshed.forge.server

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.JvmTikaIngestAdapter
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.util.io.ContentTypes
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.narsese.DocumentAnchor
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ProjectMiner — the MINING half of "a pile of mining assets": Tika/OCR text
 * extraction over a project db's binary documents, landed back INTO the db as
 * `<path>.extract.md` citizens.
 *
 * The CAS linkages fall out of content addressing, not bookkeeping: two PDFs
 * with the same payload extract to the same markdown → the SAME ContentId →
 * the graal terrain's cyan shared-blob arcs connect them across territories.
 *
 * Durability follows each db's own shape: dir-backed dbs get the extract file
 * written into the forge-home clone (the next remount re-absorbs it); upload
 * dbs go through [ProjectScopes.uploadPut] (manifest + mirror + store in one).
 */
class ProjectMiner(
    private val registry: ProjectDbRegistry,
    private val scopes: ProjectScopes,
    private val casStore: CasStore,
    private val beliefBag: BeliefBagElement?,
    private val filesRoot: File?,
) {
    data class Progress(
        @Volatile var total: Int = 0,
        @Volatile var extracted: Int = 0,
        @Volatile var skipped: Int = 0,
        @Volatile var failed: Int = 0,
        @Volatile var minted: Int = 0,
        @Volatile var done: Boolean = false,
        @Volatile var note: String = "",
    )

    private val runs = ConcurrentHashMap<String, Progress>()
    private val ingesting = ConcurrentHashMap<Pair<String, String>, Mutex>()

    /** Pages OCR'd at once across every document in flight: many books share the machine, not each its own budget. */
    private val pageGate = kotlinx.coroutines.sync.Semaphore(OCR_PARALLEL)

    fun progress(name: String): Progress? = runs[name]

    companion object {
        /** Pages read per poppler call: bounds memory, not what gets read. */
        const val PAGE_BATCH = 50
        /** The notes stream of a paged document, beside its extract. */
        const val FOOTNOTES_SUFFIX = borg.trikeshed.lcnc.ProjectNodes.FOOTNOTES_SUFFIX
        /** OCR pages run at once: Tesseract is single-threaded per page, so pages are the parallel unit. */
        val OCR_PARALLEL = maxOf(2, Runtime.getRuntime().availableProcessors() - 2)
        /** The route every PDF's pages take: the operator's `TRIKESHED_PDF_ROUTE` (auto | layer | ocr), auto when unset. */
        val PDF_ROUTE: PdfRoute = System.getenv("TRIKESHED_PDF_ROUTE")?.trim()
            ?.let { r -> PdfRoute.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } } ?: PdfRoute.AUTO
        /** A document's OCR pages stay under the configured JVM temporary root, never in the forge home. */
        fun ocrDir(name: String, id: String) = File(System.getProperty("java.io.tmpdir"), ".ocr/$name/$id")

        /** Completion binds retained text and layout to the source CID and its exact document revision. */
        fun currentExtraction(pdb: ProjectDb, id: String): Boolean {
            if (pdb.store.head.isDeleted(id)) return false
            val source = pdb.gateway.getAttachmentRef(id)?.contentId ?: return false
            val sourceRev = pdb.store.head.getRev(id) ?: return false
            val text = pdb.gateway.getAttachmentRef(id + ProjectNodes.EXTRACT_SUFFIX)?.contentId ?: return false
            val origin = pdb.gateway.getAttachment(id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX)
                ?.second?.decodeToString()?.split('\t') ?: return false
            if (origin.size != 2 || origin[0] != source.value || origin[1] != text.value) return false
            val completion = pdb.gateway.getAttachment(id + ProjectNodes.EXTRACTION_SUFFIX)?.second ?: return false
            val lineage = reifyStrict(completion.decodeToString()) as? Map<*, *> ?: return false
            if (lineage["originalCid"] != source.value || lineage["originalRev"] != sourceRev ||
                lineage["extractedTextCid"] != text.value) return false
            return !id.lowercase().endsWith(".pdf") || (pdb.gateway.getAttachmentRef(id + FOOTNOTES_SUFFIX)?.length ?: 0) > 0
        }
        /** Markup read as its markup declares it ([borg.trikeshed.narsese.MarkupStreams]): no print, no OCR, no Tika. */
        val MARKUP = setOf("html", "htm", "xhtml")
        /** Formats read into curation: PDF pages, markup, and what Tika earns its keep on. Plain text/markdown mints at mount already. */
        val MINEABLE = setOf(
            "pdf", "docx", "doc", "rtf", "odt", "pptx", "ppt", "xlsx", "epub",
            "png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp",
        ) + MARKUP
    }

    /** A document's text by its format: PDF pages by [PDF_ROUTE], markup by its declared structure, the rest through Tika. */
    private suspend fun textOf(name: String, id: String, src: File, originalCid: ContentId): Pair<String?, List<DocumentAnchor>> = when (id.substringAfterLast('.', "").lowercase()) {
        "pdf" -> readPages(name, id, src, PDF_ROUTE, originalCid)?.let { b ->
            putTwin(name, id + FOOTNOTES_SUFFIX, borg.trikeshed.narsese.PageStreams.notesTsv(b.notes).encodeToByteArray(), "project-miner", "page-streams")
            val prefix = "# ${id.substringAfterLast('/')}\n\n"
            (prefix + b.body) to b.anchors.map { it.copy(begin = it.begin + prefix.length, end = it.end + prefix.length) }
        } ?: (null to emptyList())
        in MARKUP -> src.readText().let { html ->
            // The anchors are their own stream, a twin beside the extract: what the page links out to is curated, never inlined.
            putTwin(name, id + borg.trikeshed.lcnc.ProjectNodes.LINKS_SUFFIX,
                borg.trikeshed.narsese.MarkupStreams.linksTsv(html).encodeToByteArray(), "project-miner", "markup-streams")
            borg.trikeshed.narsese.MarkupStreams.text(html) to emptyList()
        }
        else -> runCatching { JvmTikaIngestAdapter.extractToMarkdown(src.toPath()) }.getOrNull() to emptyList()
    }

    /**
     * Lands a twin beside a document the way the store keeps it: an upload scope through its upload
     * path, a directory scope in its clone dir (the durable source) and as an attachment.
     */
    fun putTwin(name: String, twinId: String, bytes: ByteArray, agent: String, revision: String) {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val scope = scopes.list().firstOrNull { it.name == name }
        // An uploaded project (path @upload; older ledgers: kind "upload") is rebuilt from its manifest at boot,
        // so its twins go through the upload writer that appends there, or they vanish on restart.
        if (scope?.path == "@upload" || (scope?.kind ?: pdb.kind) == "upload") { scopes.uploadPut(name, twinId, bytes); return }
        filesRoot?.let { fr -> runCatching { File(File(fr, name), twinId).apply { parentFile?.mkdirs() }.writeBytes(bytes) } }
        pdb.gateway.putAttachment(
            OroborosAttachmentRef(
                path = twinId, contentType = ContentTypes.forPath(twinId), length = bytes.size.toLong(),
                contentId = ContentId.of(bytes), agentId = agent, revision = revision, sequence = System.currentTimeMillis(),
            ),
            bytes,
        )
    }

    /** How a PDF's pages are read. */
    enum class PdfRoute {
        /** A page drawn from text reads from its own text layer; a page that is a picture (a scan) is OCR'd. */
        AUTO,
        /** Every page that carries a text layer reads from it; the rest are OCR'd. */
        LAYER,
        /** Every page is OCR'd: no layer is trusted. */
        OCR,
    }

    /** A poppler or tesseract run, started with the guest environment rather than a copy of the daemon's. */
    private fun tool(vararg cmd: String): ProcessBuilder = borg.trikeshed.graal.subvm.GuestEnvironment.curate(ProcessBuilder(*cmd))

    /** Page count and the pages that carry a text layer, read by poppler; null when poppler is absent. */
    private fun layeredPages(f: File): Pair<Int, Set<Int>>? = runCatching {
        val proc = tool("pdftotext", "-enc", "UTF-8", f.absolutePath, "-").start()
        val layer = proc.inputStream.readAllBytes().decodeToString().also { proc.errorStream.readAllBytes(); proc.waitFor() }
        // pdftotext closes every page with a form feed, blank pages included: the page count is the feed count.
        val pages = layer.split('\u000c').dropLast(1)
        pages.size to pages.indices.filter { pages[it].isNotBlank() }.map { it + 1 }.toSet()
    }.getOrNull()

    /** A poppler run's whole output, its errors folded in. */
    private fun output(vararg cmd: String): String =
        tool(*cmd).redirectErrorStream(true).start().let { p -> p.inputStream.readAllBytes().decodeToString().also { p.waitFor() } }

    /**
     * The pages that are pictures: a raster drawn over at least half the page, as a scan is — whatever text
     * layer a scanner left over it. A page drawn from text carries no such raster.
     */
    private fun picturedPages(f: File, pages: Int): Set<Int> = runCatching {
        val area = Regex("Page\\s+(\\d+)\\s+size:\\s+([\\d.]+)\\s+x\\s+([\\d.]+)").findAll(output("pdfinfo", "-f", "1", "-l", "$pages", f.absolutePath))
            .associate { m -> m.groupValues[1].toInt() to m.groupValues[2].toDouble() * m.groupValues[3].toDouble() }
        // pdfimages -list columns: page num type width height color comp bpc enc interp object ID x-ppi y-ppi size ratio
        output("pdfimages", "-list", f.absolutePath).lines().mapNotNull { row ->
            val c = row.trim().split(Regex("\\s+"))
            val pg = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val xp = c.getOrNull(12)?.toDoubleOrNull() ?: 0.0; val yp = c.getOrNull(13)?.toDoubleOrNull() ?: 0.0
            if (c.getOrNull(2) != "image" || xp <= 0 || yp <= 0) return@mapNotNull null
            // The area it is drawn at, in points: pixels over pixels per inch, 72 points to the inch.
            pg.takeIf { c[3].toDouble() / xp * 72 * (c[4].toDouble() / yp * 72) >= (area[pg] ?: Double.MAX_VALUE) / 2 }
        }.toSet()
    }.getOrDefault(emptySet())

    /** A page's text layer as word boxes in Tesseract's TSV — block before paragraph, points as 300 dpi pixels — for [borg.trikeshed.narsese.PageStreams]. */
    private fun layerTsv(f: File, pg: Int): String {
        fun px(v: String) = Math.round(v.toDouble() * 300 / 72).toString()
        val out = StringBuilder("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n")
        // Poppler's columns: level page_num par_num block_num line_num word_num left top width height conf text.
        for (row in output("pdftotext", "-enc", "UTF-8", "-f", "$pg", "-l", "$pg", "-tsv", f.absolutePath, "-").lineSequence()) {
            val c = row.split('\t')
            if (c.size < 12 || c[0] != "5") continue
            out.append("5\t${c[1]}\t${c[3]}\t${c[2]}\t${c[4]}\t${c[5]}\t${px(c[6])}\t${px(c[7])}\t${px(c[8])}\t${px(c[9])}\t${c[10]}\t${c[11]}\n")
        }
        return out.toString()
    }

    /** Detached mining pass. One run per db at a time; re-runs skip docs already extracted. */
    /**
     * What a document is, before anything is read into it: bytes, type, whether it carries its own
     * text, its page count and layout (columns per page over a sample), its producer, and whether it
     * has been ingested. Reading only headers and a few sampled pages — ingest stays a separate act.
     */
    fun recognize(name: String, id: String): Map<String, Any?> {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
        val att = pdb.gateway.getAttachmentRef(id) ?: return mapOf("error" to "absent", "id" to id)
        val ext = id.substringAfterLast('.', "").lowercase()
        val out = linkedMapOf<String, Any?>("project" to name, "id" to id, "bytes" to att.length,
            "contentType" to att.contentType, "cid" to att.contentId.value,
            "ingested" to currentExtraction(pdb, id), "notes" to (pdb.store.get("$id${borg.trikeshed.lcnc.ProjectNodes.NOTES_SUFFIX}") != null))
        if (ext == "pdf" && onDisk != null) {
            val info = runCatching {
                val proc = tool("pdfinfo", onDisk.absolutePath).start()
                proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() }
            }.getOrDefault("")
            fun field(k: String) = Regex("(?m)^$k:[ \\t]+(.+)$").find(info)?.groupValues?.get(1)?.trim()
            val pages = field("Pages")?.toIntOrNull()
            val layeredSet = layeredPages(onDisk)?.second.orEmpty()
            val layered = layeredSet.size
            val text = pages != null && layered == pages
            val fromLayer = when (PDF_ROUTE) {
                PdfRoute.OCR -> 0
                PdfRoute.LAYER -> layered
                PdfRoute.AUTO -> (layeredSet - picturedPages(onDisk, pages ?: 0)).size
            }
            out += mapOf("pages" to pages, "title" to field("Title"), "author" to field("Author"), "producer" to field("Producer"),
                "textLayer" to text, "layeredPages" to layered,
                "route" to PDF_ROUTE.name.lowercase(), "layerPages" to fromLayer, "ocrPages" to ((pages ?: 0) - fromLayer),
                "pagesDone" to ocrDone["$name/$id/${att.contentId.value}"])
            // Layout: columns found on a spread of sample pages (front, middle, back — where an index lives).
            if (text && pages != null && pages > 0) {
                val sample = listOf(pages / 10, pages / 2, pages - pages / 20).map { it.coerceIn(1, pages) }.distinct()
                out["columns"] = sample.associate { pg ->
                    val html = runCatching {
                        val proc = tool("pdftotext", "-bbox", "-enc", "UTF-8", "-f", "$pg", "-l", "$pg", onDisk.absolutePath, "-").start()
                        proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() }
                    }.getOrDefault("")
                    val words = Regex("<word xMin=\"([\\d.]+)\" yMin=\"([\\d.]+)\" xMax=\"([\\d.]+)\" yMax=\"([\\d.]+)\">").findAll(html)
                        .map { m -> borg.trikeshed.narsese.PageColumns.Word(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), m.groupValues[4].toDouble(), "") }.toList()
                    "p$pg" to borg.trikeshed.narsese.PageColumns.gutters(words).size + 1
                }
            }
        }
        return out
    }

    /** Pages an OCR ingest has finished, per document: what `recognize` and a resumed ingest read. */
    private val ocrDone = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * A PDF read page by page, each page's word boxes (Tesseract's TSV) from where [route] reads it: a page
     * drawn from text from its own layer; a picture rendered at 300 dpi and read by Tesseract's own layout
     * analysis (`--psm 3`: column finding by tab stops, rules removed), so two-column pages, dictionaries and
     * indexes read column by column. The boxes are kept per page as they land, so a stopped ingest resumes at
     * the next page; the book is then read as page streams — running head, body, notes — from those boxes
     * ([borg.trikeshed.narsese.PageStreams]). Pages run [OCR_PARALLEL] at a time.
     */
    private suspend fun readPages(name: String, id: String, f: File, route: PdfRoute, originalCid: ContentId): borg.trikeshed.narsese.PageStreams.Book? = coroutineScope {
        val pages = runCatching {
            val proc = tool("pdfinfo", f.absolutePath).start()
            Regex("Pages:[ \\t]+(\\d+)").find(proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() })?.groupValues?.get(1)?.toInt()
        }.getOrNull() ?: return@coroutineScope null
        val layered = if (route == PdfRoute.OCR) emptySet() else layeredPages(f)?.second.orEmpty()
        val fromLayer = if (route == PdfRoute.AUTO) layered - picturedPages(f, pages) else layered
        val dir = File(ocrDir(name, id), "${originalCid.hex}/${route.name.lowercase()}").apply { mkdirs() }
        val gate = pageGate
        val key = "$name/$id/${originalCid.value}"
        ocrDone[key] = dir.listFiles { x -> x.name.endsWith(".tsv") }?.size ?: 0
        val queue = Channel<Int>(OCR_PARALLEL)
        val workers = List(OCR_PARALLEL) {
            launch(Dispatchers.IO) { for (pg in queue) {
                val base = File(dir, "%05d".format(pg))
                val out = File(base.path + ".tsv")
                if (out.isFile) continue
                if (pg in fromLayer) {
                    out.writeText(layerTsv(f, pg))
                    val n = ocrDone.merge(key, 1, Int::plus) ?: 0
                    if (n % 25 == 0) System.err.println("[OROBOROS] PDF $key: $n/$pages pages")
                    continue
                }
                gate.acquire()
                try {
                    val png = File(dir, "p$pg")
                    tool("pdftoppm", "-f", "$pg", "-l", "$pg", "-r", "300", "-gray", "-png", "-singlefile", f.absolutePath, png.absolutePath)
                        .redirectErrorStream(true).start().also { it.inputStream.readAllBytes(); it.waitFor() }
                    val img = File(png.absolutePath + ".png")
                    // The tika4all pre-pass on every rendered page — grayscale + contrast/brightness equalisation — then OCR.
                    // Tesseract is handed the real path: Leptonica rewrites a path under /tmp to the macOS per-user temp dir and reads nothing.
                    val conditioned = borg.trikeshed.kanban.JvmTikaIngestAdapter.preprocess(img.toPath())
                    // One read, two renderings: the page text and its word boxes.
                    val part = File(dir, "part-$pg")
                    val proc = tool("tesseract", conditioned.toRealPath().toString(), part.absolutePath, "--psm", "3", "txt", "tsv").redirectErrorStream(true).start()
                    proc.inputStream.readAllBytes(); proc.waitFor()
                    img.delete()
                    java.nio.file.Files.deleteIfExists(conditioned)
                    File(part.path + ".txt").renameTo(File(base.path + ".txt"))
                    File(part.path + ".tsv").renameTo(out)
                    val n = ocrDone.merge(key, 1, Int::plus) ?: 0
                    if (n % 25 == 0) System.err.println("[OROBOROS] PDF $key: $n/$pages pages")
                } finally { gate.release() }
            } }
        }
        try { for (pg in 1..pages) queue.send(pg) } finally { queue.close() }
        workers.joinAll()
        val read = (1..pages).map { pg -> borg.trikeshed.narsese.PageStreams.page(pg, File(dir, "%05d.tsv".format(pg)).takeIf { it.isFile }?.readText().orEmpty()) }
        borg.trikeshed.narsese.PageStreams.book(read)
    }

    private fun sourceDigest(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest())
    }

    /** Parse an owned, digest-checked snapshot; publish its source/text association last. */
    suspend fun ingest(name: String, id: String): Map<String, Any?> =
        ingesting.computeIfAbsent(name to id) { Mutex() }.withLock { withContext(Dispatchers.IO) {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        if (pdb.store.head.isDeleted(id)) return@withContext mapOf("error" to "absent", "id" to id)
        val source = pdb.gateway.getAttachmentRef(id) ?: return@withContext mapOf("error" to "absent", "id" to id)
        val sourceRev = pdb.store.head.getRev(id).orEmpty()
        val dir = java.nio.file.Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath(), "project-source-").toFile()
        val src = File(dir, id.substringAfterLast('/'))
        val began = System.currentTimeMillis()
        val ext = id.substringAfterLast('.', "").lowercase()
        try {
            val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
            if (onDisk != null) onDisk.copyTo(src, overwrite = true)
            else {
                val att = pdb.gateway.getAttachment(id) ?: return@withContext mapOf("error" to "absent", "id" to id)
                check(att.first.contentId == source.contentId) { "source changed before snapshot: $id" }
                src.writeBytes(att.second)
            }
            check(sourceDigest(src) == source.contentId.value) { "source snapshot does not match its CID: $id" }
            val (md, anchors) = textOf(name, id, src, source.contentId)
            val body = md?.trimEnd().orEmpty()
            check(!pdb.store.head.isDeleted(id) && pdb.gateway.getAttachmentRef(id)?.contentId == source.contentId &&
                pdb.store.head.getRev(id) == sourceRev) {
                "source changed during extraction: $id"
            }
            if (body.length < 80) return@withContext mapOf("verdict" to "no_text", "id" to id)
            putExtraction(name, id, source.contentId, sourceRev, body, anchors)
            mapOf("verdict" to "ingested", "id" to id, "chars" to body.length,
                "route" to when { ext == "pdf" -> "pdf pages (${PDF_ROUTE.name.lowercase()})"; ext in MARKUP -> "markup"; else -> "tika" },
                "ms" to (System.currentTimeMillis() - began))
        } finally { dir.deleteRecursively() }
    } }

    /** Publish lineage last: a stale or interrupted extraction is never a current twin. */
    private fun putExtraction(name: String, id: String, originalCid: ContentId, originalRev: String,
                              text: String, anchors: List<DocumentAnchor>) {
        val bytes = text.encodeToByteArray()
        putTwin(name, id + ProjectNodes.EXTRACT_SUFFIX, bytes, "project-miner", "ingested")
        putTwin(name, id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX,
            "${originalCid.value}\t${ContentId.of(bytes).value}".encodeToByteArray(), "project-miner", "origin")
        putTwin(name, id + ProjectNodes.EXTRACTION_SUFFIX, jsonOf(mapOf(
            "version" to 1, "originalCid" to originalCid.value, "originalRev" to originalRev,
            "extractedTextCid" to ContentId.of(bytes).value, "anchors" to anchors.map(DocumentAnchor::toMap),
            "unresolved" to if (anchors.isEmpty()) listOf("page", "folio", "section", "table", "diagram", "coordinates") else listOf("table", "diagram"),
        )).encodeToByteArray(), "project-miner", "extraction-lineage")
    }

    suspend fun mine(name: String, cap: Int = 1000): Progress {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val existing = runs[name]
        if (existing != null && !existing.done) return existing
        val prog = Progress()
        runs[name] = prog
        val storeIds = pdb.store.ids()
        val ids = mutableListOf<String>()
        for (i in 0 until storeIds.a) {
            val id = storeIds.b(i)
            if (!pdb.store.head.isDeleted(id) && !borg.trikeshed.lcnc.ProjectNodes.twin(id) &&
                id.substringAfterLast('.', "").lowercase() in MINEABLE) ids.add(id)
        }
        val work = ids.filter { !currentExtraction(pdb, it) }.take(cap)
        prog.total = work.size

        withContext(Dispatchers.IO) {
            for (id in work) {
                try {
                    val result = ingest(name, id)
                    when {
                        result["verdict"] == "ingested" -> prog.extracted++
                        result["error"] == "absent" -> prog.skipped++
                        else -> prog.failed++
                    }
                } catch (t: Throwable) {
                    prog.failed++
                    prog.note = "${id.take(60)}: ${t.message?.take(80)}"
                }
            }
        }
        prog.done = true
        System.err.println("[OROBOROS] mined $name: ${prog.extracted}/${prog.total} extracted, ${prog.minted} beliefs, ${prog.skipped} skipped, ${prog.failed} failed")
        return prog
    }
}
