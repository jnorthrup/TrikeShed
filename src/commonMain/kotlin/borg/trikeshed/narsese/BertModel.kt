package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** BERT hidden states in common Kotlin; one owned worker admits bounded target batches. */
class BertModel(
    parent: CoroutineScope,
    weights: BertWeights,
    tokenizer: BertWordPiece,
    override val model: String,
) : BertEncoder {
    companion object {
        suspend fun load(parent: CoroutineScope, directory: String, model: String = directory,
                         channels: (String) -> FileChannel = {
                             FileChannel.open(parent, it, setOf(StandardOpenOption.READ))
                         }): BertModel {
            var retained: BertWeights? = null
            try {
                val assets = withContext(parent.coroutineContext.minusKey(Job) + fileIoContext) {
                    val files = requireNotNull(parent.coroutineContext[FileOperations]) { "BERT requires FileOperations" }
                    val parameters = BertWeights.load(files, directory, channels).also { retained = it }
                    parameters j BertTokenizerConfig.load(files, directory, parameters.config, channels)
                }
                currentCoroutineContext().ensureActive()
                parent.coroutineContext.ensureActive()
                return BertModel(parent, assets.a, assets.b, model)
            } catch (failure: Throwable) {
                retained?.close()
                throw failure
            }
        }
    }

    val dimensions = weights.config.hiddenSize
    val parameterBytes = weights.tensors.values.sumOf { it.b.size.toLong() * 4 }
    private var parameters: BertWeights? = weights
    private var wordPiece: BertWordPiece? = tokenizer
    private val supervisor = SupervisorJob(requireNotNull(parent.coroutineContext[Job]) { "BERT requires an owning Job" })
    private val scope = CoroutineScope(parent.coroutineContext + Dispatchers.Default + this + supervisor)
    private class Read(val tokens: Series<BertToken>, val reply: CompletableDeferred<Result<Series<BertVector>>>)
    private val input = Channel.buffered<Read>(1)
    private val admission = Mutex()
    private var accepting = true
    private val completion = CompletableDeferred<Result<Unit>>()
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var active: Read? = null
        var failure: Throwable? = null
        try {
            for (read in input.kChannel) {
                active = read
                try { read.reply.complete(Result.success(process(read.tokens))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { read.reply.complete(Result.failure(error)) }
                active = null
            }
        } catch (error: Throwable) { failure = error }
        finally {
            input.close()
            parameters?.close(); parameters = null; wordPiece = null
            val error = failure ?: IllegalStateException("BERT model is closed")
            active?.reply?.complete(Result.failure(error))
            while (true) (input.kChannel.tryReceive().getOrNull() ?: break).reply.complete(Result.failure(error))
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            supervisor.complete()
        }
    }

    override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
        require(tokens.size in 0..1024) { "BERT request exceeds 1024 targets" }
        val owned = Array(tokens.size) { i -> tokens[i].also {
            require(it.lemma.isNotBlank() && it.lemma.length <= 128 && it.sentence.length <= 16_384)
            it.offsets
        } }.toSeries()
        val reply = CompletableDeferred<Result<Series<BertVector>>>()
        admission.withLock {
            check(accepting) { "BERT model is draining" }
            input.send(Read(owned, reply)).getOrThrow()
        }
        return reply.await().getOrThrow()
    }

    override suspend fun drain() = withContext(NonCancellable) {
        admission.withLock { accepting = false; input.close() }
        worker.join(); supervisor.join()
        completion.await().getOrThrow()
    }

    private suspend fun process(tokens: Series<BertToken>): Series<BertVector> {
        val tokenizer = checkNotNull(wordPiece)
        val result = arrayOfNulls<BertVector>(tokens.size)
        val sentences = LinkedHashMap<String, MutableList<Int>>()
        tokens.view.forEachIndexed { i, token -> sentences.getOrPut(token.sentence) { mutableListOf() }.add(i) }
        for ((text, indices) in sentences) {
            currentCoroutineContext().ensureActive()
            val sentence = tokenizer.sentence(text)
            val targets = indices.toSeries() α { tokenizer.encode(tokens[it], sentence) }
            val hidden = if (targets.view.any { it.target.size > 0 }) hidden(sentence.ids) else null
            targets.view.forEachIndexed { i, target ->
                val values = hidden?.takeIf { target.target.size > 0 }?.let { matrix ->
                    FloatArray(dimensions) { column ->
                        var sum = 0f
                        for (piece in target.target.view) sum += matrix[piece * dimensions + column]
                        (sum / target.target.size).also { check(it.isFinite()) { "Non-finite BERT coordinate" } }
                    }
                }
                result[indices[i]] = BertVector(target.token, target.span, values)
            }
        }
        return tokens.size j { i: Int -> checkNotNull(result[i]) }
    }

    /** Unpadded sentences omit masked padding work; each layer retains only its current activations. */
    private suspend fun hidden(ids: Series<Int>): FloatArray {
        val weights = checkNotNull(parameters)
        val config = weights.config
        val rows = ids.size
        val h = dimensions
        require(rows in 2..minOf(512, config.maxPositionEmbeddings))
        fun parameter(name: String) = weights[name].b
        suspend fun linear(input: FloatArray, prefix: String, from: Int = h, to: Int = h): FloatArray {
            val weight = parameter("$prefix.weight")
            val bias = parameter("$prefix.bias")
            if (rows < 64) return BertMath.linear(input, rows, from, weight, to, bias)
            return coroutineScope {
                val output = FloatArray(rows * to)
                val ranges = Channel.buffered<Twin<Int>>(4)
                val completed = Channel.buffered<Unit>(4)
                try {
                    val workers = Array(4) {
                        launch {
                            for (range in ranges.kChannel) {
                                BertMath.linear(input, rows, from, weight, to, bias, output, range)
                                completed.send(Unit).getOrThrow()
                            }
                        }
                    }
                    for (i in 0..3) ranges.send((i * rows / 4) j ((i + 1) * rows / 4)).getOrThrow()
                    ranges.close()
                    repeat(4) { completed.recv().getOrThrow() }
                    for (worker in workers) worker.join()
                    output
                } finally { ranges.close(); completed.close() }
            }
        }
        fun norm(input: FloatArray, prefix: String, residual: FloatArray? = null) =
            BertMath.layerNorm(input, rows, h, parameter("$prefix.weight"), parameter("$prefix.bias"),
                config.layerNormEps.toFloat(), residual)
        val words = parameter("embeddings.word_embeddings.weight")
        val positions = parameter("embeddings.position_embeddings.weight")
        val types = parameter("embeddings.token_type_embeddings.weight")
        var state = FloatArray(rows * h) { coordinate ->
            val row = coordinate / h
            val column = coordinate % h
            val id = ids[row]
            require(id in 0 until config.vocabSize) { "BERT WordPiece exceeds vocabulary" }
            words[id * h + column] + positions[row * h + column] + types[column]
        }
        state = norm(state, "embeddings.LayerNorm")
        val sum = FloatArray(state.size)
        val count = minOf(4, config.numHiddenLayers + 1)
        if (config.numHiddenLayers < 4) state.copyInto(sum)
        for (layer in 0 until config.numHiddenLayers) {
            currentCoroutineContext().ensureActive()
            val prefix = "encoder.layer.$layer"
            val attention = BertMath.attention(linear(state, "$prefix.attention.self.query"),
                linear(state, "$prefix.attention.self.key"), linear(state, "$prefix.attention.self.value"),
                rows, h, config.numAttentionHeads)
            val attended = norm(linear(attention, "$prefix.attention.output.dense"),
                "$prefix.attention.output.LayerNorm", state)
            val intermediate = BertMath.gelu(linear(attended, "$prefix.intermediate.dense", h, config.intermediateSize))
            state = norm(linear(intermediate, "$prefix.output.dense", config.intermediateSize),
                "$prefix.output.LayerNorm", attended)
            if (layer >= config.numHiddenLayers - count) for (i in state.indices) sum[i] += state[i]
        }
        for (i in sum.indices) sum[i] /= count
        return sum
    }
}
