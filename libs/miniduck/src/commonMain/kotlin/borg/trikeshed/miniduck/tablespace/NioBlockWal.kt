package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.parse.json.JsonParser
import borg.trikeshed.lib.mutable.SeriesBuffer
import borg.trikeshed.lib.view
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.currentCoroutineContext

/**
 * Persistent write-ahead log using [FileOperations] NIO SPI.
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
    private val root: CharSequence,
) {
    private var nextSeq: Long = 0L

    private suspend fun fs(): FileOperations =
        currentCoroutineContext()[FileOperations.Key]
            ?: throw IllegalStateException("FileOperations not in coroutine context")

    val headSequence: Long get() = nextSeq

    /**
     * Append a put operation. Returns the assigned sequence number.
     * The block payload is written as a second NDJSON line.
     */
    suspend fun appendPut(collection: CharSequence, id: CharSequence, block: BlockRowVec): Long {
        nextSeq++
        val encoded = MiniDuckBlockCodec.encode(block)
        val header = """{"seq":$nextSeq,"op":"put","collection":"$collection","id":"$id"}"""
        appendText("$header\n$encoded\n")
        return nextSeq
    }

    /**
     * Append a remove operation. Returns the assigned sequence number.
     */
    suspend fun appendRemove(collection: CharSequence, id: CharSequence): Long {
        nextSeq++
        val entry = """{"seq":$nextSeq,"op":"remove","collection":"$collection","id":"$id"}"""
        appendText("$entry\n")
        return nextSeq
    }

    /**
     * Replay all WAL entries from [startSeq] onto [store].
     */
    suspend fun replay(startSeq: Long, store: BlockStore) {
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
    suspend fun compact(keepFromSeq: Long) {
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
        fs().write(fs().resolvePath(root, "wal.ndjson").toString(), newContent.toString())
    }

    // ── Internal entry parsing ─────────────────────────────────────

    private data class WalEntry(
        val seq: Long,
        val op: CharSequence,
        val collection: CharSequence,
        val id: CharSequence,
        val blockData: CharSequence? = null,
    )

    private suspend fun readEntries(): List<WalEntry> {
        val path = fs().resolvePath(root, "wal.ndjson").toString()
        if (!fs().exists(path)) return emptyList()
        val text = fs().readString(path)
        val lines = text.lines()
        val entries: SeriesBuffer<WalEntry> = SeriesBuffer()
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
                val blockLines: SeriesBuffer<CharSequence> = SeriesBuffer()
                var cursor = i + 1
                while (cursor < lines.size) {
                    val candidate = lines[cursor]
                    if (candidate.isNotBlank() && parseWalHeader(candidate.toString().trim()) != null) break
                    if (candidate.isNotBlank()) blockLines.add(candidate)
                    cursor++
                }
                val joined = blockLines.view.joinToString("\n")
                entries.add(header.copy(blockData = if (joined.isEmpty()) null else joined))
                i = cursor
            } else {
                entries.add(header)
                i++
            }
        }
        val allEntries = entries.toList()
        return allEntries
    }

    private fun parseWalHeader(line: CharSequence): WalEntry? {
        if (!line.startsWith("{")) return null
        return try {
            val m = JsonParser.parse(line)
            val seq = (m["seq"] as? Number)?.toLong() ?: return null
            val op = m["op"] as? CharSequence ?: return null
            val collection = m["collection"] as? CharSequence ?: ""
            val id = m["id"] as? CharSequence ?: ""
            WalEntry(seq, op, collection, id)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun applyEntry(entry: WalEntry, store: BlockStore) {
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

    private suspend fun appendText(text: CharSequence) {
        val path = fs().resolvePath(root, "wal.ndjson").toString()
        val existing = if (fs().exists(path)) fs().readString(path) else ""
        fs().write(path, existing.toString() + text)
    }
}
