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

    fun progress(name: String): Progress? = runs[name]

    companion object {
        /** Pages read per poppler call: bounds memory, not what gets read. */
        const val PAGE_BATCH = 50
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
     * The PDF's text layer in reading order: poppler's word boxes, [PageColumns] per page so a
     * multi-column page (a dictionary, any index) reads column by column, never across. Pages go in
     * batches, so a book of any length is never held whole. Null when poppler is absent or reads nothing.
     */
    private fun textLayerOf(f: File): String? = runCatching {
        fun run(vararg cmd: String): String {
            val pb = ProcessBuilder(*cmd).redirectErrorStream(false); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
            val text = proc.inputStream.readAllBytes().decodeToString(); proc.errorStream.readAllBytes()
            check(proc.waitFor() == 0) { "${cmd[0]} failed" }
            return text
        }
        val pages = Regex("Pages:\\s+(\\d+)").find(run("pdfinfo", f.absolutePath))?.groupValues?.get(1)?.toInt() ?: return@runCatching null
        val out = StringBuilder()
        var first = 1
        while (first <= pages) {
            val last = minOf(pages, first + PAGE_BATCH - 1)
            out.append(borg.trikeshed.narsese.PageColumns.readBbox(run("pdftotext", "-bbox", "-enc", "UTF-8", "-f", "$first", "-l", "$last", f.absolutePath, "-")))
                .append("\n\n")
            first = last + 1
        }
        out.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * A PDF's text, page by page from the source each page has: when every page carries a text layer it
     * is read whole ([textLayerOf]); otherwise the textless pages go to OCR and the rest keep their layer,
     * so a scan whose cover alone has text (a Google Books copy) is read in full.
     */
    private suspend fun pdfText(name: String, id: String, f: File): String? {
        val (count, layered) = layeredPages(f) ?: return null
        return if (layered.size == count && count > 0) textLayerOf(f) else ocrPages(name, id, f, layered)
    }

    /** Page count and the pages that carry a text layer, read by poppler; null when poppler is absent. */
    private fun layeredPages(f: File): Pair<Int, Set<Int>>? = runCatching {
        val pb = ProcessBuilder("pdftotext", "-enc", "UTF-8", f.absolutePath, "-"); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
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
                val pb = ProcessBuilder("pdfinfo", onDisk.absolutePath); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
                proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() }
            }.getOrDefault("")
            fun field(k: String) = Regex("(?m)^$k:\\s+(.+)$").find(info)?.groupValues?.get(1)?.trim()
            val pages = field("Pages")?.toIntOrNull()
            val layered = layeredPages(onDisk)?.second?.size ?: 0
            val text = pages != null && layered == pages
            out += mapOf("pages" to pages, "title" to field("Title"), "author" to field("Author"), "producer" to field("Producer"),
                "textLayer" to text, "layeredPages" to layered,
                "route" to if (text) "text layer (poppler, column order)" else "OCR per page (Tesseract, column order) where no text layer",
                "ocrPagesDone" to ocrDone["$name/$id"])
            // Layout: columns found on a spread of sample pages (front, middle, back — where an index lives).
            if (text && pages != null && pages > 0) {
                val sample = listOf(pages / 10, pages / 2, pages - pages / 20).map { it.coerceIn(1, pages) }.distinct()
                out["columns"] = sample.associate { pg ->
                    val html = runCatching {
                        val pb = ProcessBuilder("pdftotext", "-bbox", "-enc", "UTF-8", "-f", "$pg", "-l", "$pg", onDisk.absolutePath, "-"); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
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
     * An image-only PDF read page by page: each page rendered at 300 dpi and read by Tesseract's own
     * layout analysis (`--psm 3`: tab-stop column finding, rules removed), so two-column pages and
     * indexes read column by column. Each page's text is kept in a sidecar as it lands, so a stopped
     * ingest resumes at the next page instead of starting over. Pages run [OCR_PARALLEL] at a time.
     * A page in [layered] carries its own text layer and is read from it (column order), not OCR'd.
     */
    private suspend fun ocrPages(name: String, id: String, f: File, layered: Set<Int> = emptySet()): String? = coroutineScope {
        val pages = runCatching {
            val pb = ProcessBuilder("pdfinfo", f.absolutePath); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
            Regex("Pages:\\s+(\\d+)").find(proc.inputStream.readAllBytes().decodeToString().also { proc.waitFor() })?.groupValues?.get(1)?.toInt()
        }.getOrNull() ?: return@coroutineScope null
        val dir = File(filesRoot ?: File(System.getProperty("java.io.tmpdir")), ".ocr/$name/${id.replace('/', '_')}").apply { mkdirs() }
        val gate = kotlinx.coroutines.sync.Semaphore(OCR_PARALLEL)
        val key = "$name/$id"
        ocrDone[key] = dir.listFiles { x -> x.name.endsWith(".txt") }?.size ?: 0
        (1..pages).map { pg ->
            async(Dispatchers.IO) {
                val out = File(dir, "%05d.txt".format(pg))
                if (out.isFile) return@async
                gate.acquire()
                try {
                    if (pg in layered) {
                        val pb = ProcessBuilder("pdftotext", "-bbox", "-enc", "UTF-8", "-f", "$pg", "-l", "$pg", f.absolutePath, "-"); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
                        val html = proc.inputStream.readAllBytes().decodeToString(); proc.errorStream.readAllBytes(); proc.waitFor()
                        File(out.absolutePath + ".part").apply { writeText(borg.trikeshed.narsese.PageColumns.readBbox(html)) }.renameTo(out)
                        ocrDone.merge(key, 1, Int::plus)
                        return@async
                    }
                    val png = File(dir, "p$pg")
                    ProcessBuilder("pdftoppm", "-f", "$pg", "-l", "$pg", "-r", "300", "-gray", "-png", "-singlefile", f.absolutePath, png.absolutePath).apply { environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) } }
                        .redirectErrorStream(true).start().also { it.inputStream.readAllBytes(); it.waitFor() }
                    val img = File(png.absolutePath + ".png")
                    val pb = ProcessBuilder("tesseract", img.absolutePath, "-", "--psm", "3").redirectErrorStream(false); pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()) }; val proc = pb.start()
                    val text = proc.inputStream.readAllBytes().decodeToString(); proc.errorStream.readAllBytes(); proc.waitFor()
                    img.delete()
                    File(out.absolutePath + ".part").apply { writeText(text) }.renameTo(out)
                    val n = ocrDone.merge(key, 1, Int::plus) ?: 0
                    if (n % 25 == 0) System.err.println("[OROBOROS] OCR $key: $n/$pages pages")
                } finally { gate.release() }
            }
        }.awaitAll()
        val text = (1..pages).joinToString("\n\n") { pg -> File(dir, "%05d.txt".format(pg)).takeIf { it.isFile }?.readText().orEmpty() }
        text.takeIf { it.isNotBlank() }
    }

    /** Ingest one document, by hand: its text lands as the extract twin. The same reading [mine] does. */
    suspend fun ingest(name: String, id: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val att = pdb.gateway.getAttachment(id) ?: return@withContext mapOf("error" to "absent", "id" to id)
        val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
        val src = onDisk ?: File.createTempFile("mine-", "-" + id.substringAfterLast('/')).apply { writeBytes(att.second); deleteOnExit() }
        val began = System.currentTimeMillis()
        val pdf = id.lowercase().endsWith(".pdf")
        val md = (if (pdf) pdfText(name, id, src)?.let { "# ${src.name}\n\n$it\n" } else null)
            ?: runCatching { JvmTikaIngestAdapter.extractToMarkdown(src.toPath()) }.getOrNull()
        if (onDisk == null) src.delete()
        val body = md?.trim().orEmpty()
        if (body.length < 80) return@withContext mapOf("verdict" to "no_text", "id" to id)
        putTwin(name, "$id.extract.md", body.encodeToByteArray(), "project-miner", "ingested")
        mapOf("verdict" to "ingested", "id" to id, "chars" to body.length,
            "route" to if (pdf) "pdf pages (text layer, else ocr)" else "tika",
            "ms" to (System.currentTimeMillis() - began))
    }

    suspend fun mine(name: String, cap: Int = 1000): Progress {
        val pdb = registry.get(name) ?: throw IllegalArgumentException("no project db '$name'")
        val existing = runs[name]
        if (existing != null && !existing.done) return existing
        val prog = Progress()
        runs[name] = prog
        val kind = scopes.list().firstOrNull { it.name == name }?.kind ?: pdb.kind

        // ⚡ Bolt: Use store.ids() instead of store.all() to avoid materializing full Document instances in memory.
        val storeIds = pdb.store.ids()
        val ids = mutableListOf<String>()
        val already = mutableSetOf<String>()
        for (i in 0 until storeIds.a) {
            val id = storeIds.b(i)
            if (id.endsWith(".extract.md")) {
                already.add(id)
            } else if (id.endsWith(borg.trikeshed.lcnc.ProjectNodes.NOTES_SUFFIX)) {
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
                    // Each PDF page is read from its own text layer when it has one, else OCR'd;
                    // Tika is for non-PDF documents. No size decides whether a document is read.
                    // Prefer the on-disk twin (clone/mirror) — no byte copy for the parser.
                    val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
                    val src = onDisk ?: File.createTempFile("mine-", "-" + id.substringAfterLast('/')).apply {
                        writeBytes(att.second); deleteOnExit()
                    }
                    val md = (if (id.lowercase().endsWith(".pdf")) pdfText(name, id, src)?.let { "# ${src.name}\n\n$it\n" } else null)
                        ?: runCatching { JvmTikaIngestAdapter.extractToMarkdown(src.toPath()) }.getOrNull()
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
