package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncTrail
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectDoc
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.lcnc.DocumentCurationLegos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * By default every document an upload lands is read and curated with no one asking: extracted by the miner
 * (each PDF page OCR'd, columns found by the gutter scan), its notes twin read for conventions, then
 * `book.curate` into the constellation named after its project and `constellation.join`, and the
 * project's brief republished when its last document is in. Projects dropped together run together:
 * one queue, [DOCUMENTS] documents extracting at a time across all of them, OCR pages shared through
 * the miner's one page budget. Reading into constellations is one at a time ([reading]): the parser is
 * one process, and a constellation joins one book at a time. A document already curated into its constellation is not read again, so a
 * restart or a re-drop costs nothing twice; a failure is logged against its document and the rest go on.
 * The constructor also permits an opt-in review-required admission, which retains unreviewed
 * candidates without joining them. Project policy is loaded from the mount ledger before hooks run;
 * intake observations remain process-local and the policy does not prevent direct joins, queries,
 * restores, or use of existing constellation members.
 */
class CurationIntake(
    private val corpus: ProjectCorpus,
    private val runners: () -> Map<String, LcncNodeRunner>,
    val constellations: () -> ConstellationNodes?,
    private val scope: CoroutineScope,
    private val selectedProvider: (String, String) -> DecisionProvider? = { _, _ -> null },
    private val settled: suspend () -> Unit = { constellations()?.releaseDecision() },
    /** What a scored document's referenced links reach, landed as documents of their own scopes ([LinkGrab]). */
    private val grab: suspend (Doc) -> Unit = {},
    private val retainedSource: (suspend (String, String) -> DocumentSource)? = null,
    /** Per-admission opt-in only. This does not gate direct join, query, or restore paths. */
    private val mode: (String, String) -> Mode = { _, _ -> Mode.Legacy },
    private val score: suspend (DecisionProvider, String) -> Unit = { provider, book ->
        requireNotNull(constellations()) { "Curation is not available" }.forProvider(provider).ingestScore(book)
    },
    /** Host admission policy; distinct from per-document curation mode. */
    private val eligible: (String) -> Boolean = { true },
) {
    sealed interface Mode {
        data object Legacy : Mode
        /** Retain originals; automatic offers and boot resume do not admit readings. */
        data object Manual : Mode
        data class ReviewRequired(val ontologyCid: borg.trikeshed.job.ContentId) : Mode
        /** No intake action may join while persisted project policy cannot be verified. */
        data class Blocked(val reason: String) : Mode
    }

    data class Doc(val project: String, val id: String, val decisionProvider: DecisionProvider? = null,
                   val mode: Mode = Mode.Legacy, val expected: ProjectDoc? = null)

    /** Per project: admissions and intake-time review observations, not current review decisions. */
    class Tally {
        val queued = java.util.concurrent.atomic.AtomicInteger()
        val curated = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val awaitingReview = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var note = ""
        val pending get() = queued.get() - curated.get() - failed.get() - awaitingReview.get()
        fun toMap() = mapOf("queued" to queued.get(), "curated" to curated.get(), "failed" to failed.get(),
            "awaitingReview" to awaitingReview.get(), "pending" to pending, "note" to note)
    }

    private val queue = Channel<Unit>(Channel.CONFLATED)
    // Admission retains identifiers only. Fixed consumers bound decoded documents through join.
    private val pending = ConcurrentLinkedQueue<Doc>()
    private val admission = Any()
    private var admissionVersion = 0L
    private val inFlight = ConcurrentHashMap.newKeySet<Doc>()
    // At most one latest follow-up per document; access only while holding admission.
    private val followUps = HashMap<Pair<String, String>, Doc>()
    private val tallies = ConcurrentHashMap<String, Tally>()
    private val providerTallies = ConcurrentHashMap<Pair<String, DecisionProvider>, Tally>()
    /** Process-local observations at intake time, not live review state or a security gate. */
    private data class Awaiting(val doc: Doc, val details: Map<String, Any?>)
    private val awaiting = ConcurrentHashMap<Long, Awaiting>()
    private val awaitingSequence = java.util.concurrent.atomic.AtomicLong()
    private val reading = kotlinx.coroutines.sync.Mutex()
    // New readings wait if idle resource release is in progress.
    private val settlement = kotlinx.coroutines.sync.Mutex()

    private val worker = scope.launch {
        repeat(DOCUMENTS) {
            launch {
                for (unit in queue) {
                    while (true) {
                        // Coordinate the queued-versus-started decision with re-offer admission.
                        val d = synchronized(admission) { pending.poll() } ?: break
                        if (pending.isNotEmpty()) queue.trySend(Unit)
                        read(d)
                    }
                }
            }
        }
    }

    /** Finish admitted document readings before their sense encoder and stores are closed. */
    suspend fun drain() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        synchronized(admission) { queue.close() }
        worker.join()
        check(tallies.values.all { it.pending == 0 }) { "Document curation stopped with unfinished admitted work" }
    }

    fun status(): Map<String, Any?> = tallies.toSortedMap().mapValues { (project, tally) ->
        tally.toMap() + mapOf("providers" to providerTallies.entries.filter { it.key.first == project }
            .associate { it.key.second.id to (it.value.toMap() + ("decisionProvider" to it.key.second.id)) },
            "reviewStatusAsOf" to "intake",
            "awaitingReviews" to awaiting.entries.filter { it.value.doc.project == project }.sortedBy { it.key }.map { it.value.details })
    }

    /** Every document of every mounted project: what a boot resumes. Curated documents pass through at once. */
    suspend fun resume() {
        for (p in corpus.projects()) {
            if (!eligible(p.name)) continue
            for (d in corpus.docs(p.name, limit = Int.MAX_VALUE)) offer(p.name, d.id)
        }
    }

    /** A document removed from its upload: a queued reading of it is dropped, not failed. */
    fun forget(project: String, id: String) {
        synchronized(admission) {
            removed.addAll(inFlight.filter { it.project == project && it.id == id })
            followUps.remove(project to id)?.let { uncountQueued(it) }
            clearObservations(project, id)
        }
    }
    private val removed = ConcurrentHashMap.newKeySet<Doc>()

    /** Called under admission: discard superseded observations and their admission counts. */
    private fun clearObservations(project: String, id: String) {
        val entries = awaiting.entries.filter { it.value.doc.project == project && it.value.doc.id == id }
        entries.forEach { awaiting.remove(it.key) }
        tallies[project]?.let { tally ->
            tally.queued.addAndGet(-entries.size)
            tally.awaitingReview.addAndGet(-entries.size)
        }
    }

    private fun countQueued(d: Doc) {
        admissionVersion++
        tallies.computeIfAbsent(d.project) { Tally() }.queued.incrementAndGet()
        d.decisionProvider?.let { providerTallies.computeIfAbsent(d.project to it) { Tally() }.queued.incrementAndGet() }
    }

    private fun uncountQueued(d: Doc) {
        tallies.getValue(d.project).queued.decrementAndGet()
        d.decisionProvider?.let { providerTallies.getValue(d.project to it).queued.decrementAndGet() }
    }

    /** Queue one landed document. PDFs and the miner's other formats curate; twins and the rest do not. */
    fun offer(project: String, id: String) {
        if (!eligible(project)) return
        offer(project, id, selectedProvider(project, id))
    }

    fun offer(project: String, id: String, decisionProvider: DecisionProvider?) = offer(project, id, decisionProvider, false)

    /** Manual policy disables automatic intake; this explicit document action retains legacy curation. */
    fun offerSelected(project: String, id: String, decisionProvider: DecisionProvider?) = offer(project, id, decisionProvider, true)

    private fun offer(project: String, id: String, decisionProvider: DecisionProvider?, selected: Boolean) {
        if (!eligible(project)) return
        if (ProjectNodes.twin(id)) return
        if (id.substringAfterLast('.', "").lowercase() !in ProjectMiner.MINEABLE) return
        val selectedMode = mode(project, id)
        if (selectedMode == Mode.Manual && !selected) return
        enqueue(project, id, decisionProvider, if (selectedMode == Mode.Manual) Mode.Legacy else selectedMode)
    }

    /** An operator selects this particular text document; it is never admitted by mount, upload or resume. */
    fun offerTextForReview(selected: ProjectDoc) {
        val project = selected.project
        val id = selected.id
        require(eligible(project)) { "Project is not eligible for curation intake: $project" }
        require(explicitText(id) && !ProjectNodes.twin(id)) { "Document format is not supported for text review: $id" }
        require(selected.cid.isNotBlank() && selected.rev.isNotBlank() && selected.seq >= 0) { "Text selection requires a complete revision" }
        val selectedMode = mode(project, id)
        require(selectedMode is Mode.ReviewRequired) { "Text admission requires review-required intake" }
        enqueue(project, id, null, selectedMode, selected)
    }

    private fun enqueue(project: String, id: String, decisionProvider: DecisionProvider?, selectedMode: Mode, expected: ProjectDoc? = null) {
        val d = Doc(project, id, if (selectedMode != Mode.Legacy) null else decisionProvider, selectedMode, expected)
        synchronized(admission) {
            check(!queue.isClosedForSend) { "Document curation intake is draining" }
            val sameDocument = inFlight.filter { it.project == project && it.id == id }
            val key = project to id
            // Automatic admissions read the latest upload; explicit text retains its selected revision.
            if (d in sameDocument && pending.contains(d) && d !in removed && !followUps.containsKey(key)) return
            if (sameDocument.isNotEmpty() && (d in sameDocument || sameDocument.any { it in removed || it.mode != Mode.Legacy } ||
                    d.mode != Mode.Legacy || followUps.containsKey(key))) {
                followUps.put(key, d)?.let { uncountQueued(it) }
                clearObservations(project, id)
                countQueued(d)
                return
            }
            if (!inFlight.add(d)) return
            clearObservations(project, id)
            countQueued(d)
            pending.add(d)
            queue.trySend(Unit).getOrThrow()
        }
    }

    private suspend fun read(d: Doc) {
        val t = tallies.getValue(d.project)
        val providerTally = d.decisionProvider?.let { providerTallies.getValue(d.project to it) }
        val constellation = d.project
        var r = emptyMap<String, LcncNodeRunner>()
        val params = mapOf("constellation" to constellation) +
            (d.decisionProvider?.let { mapOf("decisionProvider" to it.id) } ?: emptyMap())
        try {
            settlement.withLock { }
            if (removed.contains(d) || !eligible(d.project)) {
                t.queued.decrementAndGet(); providerTally?.queued?.decrementAndGet()
                return
            }
            if (d.mode is Mode.Blocked) error("Intake policy blocked: ${d.mode.reason}")
            val root = constellations()
            val nodes = d.decisionProvider?.let { root?.ingestAccess(it) } ?: root
            r = if (d.decisionProvider != null && nodes != null)
                LinkedHashMap<String, LcncNodeRunner>().also { nodes.register(it) } else runners()
            fun runner(type: String) = r[type] ?: error("no runner '$type'")
            if (d.mode is Mode.ReviewRequired) {
                val sourceReader = requireNotNull(retainedSource) { "Review-required intake needs a retained source" }
                d.expected?.let { expected ->
                    val current = requireNotNull(corpus.document(d.project, d.id)) { "Selected document is unavailable" }
                    require(current.cid == expected.cid && current.rev == expected.rev && current.seq == expected.seq) {
                        "Selected document revision changed before curation"
                    }
                }
                val source = sourceReader(d.project, d.id)
                d.expected?.let { expected ->
                    val revision = requireNotNull(source.project) { "Selected document source lacks a project revision" }
                    require(revision.project == expected.project && revision.id == expected.id && revision.cid == expected.cid &&
                        revision.rev == expected.rev && revision.seq == expected.seq && source.originalCid.value == expected.cid) {
                        "Retained source differs from selected document revision"
                    }
                }
                // Names include the project, document, source revision, and complete ontology identity.
                val identity = borg.trikeshed.job.ContentId.of(borg.trikeshed.job.CanonicalCbor.encodeMap(mapOf(
                    "project" to d.project, "document" to d.id, "originalCid" to source.originalCid.value,
                    "extractedTextCid" to source.extractedTextCid.value, "ontologyCid" to d.mode.ontologyCid.value)))
                val bookBase = "intake-review-${identity.hex}"
                val bookName = nodes?.curationBookName(bookBase, d.mode.ontologyCid)
                    ?: ConstellationNodes.curationBookName(bookBase, d.mode.ontologyCid)
                val curated = reading.withLock {
                    if (removed.contains(d)) return@withLock null
                    LcncTrail.live.run("intake/${d.project}")
                    // This preflight avoids known members; direct external joins do not share this lock.
                    check(root?.constellationOf(bookBase) == null && root?.constellationOf(bookName) == null) {
                        "Review intake would overwrite a joined book"
                    }
                    runner(ConstellationNodes.CURATE).run(
                        LcncNode("curate", ConstellationNodes.CURATE, params = mapOf("book" to bookBase)),
                        mapOf("source" to DocumentCuratorCodec.source(source), "ontologyCid" to d.mode.ontologyCid.value),
                    )
                }
                if (curated == null) {
                    t.queued.decrementAndGet()
                    return
                }
                require(curated["reviewStatus"] == "unreviewed") { "Review-required curation must return unreviewed candidates" }
                require(curated["ontologyCid"] == d.mode.ontologyCid.value) { "book.curate returned a different ontology" }
                val book = (curated["book"] as? String)?.takeIf { it.isNotBlank() } ?: error("book.curate returned no book")
                require(book == bookName) { "book.curate returned a different book" }
                val receipt = borg.trikeshed.job.ContentId(curated["curationReceiptCid"] as? String ?: error("book.curate returned no receipt"))
                synchronized(admission) {
                    if (removed.contains(d) || followUps.containsKey(d.project to d.id)) t.queued.decrementAndGet()
                    else {
                        awaiting[awaitingSequence.incrementAndGet()] = Awaiting(d, mapOf("document" to d.id, "book" to book,
                            "curationReceiptCid" to receipt.value, "ontologyCid" to d.mode.ontologyCid.value,
                            "reviewStatus" to "unreviewed", "reviewStatusObservedAt" to "intake"))
                        t.awaitingReview.incrementAndGet()
                    }
                }
                return
            }
            val source = retainedSource?.invoke(d.project, d.id)
            val text = source?.text ?: corpus.extract(d.project, d.id, Int.MAX_VALUE / 4)?.text
                ?: error("no text read from ${d.id}")
            // Held only when curated from this very text: a re-read source curates again by itself.
            if (nodes != null && nodes.holds(constellation, d.id, borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value)) {
                d.decisionProvider?.let { provider -> reading.withLock {
                    score(provider, d.id)
                    runner(ConstellationNodes.JOIN).run(
                        LcncNode("join", ConstellationNodes.JOIN, params = params), mapOf("book" to d.id))
                } }
                t.curated.incrementAndGet(); providerTally?.curated?.incrementAndGet()
                if (d.decisionProvider != null) grabbing(d)
                return
            }
            val notes = corpus.read(d.project, d.id + ProjectNodes.NOTES_SUFFIX, 262_144)?.text
            val doc = mapOf("project" to d.project, "id" to d.id) + (source?.let { retained ->
                retained.project?.toMap().orEmpty() + mapOf("originalCid" to retained.originalCid.value,
                    "extractedTextCid" to retained.extractedTextCid.value)
            } ?: emptyMap())
            reading.withLock {
            LcncTrail.live.run("intake/${d.project}")
            val curated = runner(ConstellationNodes.CURATE).run(
                LcncNode("curate", ConstellationNodes.CURATE, params = params),
                buildMap {
                    put("text", text); put("doc", doc)
                    source?.let { put("source", DocumentCuratorCodec.source(it)) }
                    notes?.let { put("conventions", ProjectNodes.conventions(it)); put("notes", ProjectNodes.prose(it)) }
                },
            )
            val book = curated["book"] ?: error("book.curate returned no book")
            runner(ConstellationNodes.JOIN).run(
                LcncNode("join", ConstellationNodes.JOIN, params = params), mapOf("book" to book))
            d.decisionProvider?.let {
                score(it, book.toString())
                runner(ConstellationNodes.JOIN).run(
                    LcncNode("join", ConstellationNodes.JOIN, params = params), mapOf("book" to book))
            }
            t.curated.incrementAndGet(); providerTally?.curated?.incrementAndGet()
            System.err.println("[INTAKE] ${d.project}/${d.id}: ${curated["statements"]} statements from ${curated["sections"]} sections")
            }
            if (d.decisionProvider != null) grabbing(d)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            synchronized(admission) {
                if (d.mode != Mode.Legacy && (removed.contains(d) || followUps.containsKey(d.project to d.id))) t.queued.decrementAndGet()
                else {
                    t.failed.incrementAndGet(); t.note = "${d.id}: ${e.message ?: e}"
                    providerTally?.let { it.failed.incrementAndGet(); it.note = t.note }
                    System.err.println("[INTAKE] ${d.project}/${d.id} FAILED: ${e.message ?: e}")
                }
            }
        } finally {
            finish(d)
        }
        if (d.mode is Mode.Legacy && t.pending == 0) runCatching {
            reading.withLock { r[ConstellationNodes.BRIEF]?.run(
                LcncNode("brief", ConstellationNodes.BRIEF, params = params + ("page" to "true")), emptyMap()) }
            System.err.println("[INTAKE] ${d.project}: ${t.curated} curated, ${t.failed} failed — brief published")
        }.onFailure { System.err.println("[INTAKE] ${d.project}: brief failed: ${it.message}") }
    }

    /** A scored document's links are fetched beside the reading lane, never holding it. */
    private fun grabbing(d: Doc) {
        scope.launch { runCatching { grab(d) }.onFailure { System.err.println("[GRAB] ${d.project}/${d.id} failed: ${it.message ?: it}") } }
    }

    private suspend fun finish(d: Doc) {
        val idleVersion = synchronized(admission) {
            removed.remove(d)
            inFlight.remove(d)
            val key = d.project to d.id
            if (inFlight.none { it.project == d.project && it.id == d.id }) {
                followUps.remove(key)?.let { next ->
                    check(inFlight.add(next)) { "Follow-up document is already in flight" }
                    pending.add(next)
                    // A draining worker is already active and will poll pending after this read.
                    if (!queue.isClosedForSend) queue.trySend(Unit).getOrThrow()
                }
            }
            if (inFlight.isEmpty()) admissionVersion else null
        }
        if (idleVersion != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            settlement.withLock {
                if (synchronized(admission) { inFlight.isEmpty() && admissionVersion == idleVersion }) {
                    runCatching { settled() }
                        .onFailure { System.err.println("[INTAKE] inference release failed: ${it.message}") }
                }
            }
        }
    }

    companion object {
        /** Limited to document-like text; code and data require their own explicit curation paths. */
        fun explicitText(id: String): Boolean = id.substringAfterLast('.', "").lowercase() in
            setOf("txt", "text", "md", "markdown")

        /**
         * Uploads curate themselves: every landed document is extracted and read into its project's
         * constellation; a boot resumes whatever the last run left unread. `GET /api/intake` reports.
         */
        fun install(nodes: ConstellationNodes, ctx: borg.trikeshed.module.ModuleContext, corpus: JvmProjectCorpus,
                    scopes: ProjectScopes, scope: CoroutineScope): CurationIntake {
            val links = LinkGrab(nodes, corpus, scopes, ctx.muxContext)
            val intake = CurationIntake(corpus, { ctx.lcncRunners }, { nodes }, scope, scopes::decisionProvider,
                grab = { d -> links.grab(d.project, d.id, d.decisionProvider) },
                retainedSource = { project, id ->
                    DocumentCurationLegos.projectSource(ctx.casStore, corpus,
                        requireNotNull(corpus.document(project, id)) { "Source document is unavailable" })
                }, mode = { project, _ -> scopes.intakeMode(project) }, eligible = corpus::intakeEligible)
            scopes.onDocument = { project, id, provider -> intake.offer(project, id, provider) }
            scopes.onExplicitDocument = { project, id, provider -> intake.offerSelected(project, id, provider) }
            scopes.onTextForReview = { selected -> intake.offerTextForReview(selected) }
            scopes.onRemoved = { project, id -> intake.forget(project, id); nodes.leave(project, id) }
            scope.launch { runCatching { intake.resume() }.onFailure { System.err.println("[INTAKE] resume failed: ${it.message}") } }
            ctx.routes.claim("language", "/api/intake") { method, _, _, _ ->
                if (method != "GET") borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                else borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, borg.trikeshed.parse.jsonOf(intake.status()))
            }
            // A dragged link roots a group: POST {url, decisionProvider?} → {verdict, name, id}.
            ctx.routes.claim("language", "/api/projects/link") { method, _, text, _ ->
                if (method != "POST") return@claim borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
                val q = runCatching { borg.trikeshed.parse.reifyMap(text.substringAfter("\r\n\r\n", text)) }.getOrDefault(emptyMap())
                val url = q["url"]?.toString()?.trim().orEmpty()
                runCatching {
                    require(q["requirePdf"] == null || q["requirePdf"] is Boolean) { "requirePdf must be a boolean" }
                    links.root(url, q["decisionProvider"]?.toString()?.takeIf { it.isNotBlank() }?.let(DecisionProvider::of), q["requirePdf"] == true)
                }.fold(
                    onSuccess = { (name, id) -> borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200,
                        borg.trikeshed.parse.jsonOf(mapOf("verdict" to "ok", "name" to name, "id" to id))) },
                    onFailure = { borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(400,
                        borg.trikeshed.parse.jsonOf(mapOf("verdict" to "refused", "detail" to (it.message ?: it.toString())))) },
                )
            }
            return intake
        }

        /** Documents retained from extraction through join; OCR pages share the miner's page budget. */
        const val DOCUMENTS = 3
    }
}
