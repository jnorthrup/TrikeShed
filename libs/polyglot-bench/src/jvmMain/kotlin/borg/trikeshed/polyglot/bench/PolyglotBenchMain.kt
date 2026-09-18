package borg.trikeshed.polyglot.bench

import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import borg.trikeshed.polyglot.graal.GraalPointcutHarness
import borg.trikeshed.polyglot.graal.OP_L_GET
import borg.trikeshed.polyglot.graal.OP_L_SET
import borg.trikeshed.polyglot.graal.PHASE_AFTER
import borg.trikeshed.polyglot.graal.PHASE_BEFORE
import borg.trikeshed.lib.Series
import java.util.concurrent.atomic.AtomicLong

/**
 * PolyglotBenchMain — minimal CLI mirroring `bun run bench.ts`:
 *
 *  1. Spin up a GraalVM polyglot context
 *  2. Install the pointcut emitter (FieldSynapse wireproto)
 *  3. Evaluate a small JS program that does 1000 iterations of a sum
 *  4. Emit one L_GET + one L_SET to prove the wire contract round-trips
 *  5. Print a single-line JSON result and exit
 *
 * Designed to be `native-image`-compiled so cold-start competes with Bun.
 */
fun main(args: Array<String>) {
    val iters = args.firstOrNull()?.toIntOrNull() ?: 1000

    // Capture-only producer. No console spam — every test in CI runs this
    // 100+ times and a single noisy line would be 100+ extra lines of output.
    val emitCount = AtomicLong(0)
    val producer = object : PointcutEventProducer {
        override fun emit(synapse: FieldSynapse) { emitCount.incrementAndGet() }
        override fun emitBatch(synapses: Series<FieldSynapse>) { /* no-op */ }
    }

    GraalPointcutHarness(pointcutProducer = producer).use { h ->
        // Step 1: verify emitter is bound (cheap: 1 attribute read)
        val emitterType = h.eval("js", "typeof pointcutEmitter")
        check(emitterType == "object") { "pointcutEmitter not bound: $emitterType" }

        // Step 2: do a small JS compute — the bun_bench.ts equivalent.
        val sum = h.eval("js", "let s=0; for(let i=0;i<$iters;i++){s+=i;} s;") as Long
        check(sum == ((iters.toLong() - 1) * iters / 2)) { "sum mismatch: $sum" }

        // Step 3: emit the wire-contract demo (BEFORE + AFTER pair)
        h.eval("js", """
            pointcutEmitter.emitFieldAccess(0, false, false, 'Bench', 'sum', 'bench:1', 1);
            pointcutEmitter.emitFieldAccess(1, false, true,  'Bench', 'sum', 'bench:1', 1);
        """.trimIndent())
        check(emitCount.get() >= 2) { "expected 2 emits, got ${emitCount.get()}" }
    }

    // Final line: structured for parsing. No timestamps or PIDs — pure determinism.
    println("""{"ok":true,"runtime":"trikeshed","iter":$iters,"emits":${emitCount.get()}}""")
}
