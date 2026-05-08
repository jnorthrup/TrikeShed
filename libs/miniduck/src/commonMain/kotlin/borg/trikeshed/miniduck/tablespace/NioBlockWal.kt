package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.contextOf
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Persistent write-ahead log backed by [FileOperations] NIO SPI.
 *
 * Format: JSON-lines (one entry per line).
 *   {"seq":1,"op":"put","collection":"docs","id":"doc1"}
 *   {NDJSON block content on next line for put ops}
 *   ...
 *   {"seq":2,"op":"remove","collection":"docs","id":"doc1"}
 *
 * Replay reads all entries and applies them to a [BlockStore].
 * Compaction rewrites the WAL, keeping only entries after a sequence number.
 */
class NioBlockWal(
    private val root: String,
    private val fs: FileOperations,
) {
    private var nextSeq: Long = 0L
    private val walPath: String get() = fs.resolvePath(root, "wal.ndjson")

    val headSequence: Long get() = nextSeq

    /**
     * Append a put operation. Returns the assigned sequence number.
     * The block payload is written as a second NDJSON line.
     */
    fun appendPut(collection: String, id: String, block: BlockRowVec): Long {
        nextSeq++
        val encoded = MiniDuckBlockCodec.encode(block)
        val header = """{"seq":$nextSeq,"op":"put","collection":"$collection","id":"$id"}"""
        fs.write(walPath, "$header\n$encoded\n")
        return nextSeq
    }

    /**
     * Append a remove operation. Returns the assigned sequence number.
     */
    fun appendRemove(collection: String, id: String): Long {
        nextSeq++
        val entry = """{"seq":$nextSeq,"op":"remove","collection":"$collection","id":"$id"}"""
        fs.write(walPath, "$entry\n")
        return nextSeq
    }

    /**
     * Replay all WAL entries from [startSeq] onto [store].
     */
    fun replay(startSeq: Long, store: BlockStore) {
        val entries = readEntries()
        for (entry in entries) {
            if (entry.seq < startSeq) continue
            applyEntry(entry, store)
        }
    }

    /**
     * Compact the WAL, keeping only entries with seq >= [keepFromSeq].
     * Rewrites the WAL file.
     */
    fun compact(keepFromSeq: Long) {
        val entries = readEntries().filter { it.seq >= keepFromSeq }
        val newContent = StringBuilder()
        for (entry in entries) {
            newContent.append("""{"seq":${entry.seq},"op":"${entry.op}","collection":"${entry.collection}","id":"${entry.id}"}""")
            newContent.append('\n')
            if (entry.op == "put" && entry.blockData != null) {
                newContent.append(entry.blockData)
                newContent.append('\n')
            }
        }
        fs.write(walPath, newContent.toString())
    }

    // ── Internal entry parsing ─────────────────────────────────────

    private data class WalEntry(
        val seq: Long,
        val op: String,
        val collection: String,
        val id: String,
        val blockData: String? = null,
    )

    private fun readEntries(): List<WalEntry> {
        if (!fs.exists(walPath)) return emptyList()
        val text = fs.readString(walPath)
        val lines = text.lines()
        val entries = mutableListOf<WalEntry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i++; continue }
            val header = parseWalHeader(line) ?: run { i++; continue }
            var blockData: String? = null
            if (header.op == "put" && i + 1 < lines.size) {
                val next = lines[i + 1]
                if (next.startsWith("{\"_type\":\"_block\"")) {
                    blockData = next
                    i++ // consume block line
                }
            }
            entries.add(header.copy(blockData = blockData))
            i++
        }
        // Update nextSeq from the last entry
        if (entries.isNotEmpty()) {
            nextSeq = entries.last().seq
        }
        return entries
    }

    private fun parseWalHeader(line: String): WalEntry? {
        if (!line.startsWith("{")) return null
        try {
            val ctx = contextOf(Syntax.JSON, line.toSeries())
            val reified = Combinators.reify(ctx)
            @Suppress("UNCHECKED_CAST")
            val m = reified as? Map<String, Any?> ?: return null
            val seq = (m["seq"] as? Number)?.toLong() ?: return null
            val op = m["op"] as? String ?: return null
            val collection = m["collection"] as? String ?: ""
            val id = m["id"] as? String ?: ""
            return WalEntry(seq, op, collection, id)
        } catch (_: Exception) {
            return null
        }
    }

    private fun applyEntry(entry: WalEntry, store: BlockStore) {
        when (entry.op) {
            "put" -> {
                if (entry.blockData != null) {
                    val block = MiniDuckBlockCodec.decode(entry.blockData)
                    store.putWithId(entry.collection, entry.id, block)
                }
            }
            "remove" -> store.remove(entry.collection, entry.id)
        }
    }

    private fun String.toSeries(): borg.trikeshed.lib.Series<Char> {
        val n = length
        return (n j { i: Int -> this[i] }) as borg.trikeshed.lib.Series<Char>
    }
}
