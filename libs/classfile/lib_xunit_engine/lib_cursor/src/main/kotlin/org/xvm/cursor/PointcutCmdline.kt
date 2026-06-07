package org.xvm.cursor

import org.xvm.api.InterpreterConnector
import org.xvm.asm.ConstantPool
import org.xvm.asm.DirRepository
import org.xvm.asm.FileRepository
import org.xvm.asm.LinkedRepository
import org.xvm.runtime.CascadeRollup
import org.xvm.runtime.FieldSynapse
import org.xvm.runtime.PointcutDrain
import org.xvm.runtime.PointcutObservation
import org.xvm.runtime.TypedefCascadeTable
import org.xvm.runtime.VmPointcutPublisher
import org.xvm.runtime.XvmLifecycle
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime

private fun findRepoRoot(): File {
    var dir = File(System.getProperty("user.dir")).canonicalFile
    while (!dir.resolve("settings.gradle.kts").isFile) {
        dir = dir.parentFile ?: error("Could not locate repo root from ${System.getProperty("user.dir")}")
    }
    return dir
}

private val REPO_ROOT = findRepoRoot()
private val XDK_LIB = REPO_ROOT.resolve("xdk/build/install/xdk/lib")
private val XDK_JAVATOOLS = REPO_ROOT.resolve("xdk/build/install/xdk/javatools")
private val FIZZBUZZ_XTC = REPO_ROOT.resolve("manualTests/build/xtc/main/lib/FizzBuzz.xtc")

private data class TimerBlock(
    val label: String,
    val startNanos: Long,
    val endNanos: Long,
    val events: Int,
) {
    val durationMs = (endNanos - startNanos) / 1_000_000.0
    val eventsPerSec = if (durationMs > 0.0) events / (durationMs / 1000.0) else 0.0

    fun dump() {
        println("  ┌── $label ──")
        println("  │  wall: ${"%.3f".format(durationMs)}ms")
        println("  │  events: $events")
        println("  │  rate: ${"%.0f".format(eventsPerSec)} events/sec")
        println("  └──")
    }
}

private val batchCounts = mutableListOf<Pair<Long, Int>>()  // epoch, count

private fun subscribeFieldObservation(): Int = PointcutObservation.subscribe { source, count, epoch ->
    if (source != PointcutObservation.Source.FIELD) {
        return@subscribe
    }
    batchCounts.add(epoch to count)
}

fun main(args: Array<String>) {
    val modes = if (args.isEmpty() || args.contentEquals(arrayOf("all"))) {
        listOf("redux", "synapse", "xvm")
    } else {
        args.toList()
    }

    for (mode in modes) {
        when (mode) {
            "redux", "redux-standalone" -> runReduxStandalone()
            "synapse", "synapse-standalone" -> runSynapseStandalone()
            "xvm", "xvm-fizzbuzz" -> runXvmFizzBuzz()
            else -> error("Unknown pointcut mode: $mode")
        }
    }
}

private fun runXvmFizzBuzz() {
    check(XDK_LIB.isDirectory) { "Missing XDK lib directory: ${XDK_LIB.absolutePath}" }
    check(XDK_JAVATOOLS.isDirectory) { "Missing XDK javatools directory: ${XDK_JAVATOOLS.absolutePath}" }
    check(FIZZBUZZ_XTC.isFile) { "Missing FizzBuzz module: ${FIZZBUZZ_XTC.absolutePath}" }

    println("═══════════════════════════════════════════════════════")
    println("  PointcutCmdline: Redux timeseries + XVM launch")
    println("═══════════════════════════════════════════════════════")
    println()

    val lifecycle = XvmLifecycle()
    batchCounts.clear()
    val fieldObservationId = subscribeFieldObservation()

    VmPointcutPublisher.reset()
    VmPointcutPublisher.active = true

    FieldSynapse.reset()
    FieldSynapse.active = true

    try {
        val connectNanos = measureNanoTime {
            val xdkRepo = DirRepository(XDK_LIB, true)
            val javatoolsRepo = DirRepository(XDK_JAVATOOLS, true)
            val fizzRepo = FileRepository(FIZZBUZZ_XTC, true)
            val repo = LinkedRepository(false, xdkRepo, javatoolsRepo, fizzRepo)
            val connector = InterpreterConnector(repo)
            connector.loadModule("TestFizzBuzz")
            val pool = connector.getConstantPool()
            ConstantPool.withPool(pool).use {
                val methods = connector.findMethods("run")
                check(methods.size == 1) { "Expected exactly one run method, found ${methods.size}" }
                val method = methods.iterator().next()
                lifecycle.start()

                val runNanos = measureNanoTime {
                    connector.start(null)
                    connector.invoke0(method)
                    connector.join()
                }
                TimerBlock("XVM FizzBuzz execution", 0L, runNanos, VmPointcutPublisher.size()).dump()

                val table = TypedefCascadeTable(8192)
                val drainStart = System.nanoTime()
                VmPointcutPublisher.drain { evt ->
                    table.routeOpcode(evt.opcode, evt.methodName(), evt.addr)
                }
                val drainEnd = System.nanoTime()

                println("  [drain] ${table.rowCount()} rows → cascade table in ${(drainEnd - drainStart) / 1_000_000.0}ms")
                check(table.rowCount() > 0) { "Pointcut ring captured no events from FizzBuzz" }

                val rollupStart = System.nanoTime()
                val snapshots = CascadeRollup.cascadeRollup(table)
                val rollupEnd = System.nanoTime()

                println("  [rollup] 4-tier cascade in ${(rollupEnd - rollupStart) / 1_000_000.0}ms")
                dumpCascadeSnapshots(snapshots)

                FieldSynapse.flush("final")

                val dumpDir = Files.createTempDirectory("pointcut-cmdline-")
                val drain = PointcutDrain(lifecycle, table, dumpDir)
                drain.drain()
                drain.shutdown()

                println()
                dumpArtifacts(dumpDir)
                println()
                dumpSynapseBatches()

                check(lifecycle.isShutdown) { "Lifecycle did not reach SHUTDOWN" }
                check(dumpDir.resolve("cascade_joint.bin").toFile().exists()) { "Missing cascade_joint.bin" }
                check(dumpDir.resolve("joint_histogram.bin").toFile().exists()) { "Missing joint_histogram.bin" }
                check(dumpDir.resolve("table_dump.bin").toFile().exists()) { "Missing table_dump.bin" }
                check(snapshots[3].totalEvents > 0) { "T4 joint histogram was empty" }
            }
        }

        println()
        println("  Total connect+run: ${connectNanos / 1_000_000.0}ms")
    } finally {
        PointcutObservation.unsubscribe(fieldObservationId)
        VmPointcutPublisher.reset()
        FieldSynapse.reset()
    }
}

private fun runSynapseStandalone() {
    println("═══════════════════════════════════════════════════════")
    println("  PointcutCmdline: Synapse spiking model benchmark")
    println("═══════════════════════════════════════════════════════")
    println()

    FieldSynapse.reset()
    FieldSynapse.active = true
    batchCounts.clear()
    val fieldObservationId = subscribeFieldObservation()

    val eventCount = 50_000
    val methods = arrayOf("FieldAccess.get", "DataStore.put", "Cache.invalidate", "Index.lookup")

    val burstStart = System.nanoTime()
    for (i in 0 until eventCount) {
        val opcode = 0xA5 + (i % 4)
        val method = methods[i % methods.size]
        val isAfter = i % 2 == 1
        FieldSynapse.publishStatic(opcode, method, 100 + (i % 256), isAfter)
    }
    FieldSynapse.flush("burst-end")
    val burstEnd = System.nanoTime()
    TimerBlock("Synapse burst ($eventCount events)", burstStart, burstEnd, eventCount).dump()

    val reifyStart = System.nanoTime()
    var totalReified = 0
    FieldSynapse.drain { fs ->
        val reified = fs.reify()
        check(reified.isNotEmpty()) { "Reified field synapse string was empty" }
        totalReified++
    }
    val reifyEnd = System.nanoTime()
    TimerBlock("Ring reification ($totalReified)", reifyStart, reifyEnd, totalReified).dump()

    FieldSynapse.reset()
    FieldSynapse.active = true
    for (i in 0 until 10_000) {
        FieldSynapse.publishStatic(0xA5 + (i % 4), methods[i % methods.size], 100 + (i % 256), i % 2 == 1)
    }

    val wireStart = System.nanoTime()
    val wireBuf = FieldSynapse.drainToWireproto()
    val wireEnd = System.nanoTime()

    val wireEvents = wireBuf.remaining() / FieldSynapse.RECORD_SIZE
    TimerBlock(
        "Wireproto encode ($wireEvents records, ${wireBuf.remaining()}B)",
        wireStart,
        wireEnd,
        wireEvents,
    ).dump()

    val expectedWireEvents = 10_000 % FieldSynapse.SLAB_SIZE
    check(wireBuf.remaining() == expectedWireEvents * FieldSynapse.RECORD_SIZE) {
        "Expected $expectedWireEvents wireproto records, found $wireEvents"
    }

    println()
    dumpSynapseBatches()

    PointcutObservation.unsubscribe(fieldObservationId)
    FieldSynapse.reset()
}

private fun runReduxStandalone() {
    println("═══════════════════════════════════════════════════════")
    println("  PointcutCmdline: Redux cascade standalone benchmark")
    println("═══════════════════════════════════════════════════════")
    println()

    VmPointcutPublisher.reset()
    VmPointcutPublisher.active = true

    val eventCount = VmPointcutPublisher.ring().cap()
    val opcodes = intArrayOf(
        0x10, 0x14, 0x18, 0x1C,
        0x20, 0x25, 0x2A, 0x2F,
        0x38, 0x40, 0x48,
        0x4C, 0x4D, 0x4E, 0x4F,
        0x65, 0x66,
        0x77, 0x78,
        0x90,
        0xA5, 0xA6, 0xA7, 0xA8,
    )
    val methods = arrayOf("pkg.Class.methodName()A", "pkg.Class.methodName()B", "pkg.Class.methodName()C")

    val pubStart = System.nanoTime()
    for (i in 0 until eventCount) {
        VmPointcutPublisher.publish(
            opcodes[i % opcodes.size],
            methods[i % methods.size],
            50 + (i % 200),
        )
    }
    val pubEnd = System.nanoTime()
    TimerBlock("Ring publish ($eventCount events)", pubStart, pubEnd, eventCount).dump()

    val table = TypedefCascadeTable(eventCount + 256)
    val drainStart = System.nanoTime()
    VmPointcutPublisher.drain { evt ->
        table.routeOpcode(evt.opcode, evt.methodName(), evt.addr)
    }
    val drainEnd = System.nanoTime()
    TimerBlock("Ring drain → cascade", drainStart, drainEnd, table.rowCount()).dump()
    check(table.rowCount() == eventCount) { "Expected $eventCount cascade rows, found ${table.rowCount()}" }

    val rollupStart = System.nanoTime()
    val snapshots = CascadeRollup.cascadeRollup(table)
    val rollupEnd = System.nanoTime()
    TimerBlock("4-tier cascade rollup", rollupStart, rollupEnd, table.rowCount()).dump()
    dumpCascadeSnapshots(snapshots)

    val dumpDir = Files.createTempDirectory("pointcut-standalone-")
    val lifecycle = XvmLifecycle()
    lifecycle.start()

    val fileStart = System.nanoTime()
    val drain = PointcutDrain(lifecycle, table, dumpDir)
    drain.drain()
    drain.shutdown()
    val fileEnd = System.nanoTime()
    TimerBlock("File artifact write", fileStart, fileEnd, table.rowCount()).dump()

    println()
    dumpArtifacts(dumpDir)

    check(snapshots[3].totalEvents == eventCount.toLong()) {
        "Expected ${eventCount.toLong()} total T4 events, found ${snapshots[3].totalEvents}"
    }
    check(dumpDir.resolve("cascade_joint.bin").toFile().exists()) { "Missing cascade_joint.bin" }
    check(dumpDir.resolve("table_dump.bin").toFile().exists()) { "Missing table_dump.bin" }
    check(lifecycle.isShutdown) { "Lifecycle did not reach SHUTDOWN" }

    VmPointcutPublisher.reset()
}

private fun dumpCascadeSnapshots(snapshots: Array<CascadeRollup.TierSnapshot>) {
    val kindNames = arrayOf("CALL", "ALLOC", "RETURN", "FIELD", "TYPE", "ASSERT", "LOOP", "SYNC", "GAP")
    val scopeNames = arrayOf("MODULE", "PACKAGE", "CLASS", "METHOD")

    println()
    println("  ┌── Cascade Tier Snapshots ──")
    for (tier in snapshots) {
        println("  │  T${tier.tier}: total=${tier.totalEvents}")
    }

    println("  │")
    println("  │  Kind histogram:")
    val t2 = snapshots[1]
    for (k in 0 until TypedefCascadeTable.KIND_COUNT) {
        if (t2.buckets[k] > 0) {
            println("  │    ${kindNames[k].padEnd(8)} ${t2.buckets[k]}")
        }
    }

    println("  │")
    println("  │  Scope histogram:")
    val t3 = snapshots[2]
    for (s in 0 until TypedefCascadeTable.SCOPE_COUNT) {
        if (t3.buckets[s] > 0) {
            println("  │    ${scopeNames[s].padEnd(8)} ${t3.buckets[s]}")
        }
    }

    println("  │")
    println("  │  Joint histogram (kind × scope):")
    val t4 = snapshots[3]
    val joint = t4.jointHistogram
    if (joint != null) {
        val scopeCount = TypedefCascadeTable.SCOPE_COUNT
        print("  │    ")
        for (s in 0 until scopeCount) {
            print("${scopeNames[s].padEnd(8)} ")
        }
        println()
        for (k in 0 until TypedefCascadeTable.KIND_COUNT) {
            print("  │    ")
            for (s in 0 until scopeCount) {
                val value = joint[k * scopeCount + s]
                print("${value.toString().padEnd(8)} ")
            }
            println(" ${kindNames[k]}")
        }
    }
    println("  └──")
}

private fun dumpArtifacts(dir: Path) {
    println("  ┌── File Artifacts (${dir.toAbsolutePath()}) ──")
    for (file in dir.toFile().listFiles()?.sortedBy { it.name } ?: emptyList()) {
        val lines = file.readLines()
        println("  │  ${file.name}: ${lines.size} lines, ${file.length()} bytes")
        if (lines.size <= 10) {
            for (line in lines) {
                println("  │    $line")
            }
        } else {
            for (line in lines.take(5)) {
                println("  │    $line")
            }
            println("  │    ... (${lines.size - 5} more)")
        }
    }
    println("  └──")
}

private fun dumpSynapseBatches() {
    println("  ┌── FieldSynapse Batches ──")
    println("  │  total batches: ${batchCounts.size}")
    var totalEvents = 0
    for ((idx, batch) in batchCounts.withIndex()) {
        totalEvents += batch.second
        println("  │  batch[$idx]: epoch=${batch.first}, count=${batch.second}")
    }
    println("  │  total synapse events: $totalEvents")
    println("  └──")
}
