package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Bounded input -> bounded NLP/model channelFlow -> fan-in -> ledger/intake -> result queue.
 * One dispatcher joins one branch pair at a time; there is no unbounded correlation map.
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
    private val model: suspend (Prompt) -> ModelResponse,
    private val cas: CasStore,
    private val log: DurableAppendLog,
    private val intake: SendChannel<BeliefIntake>,
    private val modelId: String,
    private val capacity: Int,
    private val observer: DocumentCuratorObserver,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<DocumentCuratorElement> {
        suspend fun create(
            scope: CoroutineScope, nlp: NlpReader, model: suspend (Prompt) -> ModelResponse, modelId: String,
            cas: CasStore, log: DurableAppendLog, bag: BeliefBagElement,
            observer: DocumentCuratorObserver? = null, capacity: Int = 8,
        ): DocumentCuratorElement {
            require(capacity > 0) { "capacity must be positive" }
            require(modelId.isNotBlank()) { "modelId is required" }
            val curator = DocumentCuratorElement(scope, nlp, model, cas, log, bag.intake, modelId, capacity,
                observer ?: DocumentCuratorObserver { _, _, _ -> })
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
    private val joined = Channel.buffered<Joined>(capacity)
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

    private data class Work(val source: DocumentSource, val reply: CompletableDeferred<Result<DocumentCurationResult>>)
    private data class Outcome<T>(val result: Result<T>, val observerError: String?)
    private sealed interface Branch {
        data class Nlp(val outcome: Outcome<NlpDocument>) : Branch
        data class Model(val outcome: Outcome<ModelResponse>) : Branch
    }
    private data class Joined(val work: Work, val nlp: Outcome<NlpDocument>, val model: Outcome<ModelResponse>, val notices: List<String>)
    private data class Completion(val work: Work, val result: Result<DocumentCurationResult>)

    suspend fun curate(source: DocumentSource): DocumentCurationResult {
        val reply = CompletableDeferred<Result<DocumentCurationResult>>(supervisor)
        try {
            admission.withLock {
                check(accepting && supervisor.isActive) { "document curator is not accepting work" }
                input.send(Work(source, reply)).getOrThrow()
            }
        } catch (e: CancellationException) {
            reply.cancel(); throw e
        } catch (e: Exception) {
            reply.complete(Result.failure(e))
        }
        return reply.await().getOrThrow()
    }

    suspend fun records(): Series<DocumentCurationRecord> = ledgerGate.withLock {
        history.values.map { r ->
            val uncertain = r.reservedReceiptCids.values().filter { it !in submitted }
            if (uncertain.isEmpty()) r else r.copy(reasons = (r.reasons.values() +
                uncertain.map { "submission unconfirmed; automatic retry suppressed: ${it.value}" }).toSeries())
        }.toSeries()
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
                    var nlp: Outcome<NlpDocument>? = null
                    var model: Outcome<ModelResponse>? = null
                    channelFlow<Branch> {
                        launch {
                            val owner = currentCoroutineContext()[Key] ?: error("missing curator owner")
                            val result = attempt { owner.nlp.read(work.source.text) }
                            send(Branch.Nlp(Outcome(result, owner.observe("curator.nlp", work.source))))
                        }
                        launch {
                            val owner = currentCoroutineContext()[Key] ?: error("missing curator owner")
                            val result = attempt { owner.model(Prompt(
                                listOf(PromptMessage.System(DocumentCuratorGrounding.instructions),
                                    PromptMessage.User(JsonSupport.stringify(DocumentCuratorCodec.source(work.source)))).toSeries(),
                                owner.modelId, temperature = 0.0, maxTokens = 4096,
                            )) }
                            send(Branch.Model(Outcome(result, owner.observe("curator.model", work.source))))
                        }
                    }.buffer(capacity).collect { branch ->
                        when (branch) {
                            is Branch.Nlp -> nlp = branch.outcome
                            is Branch.Model -> model = branch.outcome
                        }
                    }
                    observe("curator.join", work.source)?.let(notices::add)
                    joined.send(Joined(work, checkNotNull(nlp), checkNotNull(model), notices)).getOrThrow()
                }
            } finally { joined.close() }
        }
        owned {
            try {
                while (true) {
                    val work = joined.recv().getOrNull() ?: break
                    output.send(Completion(work.work, attempt { reconcile(work) })).getOrThrow()
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

    private suspend fun reconcile(join: Joined): DocumentCurationResult = ledgerGate.withLock {
        val source = join.work.source
        val reasons = mutableListOf<String>()
        val observerFailures = join.notices.toMutableList()
        join.nlp.observerError?.let(observerFailures::add); join.model.observerError?.let(observerFailures::add)
        join.nlp.result.exceptionOrNull()?.let { reasons.add("nlp: ${it.message}") }
        join.model.result.exceptionOrNull()?.let { reasons.add("model: ${it.message}") }
        val original = cas.get(source.originalCid)
        val sourceValid = original != null && ContentId.of(original) == source.originalCid &&
            ContentId.of(source.text.encodeToByteArray()) == source.extractedTextCid
        if (!sourceValid) reasons.add("source CID absent or extracted text CID mismatch")
        else putVerified(cas, source.text.encodeToByteArray())
        val response = join.model.result.getOrNull()
        val parsed = response?.let { DocumentCuratorGrounding.parse(it.content) } ?: emptySeriesOf()
        if (response != null && parsed.size == 0) reasons.add("model proposed no assertions")
        val grounded = DocumentCuratorGrounding.reconcile(source, join.nlp.result.getOrNull(), parsed)
        val candidates = mutableListOf<DocumentProposal>()
        val attributions = mutableListOf<DocumentAttribution>()
        val reservations = mutableListOf<ContentId>()
        val duplicates = mutableListOf<ContentId>()
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
            candidates.add(p)
        }
        var record = DocumentCurationRecord(source, join.nlp.result.getOrNull(), response, modelId,
            candidates.toSeries(), reasons.toSeries(), reservations.toSeries(), duplicateReceiptCids = duplicates.distinct().toSeries(),
            observerFailures = observerFailures.toSeries())
        var recordCid = append(record)
        observe("curator.record", source, listOf(recordCid).toSeries())?.let(observerFailures::add)
        val sent = mutableListOf<ContentId>()
        for (a in attributions) {
            val result = attempt {
                intake.send(BeliefIntake.Mint(a.signal, BudgetCoord(0.5f, 0.5f, 0.5f), a.receiptCid,
                    a.evidenceBasis, a.expression.toKifString()))
            }
            if (result.isSuccess) {
                sent.add(a.receiptCid)
                observe("curator.intake", source, listOf(a.receiptCid).toSeries())?.let(observerFailures::add)
            } else reasons.add("intake unconfirmed; automatic retry suppressed: ${a.receiptCid.value}: ${result.exceptionOrNull()?.message}")
        }
        if (reservations.isNotEmpty() || reasons.size != record.reasons.size) {
            record = record.copy(submittedReceiptCids = sent.toSeries(), reasons = reasons.toSeries(), observerFailures = observerFailures.toSeries())
            recordCid = append(record)
        }
        DocumentCurationResult(recordCid, record, attributions.toSeries())
    }

    private fun append(record: DocumentCurationRecord): ContentId {
        val cid = putVerified(cas, DocumentCuratorCodec.encode(record))
        val next = sequence + 1
        check(log.append(next, cid.value.encodeToByteArray()) == next) { "unexpected ledger sequence" }
        sequence = next
        history[cid] = record
        reserved.addAll(record.reservedReceiptCids.values())
        // Flush before any intake; a flush failure cannot be mistaken for completed persistence.
        log.flush()
        submitted.addAll(record.submittedReceiptCids.values())
        return cid
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
            for (p in record.proposals.values()) {
                if (p.receiptCid in record.reservedReceiptCids.values() && p.reasons.size == 0) {
                    val a = DocumentCuratorGrounding.attribution(record.source, p, cas)
                    angularReceipts[a.signal.angular] = a.receiptCid
                }
            }
            sequence = seq
        }
    }

    private suspend fun observe(name: String, source: DocumentSource, refs: Series<ContentId> =
        listOf(source.originalCid, source.extractedTextCid).toSeries()): String? =
        attempt { observer.boundary(name, source.correlation, refs) }.exceptionOrNull()?.let { "observer $name: ${it.message}" }
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
