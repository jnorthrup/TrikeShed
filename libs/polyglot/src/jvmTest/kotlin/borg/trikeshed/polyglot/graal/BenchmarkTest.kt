package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * Cold-start benchmark: runs the same workload 100x and reports percentiles.
 * Run with: ./gradlew :libs:polyglot:jvmTest --tests "borg.trikeshed.polyglot.graal.BenchmarkTest*"
 */
class BenchmarkTest {

    private val times = mutableListOf<Long>()
    private val warmupTimes = mutableListOf<Long>()

    @RepeatedTest(5)
    fun warmup(rep: RepetitionInfo) {
        val start = System.nanoTime()
        runWorkload()
        val elapsed = System.nanoTime() - start
        warmupTimes.add(elapsed)
        println("[warmup ${rep.currentRepetition}/5] ${elapsed / 1_000_000}ms")
    }

    @RepeatedTest(100)
    fun coldStart(rep: RepetitionInfo) {
        val start = System.nanoTime()
        runWorkload()
        val elapsed = System.nanoTime() - start
        times.add(elapsed)
        if (rep.currentRepetition % 20 == 0) {
            println("[run ${rep.currentRepetition}/100] ${elapsed / 1_000_000}ms")
        }
    }

    @Test
    fun printPercentiles() {
        val sorted = times.sorted()
        val n = sorted.size
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║  TrikeShed Polyglot Cold-Start Benchmark (100 runs)         ║")
        println("╠══════════════════════════════════════════════════════════════╣")
        println("║  Min:     ${String.format("%8dms", sorted[0] / 1_000_000)}                                     ║")
        println("║  p50:     ${String.format("%8dms", sorted[n / 2] / 1_000_000)}                                     ║")
        println("║  p95:     ${String.format("%8dms", sorted[(n * 95 / 100)] / 1_000_000)}                                     ║")
        println("║  p99:     ${String.format("%8dms", sorted[(n * 99 / 100)] / 1_000_000)}                                     ║")
        println("║  Max:     ${String.format("%8dms", sorted[n - 1] / 1_000_000)}                                     ║")
        println("╚══════════════════════════════════════════════════════════════╝")
    }

    private fun runWorkload() {
        val emitCount = AtomicLong(0)
        val producer = object : PointcutEventProducer {
            override fun emit(synapse: FieldSynapse) { emitCount.incrementAndGet() }
            override fun emitBatch(synapses: Series<FieldSynapse>) { }
        }
        val h = GraalPointcutHarness(pointcutProducer = producer)
        try {
            val emitterType = h.eval("js", "typeof pointcutEmitter") as String
            check(emitterType == "object") { "emitter not bound: $emitterType" }
            
            val sumObj = h.eval("js", "let s=0; for(let i=0;i<1000;i++){s+=i;} s;")
            val sum = (sumObj as Number).toLong()
            check(sum == 499500L) { "sum mismatch: $sum" }
            
            h.eval("js", "pointcutEmitter.emitFieldAccess(0,false,false,'Bench','sum','bench:1',1);")
            h.eval("js", "pointcutEmitter.emitFieldAccess(1,false,true,'Bench','sum','bench:1',1);")
            check(emitCount.get() >= 2) { "expected 2 emits, got ${emitCount.get()}" }
        } finally {
            h.close()
        }
    }
}
