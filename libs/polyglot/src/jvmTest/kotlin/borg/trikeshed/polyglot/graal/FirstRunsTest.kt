package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class FirstRunsTest {
    @Test
    fun firstRuns() {
        repeat(5) { i ->
            val start = System.nanoTime()
            runWorkload()
            val elapsed = System.nanoTime() - start
            println("[cold ${i+1}/5] ${elapsed / 1_000_000}ms")
        }
        repeat(5) { i ->
            val start = System.nanoTime()
            runWorkload()
            val elapsed = System.nanoTime() - start
            println("[warm ${i+6}/10] ${elapsed / 1_000_000}ms")
        }
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
