package org.xvm.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.RingSeries as LibRingSeries
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.*
import java.util.concurrent.TimeUnit

/**
 * JMH benchmarks for the pointcut → RingSeries → debounce → MutableSeries pipeline.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(
    iterations = 3,
    time = 10,
    timeUnit = TimeUnit.SECONDS,
    batchSize = 1
)
@Measurement(
    iterations = 5,
    time = 10,
    timeUnit = TimeUnit.SECONDS,
    batchSize = 1
)
@Fork(
    value = 3,
    jvmArgs = ["-Xms4g", "-Xmx4g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"]
)
@Threads(1)
@Timeout(time = 30, timeUnit = TimeUnit.MINUTES)
open class PointcutBenchmarks {

    // ── Event record (same wire format as pointcut emitter) ───────────

    data class EventRecord(
        val cls: String,
        val addr: Long,
        val opcode: Int,
        val nano: Long
    )

    private val opcodes = listOf(
        "LOAD", "STORE", "BRANCH", "CALL", "RETURN", "ADD", "SUB", "MULT",
        "DIV", "AND", "OR", "XOR", "SHIFT_L", "SHIFT_R", "CBRANCH", "LABEL"
    )

    private fun randomEvent(): EventRecord {
        return EventRecord(
            cls = "Lorg/xvm/runtime/Frame;",
            addr = (Math.random() * 0x10000).toLong(),
            opcode = (Math.random() * opcodes.size).toInt(),
            nano = java.lang.System.nanoTime()
        )
    }

    // ── RingSeries benchmarks ─────────────────────────────────────────────────

    @Benchmark
    fun ringAppend_1k(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(1024)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_4k(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(4096)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_16k(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(16384)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_64k(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(65536)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_withEviction(bh: Blackhole) {
        var evicted = 0
        val ring = LibRingSeries<EventRecord>(256) { evicted++ }
        repeat(100_000) { ring.add(randomEvent()) }
        bh.consume(evicted)
    }

    @Benchmark
    fun ringAppend_1k_readEveryGet(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(1024)
        repeat(100_000) {
            ring.add(randomEvent())
            if (it % 100 == 0) bh.consume(ring[it % 1024])
        }
    }

    // ── MergeMutableSeries benchmarks ────────────────────────────────────────

    @Benchmark
    fun mergeMutable_appendBelowThreshold(bh: Blackhole) {
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(63) { merge.add(randomEvent()) }
        bh.consume(merge.a)
    }

    @Benchmark
    fun mergeMutable_appendThroughThreshold(bh: Blackhole) {
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(128) { merge.add(randomEvent()) }
        bh.consume(merge.a)
    }

    @Benchmark
    fun mergeMutable_flush(bh: Blackhole) {
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 1024) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(4096) { merge.add(randomEvent()) }
        merge.flush()
        bh.consume(merge.a)
    }

    // ── RecursiveMutableSeries (COW) benchmarks ────────────────────────────────

    @Benchmark
    fun recursiveCOW_append10(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(10) { s.add(randomEvent()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun recursiveCOW_append1k(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(randomEvent()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun recursiveCOW_append10k(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(10_000) { s.add(randomEvent()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun recursiveCOW_setThrough10k(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(5000) { s.add(randomEvent()) }
        repeat(5000) { s.set(it % 5000, randomEvent()) }
        bh.consume(s.a)
    }

    // ── JournalSeries benchmarks ───────────────────────────────────────────────

    @Benchmark
    fun journal_append10_commit(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(10) { j.add(randomEvent()) }
        j.commit()
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_append1k_rollback(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(randomEvent()) }
        j.rollback()
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_append1k_commit(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(randomEvent()) }
        j.commit()
        bh.consume(j.a)
    }

    // ── ChunkedMutableSeries benchmarks ────────────────────────────────────────

    @Benchmark
    fun chunkedAppend_1k(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(randomEvent()) }
        bh.consume(c.a)
    }

    @Benchmark
    fun chunkedAppend_10k(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(10_000) { c.add(randomEvent()) }
        bh.consume(c.a)
    }

    // ── Full debounce pipeline benchmarks ──────────────────────────────────────

    @Benchmark
    fun pipeline_debounce_firehose(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(65536)
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }

        repeat(100_000) { i ->
            ring.add(randomEvent())
            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring[it]) }
                ring.clear()
            }
        }
        repeat(ring.a) { merge.add(ring[it]) }
        merge.flush()
        bh.consume(merge.a)
    }

    @Benchmark
    fun pipeline_full_3stage(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(65536)
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val journal = JournalSeries<EventRecord>()

        repeat(10_000) { i ->
            ring.add(randomEvent())
            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring[it]) }
                ring.clear()
                merge.flush()
            }
        }
        repeat(merge.a) { journal.add(merge[it]) }
        journal.commit()
        bh.consume(journal.a)
    }

    @Benchmark
    fun pipeline_full_withRollback(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(65536)
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val journal = JournalSeries<EventRecord>()

        repeat(10_000) { i ->
            ring.add(randomEvent())
            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring[it]) }
                ring.clear()
                merge.flush()
            }
        }
        repeat(merge.a) { journal.add(merge[it]) }
        journal.rollback()
        bh.consume(journal.a)
    }

    // ── Redux 5-layer burrito delegate chain ────────────────────────────────────

    @Benchmark
    fun burrito_5layer_firehose(bh: Blackhole) {
        val layer5_rms = RecursiveMutableSeries.create<EventRecord>()
        val layer4_journal = JournalSeries<PointcutBenchmarks.EventRecord>()
        val layer3_merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val layer2_chunked = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        val layer1_ring = LibRingSeries<EventRecord>(65536)

        repeat(50_000) { i ->
            layer1_ring.add(randomEvent())

            if (i > 0 && i % 256 == 0) {
                repeat(layer1_ring.a) { layer2_chunked.add(layer1_ring[it]) }
                layer1_ring.clear()

                if (i % 4096 == 0) {
                    repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
                    layer2_chunked.clear()
                    layer3_merge.flush()
                }
            }
        }

        if (layer2_chunked.a > 0) {
            repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
            layer2_chunked.clear()
            layer3_merge.flush()
        }

        repeat(layer3_merge.a) { layer4_journal.add(layer3_merge[it]) }
        layer4_journal.commit()

        bh.consume(layer4_journal.a)
    }

    @Benchmark
    fun burrito_5layer_withRollback(bh: Blackhole) {
        val layer5_rms = RecursiveMutableSeries.create<EventRecord>()
        val layer4_journal = JournalSeries<PointcutBenchmarks.EventRecord>()
        val layer3_merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val layer2_chunked = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        val layer1_ring = LibRingSeries<EventRecord>(65536)

        repeat(50_000) { i ->
            layer1_ring.add(randomEvent())
            if (i > 0 && i % 256 == 0) {
                repeat(layer1_ring.a) { layer2_chunked.add(layer1_ring[it]) }
                layer1_ring.clear()
                if (i % 4096 == 0) {
                    repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
                    layer2_chunked.clear()
                    layer3_merge.flush()
                }
            }
        }
        if (layer2_chunked.a > 0) {
            repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
            layer2_chunked.clear()
            layer3_merge.flush()
        }
        repeat(layer3_merge.a) { layer4_journal.add(layer3_merge[it]) }
        layer4_journal.rollback()
        bh.consume(layer4_journal.a)
    }

    // ── Comparison: RingSeries vs COW at firehose ──────────────────────────────

    @Benchmark
    fun comparison_ring_vs_recursive_cow(bh: Blackhole) {
        val ring = LibRingSeries<EventRecord>(65536)
        repeat(100_000) { ring.add(randomEvent()) }

        val cow = RecursiveMutableSeries.create<EventRecord>()
        repeat(100_000) { cow.add(randomEvent()) }

        bh.consume(ring.a)
        bh.consume(cow.a)
    }
}