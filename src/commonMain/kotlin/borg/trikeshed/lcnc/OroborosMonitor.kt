package borg.trikeshed.lcnc

import kotlin.time.TimeSource

/** A bounded JSON projection of runtime state, never ownership of guest values. */
typealias OroborosState = Map<String, Any?>

/**
 * An attachable observation ring owned by the debugger's synchronization boundary.
 *
 * Following [LcncTrail], loss is reported explicitly. The 250 ms bins match the existing
 * CuratorScope rate display. Logging admits [eventsPerBin] detailed events per bin; further
 * observations only increment the bin and cumulative counters. Explicit spiking admits one
 * representative per bin. The next bin can log again, so a burst cannot permanently disable detail.
 *
 * `persistent` describes an effect the caller has confirmed at its persistence seam. It must not
 * be inferred from a node output or blackboard notification. Neither this ring nor its snapshots
 * are a durable journal, and aggregate counts cannot reconstruct omitted mutation effects.
 */
class OroborosMonitor(
    val capacity: Int = 256,
    val binCapacity: Int = 120,
    val eventsPerBin: Int = 64,
    val binMillis: Long = 250,
    clock: (() -> Long)? = null,
) {
    init {
        require(capacity in 1..4096)
        require(binCapacity in 1..1024)
        require(eventsPerBin in 1..4096)
        require(binMillis > 0)
    }

    private val origin = TimeSource.Monotonic.markNow()
    private val clock = clock ?: { origin.elapsedNow().inWholeMilliseconds }
    private val events = arrayOfNulls<OroborosState>(capacity)
    private val bins = arrayOfNulls<Bin>(binCapacity)
    private var recorded = 0L
    private var observed = 0L
    private var persistent = 0L
    private var aggregated = 0L
    private var valueTruncations = 0L
    private var epoch = 1L
    private var attached = true
    private var scope: String? = null
    private var pointcut: String? = null
    private var spiking = false
    private var time = 0L

    private class Bin(val ordinal: Long) {
        var count = 0L
        var persistent = 0L
        var aggregated = 0L
    }

    /** A new attachment starts a new observation interval; detach preserves its retained evidence. */
    fun attach(scope: String? = null, pointcut: String? = null, spiking: Boolean = false) {
        require(scope == null || scope.length <= ID_CHARS) { "scope exceeds monitor identity limit" }
        require(pointcut == null || pointcut.length <= ID_CHARS) { "pointcut exceeds monitor identity limit" }
        this.scope = scope
        this.pointcut = pointcut
        this.spiking = spiking
        attached = true
        recorded = 0
        observed = 0
        persistent = 0
        aggregated = 0
        valueTruncations = 0
        events.fill(null)
        bins.fill(null)
        epoch++
    }

    fun detach() { attached = false }

    fun observe(
        scope: String,
        pointcut: String,
        kind: String,
        before: OroborosState = emptyMap(),
        after: OroborosState = emptyMap(),
        persistent: Boolean = false,
    ) {
        if (!attached) return
        val selectedScope = this.scope
        if (!selectedScope.isNullOrEmpty() && scope != selectedScope && !scope.startsWith("$selectedScope/")) return
        if (this.pointcut != null && pointcut != this.pointcut) return

        val now = now()
        val ordinal = now / binMillis
        val slot = (ordinal % binCapacity).toInt()
        val bin = bins[slot]?.takeIf { it.ordinal == ordinal } ?: Bin(ordinal).also { bins[slot] = it }
        observed++
        bin.count++
        if (persistent) { this.persistent++; bin.persistent++ }
        if (bin.count > if (spiking) 1L else eventsPerBin.toLong()) {
            aggregated++
            bin.aggregated++
            return
        }

        val projection = Projection()
        val beforeState = projection.state(before)
        val afterState = projection.state(after)
        val truncated = projection.truncated ||
            scope.length > ID_CHARS || pointcut.length > ID_CHARS || kind.length > ID_CHARS
        if (truncated) valueTruncations++
        events[(recorded % capacity).toInt()] = mapOf(
            "sequence" to observed,
            "timeMillis" to now,
            "scope" to scope.take(ID_CHARS),
            "pointcut" to pointcut.take(ID_CHARS),
            "kind" to kind.take(ID_CHARS),
            "persistent" to persistent,
            "before" to beforeState,
            "after" to afterState,
            "truncated" to truncated,
        )
        recorded++
    }

    /** Lists here are the JSON boundary; all retained state has fixed event/bin/value budgets. */
    fun snapshot(): OroborosState {
        val ordinal = now() / binMillis
        val current = bins[(ordinal % binCapacity).toInt()]?.takeIf { it.ordinal == ordinal }
        val first = (recorded - capacity).coerceAtLeast(0)
        val retained = (first until recorded).map { events[(it % capacity).toInt()]!! }
        val rates = bins.filterNotNull().filter { it.ordinal > ordinal - binCapacity && it.ordinal <= ordinal }
            .sortedBy { it.ordinal }.map { bin ->
                mapOf(
                    "startMillis" to bin.ordinal * binMillis,
                    "count" to bin.count,
                    "persistent" to bin.persistent,
                    "aggregated" to bin.aggregated,
                )
            }
        return mapOf(
            "epoch" to epoch,
            "attached" to attached,
            "mode" to if (spiking || (current?.aggregated ?: 0) > 0) "spiking" else "logging",
            "requestedMode" to if (spiking) "spiking" else "logging",
            "scope" to scope,
            "pointcut" to pointcut,
            "observed" to observed,
            "persistent" to persistent,
            "aggregated" to aggregated,
            "overwritten" to first,
            "valueTruncations" to valueTruncations,
            "complete" to (aggregated == 0L && first == 0L && valueTruncations == 0L),
            "coverage" to "attached runtime observations",
            "durableLog" to false,
            "eventCapacity" to capacity,
            "binCapacity" to binCapacity,
            "binMillis" to binMillis,
            "eventsPerBin" to eventsPerBin,
            "events" to retained,
            "bins" to rates,
        )
    }

    private fun now(): Long = clock().coerceAtLeast(time).also { time = it }

    /** Copies only bounded primitive state. Opaque guest objects are never rendered or retained. */
    private class Projection {
        var truncated = false
        private var chars = STATE_CHARS
        private var entries = STATE_ENTRIES

        fun state(value: OroborosState): OroborosState = fields(value, 0)

        private fun text(value: String, limit: Int = VALUE_CHARS): String {
            val count = minOf(value.length, limit, chars)
            if (count < value.length) truncated = true
            chars -= count
            return value.take(count)
        }

        private fun fields(value: Map<*, *>, depth: Int): OroborosState {
            val result = mutableMapOf<String, Any?>()
            for ((key, item) in value) {
                if (entries == 0 || chars == 0) { truncated = true; break }
                entries--
                if (key !is String) { truncated = true; continue }
                val name = text(key, ID_CHARS)
                result[name] = value(item, depth + 1)
            }
            return result
        }

        private fun value(item: Any?, depth: Int): Any? = when (item) {
            null, is Boolean, is Byte, is Short, is Int, is Long -> item
            is Double -> if (item.isFinite()) item else text(item.toString())
            is Float -> if (item.isFinite()) item else text(item.toString())
            is Char -> text(item.toString())
            is String -> text(item)
            else -> when {
                depth >= STATE_DEPTH -> omitted()
                item is Map<*, *> -> fields(item, depth)
                item is Collection<*> -> sequence(item.iterator(), depth)
                item is Array<*> -> sequence(item.iterator(), depth)
                else -> omitted()
            }
        }

        private fun sequence(iterator: Iterator<*>, depth: Int): List<Any?> {
            val result = mutableListOf<Any?>()
            while (iterator.hasNext()) {
                if (entries == 0 || chars == 0) { truncated = true; break }
                entries--
                result.add(value(iterator.next(), depth + 1))
            }
            return result
        }

        private fun omitted(): String { truncated = true; return text("<unexpanded>") }
    }

    companion object {
        const val ID_CHARS = 512
        const val VALUE_CHARS = 512
        const val STATE_CHARS = 8192
        const val STATE_ENTRIES = 64
        const val STATE_DEPTH = 4
    }
}
