package borg.trikeshed.lcnc.reduction

import borg.trikeshed.lib.*

/**
 * CRMS-specific implementations for TypedefProductionSystem.fold as LcncReduction.
 * These are extracted from the CRMS fold implementation in jvmMain/cursor.
 *
 * NOTE: this object uses an [EnrichedConflictCell] — a superset of the top-level
 * [ConflictCell] with extra latency metrics — for richer real-time analysis. The
 * top-level [ConflictCell] remains the canonical type consumed by [LcncReductions.crmsFold].
 */
object CrmsReducers {

    /** FieldSynapse opcodes for BEFORE/AFTER phases. */
    object FieldOpcode {
        const val L_GET  = 0xA5  // BEFORE
        const val L_SET  = 0xA6  // AFTER
        const val P_GET  = 0xA7  // BEFORE
        const val P_SET  = 0xA8  // AFTER
    }

    /** Phase enum for CRMS. */
    enum class CrmsPhase { BEFORE, AFTER }

    /** Enhanced ConflictCell with additional latency metrics (untested analysis path). */
    data class EnrichedConflictCell(
        val callsiteHash: Int,
        val beforeEvents: List<TraceEvent> = emptyList(),
        val afterEvents: List<TraceEvent> = emptyList(),
        val depth: Int = 0,
        val frequency: Long = 0,
        val latencyNanos: Long = 0,
        val severity: Double = 0.0,
        val minLatency: Long = Long.MAX_VALUE,
        val maxLatency: Long = Long.MIN_VALUE,
        val avgLatency: Double = 0.0
    ) {
        companion object {
            fun init(): EnrichedConflictCell = EnrichedConflictCell(callsiteHash = 0)
        }

        fun withEvent(event: TraceEvent): EnrichedConflictCell {
            val isBefore = event.opcode in setOf(FieldOpcode.L_GET, FieldOpcode.P_GET)
            val newFreq = frequency + 1
            return if (isBefore) {
                copy(
                    beforeEvents = beforeEvents + event,
                    frequency = newFreq,
                    depth = maxOf(depth, event.methodIdx),
                    minLatency = minOf(minLatency, event.latencyNanos),
                    maxLatency = maxOf(maxLatency, event.latencyNanos),
                    avgLatency = (avgLatency * frequency + event.latencyNanos) / newFreq
                )
            } else {
                copy(
                    afterEvents = afterEvents + event,
                    frequency = newFreq,
                    depth = maxOf(depth, event.methodIdx),
                    minLatency = minOf(minLatency, event.latencyNanos),
                    maxLatency = maxOf(maxLatency, event.latencyNanos),
                    avgLatency = (avgLatency * frequency + event.latencyNanos) / newFreq
                )
            }
        }

        fun mergedWith(other: EnrichedConflictCell): EnrichedConflictCell {
            require(callsiteHash == other.callsiteHash) { "Cannot merge different callsite hashes" }
            val totalFreq = frequency + other.frequency
            return EnrichedConflictCell(
                callsiteHash = callsiteHash,
                beforeEvents = beforeEvents + other.beforeEvents,
                afterEvents = afterEvents + other.afterEvents,
                depth = maxOf(depth, other.depth),
                frequency = totalFreq,
                latencyNanos = latencyNanos + other.latencyNanos,
                severity = maxOf(severity, other.severity),
                minLatency = minOf(minLatency, other.minLatency),
                maxLatency = maxOf(maxLatency, other.maxLatency),
                avgLatency = if (totalFreq == 0L) 0.0 else (avgLatency * frequency + other.avgLatency * other.frequency) / totalFreq
            )
        }

        /** Eigsort by composite score (depth × frequency × severity). */
        fun compositeScore(): Double = depth.toDouble() * frequency * (1.0 + severity)

        /** Eigsort by depth desc (original). */
        fun depthScore(): Int = depth
    }

    /** CRMS Folder: pairs BEFORE/AFTER events and computes eigsort. */
    class CrmsFolder : Folder<TraceEvent, EnrichedConflictCell> {
        override fun fold(acc: EnrichedConflictCell, input: TraceEvent): EnrichedConflictCell =
            acc.withEvent(input)
    }

    /** CRMS Merger: combines partial EnrichedConflictCells. */
    class CrmsMerger : Merger<EnrichedConflictCell> {
        override fun merge(partials: Series<EnrichedConflictCell>): EnrichedConflictCell {
            if (partials.size == 0) return EnrichedConflictCell.init()
            var result = partials[0]
            for (i in 1 until partials.size) {
                result = result.mergedWith(partials[i])
            }
            return result
        }
    }

    /** 32-bit FNV-1a hash for callsite (replaces 16-bit). */
    object CallsiteHash {
        private const val FNV_OFFSET = -2128831035  // 0x811c9dc5
        private const val FNV_PRIME = 0x01000193

        fun hash(opcode: Int, methodIdx: Int, siteIdx: Int): Int {
            var hash = FNV_OFFSET
            hash = (hash xor opcode) * FNV_PRIME
            hash = (hash xor methodIdx) * FNV_PRIME
            hash = (hash xor siteIdx) * FNV_PRIME
            return hash
        }

        /** SipHash-2-4 alternative for better collision resistance. */
        fun sipHash24(opcode: Int, methodIdx: Int, siteIdx: Int, key: Long = 0x0706050403020100): Int {
            var v0 = 0x736f6d65.toInt() xor key.toInt()
            var v1 = 0x646f7261.toInt() xor (key shr 32).toInt()
            var v2 = 0x6c796765.toInt() xor key.toInt()
            var v3 = 0x74656462.toInt() xor (key shr 32).toInt()

            fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

            fun sipRound() {
                v0 += v1; v1 = rotl(v1, 13); v1 = v0 xor v1; v0 = rotl(v0, 32)
                v2 += v3; v3 = rotl(v3, 16); v3 = v2 xor v3
                v0 += v3; v3 = rotl(v3, 21); v3 = v0 xor v3
                v2 += v1; v1 = rotl(v1, 17); v1 = v2 xor v1; v2 = rotl(v2, 32)
            }

            val msg = intArrayOf(opcode, methodIdx, siteIdx)
            for (m in msg) {
                v3 = v3 xor m
                sipRound()
                v0 = v0 xor m
            }
            v2 = v2 xor 0xFF
            repeat(2) { sipRound() }
            return v0 xor v1 xor v2 xor v3
        }
    }

    /** RingSeries-native grouping to avoid GC. Uses Series-level access only. */
    class RingSeriesGrouper<T>(
        private val ring: Series<T>,
        private val keyExtractor: (T) -> Int
    ) {
        private val groups = mutableMapOf<Int, IntArray>()
        private val groupCounts = mutableMapOf<Int, Int>()

        fun group(): Map<Int, IntArray> {
            for (i in 0 until ring.size) {
                val item = ring[i]
                val key = keyExtractor(item)
                val count = groupCounts.getOrDefault(key, 0)
                val arr = groups.getOrPut(key) { IntArray(16) }
                if (count >= arr.size) {
                    val newArr = IntArray(arr.size * 2)
                    System.arraycopy(arr, 0, newArr, 0, arr.size)
                    groups[key] = newArr
                    newArr[count] = i
                } else {
                    arr[count] = i
                }
                groupCounts[key] = count + 1
            }
            return groups.mapValues { (k, arr) -> arr.copyOf(groupCounts[k]!!) }
        }
    }

    /** Windowed fold for real-time conflict detection. */
    data class WindowedFoldResult(
        val windowStart: Long,
        val windowEnd: Long,
        val cells: List<EnrichedConflictCell>
    )

    class WindowedFolder(
        private val windowDurationNanos: Long,
        private val hashExtractor: (TraceEvent) -> Int
    ) {
        private val windows = mutableMapOf<Long, MutableList<TraceEvent>>()

        fun add(event: TraceEvent): List<WindowedFoldResult> {
            val windowKey = (event.timestampNanos / windowDurationNanos) * windowDurationNanos
            windows.getOrPut(windowKey) { mutableListOf() }.add(event)

            // Emit completed windows
            val now = System.nanoTime()
            val completed = windows.keys.filter { it + windowDurationNanos <= now }.sorted()
            return completed.map { key ->
                val events = windows.remove(key)!!
                val folder = CrmsFolder()
                val grouped = events.groupBy { hashExtractor(it) }
                val cells = grouped.map { (hash, evs) ->
                    evs.fold(EnrichedConflictCell.init().copy(callsiteHash = hash)) { acc, e -> folder.fold(acc, e) }
                }.sortedByDescending { it.compositeScore() }
                WindowedFoldResult(key, key + windowDurationNanos, cells)
            }
        }
    }

    /** Non-blocking slab flush via Channel. */
    interface SlabSubscriber<T> {
        suspend fun onSlab(slab: Array<T>)
    }

    /** Slab flush helper. */
    class SlabFlusher<T>(
        private val ring: Series<T>,
        private val slabSize: Int
    ) {
        @Suppress("UNCHECKED_CAST")
        suspend fun flush(subscriber: SlabSubscriber<T>) {
            val slab = arrayOfNulls<Any>(slabSize)
            for (i in 0 until minOf(slabSize, ring.size)) slab[i] = ring[i] as Any
            subscriber.onSlab(slab as Array<T>)
        }
    }
}
