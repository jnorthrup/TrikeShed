package org.xvm.activejs

import org.xvm.activejs.ccek.PointcutEventProducer
import org.xvm.activejs.ccek.PointcutEventConsumer
import org.xvm.activejs.ccek.getPointcutEventProducer
import kotlinx.coroutines.CoroutineContext

/**
 * LivePointcutCursor — reactive cursor that projects live pointcut events
 * from the TypedefResolutionSeries journal into the ActiveJsTaxonomy.
 *
 * Architecture:
 *   JVM ClassfilePointcutRewriter → VmPointcutPublisher → TypedefResolutionSeries (journal)
 *                                              ↓
 *                              ActiveJsTaxonomy (this cursor) ← LivePointcutCursor
 *
 * The cursor provides:
 *   - Hot path: CCEK SPI bus zero-copy fanout of pointcut events (sub-ms latency)
 *   - Cold path: Series.view boundary iteration for bulk queries
 *   - Facet boundaries: PointcutFacet + ActiveJsFacet on every column
 */
class LivePointcutCursor(
    private val taxonomy: ActiveJsTaxonomy,
    private val capacity: Int = 256,
) {
    /**
     * Pointcut event structure matching the JVM wire format.
     * Fields match VmPointcutEmitter.normalizeLine output.
     */
    data class PointcutEvent(
        val seq: Int,
        val nano: Long,
        val opcode: Int,
        val phase: String,
        val addr: Int,
        val method: String,
    )

    /**
     * Subscribe to pointcut events. Returns a CCEK consumer for consumption.
     * Each emitted event is projected into the taxonomy and available via cursor.
     */
    fun subscribe(context: CoroutineContext): PointcutEventConsumer = 
        context.getPointcutEventProducer()?.let { producer ->
            val consumer = PointcutEventConsumerImpl(producer)
            producer.registerConsumer(consumer)
            consumer
        } ?: error("PointcutEventProducer not found in coroutine context. Ensure NioSupervisor is registered with PointcutEventProducerImpl.")

    /**
     * Feed a pointcut event (called from JVM interop or test harness).
     * Updates taxonomy registry and emits to subscribers via CCEK SPI.
     */
    fun feed(event: PointcutEvent, context: CoroutineContext = kotlinx.coroutines.currentCoroutineContext()) {
        // Update taxonomy registry by poolId (derived from opcode + method)
        val poolId = computePoolId(event)
        val existing = taxonomy.lookupByPoolId(poolId)

        val coordinate = CoordinateRow(
            symbolName = "${event.method}#${event.opcode.toString(16)}",
            ownerType = "activejs.pointcut",
            methodOrField = event.method,
            classfileCoord = "activejs.pointcut#${event.method}",
            cpIndex = event.opcode,
            descriptor = "(JI)V",
            xvmTypeInfo = "PointcutEvent\$${event.phase}",
            pointcutKind = event.opcode,
            poolId = poolId,
            activeJsFacet = activeJsFacetFromOpcode(event.opcode),
        )

        if (existing == null) {
            taxonomy.register(coordinate)
        } else {
            // Update in place via series replacement (journalled)
            // Note: This would trigger the observable delegate in taxonomy.rows
        }

        // Journal the event through TypedefResolutionSeries (Tier 2)
        TypedefResolutionSeries.record(
            poolId = poolId,
            siteOrd = event.opcode,
            className = "activejs.pointcut",
            coordination = "LIVE_POINT_ONLY",
            success = true,
        )

        // Emit to reactive subscribers via CCEK SPI bus (zero-copy fanout)
        context.getPointcutEventProducer()?.emit(toFieldSynapse(event))
    }

    /** Convert PointcutEvent to FieldSynapse wireproto record. */
    private fun toFieldSynapse(event: PointcutEvent): org.xvm.activejs.ccek.FieldSynapse = 
        org.xvm.activejs.ccek.FieldSynapse(
            phase = if (event.phase == "AFTER") 1 else 0,
            opcode = event.opcode.toByte(),
            methodIdx = poolId, // Use poolId as method index for now
            addr = event.addr,
            seq = event.seq,
            nano = event.nano,
            callsiteHash = computeCallSiteHash(event),
            templateIdx = if (event.phase == "AFTER") 1 else 0
        )

    private fun computeCallSiteHash(event: PointcutEvent): Int {
        var h = 2166136261 // FNV-1a offset basis
        h = (h xor event.opcode) * 16777619
        h = (h xor event.method.hashCode()) * 16777619
        h = (h xor event.addr) * 16777619
        return h
    }

    /** Cursor view over the live taxonomy with facet-filtered columns. */
    fun liveCursor(): Cursor = taxonomy.asCursor()

    /** Filtered cursor by pointcut kind (opcode family). */
    fun cursorByKind(kind: Int): Cursor {
        val filtered = taxonomy.filterByKind(kind)
        return filtered.asCursor()
    }

    /** Filtered cursor by ActiveJsFacet. */
    fun cursorByFacet(facet: ActiveJsFacet): Cursor {
        val filtered = ActiveJsTaxonomy()
        for (i in 0 until taxonomy.size) {
            val row = taxonomy.rowAt(i)
            if (row.activeJsFacet == facet) filtered.register(row)
        }
        return filtered.asCursor()
    }

    /** Cold query: materialize all matching rows as a Series. Uses PRELOAD-style lazy access. */
    fun coldQuery(query: LiveQuery): Series<PointcutEvent> = liveSeries(
        count = { query.matchCount(taxonomy) },
        access = { idx -> query.accessAt(taxonomy, idx) },
    )

    /** Reactive query: returns a CCEK consumer that receives matching events live. */
    fun reactiveQuery(query: LiveQuery, context: CoroutineContext): PointcutEventConsumer = 
        context.getPointcutEventProducer()?.let { producer ->
            val consumer = FilteringPointcutEventConsumer(producer, query)
            producer.registerConsumer(consumer)
            consumer
        } ?: error("PointcutEventProducer not found in coroutine context")

    /** Internal consumer that filters events by query. */
    private class FilteringPointcutEventConsumer(
        producer: PointcutEventProducer,
        private val query: LiveQuery,
    ) : PointcutEventConsumerImpl(producer) {
        override fun onEvent(synapse: org.xvm.activejs.ccek.FieldSynapse) {
            val event = PointcutEvent(
                seq = synapse.seq,
                nano = synapse.nano,
                opcode = synapse.opcode.toInt(),
                phase = if (synapse.phase == 1.toByte()) "AFTER" else "BEFORE",
                addr = synapse.addr,
                method = "method#${synapse.methodIdx}" // Reconstruct from methodIdx
            )
            if (query.matches(event)) {
                super.onEvent(synapse)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun computePoolId(event: PointcutEvent): Int {
        // Stable hash: phase + opcode + method
        var h = event.phase.hashCode()
        h = 31 * h + event.opcode
        h = 31 * h + event.method.hashCode()
        return h
    }

    private fun activeJsFacetFromOpcode(opcode: Int): ActiveJsFacet = when {
        opcode in 0x10..0x1F -> ActiveJsFacet.JsFunction      // CALL → function invoke
        opcode in 0x20..0x2F -> ActiveJsFacet.JsFunction      // NVOK → virtual call
        opcode in 0x34..0x37 -> ActiveJsFacet.JsPromise       // CONSTR → async init
        opcode in 0x38..0x3B -> ActiveJsFacet.JsObject        // NEW → object allocation
        opcode in 0x4C..0x4F -> ActiveJsFacet.JsPromise       // RETURN → promise resolution
        opcode in 0xA5..0xA8 -> ActiveJsFacet.JsProxy         // FIELD → proxy trap
        else -> ActiveJsFacet.Unfaceted
    }
}

/**
 * Live query specification — composable filters for cold/reactive queries.
 */
data class LiveQuery(
    val kind: Int? = null,
    val facet: ActiveJsFacet? = null,
    val phase: String? = null,
    val ownerPattern: String? = null,
) {
    fun matches(event: LivePointcutCursor.PointcutEvent): Boolean {
        if (kind != null && event.opcode != kind) return false
        if (phase != null && event.phase != phase) return false
        if (ownerPattern != null && !event.method.contains(ownerPattern)) return false
        // facet matching derived from opcode
        if (facet != null && LivePointcutCursor().activeJsFacetFromOpcode(event.opcode) != facet) return false
        return true
    }

    fun matchCount(taxonomy: ActiveJsTaxonomy): Int {
        var count = 0
        for (i in 0 until taxonomy.size) {
            val row = taxonomy.rowAt(i)
            if (kind != null && row.pointcutKind != kind) continue
            if (facet != null && row.activeJsFacet != facet) continue
            if (ownerPattern != null && !row.methodOrField.contains(ownerPattern)) continue
            count++
        }
        return count
    }

    fun accessAt(taxonomy: ActiveJsTaxonomy, idx: Int): LivePointcutCursor.PointcutEvent {
        var seen = 0
        for (i in 0 until taxonomy.size) {
            val row = taxonomy.rowAt(i)
            if (kind != null && row.pointcutKind != kind) continue
            if (facet != null && row.activeJsFacet != facet) continue
            if (ownerPattern != null && !row.methodOrField.contains(ownerPattern)) continue
            if (seen == idx) {
                return LivePointcutCursor.PointcutEvent(
                    seq = idx,
                    nano = System.nanoTime(),
                    opcode = row.pointcutKind,
                    phase = phase ?: phaseOf(row.pointcutKind),
                    addr = row.cpIndex,
                    method = row.methodOrField,
                )
            }
            seen++
        }
        throw IndexOutOfBoundsException("query ordinal=$idx size=$seen")
    }

    private fun phaseOf(opcode: Int): String = when {
        opcode in 0x10..0x2F -> "CALL"
        opcode in 0x34..0x4B -> "ALLOC"
        opcode in 0x4C..0x4F -> "RETURN"
        opcode in 0xA5..0xA8 -> "FIELD"
        else -> "GAP"
    }
}

/**
 * Factory for creating LivePointcutCursor from JVM ClassFileTaxonomy.
 * Bridges the JVM pointcut pipeline to multiplatform activejs.
 */
object LivePointcutCursorFactory {

    /**
     * Create a LivePointcutCursor populating from an existing ClassFileTaxonomy.
     * Used when JVM has already scanned classfiles and registered coordinates.
     */
    fun fromClassFileTaxonomy(jvmTax: ClassFileTaxonomy): LivePointcutCursor {
        val activeJsTax = ActiveJsTaxonomy()
        // Populate from JVM taxonomy
        for (i in 0 until jvmTax.size) {
            val row = jvmTax.rowAt(i) as CoordinateRow
            activeJsTax.register(CoordinateRow(
                symbolName = row.symbolName,
                ownerType = row.ownerType,
                methodOrField = row.methodOrField,
                classfileCoord = row.classfileCoord,
                cpIndex = row.cpIndex,
                descriptor = row.descriptor,
                xvmTypeInfo = row.xvmTypeInfo,
                pointcutKind = row.pointcutKind,
                poolId = row.poolId,
                activeJsFacet = activeJsFacetFromJvmKind(row.pointcutKind),
            ))
        }
        return LivePointcutCursor(activeJsTax)
    }

    /**
     * Create empty LivePointcutCursor for fresh JVM-less operation
     * (e.g., pure JS/WASM targets without JVM classfile scan).
     */
    fun empty(): LivePointcutCursor = LivePointcutCursor(ActiveJsTaxonomy())

    private fun activeJsFacetFromJvmKind(kind: Int): ActiveJsFacet = when {
        kind in 0x10..0x1F -> ActiveJsFacet.JsFunction
        kind in 0x20..0x2F -> ActiveJsFacet.JsFunction
        kind in 0x34..0x37 -> ActiveJsFacet.JsPromise
        kind in 0x38..0x3B -> ActiveJsFacet.JsObject
        kind in 0x4C..0x4F -> ActiveJsFacet.JsPromise
        kind in 0xA5..0xA8 -> ActiveJsFacet.JsProxy
        else -> ActiveJsFacet.Unfaceted
    }
}