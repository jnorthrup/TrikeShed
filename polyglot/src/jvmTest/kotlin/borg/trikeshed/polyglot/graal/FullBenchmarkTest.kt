package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Single benchmark test that runs 100 iterations and reports percentiles.
 * Run with: ./gradlew :libs:polyglot:jvmTest --tests "borg.trikeshed.polyglot.graal.FullBenchmark*"
 */
class FullBenchmarkTest {

    @Test
    fun benchmark100Runs() {
        val times = mutableListOf<Long>()
        
        // 5 warmup runs
        repeat(5) {
            val start = System.nanoTime()
            runWorkload()
            val elapsed = System.nanoTime() - start
            println("[warmup ${it+1}/5] ${elapsed / 1_000_000}ms")
        }
        
        // 100 measurement runs
        repeat(100) { i ->
            val start = System.nanoTime()
            runWorkload()
            val elapsed = System.nanoTime() - start
            times.add(elapsed)
            if ((i + 1) % 20 == 0) {
                println("[run ${i+1}/100] ${elapsed / 1_000_000}ms")
            }
        }
        
        // Percentiles
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