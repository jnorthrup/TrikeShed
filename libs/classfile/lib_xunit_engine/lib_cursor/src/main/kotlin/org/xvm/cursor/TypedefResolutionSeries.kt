package org.xvm.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.Reducer
import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

/**
 * Cold event log for typedef resolution events.
 *
 * frontLine is the event journal.
 * WAL rings are transient buffers only.
 * ReduxMutableSeries is exposed as a cold wrapper over the same event log,
 * but capture APIs below dump raw events rather than reducer-derived state.
 */
data class TypedefFact(
    val factId: Long,
    val nano: Long,
    val poolId: Int,
    val siteOrd: Int,
    val clsName: String,
    val format: String,
    val success: Boolean,
    val isReverted: Boolean = false
)

@Suppress("UNUSED", "UNCHECKED_CAST")
object TypedefResolutionSeries {

    const val RING_SIZE = 256
    const val RING_COUNT = 4

    // Named-tuple field indices
    const val FACTID = 0
    const val NANO = 1
    const val POOLID = 2
    const val SITEORD = 3
    const val CLSNAME_ID = 4
    const val FORMAT_ID = 5
    const val SUCCESS = 6
    const val IS_REVERTED = 7
    const val FIELD_COUNT = 8

    val FIELD_NAMES =  s_[
        "factId", "nano", "poolId", "siteOrd",
        "clsNameId", "formatId", "success", "isReverted"
    ]

    @JvmField
    val LOG_TEMPLATE = TypedefFact(
        factId = -1L,
        nano = 0L,
        poolId = -1,
        siteOrd = -1,
        clsName = "TypedefResolutionSeries.record",
        format = FIELD_NAMES.view.joinToString(","),
        success = true,
        isReverted = false
    )

    // ── State ──────────────────────────────────────────────────────────

    private val nextFactId = java.util.concurrent.atomic.AtomicLong(0)
    private val factIndex = java.util.concurrent.ConcurrentHashMap<Long, TypedefFact>()
    private var frontLine = ChunkedMutableSeries<TypedefFact>(chunkSize = RING_SIZE)
    private var walRings = Array(RING_COUNT) { ChunkedMutableSeries<TypedefFact>(chunkSize = RING_SIZE) }
    private val walIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private object TypedefReducer : Reducer<TypedefFact, Map<Long, TypedefFact>> {
        override val zero: Map<Long, TypedefFact> = emptyMap()
        override fun combine(acc: Map<Long, TypedefFact>, element: TypedefFact): Map<Long, TypedefFact> {
            val mut = acc.toMutableMap()
            if (element.isReverted) {
                mut.remove(element.factId)
            } else {
                mut[element.factId] = element
            }
            return mut
        }
    }

    private fun newJournal() = ReduxMutableSeries<TypedefFact, Map<Long, TypedefFact>>(
        eventJournal = frontLine,
        reducer = TypedefReducer,
        capture = LOG_TEMPLATE
    )

    @get:JvmName("journal")
    var journal = newJournal()
        private set

    // ── Accessors ──────────────────────────────────────────────────────────

    fun factsBySite(poolId: Int, siteOrd: Int): List<TypedefFact> {
        val result = mutableListOf<TypedefFact>()
        for (e in factIndex.values) {
            if (e.poolId == poolId && e.siteOrd == siteOrd) result.add(e)
        }
        return result.sortedBy { it.factId }
    }

    fun factsByPool(poolId: Int): List<TypedefFact> {
        val result = mutableListOf<TypedefFact>()
        for (e in factIndex.values) {
            if (e.poolId == poolId) result.add(e)
        }
        return result.sortedBy { it.factId }
    }

    // ── WAL ────────────────────────────────────────────────────────────────

    private fun flushWalRing() {
        val idx = walIndex.getAndIncrement() % RING_COUNT
        val ring = walRings[idx]
        ring.clear()
    }

    // ── Java API ───────────────────────────────────────────────────────────

    @JvmStatic
    fun record(poolId: Int, siteOrdinal: Int, className: String, formatName: String, success: Boolean): Long {
        val factId = nextFactId.getAndIncrement()
        val nano = java.lang.System.nanoTime()
        val fact = TypedefFact(factId, nano, poolId, siteOrdinal, className, formatName, success)
        factIndex[factId] = fact
        frontLine.add(fact)
        val idx = walIndex.get() % RING_COUNT
        walRings[idx].add(fact)
        val ringSize = walRings[idx].a
        if (ringSize >= RING_SIZE) flushWalRing()
        return factId
    }

    @JvmStatic
    fun fact(factId: Long): Any? = factIndex[factId]

    @JvmStatic
    fun revert(factId: Long): Boolean {
        val f = factIndex.remove(factId) ?: return false
        val comp = f.copy(nano = java.lang.System.nanoTime(), isReverted = true)
        frontLine.add(comp)
        walRings[walIndex.get() % RING_COUNT].add(comp)
        return true
    }

    @JvmStatic
    fun revertSite(poolId: Int, siteOrd: Int): Int {
        val facts = factsBySite(poolId, siteOrd)
        for (f in facts) {
            factIndex.remove(f.factId)
            val comp = f.copy(nano = java.lang.System.nanoTime(), isReverted = true)
            frontLine.add(comp)
            walRings[walIndex.get() % RING_COUNT].add(comp)
        }
        return facts.size
    }

    @JvmStatic
    fun revertPool(poolId: Int): Int {
        val facts = factsByPool(poolId)
        for (f in facts) {
            factIndex.remove(f.factId)
            val comp = f.copy(nano = java.lang.System.nanoTime(), isReverted = true)
            frontLine.add(comp)
            walRings[walIndex.get() % RING_COUNT].add(comp)
        }
        return facts.size
    }

    @JvmStatic
    fun drain(): Int {
        var count = 0
        for (i in 0 until RING_COUNT) {
            val ring = walRings[i]
            val ringSize = ring.a
            if (ringSize > 0) {
                count += ringSize
                ring.clear()
            }
        }
        return count
    }

    @JvmStatic
    fun size(): Int {
        drain()
        return frontLine.a
    }

    @JvmStatic
    fun reset() {
        nextFactId.set(0)
        factIndex.clear()
        frontLine = ChunkedMutableSeries(chunkSize = RING_SIZE)
        walRings = Array(RING_COUNT) { ChunkedMutableSeries(chunkSize = RING_SIZE) }
        walIndex.set(0)
        journal = newJournal()
    }

    @JvmStatic
    fun snapshotEvents(): Series<TypedefFact> {
        drain()
        return frontLine.α { it: TypedefFact -> it.copy() }
    }

    @JvmStatic
    fun reduxJournal(): ReduxMutableSeries<TypedefFact, Map<Long, TypedefFact>> = journal

    @Deprecated("Use reduxJournal(); this is a Redux event journal, not a semantic MetaSeries")
    @JvmStatic
    fun metaSeries(): ReduxMutableSeries<TypedefFact, Map<Long, TypedefFact>> = reduxJournal()

    @JvmStatic
    fun journalTemplate(): TypedefFact = journal.capture

    @JvmStatic
    fun snapshotFacts(): Series<TypedefFact> = snapshotEvents()

    @JvmStatic
    fun toRowVec(): String {
        val events = snapshotEvents()
        val keys = events.α { it.factId }.view.joinToString(",")
        val cells = events.view.joinToString(";") {
            "${it.poolId},${it.siteOrd},${it.clsName},${it.format},${it.success},${it.nano}"
        }
        return "$keys|$cells"
    }
}
