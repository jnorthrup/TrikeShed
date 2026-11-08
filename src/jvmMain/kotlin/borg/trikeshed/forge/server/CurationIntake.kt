package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncTrail
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectDoc
import borg.trikeshed.lcnc.ProjectVisitUnavailable
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.lcnc.DocumentCurationLegos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
    // Input channels carry only document identities. Duplicate and replacement offers
    // occupy conflated per-document mailboxes instead of accumulating notifications.
    private val ingress = Channel<Doc>(Channel.UNLIMITED)
    private val links = Channel<Doc>(Channel.UNLIMITED)
    private val ingressDone = Job()
    private val linksDone = Job()
    init {
        ingress.invokeOnClose { ingressDone.complete() }
        links.invokeOnClose { linksDone.complete() }
    }
    private class Mail<T>(ingressDone: Job, linksDone: Job) {
        val ingress = Channel<T>(Channel.CONFLATED)
        val links = Channel<T>(Channel.CONFLATED)
        private val ingressHandle = ingressDone.invokeOnCompletion { ingress.close() }
        private val linksHandle = linksDone.invokeOnCompletion { links.close() }
        fun close() {
            ingressHandle.dispose(); linksHandle.dispose()
            ingress.close(); links.close()
        }
    }
    private data class Update(val serial: Long, val doc: Doc)
    private class Group {
        val active = HashSet<Doc>()
        var mail: Mail<Update>? = null
        var latest: Update? = null
    }
    private val pending = HashMap<Doc, Mail<Doc>?>()
    private val groups = HashMap<Pair<String, String>, Group>()
    private val admission = Any()
    private val children = HashSet<Job>()
    // Jobs stay here through their final settlement, even if a cancelled owner
    // can no longer attach a newly launched child to its completed worker.
    private val jobs = HashSet<Job>()
    private val grabCapacity = Semaphore(DOCUMENTS)
    private var admissionVersion = 0L
    private var sourceTurn = 0
    private enum class Outcome { HELD, JOINED }
    // Successful in-flight outcomes remain observable until finish removes the admission.
    private val outcomes = HashMap<Doc, Outcome>()
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
                        val d = synchronized(admission) {
                            val next = if ((sourceTurn++ and 1) == 0)
                                ingress.tryReceive().getOrNull() ?: links.tryReceive().getOrNull()
                            else links.tryReceive().getOrNull() ?: ingress.tryReceive().getOrNull()
                            next?.let {
                                check(pending.containsKey(it)) { "Missing pending admission" }
                                pending.remove(it)?.let { mail ->
                                    mail.ingress.tryReceive(); mail.links.tryReceive(); mail.close()
                                }
                            }
                            if (pending.isNotEmpty()) queue.trySend(Unit)
                            next
                        } ?: break
                        read(d)
                    }
                }
            }
        }
    }

    /** Finish admitted document readings before their sense encoder and stores are closed. */
    suspend fun drain() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        synchronized(admission) {
            ingress.close()
            closeIfIdle()
        }
        worker.join()
        while (true) {
            val unfinished = synchronized(admission) { jobs.toList() }
            if (unfinished.isEmpty()) break
            for (job in unfinished) job.join()
        }
        // Hard parent cancellation can stop every consumer with identifiers still
        // queued. The joined worker can no longer take ownership of those admissions.
        if (worker.isCancelled) settlement.withLock {
            val idleVersion = synchronized(admission) {
                var cleared = false
                for ((d, mail) in pending) {
                    mail?.close()
                    groups[d.project to d.id]?.active?.remove(d)
                    outcomes.remove(d)
                    if (removed.remove(d)) uncountQueued(d) else cancelled(d)
                    cleared = true
                }
                pending.clear()
                for (group in groups.values) {
                    group.latest?.let { cancelled(it.doc); cleared = true }
                    group.mail?.close()
                    check(group.active.isEmpty()) { "Document curation stopped with active work" }
                }
                groups.clear()
                links.close(); queue.close()
                while (ingress.tryReceive().isSuccess) { }
                while (links.tryReceive().isSuccess) { }
                admissionVersion.takeIf { cleared && groups.isEmpty() && children.isEmpty() }
            }
            if (idleVersion != null) settleLocked(idleVersion)
        }
        check(tallies.values.all { it.pending == 0 }) { "Document curation stopped with unfinished admitted work" }
    }

    /** Called under admission after an external input has closed. */
    private fun closeIfIdle() {
        if (ingress.isClosedForSend && groups.isEmpty() && children.isEmpty()) {
            links.close(); queue.close()
        }
    }

    /** Called under settlement; admission is never held across release. */
    private suspend fun settleLocked(version: Long) {
        val idle = synchronized(admission) {
            groups.isEmpty() && children.isEmpty() && admissionVersion == version
        }
        if (idle) runCatching { settled() }
            .onFailure { System.err.println("[INTAKE] inference release failed: ${it.message}") }
    }

    fun status(): Map<String, Any?> = tallies.toSortedMap().mapValues { (project, tally) ->
        tally.toMap() + mapOf("providers" to providerTallies.entries.filter { it.key.first == project }
            .associate { it.key.second.id to (it.value.toMap() + ("decisionProvider" to it.key.second.id)) },
            "reviewStatusAsOf" to "intake",
            "awaitingReviews" to awaiting.entries.filter { it.value.doc.project == project }.sortedBy { it.key }.map { it.value.details })
    }

    /** Every document of every mounted project: what a boot resumes. Curated documents pass through at once. */
    suspend fun resume() = resume(false)

    private suspend fun resume(link: Boolean) {
        for (p in corpus.projects()) {
            currentCoroutineContext().ensureActive()
            if (!eligible(p.name)) continue
<<<<<<< HEAD
            try {
                corpus.visit(p.name) { d ->
                    currentCoroutineContext().ensureActive()
                    if (link) offer(p.name, d.id, selectedProvider(p.name, d.id), null, true)
                    else offer(p.name, d.id)
                }
            } catch (unavailable: ProjectVisitUnavailable) {
                System.err.println("[INTAKE] ${unavailable.message}")
            }
||||||| 777887620
            for (d in corpus.docs(p.name, limit = Int.MAX_VALUE)) offer(p.name, d.id)
=======
            for (d in corpus.docs(p.name, limit = Int.MAX_VALUE)) {
                currentCoroutineContext().ensureActive()
                if (link) offer(p.name, d.id, selectedProvider(p.name, d.id), null, true)
                else offer(p.name, d.id)
            }
>>>>>>> 5dad15b012473bf5cd1367eac5e74260873dcfe1
        }
    }

    /** A document removed from its upload: a queued reading of it is dropped, not failed. */
    fun forget(project: String, id: String, unverifiedOwnership: Boolean = false) {
        synchronized(admission) {
            val affected = groups[project to id]?.active.orEmpty()
            removed.addAll(affected)
            for (doc in affected) {
                when (outcomes.remove(doc)) {
                    Outcome.JOINED -> {
                        val tally = tallies.getValue(project)
                        tally.curated.decrementAndGet()
                        tally.failed.incrementAndGet()
                        tally.note = "${doc.id}: removed near join; derived membership may be stale"
                        doc.decisionProvider?.let {
                            val provider = providerTallies.getValue(project to it)
                            provider.curated.decrementAndGet()
                            provider.failed.incrementAndGet()
                            provider.note = tally.note
                        }
                    }
                    Outcome.HELD -> {
                        tallies.getValue(project).curated.decrementAndGet()
                        doc.decisionProvider?.let { providerTallies.getValue(project to it).curated.decrementAndGet() }
                        uncountQueued(doc)
                    }
                    null -> Unit
                }
            }
            groups[project to id]?.let { group ->
                group.latest?.let { uncountQueued(it.doc) }
                group.latest = null
                group.mail?.let { it.ingress.tryReceive(); it.links.tryReceive() }
            }
            clearObservations(project, id)
            if (unverifiedOwnership) {
                val tally = tallies.computeIfAbsent(project) { Tally() }
                val note = "$id: derived membership may remain because ownership is unverified"
                tally.note = if (tally.note.isBlank()) note else "${tally.note}; $note"
            }
        }
    }
    private val removed = ConcurrentHashMap.newKeySet<Doc>()

    private class RemovedDuringJoin(cause: Throwable? = null) :
        IllegalStateException("source removed near join; derived membership may be stale", cause)

    private class JoinCancellation(val original: kotlinx.coroutines.CancellationException) :
        kotlinx.coroutines.CancellationException(original.message)

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

    /** Called under admission after an owning worker can no longer execute this item. */
    private fun cancelled(d: Doc) {
        val tally = tallies.getValue(d.project)
        tally.failed.incrementAndGet()
        tally.note = "${d.id}: curation owner cancelled before execution"
        d.decisionProvider?.let {
            val provider = providerTallies.getValue(d.project to it)
            provider.failed.incrementAndGet()
            provider.note = tally.note
        }
    }

    /** Mailbox inputs close with the owning input channels. */
    private fun <T> mail(): Mail<T> = Mail(ingressDone, linksDone)

    /** Queue one landed document. PDFs and the miner's other formats curate; twins and the rest do not. */
    fun offer(project: String, id: String) {
        if (!eligible(project)) return
        offer(project, id, selectedProvider(project, id))
    }

    fun offer(project: String, id: String, decisionProvider: DecisionProvider?) { offer(project, id, decisionProvider, null) }

    /** Manual policy disables automatic intake; this explicit document action retains legacy curation. */
    fun offerSelected(selected: ProjectDoc, decisionProvider: DecisionProvider?) {
        require(selected.cid.isNotBlank() && selected.rev.isNotBlank() && selected.seq >= 0) { "Document selection requires a complete revision" }
        offer(selected.project, selected.id, decisionProvider, selected)
    }

    private fun offer(project: String, id: String, decisionProvider: DecisionProvider?, selected: ProjectDoc?, link: Boolean = false): Boolean {
        if (!eligible(project)) return false
        if (ProjectNodes.twin(id)) return false
        if (id.substringAfterLast('.', "").lowercase() !in ProjectMiner.MINEABLE) return false
        val selectedMode = mode(project, id)
        if (selectedMode == Mode.Manual && selected == null) return false
        enqueue(project, id, decisionProvider, if (selectedMode == Mode.Manual) Mode.Legacy else selectedMode, selected, link)
        return true
    }

    /** An already admitted traversal lands its referenced document. */
    internal fun offerLink(project: String, id: String, provider: DecisionProvider?) {
        check(offer(project, id, provider, null, true)) { "Linked document was retained but not admitted by project policy: $project/$id" }
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

    private fun enqueue(project: String, id: String, decisionProvider: DecisionProvider?, selectedMode: Mode, expected: ProjectDoc? = null, link: Boolean = false) {
        val d = Doc(project, id, if (selectedMode != Mode.Legacy) null else decisionProvider, selectedMode, expected)
        synchronized(admission) {
            val key = project to id
            val group = groups[key]
            val sameDocument = group?.active.orEmpty()
            // Automatic admissions read the latest upload; explicit text retains its selected revision.
            if (d in sameDocument && pending.containsKey(d) && d !in removed && group?.latest == null) {
                val mailbox = pending[d] ?: mail<Doc>()
                check((if (link) mailbox.links else mailbox.ingress).trySend(d).isSuccess) { "Document curation intake is draining" }
                pending[d] = mailbox
                return
            }
            if (sameDocument.isNotEmpty() && (d in sameDocument || sameDocument.any { it in removed || it.mode != Mode.Legacy } ||
                    d.mode != Mode.Legacy || group?.latest != null)) {
                val holder = requireNotNull(group)
                val update = Update(admissionVersion + 1, d)
                val mailbox = holder.mail ?: mail<Update>()
                check((if (link) mailbox.links else mailbox.ingress).trySend(update).isSuccess) { "Document curation intake is draining" }
                holder.mail = mailbox
                holder.latest?.let { uncountQueued(it.doc) }
                holder.latest = update
                clearObservations(project, id)
                countQueued(d)
                return
            }
            check((if (link) links else ingress).trySend(d).isSuccess) { "Document curation intake is draining" }
            val holder = group ?: Group().also { groups[key] = it }
            check(holder.active.add(d)) { "Document is already in flight" }
            clearObservations(project, id)
            countQueued(d)
            pending[d] = null
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
        var permit: Semaphore? = null
        suspend fun selectedCurrent() {
            d.expected?.let { expected ->
                val current = requireNotNull(corpus.document(d.project, d.id)) { "Selected document is unavailable" }
                require(current.cid == expected.cid && current.rev == expected.rev && current.seq == expected.seq) {
                    "Selected document revision changed before curation or join"
                }
            }
        }
        fun selectedSource(source: DocumentSource?) {
            d.expected?.let { expected ->
                val retained = requireNotNull(source) { "Selected document source is unavailable" }
                val revision = requireNotNull(retained.project) { "Selected document source lacks a project revision" }
                require(revision.project == expected.project && revision.id == expected.id && revision.cid == expected.cid &&
                    revision.rev == expected.rev && revision.seq == expected.seq && retained.originalCid.value == expected.cid) {
                    "Retained source differs from selected document revision"
                }
            }
        }
        try {
            settlement.withLock { }
            if (removed.contains(d) || !eligible(d.project)) {
                t.queued.decrementAndGet(); providerTally?.queued?.decrementAndGet()
                return
            }
            if (d.mode is Mode.Blocked) error("Intake policy blocked: ${d.mode.reason}")
            selectedCurrent()
<<<<<<< HEAD
            if (d.decisionProvider != null) {
                grabCapacity.acquire()
                permit = grabCapacity
            }
            // A cancelled grab can release capacity before cancellation reaches this consumer.
            // The owning scope must still admit work; finally already owns any acquired permit.
            scope.coroutineContext.ensureActive()
||||||| 777887620
=======
            if (d.decisionProvider != null) {
                grabCapacity.acquire()
                permit = grabCapacity
            }
>>>>>>> 5dad15b012473bf5cd1367eac5e74260873dcfe1
            val root = constellations()
            // A removal observed before a later stage stops that stage.
            fun discarded(orphan: Boolean = false): Boolean = synchronized(admission) {
                if (d !in removed) false else {
                    if (orphan) {
                        t.note = "${d.id}: removed during curation; unjoined artifacts may remain"
                        providerTally?.note = t.note
                    }
                    uncountQueued(d)
                    true
                }
            }
            fun completed(joinedNow: Boolean): Boolean = synchronized(admission) {
                if (d in removed) {
                    if (joinedNow) {
                        t.failed.incrementAndGet()
                        t.note = "${d.id}: removed near join; derived membership may be stale"
                        providerTally?.let { it.failed.incrementAndGet(); it.note = t.note }
                    } else uncountQueued(d)
                    false
                } else {
                    t.curated.incrementAndGet(); providerTally?.curated?.incrementAndGet()
                    outcomes[d] = if (joinedNow) Outcome.JOINED else Outcome.HELD
                    true
                }
            }
            suspend fun <T> afterJoin(block: suspend () -> T): T {
                if (d in removed) throw RemovedDuringJoin()
                val result = try { block() } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw JoinCancellation(failure)
                } catch (failure: Throwable) {
                    if (d in removed) throw RemovedDuringJoin(failure)
                    throw failure
                }
                if (d in removed) throw RemovedDuringJoin()
                return result
            }
            val nodes = d.decisionProvider?.let { root?.ingestAccess(it) } ?: root
            r = if (d.decisionProvider != null && nodes != null)
                LinkedHashMap<String, LcncNodeRunner>().also { nodes.register(it) } else runners()
            fun runner(type: String) = r[type] ?: error("no runner '$type'")
            if (d.mode is Mode.ReviewRequired) {
                val sourceReader = requireNotNull(retainedSource) { "Review-required intake needs a retained source" }
                val source = sourceReader(d.project, d.id)
                selectedSource(source)
                // Names include the project, document, source revision, and complete ontology identity.
                val identity = borg.trikeshed.job.ContentId.of(borg.trikeshed.job.CanonicalCbor.encodeMap(mapOf(
                    "project" to d.project, "document" to d.id, "originalCid" to source.originalCid.value,
                    "extractedTextCid" to source.extractedTextCid.value, "ontologyCid" to d.mode.ontologyCid.value)))
                val bookBase = "intake-review-${identity.hex}"
                val bookName = nodes?.curationBookName(bookBase, d.mode.ontologyCid)
                    ?: ConstellationNodes.curationBookName(bookBase, d.mode.ontologyCid)
                val curated = reading.withLock {
                    if (removed.contains(d)) return@withLock null
                    selectedCurrent()
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
                    if (removed.contains(d) || groups[d.project to d.id]?.latest != null) t.queued.decrementAndGet()
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
            selectedSource(source)
            val text = source?.text ?: corpus.extract(d.project, d.id, Int.MAX_VALUE / 4)?.text
                ?: error("no text read from ${d.id}")
            // Held only when curated from this very text: a re-read source curates again by itself.
            if (nodes != null && nodes.holds(constellation, d.id, borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value)) {
                d.decisionProvider?.let { provider -> reading.withLock {
                    if (discarded()) return
                    score(provider, d.id)
                    if (discarded(orphan = true)) return
                    selectedCurrent()
                    afterJoin { runner(ConstellationNodes.JOIN).run(
                        LcncNode("join", ConstellationNodes.JOIN, params = params), mapOf("book" to d.id)) }
                } }
                if (!completed(d.decisionProvider != null)) return
                if (d.decisionProvider != null) {
                    grabbing(d, requireNotNull(permit))
                    permit = null
                }
                return
            }
            val notes = corpus.read(d.project, d.id + ProjectNodes.NOTES_SUFFIX, 262_144)?.text
            val doc = mapOf("project" to d.project, "id" to d.id) + (source?.let { retained ->
                retained.project?.toMap().orEmpty() + mapOf("originalCid" to retained.originalCid.value,
                    "extractedTextCid" to retained.extractedTextCid.value)
            } ?: emptyMap())
            reading.withLock {
            if (discarded()) return
            selectedCurrent()
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
            if (discarded(orphan = true)) return
            selectedCurrent()
            afterJoin { runner(ConstellationNodes.JOIN).run(
                LcncNode("join", ConstellationNodes.JOIN, params = params), mapOf("book" to book)) }
            d.decisionProvider?.let {
                afterJoin { score(it, book.toString()) }
                afterJoin {
                    selectedCurrent()
                    runner(ConstellationNodes.JOIN).run(
                        LcncNode("join", ConstellationNodes.JOIN, params = params), mapOf("book" to book))
                }
            }
            if (!completed(joinedNow = true)) return
            System.err.println("[INTAKE] ${d.project}/${d.id}: ${curated["statements"]} statements from ${curated["sections"]} sections")
            }
            if (d.decisionProvider != null) {
                grabbing(d, requireNotNull(permit))
                permit = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val ownerCancelled = !kotlinx.coroutines.currentCoroutineContext().isActive || !scope.coroutineContext.isActive
            synchronized(admission) {
                if (e is JoinCancellation && d in removed) {
                    t.failed.incrementAndGet()
                    t.note = "${d.id}: source removed near join; derived membership may be stale"
                    providerTally?.let { it.failed.incrementAndGet(); it.note = t.note }
                } else {
                    if (d in removed) {
                        t.note = "${d.id}: removed during curation; unjoined artifacts may remain"
                        providerTally?.note = t.note
                        uncountQueued(d)
                    } else {
                        t.failed.incrementAndGet()
                        t.note = "${d.id}: ${if (ownerCancelled) "curation owner cancelled" else "operation cancelled"}: ${e.message ?: e}"
                        providerTally?.let { it.failed.incrementAndGet(); it.note = t.note }
                    }
                }
            }
            if (ownerCancelled) throw if (e is JoinCancellation) e.original else e
        } catch (e: Throwable) {
            synchronized(admission) {
                if (e is RemovedDuringJoin) {
                    t.failed.incrementAndGet(); t.note = "${d.id}: ${e.message}"
                    providerTally?.let { it.failed.incrementAndGet(); it.note = t.note }
                } else if (removed.contains(d)) {
                    if (d.mode == Mode.Legacy) {
                        t.note = "${d.id}: removed during curation; unjoined artifacts may remain"
                        providerTally?.note = t.note
                    }
                    uncountQueued(d)
                }
                else if (d.mode != Mode.Legacy && groups[d.project to d.id]?.latest != null) t.queued.decrementAndGet()
                else {
                    t.failed.incrementAndGet(); t.note = "${d.id}: ${e.message ?: e}"
                    providerTally?.let { it.failed.incrementAndGet(); it.note = t.note }
                    System.err.println("[INTAKE] ${d.project}/${d.id} FAILED: ${e.message ?: e}")
                }
            }
        } finally {
            permit?.release()
            finish(d)
        }
        if (d.mode is Mode.Legacy && t.pending == 0) runCatching {
            reading.withLock { r[ConstellationNodes.BRIEF]?.run(
                LcncNode("brief", ConstellationNodes.BRIEF, params = params + ("page" to "true")), emptyMap()) }
            System.err.println("[INTAKE] ${d.project}: ${t.curated} curated, ${t.failed} failed — brief published")
        }.onFailure { System.err.println("[INTAKE] ${d.project}: brief failed: ${it.message}") }
    }

    /** A scored document's links are fetched beside the reading lane, never holding it. */
    private suspend fun grabbing(d: Doc, permit: Semaphore) {
        child(currentCoroutineContext(), onEnd = { permit.release() }) {
            try { grab(d) }
            catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                System.err.println("[GRAB] ${d.project}/${d.id} failed: ${cancelled.message ?: cancelled}")
            }
            catch (failure: Throwable) { System.err.println("[GRAB] ${d.project}/${d.id} failed: ${failure.message ?: failure}") }
        }
    }

    /** Boot discovery is a child of intake and can complete its admitted scan during drain. */
    internal fun resumeOwned() {
        child(scope.coroutineContext + worker) {
            try { resume(true) }
            catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                System.err.println("[INTAKE] resume failed: ${cancelled.message ?: cancelled}")
            }
            catch (failure: Throwable) { System.err.println("[INTAKE] resume failed: ${failure.message ?: failure}") }
        }
    }

    private fun child(context: kotlin.coroutines.CoroutineContext, onEnd: () -> Unit = {}, block: suspend () -> Unit) {
        val admitted = CompletableDeferred<Unit>()
        val child = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            val job = requireNotNull(currentCoroutineContext()[Job])
            try {
                // Even a cancelled parent waits here until the child is registered.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { admitted.await() }
                currentCoroutineContext().ensureActive()
                block()
            }
            finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val idleVersion = synchronized(admission) {
                        children.remove(job)
                        closeIfIdle()
                        admissionVersion.takeIf { it > 0 && groups.isEmpty() && children.isEmpty() }
                    }
                    onEnd()
                    if (idleVersion != null) settlement.withLock { settleLocked(idleVersion) }
                }
            }
        }
        synchronized(admission) {
            children.add(child)
            jobs.add(child)
            child.invokeOnCompletion { synchronized(admission) { jobs.remove(child) } }
        }
        admitted.complete(Unit)
    }

    private suspend fun finish(d: Doc) {
        val idleVersion = synchronized(admission) {
            removed.remove(d)
            outcomes.remove(d)
            val key = d.project to d.id
            val group = requireNotNull(groups[key])
            check(group.active.remove(d)) { "Document is not in flight" }
            if (group.active.isEmpty()) {
                val a = group.mail?.ingress?.tryReceive()?.getOrNull()
                val b = group.mail?.links?.tryReceive()?.getOrNull()
                val next = when {
                    a == null -> b
                    b == null || a.serial > b.serial -> a
                    else -> b
                }
                check(next == group.latest) { "Follow-up admission was not retained" }
                group.latest = null
                if (next != null) {
                    val doc = next.doc
                    check(group.active.add(doc)) { "Follow-up document is already in flight" }
                    links.trySend(doc).getOrThrow()
                    pending[doc] = null
                    queue.trySend(Unit).getOrThrow()
                } else {
                    groups.remove(key)
                    group.mail?.close()
                }
            }
            closeIfIdle()
            admissionVersion.takeIf { groups.isEmpty() && children.isEmpty() }
        }
        if (idleVersion != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            settlement.withLock { settleLocked(idleVersion) }
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
            lateinit var intake: CurationIntake
            val links = LinkGrab(nodes, corpus, scopes, ctx.muxContext) { p, id, provider -> intake.offerLink(p, id, provider) }
            intake = CurationIntake(corpus, { ctx.lcncRunners }, { nodes }, scope, scopes::decisionProvider,
                grab = { d -> links.grab(d.project, d.id, d.decisionProvider) },
                retainedSource = { project, id ->
                    DocumentCurationLegos.projectSource(ctx.casStore, corpus,
                        requireNotNull(corpus.document(project, id)) { "Source document is unavailable" })
                }, mode = { project, _ -> scopes.intakeMode(project) }, eligible = corpus::intakeEligible)
            scopes.onDocument = { project, id, provider -> intake.offer(project, id, provider) }
            scopes.onExplicitDocument = { selected, provider -> intake.offerSelected(selected, provider) }
            scopes.onTextForReview = { selected -> intake.offerTextForReview(selected) }
<<<<<<< HEAD
            // A removed upload does not establish ownership of a same-named global book.
            // Retain uncertain derived membership and expose it through intake status.
            scopes.onRemoved = { project, id -> intake.forget(project, id, unverifiedOwnership = true) }
            intake.resumeOwned()
||||||| 777887620
            scopes.onRemoved = { project, id -> intake.forget(project, id); nodes.leave(project, id) }
            scope.launch { runCatching { intake.resume() }.onFailure { System.err.println("[INTAKE] resume failed: ${it.message}") } }
=======
            scopes.onRemoved = { project, id -> intake.forget(project, id); nodes.leave(project, id) }
            intake.resumeOwned()
>>>>>>> 5dad15b012473bf5cd1367eac5e74260873dcfe1
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
                    require(q["manualIntake"] == null || q["manualIntake"] is Boolean) { "manualIntake must be a boolean" }
                    links.root(url, q["decisionProvider"]?.toString()?.takeIf { it.isNotBlank() }?.let(DecisionProvider::of),
                        q["requirePdf"] == true, q["manualIntake"] == true)
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
