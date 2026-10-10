package borg.trikeshed.userspace.benchmark

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.ISAM_LEAF_BYTES
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.UringIsamOperations
import borg.trikeshed.isam.getGroupFilename
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlin.time.TimeSource

/** SplitMix64: the event generator shared with the Rust contender (cocaine-rats `loom-isam`). */
class SplitMix64(var state: Long) {
    fun next(): Long {
        state += -7046029254386353131L // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    fun below(m: Long): Long = (next() ushr 1) % m
}

/** Reservation events (loom-mesh customer.rs fields), one meta-v2 group per column. */
class LedgerEvents(val rows: Int) {
    val id = ByteArray(rows * 16)
    val customers = ByteArray(512 * 16)
    val customer = IntArray(rows)
    val hold = LongArray(rows)
    val charge = LongArray(rows)
    val created = LongArray(rows)
    val completed = LongArray(rows)
    val state = ByteArray(rows)
    val meta: Array<RecordMeta>

    init {
        val r = SplitMix64(11)
        fun put(dst: ByteArray, at: Int, v: Long) { for (k in 0 until 8) dst[at + k] = (v ushr (8 * k)).toByte() }
        for (c in 0 until 512) { put(customers, c * 16, r.next()); put(customers, c * 16 + 8, r.next()) }
        var t = 1_791_000_000L
        for (i in 0 until rows) {
            t += r.below(5)
            put(id, i * 16, r.next()); put(id, i * 16 + 8, r.next())
            customer[i] = r.below(512).toInt()
            val st = r.below(3)
            val h = 1000 + r.below(4_999_000)
            hold[i] = h
            charge[i] = if (st == 1L) r.below(h + 1) else 0L
            created[i] = if (r.below(100) == 0L) t - (1 + r.below(39_999)) else t
            completed[i] = if (st == 0L) 0L else t + 1 + r.below(899)
            state[i] = st.toByte()
        }
        val names = arrayOf("id", "customer_id", "hold_micro_usd", "charge_micro_usd", "created_at", "completed_at", "state")
        val types = arrayOf(IOMemento.IoByteArray, IOMemento.IoByteArray, IOMemento.IoLong, IOMemento.IoLong, IOMemento.IoLong, IOMemento.IoLong, IOMemento.IoByte)
        val widths = intArrayOf(16, 16, 8, 8, 8, 8, 1)
        var off = 0
        meta = Array(names.size) { c ->
            RecordMeta(names[c], types[c], off, off + widths[c]).also { it.groupId = c; it.groupName = names[c]; off += widths[c] }
        }
    }

    fun value(i: Int, c: Int): Any = when (c) {
        0 -> id.copyOfRange(i * 16, i * 16 + 16)
        1 -> customers.copyOfRange(customer[i] * 16, customer[i] * 16 + 16)
        2 -> hold[i]
        3 -> charge[i]
        4 -> created[i]
        5 -> completed[i]
        else -> state[i]
    }

    /** Whether [v] is row [i]'s value of column [c]. */
    fun same(i: Int, c: Int, v: Any?): Boolean = when (c) {
        0 -> (v as ByteArray).same(id, i * 16)
        1 -> (v as ByteArray).same(customers, customer[i] * 16)
        2 -> v as Long == hold[i]
        3 -> v as Long == charge[i]
        4 -> v as Long == created[i]
        5 -> v as Long == completed[i]
        else -> v as Byte == state[i]
    }

    val cursor: Cursor get() = rows j { i: Int -> meta.size j { c: Int -> value(i, c) j meta[c].`↺` } }
}

fun ByteArray.same(src: ByteArray, at: Int): Boolean {
    for (k in indices) if (this[k] != src[at + k]) return false
    return true
}

/** Writes [rows] events as a leaf ISAM at [path], reads them back, and returns the receipt. */
fun ledgerBenchmark(path: String, rows: Int, crossRead: String? = null): Map<String, Any?> {
    val clock = TimeSource.Monotonic
    var mark = clock.markNow()
    val events = LedgerEvents(rows)
    val generateMs = mark.elapsedNow().inWholeMilliseconds
    mark = clock.markNow()
    UringIsamOperations(leafBytes = ISAM_LEAF_BYTES).write(events.cursor, path, emptyMap(), false)
    val writeMs = mark.elapsedNow().inWholeMilliseconds
    var bytes = 0L
    for (m in events.meta) {
        val file = if (m.groupId == events.meta.size - 1) path else getGroupFilename(path, m.groupName)
        val channel = FileChannel.open(file, setOf(StandardOpenOption.READ))
        try { bytes += channel.size() } finally { channel.close() }
    }
    val receipt = linkedMapOf<String, Any?>("impl" to "trikeshed", "rows" to rows, "generateMs" to generateMs, "writeMs" to writeMs, "bytes" to bytes)
    for ((label, file) in listOfNotNull("self" to path, crossRead?.let { "cross" to it })) {
        val isam = IsamDataFile(file)
        isam.open()
        try {
            require(isam.size == rows) { "$label holds ${isam.size} rows" }
            mark = clock.markNow()
            var bad = 0
            for (i in 0 until rows) {
                val row = isam[i]
                for (c in 0 until 7) if (!events.same(i, c, row[c].a)) { bad++; break }
            }
            val scanMs = mark.elapsedNow().inWholeMilliseconds
            val createdAt = isam["created_at"]
            mark = clock.markNow()
            var sum = 0L
            for (i in 0 until rows) sum += createdAt[i][0].a as Long
            val projectionMs = mark.elapsedNow().inWholeMilliseconds
            val picks = SplitMix64(3)
            mark = clock.markNow()
            var randomSum = 0L
            repeat(20_000) { randomSum += createdAt[picks.below(rows.toLong()).toInt()][0].a as Long }
            val randomMs = mark.elapsedNow().inWholeMilliseconds
            val fence = isam.fence("created_at")
            val lo = events.created[rows / 2]
            val hi = lo + 600
            var candidates = 0
            var found = 0
            for (k in 0 until fence.size) {
                val leaf = fence[k]
                if ((leaf.b.a as Long) > hi || (leaf.b.b as Long) < lo) continue
                candidates++
                for (r in leaf.a) if ((createdAt[r][0].a as Long) in lo..hi) found++
            }
            val truth = events.created.count { it in lo..hi }
            receipt[label] = linkedMapOf("badRows" to bad, "scanMs" to scanMs, "projectionMs" to projectionMs, "createdSum" to sum,
                "random20kMs" to randomMs, "randomSum" to randomSum, "leaves" to fence.size, "fenceCandidates" to candidates,
                "fenceRows" to found, "truthRows" to truth)
        } finally { isam.close() }
    }
    return receipt
}

fun ledgerBenchmarkMain(args: Array<String>) {
    val path = args.getOrElse(0) { "/tmp/ledger-trikeshed.bin" }
    val rows = args.getOrNull(1)?.toInt() ?: 1_000_000
    println(jsonOf(ledgerBenchmark(path, rows, args.getOrNull(2))))
}
