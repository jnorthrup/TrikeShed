package borg.trikeshed.web.pages

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One model attempt as the mux activity feed reports it; token fields already coerced (non-finite → 0). */
class MuxCall(
    val status: String,
    val cachedHit: Boolean,
    val startedAt: Double,
    val endedAt: Double?,
    val inputTokens: Double,
    val outputTokens: Double,
    val cacheReadTokens: Double,
    val cacheWriteTokens: Double,
)

class MuxTotals(
    val attempts: Int,
    val running: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val cached: Int,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val medianMs: Double?,
    val successRate: Double?,
)

/** A quota pool standing; numeric fields already coerced (non-finite → 0). */
class MuxStanding(
    val limit: Double,
    val spent: Double,
    val windowMs: Double,
    val windowStartMs: Double,
    val exhausted: Boolean,
    val usableFalse: Boolean,
)

class MuxQuota(val known: Boolean, val remaining: Double?, val used: Double?, val resetMs: Double?, val status: String)

class MuxBucket(val at: Double, var completed: Int = 0, var failed: Int = 0, var cached: Int = 0)

/** mux-core.js: totals, quota, buckets, session filter, descendants. */
object MuxCore {
    val ACTIVE: Set<String> = setOf("queued", "running", "joining")

    fun <T> uniqueBy(items: List<T>, key: (T) -> String): List<T> {
        val map = LinkedHashMap<String, T>()
        for (i in items) map[key(i)] = i
        return map.values.toList()
    }

    fun totals(calls: List<MuxCall>): MuxTotals {
        var running = 0; var completed = 0; var failed = 0; var cancelled = 0; var cached = 0
        var input = 0.0; var output = 0.0; var cacheRead = 0.0; var cacheWrite = 0.0
        val latencies = ArrayList<Double>()
        for (call in calls) {
            when (call.status) {
                "running" -> running++
                "completed" -> completed++
                "failed" -> failed++
                "cancelled" -> cancelled++
                "cached" -> cached++
            }
            if (call.cachedHit) cached++
            else {
                input += call.inputTokens; output += call.outputTokens
                cacheRead += call.cacheReadTokens; cacheWrite += call.cacheWriteTokens
            }
            val ended = call.endedAt
            if (call.status == "completed" && !call.cachedHit && ended != null) latencies.add(max(0.0, ended - call.startedAt))
        }
        latencies.sort()
        var median: Double? = null
        if (latencies.isNotEmpty()) {
            val i = latencies.size / 2
            median = if (latencies.size % 2 != 0) latencies[i] else (latencies[i - 1] + latencies[i]) / 2
        }
        val successRate = if (completed + failed != 0) completed.toDouble() / (completed + failed) else null
        return MuxTotals(calls.size, running, completed, failed, cancelled, cached, input, output, cacheRead, cacheWrite, median, successRate)
    }

    fun quota(standing: MuxStanding?, now: Double): MuxQuota {
        if (standing == null) return MuxQuota(false, null, null, null, "unobserved")
        val known = standing.limit > 0
        val expired = standing.windowMs > 0 && now >= standing.windowStartMs + standing.windowMs
        return MuxQuota(
            known,
            if (known) max(0.0, standing.limit - standing.spent) else null,
            if (known) min(1.0, max(0.0, standing.spent / standing.limit)) else null,
            max(0.0, standing.windowStartMs + standing.windowMs - now),
            if (expired) "refreshing" else if (standing.exhausted || standing.usableFalse) "exhausted" else "available",
        )
    }

    fun buckets(calls: List<MuxCall>, minutes: Double, now: Double, count: Int = 30): List<MuxBucket> {
        val span = minutes * 60000
        val start = now - span
        val width = span / count
        val bins = List(count) { MuxBucket(start + it * width) }
        for (c in calls) {
            val at = c.endedAt ?: c.startedAt
            if (at < start || at > now) continue
            val bin = bins[min(count - 1, floor((at - start) / width).toInt())]
            if (c.cachedHit && c.status == "completed") bin.cached++
            else if (c.status == "completed") bin.completed++
            else if (c.status == "failed") bin.failed++
        }
        return bins
    }

    /** filterSessions predicate: [statusFilter] is all / active / failed / archived. */
    fun sessionMatches(title: String, model: String, id: String, archived: Boolean, status: String, query: String, statusFilter: String): Boolean {
        if (if (statusFilter == "archived") !archived else archived) return false
        if (statusFilter == "active" && status !in ACTIVE) return false
        if (statusFilter == "failed" && status !in listOf("failed", "partial", "interrupted")) return false
        return listOf(title, model, id).joinToString(" ").lowercase().contains(query.lowercase())
    }

    /** [id] and every session whose parent chain reaches it; [parentOf] maps session id → parent id. */
    fun descendants(sessions: List<Pair<String, String?>>, id: String): Set<String> {
        val found = linkedSetOf(id)
        var changed = true
        while (changed) {
            changed = false
            for ((sid, parent) in sessions) if (sid !in found && parent != null && parent in found) { found.add(sid); changed = true }
        }
        return found
    }

    fun escape(v: String): String = Kanban.escapeHtml(v)

    private fun roundHalfUp(x: Double): Long = floor(x + 0.5).toLong()

    private fun fixed1(x: Double): String {
        val t = roundHalfUp(x * 10)
        return "${t / 10}.${t % 10}"
    }

    fun duration(ms: Double?): String {
        if (ms == null) return "--"
        if (ms < 1000) return roundHalfUp(ms).toString() + " ms"
        if (ms < 60000) return fixed1(ms / 1000) + " s"
        if (ms < 3600000) return ceil(ms / 60000).toLong().toString() + " min"
        return fixed1(ms / 3600000) + " h"
    }

    private val fence = Regex("```[^\\n]*\\n([\\s\\S]*?)```")

    /** Fenced code blocks become `<pre><code>`; everything is escaped. */
    fun messageContent(text: String): String = buildString {
        var last = 0
        for (m in fence.findAll(text)) {
            append(escape(text.substring(last, m.range.first)))
            append("<pre><code>").append(escape(m.groupValues[1])).append("</code></pre>")
            last = m.range.last + 1
        }
        append(escape(text.substring(last)))
    }
}
