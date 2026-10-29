package borg.trikeshed.narsese

import borg.trikeshed.parse.*
import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.process.ProcessLineChannel
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import borg.trikeshed.userspace.nio.platform.spi.MatrixOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext

/** One bounded inference lane owns the mapped Kotlin model or explicit Python compatibility runtime. */
class JevaElement(parent: CoroutineScope, val config: JevaConfig = JevaConfig(), capacity: Int = 4) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<JevaElement>
    override val key: CoroutineContext.Key<JevaElement> get() = Key
    val supervisor = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + supervisor + this)
    /** Completes at [closeAdmission]; afterwards only retained curators submit until their effects settle. */
    val admission: CompletableJob = Job()
    val dependentsLock = Any()
    val dependents = mutableMapOf<CurationElement, Job>()
    val places = Semaphore(capacity.also { require(it > 0) })
    val input = Channel.buffered<Work>(capacity)
    @kotlin.concurrent.Volatile
    var metadata: Map<String, Any?> = config.fields() + mapOf("available" to false, "error" to "Jeva runtime has not been checked")
        private set
    private var resolvedMetadata: Map<String, Any?>? = null
    enum class Operation { STATUS, INFER, RELEASE }
    class Work(val operation: Operation, val request: String?, val questions: JevaQuestions?,
               val reply: CompletableDeferred<Result<Map<String, Any?>>>)
    val completion = CompletableDeferred<Result<Unit>>()
    var process: ProcessLineChannel? = null
        private set
    var nio: NioSupervisor? = null
        private set
    var inference: JevaInference? = null
        private set
    var released = false
        private set
    var expiration: JevaDeadline.Expired? = null
        private set

    val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
        var failure: Throwable? = null
        try {
            for (work in input.kChannel) {
                places.release()
                val result = runCatching {
                    when (work.operation) {
                        Operation.STATUS -> status()
                        Operation.RELEASE -> {
                            metadata = (resolvedMetadata ?: metadata) + mapOf("loaded" to false)
                            released = metadata["model_identity"] != null
                            closeRuntime()
                            expiration = null
                            metadata
                        }
                        Operation.INFER -> {
                            val available = ensureLoaded()
                            check(available["available"] == true) { "Jeva unavailable: ${available["error"]}" }
                            val answer = infer(work, Jeva.text(available["model_identity"], "model identity"))
                            metadata = metadata + mapOf("loaded" to true)
                            answer
                        }
                    }
                }
                result.exceptionOrNull()?.let {
                    if (it is JevaDeadline.Expired) expiration = it
                    metadata = metadata + mapOf("available" to false, "error" to (it.message ?: it.toString()))
                }
                // Availability is data; inference errors remain failures and cannot become evidence.
                work.reply.complete(if (work.operation == Operation.STATUS && result.isFailure) Result.success(metadata) else result)
            }
        } finally {
            try { closeRuntime() } catch (error: Throwable) { failure = error }
            try { nio?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    } }

    val cancellation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { closeLanes() } }
    }

    /** Closing model resources does not close the lane, its dependents, or its reusable NIO service. */
    private suspend fun closeRuntime() {
        var failure: Throwable? = null
        try { inference?.close(); inference = null } catch (error: Throwable) { failure = error }
        try { process?.drain(); process = null } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    /** Registered upstream lanes retain future inference admission until their accepted effects have settled. */
    fun retain(curator: CurationElement) = synchronizedLock(dependentsLock) {
        if (admission.isActive && supervisor.isActive && curator !in dependents) {
            dependents[curator] = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
                curator.completion.await()
            } }
        }
    }

    private fun nested(curator: CurationElement?): Boolean = synchronizedLock(dependentsLock) {
        curator != null && curator in dependents && !curator.completion.isCompleted
    }

    suspend fun channel(): ProcessLineChannel {
        check(config.runtime == JevaRuntime.PYTHON) { "Kotlin Jeva does not launch a Python process" }
        process?.let { return it }
        val context = currentCoroutineContext()
        val ops = context[ProcessOperations] ?: context[NioSupervisor]?.service<ProcessOperations>()
            ?: nio?.service<ProcessOperations>() ?: run {
            val owned = NioSupervisor()
            nio = owned
            owned.open()
            requireNotNull(owned.service<ProcessOperations>()) { "Jeva requires local ProcessOperations" }
        }
        return ProcessLineChannel.open(CoroutineScope(context + ops), config.python,
            listOf("-u", "-c", JevaProcess.program, jsonOf(config.fields() +
                ("worker_sha256" to ContentId.of(JevaProcess.program.encodeToByteArray()).hex))),
            env = config.env + mapOf("HF_HUB_OFFLINE" to "1", "TRANSFORMERS_OFFLINE" to "1", "HF_HUB_DISABLE_TELEMETRY" to "1"),
            capacity = 1, frameBytes = 8_388_608, timeoutMs = config.timeoutMs).also { process = it }
    }

    fun decode(line: String): Map<*, *> = requireNotNull(reifyStrict(line) as? Map<*, *>) { "Jeva returned no JSON object" }

    suspend fun exchange(request: String): Map<*, *> {
        val channel = channel()
        return try { decode(channel.exchange(request)) }
        catch (failure: Throwable) {
            process = null
            try { channel.drain() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Return only after every answer validates; Python predictions retain their individual process deadlines. */
    suspend fun infer(work: Work, model: String): Map<String, Any?> {
        val state = requireNotNull(reifyMap(requireNotNull(work.request))["state"] as? String) { "Jeva request has no state" }
        val questions = requireNotNull(work.questions)
        if (config.runtime == JevaRuntime.KOTLIN) return withContext(Dispatchers.Default + fileIoContext) {
            requireNotNull(inference).infer(state, questions).also {
                check(it["model"] == model) { "Jeva model identity changed while answering" }
            }
        }
        val answers = LinkedHashMap<String, Any?>()
        val usage = LinkedHashMap<String, Long>()
        for ((id, question) in questions) {
            val selected = mapOf(id to question)
            val reply = Jeva.answers(exchange(Jeva.body(state, selected)), selected)
            check(reply["model"] == model) { "Jeva model identity changed while answering" }
            answers[id] = requireNotNull((reply["answers"] as Map<*, *>)[id])
            val measured = reply["usage"]
            require(measured == null || measured is Map<*, *>) { "Jeva returned invalid usage" }
            for ((key, value) in (measured as? Map<*, *>).orEmpty()) {
                val name = Jeva.text(key, "usage field")
                val count = requireNotNull((value as? Number)?.toDouble()?.takeIf {
                    it.isFinite() && it >= 0.0 && it % 1.0 == 0.0 && it < Long.MAX_VALUE.toDouble()
                }) { "Invalid Jeva integer usage '$name'" }.toLong()
                val prior = usage[name] ?: 0L
                require(count <= Long.MAX_VALUE - prior) { "Jeva usage overflow '$name'" }
                usage[name] = prior + count
            }
        }
        return mapOf("model" to model, "answers" to answers, "usage" to usage)
    }

    suspend fun status(): Map<String, Any?> {
        if (expiration != null || released || metadata["available"] == true) return metadata
        return resolve()
    }

    suspend fun ensureLoaded(): Map<String, Any?> {
        expiration?.let { throw it }
        if (released) closeRuntime()
        return if (released || metadata["available"] != true) resolve() else metadata
    }

    private suspend fun resolve(): Map<String, Any?> {
        // Request failures do not revoke a resolved runtime; a failed load or changed identity does.
        resolvedMetadata = null
        val fields: Map<String, Any?>
        if (config.runtime == JevaRuntime.KOTLIN) {
            val runtime = inference ?: run {
                val context = currentCoroutineContext()
                val files = context[FileOperations] ?: context[NioSupervisor]?.service<FileOperations>()
                    ?: nio?.service<FileOperations>() ?: run {
                        val owned = NioSupervisor(); nio = owned; owned.open()
                        requireNotNull(owned.service<FileOperations>()) { "Jeva requires FileOperations" }
                    }
                withContext(Dispatchers.Default + fileIoContext + files) {
                    if (config.device == "mlx") {
                        val encoders = context[EncoderOperations] ?: context[NioSupervisor]?.service<EncoderOperations>()
                            ?: nio?.service<EncoderOperations>()
                        return@withContext JevaInference.loadDevice(config, requireNotNull(encoders) {
                            "Jeva device mlx needs an EncoderOperations provider (macOS native)"
                        })
                    }
                    val owner = CoroutineScope(currentCoroutineContext())
                    val matrices = context[MatrixOperations] ?: context[NioSupervisor]?.service<MatrixOperations>()
                        ?: nio?.service<MatrixOperations>()
                    JevaInference.load(config, files, matrices) { path -> FileChannel.open(owner, path, setOf(StandardOpenOption.READ)) }
                }.also { inference = it }
            }
            fields = mapOf("available" to true, "loaded" to true,
                "model_identity" to runtime.identity, "runtime" to "kotlin", "device" to config.device,
                "mapped_weights" to true, "arithmetic" to "float32",
                "matrix" to runtime.model.decision.matrix.identity, "error" to null)
        } else {
            val reply = exchange("{\"op\":\"status\"}")
            fields = reply.entries.associate { (key, value) -> Jeva.text(key, "status field") to value }
        }
        require(fields["available"] is Boolean) { "Jeva returned invalid availability" }
        if (fields["available"] == true) {
            val identity = Jeva.text(fields["model_identity"], "resolved model identity")
            val previous = metadata["model_identity"]
            if (previous != null && previous != identity) {
                released = true
                metadata = metadata + mapOf("loaded" to false)
                val failure = IllegalStateException("Jeva model identity changed while reloading")
                try { closeRuntime() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        metadata = config.fields() + metadata.filterKeys { it == "model_identity" } + fields
        if (fields["available"] == true) resolvedMetadata = metadata
        released = false
        return metadata
    }

    suspend fun submit(request: String?, questions: JevaQuestions?,
                       operation: Operation = if (request == null) Operation.STATUS else Operation.INFER): Map<String, Any?> {
        val curator = currentCoroutineContext()[CurationElement]
        check(admission.isActive && supervisor.isActive || nested(curator)) { "Jeva inference is draining" }
        places.acquire()
        val reply = CompletableDeferred<Result<Map<String, Any?>>>()
        try {
            check(admission.isActive && supervisor.isActive || nested(curator)) { "Jeva inference is draining" }
            check(input.kChannel.trySend(Work(operation, request, questions, reply)).isSuccess) { "Jeva inference is draining" }
        } catch (failure: Throwable) {
            places.release()
            throw failure
        }
        return reply.await().getOrThrow()
    }

    suspend fun availability(): Map<String, Any?> = submit(null, null)

    /** Serialize resource release behind admitted work; later inference reloads the same resolved identity. */
    suspend fun release(): Map<String, Any?> = submit(null, null, Operation.RELEASE)

    suspend fun ask(state: Any, questions: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val prepared = Jeva.questions(questions)
        return submit(Jeva.body(state, prepared), prepared)
    }

    fun closeAdmission() { admission.complete() }

    private suspend fun closeLanes() {
        closeAdmission()
        val retained = synchronizedLock(dependentsLock) { dependents.values.toList() }
        retained.joinAll()
        input.close()
        worker.join()
        synchronizedLock(dependentsLock) { dependents.clear() }
    }

    suspend fun drain() = withContext(NonCancellable) {
        closeLanes()
        cancellation.cancelAndJoin()
        supervisor.complete()
        supervisor.join()
        completion.await().getOrThrow()
    }
}
