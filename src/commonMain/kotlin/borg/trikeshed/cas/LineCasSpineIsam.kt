package borg.trikeshed.cas

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.IsamOperations
import borg.trikeshed.isam.defaultIsamOperations
// The ISAM codec enum, not the cursor package's same-named one: the writer refuses any other ("No ISAM codec for spine").
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * The [LineCasIndex] as an appendable ISAM cursor: one row per indexed line, appended as its spine is ingested and
 * never rewritten, so retention grows with the lines indexed, not with the number of ingests. A couch reader takes
 * the rows as a faceted cursor; restore re-ingests the spines in row order, each spine's id derived again from its lines.
 *
 * Row (fixed width, 48 bytes): `spine` int (ingest sequence), `ordinal` int, `stamp` int (prev byte shl 8 or next
 * byte), `c0`..`c3` long (the line's SHA-256, big endian), `code` int.
 */
class LineCasSpineIsam(
    val datafile: String,
    private val files: FileOperations,
    private val operations: IsamOperations = defaultIsamOperations(),
) {
    private var next = -1

    /** Append [spine]'s lines as the next spine. */
    fun append(spine: LineSpine) {
        if (spine.size == 0) return
        val s = sequence()
        IsamDataFile.append(spine.view.map { n -> row(s, n) }.asIterable(), datafile, operations = operations)
        next = s + 1
    }

    /** The index the appended rows describe; null when nothing has been appended. */
    fun restore(): LineCasIndex? {
        val rows = open() ?: return null
        val index = LineCasIndex()
        var spine = -1
        var lines = ArrayList<LineNode>()
        fun flush() { if (lines.isNotEmpty()) { val done = lines; index.ingestSpine(done.size j { done[it] }) }; lines = ArrayList() }
        try {
            for (i in 0 until rows.a) {
                val r = rows.b(i)
                val s = r[0].a as Int
                if (s != spine) { flush(); spine = s }
                val stamp = r[2].a as Int
                val hex = (3..6).joinToString("") { (r[it].a as Long).toULong().toString(16).padStart(16, '0') }
                lines.add(LineNode(ContentId("sha256:$hex"), NeighborStamp(byteHex(stamp ushr 8) + byteHex(stamp and 0xFF)),
                    r[1].a as Int, r[7].a as Int))
            }
            flush()
            next = spine + 1
        } finally { rows.close() }
        return index
    }

    private fun sequence(): Int {
        if (next < 0) next = open()?.let { rows -> try { if (rows.a == 0) 0 else (rows.b(rows.a - 1)[0].a as Int) + 1 } finally { rows.close() } } ?: 0
        return next
    }

    private fun open(): IsamDataFile? {
        if (!files.exists("$datafile.meta")) return null
        return IsamDataFile(datafile, operations = operations).also { it.open() }
    }

    private fun row(spine: Int, n: LineNode): RowVec {
        val h = n.contentCid.hex
        val stamp = (n.prevHex.toInt(16) shl 8) or n.nextHex.toInt(16)
        val values = arrayOf<Any?>(spine, n.ordinal, stamp,
            h.substring(0, 16).toULong(16).toLong(), h.substring(16, 32).toULong(16).toLong(),
            h.substring(32, 48).toULong(16).toLong(), h.substring(48, 64).toULong(16).toLong(), n.code)
        return values.size j { i: Int -> values[i] j SCHEMA[i] }
    }

    private fun byteHex(b: Int): String = b.toString(16).padStart(2, '0')

    private companion object {
        val SCHEMA: Array<`ColumnMeta↻`> = arrayOf(
            "spine" to IOMemento.IoInt, "ordinal" to IOMemento.IoInt, "stamp" to IOMemento.IoInt,
            "c0" to IOMemento.IoLong, "c1" to IOMemento.IoLong, "c2" to IOMemento.IoLong, "c3" to IOMemento.IoLong,
            "code" to IOMemento.IoInt,
        ).map { (name, type) -> val f: `ColumnMeta↻` = { ColumnMeta(name, type) }; f }.toTypedArray()
    }
}
