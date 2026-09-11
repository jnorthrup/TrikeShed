package borg.trikeshed.bench.columnar

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.select
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.JvmIsamOperations
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.serialization.json.*
import java.io.File
import java.lang.System
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.system.exitProcess
import kotlin.system.measureNanoTime

/** Existing production factory seams are visible only through this benchmark's friend compilation. */
enum class IsamBackend { EMULATED, NATIVE, AUTO }
enum class IsamTracePlacement { OFF, APPLICATION, BACKEND, BOTH }

class IsamIoTrace(
    val placement: IsamTracePlacement,
    val operations: Set<String> = emptySet(),
    val phases: Set<String> = emptySet(),
    val every: Int = 1,
) {
    init { require(every > 0) }
    val enabled: Boolean get() = placement != IsamTracePlacement.OFF
    private var matched = 0L
    private val selected = mutableSetOf<Pair<Int, Long>>()
    val eventLimit = 100000
    var omittedEvents = 0L
        private set
    val events = mutableListOf<Map<String, Any?>>()
    var phase = "setup"
    var iteration = -1
    var backendNs = 0L
    var readBytes = 0L
    var writtenBytes = 0L
    var errors = 0L
    var cancellations = 0L
    var requests = 0L
    var nextChannel = 0
    val backends = mutableListOf<Map<String, Any?>>()
    fun record(event: Map<String, Any?>) {
        if (!enabled) return
        val kind = event["event"].toString()
        val phaseMatches = phases.isEmpty() || phase in phases
        val keep = when (kind) {
            "backend_submit" -> {
                if (placement == IsamTracePlacement.APPLICATION || !phaseMatches || operations.isNotEmpty() && event["opcode"] !in operations) false
                else if (++matched % every != 0L) false
                else if (events.size + selected.size + 2 > eventLimit) { omittedEvents += 2; false }
                else selected.add(event["channel"] as Int to event["request"] as Long)
            }
            "backend_complete" -> selected.remove(event["channel"] as Int to event["request"] as Long)
            "application_failure", "backend_throw" -> true
            else -> phaseMatches && placement != IsamTracePlacement.BACKEND
        }
        if (keep) {
            // Reserve one slot for every selected request's completion, including diagnostic replay.
            if (kind != "backend_complete" && kind != "backend_submit" && events.size + selected.size >= eventLimit) omittedEvents++
            else events.add(linkedMapOf("phase" to phase, "iteration" to iteration, "nano" to System.nanoTime()) + event)
        }
    }
    fun <T> stage(name: String, action: () -> T): Pair<T, Map<String, Any?>> {
        phase = name
        val before = backendNs
        record(mapOf("event" to "application_enter"))
        lateinit var value: Any
        val elapsed = measureNanoTime { value = action() as Any }
        record(mapOf("event" to "application_return"))
        @Suppress("UNCHECKED_CAST")
        return value as T to linkedMapOf("phase" to name, "application_ns" to elapsed, "backend_ns" to backendNs - before)
    }
}

class IsamIoBackend(val delegate: UserspaceChannelBackend, val channel: Int, val trace: IsamIoTrace) : UserspaceChannelBackend by delegate {
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        for (sqe in submissions) {
            trace.requests++
            if (trace.enabled) trace.record(linkedMapOf("event" to "backend_submit", "channel" to channel, "request" to sqe.userData,
                "opcode" to sqe.opcode.name, "fd" to sqe.fd, "offset" to sqe.offset, "requested_bytes" to sqe.len,
                "native_operation" to (nativeCapabilities and sqe.opcode.mask != 0L)))
        }
        lateinit var results: List<SelectionResult>
        try {
            trace.backendNs += measureNanoTime { results = delegate.submitBatch(submissions) }
        } catch (failure: Throwable) {
            trace.record(mapOf("event" to "backend_throw", "channel" to channel, "error" to failure.toString()))
            throw failure
        }
        check(results.size == submissions.size) { "Missing backend completions" }
        val pending = submissions.associateBy { it.userData }.toMutableMap()
        for (cqe in results) {
            val sqe = checkNotNull(pending.remove(cqe.userData)) { "Unknown completion token ${cqe.userData}" }
            if (cqe.res < 0) trace.errors++
            if (cqe.res == -125) trace.cancellations++
            if (sqe.opcode == UringOp.READ && cqe.res > 0) trace.readBytes += cqe.res
            if (sqe.opcode == UringOp.WRITE && cqe.res > 0) trace.writtenBytes += cqe.res
            if (trace.enabled) trace.record(linkedMapOf("event" to "backend_complete", "channel" to channel, "request" to cqe.userData,
                "opcode" to sqe.opcode.name, "result" to cqe.res, "errno" to if (cqe.res < 0) -cqe.res else 0,
                "cancelled" to (cqe.res == -125)))
        }
        check(pending.isEmpty())
        return results
    }
}

fun isamBackend(mode: IsamBackend): UserspaceChannelBackend {
    val backend = if (mode == IsamBackend.EMULATED) openJvmEmulatedChannelBackend() else openUserspaceChannelBackend(32)
    if (mode == IsamBackend.NATIVE) {
        val required = UringOp.caps(UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.CLOSE, UringOp.STATX)
        if (backend.nativeCapabilities and required != required) {
            val status = backend.availability
            backend.close()
            error("Native ISAM operations unavailable: $status")
        }
    }
    return backend
}

fun isamOperations(mode: IsamBackend, trace: IsamIoTrace): JvmIsamOperations = JvmIsamOperations { path: String, options: Set<OpenOption> ->
    FileChannel.open(path, options) {
        val backend = isamBackend(mode)
        val channel = trace.nextChannel++
        trace.backends.add(mapOf("channel" to channel, "path" to path, "availability" to backend.availability,
            "capabilities" to backend.capabilities, "native_capabilities" to backend.nativeCapabilities))
        UringChannel(FunctionalUringFacade(32, IsamIoBackend(backend, channel, trace)))
    }
}

fun isamInput(rows: Int): Cursor {
    val schema = s_[ColumnMeta("id", IOMemento.IoInt), ColumnMeta("flag", IOMemento.IoByte), ColumnMeta("amount", IOMemento.IoDouble)]
    return rows j { row ->
        3 j { column ->
            val value: Any = when (column) { 0 -> row; 1 -> (row % 4).toByte(); else -> ((row * 17L + 3L) % 101L - 50L) / 64.0 }
            value j { schema[column] }
        }
    }
}

/** Independently encode the fixed-width test schema, avoiding production codecs in the reference. */
fun expectedFiles(rows: Int, grouped: Boolean): Map<String, ByteArray> {
    fun bytes(width: Int, cell: (ByteBuffer, Int) -> Unit): ByteArray {
        val output = ByteBuffer.allocate(rows * width).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rows) { cell(output, it) }
        return output.array()
    }
    if (!grouped) return mapOf("rows.bin" to bytes(13) { output, row ->
        output.putInt(row).put((row % 4).toByte()).putDouble(((row * 17L + 3L) % 101L - 50L) / 64.0)
    })
    return mapOf("rows.bin" to bytes(8) { output, row -> output.putDouble(((row * 17L + 3L) % 101L - 50L) / 64.0) },
        "rows.IoInt.bin" to bytes(4) { output, row -> output.putInt(row) },
        "rows.IoByte.bin" to bytes(1) { output, row -> output.put((row % 4).toByte()) })
}

fun isamIteration(rows: Int, grouped: Boolean, mode: IsamBackend, directory: File, trace: IsamIoTrace): Map<String, Any?> {
    val input = isamInput(rows)
    val half = rows / 2
    val operations = isamOperations(mode, trace)
    val path = File(directory, "rows.bin").absolutePath
    val stages = mutableListOf<Map<String, Any?>>()
    stages += trace.stage("write") { IsamDataFile.write(input[0 until half], path, operations = operations, useMonocursorGroupings = grouped) }.second
    stages += trace.stage("append") { IsamDataFile.append(input[half until rows].view, path, operations = operations, useMonocursorGroupings = grouped) }.second
    for ((name, bytes) in expectedFiles(rows, grouped)) {
        check(File(directory, name).readBytes().contentEquals(bytes)) { "Physical ISAM bytes differ: $name" }
    }
    val reader = IsamDataFile(path, operations = operations)
    try {
        stages += trace.stage("open") { reader.open(); check(reader.size == rows) }.second
        val (values, readTiming) = trace.stage("read_decode") {
            val output = DoubleArray(rows * 3)
            repeat(rows) { index ->
                val row = reader[index]
                output[index * 3] = (row[0].a as Int).toDouble()
                output[index * 3 + 1] = (row[1].a as Byte).toDouble()
                output[index * 3 + 2] = row[2].a as Double
            }
            output
        }
        stages += readTiming
        repeat(rows) { row ->
            check(values[row * 3] == row.toDouble())
            check(values[row * 3 + 1] == (row % 4).toDouble())
            check(values[row * 3 + 2] == ((row * 17L + 3L) % 101L - 50L) / 64.0)
        }
        val (sum, queryTiming) = trace.stage("query_projection") {
            val projected = reader.select("amount", "id")
            var total = 0.0
            repeat(rows) { index ->
                val row = projected[index]
                if ((row[1].a as Int) % 7 == 0) total += row[0].a as Double
            }
            total
        }
        stages += queryTiming
        val expected = (0 until rows).filter { it % 7 == 0 }.sumOf { (it * 17L + 3L) % 101L - 50L } / 64.0
        check(sum == expected) { "Query checksum mismatch: $sum vs $expected" }
        stages += trace.stage("close") { reader.close() }.second
        return linkedMapOf("layout" to if (grouped) "type_grouped" else "row_major", "status" to "verified",
            "rows" to rows, "data_bytes" to rows * 13L, "verified_cells" to rows * 3L,
            "query_checksum" to sum, "stages" to stages)
    } finally { reader.close() }
}

fun isamJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to isamJson(it.value) })
    is Iterable<*> -> JsonArray(value.map(::isamJson))
    else -> error("Unexpected JSON boundary type ${value.javaClass.name}")
}

fun main(args: Array<String>) {
    val mode = IsamBackend.valueOf(args.getOrElse(0) { "emulated" }.uppercase())
    val rows = args.getOrElse(1) { "1024" }.toInt()
    val warmup = args.getOrElse(2) { "1" }.toInt()
    val iterations = args.getOrElse(3) { "3" }.toInt()
    val output = File(args.getOrElse(4) { "../results/isam-io.json" }).absoluteFile
    val placement = when (val value = args.getOrElse(5) { "off" }.uppercase()) {
        "TRUE" -> IsamTracePlacement.BOTH
        "FALSE" -> IsamTracePlacement.OFF
        else -> IsamTracePlacement.valueOf(value)
    }
    val ops = args.getOrElse(6) { "" }.split(',').filter { it.isNotBlank() }.map { UringOp.valueOf(it.uppercase()).name }.toSet()
    val phases = args.getOrElse(7) { "" }.split(',').filter { it.isNotBlank() }.toSet()
    val every = args.getOrElse(8) { "1" }.toInt()
    require(phases.all { it in setOf("setup", "write", "append", "open", "read_decode", "query_projection", "close") }) { "Unknown trace application phase" }
    val tracing = placement != IsamTracePlacement.OFF
    require(rows in 2..1000000 && warmup >= 0 && iterations > 0)
    require(!tracing || rows.toLong() * (warmup + iterations) * 32 < 2000000) { "Trace request bound exceeds 2 million events; reduce rows/iterations" }
    val trace = IsamIoTrace(placement, ops, phases, every)
    val samples = mutableListOf<Map<String, Any?>>()
    val report = linkedMapOf<String, Any?>("mode" to mode.name.lowercase(), "rows" to rows,
        "warmup" to warmup, "iterations" to iterations, "tracing" to tracing,
        "trace_controls" to mapOf("placement" to placement.name.lowercase(), "opcodes" to ops, "phases" to phases, "every" to every),
        "java" to System.getProperty("java.runtime.version"), "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"),
        "closure" to "Cursor -> IsamDataFile -> JvmIsamOperations -> userspace FileChannel/UringChannel -> FunctionalUringFacade -> backend -> real files",
        "timing_contract" to "Application intervals include serialization/decoding, facade work, backend IO and optional trace bookkeeping. Backend intervals enclose only real submitBatch. They are nested, not additive; their difference is not pure facade latency.",
        "trace_gaps" to "Synchronous ISAM does not exercise cancellation admission/drain; negative ECANCELED completions are counted if observed. Trace records backend admission after facade validation, not pre-validation rejects. STATX completion24 is the normalized metadata payload width; native kernel STATX CQE0 is translated by the backend. Independent expected-file verification and benchmark logging are outside workload IO.")
    var failure: Throwable? = null
    var activeLayout = false
    val directory = Files.createTempDirectory("trikeshed-isam-bench-").toFile()
    try {
        val probe = isamBackend(mode)
        report["backend_selection"] = mapOf("availability" to probe.availability, "capabilities" to probe.capabilities, "native_capabilities" to probe.nativeCapabilities)
        probe.close()
        for (grouped in arrayOf(false, true)) {
            activeLayout = grouped
            repeat(warmup + iterations) { index ->
                trace.iteration = index - warmup
                directory.listFiles()?.forEach { check(it.delete()) }
                val sample = isamIteration(rows, grouped, mode, directory, trace)
                if (index >= warmup) samples.add(sample + ("iteration" to index - warmup))
            }
        }
        report["status"] = "verified"
        report["samples"] = samples
    } catch (caught: Throwable) {
        failure = caught
        report["status"] = "failed"
        report["error"] = caught.toString()
        report["samples"] = emptyList<Any>() // failed runs are never candidates for timing comparison
        report["completed_samples_before_failure"] = samples.size
        trace.record(mapOf("event" to "application_failure", "error" to caught.toString()))
        if (!tracing || placement != IsamTracePlacement.BOTH || ops.isNotEmpty() || phases.isNotEmpty() || every != 1) {
            // A diagnostic replay never contributes a timing sample or changes the original failure.
            val diagnostic = IsamIoTrace(IsamTracePlacement.BOTH)
            try {
                isamBackend(mode).close()
                directory.listFiles()?.forEach { check(it.delete()) }
                isamIteration(rows, activeLayout, mode, directory, diagnostic)
                report["diagnostic_replay"] = "completed; original failed run remains invalid"
            } catch (replayed: Throwable) {
                diagnostic.record(mapOf("event" to "application_failure", "error" to replayed.toString()))
                report["diagnostic_replay"] = replayed.toString()
            }
            output.parentFile.mkdirs()
            val diagnosticPath = output.path + ".failure.trace.jsonl"
            File(diagnosticPath).bufferedWriter().use { writer -> diagnostic.events.forEach { writer.appendLine(isamJson(it).toString()) } }
            report["failure_trace"] = diagnosticPath
            report["diagnostic_trace_omitted_events"] = diagnostic.omittedEvents
        } else {
            report["failure_trace"] = output.path + ".trace.jsonl"
        }
    } finally {
        report["requests"] = trace.requests
        report["read_bytes"] = trace.readBytes
        report["written_bytes"] = trace.writtenBytes
        report["backend_errors"] = trace.errors
        report["cancelled_completions"] = trace.cancellations
        report["backend_channels"] = trace.backends
        report["trace_event_limit"] = trace.eventLimit
        report["trace_omitted_events"] = trace.omittedEvents
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), isamJson(report)) + "\n")
        if (tracing) File(output.path + ".trace.jsonl").bufferedWriter().use { writer ->
            trace.events.forEach { writer.appendLine(isamJson(it).toString()) }
        }
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }
    println("ISAM ${report["status"]}: ${output.path}")
    if (failure != null) { failure.printStackTrace(); exitProcess(2) }
}
