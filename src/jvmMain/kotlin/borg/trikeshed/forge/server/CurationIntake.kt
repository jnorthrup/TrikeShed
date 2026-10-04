package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncTrail
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.narsese.ConstellationNodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Every document an upload lands is read and curated with no one asking: extracted by the miner
 * (each PDF page OCR'd, columns found by the gutter scan), its notes twin read for conventions, then
 * `book.curate` into the constellation named after its project and `constellation.join`, and the
 * project's brief republished when its last document is in. Projects dropped together run together:
 * one queue, [DOCUMENTS] documents extracting at a time across all of them, OCR pages shared through
 * the miner's one page budget. Reading into constellations is one at a time ([reading]): the parser is
 * one process, and a constellation joins one book at a time. A document already curated into its constellation is not read again, so a
 * restart or a re-drop costs nothing twice; a failure is logged against its document and the rest go on.
 */
class CurationIntake(
    private val corpus: JvmProjectCorpus,
    private val runners: () -> Map<String, LcncNodeRunner>,
    private val constellations: () -> ConstellationNodes?,
    private val scope: CoroutineScope,
) {
    data class Doc(val project: String, val id: String)

    /** Per project: documents queued, curated, failed, and the last failure. */
    class Tally {
        val queued = java.util.concurrent.atomic.AtomicInteger()
        val curated = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var note = ""
        val pending get() = queued.get() - curated.get() - failed.get()
        fun toMap() = mapOf("queued" to queued.get(), "curated" to curated.get(), "failed" to failed.get(), "pending" to pending, "note" to note)
    }

    private val queue = Channel<Doc>(Channel.UNLIMITED)
    private val inFlight = ConcurrentHashMap.newKeySet<Doc>()
    private val tallies = ConcurrentHashMap<String, Tally>()
    private val gate = Semaphore(DOCUMENTS)
    private val reading = kotlinx.coroutines.sync.Mutex()

    init {
        scope.launch { for (d in queue) launch { read(d) } }
    }

    fun status(): Map<String, Any?> = tallies.toSortedMap().mapValues { it.value.toMap() }

    /** Every document of every mounted project: what a boot resumes. Curated documents pass through at once. */
    suspend fun resume() {
        for (p in corpus.projects()) for (d in corpus.docs(p.name, limit = Int.MAX_VALUE)) offer(p.name, d.id)
    }

    /** A document removed from its upload: a queued reading of it is dropped, not failed. */
    fun forget(project: String, id: String) {
        val d = Doc(project, id)
        if (inFlight.contains(d)) removed.add(d)
    }
    private val removed = ConcurrentHashMap.newKeySet<Doc>()

    /** Queue one landed document. PDFs and the miner's other formats curate; twins and the rest do not. */
    fun offer(project: String, id: String) {
        if (id.endsWith(ProjectNodes.EXTRACT_SUFFIX) || id.endsWith(ProjectNodes.NOTES_SUFFIX) || id.endsWith(ProjectMiner.FOOTNOTES_SUFFIX)) return
        if (id.substringAfterLast('.', "").lowercase() !in ProjectMiner.MINEABLE) return
        val d = Doc(project, id)
        if (!inFlight.add(d)) return
        tallies.getOrPut(project) { Tally() }.queued.incrementAndGet()
        queue.trySend(d)
    }

    private suspend fun read(d: Doc) {
        val t = tallies.getValue(d.project)
        val constellation = d.project
        if (removed.remove(d)) { inFlight.remove(d); t.queued.decrementAndGet(); return }
        try {
            val nodes = constellations()
            val r = runners()
            fun runner(type: String) = r[type] ?: error("no runner '$type'")
            // The permit covers extraction only: a document waiting to be read into its constellation holds no OCR slot.
            val text = gate.withPermit { corpus.extract(d.project, d.id, Int.MAX_VALUE / 4)?.text }
                ?: error("no text read from ${d.id}")
            // Held only when curated from this very text: a re-read source curates again by itself.
            if (nodes != null && nodes.holds(constellation, d.id, borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value)) { t.curated.incrementAndGet(); return }
            val notes = corpus.read(d.project, d.id + ProjectNodes.NOTES_SUFFIX, 262_144)?.text
            val doc = mapOf("project" to d.project, "id" to d.id)
            reading.withLock {
            LcncTrail.live.run("intake/${d.project}")
            val curated = runner(ConstellationNodes.CURATE).run(
                LcncNode("curate", ConstellationNodes.CURATE, params = mapOf("constellation" to constellation)),
                buildMap {
                    put("text", text); put("doc", doc)
                    notes?.let { put("conventions", ProjectNodes.conventions(it)); put("notes", ProjectNodes.prose(it)) }
                },
            )
            val book = curated["book"] ?: error("book.curate returned no book")
            runner(ConstellationNodes.JOIN).run(
                LcncNode("join", ConstellationNodes.JOIN, params = mapOf("constellation" to constellation)), mapOf("book" to book))
            t.curated.incrementAndGet()
            System.err.println("[INTAKE] ${d.project}/${d.id}: ${curated["statements"]} statements from ${curated["sections"]} sections")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            t.failed.incrementAndGet(); t.note = "${d.id}: ${e.message ?: e}"
            System.err.println("[INTAKE] ${d.project}/${d.id} FAILED: ${e.message ?: e}")
        } finally {
            inFlight.remove(d)
        }
        if (t.pending == 0) runCatching {
            reading.withLock { runners()[ConstellationNodes.BRIEF]?.run(
                LcncNode("brief", ConstellationNodes.BRIEF, params = mapOf("constellation" to constellation, "page" to "true")), emptyMap()) }
            System.err.println("[INTAKE] ${d.project}: ${t.curated} curated, ${t.failed} failed — brief published")
        }.onFailure { System.err.println("[INTAKE] ${d.project}: brief failed: ${it.message}") }
    }

    companion object {
        /**
         * Uploads curate themselves: every landed document is extracted and read into its project's
         * constellation; a boot resumes whatever the last run left unread. `GET /api/intake` reports.
         */
        fun install(nodes: ConstellationNodes, ctx: borg.trikeshed.module.ModuleContext, corpus: JvmProjectCorpus,
                    scopes: ProjectScopes, scope: CoroutineScope): CurationIntake {
            val intake = CurationIntake(corpus, { ctx.lcncRunners }, { nodes }, scope)
            scopes.onDocument = { project, id -> intake.offer(project, id) }
            scopes.onRemoved = { project, id -> intake.forget(project, id); nodes.leave(project, id) }
            scope.launch { runCatching { intake.resume() }.onFailure { System.err.println("[INTAKE] resume failed: ${it.message}") } }
            ctx.routes.claim("language", "/api/intake") { method, _, _, _ ->
                if (method != "GET") borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                else borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, borg.trikeshed.parse.jsonOf(intake.status()))
            }
            return intake
        }

        /** Documents extracting at once across all projects; their OCR pages share the miner's page budget. */
        const val DOCUMENTS = 3
    }
}
