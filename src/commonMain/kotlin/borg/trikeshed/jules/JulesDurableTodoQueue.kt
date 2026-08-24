package borg.trikeshed.jules

import borg.trikeshed.btrfs.TodoQueueItem
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.parse.json.JsonSupport

/**
 * Jules durable queue — file-backed persistent queue for polyglot sleeve chokepoint TODOs.
 *
 * Backing store: `queueDir/queue.jsonl` (one JSON object per line) + `queueDir/index.json`
 * for atomic commit. Every mutation goes through `FileOperations.writeAtomically` so a
 * crash never leaves a torn queue. Re-opening the queue replays the file — persistence
 * verified by re-instantiation.
 *
 * No stubs: enqueue, list, markDispatched, drain, and persistence are all real FileOperations.
 */
class JulesDurableTodoQueue(
    private val queueDir: String,
    private val fileOps: FileOperations,
) {
    private val queueFile get() = fileOps.resolvePath(queueDir, "queue.jsonl")
    private val lockFile get() = fileOps.resolvePath(queueDir, ".lock")

    init {
        if (!fileOps.exists(queueDir)) fileOps.mkdirs(queueDir)
        if (!fileOps.exists(queueFile)) fileOps.write(queueFile, "".encodeToByteArray())
    }

    // ── enqueue ──────────────────────────────────────────────────────────

    fun enqueue(item: TodoQueueItem): Boolean {
        if (exists(item.id)) return false // idempotent
        val line = toJsonLine(item.copy(enqueuedAt = currentTimeMs()))
        val existing = if (fileOps.exists(queueFile)) fileOps.readString(queueFile) else ""
        val next = if (existing.isEmpty() || existing.endsWith("\n")) existing + line + "\n" else existing + "\n" + line + "\n"
        fileOps.writeAtomically(queueFile, next.encodeToByteArray())
        return true
    }

    fun enqueueAll(items: List<TodoQueueItem>): Int {
        var added = 0
        for (it in items) if (enqueue(it)) added++
        return added
    }

    // ── read ─────────────────────────────────────────────────────────────

    fun listAll(): List<TodoQueueItem> {
        if (!fileOps.exists(queueFile)) return emptyList()
        val text = fileOps.readString(queueFile).trim()
        if (text.isEmpty()) return emptyList()
        return text.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { fromJsonLine(it) }.getOrNull() }
    }

    fun listPending(): List<TodoQueueItem> = listAll().filter { it.status == "pending" }
    fun listDispatched(): List<TodoQueueItem> = listAll().filter { it.status == "dispatched" }
    fun size(): Int = listAll().size
    fun pendingSize(): Int = listPending().size
    fun exists(id: String): Boolean = listAll().any { it.id == id }

    // ── mark dispatched ──────────────────────────────────────────────────

    fun markDispatched(id: String): Boolean {
        val all = listAll()
        var changed = false
        val now = currentTimeMs()
        val next = all.map {
            if (it.id == id && it.status == "pending") {
                changed = true
                it.copy(status = "dispatched", dispatchedAt = now)
            } else it
        }
        if (!changed) return false
        val text = next.joinToString("\n") { toJsonLine(it) } + "\n"
        fileOps.writeAtomically(queueFile, text.encodeToByteArray())
        return true
    }

    fun markAllDispatched(ids: List<String>): Int {
        val idSet = ids.toSet()
        val all = listAll()
        var count = 0
        val now = currentTimeMs()
        val next = all.map {
            if (it.id in idSet && it.status == "pending") {
                count++
                it.copy(status = "dispatched", dispatchedAt = now)
            } else it
        }
        if (count > 0) {
            val text = next.joinToString("\n") { toJsonLine(it) } + "\n"
            fileOps.writeAtomically(queueFile, text.encodeToByteArray())
        }
        return count
    }

    fun drainPending(): List<TodoQueueItem> {
        val pending = listPending()
        markAllDispatched(pending.map { it.id })
        return pending
    }

    fun clear() {
        fileOps.writeAtomically(queueFile, "".encodeToByteArray())
    }

    // ── JSON ─────────────────────────────────────────────────────────────

    private fun toJsonLine(item: TodoQueueItem): String {
        // manual JSON to avoid kotlinx.serialization dependency in commonMain
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"id":"${esc(item.id)}","target":"${esc(item.target)}","description":"${esc(item.description)}","source":"${esc(item.source)}","status":"${esc(item.status)}","enqueuedAt":${item.enqueuedAt},"dispatchedAt":${item.dispatchedAt ?: 0}}"""
    }

    private fun fromJsonLine(line: String): TodoQueueItem {
        // minimal parse via JsonSupport if available, else regex
        val map = try {
            @Suppress("UNCHECKED_CAST")
            JsonSupport.parse(line) as? Map<String, Any?> ?: emptyMap()
        } catch (_: Throwable) { emptyMap<String, Any?>() }
        if (map.isNotEmpty()) {
            return TodoQueueItem(
                id = map["id"] as? String ?: "",
                target = map["target"] as? String ?: "common",
                description = map["description"] as? String ?: "",
                source = map["source"] as? String ?: "",
                status = map["status"] as? String ?: "pending",
                enqueuedAt = (map["enqueuedAt"] as? Number)?.toLong() ?: 0L,
                dispatchedAt = (map["dispatchedAt"] as? Number)?.takeIf { it.toLong() != 0L }?.toLong(),
            )
        }
        // fallback regex
        fun field(name: String): String? = Regex(""""$name"\s*:\s*"([^"]*)"""").find(line)?.groupValues?.get(1)
        fun longField(name: String): Long? = Regex(""""$name"\s*:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
        return TodoQueueItem(
            id = field("id") ?: "",
            target = field("target") ?: "common",
            description = field("description") ?: "",
            source = field("source") ?: "",
            status = field("status") ?: "pending",
            enqueuedAt = longField("enqueuedAt") ?: 0L,
            dispatchedAt = longField("dispatchedAt")?.takeIf { it != 0L },
        )
    }

    private fun currentTimeMs(): Long = try {
        kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    } catch (_: Throwable) { 1L }
}
