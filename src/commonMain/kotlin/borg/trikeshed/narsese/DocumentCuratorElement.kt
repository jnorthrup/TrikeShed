package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.SeriesBuffer
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import borg.trikeshed.modelmux.ToolOntologyScaffold
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Bounded input -> CoreNLP -> bounded model/reconcile stage -> ledger/intake -> result queue.
 * Each model consumes its own validated NLP; the next document's NLP may overlap it.
 * The injected log is exclusively owned here and MUST NOT be the belief log.
 *
 * CAS-before-WAL reservations give conservative at-most-once submission across retries.
 * A crash between reservation and enqueue can lose delivery; a crash after enqueue but before
 * its completion record leaves delivery uncertain. Replay NEVER retries reservations. The
 * bag's independent WAL and this log cannot provide an atomic exactly-once transaction.
 */
class DocumentCuratorElement private constructor(
    scope: CoroutineScope,
    private val nlp: NlpReader,
    private val model: DocumentModel,
    private val cas: CasStore,
    private val log: DurableAppendLog,
    private val intake: SendChannel<BeliefIntake>,
    private val modelId: String,
    private val capacity: Int,
    private val observer: DocumentCuratorObserver,
    private val rete: CausalityReteElement?,
    private val toolOntology: ToolOntologyScaffold,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<DocumentCuratorElement> {
        const val MAX_INSTRUCTIONS_CHARS = 16 * 1024

        suspend fun create(
            scope: CoroutineScope, nlp: NlpReader, model: DocumentModel, modelId: String,
            cas: CasStore, log: DurableAppendLog, bag: BeliefBagElement,
            observer: DocumentCuratorObserver? = null, capacity: Int = 8,
            rete: CausalityReteElement? = scope.coroutineContext[CausalityReteElement.Key],
            toolOntology: ToolOntologyScaffold = emptySeriesOf(),
        ): DocumentCuratorElement {
            require(capacity > 0) { "capacity must be positive" }
            require(modelId.isNotBlank()) { "modelId is required" }
            val curator = DocumentCuratorElement(scope, nlp, model, cas, log, bag.intake, modelId, capacity,
                observer ?: DocumentCuratorObserver { _, _, _ -> }, rete, toolOntology)
            try {
                curator.replay()
                curator.start()
                return curator
            } catch (e: Throwable) {
                curator.supervisor.cancel()
                throw e
            }
        }
    }

    override val key: CoroutineContext.Key<DocumentCuratorElement> get() = Key
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + this + supervisor)
    private val input = Channel.buffered<Work>(capacity)
    private val parsed = Channel.buffered<Parsed>(capacity)
    private val output = Channel.buffered<Completion>(capacity)
    private val jobs = mutableListOf<Job>()
    private val admission = Mutex()
    private val draining = Mutex()
    private val ledgerGate = Mutex()
    private var accepting = true
    private var sequence = 0L
    private val history = linkedMapOf<ContentId, DocumentCurationRecord>()
    private val reserved = mutableSetOf<ContentId>()
    private val submitted = mutableSetOf<ContentId>()
    private val angularReceipts = mutableMapOf<Long, ContentId>()

    private data class Work(val source: DocumentSource, val instructions: String, val reply: CompletableDeferred<Result<DocumentCurationResult>>)
    private data class Outcome<T>(val result: Result<T>, val observerError: String?)
    private data class Parsed(
        val work: Work,
        val nlp: Outcome<NlpDocument>,
        val notices: List<String>,
    )
    private data class Joined(val work: Work, val nlp: Outcome<NlpDocument>, val model: Outcome<DocumentModelAnswer>, val notices: List<String>)
    private data class Completion(val work: Work, val result: Result<DocumentCurationResult>)

    suspend fun curate(
        source: DocumentSource,
        instructions: String = DocumentCuratorGrounding.instructions,
    ): DocumentCurationResult {
        require(instructions.isNotBlank() && instructions.length <= MAX_INSTRUCTIONS_CHARS) {
            "Document instructions must contain 1..$MAX_INSTRUCTIONS_CHARS characters"
        }
        val reply = CompletableDeferred<Result<DocumentCurationResult>>(supervisor)
        try {
            admission.withLock {
                check(accepting && supervisor.isActive) { "document curator is not accepting work" }
                input.send(Work(source, instructions, reply)).getOrThrow()
            }
        } catch (e: CancellationException) {
            reply.cancel(); throw e
        } catch (e: Exception) {
            reply.complete(Result.failure(e))
        }
        return reply.await().getOrThrow()
    }

    /** Exact retained records with their original receipt identities; reading never dispatches work. */
    suspend fun receipts(): Series<DocumentCurationReceipt> = ledgerGate.withLock {
        SeriesBuffer<DocumentCurationReceipt>(history.size).apply {
            for ((cid, record) in history) add(cid j record)
        }.drain()
    }

    suspend fun record(cid: ContentId): DocumentCurationRecord? = ledgerGate.withLock { history[cid] }

    suspend fun records(): Series<DocumentCurationRecord> = ledgerGate.withLock {
        SeriesBuffer<DocumentCurationRecord>(history.size).apply {
            for (r in history.values) {
                val uncertain = (r.reservedReceiptCids.values() + r.quotationReservedReceiptCids.values())
                    .filter { it !in submitted }
                add(if (uncertain.isEmpty()) r else r.copy(reasons = (r.reasons.values() +
                    uncertain.map { "submission unconfirmed; automatic retry suppressed: ${it.value}" }).toSeries()))
            }
        }.drain()
    }

    suspend fun drain() = draining.withLock {
        admission.withLock { accepting = false; input.close() }
        jobs.joinAll()
        supervisor.complete()
        supervisor.join()
    }

    suspend fun close() = drain()

    private fun owned(block: suspend DocumentCuratorElement.() -> Unit) {
        jobs.add(workers.launch {
            val owner = currentCoroutineContext()[Key] ?: error("missing DocumentCuratorElement context")
            try { owner.block() }
            catch (e: CancellationException) { owner.supervisor.cancel(e); throw e }
            catch (e: Throwable) {
                owner.supervisor.cancel(CancellationException("document curator worker failed", e))
                throw e
            }
        })
    }

    private fun start() {
        owned {
            try {
                while (true) {
                    val work = input.recv().getOrNull() ?: break
                    val notices = mutableListOf<String>()
                    observe("curator.input", work.source)?.let(notices::add)
                    val nlp = attempt {
                        withContext(Dispatchers.Default) {
                            require(sourceValid(work.source)) { "source CID absent or extracted text CID mismatch" }
                            this@DocumentCuratorElement.nlp.read(work.source.text)
                        }
                    }
                    parsed.send(Parsed(work, Outcome(nlp, observe("curator.nlp", work.source)), notices)).getOrThrow()
                }
            } finally { parsed.close() }
        }
        owned {
            try {
                while (true) {
                    val read = parsed.recv().getOrNull() ?: break
                    val document = read.nlp.result.getOrNull()
                    val issue = read.nlp.result.exceptionOrNull()?.message ?: nlpIssue(read.work.source, document)
                    val model = if (issue != null) Result.failure(IllegalStateException("skipped: NLP $issue"))
                    else attempt {
                        withContext(Dispatchers.Default) {
                            val payload = linkedMapOf<String, Any?>(
                                "source" to DocumentCuratorCodec.source(read.work.source),
                                "nlp" to DocumentCuratorCodec.nlp(checkNotNull(document)),
                            )
                            if (toolOntology.size > 0) payload["toolOntology"] =
                                List(toolOntology.size) { index -> toolOntology[index] }
                            this@DocumentCuratorElement.model(Prompt(
                                listOf(
                                    PromptMessage.System(read.work.instructions),
                                    PromptMessage.User(JsonSupport.stringify(payload)),
                                ).toSeries(),
                                modelId,
                                temperature = 0.0,
                                maxTokens = read.work.source.text.length.coerceIn(4096, 16384),
                            ), contextId = read.work.source.correlation)
                        }
                    }
                    val notices = read.notices.toMutableList()
                    val modelOutcome = Outcome(model, if (issue == null) observe("curator.model", read.work.source) else null)
                    observe("curator.join", read.work.source)?.let(notices::add)
                    val joined = Joined(read.work, read.nlp, modelOutcome, notices)
                    output.send(Completion(read.work, attempt { reconcile(joined) })).getOrThrow()
                }
            } finally { output.close() }
        }
        owned {
            while (true) {
                val completion = output.recv().getOrNull() ?: break
                completion.work.reply.complete(completion.result)
            }
        }
    }

    private suspend fun reconcile(join: Joined): DocumentCurationResult {
        val source = join.work.source
        val reasons = mutableListOf<String>()
        val observerFailures = join.notices.toMutableList()
        join.nlp.observerError?.let(observerFailures::add); join.model.observerError?.let(observerFailures::add)
        join.nlp.result.exceptionOrNull()?.let { reasons.add("nlp: ${it.message}") }
        join.model.result.exceptionOrNull()?.let { reasons.add("model: ${it.message}") }
        val sourceValid = sourceValid(source)
        if (!sourceValid) reasons.add("source CID absent or extracted text CID mismatch")
        else putVerified(cas, source.text.encodeToByteArray())
        val answer = join.model.result.getOrNull()
        val response = answer?.response
        val receipt = answer?.receipt ?: (join.model.result.exceptionOrNull() as? DocumentModelFailure)?.receipt
        val parsed = response?.let { DocumentCuratorGrounding.parse(it.content) } ?: emptySeriesOf()
        if (response != null && parsed.size == 0) reasons.add("model proposed no assertions")
        val grounded = DocumentCuratorGrounding.reconcile(source, join.nlp.result.getOrNull(), parsed)
        val candidates = mutableListOf<DocumentProposal>()
        val attributions = mutableListOf<DocumentAttribution>()
        val reservations = mutableListOf<ContentId>()
        val duplicates = mutableListOf<ContentId>()
        val quotationReservations = mutableListOf<ContentId>()
        val quotationDuplicates = mutableListOf<ContentId>()
        val attemptReceipts = mutableSetOf<ContentId>()
        for (candidate in grounded.values()) {
            val identity = if (sourceValid && candidate.reasons.size == 0) DocumentCuratorGrounding.receipt(source, candidate)
                else if (candidate.subject != null) DocumentCuratorCodec.identity(source, candidate)
                else CanonicalCbor.encodeMap(mapOf("originalCid" to source.originalCid.value,
                    "extractedTextCid" to source.extractedTextCid.value, "raw" to candidate.raw))
            val cid = putVerified(cas, identity)
            var p = candidate.copy(receiptCid = cid)
            if (!sourceValid) p = p.copy(reasons = (p.reasons.values() + "source is not CAS-grounded").toSeries())
            if (p.reasons.size == 0) {
                when {
                    cid in reserved || !attemptReceipts.add(cid) -> {
                        duplicates.add(cid)
                        if (cid in reserved && cid !in submitted) {
                            p = p.copy(reasons = listOf("submission unconfirmed; automatic retry suppressed").toSeries())
                        }
                    }
                    else -> {
                        val attribution = DocumentCuratorGrounding.attribution(source, p, cas)
                        val existing = angularReceipts[attribution.signal.angular]
                        if (existing != null && existing != cid) {
                            p = p.copy(reasons = listOf("angular collision; exact structures differ").toSeries())
                        } else {
                            angularReceipts[attribution.signal.angular] = cid
                            reservations.add(cid); attributions.add(attribution)
                        }
                    }
                }
            }
            if (sourceValid) {
                p = DocumentCuratorGrounding.quotation(source, p)
                if (p.quotationBegin != null) {
                    val quotationCid = putVerified(cas, DocumentCuratorGrounding.quotationReceipt(source, p))
                    p = p.copy(quotationReceiptCid = quotationCid)
                    when {
                        quotationCid in reserved || !attemptReceipts.add(quotationCid) -> {
                            quotationDuplicates.add(quotationCid)
                            if (quotationCid in reserved && quotationCid !in submitted)
                                reasons.add("quotation submission unconfirmed; automatic retry suppressed: ${quotationCid.value}")
                        }
                        else -> {
                            val attribution = DocumentCuratorGrounding.quotationAttribution(source, p, cas)
                            val existing = angularReceipts[attribution.signal.angular]
                            if (existing != null && existing != quotationCid)
                                reasons.add("quotation angular collision; exact structures differ: ${quotationCid.value}")
                            else {
                                angularReceipts[attribution.signal.angular] = quotationCid
                                quotationReservations.add(quotationCid); attributions.add(attribution)
                            }
                        }
                    }
                }
            }
            candidates.add(p)
        }
        var record = DocumentCurationRecord(source, join.nlp.result.getOrNull(), response, modelId,
            candidates.toSeries(), reasons.toSeries(), reservations.toSeries(), duplicateReceiptCids = duplicates.distinct().toSeries(),
            observerFailures = observerFailures.toSeries(), instructions = join.work.instructions,
            quotationReservedReceiptCids = quotationReservations.toSeries(),
            quotationDuplicateReceiptCids = quotationDuplicates.distinct().toSeries(),
            toolOntology = toolOntology, receipt = receipt)
        var recordCid = append(record)
        observe("curator.record", source, listOf(recordCid).toSeries())?.let(observerFailures::add)
        val sent = mutableListOf<ContentId>()
        val quotationsSent = mutableListOf<ContentId>()
        for (a in attributions) {
            val result = attempt {
                intake.send(BeliefIntake.Mint(a.signal, BudgetCoord(0.5f, 0.5f, 0.5f), a.receiptCid,
                    a.evidenceBasis, a.expression.toKifString()))
            }
            if (result.isSuccess) {
                if (a.receiptCid in quotationReservations) quotationsSent.add(a.receiptCid) else sent.add(a.receiptCid)
                observe("curator.intake", source, listOf(a.receiptCid).toSeries())?.let(observerFailures::add)
            } else reasons.add("intake unconfirmed; automatic retry suppressed: ${a.receiptCid.value}: ${result.exceptionOrNull()?.message}")
        }
        if (reservations.isNotEmpty() || quotationReservations.isNotEmpty() || reasons.size != record.reasons.size ||
            observerFailures.size != record.observerFailures.size) {
            record = record.copy(submittedReceiptCids = sent.toSeries(), quotationSubmittedReceiptCids = quotationsSent.toSeries(),
                reasons = reasons.toSeries(), observerFailures = observerFailures.toSeries())
            recordCid = append(record)
        }
        return DocumentCurationResult(recordCid, record, attributions.toSeries(), observerFailures.toSeries())
    }

    private suspend fun append(record: DocumentCurationRecord): ContentId = ledgerGate.withLock {
        val cid = putVerified(cas, DocumentCuratorCodec.encode(record))
        val next = sequence + 1
        check(log.append(next, cid.value.encodeToByteArray()) == next) { "unexpected ledger sequence" }
        sequence = next
        reserved.addAll(record.reservedReceiptCids.values())
        reserved.addAll(record.quotationReservedReceiptCids.values())
        // Flush before any intake; a flush failure cannot be mistaken for completed persistence.
        log.flush()
        history[cid] = record
        submitted.addAll(record.submittedReceiptCids.values())
        submitted.addAll(record.quotationSubmittedReceiptCids.values())
        cid
    }

    private suspend fun replay() = ledgerGate.withLock {
        sequence = log.replay { seq, payload ->
            require(seq > sequence) { "non-monotonic curator ledger" }
            val cid = ContentId(payload.decodeToString())
            val bytes = cas.get(cid) ?: error("missing curation record $cid")
            check(ContentId.of(bytes) == cid) { "curation record digest mismatch" }
            val record = DocumentCuratorCodec.decode(bytes)
            history[cid] = record
            reserved.addAll(record.reservedReceiptCids.values())
            submitted.addAll(record.submittedReceiptCids.values())
            reserved.addAll(record.quotationReservedReceiptCids.values())
            submitted.addAll(record.quotationSubmittedReceiptCids.values())
            for (p in record.proposals.values()) {
                if (p.receiptCid in record.reservedReceiptCids.values() && p.reasons.size == 0) {
                    val a = DocumentCuratorGrounding.attribution(record.source, p, cas)
                    angularReceipts[a.signal.angular] = a.receiptCid
                }
                if (p.quotationReceiptCid in record.quotationReservedReceiptCids.values()) {
                    val a = DocumentCuratorGrounding.quotationAttribution(record.source, p, cas)
                    angularReceipts[a.signal.angular] = a.receiptCid
                }
            }
            // Parser-derived candidates (record.nlpAxioms) are recomputed on demand for
            // inspection; replay never re-admits them as global law or re-registers raw
            // subject/object terms — that would invent an eternal rule from stored text.
            sequence = seq
        }
    }

    private suspend fun observe(name: String, source: DocumentSource, refs: Series<ContentId> =
        listOf(source.originalCid, source.extractedTextCid).toSeries()): String? =
        attempt { observer.boundary(name, source.correlation, refs) }.exceptionOrNull()?.let { "observer $name: ${it.message}" }

    private fun sourceValid(source: DocumentSource): Boolean {
        val original = cas.get(source.originalCid) ?: return false
        val extracted = cas.get(source.extractedTextCid) ?: return false
        return ContentId.of(original) == source.originalCid &&
            ContentId.of(extracted) == source.extractedTextCid && extracted.contentEquals(source.text.encodeToByteArray())
    }

    private fun nlpIssue(source: DocumentSource, document: NlpDocument?): String? =
        source.curationIndex(document).let { index ->
            if (index.facet(DocumentCurationIndexK.NlpStatus) == DocumentNlpStatus.AVAILABLE) null
            else index.facet(DocumentCurationIndexK.Reasons).values().joinToString("; ")
        }
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
