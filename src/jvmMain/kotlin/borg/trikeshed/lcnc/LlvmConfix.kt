package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.process.KernelSeat
import kotlinx.coroutines.*
import org.graalvm.polyglot.*
import org.graalvm.polyglot.io.ByteSequence
import org.graalvm.polyglot.io.IOAccess
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Base64
import kotlin.time.TimeSource

/** Operator-selected code and bounded allocation; the graph supplies data only. */
class LlvmConfixProfile(val module: Path, val sha256: String, val maxInputBytes: Int, val maxRows: Int) {
    init {
        require(module.isAbsolute && sha256.matches(Regex("[0-9a-f]{64}"))) { "Confix requires an absolute module and SHA-256 pin" }
        require(maxInputBytes in 1..65536 && maxRows in 1..16384) { "Confix profile exceeds native ABI bounds" }
    }
    companion object {
        const val ABI = "confix.flat/1"
        fun parse(text: String): LlvmConfixProfile {
            require(text.encodeToByteArray(throwOnInvalidSequence = true).size <= 16 * 1024)
            val fields = DocumentResult.parse(text)
            require(fields.keys == _s["module", "sha256", "abi", "maxInputBytes", "maxRows"] && fields["abi"] == ABI) {
                "Confix profile requires module, sha256, abi=$ABI, maxInputBytes and maxRows"
            }
            fun string(key: String) = fields[key] as? String ?: error("Confix $key must be text")
            fun integer(key: String): Int {
                val number = fields[key] as? Number ?: error("Confix $key must be an integer")
                val value = number.toDouble()
                require(value.isFinite() && value in 1.0..65536.0 && value % 1.0 == 0.0)
                return value.toInt()
            }
            return LlvmConfixProfile(Path.of(string("module")), string("sha256"), integer("maxInputBytes"), integer("maxRows"))
        }
    }
}

/** Each admitted scan owns one guest allocation and copies geometry before releasing it. */
class LlvmConfix private constructor(
    private val kernel: KernelElement,
    override val id: KernelId,
    private val seat: KernelSeat,
    val profile: LlvmConfixProfile,
    val metadata: Map<String, Any?>,
    private val call: (Syntax, ByteArray, Int) -> Map<String, Any?>,
) : KernelLifecycle {
    suspend fun submit(format: Syntax, source: ByteArray, rowCapacity: Int = profile.maxRows): Deferred<Result<Map<String, Any?>>> {
        require(source.size <= profile.maxInputBytes && rowCapacity in 1..profile.maxRows) { "Confix input or row capacity exceeds profile" }
        val retained = source.copyOf()
        val queued = TimeSource.Monotonic.markNow()
        return seat.submit("confix.scan") {
            KernelExecution.require(KernelCapability.EVALUATION)
            val queueNanos = queued.elapsedNow().inWholeNanoseconds
            val started = TimeSource.Monotonic.markNow()
            val result = call(format, retained, rowCapacity)
            @Suppress("UNCHECKED_CAST")
            val observations = result["runtime"] as Map<String, Any?>
            result + _m["runtime" j (metadata + observations + _m[
                "queueNanos" j queueNanos, "callNanos" j started.elapsedNow().inWholeNanoseconds])]
        }
    }

    suspend fun scan(format: Syntax, source: ByteArray, rowCapacity: Int = profile.maxRows): Map<String, Any?> {
        val invocation = currentCoroutineContext()[LcncInvocationLedger.Invocation]
        val accepted = submit(format, source, rowCapacity)
        val result = withContext(NonCancellable) { accepted.await() }
        invocation?.runtime { result.getOrNull()?.get("runtime") ?: (metadata + _m["completionOutcome" j "failed"]) }
        currentCoroutineContext().ensureActive()
        return result.getOrThrow()
    }

    fun runner(cas: CasStore? = null) = LcncNodeRunner { node, inputs ->
        require(inputs.keys.all { it == "data" || it == "data?" } && node.params.keys.all { it == "format" || it == "data" }) {
            "Confix inputs are format and data; native authority belongs to the operator"
        }
        require(!(inputs.containsKey("data") && inputs.containsKey("data?"))) { "Ambiguous Confix data input" }
        val format = Syntax.valueOf(node.params["format"] ?: "JSON")
        val data = (when {
            inputs.containsKey("data") -> inputs["data"]
            inputs.containsKey("data?") -> inputs["data?"]
            else -> node.params["data"]
        }) as? String ?: error("Confix data must be text (CBOR uses canonical base64)")
        val source = if (format == Syntax.CBOR) {
            require(data.length <= ((profile.maxInputBytes + 2) / 3) * 4) { "Confix encoded input exceeds profile" }
            Base64.getDecoder().decode(data).also { require(Base64.getEncoder().encodeToString(it) == data) { "Confix CBOR requires canonical base64" } }
        } else {
            require(data.length <= profile.maxInputBytes) { "Confix text exceeds profile" }
            data.encodeToByteArray(throwOnInvalidSequence = true)
        }
        val result = scan(format, source)
        if (cas != null) withContext(fileIoContext) {
            check(cas.put(source).value == result["sourceCid"])
            cas.sync()
        }
        result + _m["sourceRetained" j (cas != null)]
    }

    override suspend fun drain() = kernel.remove(id)

    companion object {
        suspend fun open(kernel: KernelElement, id: KernelId, profile: LlvmConfixProfile,
                         files: FileOperations, observer: KernelPointcut? = null): LlvmConfix {
            val bytes = withContext(fileIoContext) {
                LlvmScalarProfile.read(files, files.canonicalPath(profile.module.toString()), LlvmScalarProfile.MODULE_LIMIT)
            }
            val cid = ContentId.of(bytes)
            require(cid.hex == profile.sha256) { "Confix module pin differs" }
            var context: Context? = null
            val pointcut = object : KernelPointcut {
                override fun dispatch(turn: KernelTurn) { observer?.dispatch(turn) }
                override fun complete(receipt: KernelReceipt) { observer?.complete(receipt) }
                override fun close() { try { context?.close() } finally { context = null; observer?.close() } }
            }
            val seat = kernel.seat(id, KernelCapability.EVALUATION, parallelism = 1, capacity = 1, pointcut = pointcut)
            try {
                val opened = seat.submit("confix.open") {
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
                    fun export(name: String) = requireNotNull(module.getMember("confix_$name")) { "Confix export missing: $name" }
                        .also { require(it.canExecute()) }
                    val abi = export("abi"); val allocate = export("open"); val write = export("write")
                    val run = export("run"); val header = export("header"); val cell = export("cell")
                    val child = export("child"); val release = export("close"); val live = export("live_bytes"); val guards = export("guards")
                    require(abi.execute().asInt() == 1 && live.execute().asLong() == 0L) { "Confix ABI handshake failed" }
                    val metadata: Map<String, Any?> = _m["backend" j "graalvm-llvm", "moduleCid" j cid.value,
                        "abi" j LlvmConfixProfile.ABI, "engineVersion" j ctx.engine.version,
                        "llvmVersion" j ctx.engine.languages.getValue("llvm").version,
                        "nativeAccess" j true, "verifyBitcode" j true, "internalProgress" j "unobserved",
                        "pauseBoundary" j "between-scans", "hardCancellation" j false, "nativeMemoryIsolation" j false,
                        "loadNanos" j started.elapsedNow().inWholeNanoseconds, "semanticProfile" j "mlir-nix-confix-571b57d3",
                        "strictValidation" j false, "copyTransport" j "packed-i32"]
                    val tags = s_["IoNothing", "IoBoolean", "IoLong", "IoDouble", "IoString", "IoObject", "IoArray", "IoBytes"]
                    LlvmConfix(kernel, id, seat, profile, metadata) { format, source, capacity ->
                        check(live.execute().asLong() == 0L) { "Confix previous allocation remains" }
                        val handle = allocate.execute(source.size, capacity)
                        check(!handle.isNull) { "Confix allocation refused" }
                        val copied = try {
                            val allocated = live.execute().asLong()
                            var offset = 0
                            while (offset < source.size) {
                                var packed = 0
                                for (lane in 0..3) if (offset + lane < source.size)
                                    packed = packed or ((source[offset + lane].toInt() and 255) shl (lane * 8))
                                check(write.execute(handle, offset, packed).asInt() == 0)
                                offset += 4
                            }
                            val scanStart = TimeSource.Monotonic.markNow()
                            val code = run.execute(handle, when (format) { Syntax.JSON -> 0; Syntax.CBOR -> 1; Syntax.YAML -> 2 }).asInt()
                            val scanNanos = scanStart.elapsedNow().inWholeNanoseconds
                            check(guards.execute(handle).asInt() == 1) { "Confix buffer guard failed" }
                            val total = header.execute(handle, 0).asInt()
                            check(code in 0..5 && header.execute(handle, 1).asInt() == code && total in 0..capacity) { "Invalid Confix result header" }
                            val at = header.execute(handle, 2).asInt()
                            var children = 0
                            val rows = ((if (code == 0) total else 0) j { row: Int ->
                                fun value(field: Int) = cell.execute(handle, row, field).asInt()
                                val begin = value(0); val end = value(1); val tag = value(2); val depth = value(3); val count = value(4)
                                check(end < begin || (begin >= 0 && end < source.size)) { "Confix span outside input" }
                                check(tag in 0 until tags.size && depth in 0 until total && count in 0..total)
                                children += count; check(children <= total)
                                val kids = (count j { index: Int -> child.execute(handle, row, index).asInt().also {
                                    check(it > row && it < total) { "Confix child outside geometry" }
                                } }).view.toList()
                                _m.get<String, Any?>("open" j begin, "close" j end, "tag" j tags[tag], "depth" j depth, "children" j kids)
                            }).view.toList()
                            _m.get<String, Any?>("ok" j (code == 0), "format" j format.name, "sourceCid" j ContentId.of(source).value,
                                "sourceBytes" j source.size, "rowCount" j rows.size, "observedRows" j total, "rows" j rows,
                                "fault" j _m["code" j code, "name" j s_["none", "truncated", "reserved-cbor-ai", "span-bounds", "nonprogress", "row-capacity"][code],
                                    "at" j at, "atKind" j when { code == 0 -> "none"; code == 3 -> "row"; format == Syntax.YAML -> "unspecified"; else -> "input" }],
                                "runtime" j _m["scanNanos" j scanNanos, "allocatedBytes" j allocated, "rowCapacity" j capacity,
                                    "guardsVerified" j true])
                        } finally { release.execute(handle) }
                        val remaining = live.execute().asLong()
                        check(remaining == 0L) { "Confix native allocation was not released" }
                        @Suppress("UNCHECKED_CAST")
                        val observations = copied["runtime"] as Map<String, Any?>
                        copied + _m["runtime" j (observations + _m["liveBytesAfter" j remaining])]
                    }
                }
                val scanner = withContext(NonCancellable) { opened.await().getOrThrow() }
                currentCoroutineContext().ensureActive()
                return scanner
            } catch (failure: Throwable) {
                try { kernel.remove(id) } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
