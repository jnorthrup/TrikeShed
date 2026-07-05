package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.atomic.AtomicLong

/**
 * Smoke benchmark: runs the workload once and asserts it completes in a sane time.
 * Formerly a cold-start benchmark with percentile reporting — removed percentile reporting because
 * JUnit @RepeatedTest instantiates a fresh class per repetition, making the
 * per-instance 'times' list always empty when printPercentiles() runs.
 * Run with: ./gradlew :libs:polyglot:jvmTest --tests "BenchmarkTest"
 */
class BenchmarkTest {

    @Test
    fun coldStartSmoke() {
        val start = System.nanoTime()
        runWorkload()
        val elapsed = System.nanoTime() - start
        val ms = elapsed / 1_000_000
        println("[Bench] cold-start workload completed in ${ms}ms")
        // Sanity bound: cold-start polyglot context + emit should complete under 5s.
        assertTrue(ms < 5_000, "Polyglot workload too slow: ${ms}ms")
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
        } finally { h.close() }
    }
}