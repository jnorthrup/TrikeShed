package borg.trikeshed.forge.server

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.JvmTikaIngestAdapter
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.util.io.ContentTypes
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Extracted text also mints epistemic signals into the belief bag (AngularCodec
 * coords keyed by `<db>/<path>`), so mined piles join resonance immediately.
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
        /** Formats Tika earns its keep on. Plain text/markdown mints at mount already. */
        val MINEABLE = setOf(
            "pdf", "docx", "doc", "rtf", "odt", "pptx", "ppt", "xlsx", "epub",
            "png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp",
        )
        const val MAX_BYTES = 50L * 1024 * 1024   // OCR on a 250MB video-sized blob is not mining, it's arson
        const val MINT_PER_DOC = 8
        const val MINT_PER_RUN = 2000
    }

    /** Detached mining pass. One run per db at a time; re-runs skip docs already extracted. */
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
            } else if (id.substringAfterLast('.', "").lowercase() in MINEABLE) {
                ids.add(id)
            }
        }

        val work = ids.filter { "$it.extract.md" !in already }.take(cap)
        prog.total = work.size
        var mintedRun = 0

        withContext(Dispatchers.IO) {
            for (id in work) {
                try {
                    val att = pdb.gateway.getAttachment(id)
                    if (att == null || att.first.length > MAX_BYTES) { prog.skipped++; continue }
                    // Prefer the on-disk twin (clone/mirror) — no byte copy for the parser.
                    val onDisk = filesRoot?.let { File(File(it, name), id) }?.takeIf { it.isFile }
                    val src = onDisk ?: File.createTempFile("mine-", "-" + id.substringAfterLast('/')).apply {
                        writeBytes(att.second); deleteOnExit()
                    }
                    val md = runCatching { JvmTikaIngestAdapter.extractToMarkdown(src.toPath()) }.getOrNull()
                    if (onDisk == null) src.delete()
                    val body = md?.trim().orEmpty()
                    if (body.length < 80) { prog.failed++; continue }   // no text worth landing
                    val bytes = body.encodeToByteArray()
                    val extractId = "$id.extract.md"
                    if (kind == "upload") {
                        scopes.uploadPut(name, extractId, bytes)
                    } else {
                        // dir-backed: the clone dir is the durable source — write there too
                        filesRoot?.let { fr ->
                            runCatching { File(File(fr, name), extractId).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
                        }
                        pdb.gateway.putAttachment(
                            OroborosAttachmentRef(
                                path = extractId,
                                contentType = ContentTypes.forPath(extractId),
                                length = bytes.size.toLong(),
                                contentId = ContentId.of(bytes),
                                agentId = "project-miner",
                                revision = "mined",
                                sequence = System.currentTimeMillis(),
                            ),
                            bytes,
                        )
                    }
                    prog.extracted++

                    val bag = beliefBag
                    if (bag != null && mintedRun < MINT_PER_RUN) {
                        val surface = runCatching {
                            borg.trikeshed.cas.ContentEpistemicIngest.ingest(casStore, body)
                        }.getOrNull()
                        if (surface != null) {
                            var perDoc = 0
                            for (si in 0 until surface.signals.size) {
                                if (perDoc >= MINT_PER_DOC || mintedRun >= MINT_PER_RUN) break
                                val s = surface.signals[si]
                                bag.intake.send(
                                    BeliefIntake.Mint(
                                        s.copy(
                                            angular = AngularCodec.encode(
                                                relation = s.relation,
                                                taxonomyKey = "$name/$id",
                                                subjectTerm = id.substringAfterLast('/'),
                                                objectTerm = s.objectCid?.take(12),
                                            ),
                                        ),
                                        BudgetCoord(0.5f, 0.35f, 0.5f),
                                        gloss = borg.trikeshed.cas.epistemicGloss(surface, s, id.substringAfterLast('/'), body),
                                    ),
                                )
                                perDoc++; mintedRun++; prog.minted++
                            }
                        }
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
