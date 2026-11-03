package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext

data class BertSenseLabel(val source: String, val sense: String, val token: BertToken, val table: String? = null)
data class BertSenseQuery(val source: String, val token: BertToken, val menu: Series<String>)
data class BertSenseReading(val model: String?, val results: Series<BertSense>, val error: String? = null)

/**
 * The curator's word-sense role. This lane never calls Jev and never observes its votes into SenseMemory.
 * Capture is bounded by request count, not bytes per request. Input values are copied after a capture
 * permit is acquired; callers must keep their oracles stable during that copy.
 */
class BertSenseElement(
    parent: CoroutineScope,
    private val encoderFactory: suspend (CoroutineScope) -> BertEncoder,
    capacity: Int = 4,
    private val cacheSize: Int = 2048,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<BertSenseElement>
    override val key: CoroutineContext.Key<BertSenseElement> get() = Key
    init { require(capacity > 0 && cacheSize > 0) }
    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + this + supervisor)
    private class Read(val queries: Series<BertSenseQuery>, val labels: Series<BertSenseLabel>,
                       val reply: CompletableDeferred<Result<BertSenseReading>>)
    private val input = Channel.buffered<Read>(capacity)
    private val capture = Semaphore(capacity)
    private var encoder: BertEncoder? = null
    private val vectors = LinkedHashMap<BertToken, BertVector>()
    private var vectorsModel: String? = null
    private val completion = CompletableDeferred<Result<Unit>>()
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var active: Read? = null
        var failure: Throwable? = null
        try {
            for (read in input.kChannel) {
                active = read
                try { read.reply.complete(Result.success(process(read))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // An unavailable model is visible on the retained reading, not an invented sense.
                    val identity = try { encoder?.model }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    read.reply.complete(Result.success(BertSenseReading(identity,
                        read.queries.size j { BertSense(null, BertSenseStatus.Unavailable, emptySeriesOf()) },
                        error.message ?: error.toString())))
                }
                active = null
            }
        } catch (error: Throwable) { failure = error }
        finally {
            input.close()
            withContext(NonCancellable) {
                try { encoder?.drain() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            val error = failure ?: IllegalStateException("BERT sense lane closed")
            active?.reply?.complete(Result.failure(error))
            while (true) (input.kChannel.tryReceive().getOrNull() ?: break).reply.complete(Result.failure(error))
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            supervisor.complete()
        }
    }

    suspend fun read(queries: Series<BertSenseQuery>, labels: Series<BertSenseLabel>): BertSenseReading {
        val size = queries.size
        require(size in 0..128) { "A BERT sense batch contains at most 128 targets" }
        val reply = CompletableDeferred<Result<BertSenseReading>>()
        capture.withPermit {
            val (ownedQueries, ownedLabels) = try {
                // Series are index oracles, so copy the request before its channel send:
                // neither a queued call nor its menus may change under the worker.
                val ownedQueries = Array(size) { i ->
                    val query = queries[i]
                    val menu = Array(query.menu.size) { index -> query.menu[index] }
                    query.copy(menu = menu.toSeries())
                }.toSeries()
                val lemmas = ownedQueries.view.map { it.token.lemma }.toSet()
                val grouped = LinkedHashMap<Pair<String, String>, BertSenseLabel?>()
                val context = currentCoroutineContext()
                for (label in labels.view) {
                    context.ensureActive()
                    if (label.token.lemma !in lemmas) continue
                    val key = (label.source j label.token.lemma).pair
                    if (!grouped.containsKey(key)) grouped[key] = label
                    else if (grouped[key]?.sense != label.sense) grouped[key] = null
                }
                // Contradictory labels from one source are not independent votes. Retain
                // the first matching label for each uncontradicted source and lemma.
                ownedQueries j grouped.values.filterNotNull().toSeries()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return BertSenseReading(null, size j { BertSense(null, BertSenseStatus.Unavailable, emptySeriesOf()) },
                    error.message ?: error.toString())
            }
            input.send(Read(ownedQueries, ownedLabels, reply)).getOrThrow()
        }
        return reply.await().getOrThrow()
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        worker.join(); supervisor.join()
        completion.await().getOrThrow()
    }

    private suspend fun process(read: Read): BertSenseReading {
        val labels = read.labels.view
        val contexts = mutableMapOf<BertToken, String?>()
        for (label in labels) {
            if (!contexts.containsKey(label.token)) contexts[label.token] = label.sense
            else if (contexts[label.token] != label.sense) contexts[label.token] = null
        }
        val counts = mutableMapOf<String, Int>()
        for ((token, sense) in contexts) if (sense != null) counts[token.lemma] = (counts[token.lemma] ?: 0) + 1
        if (read.queries.view.none { (counts[it.token.lemma] ?: 0) >= 7 }) return BertSenseReading(null,
            read.queries.size j { BertSense(null, BertSenseStatus.InsufficientEvidence, emptySeriesOf()) })
        // Contradictory contexts cannot vote; avoid encoding them merely because
        // another context makes this batch eligible for inference.
        val evidence = labels.filter { contexts[it.token] != null }
        val model = encoder ?: encoderFactory(scope).also { encoder = it }
        var identity = vectorsModel
        if (identity != null && model.model != identity) {
            // Model identity can resolve lazily on the first encode. Once vectors
            // have been retained, a different identity cannot reuse them.
            vectors.clear()
            vectorsModel = null
            identity = null
        }
        val targets = (evidence.map { it.token } + read.queries.view.map { it.token }).distinct()
        val encoded = HashMap<BertToken, BertVector>()
        val missing = targets.filter { token -> vectors[token]?.let { encoded[token] = it; false } ?: true }
        for (batch in missing.chunked(128)) {
            if (identity != null) check(model.model == identity) { "BERT model identity changed during reading" }
            val result = model.encode(batch.toSeries())
            val observed = model.model
            check(observed.isNotBlank()) { "BERT model identity is unavailable" }
            if (identity == null) identity = observed
            else check(observed == identity) { "BERT model identity changed during reading" }
            check(result.size == batch.size) { "BERT encoder omitted targets" }
            val expected = batch.toSet()
            val received = HashSet<BertToken>()
            result.view.forEach { vector ->
                check(vector.token in expected && received.add(vector.token)) { "BERT encoder returned unexpected or duplicate targets" }
                check(vector.values == null || vector.span.resolved) {
                    "BERT encoder returned a vector for an unresolved target"
                }
                encoded[vector.token] = vector.copy(values = vector.values?.copyOf())
            }
        }
        // Validate the complete response before retaining any of it. A failed batch must
        // not poison subsequent reads, and all vectors in one model share a dimension.
        val dimensions = encoded.values.mapNotNull { vector -> vector.values?.let { values ->
            check(values.isNotEmpty() && values.all { it.isFinite() }) { "Invalid BERT vector" }
            values.size
        } }.distinct()
        check(dimensions.size <= 1) { "BERT encoder returned incompatible dimensions" }
        require(labels.all { it.source.isNotBlank() && it.sense.isNotBlank() }) { "Invalid BERT evidence" }
        val resolved = checkNotNull(identity) { "BERT model identity is unavailable" }
        check(model.model == resolved) { "BERT model identity changed during reading" }
        val index = BertSenseIndex(resolved, evidence.map {
            val vector = checkNotNull(encoded[it.token])
            // Neighbor receipts escape this lane; do not expose cached mutable arrays.
            BertSenseEvidence(it.source, it.sense, vector.copy(values = vector.values?.copyOf()), it.table)
        }.toSeries())
        val results = Array(read.queries.size) { i ->
            val q = read.queries[i]
            index.sense(checkNotNull(encoded[q.token]), q.source, q.menu)
        }
        // Index construction and every query can still fail; do not publish a
        // partial reading's vectors into the cache until all have succeeded.
        check(model.model == resolved) { "BERT model identity changed during reading" }
        for (token in missing) {
            vectors[token] = checkNotNull(encoded[token])
            while (vectors.size > cacheSize) vectors.remove(vectors.keys.first())
        }
        vectorsModel = resolved
        return BertSenseReading(resolved, results.toSeries())
    }
}
