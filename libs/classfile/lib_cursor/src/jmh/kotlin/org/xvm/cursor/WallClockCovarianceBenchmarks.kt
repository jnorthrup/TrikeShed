@file:Suppress("UNCHECKED_CAST")

package org.xvm.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.RingSeries
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * JMH wall-clock covariance benchmarks for all MutableSeries types.
 *
 * Unlike [PointcutBenchmarks] (throughput / sample-time), these measure the
 * Pearson correlation coefficient r between event sequence position (X) and
 * wall-clock timestamp (Y) collected under JMH's controlled conditions:
 *   - Forked JVM with warmed JIT
 *   - GC between forks (isolates GC noise)
 *   - Multiple iterations for variance estimation
 *
 * Covariance is the canonical probe for algorithmic jitter:
 *   r ≈ 1.0  → linear wall-clock growth (predictable, uniform latency)
 *   r < 0.95 → non-linear timing artifacts (GC, COW, compaction spikes)
 *
 * The benchmark drives the hot path; statistical helpers compute r offline.
 *
 * Run with:
 *   ./gradlew :lib_cursor:jmh              # full JMH suite
 *   ./gradlew :lib_cursor:jmh -PjmhArgs=".*ringSeries_covariance.*"   # single type
 *
 * Warmup: 3 forks × 2 iterations × 5s
 * Measurement: 3 forks × 3 iterations × 5s
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(
    iterations = 2,
    time = 5,
    timeUnit = TimeUnit.SECONDS,
    batchSize = 1
)
@Measurement(
    iterations = 3,
    time = 5,
    timeUnit = TimeUnit.SECONDS,
    batchSize = 1
)
@Fork(
    value = 3,
    jvmArgs = [
        "-Xms4g",
        "-Xmx4g",
        "-XX:+UseG1GC",
        "-XX:+AlwaysPreTouch",
        "-XX:+DisableExplicitGC"
    ]
)
@Threads(1)
@Timeout(time = 20, timeUnit = TimeUnit.MINUTES)
open class WallClockCovarianceBenchmarks {

    // ── Shared event record ─────────────────────────────────────────────────

    data class PointcutEvent(
        val seq: Int,
        val nano: Long,
        val opcode: String,
        val addr: Long,
        val cls: String,
    ) {
        companion object {
            private val opcodes = listOf(
                "LOAD", "STORE", "BRANCH", "CALL", "RETURN", "ADD", "SUB", "MULT",
                "DIV", "AND", "OR", "XOR", "SHIFT_L", "SHIFT_R", "CBRANCH", "LABEL"
            )
            fun random(seq: Int): PointcutEvent = PointcutEvent(
                seq = seq,
                nano = java.lang.System.nanoTime(),
                opcode = opcodes[seq % opcodes.size],
                addr = (Math.random() * 0x10000).toLong(),
                cls = "Lorg/xvm/runtime/Frame;"
            )
        }
    }

    // ── Statistical helpers (computed offline from captured events) ─────────

    fun pearsonR(events: Series<PointcutEvent>): Double {
        if (events.size < 3) return Double.NaN
        val n = events.size.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        for (e in events) {
            sumX += e.seq.toDouble()
            sumY += e.nano.toDouble()
        }
        val meanX = sumX / n
        val meanY = sumY / n
        var num = 0.0
        var denX2 = 0.0
        var denY2 = 0.0
        for (e in events) {
            val dx = e.seq.toDouble() - meanX
            val dy = e.nano.toDouble() - meanY
            num += dx * dy
            denX2 += dx * dx
            denY2 += dy * dy
        }
        val den = kotlin.math.sqrt(denX2 * denY2)
        return if (den == 0.0) Double.NaN else num / den
    }

    fun interArrivalStdev(events: Series<PointcutEvent>): Double {
        if (events.size < 2) return Double.NaN
        val deltas = events.zipWithNext().α { (a, b) -> (b.nano - a.nano).toDouble() }
        val mean = deltas.fold(0.0) { acc, v -> acc + v } / deltas.size
        val variance = deltas.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / (deltas.size - 1)
        return kotlin.math.sqrt(variance)
    }

    // ── Blackhole-safe benchmarks: r is consumed, jitter is consumed ───────

    @Benchmark
    fun ringSeries_covariance(bh: Blackhole) {
        val ring = RingSeries<PointcutEvent>(65536)
        val captured = ArrayList<PointcutEvent>(50_000)
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())
        }
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    @Benchmark
    fun ringSeries_1k_covariance(bh: Blackhole) {
        val ring = RingSeries<PointcutEvent>(1024)
        val captured = ArrayList<PointcutEvent>(10_000)
        repeat(10_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())
        }
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured as Series<PointcutEvent>))
    }

    @Benchmark
    fun ringSeries_withEviction_covariance(bh: Blackhole) {
        var evicted = 0
        val ring = RingSeries<PointcutEvent>(256) { evicted++ }
        val captured = ArrayList<PointcutEvent>(100_000)
        repeat(100_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())
        }
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured ))
        bh.consume(evicted)
    }

    @Benchmark
    fun mergeMutableSeries_belowThreshold_covariance(bh: Blackhole) {
        val m = MergeMutableSeries<PointcutEvent>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val captured = ArrayList<PointcutEvent>(5_000)
        repeat(5_000) { i ->
            captured.add(PointcutEvent.random(i))
            m.add(captured.last())
        }
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    @Benchmark
    fun mergeMutableSeries_throughCompaction_covariance(bh: Blackhole) {
        val m = MergeMutableSeries<PointcutEvent>(mergeThreshold = 1024) { a, b -> a.opcode.compareTo(b.opcode) }
        val captured = ArrayList<PointcutEvent>(50_000)
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            m.add(captured.last())
        }
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    @Benchmark
    fun journalSeries_covariance(bh: Blackhole) {
        val j = JournalSeries<PointcutEvent>()
        val captured = ArrayList<PointcutEvent>(50_000)
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            j.add(captured.last())
        }
        bh.consume(pearsonR(captured  as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    @Benchmark
    fun journalSeries_commit_covariance(bh: Blackhole) {
        val j = JournalSeries<PointcutEvent>()
        val captured = ArrayList<PointcutEvent>(5_000)
        repeat(5_000) { i ->
            captured.add(PointcutEvent.random(i))
            j.add(captured.last())
        }
        j.commit()
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    @Benchmark
    fun journalSeries_rollback_covariance(bh: Blackhole) {
        val j = JournalSeries<PointcutEvent>()
        repeat(5_000) { j.add(PointcutEvent.random(it)) }
        val captured = ArrayList<PointcutEvent>(5_000)
        repeat(5_000) { i ->
            captured.add(PointcutEvent.random(i))
            j.add(captured.last())
        }
        j.rollback()
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    @Benchmark
    fun chunkedMutableSeries_covariance(bh: Blackhole) {
        val c = ChunkedMutableSeries<PointcutEvent>(chunkSize = 4096)
        val captured = ArrayList<PointcutEvent>(50_000)
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            c.add(captured.last())
        }
        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    // ── 3-stage pipeline: RingSeries → MergeMutableSeries → JournalSeries ──

    @Benchmark
    fun pipeline_ringMergeJournal_covariance(bh: Blackhole) {
        val ring = RingSeries<PointcutEvent>(65536)
        val merge = MergeMutableSeries<PointcutEvent>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val journal = JournalSeries<PointcutEvent>()

        val captured = ArrayList<PointcutEvent>(50_000)

        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())

            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring.b(it)) }
                ring.clear()
                merge.flush()
            }
        }

        repeat(merge.a) { journal.add(merge.b(it)) }
        journal.commit()

        bh.consume(pearsonR(captured  as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }

    // ── 4-stage pipeline: RingSeries → ChunkedMutableSeries → MergeMutableSeries → JournalSeries ──

    @Benchmark
    fun burrito_4layer_covariance(bh: Blackhole) {
        val layer4_journal = JournalSeries<PointcutEvent>()
        val layer3_merge = MergeMutableSeries<PointcutEvent>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val layer2_chunked = ChunkedMutableSeries<PointcutEvent>(chunkSize = 4096)
        val layer1_ring = RingSeries<PointcutEvent>(65536)

        val captured = ArrayList<PointcutEvent>(50_000)

        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            layer1_ring.add(captured.last())

            if (i > 0 && i % 256 == 0) {
                repeat(layer1_ring.a) { layer2_chunked.add(layer1_ring.b(it)) }
                layer1_ring.clear()

                if (i % 4096 == 0) {
                    repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked.b(it)) }
                    layer2_chunked.clear()
                    layer3_merge.flush()
                }
            }
        }

        if (layer2_chunked.a > 0) {
            repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked.b(it)) }
            layer2_chunked.clear()
            layer3_merge.flush()
        }

        repeat(layer3_merge.a) { layer4_journal.add(layer3_merge.b(it)) }
        layer4_journal.commit()

        bh.consume(pearsonR(captured as Series<PointcutEvent>))
        bh.consume(interArrivalStdev(captured))
    }
}
