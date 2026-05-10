package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.size
import java.nio.file.Path

/**
 * JVM-only file I/O for BlockRowVec.
 *
 * Writes a sealed block as NDJSON (one JSON object per line, header line first).
 * Reads back by parsing each line through [MiniDuckBlockCodec].
 *
 * @throws IllegalStateException if the block is not sealed
 */
object MiniDuckBlockFiles {

    /** Write a sealed block to an NDJSON file. */
    fun write(path: Path, block: BlockRowVec) {
        check(block.state == BlockRowVec.State.SEALED) {
            "Cannot write a mutable block — call seal() first"
        }
        val text = MiniDuckBlockCodec.encode(block)
        path.toFile().writeText(text)
    }

    /** Read a block from an NDJSON file. */
    fun read(path: Path): BlockRowVec {
        val text = path.toFile().readText()
        val decoded = MiniDuckBlockCodec.decode(text)
        val normalized = BlockRowVec.mutable()
        for (i in 0 until decoded.child.size) {
            normalized.append(normalizeRow(decoded.child[i]))
        }
        return normalized.seal()
    }

    private fun normalizeRow(row: RowVec, parentKind: String? = null): RowVec = when (row) {
        is JsonRowVec -> {
            val normalizedChild = row.child?.let { ch -> ch.size j { i: Int -> normalizeRow(ch[i], "doc") } }
            if (parentKind == "blob") row else DocRowVec(listOf(row.nodeType), listOf(row.rawValue), normalizedChild)
        }
        is DocRowVec -> DocRowVec(
            row.keys.toList(),
            row.cells.toList(),
            row.child?.let { ch -> ch.size j { i: Int -> normalizeRow(ch[i], "doc") } },
        )
        is ViewRowVec -> {
            val normalizedChild = row.child?.let { ch -> ch.size j { i: Int -> normalizeRow(ch[i], "doc") } }
            ViewRowVec(row.id, row.key, row.value) {
                normalizedChild?.get(0) ?: JsonRowVec("empty", null)
            }
        }
        else -> row
    }
}
