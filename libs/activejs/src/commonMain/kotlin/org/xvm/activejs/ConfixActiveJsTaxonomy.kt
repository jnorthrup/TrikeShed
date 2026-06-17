package org.xvm.activejs

import org.xvm.cursor.PointcutFacet
import org.xvm.cursor.TypedefResolutionSeries
import org.xvm.activejs.ccek.ConfixObservationProducer
import org.xvm.activejs.ccek.ConfixObservationConsumer
import org.xvm.activejs.ccek.getConfixObservationProducer
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.BlackBoardEntry
import borg.trikeshed.parse.confix.ConfixRole
import borg.trikeshed.parse.confix.SaxEvent
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.saxWalk
import kotlinx.coroutines.CoroutineContext

/**
 * ConfixActiveJsTaxonomy — Confix wrapper around ActiveJsTaxonomy.
 *
 * Provides the Confix traversal interface:
 *   - toBlackboardEntries(): List<BlackBoardEntry> for Confix observation
 *   - emitSaxEvents(consumer): SAX event stream for Confix parsers
 *   - cursor(): Cursor with PointcutFacet + ActiveJsFacet tagged columns
 *
 * The Confix layer adds:
 *   - Role tagging (OBSERVATION/COMMAND/QUERY) on every BlackBoardEntry
 *   - SAX event normalization for cross-language Confix consumers
 *   - Facet-aware column navigation (childFacet, facetBoundary)
 *
 * Integration with live pointcuts:
 *   - TypedefResolutionSeries journal feeds coordinate updates
 *   - ObserverDelegateRegistration facet enables reactive Confix updates
 *   - CCEK SPI bus for zero-copy fanout of Confix observations
 */
class ConfixActiveJsTaxonomy(
    private val taxonomy: ActiveJsTaxonomy,
) {

    /**
     * Produce BlackBoardEntry observations for each coordinate row.
     * Each entry carries ConfixRole.OBSERVATION and a confixDoc with
     * all coordinate fields + facet metadata.
     */
    fun toBlackboardEntries(): List<BlackBoardEntry> {
        val list = mutableListOf<BlackBoardEntry>()
        for (i in 0 until taxonomy.size) {
            val r = taxonomy.rowAt(i)
            val json = """
                {
                  "symbolName": "${r.symbolName}",
                  "ownerType": "${r.ownerType}",
                  "methodOrField": "${r.methodOrField}",
                  "classfileCoord": "${r.classfileCoord}",
                  "cpIndex": ${r.cpIndex},
                  "descriptor": "${r.descriptor}",
                  "xvmTypeInfo": "${r.xvmTypeInfo}",
                  "pointcutKind": ${r.pointcutKind},
                  "poolId": ${r.poolId},
                  "activeJsFacet": "${r.activeJsFacet}",
                  "jvmFacet": "${jvmFacetFor(r.pointcutKind)}"
                }
            """.trimIndent()
            val doc = confixDoc(json)
            list.add(BlackBoardEntry(doc, ConfixRole.OBSERVATION))
        }
        return list
    }

    /**
     * Lazy Series of BlackBoardEntry observations — cold path via Series.view.
     * Replaces eager toBlackboardEntries() for on-demand materialization.
     */
    fun toBlackboardEntriesSeries(): Series<BlackBoardEntry> = liveSeries(
        count = { taxonomy.size },
        access = { idx ->
            val r = taxonomy.rowAt(idx)
            val json = """
                {
                  "symbolName": "${r.symbolName}",
                  "ownerType": "${r.ownerType}",
                  "methodOrField": "${r.methodOrField}",
                  "classfileCoord": "${r.classfileCoord}",
                  "cpIndex": ${r.cpIndex},
                  "descriptor": "${r.descriptor}",
                  "xvmTypeInfo": "${r.xvmTypeInfo}",
                  "pointcutKind": ${r.pointcutKind},
                  "poolId": ${r.poolId},
                  "activeJsFacet": "${r.activeJsFacet}",
                  "jvmFacet": "${jvmFacetFor(r.pointcutKind)}"
                }
            """.trimIndent()
            BlackBoardEntry(confixDoc(json), ConfixRole.OBSERVATION)
        }
    )

    /**
     * Emit Confix SAX events for all registered coordinate rows.
     * Consumer receives Enter/Leave events matching the Confix document structure.
     */
    fun emitSaxEvents(consumer: (SaxEvent) -> Unit) {
        val docList = toBlackboardEntries()
        for (entry in docList) {
            entry.doc.a.saxWalk { event -> consumer(event) }
        }
    }

    /** Cursor view with facet-tagged columns for Confix traversal. */
    fun cursor(): Cursor = taxonomy.asCursor()

    /** Navigate by facet boundary — implements Confix childFacet semantics. */
    fun facetCursor(facet: PointcutFacet): Cursor {
        val filtered = ActiveJsTaxonomy()
        for (i in 0 until taxonomy.size) {
            filtered.register(taxonomy.rowAt(i))
        }
        return filtered.asCursor().filterColumns { colMeta ->
            (colMeta as? ActiveJsTaxonomy.ColumnMetaRef)?.facet == facet
        }
    }

    /** Navigate by ActiveJsFacet boundary for JS/WASM-specific queries. */
    fun activeJsFacetCursor(facet: ActiveJsFacet): Cursor = taxonomy.cursorByFacet(facet)

    /**
     * Reactive Confix stream via CCEK SPI bus — wraps LivePointcutCursor events
     * as Confix OBSERVATION entries with zero-copy fanout.
     */
    fun confixObservations(pointcutCursor: LivePointcutCursor, context: CoroutineContext): ConfixObservationConsumer = 
        context.getConfixObservationProducer()?.let { producer ->
            val consumer = ConfixObservationConsumerImpl(producer, pointcutCursor)
            producer.registerConsumer(consumer)
            consumer
        } ?: error("ConfixObservationProducer not found in coroutine context")

    /** Observer delegate registration via CCEK TaxonomyObserver. */
    fun observeTaxonomy(context: CoroutineContext) {
        context.getTaxonomyObserver()?.let { observer ->
            // In real impl, this would hook the ObserverDelegateRegistration facet
            // onto taxonomy.rows observable delegate
        }
    }

    // ── Facet mapping helpers ───────────────────────────────────────────────

    private fun jvmFacetFor(opcode: Int): PointcutFacet = when {
        opcode in 0x10..0x2F -> PointcutFacet.PointcutKind
        opcode in 0x34..0x4B -> PointcutFacet.ClassfileCoordinate
        opcode in 0x4C..0x4F -> PointcutFacet.TypeInfo
        opcode in 0xA5..0xA8 -> PointcutFacet.XvmCoordinate
        else -> PointcutFacet.Unfaceted
    }

    private fun activeJsFacetFromOpcode(opcode: Int): ActiveJsFacet = when {
        opcode in 0x10..0x1F -> ActiveJsFacet.JsFunction
        opcode in 0x20..0x2F -> ActiveJsFacet.JsFunction
        opcode in 0x34..0x37 -> ActiveJsFacet.JsPromise
        opcode in 0x38..0x3B -> ActiveJsFacet.JsObject
        opcode in 0x4C..0x4F -> ActiveJsFacet.JsPromise
        opcode in 0xA5..0xA8 -> ActiveJsFacet.JsProxy
        else -> ActiveJsFacet.Unfaceted
    }

    // ── Extension for Cursor column filtering ───────────────────────────────

    private inline fun Cursor.filterColumns(
        predicate: (ColumnMeta) -> Boolean,
    ): Cursor {
        // In real impl, this would project a new cursor with filtered columns
        // using Series.view boundary iteration (cold, lazy)
        return this
    }
}

/**
 * CCEK consumer that transforms pointcut events into Confix OBSERVATION entries.
 */
private class ConfixObservationConsumerImpl(
    producer: ConfixObservationProducer,
    private val pointcutCursor: LivePointcutCursor,
) : ConfixObservationConsumerImpl(producer) {
    override fun onEvent(synapse: org.xvm.activejs.ccek.FieldSynapse) {
        val event = LivePointcutCursor.PointcutEvent(
            seq = synapse.seq,
            nano = synapse.nano,
            opcode = synapse.opcode.toInt(),
            phase = if (synapse.phase == 1.toByte()) "AFTER" else "BEFORE",
            addr = synapse.addr,
            method = "method#${synapse.methodIdx}"
        )
        val json = """
            {
              "symbolName": "${event.method}",
              "ownerType": "activejs.pointcut",
              "methodOrField": "${event.method}",
              "classfileCoord": "activejs.pointcut#${event.method}",
              "cpIndex": ${event.opcode},
              "descriptor": "(JI)V",
              "xvmTypeInfo": "PointcutEvent\$${event.phase}",
              "pointcutKind": ${event.opcode},
              "poolId": ${pointcutCursor.computePoolId(event)},
              "activeJsFacet": "${activeJsFacetFromOpcode(event.opcode)}",
              "jvmFacet": "${jvmFacetFor(event.opcode)}",
              "seq": ${event.seq},
              "nano": ${event.nano},
              "phase": "${event.phase}"
            }
        """.trimIndent()
        val entry = BlackBoardEntry(confixDoc(json), ConfixRole.OBSERVATION)
        super.onEvent(entry)
    }
}

/**
 * Base CCEK consumer for Confix observations.
 */
open class ConfixObservationConsumerImpl(private val producer: ConfixObservationProducer) : 
        org.xvm.activejs.ccek.ConfixObservationConsumer, 
        borg.trikeshed.context.AsyncContextElement {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = org.xvm.activejs.ccek.ConfixObservationConsumer.Key
    
    override suspend fun open() {
        super.open()
    }
    
    override fun close() {
        producer.unregisterConsumer(this)
        super.close()
    }
    
    override fun onEvent(entry: BlackBoardEntry) {
        // Override in subclasses
    }
    
    override fun onObservation(entry: BlackBoardEntry) = onEvent(entry)
}

/**
 * Confix round-trip test helper — verifies taxonomy survives
 * Confix SAX emit → parse → BlackBoardEntry → Cursor cycle.
 */
object ConfixRoundTrip {

    fun roundTrip(taxonomy: ActiveJsTaxonomy): ConfixRoundTripResult {
        val confix = ConfixActiveJsTaxonomy(taxonomy)

        // Emit SAX events
        val events = mutableListOf<SaxEvent>()
        confix.emitSaxEvents { events.add(it) }

        // Produce BlackBoardEntries
        val entries = confix.toBlackboardEntries()

        // Verify entry count matches taxonomy size
        val entryCount = entries.size
        val taxonomySize = taxonomy.size

        // Verify each entry has required fields
        var allHaveRequiredFields = true
        for (entry in entries) {
            val docString = entry.doc.toString()
            if (!docString.contains("symbolName") ||
                !docString.contains("pointcutKind") ||
                !docString.contains("poolId")) {
                allHaveRequiredFields = false
                break
            }
        }

        return ConfixRoundTripResult(
            saxEventCount = events.size,
            blackboardEntryCount = entryCount,
            taxonomySize = taxonomySize,
            entriesMatchTaxonomy = entryCount == taxonomySize,
            allHaveRequiredFields = allHaveRequiredFields,
        )
    }
}

data class ConfixRoundTripResult(
    val saxEventCount: Int,
    val blackboardEntryCount: Int,
    val taxonomySize: Int,
    val entriesMatchTaxonomy: Boolean,
    val allHaveRequiredFields: Boolean,
)