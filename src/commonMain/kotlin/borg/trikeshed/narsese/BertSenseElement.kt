package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

data class BertSenseLabel(val source: String, val sense: String, val token: BertToken, val table: String? = null)
data class BertSenseQuery(val source: String, val token: BertToken, val menu: Series<String>)
data class BertSenseReading(val model: String?, val results: Series<BertSense>, val error: String? = null)

/** The curator's word-sense role. This lane never calls Jev and never observes its votes into SenseMemory. */
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
    private val admission = Mutex()
    private var accepting = true
    private var encoder: BertEncoder? = null
    private val vectors = LinkedHashMap<BertToken, BertVector>()
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
                    read.reply.complete(Result.success(BertSenseReading(encoder?.model,
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
        require(queries.size <= 128) { "A BERT sense batch contains at most 128 targets" }
        val reply = CompletableDeferred<Result<BertSenseReading>>()
        admission.withLock {
            check(accepting) { "BERT sense lane is draining" }
            input.send(Read(queries, labels, reply)).getOrThrow()
        }
        return reply.await().getOrThrow()
    }

    suspend fun drain() = withContext(NonCancellable) {
        admission.withLock { accepting = false; input.close() }
        worker.join(); supervisor.join()
        completion.await().getOrThrow()
    }

    private suspend fun process(read: Read): BertSenseReading {
        val lemmas = read.queries.view.map { it.token.lemma }.toSet()
        val labels = read.labels.view.filter { it.token.lemma in lemmas }.view
            .distinctBy { it.source to it.token.lemma }
        val counts = labels.groupingBy { it.token.lemma }.eachCount()
        if (read.queries.view.none { (counts[it.token.lemma] ?: 0) >= 7 }) return BertSenseReading(null,
            read.queries.size j { BertSense(null, BertSenseStatus.InsufficientEvidence, emptySeriesOf()) })
        val model = encoder ?: encoderFactory(scope).also { encoder = it }
        val targets = (labels.map { it.token } + read.queries.view.map { it.token }).distinct()
        val encoded = HashMap<BertToken, BertVector>()
        val missing = targets.filter { token -> vectors[token]?.let { encoded[token] = it; false } ?: true }
        for (batch in missing.chunked(128)) {
            val result = model.encode(batch.toSeries())
            check(result.size == batch.size) { "BERT encoder omitted targets" }
            result.view.forEach { vector ->
                encoded[vector.token] = vector
                vectors[vector.token] = vector
                while (vectors.size > cacheSize) vectors.remove(vectors.keys.first())
            }
        }
        val index = BertSenseIndex(model.model, labels.map {
            BertSenseEvidence(it.source, it.sense, checkNotNull(encoded[it.token]), it.table)
        }.toSeries())
        val results = Array(read.queries.size) { i ->
            val q = read.queries[i]
            index.sense(checkNotNull(encoded[q.token]), q.source, q.menu)
        }
        return BertSenseReading(model.model, results.toSeries())
    }
}
