package org.xvm.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.RingSeries
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.*
import java.util.concurrent.TimeUnit

/**
 * Full CRUD benchmarks for the VM event pipeline.
 * Tests create/read/update/delete on all MutableSeries types used in the burrito chain.
 * These run alongside PointcutBenchmarks — both share the same EventRecord wire format.
 *
 * Run with:
 *   ./gradlew jmh -Pjmh=true          # JMH mode
 *   ./gradlew jvmTest -Pjmh=false     # JUnit mode (excludes JMH compilation)
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = ["-Xms2g", "-Xmx2g", "-XX:+UseG1GC"])
@Threads(1)
open class VmCrudBenchmarks {

    data class EventRecord(
        val cls: String,
        val addr: Long,
        val opcode: Int,
        val nano: Long
    ) {
        companion object {
            private val opcodes = listOf(
                "LOAD", "STORE", "BRANCH", "CALL", "RETURN", "ADD", "SUB", "MULT",
                "DIV", "AND", "OR", "XOR", "SHIFT_L", "SHIFT_R", "CBRANCH", "LABEL"
            )
            fun random() = EventRecord(
                cls = "Lorg/xvm/runtime/Frame;",
                addr = (Math.random() * 0x10000).toLong(),
                opcode = (Math.random() * opcodes.size).toInt(),
                nano = java.lang.System.nanoTime()
            )
        }
    }

    // ── RingSeries CRUD ────────────────────────────────────────────────────────

    @Benchmark
    fun ring_create(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(4096)
        repeat(1000) { ring.add(EventRecord.random()) }
        bh.consume(ring.a)
    }

    @Benchmark
    fun ring_read_by_index(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(4096)
        repeat(1000) { ring.add(EventRecord.random()) }
        repeat(1000) { bh.consume(ring[it % 1000]) }
    }

    @Benchmark
    fun ring_update_by_index(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(4096)
        repeat(1000) { ring.add(EventRecord.random()) }
        repeat(1000) { ring[it % 1000] = EventRecord.random() }
        bh.consume(ring.a)
    }

    @Benchmark
    fun ring_delete_at_tail(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(4096)
        repeat(1000) { ring.add(EventRecord.random()) }
        repeat(500) { ring.removeAt(ring.a - 1) }
        bh.consume(ring.a)
    }

    @Benchmark
    fun ring_delete_at_head(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(4096)
        repeat(1000) { ring.add(EventRecord.random()) }
        repeat(500) { ring.removeAt(0) }
        bh.consume(ring.a)
    }

    // ── RecursiveMutableSeries (COW) CRUD ──────────────────────────────────────

    @Benchmark
    fun cow_create(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(EventRecord.random()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun cow_read_by_index(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(EventRecord.random()) }
        repeat(1000) { bh.consume(s[it % 1000]) }
    }

    @Benchmark
    fun cow_update_by_index(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(EventRecord.random()) }
        repeat(1000) { s[it % 1000] = EventRecord.random() }
        bh.consume(s.a)
    }

    @Benchmark
    fun cow_delete_at_tail(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(EventRecord.random()) }
        repeat(500) { s.removeAt(s.a - 1) }
        bh.consume(s.a)
    }

    @Benchmark
    fun cow_delete_at_head(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(EventRecord.random()) }
        repeat(500) { s.removeAt(0) }
        bh.consume(s.a)
    }

    // ── MergeMutableSeries CRUD ───────────────────────────────────────────────

    @Benchmark
    fun merge_create(bh: Blackhole) {
        val m = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(1000) { m.add(EventRecord.random()) }
        bh.consume(m.a)
    }

    @Benchmark
    fun merge_read_by_index(bh: Blackhole) {
        val m = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(1000) { m.add(EventRecord.random()) }
        repeat(1000) { bh.consume(m[it % 1000]) }
    }

    @Benchmark
    fun merge_update_by_index(bh: Blackhole) {
        val m = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(1000) { m.add(EventRecord.random()) }
        repeat(1000) { m[it % 1000] = EventRecord.random() }
        bh.consume(m.a)
    }

    @Benchmark
    fun merge_delete_by_value(bh: Blackhole) {
        val m = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(1000) { m.add(EventRecord.random()) }
        repeat(500) { m.remove(EventRecord.random()) }
        bh.consume(m.a)
    }

    // ── JournalSeries CRUD ────────────────────────────────────────────────────

    @Benchmark
    fun journal_create(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(EventRecord.random()) }
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_read_by_index(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(EventRecord.random()) }
        repeat(1000) { bh.consume(j[it % 1000]) }
    }

    @Benchmark
    fun journal_update_by_index(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(EventRecord.random()) }
        repeat(1000) { j[it % 1000] = EventRecord.random() }
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_delete_at_tail(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(EventRecord.random()) }
        repeat(500) { j.removeAt(j.a - 1) }
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_commit_rollback(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(EventRecord.random()) }
        j.commit()
        repeat(500) { j.add(EventRecord.random()) }
        j.rollback()
        bh.consume(j.a)
    }

    // ── ChunkedMutableSeries CRUD ─────────────────────────────────────────────

    @Benchmark
    fun chunked_create(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(EventRecord.random()) }
        bh.consume(c.a)
    }

    @Benchmark
    fun chunked_read_by_index(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(EventRecord.random()) }
        repeat(1000) { bh.consume(c[it % 1000]) }
    }

    @Benchmark
    fun chunked_update_by_index(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(EventRecord.random()) }
        repeat(1000) { c[it % 1000] = EventRecord.random() }
        bh.consume(c.a)
    }

    @Benchmark
    fun chunked_delete_at_tail(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(EventRecord.random()) }
        repeat(500) { c.removeAt(c.a - 1) }
        bh.consume(c.a)
    }

    @Benchmark
    fun chunked_delete_at_head(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(EventRecord.random()) }
        repeat(500) { c.removeAt(0) }
        bh.consume(c.a)
    }

    // ── 5-layer burrito CRUD — full pipeline ─────────────────────────────────

    @Benchmark
    fun burrito_crud_cycle(bh: Blackhole) {
        // Build chain: RingSeries → ChunkedMutableSeries → MergeMutableSeries → JournalSeries → RecursiveMutableSeries
        val layer5_rms = RecursiveMutableSeries.create<EventRecord>()
        val layer4_journal = JournalSeries<VmCrudBenchmarks.EventRecord>()
        val layer3_merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val layer2_chunked = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        val layer1_ring = RingSeries<EventRecord>(4096)

        // Populate via ring (simulate firehose absorption)
        repeat(5000) { i ->
            layer1_ring.add(EventRecord.random())
            // Drain to chunked
            if (i > 0 && i % 256 == 0) {
                repeat(layer1_ring.a) { layer2_chunked.add(layer1_ring[it]) }
                layer1_ring.clear()
            }
        }
        if (layer2_chunked.a > 0) {
            repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
            layer2_chunked.clear()
            layer3_merge.flush()
        }

        // Read from merge (simulate downstream consumption)
        val totalMergeSize = layer3_merge.a
        repeat(totalMergeSize) { bh.consume(layer3_merge[it % totalMergeSize]) }

        // Update some records in-place at journal layer
        repeat(100) { i ->
            if (i < layer4_journal.a) {
                layer4_journal[i % layer4_journal.a] = EventRecord.random()
            }
        }

        // Delete from RMS layer
        repeat(100) {
            if (layer5_rms.a > 0) layer5_rms.removeAt(layer5_rms.a - 1)
        }

        bh.consume(layer4_journal.a)
    }
}