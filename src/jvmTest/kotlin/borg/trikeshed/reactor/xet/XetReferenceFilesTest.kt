package borg.trikeshed.reactor.xet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks against xet-team/xet-spec-reference-files. Set XET_REF_DIR to a directory holding
 * Electric_Vehicle_Population_Data_20250917.csv (63.5 MB), its `.chunks`, `.xet-file-hash`,
 * `.xet-xorb-hash` and the xorb's `.range-hash`. Passes without checking anything when the variable is unset.
 */
class XetReferenceFilesTest {
    @Test
    fun matchesReferenceFiles() {
        val dir = System.getenv("XET_REF_DIR")?.let(::File) ?: return
        val base = "Electric_Vehicle_Population_Data_20250917.csv"
        val data = File(dir, base).readBytes()
        val exp = File(dir, "$base.chunks").readLines().map { it.split(" ") }
        val spans = XetChunker.chunk(data)
        assertEquals(exp.map { it[1].toInt() }, spans.map { it.length })
        val nodes = XetHashing.chunkNodes(data, spans)
        assertEquals(exp.map { it[0] }, nodes.map { XetHashing.toApiHex(it.hash) })
        val xorb = File(dir, "$base.xet-xorb-hash").readText().trim()
        assertEquals(xorb, XetHashing.toApiHex(XetHashing.merkleRoot(nodes)))
        assertEquals(File(dir, "$base.xet-file-hash").readText().trim(), XetHashing.toApiHex(XetHashing.fileHash(nodes)))
        val range = File(dir, "$xorb.xorb.range-hash").readText().trim()
        assertEquals(range, XetHashing.toApiHex(XetHashing.verificationHash(nodes.map { it.hash })))
        assertTrue(spans.all { it.length <= XetChunker.MAX_CHUNK_SIZE })
    }
}
