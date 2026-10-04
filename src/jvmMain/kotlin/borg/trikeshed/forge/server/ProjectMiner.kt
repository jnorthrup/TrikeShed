package borg.trikeshed.forge.server

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.JvmTikaIngestAdapter
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.util.io.ContentTypes
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        /** Formats Tika earns its keep on. Plain text/markdown mints at mount already. */
        val MINEABLE = setOf(
            "pdf", "docx", "doc", "rtf", "odt", "pptx", "ppt", "xlsx", "epub",
            "png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp",
        )
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

    /**
     * A PDF's text, every page OCR'd: old books read better through today's OCR than through the
     * text layer an upstream scanner left in them, so no layer is trusted.
     */
    private suspend fun pdfText(name: String, id: String, f: File): String? = ocrPages(name, id, f)?.let { b ->
        // The notes are their own stream, a twin beside the extract: page, folio, sections, marker, ordinal, measure.
        putTwin(name, id + FOOTNOTES_SUFFIX, borg.trikeshed.narsese.PageStreams.notesTsv(b.notes).encodeToByteArray(), "project-miner", "page-streams")
        b.body.takeIf { it.isNotBlank() }
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

    /** Detached mining pass. One run per db at a time; re-runs skip docs already extracted. */
    /**
     * What a document is, before anything is read into it: bytes, type, whether it carries its own
     * text, its page count and layout (columns per page over a sample), its producer, and whether it
     * has been ingested. Reading only headers and a few sampled pages — ingest stays a separate act.
     */
    fun recognize(name: String, id: String): Map<String, Any?> {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
        val att = pdb.gateway.getAttachment(id) ?: return mapOf("error" to "absent", "id" to id)
        val ext = id.substringAfterLast('.', "").lowercase()
        val out = linkedMapOf<String, Any?>("project" to name, "id" to id, "bytes" to att.first.length,
            "contentType" to att.first.contentType, "cid" to att.first.contentId.value,
            "ingested" to (pdb.store.get("$id.extract.md") != null), "notes" to (pdb.store.get("$id${borg.trikeshed.lcnc.ProjectNodes.NOTES_SUFFIX}") != null))
        if (ext == "pdf" && onDisk != null) {
            val info = runCatching {
                val proc = tool("pdfinfo", onDisk.absolutePath).start()
                proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() }
            }.getOrDefault("")
            fun field(k: String) = Regex("(?m)^$k:[ \\t]+(.+)$").find(info)?.groupValues?.get(1)?.trim()
            val pages = field("Pages")?.toIntOrNull()
            val layered = layeredPages(onDisk)?.second?.size ?: 0
            val text = pages != null && layered == pages
            out += mapOf("pages" to pages, "title" to field("Title"), "author" to field("Author"), "producer" to field("Producer"),
                "textLayer" to text, "layeredPages" to layered,
                "route" to "OCR every page (Tesseract layout, column order)",
                "ocrPagesDone" to ocrDone["$name/$id"])
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
     * A PDF read page by page: each page rendered at 300 dpi and read by Tesseract's own layout analysis
     * (`--psm 3`: column finding by tab stops, rules removed), so two-column pages, dictionaries and
     * indexes read column by column. Tesseract's word boxes (TSV) are kept per page as they land, so a
     * stopped ingest resumes at the next page; the book is then read as page streams — running head,
     * body, notes — from those boxes ([borg.trikeshed.narsese.PageStreams]). Pages run [OCR_PARALLEL] at a time.
     */
    private suspend fun ocrPages(name: String, id: String, f: File): borg.trikeshed.narsese.PageStreams.Book? = coroutineScope {
        val pages = runCatching {
            val proc = tool("pdfinfo", f.absolutePath).start()
            Regex("Pages:[ \\t]+(\\d+)").find(proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() })?.groupValues?.get(1)?.toInt()
        }.getOrNull() ?: return@coroutineScope null
        val dir = File(filesRoot ?: File(System.getProperty("java.io.tmpdir")), ".ocr/$name/${id.replace('/', '_')}").apply { mkdirs() }
        val gate = pageGate
        val key = "$name/$id"
        ocrDone[key] = dir.listFiles { x -> x.name.endsWith(".tsv") }?.size ?: 0
        (1..pages).map { pg ->
            async(Dispatchers.IO) {
                val base = File(dir, "%05d".format(pg))
                val out = File(base.path + ".tsv")
                if (out.isFile) return@async
                gate.acquire()
                try {
                    val png = File(dir, "p$pg")
                    tool("pdftoppm", "-f", "$pg", "-l", "$pg", "-r", "300", "-gray", "-png", "-singlefile", f.absolutePath, png.absolutePath)
                        .redirectErrorStream(true).start().also { it.inputStream.readAllBytes(); it.waitFor() }
                    val img = File(png.absolutePath + ".png")
                    // One read, two renderings: the page text and its word boxes.
                    val part = File(dir, "part-$pg")
                    val proc = tool("tesseract", img.absolutePath, part.absolutePath, "--psm", "3", "txt", "tsv").redirectErrorStream(true).start()
                    proc.inputStream.readAllBytes(); proc.waitFor()
                    img.delete()
                    File(part.path + ".txt").renameTo(File(base.path + ".txt"))
                    File(part.path + ".tsv").renameTo(out)
                    val n = ocrDone.merge(key, 1, Int::plus) ?: 0
                    if (n % 25 == 0) System.err.println("[OROBOROS] OCR $key: $n/$pages pages")
                } finally { gate.release() }
            }
        }.awaitAll()
        val read = (1..pages).map { pg -> borg.trikeshed.narsese.PageStreams.page(pg, File(dir, "%05d.tsv".format(pg)).takeIf { it.isFile }?.readText().orEmpty()) }
        borg.trikeshed.narsese.PageStreams.book(read)
    }

    /** Ingest one document, by hand: its text lands as the extract twin. The same reading [mine] does. */
    suspend fun ingest(name: String, id: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        // The uploaded file itself when it is on disk; the attachment bytes are read only when it is not.
        val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
        val src = onDisk ?: run {
            val att = pdb.gateway.getAttachment(id) ?: return@withContext mapOf("error" to "absent", "id" to id)
            File.createTempFile("mine-", "-" + id.substringAfterLast('/')).apply { writeBytes(att.second); deleteOnExit() }
        }
        val began = System.currentTimeMillis()
        val pdf = id.lowercase().endsWith(".pdf")
        val md = if (pdf) pdfText(name, id, src)?.let { "# ${src.name}\n\n$it\n" }
            else runCatching { JvmTikaIngestAdapter.extractToMarkdown(src.toPath()) }.getOrNull()
        if (onDisk == null) src.delete()
        val body = md?.trim().orEmpty()
        if (body.length < 80) return@withContext mapOf("verdict" to "no_text", "id" to id)
        putTwin(name, "$id.extract.md", body.encodeToByteArray(), "project-miner", "ingested")
        mapOf("verdict" to "ingested", "id" to id, "chars" to body.length,
            "route" to if (pdf) "pdf pages (ocr, column order)" else "tika",
            "ms" to (System.currentTimeMillis() - began))
    }

    suspend fun mine(name: String, cap: Int = 1000): Progress {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val existing = runs[name]
        if (existing != null && !existing.done) return existing
        val prog = Progress()
        runs[name] = prog
        val kind = scopes.list().firstOrNull { it.name == name }?.kind ?: pdb.kind

        val storeIds = pdb.store.ids()
        val ids = mutableListOf<String>()
        val already = mutableSetOf<String>()
        for (i in 0 until storeIds.a) {
            val id = storeIds.b(i)
            if (id.endsWith(".extract.md")) {
                already.add(id)
            } else if (id.endsWith(borg.trikeshed.lcnc.ProjectNodes.NOTES_SUFFIX) || id.endsWith(FOOTNOTES_SUFFIX)) {
                continue
            } else if (id.substringAfterLast('.', "").lowercase() in MINEABLE) {
                ids.add(id)
            }
        }

        val work = ids.filter { "$it.extract.md" !in already }.take(cap)
        prog.total = work.size

        withContext(Dispatchers.IO) {
            for (id in work) {
                try {
                    val att = pdb.gateway.getAttachment(id)
                    if (att == null) { prog.skipped++; continue }
                    // Every PDF page is OCR'd (no upstream text layer trusted); Tika is for non-PDF documents.
                    // Prefer the on-disk twin (clone/mirror) — no byte copy for the parser.
                    val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
                    val src = onDisk ?: File.createTempFile("mine-", "-" + id.substringAfterLast('/')).apply {
                        writeBytes(att.second); deleteOnExit()
                    }
                    val md = if (id.lowercase().endsWith(".pdf")) pdfText(name, id, src)?.let { "# ${src.name}\n\n$it\n" }
                        else runCatching { JvmTikaIngestAdapter.extractToMarkdown(src.toPath()) }.getOrNull()
                    if (onDisk == null) src.delete()
                    val body = md?.trim().orEmpty()
                    if (body.length < 80) { prog.failed++; continue }   // no text worth landing
                    putTwin(name, "$id.extract.md", body.encodeToByteArray(), "project-miner", "mined")
                    prog.extracted++
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
