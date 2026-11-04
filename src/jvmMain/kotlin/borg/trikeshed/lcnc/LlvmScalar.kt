package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.process.KernelSeat
import kotlinx.coroutines.*
import org.graalvm.polyglot.*
import org.graalvm.polyglot.io.ByteSequence
import org.graalvm.polyglot.io.IOAccess
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.TimeSource

/** Trusted operator assertion of a fixed scalar ABI; a hash does not make arbitrary native code safe. */
class LlvmScalarProfile(val module: Path, val sha256: String, val export: String) {
    init {
        require(module.isAbsolute) { "LLVM module requires an absolute operator path" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "LLVM module SHA-256 required" }
        require(export.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}"))) { "Invalid LLVM export" }
    }
    companion object {
        const val ABI = "i32(i32,i32)"
        const val MODULE_LIMIT = 16 * 1024 * 1024

        fun parse(text: String): LlvmScalarProfile {
            require(text.encodeToByteArray(throwOnInvalidSequence = true).size <= 16 * 1024)
            val fields = DocumentResult.parse(text)
            require(fields.keys == _s["module", "sha256", "export", "abi"] && fields["abi"] == ABI) {
                "LLVM profile requires exactly module, sha256, export and abi=$ABI"
            }
            fun string(key: String) = fields[key] as? String ?: error("LLVM profile $key must be text")
            return LlvmScalarProfile(Path.of(string("module")), string("sha256"), string("export"))
        }

        /** File access remains at the userspace boundary, including a cap during the read itself. */
        internal fun read(files: FileOperations, path: String, limit: Int): ByteArray {
            val reader = files as? BoundedFileReader ?: error("LLVM requires bounded FileOperations")
            val fd = files.open(path)
            val size = try { files.size(fd) } finally { files.close(fd) }
            require(size in 1..limit.toLong()) { "LLVM file size outside bounds" }
            return reader.readBounded(path, limit).also {
                require(it.size.toLong() == size && it.size <= limit) { "LLVM file changed or exceeded bounds" }
            }
        }
    }
}

/** One serial commonMain seat owns every guest access; only copied scalars and metadata leave it. */
class LlvmScalar private constructor(
    private val kernel: KernelElement,
    override val id: KernelId,
    private val seat: KernelSeat,
    private val call: (Int, Int) -> Int,
    val metadata: Map<String, Any?>,
) : KernelLifecycle {
    suspend fun submit(left: Int, right: Int): Deferred<Result<Map<String, Any?>>> {
        val queued = TimeSource.Monotonic.markNow()
        return seat.submit("llvm.call") {
            KernelExecution.require(KernelCapability.EVALUATION)
            val queueNanos = queued.elapsedNow().inWholeNanoseconds
            val started = TimeSource.Monotonic.markNow()
            val value = call(left, right)
            _m["value" j value, "runtime" j (metadata + _m[
                "queueNanos" j queueNanos, "callNanos" j started.elapsedNow().inWholeNanoseconds])]
        }
    }

    suspend fun evaluate(left: Int, right: Int): Map<String, Any?> {
        val invocation = currentCoroutineContext()[LcncInvocationLedger.Invocation]
        val accepted = submit(left, right)
        // Native execution has no hard cancellation guarantee. An admitted effect must settle
        // before the caller writes its final receipt or snapshots the invocation ledger.
        val result = withContext(NonCancellable) { accepted.await() }
        invocation?.runtime { result.getOrNull()?.get("runtime") ?: (metadata + _m["completionOutcome" j "failed"]) }
        currentCoroutineContext().ensureActive()
        return result.getOrThrow()
    }

    fun runner() = LcncNodeRunner { node, inputs ->
        val names = _s["left", "right"]
        require(inputs.keys.all { it.removeSuffix("?") in names } && node.params.keys.all { it in names }) {
            "LLVM scalar inputs are left and right; module authority belongs to the operator"
        }
        fun argument(name: String): Int {
            require(!(inputs.containsKey(name) && inputs.containsKey("$name?"))) { "Ambiguous LLVM input $name" }
            val value = when {
                inputs.containsKey(name) -> inputs[name]
                inputs.containsKey("$name?") -> inputs["$name?"]
                else -> node.params[name]
            }
            return when (value) {
                is String -> value.toIntOrNull()
                is Byte, is Short, is Int -> (value as Number).toInt()
                is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
                is Double, is Float -> (value as Number).toDouble().takeIf {
                    it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it % 1.0 == 0.0
                }?.toInt()
                else -> null
            } ?: throw IllegalArgumentException("LLVM $name must be a signed 32-bit integer")
        }
        evaluate(argument("left"), argument("right"))
    }

    override suspend fun drain() = kernel.remove(id)

    companion object {
        suspend fun open(kernel: KernelElement, id: KernelId, profile: LlvmScalarProfile,
                         files: FileOperations, observer: KernelPointcut? = null): LlvmScalar {
            val bytes = withContext(fileIoContext) {
                LlvmScalarProfile.read(files, files.canonicalPath(profile.module.toString()), LlvmScalarProfile.MODULE_LIMIT)
            }
            val cid = ContentId.of(bytes)
            require(cid.hex == profile.sha256) { "LLVM module pin differs" }
            var context: Context? = null
            val pointcut = object : KernelPointcut {
                override fun dispatch(turn: KernelTurn) { observer?.dispatch(turn) }
                override fun complete(receipt: KernelReceipt) { observer?.complete(receipt) }
                override fun close() {
                    try { context?.close() } finally { context = null; observer?.close() }
                }
            }
            val seat = kernel.seat(id, KernelCapability.EVALUATION, parallelism = 1, capacity = 1, pointcut = pointcut)
            try {
                // Opening is an admitted turn too: cancellation cannot strand a partially built Context.
                val opened = seat.submit("llvm.open") {
                    KernelExecution.require(KernelCapability.EVALUATION)
                    val started = TimeSource.Monotonic.markNow()
                    val ctx = Context.newBuilder("llvm")
                        .allowHostAccess(HostAccess.NONE).allowHostClassLookup { false }
                        .allowIO(IOAccess.NONE).allowEnvironmentAccess(EnvironmentAccess.NONE)
                        .allowPolyglotAccess(PolyglotAccess.NONE).allowCreateThread(false).allowCreateProcess(false)
                        .allowNativeAccess(true).allowExperimentalOptions(true).option("llvm.verifyBitcode", "true")
                        .`in`(InputStream.nullInputStream()).out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
                        .build().also { context = it }
                    val module = ctx.eval(Source.newBuilder("llvm", ByteSequence.create(bytes), "${cid.hex}.bc").build())
                    val function = requireNotNull(module.getMember(profile.export)) { "LLVM export missing: ${profile.export}" }
                    require(function.canExecute()) { "LLVM export is not executable" }
                    val metadata: Map<String, Any?> = _m["backend" j "graalvm-llvm", "moduleCid" j cid.value,
                        "export" j profile.export, "abi" j LlvmScalarProfile.ABI,
                        "engineVersion" j ctx.engine.version, "llvmVersion" j ctx.engine.languages.getValue("llvm").version,
                        "nativeAccess" j true, "verifyBitcode" j true, "internalProgress" j "unobserved",
                        "pauseBoundary" j "between-calls", "hardCancellation" j false,
                        "nativeMemoryIsolation" j false, "loadNanos" j started.elapsedNow().inWholeNanoseconds]
                    LlvmScalar(kernel, id, seat, { left, right ->
                        val result = function.execute(left, right)
                        require(result.fitsInInt()) { "LLVM result does not fit the declared i32 ABI" }
                        result.asInt()
                    }, metadata)
                }
                val evaluator = withContext(NonCancellable) { opened.await().getOrThrow() }
                currentCoroutineContext().ensureActive()
                return evaluator
            } catch (failure: Throwable) {
                try { kernel.remove(id) } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
