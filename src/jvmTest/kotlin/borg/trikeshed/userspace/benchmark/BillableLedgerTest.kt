package borg.trikeshed.userspace.benchmark

import borg.trikeshed.couch.isam.FileBackedStringpool
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * LOOM Inn billable requests as a meta-v2 leaf ISAM, one group per column: key, payor, operation, model, route and
 * state are dense ids into pools named by the metafile; at_ms, hold and charge are longs. The same rows written with
 * inline fixed-width strings give the on-disk comparison.
 */
class BillableLedgerTest {
    val rows = 200_000
    val names = arrayOf("at_ms", "key", "payor", "operation", "model", "route", "state", "hold_micro_usd", "charge_micro_usd")
    val pooled = 1..6
    val operations = arrayOf("chat", "speech", "images")
    val models = arrayOf(arrayOf("qwen2.5-7b-instruct", "llama-3.1-8b-instruct", "mistral-nemo-12b-instruct"), arrayOf("kokoro"), arrayOf("picX_real"))
    val routes = arrayOf("/v1/chat/completions", "/v1/audio/speech", "/v1/images/generations")
    val states = arrayOf("held", "settled", "released")

    /** Row i's value of pooled column c is text[c - 1][i]. */
    val text = Array(6) { arrayOfNulls<String>(rows) }
    val at = LongArray(rows)
    val hold = LongArray(rows)
    val charge = LongArray(rows)

    init {
        val r = SplitMix64(17)
        fun hex(): String = r.next().toULong().toString(16).padStart(16, '0') + r.next().toULong().toString(16).padStart(16, '0')
        val payors = Array(256) { hex() }
        val keys = Array(1024) { hex() } // key k bills payors[k % 256]
        var t = 1_791_000_000_000L
        for (i in 0 until rows) {
            t += r.below(5)
            val k = r.below(1024).toInt()
            val p = r.below(100)
            val o = if (p < 80) 0 else if (p < 92) 1 else 2
            val s = r.below(3).toInt()
            val h = 1000 + r.below(4_999_000)
            at[i] = t
            hold[i] = h
            charge[i] = if (s == 1) r.below(h + 1) else 0L
            text[0][i] = keys[k]
            text[1][i] = payors[k % 256]
            text[2][i] = operations[o]
            text[3][i] = models[o][r.below(models[o].size.toLong()).toInt()]
            text[4][i] = routes[o]
            text[5][i] = states[s]
        }
    }

    fun meta(type: (Int) -> IOMemento, width: (Int) -> Int): Array<RecordMeta> {
        var off = 0
        return Array(names.size) { c ->
            RecordMeta(names[c], type(c), off, off + width(c)).also { it.groupId = c; it.groupName = names[c]; off = it.end }
        }
    }

    fun write(path: String, meta: Array<RecordMeta>, cell: (Int, Int) -> Any) {
        val cursor: Cursor = rows j { i: Int -> meta.size j { c: Int -> cell(i, c) j meta[c].`↺` } }
        UringIsamOperations(leafBytes = ISAM_LEAF_BYTES).write(cursor, path, emptyMap(), false)
    }

    fun bytes(directory: Path, prefix: String = ""): Long =
        Files.list(directory).use { paths -> paths.filter { it.fileName.toString().startsWith(prefix) }.mapToLong(Files::size).sum() }

    fun long(i: Int, c: Int): Long = when (c) { 0 -> at[i]; 7 -> hold[i]; else -> charge[i] }

    @Test
    fun dictionary_ledger_decodes_through_pools_and_sums_charge_per_payor_and_key() {
        val dictionary = Files.createTempDirectory("billable-dictionary-")
        val inline = Files.createTempDirectory("billable-inline-")
        try {
            val clock = TimeSource.Monotonic
            var mark = clock.markNow()
            val pools = Array(6) { FileBackedStringpool(dictionary.resolve("${names[it + 1]}.pool").toString()) }
            val ids = Array(6) { c -> IntArray(rows) { i -> pools[c].put(text[c][i]!!) } }
            val internMs = mark.elapsedNow().inWholeMilliseconds
            val coded = meta({ c -> if (c in pooled) IOMemento.IoInt else IOMemento.IoLong }, { c -> if (c in pooled) 4 else 8 })
            for (c in pooled) coded[c].pool = pools[c - 1]
            mark = clock.markNow()
            write(dictionary.resolve("ledger.bin").toString(), coded) { i, c -> if (c in pooled) ids[c - 1][i] else long(i, c) }
            val writeMs = mark.elapsedNow().inWholeMilliseconds
            val widths = IntArray(names.size) { c -> if (c in pooled) text[c - 1].maxOf { it!!.length } else 8 }
            write(inline.resolve("ledger.bin").toString(), meta({ c -> if (c in pooled) IOMemento.IoString else IOMemento.IoLong }, widths::get)) { i, c ->
                if (c in pooled) text[c - 1][i]!! else long(i, c)
            }

            val isam = IsamDataFile(dictionary.resolve("ledger.bin").toString())
            isam.open()
            try {
                assertEquals(rows, isam.size)
                mark = clock.markNow()
                var bad = 0
                for (i in 0 until rows) {
                    val row = isam[i]
                    if (row[0].a != at[i] || row[7].a != hold[i] || row[8].a != charge[i]) bad++
                    for (c in pooled) {
                        val cell = row[c]
                        val id = cell.a as Int
                        if (id != ids[c - 1][i] || (cell.b() as RecordMeta).pool!![id] != text[c - 1][i]) bad++
                    }
                }
                val scanMs = mark.elapsedNow().inWholeMilliseconds
                assertEquals(0, bad)

                fun chargeBy(column: String): Int {
                    val projected = isam[column, "charge_micro_usd"]
                    val pool = (projected[0][0].b() as RecordMeta).pool!!
                    val sum = LongArray(pool.size)
                    for (i in 0 until rows) {
                        val row = projected[i]
                        sum[row[0].a as Int] += row[1].a as Long
                    }
                    val truth = HashMap<String, Long>()
                    val c = names.indexOf(column)
                    for (i in 0 until rows) truth.merge(text[c - 1][i]!!, charge[i]) { a, b -> a + b }
                    assertEquals(truth.size, pool.size)
                    for (id in 0 until pool.size) assertEquals(truth[pool[id]], sum[id], "$column ${pool[id]}")
                    return pool.size
                }
                val payors = chargeBy("payor")
                val keys = chargeBy("key")

                val ledger = bytes(dictionary, "ledger")
                val pooledBytes = bytes(dictionary) - ledger
                val inlined = bytes(inline)
                println("billable ledger: $rows rows, $payors payors, $keys keys; intern ${internMs}ms, write ${writeMs}ms, scan ${scanMs}ms")
                println("  dictionary ids: ledger $ledger B + pools $pooledBytes B = ${"%.2f".format((ledger + pooledBytes).toDouble() / rows)} B/row on disk (${coded.last().end} B/row raw)")
                println("  inline strings: $inlined B = ${"%.2f".format(inlined.toDouble() / rows)} B/row on disk (${widths.sum()} B/row raw)")
                assertTrue(ledger + pooledBytes < inlined)
            } finally { isam.close() }
        } finally {
            for (directory in arrayOf(dictionary, inline)) {
                Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(directory)
            }
        }
    }
}
