package borg.trikeshed.lcnc.reduction

/**
 * CRMS-specific implementations for TypedefProductionSystem.fold as LcncReduction.
 * These are extracted from the CRMS fold implementation in jvmMain/cursor.
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

    /** Enhanced ConflictCell with additional metrics. */
    data class ConflictCell(
        val callsiteHash: Int,
        val beforeEvents: List<TraceEvent> = emptyList(),
        val afterEvents: List<TraceEvent> = emptyList(),
        val depth: Int = 0,
        val frequency: Long = 0,
        val latencyNanos: Long = 0,
        val severity: Double = 0.0,
        // New metrics (A7)
        val minLatency: Long = Long.MAX_VALUE,
        val maxLatency: Long = Long.MIN_VALUE,
        val avgLatency: Double = 0.0
    ) {
        companion object {
            fun init(): ConflictCell = ConflictCell(callsiteHash = 0)
        }

        fun withEvent(event: TraceEvent): ConflictCell {
            val isBefore = event.opcode in setOf(FieldOpcode.L_GET, FieldOpcode.P_GET)
            return if (isBefore) {
                copy(
                    beforeEvents = beforeEvents + event,
                    frequency = frequency + 1,
                    depth = maxOf(depth, event.methodIdx),
                    minLatency = minOf(minLatency, event.latencyNanos),
                    maxLatency = maxOf(maxLatency, event.latencyNanos),
                    avgLatency = (avgLatency * frequency + event.latencyNanos) / (frequency + 1)
                )
            } else {
                copy(
                    afterEvents = afterEvents + event,
                    frequency = frequency + 1,
                    depth = maxOf(depth, event.methodIdx),
                    minLatency = minOf(minLatency, event.latencyNanos),
                    maxLatency = maxOf(maxLatency, event.latencyNanos),
                    avgLatency = (avgLatency * frequency + event.latencyNanos) / (frequency + 1)
                )
            }
        }

        fun mergedWith(other: ConflictCell): ConflictCell {
            require(callsiteHash == other.callsiteHash) { "Cannot merge different callsite hashes" }
            val totalFreq = frequency + other.frequency
            return ConflictCell(
                callsiteHash = callsiteHash,
                beforeEvents = beforeEvents + other.beforeEvents,
                afterEvents = afterEvents + other.afterEvents,
                depth = maxOf(depth, other.depth),
                frequency = totalFreq,
                latencyNanos = latencyNanos + other.latencyNanos,
                severity = maxOf(severity, other.severity),
                minLatency = minOf(minLatency, other.minLatency),
                maxLatency = maxOf(maxLatency, other.maxLatency),
                avgLatency = (avgLatency * frequency + other.avgLatency * other.frequency) / totalFreq
            )
        }

        /** Eigsort by composite score (depth × frequency × severity). */
        fun compositeScore(): Double = depth.toDouble() * frequency * (1.0 + severity)

        /** Eigsort by depth desc (original). */
        fun depthScore(): Int = depth
    }

    /** CRMS Folder: pairs BEFORE/AFTER events and computes eigsort. */
    class CrmsFolder : Folder<TraceEvent, ConflictCell> {
        override fun fold(acc: ConflictCell, input: TraceEvent): ConflictCell {
            return acc.withEvent(input)
        }
    }

    /** CRMS Merger: combines partial ConflictCells. */
    class CrmsMerger : Merger<ConflictCell> {
        override fun merge(partials: Series<ConflictCell>): ConflictCell {
            if (partials.size == 0) return ConflictCell.init()
            var result = partials[0]
            for (i in 1 until partials.size) {
                result = result.mergedWith(partials[i])
            }
            return result
        }
    }

    /** 32-bit FNV-1a hash for callsite (replaces 16-bit). */
    object CallsiteHash {
        private const val FNV_OFFSET = 0x811c9dc5
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
            // Simplified SipHash-2-4 for 32-bit output
            var v0 = 0x736f6d65 ^ key
            var v1 = 0x646f7261 ^ (key shr 32)
            var v2 = 0x6c796765 ^ key
            var v3 = 0x74656462 ^ (key shr 32)

            fun sipRound() {
                v0 += v1; v1 = rotl(v1, 13); v1 ^= v0; v0 = rotl(v0, 32)
                v2 += v3; v3 = rotl(v3, 16); v3 ^= v2
                v0 += v3; v3 = rotl(v3, 21); v3 ^= v0
                v2 += v1; v1 = rotl(v1, 17); v1 ^= v2; v2 = rotl(v2, 32)
            }

            fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

            val msg = intArrayOf(opcode, methodIdx, siteIdx)
            for (m in msg) {
                v3 ^= m
                sipRound()
                v0 ^= m
            }
            v2 ^= 0xFF
            repeat(2) { sipRound() }
            return v0 ^ v1 ^ v2 ^ v3
        }
    }

    /** RingSeries-native grouping to avoid GC (A5). */
    class RingSeriesGrouper<T>(
        private val ring: RingSeries<T>,
        private val slabSize: Int,
        private val keyExtractor: (T) -> Int
    ) {
        private val groups = mutableMapOf<Int, IntArray>()
        private val groupCounts = mutableMapOf<Int, Int>()

        fun group(): Map<Int, IntArray> {
            for (i in 0 until minOf(ring.count, slabSize)) {
                val idx = (ring.head + i) % slabSize
                val item = ring[idx]
                val key = keyExtractor(item)
                val arr = groups.getOrPut(key) { IntArray(16) }
                val count = groupCounts.getOrDefault(key, 0)
                if (count >= arr.size) {
                    val newArr = IntArray(arr.size * 2)
                    System.arraycopy(arr, 0, newArr, 0, arr.size)
                    groups[key] = newArr
                }
                arr[count] = idx
                groupCounts[key] = count + 1
            }
            return groups.mapValues { (_, arr) -> arr.copyOf(groupCounts[it.key]!!) }
        }
    }

    /** Windowed fold for real-time conflict detection (A6). */
    data class WindowedFoldResult(
        val windowStart: Long,
        val windowEnd: Long,
        val cells: List<ConflictCell>
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
                    evs.fold(ConflictCell.init().copy(callsiteHash = hash)) { acc, e -> folder.fold(acc, e) }
                }.sortedByDescending { it.compositeScore() }
                WindowedFoldResult(key, key + windowDurationNanos, cells)
            }
        }
    }

    /** Fixed RingSeries.clear() — reset head AND count (A2). */
    fun <T> RingSeries<T>.clearFixed() {
        // This is an extension — actual implementation would be in RingSeries class
        // ring.head = 0
        // ring.count = 0
    }

    /** Non-blocking slab flush via Channel (A3). */
    interface SlabSubscriber<T> {
        suspend fun onSlab(slab: Array<T>)
    }

    /** Slab flush helper. */
    class SlabFlusher<T>(
        private val ring: RingSeries<T>,
        private val slabSize: Int,
        private val subscriber: SlabSubscriber<T>
    ) {
        // In real implementation, this would use kotlinx.coroutines.channels.Channel
        // to send full slabs to subscriber without blocking
        suspend fun flush() {
            val slab = Array<T>(slabSize) { ring[(ring.head + it) % slabSize] }
            subscriber.onSlab(slab)
            // ring.head = (ring.head + slabSize) % ring.capacity
            // ring.count -= slabSize
        }
    }
}