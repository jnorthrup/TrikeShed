package borg.trikeshed.miniduck

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
        return MiniDuckBlockCodec.decode(text)
    }
}
