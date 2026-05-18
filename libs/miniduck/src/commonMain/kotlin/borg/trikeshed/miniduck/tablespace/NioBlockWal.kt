package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.parse.json.JsonParser
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
        appendText("$header\n$encoded\n")
        return nextSeq
    }

    /**
     * Append a remove operation. Returns the assigned sequence number.
     */
    fun appendRemove(collection: String, id: String): Long {
        nextSeq++
        val entry = """{"seq":$nextSeq,"op":"remove","collection":"$collection","id":"$id"}"""
        appendText("$entry\n")
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
            val headerLine = lines[i].trim()
            if (headerLine.isEmpty()) {
                i++
                continue
            }
            val header = parseWalHeader(headerLine) ?: run {
                i++
                continue
            }
            if (header.op == "put") {
                val blockLines = mutableListOf<String>()
                var cursor = i + 1
                while (cursor < lines.size) {
                    val candidate = lines[cursor]
                    if (candidate.isNotBlank() && parseWalHeader(candidate.trim()) != null) break
                    if (candidate.isNotBlank()) blockLines += candidate
                    cursor++
                }
                entries += header.copy(blockData = blockLines.joinToString("\n").ifEmpty { null })
                i = cursor
            } else {
                entries += header
                i++
            }
        }
        if (entries.isNotEmpty()) {
            nextSeq = entries.last().seq
        }
        return entries
    }

    private fun parseWalHeader(line: String): WalEntry? {
        if (!line.startsWith("{")) return null
        return try {
            val m = JsonParser.parse(line)
            val seq = (m["seq"] as? Number)?.toLong() ?: return null
            val op = m["op"] as? String ?: return null
            val collection = m["collection"] as? String ?: ""
            val id = m["id"] as? String ?: ""
            WalEntry(seq, op, collection, id)
        } catch (_: Exception) {
            null
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

    private fun appendText(text: String) {
        val existing = if (fs.exists(walPath)) fs.readString(walPath) else ""
        fs.write(walPath, existing + text)
    }

    private fun String.toSeries(): borg.trikeshed.lib.Series<Char> {
        val n = length
        return n j { i: Int -> this[i] }
    }
}
