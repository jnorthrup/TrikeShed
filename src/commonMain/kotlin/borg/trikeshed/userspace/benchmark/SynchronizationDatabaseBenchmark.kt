package borg.trikeshed.userspace.benchmark

import borg.trikeshed.context.Synchronization
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.nio.channels.FileChannel
import kotlin.time.TimeSource

/** Fixed-count, owner-serialized ISAM workloads. Every parallel owner needs its own operations and fresh path. */
object SynchronizationDatabaseBenchmark {
    const val COLUMNS = 8
    const val ROW_BYTES = COLUMNS * Long.SIZE_BYTES

    /** All data and metadata channels use the same verified kernel backend and optional per-owner SQE observer. */
    fun nativeOperations(
        synchronization: Synchronization,
        backend: () -> UserspaceChannelBackend,
        trace: UringTrace? = null,
        entries: Int = 256,
    ): UringIsamOperations {
        require(entries > 0)
        val required = UringOp.caps(UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.STATX, UringOp.CLOSE)
        return UringIsamOperations({ path, options ->
            FileChannel.open(path, options, channelFactory = {
                val selected = backend()
                try {
                    check(selected.nativeCapabilities and required == required) {
                        "Database benchmark requires native OPENAT/READ/WRITE/FSYNC/STATX/CLOSE: ${selected.availability}"
                    }
                    FunctionalUringFacade(entries, if (trace == null) selected else UringTraceBackend(selected, trace))
                } catch (failure: Throwable) {
                    try { selected.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            })
        }, readMode = IsamReadMode.CHANNEL, leafBytes = 0, synchronization = synchronization)
    }

    fun value(row: Int, column: Int): Long = if (column == 0) row.toLong() else
        ((row.toLong() + 1) * -7046029254386353131L) xor (column.toLong() * -4658895280553007687L)

    /**
     * Seed creation, reader open, full payload verification and cache warming precede measured phases.
     * Appends force the single 64-byte group once per append call; metadata is read on each call.
     * Scans and point reads use ordinary READ SQEs over a warmed file, with no mmap or application row cache.
     * [counters] must return monotonic cumulative counters for this owner (for example native SQEs and thread CPU).
     * [phaseBoundary] brackets each phase outside worker timing so a host coordinator can align independent owners.
     * The caller owns the fresh fixture directory and removes it only after every owner's reader has closed.
     */
    fun run(
        operations: UringIsamOperations,
        path: String,
        rows: Int,
        batchRows: Int,
        scans: Int,
        randomReads: Int = rows,
        counters: () -> Map<String, Long> = { emptyMap() },
        phaseBoundary: (String, Boolean) -> Unit = { _, _ -> },
    ): Map<String, Any?> {
        require(path.isNotBlank() && rows in 1 until Int.MAX_VALUE && batchRows > 0 && scans > 0 && randomReads > 0)
        require((rows.toLong() + 1) * scans <= Long.MAX_VALUE / ROW_BYTES) { "Scan byte count exceeds receipt capacity" }
        require(operations.leafBytes == 0) { "Benchmark geometry requires an uncompressed single group" }
        val schema = Array(COLUMNS) { column ->
            RecordMeta("word$column", IOMemento.IoLong, column * Long.SIZE_BYTES, (column + 1) * Long.SIZE_BYTES)
        }
        val metadata = Array(COLUMNS) { schema[it].`↺` }
        fun source(first: Int, count: Int): Cursor = count j { index: Int ->
            COLUMNS j { column: Int -> value(first + index, column) j metadata[column] }
        }
        fun measure(name: String, count: Long, action: () -> Long): Map<String, Any?> {
            phaseBoundary(name, true)
            try {
                val before = counters()
                val started = TimeSource.Monotonic.markNow()
                val checksum = action()
                val elapsed = started.elapsedNow().inWholeNanoseconds
                val after = counters()
                val delta = after.mapValues { (key, total) ->
                    (total - (before[key] ?: 0L)).also { check(it >= 0) { "Counter $key is not cumulative" } }
                }
                check(elapsed > 0) { "Monotonic clock did not advance" }
                return mapOf("phase" to name, "rows" to count, "logical_bytes" to count * ROW_BYTES,
                    "elapsed_ns" to elapsed, "rows_per_second" to count * 1_000_000_000.0 / elapsed,
                    "checksum" to checksum, "counters" to delta)
            } finally { phaseBoundary(name, false) }
        }

        // The initial row and metadata force are deliberately excluded from durable append throughput.
        operations.write(source(0, 1), path, emptyMap(), useMonocursorGroupings = false)
        val appendCalls = (rows.toLong() + batchRows - 1) / batchRows
        val append = measure("append", rows.toLong()) {
            var first = 1
            var sum = 0L
            while (first <= rows) {
                val count = minOf(batchRows, rows - first + 1)
                operations.append(source(first, count).view, path, emptyMap(), null, useMonocursorGroupings = false)
                sum += count
                first += count
            }
            sum
        }
        check(append["checksum"] == rows.toLong()) { "Append row count changed" }

        val data = IsamDataFile(path, operations = operations)
        try {
            data.open()
            val totalRows = rows + 1
            check(data.size == totalRows) { "ISAM row count differs from admitted rows" }
            val columns = data.metafile.constraints
            check(columns.size == COLUMNS && columns.view.map { it.groupId }.distinct().size == 1 &&
                groupLength(columns.view.toList()) == ROW_BYTES) { "ISAM payload/group geometry changed" }
            var expected = 0L
            for (row in 0 until totalRows) {
                val actual = data[row]
                for (column in 0 until COLUMNS) {
                    val truth = value(row, column)
                    check(actual[column].a == truth) { "ISAM payload mismatch at $row/$column" }
                    expected += truth
                }
            }
            val scan = measure("scan", totalRows.toLong() * scans) {
                var checksum = 0L
                repeat(scans) {
                    for (row in 0 until totalRows) {
                        val actual = data[row]
                        for (column in 0 until COLUMNS) checksum += actual[column].a as Long
                    }
                }
                checksum
            }
            check(scan["checksum"] == expected * scans) { "Repeated scan checksum changed" }
            var random = 17L
            val indices = IntArray(randomReads) {
                random = (random * 1103515245L + 12345L) and 0x7fffffffL
                (random % totalRows).toInt()
            }
            var pointExpected = 0L
            for (row in indices) for (column in 0 until COLUMNS) pointExpected += value(row, column)
            val point = measure("point_read", randomReads.toLong()) {
                var checksum = 0L
                for (row in indices) {
                    val actual = data[row]
                    for (column in 0 until COLUMNS) checksum += actual[column].a as Long
                }
                checksum
            }
            check(point["checksum"] == pointExpected) { "Point-read checksum changed" }
            return mapOf("synchronization" to operations.synchronization.name, "path" to path,
                "row_bytes" to ROW_BYTES, "columns" to COLUMNS, "groups" to 1, "leaf_bytes" to 0,
                "seed_rows" to 1, "append_rows" to rows, "total_rows" to totalRows,
                "append_batch_rows" to batchRows, "append_calls" to appendCalls,
                "append_batch_kind" to "rows per forced append; sequential row transfers",
                "expected_data_fsyncs" to appendCalls, "scans" to scans,
                "point_reads" to randomReads, "cache" to "warmed OS page cache; READ SQEs; no mmap",
                "measurements" to listOf(append, scan, point))
        } finally { data.close() }
    }
}
